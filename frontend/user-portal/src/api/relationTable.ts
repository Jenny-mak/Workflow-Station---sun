import { request } from './request'
import type { LookupFilterCondition } from '@/utils/lookupFilterConditions'

// ==================== 类型定义 ====================

export interface RelationTableDTO {
  id: number
  tableName: string
  displayName: string
  description?: string
  status: string
  enabled: boolean
  portalVisible: boolean
  currentVersion: number
  /** 当前用户(按 active role)对该表的权限：READONLY=只读, READ_WRITE=读写 */
  permissionLevel?: 'READONLY' | 'READ_WRITE'
}

/** 派生带出 / 级联的一条关联列 */
export interface LookupJoin {
  fromColumn: string
  toColumn: string
  matchType?: 'eq' | 'contains' | 'startsWith' | 'endsWith'
}

/** 派生带出 / 级联配置 */
export interface LookupDerivedFrom {
  parentField: string
  joins: LookupJoin[]
  derivedMode: 'autofill' | 'filter'
}

/** LOOKUP 字段配置（来自 rt_field_definitions.lookup_config） */
export interface LookupConfig {
  refTableId?: number
  refTableName?: string
  searchFields?: string[]
  displayFields?: string[]
  selectedDisplayField?: string
  filterConditions?: LookupFilterCondition[]
  showBackfillView?: boolean
  multiple?: boolean
  derivedFrom?: LookupDerivedFrom
  /** DW form-create binding uses tableId instead of refTableId */
  tableId?: number
}

export interface RelationFieldDef {
  fieldName: string
  dataType: string
  length?: number
  precision?: number
  scale?: number
  nullable?: boolean
  isPrimaryKey?: boolean
  displayName?: string
  sortOrder?: number
  pkGeneration?: Record<string, any>
  lookupConfig?: LookupConfig
}

export interface RelationImportResult {
  dryRun?: boolean
  validCount?: number
  inserted?: number
  failed: number
  errors: Array<{ row: number; field: string; message: string }>
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
  hasPrevious: boolean
  groupCounts?: Record<string, number>
}

// ==================== API ====================

export const relationTableApi = {
  /** 获取用户可见的表列表 */
  getVisibleTables: () =>
    request.get<{ data: RelationTableDTO[] }>('/relation-tables'),

  /** 分页查询表数据（只读；可选 groupBy → response.groupCounts） */
  queryTableData: (tableId: number, params: {
    page?: number
    size?: number
    search?: string
    sortField?: string
    sortDirection?: 'ASC' | 'DESC'
    filters?: string
    groupBy?: string
  }) =>
    request.get<{ data: PageResponse<Record<string, any>> }>(
      `/relation-tables/${tableId}`,
      { params },
    ),

  /** 导出 CSV */
  exportCsv: (tableId: number, maxRows = 10000) =>
    request.get<Blob>(`/relation-tables/${tableId}/export`, {
      params: { maxRows },
      responseType: 'blob'
    }),

  /** Lookup 搜索（limit+offset 服务端分页） */
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
    return request.get<{ data: Record<string, any>[] }>(`/relation-tables/${tableId}/search?${query.toString()}`)
  },

  /** 获取表单的 Lookup 配置 */
  getLookupConfigs: (formId: number) =>
    request.get<{ data: Array<{
      componentId: string
      tableId: number
      searchFields: string
      displayField: string
      viewFields: Array<{ fieldName: string; displayLabel: string; columnWidth?: number; sortOrder: number; visible: boolean }>
    }> }>(`/relation-tables/lookup-configs/${formId}`),

  /** 获取 Relation Table 的 View 字段配置 */
  getViewFields: (tableId: number) =>
    request.get<{ data: Array<{ fieldName: string; displayLabel: string; columnWidth?: number; sortOrder: number; visible: boolean }> }>(`/relation-tables/${tableId}/view-fields`),

  /** 获取字段定义（含类型，供编辑表单使用） */
  getFieldDefinitions: (tableId: number) =>
    request.get<{ data: RelationFieldDef[] }>(`/relation-tables/${tableId}/fields`),

  /** 按策略分配主键值（add-row 自动生成，需要 READ_WRITE） */
  allocatePrimaryKeys: (tableId: number, fieldName: string, count?: number) =>
    request.post<{ data: { values: string[] } }>(`/relation-tables/${tableId}/primary-keys/allocate`, { fieldName, count }),

  /** 新增数据（需要 READ_WRITE） */
  addData: (tableId: number, data: Record<string, any>) =>
    request.post<{ data: Record<string, any> }>(`/relation-tables/${tableId}`, data),

  /** 更新数据（需要 READ_WRITE） */
  updateData: (tableId: number, rowId: string, data: Record<string, any>) =>
    request.put<{ data: Record<string, any> }>(`/relation-tables/${tableId}/${rowId}`, data),

  /** 切换状态 ACTIVE/INACTIVE（需要 READ_WRITE） */
  changeStatus: (tableId: number, rowId: string, status: string) =>
    request.put<{ data: Record<string, any> }>(`/relation-tables/${tableId}/${rowId}/status`, { status }),

  /** 下载导入模板 (csv|xlsx) */
  downloadTemplate: (tableId: number, format: 'csv' | 'xlsx' = 'csv') =>
    request.get<Blob>(`/relation-tables/${tableId}/template`, {
      params: { format },
      responseType: 'blob'
    }),

  /** 导入数据 (csv|xlsx) */
  importData: (tableId: number, file: File, format?: 'csv' | 'xlsx', dryRun = false) => {
    const formData = new FormData()
    formData.append('file', file)
    if (format) formData.append('format', format)
    formData.append('dryRun', String(dryRun))
    return request.post<{ data: RelationImportResult }>(`/relation-tables/${tableId}/import`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
}
