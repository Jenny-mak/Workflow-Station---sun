import request from './request'
import type { MiAssignmentsMap } from '@/utils/miAssignmentConfig'
import type { PortalListColumnMeta } from '@/utils/portalListGridRuntime'

export interface ProcessDefinition {
  id: string
  key: string
  name: string
  description?: string
  category: string
  version: string
  icon?: string
  isFavorite?: boolean
}

export interface ProcessInstance {
  id: string
  processDefinitionId: string
  processDefinitionKey?: string
  processDefinitionName: string
  businessKey?: string
  startTime: string
  endTime?: string
  status: string
  startUserId: string
  startUserName: string
  currentNode?: string
  /** 「当前步骤」名（MI 感知）：普通节点=currentNode；多实例子任务内部=外层多实例 subProcess name（如 "multi"）。 */
  currentStepName?: string
  currentAssignee?: string
  candidateUsers?: string
  /** Request ID: main-table configured human-readable identifier (e.g. HR-2026-001); null when unconfigured. */
  requestId?: string | null
  /**
   * 仅 startProcess 返回：首个发起人任务自动完成失败时为固定标记 `FIRST_STEP_NOT_COMPLETED`，成功时为空。
   * 实例已创建且任务退回待办可重试，故 /start 不报错——但非空即「已创建、首步未完成」，不可提示提交成功。
   * 不含具体原因（原文带 AP webhook URL，等同凭据），排查看服务端日志。
   */
  firstStepError?: string | null
  variables?: Record<string, unknown>
}

export interface ProcessStartRequest {
  /** 与路径 /processes/{processKey}/start 一致；省略时由后端用路径参数补全 */
  processDefinitionKey?: string
  businessKey?: string
  formData?: Record<string, unknown>
  priority?: string
  /** @deprecated 服务端已忽略；流程变量由 JWT 工作台上下文写入，勿在 formData 中传同名键 */
  activeBusinessUnitId?: string
}

export interface FunctionUnitContent {
  id: string
  name: string
  code: string
  version: string
  description?: string
  status: string
  forms: Array<{
    id: string
    name: string
    data: string
    type: string
    /** DW form type when assembled from admin-center (PROCESS / TASK / ACTION). */
    formType?: string
    sourceId?: string
  }>
  processes: Array<{
    id: string
    name: string
    data: string
    type: string
  }>
  dataTables: Array<{
    id: string
    name: string
    data: string
    type: string
  }>
  /** BPMN-derived assignment configuration keyed by MI subTableName. */
  miAssignments?: MiAssignmentsMap
  error?: string
}

