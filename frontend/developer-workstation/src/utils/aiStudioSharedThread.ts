import type { AiStudioChatMessage } from './aiStudioDraft'
import type { AiStudioThreadImportMessage, AiStudioThreadMessage } from '@/api/aiStudioThread'
import { isFunctionUnitReadOnly } from './permission'

/**
 * 共享线程与浏览器本地消息的合并规则（纯函数，便于单测）。
 *
 * - 后端线程是对话消息的唯一真源：带 serverId 的本地消息一律以后端为准（原对象就地更新，
 *   保住卡片展开状态与"正在 Apply"之类按对象引用的 UI 状态）。
 * - 只属于本浏览器的临时消息（阶段引导语、错误气泡、"已停止"、发送中/发送失败的提问）
 *   记着"发出时线程里最新的 serverId"（anchorId），合并后仍插回原位置。
 * - 既无 serverId 又不是临时消息的，是共享线程上线前留在 localStorage 里的旧对话：
 *   只作为首次迁移的导入素材，后端该阶段有内容后不再显示。
 * - 撤销令牌只在本地：后端确认"是我 Apply 的"时才沿用，否则丢弃。
 */

export function isLocalOnly(m: AiStudioChatMessage): boolean {
  return !m.serverId && (!!m.isPhaseNote || !!m.isError || !!m.localOnly)
}

export function isLegacy(m: AiStudioChatMessage): boolean {
  return !m.serverId && !isLocalOnly(m)
}

/** 线程里最新的 serverId；没有则 0。本地临时消息以它为锚点。 */
export function latestServerId(thread: AiStudioChatMessage[]): number {
  return thread.reduce((max, m) => Math.max(max, m.serverId ?? 0), 0)
}

/** 往线程里追加一条本地临时消息（自动打锚点），返回该消息。 */
export function pushLocal(thread: AiStudioChatMessage[], message: AiStudioChatMessage): AiStudioChatMessage {
  const m = { ...message, anchorId: message.anchorId ?? latestServerId(thread) }
  thread.push(m)
  return thread[thread.length - 1]
}

function applyServerFields(target: AiStudioChatMessage, s: AiStudioThreadMessage): AiStudioChatMessage {
  target.role = s.role === 'USER' ? 'user' : 'assistant'
  target.text = s.content
  target.serverId = s.id
  target.authorName = s.authorName
  target.mine = s.mine
  if (s.proposal && s.proposal.scope) {
    const previous = target.proposal
    // Apply 失败回写到卡上的问题只在本地：后端的预览不知道这次失败，别被刷新冲掉
    const keepLocalIssues = !!previous?.localIssues && !s.proposal.applied
    target.proposal = {
      scope: s.proposal.scope,
      data: s.proposal.data,
      preview: keepLocalIssues ? previous?.preview ?? null : s.proposal.preview,
      localIssues: keepLocalIssues || undefined,
      applied: s.proposal.applied,
      appliedByName: s.proposal.appliedByName,
      appliedByMe: s.proposal.appliedByMe,
      undo: s.proposal.applied && s.proposal.appliedByMe ? previous?.undo ?? null : null
    }
  } else {
    delete target.proposal
  }
  if (s.docSync) target.docSync = s.docSync
  else delete target.docSync
  return target
}

/**
 * 用后端线程刷新本地线程。
 *
 * @param drop 调用方已知被后端版本取代的本地消息（如发送成功后的那条乐观提问）
 */
export function mergeThread(
  local: AiStudioChatMessage[],
  server: AiStudioThreadMessage[],
  drop: AiStudioChatMessage[] = []
): AiStudioChatMessage[] {
  const byServerId = new Map<number, AiStudioChatMessage>()
  for (const m of local) if (m.serverId) byServerId.set(m.serverId, m)

  const merged: AiStudioChatMessage[] = server.map(s =>
    applyServerFields(byServerId.get(s.id) ?? { role: 'assistant', text: '' }, s))

  // 后端为空而本地还有旧对话（迁移前 / 导入失败）：照旧显示，别让用户以为历史丢了
  const keepLegacy = server.length === 0
  const extras = local.filter(m => !drop.includes(m) && (isLocalOnly(m) || (keepLegacy && isLegacy(m))))
  for (const m of extras) {
    const anchor = m.anchorId ?? 0
    // 插在锚点之后、下一条更新的后端消息之前；锚点已被截断掉时落到最前
    let at = 0
    for (let i = 0; i < merged.length; i++) {
      const id = merged[i].serverId
      if (id !== undefined && id <= anchor) at = i + 1
      else if (id === undefined && (merged[i].anchorId ?? 0) <= anchor) at = i + 1
    }
    merged.splice(at, 0, m)
  }
  return merged
}

/** 每阶段保留的后端消息数（与后端 MESSAGES_PER_PHASE_CAP 一致） */
export const SERVER_MESSAGES_PER_PHASE = 50

/**
 * 增量合并：把按 id 拉来的新消息 / 单条更新并入本地线程（原数组原地修改后返回）。
 * 已有的 serverId 就地更新；新的按 id 顺序追加到末尾（新消息 id 一定大于本地已有的）；
 * 后端消息超过上限时丢最旧的，与后端截断一致。
 */
