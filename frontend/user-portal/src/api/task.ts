import type { AxiosRequestConfig } from 'axios'
import { request } from './request'

import type { ListColumnFilterRequest, ListColumnMeta } from '@platform-shared/list/columnMeta'

export interface PortalListGroup {
  label: string | null
  count: number
}

export interface PortalListPage<T> {
  columns: ListColumnMeta[]
  content: T[]
  groups?: PortalListGroup[]
  page: number
  size: number
  totalElements: number
}

export interface CompletedTaskQueryRequest {
  page: number
  size: number
  filters?: ListColumnFilterRequest[]
  sortField?: string
  sortDirection?: 'ASC' | 'DESC'
  groupBy?: string
  keyword?: string
  startTime?: string
  endTime?: string
}

export interface TaskQueryRequest {
  userId?: string
  assignmentTypes?: string[]
  priorities?: string[]
  processTypes?: string[]
  statuses?: string[]
  startTime?: string
  endTime?: string
  includeOverdue?: boolean
  keyword?: string
  sortBy?: string
  sortDirection?: string
  page?: number
  size?: number
}

export interface TaskActionInfo {
  actionId: string
  actionName: string
  actionType: string
  description?: string
  icon?: string
  buttonColor?: string
  configJson?: string
}

export interface TaskInfo {
  taskId: string
  taskName: string
  /** 「当前步骤」名（MI 感知）：普通节点=taskName；多实例子任务=外层多实例 subProcess name（如 "multi"）。 */
  currentStepName?: string
  description?: string
  processInstanceId: string
  processDefinitionKey: string
  processDefinitionName: string
  assignmentType: string
  /** BPMN extension assigneeType (e.g. INITIATOR, PROCESS_INITIATOR) */
  bpmnAssigneeType?: string
  /** BPMN extension businessUnitId (e.g. FIXED_BU_ROLE fixed BU) */
  bpmnBusinessUnitId?: string
  /** Engine assignment target (e.g. concatenated candidate user IDs for CANDIDATE_USERS) */
  assignmentTarget?: string
  assignee: string
  assigneeName?: string
  delegatorId?: string
  delegatorName?: string
  initiatorId: string
  initiatorName?: string
  priority: string
  status: string
  createTime: string
  dueDate?: string
  isOverdue: boolean
  formKey?: string
  /** Request ID: main-table configured human-readable identifier (e.g. HR-2026-001); null when unconfigured. */
  requestId?: string | null
  variables?: Record<string, any>
  claimed?: boolean
  originalAssignmentType?: string
  originalAssignee?: string
  candidateUsers?: string
  /** Engine candidate user IDs (consistent with portal TaskInfo.candidateUserIds) */
  candidateUserIds?: string[]
  candidateGroupIds?: string[]
  // Completed task fields
  completedTime?: string
  durationInMillis?: number
  action?: string
  // Custom action buttons
  actions?: TaskActionInfo[]
  /** Whether this is a multi-instance sub-task */
  multiInstanceSubTask?: boolean
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
  hasPrevious: boolean
}

export interface TaskCompleteRequest {
  taskId: string
  action: string
  comment?: string
  formData?: Record<string, any>
  variables?: Record<string, any>
  targetUserId?: string
  returnActivityId?: string
}

/** Historic user-task node that the current task may be returned to (engine uses taskId = activityId). */
export interface ReturnableActivity {
  taskId: string
  taskName?: string
  processInstanceId?: string
  status?: string
}

export interface TaskHistoryInfo {
  id: string
  taskId: string
  taskName: string
  activityId: string
  activityName: string
  activityType: string
  operationType: string
  operatorId: string
  operatorName: string
  operationTime: string
  comment?: string
  duration?: number
}

export interface TaskStatistics {
  totalTasks: number
  directTasks: number
  groupTasks: number
  deptRoleTasks: number
  delegatedTasks: number
  overdueTasks: number
  urgentTasks: number
  highPriorityTasks: number
  todayNewTasks: number
  todayCompletedTasks: number
}

// Query pending tasks
export function queryTasks(params: TaskQueryRequest) {
  return request.post<{ data: PageResponse<TaskInfo> }>('/tasks/query', params)
}

// Get task details
export function getTaskDetail(taskId: string) {
  return request.get<{ data: TaskInfo }>(`/tasks/${taskId}`)
}

