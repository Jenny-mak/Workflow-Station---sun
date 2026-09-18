import api from './index'

/**
 * 功能单元 Requirements / Function Unit Design 文档。每次保存追加一个版本；
 * baseVersion 是打开时看到的版本（还没有文档时为 0），不是最新版本时后端回 409。
 */

export type FunctionUnitDocumentType = 'REQUIREMENTS' | 'DESIGN'

export const FUNCTION_UNIT_DOCUMENT_TYPES: readonly FunctionUnitDocumentType[] = ['REQUIREMENTS', 'DESIGN']

export interface FunctionUnitDocument {
  documentType: FunctionUnitDocumentType
  /** 内部序号：保存时回传为 baseVersion，恢复与对比也用它 */
  version: number
  /** 显示用：v{majorVersion}.{minorVersion}，见 utils/functionUnitDocumentSource.ts */
  majorVersion: number
  minorVersion: number
  /** 历史列表里为 null */
  content: string | null
  summary: string | null
  createdBy: string
  createdAt: string
}

const base = (functionUnitId: number) => `/function-units/${functionUnitId}/documents`

export const functionUnitDocumentApi = {
  current: (functionUnitId: number) =>
    api.get<unknown, { data: Record<FunctionUnitDocumentType, FunctionUnitDocument | null> }>(base(functionUnitId)),

  history: (functionUnitId: number, type: FunctionUnitDocumentType) =>
    api.get<unknown, { data: FunctionUnitDocument[] }>(`${base(functionUnitId)}/${type}/versions`),

  version: (functionUnitId: number, type: FunctionUnitDocumentType, version: number) =>
    api.get<unknown, { data: FunctionUnitDocument }>(`${base(functionUnitId)}/${type}/versions/${version}`),

  /** 开始新一轮设计：下一次保存进入新的主版本（v2.1）。还没有文档时不进位 */
  startNewRound: (functionUnitId: number) =>
    api.post<unknown, { data: number }>(`${base(functionUnitId)}/next-round`, {}, { silentError: true }),

  /** 409 由调用方处理（载入最新 / 仍然保存），不弹全局错误 */
  save: (functionUnitId: number, type: FunctionUnitDocumentType, content: string, baseVersion: number) =>
    api.put<unknown, { data: FunctionUnitDocument }>(`${base(functionUnitId)}/${type}`,
      { content, baseVersion }, { silentError: true }),

  restore: (functionUnitId: number, type: FunctionUnitDocumentType, version: number, baseVersion: number) =>
    api.post<unknown, { data: FunctionUnitDocument }>(`${base(functionUnitId)}/${type}/versions/${version}/restore`,
      { baseVersion }, { silentError: true })
}

/** 后端 CONFLICT_ 前缀的业务错误映射为 409 */
export function isDocumentConflict(error: unknown): boolean {
  return (error as { response?: { status?: number } })?.response?.status === 409
}