export const processApi = {
  // 获取可发起的流程定义列表
  getDefinitions(params?: { category?: string; keyword?: string }) {
    return request.get<ProcessDefinition[]>('/processes/definitions', { params })
  },

  // 发起流程
  startProcess(processKey: string, data: ProcessStartRequest) {
    return request.post<ProcessInstance>(`/processes/${processKey}/start`, data)
  },

  // 获取我的申请列表（page/size + optional keyword/sort/filters/groupBy — server-authoritative）
  getMyApplications(params: {
    page?: number
    size?: number
    status?: string
    keyword?: string
    sortField?: string
    sortDirection?: 'ASC' | 'DESC'
    /** MTV-shaped filters JSON string */
    filters?: string
    /** Whitelist same as sortField; response may include groupCounts */
    groupBy?: string
  }) {
    return request.get('/processes/my-applications', { params })
  },

  getMyApplicationColumns() {
    return request.get<{ data: PortalListColumnMeta[] }>('/processes/my-applications/columns')
  },

  // 获取流程详情
  getProcessDetail(processId: string) {
    return request.get<ProcessInstance>(`/processes/${processId}`)
  },

  // 撤回流程
  withdrawProcess(processId: string, reason: string) {
    return request.post(`/processes/${processId}/withdraw`, { reason })
  },

  // 催办流程
  urgeProcess(processId: string) {
    return request.post(`/processes/${processId}/urge`)
  },

  // 退回第一个用户任务节点（起草）
  returnProcessToFirstStep(processId: string, comment?: string) {
    return request.post(`/processes/${processId}/return-to-first`, { comment })
  },

  // 切换收藏状态
  toggleFavorite(processKey: string) {
    return request.post<boolean>(`/processes/${processKey}/favorite`)
  },

  // 保存草稿
  saveDraft(processKey: string, formData: Record<string, unknown>) {
    return request.post(`/processes/${processKey}/draft`, formData)
  },

  // 获取草稿
  getDraft(processKey: string) {
    return request.get(`/processes/${processKey}/draft`)
  },

  // 删除草稿
  deleteDraft(processKey: string) {
    return request.delete(`/processes/${processKey}/draft`)
  },
  
  /**
   * Draft list. When `page` is provided, returns PageResponse `{ content, totalElements }`.
   * When omitted, returns the full array (legacy).
   */
  getDraftList(params?: {
    page?: number
    size?: number
    sortField?: string
    sortDirection?: 'ASC' | 'DESC'
    filters?: string
    groupBy?: string
  }) {
    return request.get<
      | Array<{
          id: number
          processDefinitionKey: string
          processDefinitionName: string
          formData: Record<string, unknown>
          createdAt: string
          updatedAt: string
        }>
      | {
          content: Array<{
            id: number
            processDefinitionKey: string
            processDefinitionName: string
            formData: Record<string, unknown>
            createdAt: string
            updatedAt: string
          }>
          totalElements: number
          page?: number
          size?: number
          groupCounts?: Record<string, number>
        }
    >('/processes/drafts', { params })
  },

  getDraftColumns() {
    return request.get<{ data: PortalListColumnMeta[] }>('/processes/drafts/columns')
  },
  
  // 根据ID删除草稿
  deleteDraftById(draftId: number) {
    return request.delete(`/processes/drafts/${draftId}`)
  },
  
  // 获取功能单元完整内容（BPMN、表单等）
  // taskId：任务参与人（处理人/候选人/发起人）凭任务放行，无需持有该功能单元的可发起角色
  getFunctionUnitContent(functionUnitId: string, taskId?: string) {
    return request.get<FunctionUnitContent>(
      `/processes/function-units/${functionUnitId}/content`,
      taskId ? { params: { taskId } } : undefined,
    )
  },
  
  // 获取功能单元特定类型的内容
  getFunctionUnitContents(functionUnitId: string, contentType: string) {
    interface FunctionUnitContentItem {
      id: string
      name: string
      data: string
      sourceId?: string
    }
    // 调用已经能正常工作的 /processes/function-units/{id}/content 端点
    // 然后在前端根据 contentType 过滤结果
    return this.getFunctionUnitContent(functionUnitId).then((response: FunctionUnitContent | { data: FunctionUnitContent }) => {
      // response 是 ApiResponse 格式: {success, code, data: {forms, processes, dataTables, ...}}
      const content = ('data' in response ? response.data : response) as FunctionUnitContent
      const key = contentType.toUpperCase() === 'FORM' ? 'forms' :
                  contentType.toUpperCase() === 'PROCESS' ? 'processes' :
                  contentType.toUpperCase() === 'DATA_TABLE' ? 'dataTables' : null
      
      const items: FunctionUnitContentItem[] = key ? (content[key as keyof Pick<FunctionUnitContent, 'forms' | 'processes' | 'dataTables'>] || []) : []
      // 返回与原始 API 格式兼容的结构: { data: [...] }
      return {
        data: items.map((item: FunctionUnitContentItem) => ({
          id: item.id,
          contentType: contentType,
          contentName: item.name,
          contentData: item.data,
          sourceId: item.sourceId || item.id
        }))
      }
    })
  },
  
  // 获取流程历史记录
  getProcessHistory(processId: string) {
    return request.get(`/processes/${processId}/history`)
  },

  // 根据ID列表获取动作定义
  getActionsByIds(ids: string[]) {
    return request.get('/processes/actions', { params: { ids: ids.join(',') } })
  },

  /** Allocate PK for sub-table add-row (PRD S5); taskId 同 getFunctionUnitContent（任务参与人放行） */
  allocatePrimaryKeys(
    functionUnitId: string,
    payload: { tableId: number; fieldName: string; count?: number; scopeKey?: string },
    taskId?: string,
  ) {
    return request.post<{ values: string[] }>(
      `/processes/function-units/${functionUnitId}/tables/primary-keys/allocate`,
      payload,
      taskId ? { params: { taskId } } : undefined,
    )
  },
}