// Get task flow history (pass processInstanceId when already loaded from task detail — avoids extra engine lookup)
export function getTaskHistory(taskId: string, processInstanceId?: string) {
  const params =
    processInstanceId && processInstanceId.trim()
      ? { processInstanceId: processInstanceId.trim() }
      : undefined
  return request.get<{ data: TaskHistoryInfo[] }>(`/tasks/${taskId}/history`, { params })
}

// Nodes available for rollback (RETURN)
export function getReturnableActivities(taskId: string) {
  return request.get<{ data: ReturnableActivity[] }>(`/tasks/${taskId}/returnable-activities`)
}

// Get task statistics
export function getTaskStatistics() {
  return request.get<{ data: TaskStatistics }>('/tasks/statistics')
}

// Claim task
export function claimTask(taskId: string) {
  return request.post<{ data: TaskInfo }>(`/tasks/${taskId}/claim`)
}

// Unclaim task
export function unclaimTask(taskId: string, originalAssignmentType: string, originalAssignee: string) {
  return request.post<{ data: TaskInfo }>(`/tasks/${taskId}/unclaim`, null, {
    params: { originalAssignmentType, originalAssignee }
  })
}

// Complete task
export function completeTask(taskId: string, data: TaskCompleteRequest) {
  return request.post(`/tasks/${taskId}/complete`, data)
}

// Delegate task
export function delegateTask(taskId: string, delegateId: string, reason?: string) {
  return request.post(`/tasks/${taskId}/delegate`, null, {
    params: { delegateId, reason }
  })
}

// Transfer task
export function transferTask(taskId: string, toUserId: string, reason?: string) {
  return request.post(`/tasks/${taskId}/transfer`, null, {
    params: { toUserId, reason }
  })
}

// Urge task
export function urgeTask(taskId: string, message?: string) {
  return request.post(`/tasks/${taskId}/urge`, null, {
    params: { message }
  })
}

// Batch urge tasks
export function batchUrgeTasks(taskIds: string[], message?: string) {
  return request.post('/tasks/batch/urge', { taskIds, message })
}

// Query completed tasks
export function queryCompletedTasks(params: CompletedTaskQueryRequest) {
  return request.post<{ data: PortalListPage<TaskInfo> }>('/tasks/completed/query', params)
}

// Assign a user to a sub-table row
export interface AssignSubTableRowRequest {
  assigneeId: string
}

export interface AssignSubTableRowResponse {
  success: boolean
  rowId: number
  assigneeId: string
  assigneeName: string
  /** Present on engine failure (mutually exclusive with message) */
  errorMessage?: string
  message?: string
}

export function assignSubTableRow(
  taskId: string,
  rowId: number,
  assigneeId: string,
  rowKey?: Record<string, unknown>
) {
  const config: AxiosRequestConfig & { skipGlobalErrorHandler?: boolean } = {
    skipGlobalErrorHandler: true
  }
  const body: Record<string, unknown> = { assigneeId }
  if (rowKey != null && Object.keys(rowKey).length > 0) {
    body.rowKey = rowKey
  }
  return request.post<AssignSubTableRowResponse | { data: AssignSubTableRowResponse }>(
    `/tasks/${taskId}/sub-table-rows/${rowId}/assign`,
    body,
    config
  )
}

export function assignSubTableRowByIdentity(
  taskId: string,
  payload: {
    assigneeId: string
    email?: string
    name?: string
    department?: string
    topic?: string
    location?: string
    organizerName?: string
  }
) {
  const config: AxiosRequestConfig & { skipGlobalErrorHandler?: boolean } = {
    skipGlobalErrorHandler: true
  }
  return request.post<AssignSubTableRowResponse | { data: AssignSubTableRowResponse }>(
    `/tasks/${taskId}/sub-table-rows/assign-by-identity`,
    payload,
    config
  )
}

// Get main task sub-table data (for real-time sync)
export interface SubTableRowStatus {
  id?: number
  /** 物理表主键（联合主键时为多列） */
  rowKey?: Record<string, unknown>
  assignee?: string
  assigneeName?: string
  status?: string
  [key: string]: any
}

export interface SubTableDataResponse {
  taskId: string
  subTableName: string
  rows: SubTableRowStatus[]
}

export function getSubTableData(taskId: string) {
  return request.get<{ data: SubTableDataResponse }>(`/tasks/${taskId}/sub-table-data/all`)
}
