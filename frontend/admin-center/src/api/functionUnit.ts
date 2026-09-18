import { PageResult, type AdminListPage } from '@/types/common'
import { unwrapApiData } from '@/utils/apiResponse'
import { get, post, put, del } from './request'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'

// ==================== 类型定义 ====================

export interface FunctionUnit {
  id: string
  name: string
  code: string
  version: string
  description?: string
  status: 'DRAFT' | 'VALIDATED' | 'DEPLOYED' | 'DEPRECATED' | 'ARCHIVED'
  enabled?: boolean
  packagePath?: string
  importedAt?: string
  importedBy?: string
  deployedAt?: string
  deployedBy?: string
  environment?: string
  createdAt: string
  updatedAt: string
  updatedBy?: string
}

export interface Deployment {
  id: string
  functionUnitId: string
  functionUnitName?: string
  functionUnitCode?: string
  functionUnitVersion?: string
  version?: string
  environment: 'DEVELOPMENT' | 'TESTING' | 'STAGING' | 'PRODUCTION'
  strategy: 'FULL' | 'INCREMENTAL' | 'CANARY' | 'BLUE_GREEN'
  status: 'PENDING' | 'APPROVED' | 'EXECUTING' | 'DEPLOYING' | 'SUCCESS' | 'COMPLETED' | 'FAILED' | 'ROLLED_BACK' | 'CANCELLED'
  deployedBy: string
  deployedByName?: string
  deployedAt?: string
  completedAt?: string
  rollbackReason?: string
  cancelReason?: string
  createdAt: string
}

export interface DeploymentProgress {
  deploymentId: string
  status: string
  progress: number
  currentStep: string
  totalSteps: number
  logs: string[]
  startedAt: string
  estimatedCompletion?: string
  error?: string
}

export interface Approval {
  id: string
  deploymentId: string
  approverType: string
  approverId?: string
  approverName?: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  comment?: string
  approvedAt?: string
  createdAt: string
}

export interface ImportRequest {
  fileName: string
  fileContent: string
  overwriteExisting?: boolean
}

export interface ImportResult {
  success: boolean
  functionUnitId?: string
  functionUnitCode?: string
  functionUnitVersion?: string
  message?: string
  errorMessage?: string
  errors?: string[]
  warnings?: string[]
  // true when the name already existed and the package was imported as a new version
  versioned?: boolean
}

export interface ValidationError {
  type: string
  field: string
  message: string
}

export interface FunctionUnitValidationResult {
  valid: boolean
  bpmnSyntaxValid?: boolean
  formConfigValid?: boolean
  dataTableValid?: boolean
  dependenciesValid?: boolean
  engineDeployValid?: boolean
  functionUnitId?: string
  status?: string
  errors: ValidationError[]
  warnings: string[]
}

export interface ValidationResult {
  valid: boolean
  errors: string[]
  warnings: string[]
}

export interface VersionHistory {
  version: string
  status: string
  createdAt: string
  createdBy: string
  deployedAt?: string
  deployedEnvironment?: string
}

export interface VersionUpgradeCheck {
  canUpgrade: boolean
  fromVersion: string
  toVersion: string
  breakingChanges: string[]
  migrationSteps: string[]
}

// 访问权限类型（简化后只支持角色）
export type FunctionUnitAccessType = 'ROLE'

// 访问权限配置
export interface FunctionUnitAccess {
  id: string
  functionUnitId: string
  functionUnitName: string
  targetType: string    // always 'ROLE' for now
  targetId: string      // the role ID
  targetName?: string   // resolved role name (may be null from server)
  roleId: string        // same as targetId when targetType=ROLE (backward compat getter)
  roleName: string      // same as targetName when targetType=ROLE
  createdAt: string
  createdBy: string
}

/**
 * 审计授权 —— 允许某角色查看该功能单元下的全部申请，
 * 不含发起权（与 FunctionUnitAccess 分表存储）。
 */
export interface FunctionUnitAuditAccess {
  id: string
  functionUnitId: string
  functionUnitName: string
  targetType: string
  targetId: string
  targetName?: string
  targetCode?: string
  roleId: string
  roleName: string
  createdAt: string
  createdBy: string
}

// 访问权限请求（简化后只需要角色ID）
export interface FunctionUnitAccessRequest {
  roleId: string
  roleName?: string
}

