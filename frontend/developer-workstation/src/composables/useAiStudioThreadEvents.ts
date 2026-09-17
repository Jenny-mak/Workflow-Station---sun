import { ref } from 'vue'
import { getUser } from '@/api/auth'
import {
  AI_STUDIO_THREAD_EVENTS_URL,
  type AiStudioThreadEventData,
  type AiStudioThreadEventType
} from '@/api/aiStudioThread'
import { parseSseBlock, reconnectDelay } from '@/utils/aiStudioSharedThread'

export type AiStudioThreadEventHandler = (type: AiStudioThreadEventType, data: AiStudioThreadEventData) => void

/**
 * AI Studio 共享线程的推送连接（SSE）。
 *
 * 用 fetch 读流而不是 EventSource：需要带 X-User-Id 头（与 useAiEvents 同一做法）。
 * 连接到期（后端 5 分钟）或断线后按指数退避自动重连；每次连上后端会先发 READY，
 * 调用方据此按 afterId 补拉断线期间的消息。403/404 视为不可订阅，不再重连。
 */
export function useAiStudioThreadEvents(functionUnitId: () => number, onEvent: AiStudioThreadEventHandler) {
  const connected = ref(false)
  let controller: AbortController | null = null
  let stopped = true
  let attempt = 0
  let retryTimer: ReturnType<typeof setTimeout> | null = null

  function headers(): Record<string, string> {
    const user = getUser()
    return user?.userId ? { 'X-User-Id': String(user.userId), Accept: 'text/event-stream' } : { Accept: 'text/event-stream' }
  }

  async function run() {
    const ctrl = new AbortController()
    controller = ctrl
    let fatal = false
    try {
      const res = await fetch(AI_STUDIO_THREAD_EVENTS_URL(functionUnitId()), {
        method: 'GET', headers: headers(), credentials: 'same-origin', signal: ctrl.signal
      })
      if (res.status === 403 || res.status === 404) {
        fatal = true
        throw new Error(`HTTP ${res.status}`)
      }
      if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`)
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
        const blocks = buffer.split('\n\n')
        buffer = blocks.pop() ?? ''
        for (const block of blocks) {
          const event = parseSseBlock(block)
          if (!event) continue
          if (event.type === 'READY') {
            connected.value = true
            attempt = 0
          }
          try {
            onEvent(event.type as AiStudioThreadEventType, (event.data ?? {}) as AiStudioThreadEventData)
          } catch (e) {
            console.warn('[ai-studio] thread event handler failed', e)
          }
        }
      }
    } catch (e) {
      if (ctrl.signal.aborted) return
      console.warn('[ai-studio] thread event stream disconnected', e)
    } finally {
      if (controller === ctrl) {
        connected.value = false
        controller = null
      }
    }
    if (stopped || fatal) return
    retryTimer = setTimeout(() => {
      retryTimer = null
      if (!stopped) void run()
    }, reconnectDelay(attempt++))
  }

  function start() {
    if (!stopped) return
    stopped = false
    attempt = 0
    void run()
  }

  function stop() {
    stopped = true
    if (retryTimer) clearTimeout(retryTimer)
    retryTimer = null
    controller?.abort()
    controller = null
    connected.value = false
  }

  return { connected, start, stop }
}
