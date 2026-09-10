import { PageResult, type AdminListPage } from '@/types/common'
import { get, post, put, del } from './request'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'

// ==================== 类型定义 ====================

/** 表状态枚举 */
export type RelationTableStatus = 'DRAFT' | 'DEPLOYED' | 'ROLLBACK' | 'INIT' | 'UPDATED'

/** 字段数据类型枚举 */
export type RelationDataType =
  | 'VARCHAR'
  | 'INTEGER'
  | 'BIGINT'
  | 'DECIMAL'
  | 'BOOLEAN'
  | 'DATE'
  | 'TIMESTAMP'
  | 'TEXT'
  | 'LOOKUP'

/** Lookup 固定过滤 / 派生 join 匹配类型 */
export type LookupMatchType = 'eq' | 'contains' | 'startsWith' | 'endsWith'

/** Lookup 固定过滤条件 */
export interface LookupFilterCondition {
  fieldName: string
  value: string
  matchType?: LookupMatchType
}

/** 派生带出 / 级联的一条关联列 */
export interface LookupJoin {
  /** 父 lookup 引用表里的列（取父所选行该列的值） */
  fromColumn: string
  /** 本 lookup 引用表里的列（用该值去匹配本表的行） */
  toColumn: string
  matchType?: LookupMatchType
}

/** 派生带出 / 级联配置 */
export interface LookupDerivedFrom {
  /** 本表里作为父的另一个 LOOKUP 字段名 */
  parentField: string
  joins: LookupJoin[]
  /** autofill=命中行自动填入本字段；filter=仅收窄本字段候选 */
  derivedMode: 'autofill' | 'filter'
}

/** LOOKUP 字段配置（存于 lookup_config JSONB） */
export interface LookupConfig {
  /** 被引用的关联表 id */
  refTableId?: number
  refTableName?: string
  /** 下拉搜索的列；searchFields[0] 视为存储值(PK)列 */
  searchFields?: string[]
  /** 下拉表格展示的列 */
  displayFields?: string[]
  /** 选中后 tag 标签取的列 */
  selectedDisplayField?: string
  /** 固定预过滤 */
  filterConditions?: LookupFilterCondition[]
  /** 是否展示只读带出面板 */
  showBackfillView?: boolean
  /** 是否多选（值存 PK 数组） */
  multiple?: boolean
  /** 派生带出 / 级联 */
  derivedFrom?: LookupDerivedFrom
}

/** 审计操作类型枚举 */
export type RelationAuditAction = 'ADD' | 'UPDATE' | 'DELETE' | 'STATUS_CHANGE'

/** 字段定义响应 */
export interface FieldDefinitionResponse {
  id: number
  fieldName: string
  dataType: RelationDataType
  length?: number
  precision?: number
  scale?: number
  nullable: boolean
  isPrimaryKey: boolean
  defaultValue?: string
  displayName?: string
  sortOrder: number
  isForeignKey?: boolean
  refTableId?: number
  refPrimaryKeyFields?: string[]
  pkGeneration?: Record<string, unknown>
  fkDisplayMode?: string
  lookupConfig?: LookupConfig
  isComputed?: boolean
  computedField?: Record<string, unknown>
}

/** Function Unit 简要信息 */
export interface FunctionUnitBrief {
  id: string
  code: string
  name: string
}

/** 表定义响应 */
export interface RelationTableResponse {
  id: number
  tableName: string
  displayName: string
  description?: string
  status: RelationTableStatus
  enabled: boolean
  portalVisible: boolean
  currentVersion: number
  /** Function Unit(s) this table belongs to; empty = Common (visible to all Function Units) */
  functionUnits: FunctionUnitBrief[]
  fieldDefinitions: FieldDefinitionResponse[]
  /** 当前管理员对该表的权限级别：READONLY=只读, READ_WRITE=读写 */
  permissionLevel?: 'READONLY' | 'READ_WRITE'
  createdAt: string
  createdBy: string
  updatedAt: string
  updatedBy: string
}

