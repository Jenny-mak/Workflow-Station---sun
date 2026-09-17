import api from './index'
import type { AiStudioProposalPreview } from './aiGeneration'

/**
 * AI Studio 共享线程与进度（按功能单元共享给同组成员）。
 *
 * 对话消息由后端在对话轮/提案作业完成时写入，前端只读；前端能改的只有提案卡的已应用状态
 * 与已确认阶段。所有调用都静默失败，由工作台回落到 localStorage。
 */

export interface AiStudioThreadState {
  /** 该功能单元还没有共享进度时为 null */
  completedPhases: string[] | null
  updatedBy: string | null
  updatedAt: string | null
  messageCounts: Record<string, number>
  canModify: boolean
}

export interface AiStudioThreadMessage {
  id: number
  phase: string
  role: 'USER' | 'ASSISTANT'
  content: string
  authorName: string | null
  mine: boolean
  createdAt: string
  proposal: {
    scope: string | null
    data: Record<string, unknown>
    preview: AiStudioProposalPreview | null
    applied: boolean
    appliedByName: string | null
    appliedByMe: boolean
    appliedAt: string | null
  } | null
}

export interface AiStudioThreadImportMessage {
  role: 'USER' | 'ASSISTANT'
  content: string
  proposal?: Record<string, unknown> | null
}

const base = (functionUnitId: number) => `/ai-generation/studio-thread/${functionUnitId}`

/** 推送流地址（fetch 读流用，不走 axios） */
export const AI_STUDIO_THREAD_EVENTS_URL = (functionUnitId: number) =>
  `/api/v1${base(functionUnitId)}/events`

export type AiStudioThreadEventType =
  | 'READY' | 'MESSAGE_ADDED' | 'MESSAGE_UPDATED' | 'PROGRESS_UPDATED' | 'PROPOSAL_STARTED' | 'PROPOSAL_FINISHED'

/** 推送事件负载：只有 id 与展示信息，内容按 id 另拉 */
export interface AiStudioThreadEventData {
  phase?: string
  messageId?: number
  jobId?: string
  authorName?: string
  mine: boolean
}

export const aiStudioThreadApi = {
  getState: (functionUnitId: number) =>
    api.get<unknown, { data: AiStudioThreadState }>(base(functionUnitId), { silentError: true }),

  /** afterId 为空时返回该阶段最新 50 条；否则只返回 id 更大的消息（最多 50 条） */
  getMessages: (functionUnitId: number, phase: string, afterId?: number) =>
    api.get<unknown, { data: AiStudioThreadMessage[] }>(`${base(functionUnitId)}/messages`,
      { params: afterId ? { phase, afterId } : { phase }, silentError: true }),

  getMessage: (functionUnitId: number, messageId: number) =>
    api.get<unknown, { data: AiStudioThreadMessage }>(`${base(functionUnitId)}/messages/${messageId}`,
      { silentError: true }),

  saveCompletedPhases: (functionUnitId: number, completedPhases: string[]) =>
    api.put<unknown, { data: string[] }>(`${base(functionUnitId)}/completed-phases`,
      { completedPhases }, { silentError: true }),

  markApplied: (functionUnitId: number, messageId: number, applied: boolean) =>
    api.patch<unknown, { data: AiStudioThreadMessage }>(`${base(functionUnitId)}/messages/${messageId}/applied`,
      { applied }, { silentError: true }),

  importThreads: (
    functionUnitId: number,
    threads: Record<string, AiStudioThreadImportMessage[]>,
    completedPhases: string[]
  ) =>
    api.post<unknown, { data: string[] }>(`${base(functionUnitId)}/import`,
      { threads, completedPhases }, { silentError: true })
}
