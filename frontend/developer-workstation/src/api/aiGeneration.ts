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
  studioChat: (data: AiStudioChatPayload) => {
    const amToken = readAmToken()
    return api.post<any, { data: AiStudioChatResult }>(
      '/ai-generation/studio-chat',
      data,
      {
        timeout: data.propose ? 360000 : 120000,
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

  /** 轮询提案作业；作业不存在/已过期/不属于当前用户时后端返回 404。 */
  studioGetProposal: (jobId: string) =>
    api.get<any, { data: AiStudioProposalJob }>(
      `/ai-generation/studio-chat/proposals/${encodeURIComponent(jobId)}`,
      { timeout: 30000 }
    ),

  /** 应用 Copilot 改动提案（后端：抢 AI 锁 → 校验 → 按 scope 写入）。 */
  studioApplyProposal: (data: AiStudioApplyPayload) =>
    api.post('/ai-generation/studio-chat/apply', data, { timeout: 120000 }),
}

export interface AiStudioChatPayload {
  functionUnitId: number
  phase: string
  message: string
  history: { role: 'USER' | 'ASSISTANT'; content: string }[]
  propose?: boolean
}

export interface AiStudioChatResult {
  reply: string | null
  proposal: Record<string, unknown> | null
  proposalScope: string | null
}

export type AiStudioProposalJobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export interface AiStudioProposalJob extends AiStudioChatResult {
  jobId: string
  functionUnitId: number
  phase: string
  status: AiStudioProposalJobStatus
  submittedAt: string
  finishedAt: string | null
  errorCode: string | null
  errorMessage: string | null
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