/** 版本历史响应 */
export interface RelationTableVersionResponse {
  id: number
  versionNumber: number
  snapshotData: string
  deployedBy: string
  deployedAt: string
  changeLog?: string
}

/** 权限级别 */
export type RelationPermissionLevel = 'READONLY' | 'READ_WRITE'

/** 访问权限配置 */
export interface RelationTableAccess {
  id: string
  tableId: number
  targetType: string
  targetId: string
  permissionLevel: RelationPermissionLevel
  createdAt: string
  createdBy: string
}

/** 表数据行 */
export interface RelationTableDataRow {
  rowId: string
  tableId: number
  data: Record<string, unknown>
}

/** 审计日志 */
export interface RelationTableAuditLog {
  id: string
  tableId: number
  tableName: string
  rowId?: string
  action: RelationAuditAction
  oldValue?: string
  newValue?: string
  operatorId: string
  operatorName?: string
  operatedAt: string
}

/** 分页结果 */
// ==================== 请求类型 ====================

/** 字段定义请求（创建） */
export interface CreateFieldDefinitionRequest {
  fieldName: string
  dataType: RelationDataType
  length?: number
  precision?: number
  scale?: number
  nullable?: boolean
  isPrimaryKey?: boolean
  defaultValue?: string
  displayName?: string
  sortOrder?: number
  pkGeneration?: Record<string, unknown>
  isForeignKey?: boolean
  refTableId?: number
  refPrimaryKeyFields?: string[]
  fkDisplayMode?: string
  lookupConfig?: LookupConfig
  isComputed?: boolean
  computedField?: Record<string, unknown>
}

/** 创建表请求 */
export interface CreateRelationTableRequest {
  tableName: string
  displayName?: string
  description?: string
  /** Function Unit(s) this table belongs to (sys_function_units.id); empty/undefined = Common */
  functionUnitIds?: string[]
  fieldDefinitions: CreateFieldDefinitionRequest[]
}

/** 字段定义请求（更新） */
export interface UpdateFieldDefinitionRequest {
  id?: number
  fieldName?: string
  dataType?: RelationDataType
  length?: number
  precision?: number
  scale?: number
  nullable?: boolean
  isPrimaryKey?: boolean
  defaultValue?: string
  displayName?: string
  sortOrder?: number
  pkGeneration?: Record<string, unknown>
  isForeignKey?: boolean
  refTableId?: number
  refPrimaryKeyFields?: string[]
  fkDisplayMode?: string
  lookupConfig?: LookupConfig
  isComputed?: boolean
  computedField?: Record<string, unknown>
}

/** 更新表请求 */
export interface UpdateRelationTableRequest {
  tableName?: string
  displayName?: string
  description?: string
  /**
   * Function Unit(s) this table belongs to (sys_function_units.id).
   * undefined = leave unchanged; empty array = clear to Common.
   */
  functionUnitIds?: string[]
  fieldDefinitions?: UpdateFieldDefinitionRequest[]
}

/** 回滚请求 */
export interface RollbackRequest {
  targetVersionId: number
}

/** 表结构页左栏分组：`key` 是 Function Unit code（覆盖该单元的所有版本）或 `__common__`。 */
export interface RelationTableFuGroup {
  key: string
  label: string | null
  count: number
}

export interface RelationTableStructureListQuery {
  page: number
  size: number
  /** Left-rail selection: a Function Unit code (matches every published version), or `__common__`. */
  functionUnitCode?: string
  filters?: Array<ListColumnFilter & { field: string }>
  sortField?: string
  sortDirection?: 'ASC' | 'DESC'
}

export interface RelationTableStructureListPage extends AdminListPage<RelationTableResponse> {
  functionUnitGroups: RelationTableFuGroup[]
}

// ==================== 表结构 API ====================

