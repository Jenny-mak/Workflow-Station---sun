import api from './index'
import { readAmToken } from '@/utils/amToken'
import type {
  LockInfo,
  AiSession,
  AiMessage,
  AiDocument,
  AiDocumentType,
  ApplyGeneratedDataRequest,
  PageResponse
} from '@/types/aiGeneration'

export const aiGenerationApi = {
  acquireLock: (functionUnitId: number) =>
    api.post<any, { data: LockInfo }>(`/ai-generation/lock/${functionUnitId}`),

  releaseLock: (functionUnitId: number) =>
    api.delete(`/ai-generation/lock/${functionUnitId}`),

  requestForceUnlock: (functionUnitId: number) =>
    api.post(`/ai-generation/lock/${functionUnitId}/force-unlock-request`),

  respondForceUnlock: (functionUnitId: number, accept: boolean) =>
    api.post(`/ai-generation/lock/${functionUnitId}/force-unlock-response`, { accept }),

  getSessions: (functionUnitId: number) =>
    api.get<any, { data: AiSession[] }>('/ai-generation/sessions', { params: { functionUnitId } }),

  getMessages: (sessionId: string, params?: { page?: number; size?: number }) =>
    api.get<any, { data: PageResponse<AiMessage> }>(`/ai-generation/sessions/${sessionId}/messages`, { params }),

  applyGeneratedData: (functionUnitId: number, data: ApplyGeneratedDataRequest) =>
    api.post(`/ai-generation/${functionUnitId}/apply`, data),

  undoLastApply: (functionUnitId: number) =>
    api.post(`/ai-generation/${functionUnitId}/undo`),

  getDocumentVersions: (functionUnitId: number, documentType: AiDocumentType) =>
    api.get<any, { data: AiDocument[] }>(`/ai-generation/documents`, {
      params: { functionUnitId, documentType }
    }),

  getDocumentByVersion: (functionUnitId: number, documentType: AiDocumentType, version: number) =>
    api.get<any, { data: AiDocument }>(`/ai-generation/documents/version`, {
      params: { functionUnitId, documentType, version }
    }),

  saveDocument: (functionUnitId: number, documentType: AiDocumentType, content: string) =>
    api.post<any, { data: AiDocument }>('/ai-generation/documents', {
      functionUnitId, documentType, content
    }),

  updateSessionPhase: (sessionId: string, phase: string) =>
    api.put(`/ai-generation/sessions/${sessionId}/phase`, null, { params: { phase } }),

  /**
   * AI Studio Copilot 单轮对话（顾问式，无会话/锁/文档）。
   * 模型链路与 AI Generate 同源：AMToken 经 X-AM-Token 头透传，读不到就不带，
   * 后端以 AI_GATEWAY_TOKEN_MISSING 显式失败（dev 配静态 key 时无需 token）。
   * propose=true 走 GENERATION 管线产出结构化提案，耗时分钟级，超时放宽到 6 分钟。
   */
  studioChat: (data: AiStudioChatPayload, signal?: AbortSignal) => {
    const amToken = readAmToken()
    return api.post<any, { data: AiStudioChatResult }>(
      '/ai-generation/studio-chat',
      data,
      {
        timeout: data.propose ? 360000 : 120000,
        signal,
        ...(amToken ? { headers: { 'X-AM-Token': amToken } } : {})
      }
    )
  },

  /**
   * 发起 Copilot 改动提案作业（异步）。一轮 GENERATION 实测 7 分钟上下，同步等会被 Kong 300s 读超时掐断，
   * 所以后端立即返回 jobId，前端用 studioGetProposal 轮询到终态。
   */
  studioStartProposal: (data: Omit<AiStudioChatPayload, 'propose'>) => {
    const amToken = readAmToken()
    return api.post<any, { data: AiStudioProposalJob }>(
      '/ai-generation/studio-chat/proposals',
      data,
      {
        timeout: 120000,
        ...(amToken ? { headers: { 'X-AM-Token': amToken } } : {})
      }
    )
  },

  /**
   * 一键生成：一次模型调用产出整套核心设计，预校验无 ERROR 时后端直接写入。作业与 Copilot 提案同一套，
   * 用 studioGetProposal 轮询、studioCancelProposal 取消；结果卡片落在 Process Design 阶段线程。
   * followUp = 对上一轮结果的修正轮（修正指令不记进 Requirements 文档）。错误由调用方就地展示。
   */
  studioStartOneClick: (data: { functionUnitId: number; requirements: string; followUp?: boolean }) => {
    const amToken = readAmToken()
    return api.post<unknown, { data: AiStudioProposalJob }>(
      '/ai-generation/studio-chat/one-click',
      data,
      {
        timeout: 120000,
        silentError: true,
        ...(amToken ? { headers: { 'X-AM-Token': amToken } } : {})
      }
    )
  },

  /** 轮询提案作业；作业不存在/已过期/不属于当前用户时后端返回 404。 */
  studioGetProposal: (jobId: string) =>
    api.get<any, { data: AiStudioProposalJob }>(
      `/ai-generation/studio-chat/proposals/${encodeURIComponent(jobId)}`,
      { timeout: 30000 }
    ),

  /** 本人在该功能单元上正在跑的作业（换浏览器接着等）；没有时 data 为 null。静默失败。 */
  studioActiveProposal: (functionUnitId: number) =>
    api.get<unknown, { data: AiStudioProposalJob | null }>(
      '/ai-generation/studio-chat/proposals/active',
      { params: { functionUnitId }, timeout: 30000, silentError: true }
    ),

  /** 取消进行中的提案作业（幂等）；后端中断后台线程并丢弃迟到结果。 */
  studioCancelProposal: (jobId: string) =>
    api.post<any, { data: AiStudioProposalJob }>(
      `/ai-generation/studio-chat/proposals/${encodeURIComponent(jobId)}/cancel`,
      null,
      { timeout: 30000 }
    ),

  /** 应用 Copilot 改动提案（后端：抢 AI 锁 → 校验 → 按 scope 写入）；可撤销的 scope 会带回 undoToken。 */
  studioApplyProposal: (data: AiStudioApplyPayload) =>
    api.post<any, { data: AiStudioApplyResult }>('/ai-generation/studio-chat/apply', data, { timeout: 120000 }),

  /** 撤销一次 Apply（逐项逆操作）；令牌过期 → 4xx AI_STUDIO_UNDO_EXPIRED。 */
  studioUndoApply: (undoToken: string) =>
    api.post<any, { data: AiStudioApplyResult }>('/ai-generation/studio-chat/undo', { undoToken }, { timeout: 120000 }),
}

