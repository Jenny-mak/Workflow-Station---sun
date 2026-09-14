import type { ComposerTranslation } from 'vue-i18n'

/**
 * AI Studio 草稿契约。
 *
 * 入口弹窗（AiStudioEntryDialog）与未来的 AI Studio 工作台共享这份 localStorage 契约：
 * 工作台每完成一个阶段就写入草稿，入口弹窗读取后展示 "Continue AI draft · 阶段名"。
 * 草稿按功能单元隔离（key 带 functionUnitId），换功能单元不会串草稿。
 */

/**
 * AI Studio 引导阶段，顺序即 UI 展示顺序 = FunctionUnitEdit 设计器 Tab 顺序，
 * 末尾追加 Validation（最终校验门禁）。Tab 顺序变了这里要跟着调。
 */
export const AI_STUDIO_PHASES = [
  'PROCESS_DESIGN',
  'TABLE_DESIGN',
  'FORM_DESIGN',
  'VIEW_DESIGN',
  'ACTION_DESIGN',
  'AUTOMATION',
  'CONNECTIONS',
  'EMAIL_TEMPLATES',
  'EMAIL_MONITORS',
  'DECISION_DESIGN',
  'VALIDATION'
] as const

export type AiStudioPhase = (typeof AI_STUDIO_PHASES)[number]

export interface AiStudioDraft {
  /** 草稿名称，取功能单元名 */
  name: string
  /** 上次停留的阶段 */
  phase: AiStudioPhase
  /** 已确认（Confirm phase）的阶段 */
  completedPhases?: AiStudioPhase[]
  /** ISO 时间戳，工作台写入时更新 */
  updatedAt?: string
}

export type AiStudioEntryMode = 'new' | 'continue'

export interface AiStudioOpenPayload {
  mode: AiStudioEntryMode
  /** mode 为 continue 时必有；new 时为 null */
  draft: AiStudioDraft | null
}

const STORAGE_PREFIX = 'dw-ai-studio-draft:'

export function aiStudioDraftStorageKey(functionUnitId: number): string {
  return `${STORAGE_PREFIX}${functionUnitId}`
}

/**
 * 读取该功能单元的 AI Studio 草稿；没有或已损坏返回 null。
 * 损坏的草稿会 console.warn 并清除——入口弹窗随之禁用 "Continue AI draft" 卡片，
 * 用户能看到"没有可继续的草稿"，不是静默吞掉。
 */
export function loadAiStudioDraft(functionUnitId: number): AiStudioDraft | null {
  const key = aiStudioDraftStorageKey(functionUnitId)
  const raw = localStorage.getItem(key)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Partial<AiStudioDraft>
    // 末阶段曾叫 REVIEW（2026-08-09 更名 VALIDATION）：旧草稿就地迁移而不是作废
    if ((parsed.phase as string) === 'REVIEW') parsed.phase = 'VALIDATION'
    if (Array.isArray(parsed.completedPhases)) {
      parsed.completedPhases = parsed.completedPhases.map(p =>
        ((p as string) === 'REVIEW' ? 'VALIDATION' : p) as AiStudioPhase)
    }
    if (
      typeof parsed.name === 'string' &&
      parsed.name.length > 0 &&
      (AI_STUDIO_PHASES as readonly string[]).includes(parsed.phase ?? '')
    ) {
      // completedPhases 里混入未知值时只丢弃该值，不整个作废草稿
      const completed = Array.isArray(parsed.completedPhases)
        ? parsed.completedPhases.filter((p): p is AiStudioPhase =>
          (AI_STUDIO_PHASES as readonly string[]).includes(p))
        : []
      return { ...(parsed as AiStudioDraft), completedPhases: completed }
    }
    throw new Error(`unexpected draft shape: ${raw}`)
  } catch (e) {
    console.warn(`[ai-studio] discarding corrupt draft at ${key}`, e)
    localStorage.removeItem(key)
    return null
  }
}

/** 工作台写草稿；入口弹窗据此点亮 "Continue AI draft"。 */
export function saveAiStudioDraft(functionUnitId: number, draft: AiStudioDraft): void {
  localStorage.setItem(aiStudioDraftStorageKey(functionUnitId), JSON.stringify(draft))
}

export function clearAiStudioDraft(functionUnitId: number): void {
  localStorage.removeItem(aiStudioDraftStorageKey(functionUnitId))
}

// ---- Copilot 聊天线程持久化（与草稿同生命周期，Continue AI draft 时一并恢复） ----

export interface AiStudioChatMessage {
  role: 'user' | 'assistant'
  text: string
  isError?: boolean
  isPhaseNote?: boolean
  /** 结构化改动提案（propose 轮次）：data 即 Apply 时原样带回的 generatedData */
  proposal?: {
    scope: string
    data: Record<string, unknown>
    applied?: boolean
  }
}

export type AiStudioChatThreads = Partial<Record<AiStudioPhase, AiStudioChatMessage[]>>

const CHAT_STORAGE_PREFIX = 'dw-ai-studio-chat:'

/** 每阶段最多持久化的消息条数，防 localStorage 无限膨胀（保最新）。 */
const CHAT_MESSAGES_PER_PHASE_CAP = 50

