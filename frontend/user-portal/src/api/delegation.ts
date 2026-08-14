import { request } from './request'
import type { PortalListColumnMeta } from '@/utils/portalListGridRuntime'

export interface DelegationRule {
  id: number
  delegatorId: string
  delegateId: string
  delegationType: string
  processTypes?: string[]
  priorityFilter?: string[]
  startTime?: string
  endTime?: string
  status: string
  reason?: string
  createdAt: string
  updatedAt: string
}

export interface DelegationRuleRequest {
  delegateId: string
  delegationType: string
  processTypes?: string[]
  priorityFilter?: string[]
  startTime?: string
  endTime?: string
  reason?: string
}

export interface DelegationAudit {
  id: number
  delegatorId: string
  delegateId: string
  taskId?: string
  operationType: string
  operationResult?: string
  operationDetail?: string
  ipAddress?: string
  userAgent?: string
  createdAt: string
}

/**
 * My delegation rules.
 * When `page` is provided → PageResponse `{ content, totalElements }`.
 * When omitted → full List (legacy).
 */
export function getDelegations(params?: {
  page?: number
  size?: number
  sortField?: string
  sortDirection?: 'ASC' | 'DESC'
  filters?: string
  groupBy?: string
}) {
  return request.get<{
    data:
      | DelegationRule[]
      | {
          content: DelegationRule[]
          totalElements: number
          page?: number
          size?: number
          groupCounts?: Record<string, number>
        }
  }>('/delegations', { params })
}

/** @deprecated Prefer getDelegations — kept for call-site clarity during migrate */
export function getDelegationRules(params?: {
  page?: number
  size?: number
  sortField?: string
  sortDirection?: 'ASC' | 'DESC'
  filters?: string
  groupBy?: string
}) {
  return getDelegations(params)
}

/** Column kinds / operators / enum options backing the rule list's header filter dialog. */
export function getDelegationRuleColumns() {
  return request.get<{ data: PortalListColumnMeta[] }>('/delegations/columns')
}

/** Same, for the delegation audit list. */
export function getDelegationAuditColumns() {
  return request.get<{ data: PortalListColumnMeta[] }>('/delegations/audit/columns')
}

// 获取有效委托规则
export function getActiveDelegationRules() {
  return request.get<{ data: DelegationRule[] }>('/delegations/active')
}

// 创建委托规则
export function createDelegationRule(data: DelegationRuleRequest) {
  return request.post<{ data: DelegationRule }>('/delegations', data)
}

// 更新委托规则
export function updateDelegationRule(ruleId: number, data: DelegationRuleRequest) {
  return request.put<{ data: DelegationRule }>(`/delegations/${ruleId}`, data)
}

// 删除委托规则
export function deleteDelegationRule(ruleId: number) {
  return request.delete(`/delegations/${ruleId}`)
}

// 暂停委托规则
export function suspendDelegationRule(ruleId: number) {
  return request.post<{ data: DelegationRule }>(`/delegations/${ruleId}/suspend`)
}

// 恢复委托规则
export function resumeDelegationRule(ruleId: number) {
  return request.post<{ data: DelegationRule }>(`/delegations/${ruleId}/resume`)
}

// 获取委托审计记录（真分页 + 可选 filters/sort/groupBy）
export function getDelegationAuditRecords(
  page: number = 0,
  size: number = 20,
  extra?: {
    sortField?: string
    sortDirection?: 'ASC' | 'DESC'
    filters?: string
    groupBy?: string
  },
) {
  return request.get<{
    data: {
      content: DelegationAudit[]
      totalElements: number
      totalPages: number
      groupCounts?: Record<string, number>
    }
  }>('/delegations/audit', {
    params: { page, size, ...extra },
  })
}