export interface AiStudioChatPayload {
  functionUnitId: number
  phase: string
  message: string
  /** 近期对话；ASSISTANT 条目可附带上一轮提案（scope + data），让二次修改有落点 */
  history: {
    role: 'USER' | 'ASSISTANT'
    content: string
    proposal?: Record<string, unknown>
    proposalScope?: string
  }[]
  propose?: boolean
}

export type AiStudioPreviewAction = 'NEW' | 'UPDATE' | 'BIND' | 'REBIND' | 'REPLACE'

/** 提案卡预览：生成完成时后端算好的"会改什么"与"Apply 会不会被拒" */
export interface AiStudioProposalPreview {
  items: { slice: string; name: string; action: AiStudioPreviewAction }[]
  replacements: { slice: string; replacesExisting: number }[]
  issues: { severity: 'ERROR' | 'WARNING'; errorType: string; fieldPath: string; description: string }[]
  /** false = 预校验本身没跑成，"无问题"不作数 */
  checked: boolean
  /** Apply 后能否撤销（后端按 scope 给出）；false 时 Apply 前二次确认。老线程里的预览没有这个字段 */
  undoable?: boolean
}

export interface AiStudioChatResult {
  reply: string | null
  proposal: Record<string, unknown> | null
  proposalScope: string | null
  preview?: AiStudioProposalPreview | null
}

export type AiStudioProposalJobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface AiStudioProposalJob extends AiStudioChatResult {
  jobId: string
  functionUnitId: number
  phase: string
  status: AiStudioProposalJobStatus
  /** 发起时的原始消息（DW 重启中断后"重新发起"用） */
  message?: string | null
  /** 发起人展示名 */
  authorName?: string | null
  submittedAt: string
  startedAt?: string | null
  finishedAt: string | null
  errorCode: string | null
  errorMessage: string | null
}

export interface AiStudioApplyResult {
  /** 可撤销的 scope 才有；其余为 null */
  undoToken: string | null
  undoableUntil: string | null
  /** 撤销接口返回的逐项结果；文案由前端按 outcome 翻译 */
  undoNotes?: AiStudioUndoNote[]
}

export type AiStudioUndoOutcome =
  | 'RESTORED' | 'DELETED' | 'KEPT_REFERENCED' | 'SKIPPED_GONE' | 'SKIPPED_NO_PROCESS'
  | 'UNBOUND' | 'REBOUND' | 'FAILED' | 'UNSUPPORTED'

export interface AiStudioUndoNote {
  /** generatedData 切片 key，如 emailTemplates / processDefinition */
  slice: string
  /** 业务名；整片还原时为 null */
  name: string | null
  outcome: AiStudioUndoOutcome
  /** REBOUND 的 flowKey、FAILED 的原因 */
  detail: string | null
}

export interface AiStudioApplyPayload {
  functionUnitId: number
  scope: string
  generatedData: Record<string, unknown>
}

// SSE endpoint URLs (used by composables with fetch API, not axios)
export const AI_CHAT_STREAM_URL = '/api/v1/ai-generation/chat/stream'
export const AI_EVENT_STREAM_URL = (functionUnitId: number) =>
  `/api/v1/ai-generation/events/${functionUnitId}`