// 删除预览响应
export interface DeletePreviewResponse {
  functionUnitId: string
  functionUnitName: string
  functionUnitCode: string
  formCount: number
  processCount: number
  dataTableCount: number
  accessConfigCount: number
  deploymentCount: number
  dependencyCount: number
  hasRunningInstances: boolean
  runningInstanceCount: number
}

// 启用状态响应
export interface EnabledResponse {
  id: string
  enabled: boolean
  updatedAt: string
}

export interface FunctionUnitListQuery {
  page: number
  size: number
  keyword?: string
  filters?: Array<ListColumnFilter & { field: string }>
  sortField?: string
  sortDirection?: 'ASC' | 'DESC'
}

export interface FunctionUnitDeploymentListQuery {
  page: number
  size: number
  keyword?: string
  filters?: Array<ListColumnFilter & { field: string }>
  sortField?: string
  sortDirection?: 'ASC' | 'DESC'
}

// ==================== 功能单元 CRUD API ====================

export const functionUnitApi = {
  // 获取功能单元列表（分页）
  list: (status?: string, page = 0, size = 20) =>
    get<PageResult<FunctionUnit>>('/function-units', { params: { status, page, size } }),

  // 获取已归档的功能单元列表
  listArchived: (page = 0, size = 20) =>
    get<PageResult<FunctionUnit>>('/function-units/archived', { params: { page, size } }),

  query: (body: FunctionUnitListQuery) =>
    post<AdminListPage<FunctionUnit>>('/function-units/query', body),

  queryArchived: (body: FunctionUnitListQuery) =>
    post<AdminListPage<FunctionUnit>>('/function-units/archived/query', body),

  queryDeployments: (body: FunctionUnitDeploymentListQuery) =>
    post<AdminListPage<Deployment>>('/function-units/deployments/query', body),

  // 获取已部署的功能单元列表（按 code 去重取最新版本，供选择下拉使用）
  listDeployedLatest: async () =>
    unwrapApiData<FunctionUnit[]>(await get<unknown>('/function-units/deployed/latest')),

  // 根据ID获取功能单元
  getById: (id: string) =>
    get<FunctionUnit>(`/function-units/${id}`),

  // 导入功能包
  import: (data: ImportRequest) =>
    post<ImportResult>('/function-units/import', data),

  // 验证功能包
  validate: (data: ImportRequest) =>
    post<ValidationResult>('/function-units/validate', data),

  // 删除（归档）功能单元
  delete: (id: string) =>
    del<void>(`/function-units/${id}`),

  // 恢复已归档的功能单元
  restore: (id: string) =>
    post<FunctionUnit>(`/function-units/${id}/restore`),

  // 一键部署到用户门户
  deploy: (id: string) =>
    post<FunctionUnit>(`/function-units/${id}/deploy`),

  // 获取删除预览
  getDeletePreview: (id: string) =>
    get<DeletePreviewResponse>(`/function-units/${id}/delete-preview`),

  // 切换启用状态
  setEnabled: (id: string, enabled: boolean) =>
    put<EnabledResponse>(`/function-units/${id}/enabled`, { enabled }),

  // 验证功能单元（结构/依赖/引擎试部署，通过后变为 VALIDATED）
  validateUnit: async (id: string) =>
    unwrapApiData<FunctionUnitValidationResult>(
      await post<unknown>(`/function-units/${id}/validate`)
    ),

  // 废弃功能单元
  deprecate: (id: string) =>
    post<FunctionUnit>(`/function-units/${id}/deprecate`),

  // ==================== 部署管理 API ====================

  // 创建部署
  createDeployment: (id: string, environment: string, strategy = 'FULL') =>
    post<Deployment>(`/function-units/${id}/deployments`, null, {
      params: { environment, strategy }
    }),

  // 获取部署历史
  getDeploymentHistory: (id: string) =>
    get<Deployment[]>(`/function-units/${id}/deployments`),

  // 获取所有部署记录（全局分页，不限定功能单元）— Req 15.2
  getAllDeployments: (page = 0, size = 20) =>
    get<PageResult<Deployment>>('/function-units/deployments', { params: { page, size } }),

  // 获取部署详情
  getDeployment: (deploymentId: string) =>
    get<Deployment>(`/function-units/deployments/${deploymentId}`),

  // 执行部署
  executeDeployment: (deploymentId: string) =>
    post<Deployment>(`/function-units/deployments/${deploymentId}/execute`),

  // 回滚部署
  rollbackDeployment: (deploymentId: string, reason: string) =>
    post<Deployment>(`/function-units/deployments/${deploymentId}/rollback`, null, {
      params: { reason }
    }),

  // 取消部署
  cancelDeployment: (deploymentId: string, reason: string) =>
    post<Deployment>(`/function-units/deployments/${deploymentId}/cancel`, null, {
      params: { reason }
    }),

  // 获取部署进度
  getDeploymentProgress: (deploymentId: string) =>
    get<DeploymentProgress>(`/function-units/deployments/${deploymentId}/progress`),

  // ==================== 审批管理 API ====================

  // 获取部署审批记录
  getDeploymentApprovals: (deploymentId: string) =>
    get<Approval[]>(`/function-units/deployments/${deploymentId}/approvals`),

  // 审批通过
  approveDeployment: (approvalId: string, comment?: string) =>
    post<Approval>(`/function-units/approvals/${approvalId}/approve`, null, {
      params: { comment }
    }),

  // 审批拒绝
  rejectDeployment: (approvalId: string, comment: string) =>
    post<Approval>(`/function-units/approvals/${approvalId}/reject`, null, {
      params: { comment }
    }),

  // 获取待审批列表
  getPendingApprovals: () =>
    get<Approval[]>('/function-units/approvals/pending'),

  // ==================== 版本管理 API ====================

  // 获取所有版本
  getAllVersions: (code: string) =>
    get<FunctionUnit[]>(`/function-units/code/${code}/versions`),

  // 获取最新版本
  getLatestVersion: (code: string) =>
    get<FunctionUnit>(`/function-units/code/${code}/latest`),

  // 获取最新稳定版本
  getLatestStableVersion: (code: string) =>
    get<FunctionUnit>(`/function-units/code/${code}/latest-stable`),

  // 创建新版本
  createNewVersion: (id: string, newVersion: string) =>
    post<FunctionUnit>(`/function-units/${id}/new-version`, null, {
      params: { newVersion }
    }),

  // 获取版本历史
  getVersionHistory: (code: string) =>
    get<VersionHistory[]>(`/function-units/code/${code}/history`),

  // 检查版本升级
  checkVersionUpgrade: (code: string, fromVersion: string, toVersion: string) =>
    get<VersionUpgradeCheck>(`/function-units/code/${code}/upgrade-check`, {
      params: { fromVersion, toVersion }
    }),

  // ==================== 访问权限管理 API ====================

  // 获取访问权限配置列表
  getAccessConfigs: (id: string) =>
    get<FunctionUnitAccess[]>(`/function-units/${id}/access`),

  // 添加访问权限配置
  addAccessConfig: (id: string, data: FunctionUnitAccessRequest) =>
    post<FunctionUnitAccess>(`/function-units/${id}/access`, data),

  // 删除访问权限配置
  removeAccessConfig: (id: string, accessId: string) =>
    del<void>(`/function-units/${id}/access/${accessId}`),

  // 批量设置访问权限配置
  setAccessConfigs: (id: string, data: FunctionUnitAccessRequest[]) =>
    put<FunctionUnitAccess[]>(`/function-units/${id}/access`, data),

  // 检查用户访问权限
  checkUserAccess: (id: string, userId: string) =>
    get<boolean>(`/function-units/${id}/access/check`, { params: { userId } }),

  // ==================== 审计授权 API ====================
  // 与上面的访问权限分开：审计授权只让持有者查看该功能单元下的全部申请，
  // 不赋予任何发起能力。

  // 获取审计授权列表
  getAuditAccessConfigs: (id: string) =>
    get<FunctionUnitAuditAccess[]>(`/function-units/${id}/audit-access`),

  // 添加审计授权
  addAuditAccessConfig: (id: string, data: FunctionUnitAccessRequest) =>
    post<FunctionUnitAuditAccess>(`/function-units/${id}/audit-access`, data),

  // 删除审计授权
  removeAuditAccessConfig: (id: string, accessId: string) =>
    del<void>(`/function-units/${id}/audit-access/${accessId}`),

  // ==================== 批量操作 API (Req 20) ====================

  // 批量启用/禁用
  batchSetEnabled: (ids: string[], enabled: boolean) =>
    put<FunctionUnit[]>('/function-units/batch/enabled', { ids, enabled }),

  // 批量删除
  batchDelete: (ids: string[]) =>
    del<void>('/function-units/batch', { data: { ids } }),
}
