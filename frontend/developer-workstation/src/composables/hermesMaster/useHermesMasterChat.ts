import { ref } from 'vue'
import axios from 'axios'
import { hermesMasterApi } from '@/api/hermesMaster'
import { pickHttpErrorBodyMessage } from '@/utils/httpErrorMessage'
import type { HermesMasterHistoryMessage, HermesMasterMessage } from '@/types/hermesMaster'

/** 与后端 HermesMasterChatRequest 的 @Size 上限一致 */
const MAX_HISTORY = 20
export const HM_MAX_MESSAGE_LENGTH = 4000

export interface HermesMasterChatContext {
  functionUnitId?: number
  page?: string
}

/**
 * Hermes Master 的对话状态。对话只活在当前页面会话里（不落库），路由切换时组件不卸载，所以不会丢。
 */
export function useHermesMasterChat(options: {
  context: () => HermesMasterChatContext
  /** 请求失败且后端没给可读文案时的兜底提示 */
  fallbackError: () => string
}) {
  const messages = ref<HermesMasterMessage[]>([])
  const loading = ref(false)
  let nextId = 1
  let controller: AbortController | null = null

  function push(role: HermesMasterMessage['role'], content: string, error = false) {
    messages.value.push({ id: nextId++, role, content, error })
  }

  function history(): HermesMasterHistoryMessage[] {
    return messages.value
      .filter(m => !m.error)
      .slice(-MAX_HISTORY)
      .map(m => ({ role: m.role, content: m.content.slice(0, HM_MAX_MESSAGE_LENGTH) }))
  }

  /** @returns 本轮是否拿到了回复 */
  async function send(text: string): Promise<boolean> {
    const message = text.trim()
    if (!message || loading.value) return false
    const previous = history()
    push('USER', message)
    loading.value = true
    controller = new AbortController()
    try {
      const res = await hermesMasterApi.chat({ message, history: previous, ...options.context() }, controller.signal)
      push('ASSISTANT', res.data.reply)
      return true
    } catch (e) {
      if (axios.isCancel(e)) return false
      const body = axios.isAxiosError(e) ? pickHttpErrorBodyMessage(e.response?.data) : undefined
      push('ASSISTANT', body || options.fallbackError(), true)
      return false
    } finally {
      loading.value = false
      controller = null
    }
  }

  function stop() {
    controller?.abort()
  }

  function clear() {
    stop()
    messages.value = []
  }

  return { messages, loading, send, stop, clear }
}