export const relationTableStructureApi = {
  /** 创建表定义 */
  create: (data: CreateRelationTableRequest) =>
    post<RelationTableResponse>('/relation-tables/structures', data),

  /** 获取表定义列表 */
  list: () =>
    get<RelationTableResponse[]>('/relation-tables/structures'),

  query: (body: RelationTableStructureListQuery) =>
    post<RelationTableStructureListPage>('/relation-tables/structures/query', body),

  /** 检查表名是否全平台可用 */
  checkTableNameAvailable: (tableName: string, excludeTableId?: number) =>
    get<{ available: boolean; tableName: string }>('/relation-tables/structures/name-available', {
      params: { tableName, excludeTableId },
    }),

  /** 获取表定义详情 */
  getById: (id: number) =>
    get<RelationTableResponse>(`/relation-tables/structures/${id}`),

  /** 更新表定义 */
  update: (id: number, data: UpdateRelationTableRequest) =>
    put<RelationTableResponse>(`/relation-tables/structures/${id}`, data),

  /** 删除表定义 */
  delete: (id: number) =>
    del<void>(`/relation-tables/structures/${id}`),

  /** 启用/禁用 */
  setEnabled: (id: number, enabled: boolean) =>
    put<RelationTableResponse>(`/relation-tables/structures/${id}/enabled`, { enabled }),

  /** 门户可见性开关 */
  setPortalVisibility: (id: number, portalVisible: boolean) =>
    put<RelationTableResponse>(`/relation-tables/structures/${id}/portal-visibility`, { portalVisible }),

  /** 部署表结构 */
  deploy: (id: number) =>
    post<RelationTableResponse>(`/relation-tables/structures/${id}/deploy`),

  /** 回滚到指定版本 */
  rollback: (id: number, data: RollbackRequest) =>
    post<RelationTableResponse>(`/relation-tables/structures/${id}/rollback`, data),

  /** 获取版本历史 */
  getVersionHistory: (id: number) =>
    get<RelationTableVersionResponse[]>(`/relation-tables/structures/${id}/versions`),

  /** 获取访问配置 */
  getAccessConfig: (id: number) =>
    get<RelationTableAccess[]>(`/relation-tables/structures/${id}/access`),

  /** 添加访问配置 */
  addAccess: (id: number, targetId: string, permissionLevel: RelationPermissionLevel = 'READ_WRITE', targetType = 'ROLE') =>
    post<RelationTableAccess>(`/relation-tables/structures/${id}/access`, { targetType, targetId, permissionLevel }),

  /** 批量设置访问配置 */
  batchSetAccess: (id: number, targetIds: string[], permissionLevel: RelationPermissionLevel = 'READ_WRITE') =>
    put<void>(`/relation-tables/structures/${id}/access`, { targetIds, permissionLevel }),

  /** 修改某条授权的权限级别 */
  updatePermissionLevel: (id: number, accessId: string, permissionLevel: RelationPermissionLevel) =>
    put<RelationTableAccess>(`/relation-tables/structures/${id}/access/${accessId}`, { permissionLevel }),

  /** 删除访问配置 */
  removeAccess: (id: number, accessId: string) =>
    del<void>(`/relation-tables/structures/${id}/access/${accessId}`)
}

/**
 * 已部署表按 Function Unit 分组的轻量清单（导航侧边栏用）。
 * 按 code 去重：同一单元发布多个版本只出一条，所以这里没有单一的 sys_function_units.id。
 */
export interface FunctionUnitTableGroup {
  functionUnitCode: string
  functionUnitName?: string
  tableCount: number
}

// ==================== 表数据 API ====================

