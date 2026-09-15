import { request } from './request'

export interface TaskOverview {
  pendingCount: number
  claimableCount: number
  todoCount: number
  overdueCount: number
  completedTodayCount: number
  avgProcessingHours: number
  urgentCount: number
  highPriorityCount: number
  teamPendingCount: number
  teamOverdueCount: number
  teamCompletedTodayCount: number
}

export interface ProcessOverview {
  initiatedCount: number
  inProgressCount: number
  completedThisMonthCount: number
  draftCount: number
  approvalRate: number
  typeDistribution: Record<string, number>
}

export interface RecentTask {
  taskId?: string
  id?: string
  taskName?: string
  name?: string
  processDefinitionName?: string
  processName?: string
  requestId?: string | null
  functionUnitName?: string | null
  functionUnitCode?: string | null
  assignmentType?: string
  bpmnAssigneeType?: string
  assignee?: string
  status?: string
  priority?: string | number
  createTime?: string
  createdAt?: string
  dueDate?: string
  isOverdue?: boolean
  claimPoolTask?: boolean
  claimedByCurrentUser?: boolean
  claimable?: boolean
}

export interface RecentProcess {
  id: string
  processDefinitionName: string
  businessKey?: string
  status: string
  startTime: string
  endTime?: string
  currentNode?: string
}

export interface DashboardOverview {
  taskOverview: TaskOverview
  processOverview: ProcessOverview
  recentTasks: RecentTask[]
  recentProcesses: RecentProcess[]
}

// 获取Dashboard概览
export function getDashboardOverview() {
  return request.get<{ data: DashboardOverview }>('/dashboard/overview')
}

// 获取任务概览
export function getTaskOverview() {
  return request.get<{ data: TaskOverview }>('/dashboard/task-overview')
}

// 获取流程概览
export function getProcessOverview() {
  return request.get<{ data: ProcessOverview }>('/dashboard/process-overview')
}

export interface TaskTrendData {
  dates: string[]
  completed: number[]
  created: number[]
}

export interface ProcessStatisticsData {
  byStatus: Record<string, number>
  byType: Record<string, number>
  completionRate: number
}

// 获取任务趋势数据
export function getTaskTrendData(days: number = 30) {
  return request.get<{ data: TaskTrendData }>('/dashboard/task-trend', { params: { days } })
}

// 获取流程统计数据
export function getProcessStatisticsData() {
  return request.get<{ data: ProcessStatisticsData }>('/dashboard/process-statistics')
}

export interface TeamRequestItem {
  id: string
  /** 主表配置的人类可读单号；主表未配置 Request ID 时为 null。 */
  requestId?: string | null
  processDefinitionName: string
  businessKey?: string
  startUserName: string
  status: string
  currentNode?: string
  currentAssignee?: string
  startTime: string
  completedAt?: string
}

export interface TeamRequestsResponse {
  overallCount: number
  runningCount: number
  completedCount: number
  withdrawnCount: number
  content: TeamRequestItem[]
  totalElements: number
  totalPages: number
  page: number
  size: number
}

export function getTeamRequests(params: { status?: string; page?: number; size?: number }) {
  return request.get<{ data: TeamRequestsResponse }>('/dashboard/team-requests', { params })
}
