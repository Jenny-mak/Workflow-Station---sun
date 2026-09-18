import api from './index'
import { readAmToken } from '@/utils/amToken'
import type { HermesMasterChatPayload, HermesMasterChatResult } from '@/types/hermesMaster'

export const hermesMasterApi = {
  /**
   * Hermes Master 单轮对话。模型链路与 AI Studio Copilot 同源：AMToken 经 X-AM-Token 头透传，
   * 读不到就不带，后端以 AI_GATEWAY_TOKEN_MISSING 显式失败。
   * 错误由气泡自己展示，所以关掉全局错误提示。
   */
  chat: (data: HermesMasterChatPayload, signal?: AbortSignal) => {
    const amToken = readAmToken()
    return api.post<unknown, { data: HermesMasterChatResult }>(
      '/ai-generation/hermes-master/chat',
      data,
      {
        timeout: 120000,
        signal,
        silentError: true,
        ...(amToken ? { headers: { 'X-AM-Token': amToken } } : {})
      }
    )
  }
}