export const relationTableDataApi = {
  /** 获取已部署的表列表 */
  getDeployedTables: () =>
    get<RelationTableResponse[]>('/relation-tables/data/tables'),

  /** 获取已部署表按 Function Unit 分组的轻量清单（导航侧边栏用） */
  getFunctionUnitGroups: () =>
    get<FunctionUnitTableGroup[]>('/relation-tables/data/function-units'),

  /** 分页查询表数据（legacy GET，lookup / 旧调用仍走这里） */
  queryData: (tableId: number, params?: { search?: string; page?: number; size?: number }) =>
    get<PageResult<RelationTableDataRow>>(`/relation-tables/data/${tableId}`, { params }),

  query: (tableId: number, body: {
    page: number
    size: number
    search?: string
    filters?: Array<ListColumnFilter & { field: string }>
    sortField?: string
    sortDirection?: 'ASC' | 'DESC'
  }) =>
    post<AdminListPage<RelationTableDataRow>>(`/relation-tables/data/${tableId}/query`, body),

  /** 新增数据 */
  addData: (tableId: number, data: Record<string, unknown>) =>
    post<RelationTableDataRow>(`/relation-tables/data/${tableId}`, data),

  /** 修改数据 */
  updateData: (tableId: number, rowId: string, data: Record<string, unknown>) =>
    put<RelationTableDataRow>(`/relation-tables/data/${tableId}/${rowId}`, data),

  /** 删除数据 */
  deleteData: (tableId: number, rowId: string) =>
    del<void>(`/relation-tables/data/${tableId}/${rowId}`),

  /** Active/Inactive 状态变更 */
  changeStatus: (tableId: number, rowId: string, status: string) =>
    put<RelationTableDataRow>(`/relation-tables/data/${tableId}/${rowId}/status`, { status }),

  /** 查询审计日志 */
  queryAuditLogs: (
    tableId: number,
    params?: { action?: string; operatorId?: string; startTime?: string; endTime?: string; page?: number; size?: number }
  ) =>
    get<PageResult<RelationTableAuditLog>>(`/relation-tables/data/${tableId}/audit-logs`, { params }),

  /** 导出 CSV */
  exportCsv: (tableId: number, maxRows = 10000) =>
    get<Blob>(`/relation-tables/data/${tableId}/export`, {
      params: { maxRows },
      responseType: 'blob'
    }),

  /** 分配主键值（非 manual 策略） */
  allocatePrimaryKeys: (tableId: number, payload: { fieldName: string; count?: number; scopeKey?: string }) =>
    post<{ values: string[] }>(`/relation-tables/data/${tableId}/primary-keys/allocate`, payload),

  /** Lookup 搜索（供 LOOKUP 字段下拉 + 派生带出）。返回原始行 Map 列表。 */
  searchForLookup: (tableId: number, params: {
    keyword: string
    searchFields: string[]
    displayField: string
    filterConditions?: LookupFilterCondition[]
    limit?: number
    offset?: number
  }) => {
    const query = new URLSearchParams()
    if (params.keyword) query.append('keyword', params.keyword)
    if (params.displayField) query.append('displayField', params.displayField)
    if (params.filterConditions?.length) query.append('filterConditions', JSON.stringify(params.filterConditions))
    if (params.limit) query.append('limit', String(params.limit))
    if (params.offset) query.append('offset', String(params.offset))
    params.searchFields?.forEach(f => query.append('searchFields', f))
    return get<Record<string, unknown>[]>(`/relation-tables/data/${tableId}/search?${query.toString()}`)
  },

  /** 获取 Relation Table 的 View 字段配置（带出面板列） */
  getViewFields: (tableId: number) =>
    get<Array<{ fieldName: string; displayLabel: string; columnWidth?: number; sortOrder: number; visible: boolean }>>(
      `/relation-tables/data/${tableId}/view-fields`),

  /** 下载导入模板 (csv|xlsx) */
  downloadTemplate: (tableId: number, format: 'csv' | 'xlsx' = 'csv') =>
    get<Blob>(`/relation-tables/data/${tableId}/template`, {
      params: { format },
      responseType: 'blob'
    }),

  /** 导入数据 (csv|xlsx)，返回 {inserted, failed, errors} */
  importData: (tableId: number, file: File, format?: 'csv' | 'xlsx', dryRun = false) => {
    const formData = new FormData()
    formData.append('file', file)
    if (format) formData.append('format', format)
    formData.append('dryRun', String(dryRun))
    return post<RelationImportResult>(`/relation-tables/data/${tableId}/import`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
}

/** 导入结果（dryRun=true 时返回 validCount 而非 inserted） */
export interface RelationImportResult {
  dryRun?: boolean
  validCount?: number
  inserted?: number
  failed: number
  errors: Array<{ row: number; field: string; message: string }>
}