export function aiStudioChatStorageKey(functionUnitId: number): string {
  return `${CHAT_STORAGE_PREFIX}${functionUnitId}`
}

/** 读取该功能单元的 Copilot 聊天线程；没有或已损坏返回空对象（损坏会 warn 并清除）。 */
export function loadAiStudioChatThreads(functionUnitId: number): AiStudioChatThreads {
  const key = aiStudioChatStorageKey(functionUnitId)
  const raw = localStorage.getItem(key)
  if (!raw) return {}
  try {
    const parsed = JSON.parse(raw) as AiStudioChatThreads
    const threads: AiStudioChatThreads = {}
    for (const phase of AI_STUDIO_PHASES) {
      const msgs = parsed[phase]
      if (!Array.isArray(msgs)) continue
      const valid = msgs.filter(
        (m): m is AiStudioChatMessage =>
          !!m && (m.role === 'user' || m.role === 'assistant') && typeof m.text === 'string'
      )
      if (valid.length) threads[phase] = valid
    }
    return threads
  } catch (e) {
    console.warn(`[ai-studio] discarding corrupt chat threads at ${key}`, e)
    localStorage.removeItem(key)
    return {}
  }
}

/**
 * 持久化聊天线程（每阶段截断到最新 CHAT_MESSAGES_PER_PHASE_CAP 条）。
 * 配额超限等存储失败只 warn 不中断对话——聊天本体还在内存里，丢的只是这次落盘。
 */
export function saveAiStudioChatThreads(functionUnitId: number, threads: AiStudioChatThreads): void {
  const capped: AiStudioChatThreads = {}
  for (const phase of AI_STUDIO_PHASES) {
    const msgs = threads[phase]
    if (msgs?.length) capped[phase] = msgs.slice(-CHAT_MESSAGES_PER_PHASE_CAP)
  }
  try {
    localStorage.setItem(aiStudioChatStorageKey(functionUnitId), JSON.stringify(capped))
  } catch (e) {
    console.warn('[ai-studio] failed to persist chat threads (quota?)', e)
  }
}

export function clearAiStudioChatThreads(functionUnitId: number): void {
  localStorage.removeItem(aiStudioChatStorageKey(functionUnitId))
}

// ---- 进行中的改动提案作业（异步）：刷新/离开再回来能接着轮询，不用重新烧一轮模型 ----

export interface AiStudioPendingProposal {
  jobId: string
  phase: AiStudioPhase
  submittedAt: number
}

const PENDING_PROPOSAL_PREFIX = 'dw-ai-studio-proposal:'

export function aiStudioPendingProposalStorageKey(functionUnitId: number): string {
  return `${PENDING_PROPOSAL_PREFIX}${functionUnitId}`
}

/** 读取进行中的提案作业；没有或已损坏返回 null（损坏会 warn 并清除）。 */
export function loadAiStudioPendingProposal(functionUnitId: number): AiStudioPendingProposal | null {
  const key = aiStudioPendingProposalStorageKey(functionUnitId)
  const raw = localStorage.getItem(key)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Partial<AiStudioPendingProposal>
    if (
      typeof parsed?.jobId === 'string' && parsed.jobId &&
      typeof parsed.phase === 'string' && (AI_STUDIO_PHASES as readonly string[]).includes(parsed.phase) &&
      typeof parsed.submittedAt === 'number'
    ) {
      return { jobId: parsed.jobId, phase: parsed.phase as AiStudioPhase, submittedAt: parsed.submittedAt }
    }
    throw new Error('invalid shape')
  } catch (e) {
    console.warn(`[ai-studio] discarding corrupt pending proposal at ${key}`, e)
    localStorage.removeItem(key)
    return null
  }
}

export function saveAiStudioPendingProposal(functionUnitId: number, pending: AiStudioPendingProposal): void {
  try {
    localStorage.setItem(aiStudioPendingProposalStorageKey(functionUnitId), JSON.stringify(pending))
  } catch (e) {
    console.warn('[ai-studio] failed to persist pending proposal (quota?)', e)
  }
}

export function clearAiStudioPendingProposal(functionUnitId: number): void {
  localStorage.removeItem(aiStudioPendingProposalStorageKey(functionUnitId))
}

/**
 * 阶段 key → 当前语言下的阶段名。除 Review 外全部复用设计器 Tab 的既有 i18n key，
 * 保证引导条文案与 Tab 文案永远一致，不再维护一套平行翻译。
 */
export function aiStudioPhaseLabel(t: ComposerTranslation, phase: AiStudioPhase): string {
  const keyByPhase: Record<AiStudioPhase, string> = {
    PROCESS_DESIGN: 'functionUnit.process',
    TABLE_DESIGN: 'functionUnit.tables',
    FORM_DESIGN: 'functionUnit.forms',
    VIEW_DESIGN: 'functionUnit.viewDesign',
    ACTION_DESIGN: 'functionUnit.actionDesign',
    AUTOMATION: 'functionUnit.automation',
    CONNECTIONS: 'connection.title',
    EMAIL_TEMPLATES: 'emailTemplate.title',
    EMAIL_MONITORS: 'emailMonitor.title',
    DECISION_DESIGN: 'functionUnit.decisions',
    VALIDATION: 'ai.studio.step.validation'
  }
  return t(keyByPhase[phase])
}
