/** 与后端 HermesMasterChatRequest / HermesMasterChatResponse 一一对应 */

export type HermesMasterRole = 'USER' | 'ASSISTANT'

export interface HermesMasterHistoryMessage {
  role: HermesMasterRole
  content: string
}

export interface HermesMasterChatPayload {
  message: string
  /** 当前页面所属功能单元；不在功能单元页面时不带 */
  functionUnitId?: number
  /** 当前页面的路由名 */
  page?: string
  history: HermesMasterHistoryMessage[]
}

export interface HermesMasterChatResult {
  reply: string
}

/** 气泡里的一条消息；error 为 true 的是本地错误提示，不进发给模型的历史 */
export interface HermesMasterMessage {
  id: number
  role: HermesMasterRole
  content: string
  error?: boolean
}