export function appendServerMessages(
  local: AiStudioChatMessage[],
  incoming: AiStudioThreadMessage[],
  drop: AiStudioChatMessage[] = []
): AiStudioChatMessage[] {
  let thread = drop.length ? local.filter(m => !drop.includes(m)) : local
  const byServerId = new Map<number, AiStudioChatMessage>()
  for (const m of thread) if (m.serverId) byServerId.set(m.serverId, m)
  for (const s of [...incoming].sort((a, b) => a.id - b.id)) {
    const existing = byServerId.get(s.id)
    if (existing) {
      applyServerFields(existing, s)
    } else {
      const m = applyServerFields({ role: 'assistant', text: '' }, s)
      thread.push(m)
      byServerId.set(s.id, m)
    }
  }
  const serverCount = thread.filter(m => m.serverId).length
  if (serverCount > SERVER_MESSAGES_PER_PHASE) {
    const cutoff = thread.filter(m => m.serverId).map(m => m.serverId as number)
      .sort((a, b) => a - b)[serverCount - SERVER_MESSAGES_PER_PHASE]
    thread = thread.filter(m => !m.serverId || m.serverId >= cutoff)
  }
  return thread
}

/** 推送断线后的重连等待：1s 起翻倍，封顶 30s，加 ±20% 抖动避免多标签页同时撞上。 */
export function reconnectDelay(attempt: number, random: () => number = Math.random): number {
  const base = Math.min(30000, 1000 * 2 ** Math.max(0, attempt))
  return Math.round(base * (0.8 + random() * 0.4))
}

/** 解析一段 SSE 文本块（`event:` + `data:` 行）；注释行（心跳）与空块返回 null。 */
export function parseSseBlock(block: string): { type: string; data: unknown } | null {
  let type = ''
  const data: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) type = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
  }
  if (!type) return null
  const raw = data.join('\n')
  try {
    return { type, data: raw ? JSON.parse(raw) : {} }
  } catch {
    return { type, data: raw }
  }
}

/** 与后端 AiStudioChatRequest.HistoryMessage.content 的 @Size(max) 一致 */
export const HISTORY_CONTENT_MAX = 4000

export interface CopilotHistoryEntry {
  role: 'USER' | 'ASSISTANT'
  content: string
  proposal?: Record<string, unknown>
  proposalScope?: string
}

/**
 * 发给后端的历史窗口：最近 limit 条真实对话（引导语、错误不算）。
 * 后端对每条内容做非空与长度校验，任何一条不合格整轮都会被拒：
 * 模型没给说明文字的提案卡用 emptyProposalText 补上，仍为空的丢弃，超长的截断。
 */
export function toHistoryEntries(
  thread: AiStudioChatMessage[],
  emptyProposalText: string,
  limit = 10
): CopilotHistoryEntry[] {
  const out: CopilotHistoryEntry[] = []
  for (const m of thread.filter(x => !x.isError && !x.isPhaseNote).slice(-limit)) {
    let content = m.text.trim() || (m.proposal ? emptyProposalText : '')
    if (!content) continue
    if (content.length > HISTORY_CONTENT_MAX) content = content.slice(0, HISTORY_CONTENT_MAX - 1) + '…'
    out.push({
      role: m.role === 'user' ? 'USER' : 'ASSISTANT',
      content,
      // 上一轮的结构化提案一起带回去：否则"把刚才那个模板改一下"模型只看得到自己说过"Here is the proposed change"
      ...(m.role === 'assistant' && m.proposal ? { proposal: m.proposal.data, proposalScope: m.proposal.scope } : {})
    })
  }
  return out
}

/** 旧线程 → 导入请求体；undo 令牌与本地标记不上传。 */
export function toImportMessages(thread: AiStudioChatMessage[] | undefined): AiStudioThreadImportMessage[] {
  return (thread ?? []).filter(isLegacy).filter(m => m.text.trim() || m.proposal).map(m => ({
    role: m.role === 'user' ? 'USER' : 'ASSISTANT',
    content: m.text.trim() ? m.text : '(proposal)',
    proposal: m.role === 'assistant' && m.proposal
      ? { scope: m.proposal.scope, data: m.proposal.data, preview: m.proposal.preview ?? null, applied: !!m.proposal.applied }
      : null
  }))
}

/**
 * 工作台中间设计区是否只读。共享线程可用时以线程状态的 canModify（后端 MODIFY 校验）为准；
 * 两边任一说只读就只读。线程接口不可用时回落到功能单元自身的 canModify
 * （与 FunctionUnitEdit 一致，缺失即只读）；功能单元尚未加载时不提前锁定。
 */
export function isStudioStageReadOnly(
  sharedThreads: boolean,
  canModifyThread: boolean,
  functionUnit: { canModify?: boolean } | null | undefined
): boolean {
  if (sharedThreads && !canModifyThread) return true
  return functionUnit != null && isFunctionUnitReadOnly(functionUnit)
}
