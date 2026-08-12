import type { AxiosRequestConfig } from 'axios'
import { request } from './request'

export type ImportProgressPhase = 'upload' | 'process'
export type ImportProgressCallback = (percent: number, phase: ImportProgressPhase) => void

export interface FunctionUnitViewMenuItem {
  functionUnitId: string
  functionUnitCode: string
  functionUnitName: string
  viewCount: number
}

export interface MainTableViewSummary {
  id: number
  viewName: string
  isDefault?: boolean
  tableId?: number | null
  tableLabel?: string | null
  enableExport?: boolean
  enableImport?: boolean
}

export type MainTableViewColumnType = 'field' | 'lookup_display' | 'fk_display'

export interface MainTableViewFieldColumn {
  fieldName: string
  displayLabel: string
  columnWidth?: number
  systemField?: boolean
  // FK drill-down: link to the referenced table's published default view, filtered by this cell's value.
  isForeignKey?: boolean
  refViewId?: number | null
  refFunctionUnitCode?: string | null
  refPrimaryKeyFields?: string[] | null
  // Lookup drill-down: link to the referenced Relation Table's data, filtered by this cell's value.
  isLookup?: boolean
  lookupTableId?: number | null
  columnType?: MainTableViewColumnType | null
  lookupSourceField?: string | null
  lookupDisplayField?: string | null
  lookupSelectedDisplayField?: string | null
  lookupSearchFields?: string[] | null
  /** For fk_display: DW table id of the FK target (e.g. Case table). */
  fkRefTableId?: number | null
}

export interface MainTableViewDataRow {
  processInstanceId: string
  values: Record<string, unknown>
}

export interface MainTableViewGroupCount {
  label: string
  count: number
}

export interface MainTableViewColumnFilterParam {
  operator: string
  value?: string
}

export interface MainTableViewQueryParams {
  page?: number
  size?: number
  search?: string
  /** Map of fieldName → { operator, value } (serialized as JSON query param). */
  filters?: Record<string, MainTableViewColumnFilterParam>
  sortField?: string
  sortDirection?: 'ASC' | 'DESC'
  groupBy?: string
}

export interface MainTableViewDataPage {
  columns: MainTableViewFieldColumn[]
  rows: MainTableViewDataRow[]
  total: number
  page: number
  size: number
  /** Full filtered-set counts when groupBy was requested. */
  groupCounts?: MainTableViewGroupCount[]
}

export interface MainTableViewImportResult {
  createdCount: number
  updatedCount: number
  skippedCount: number
  errorCount: number
  errors: string[]
}

export const mainTableViewApi = {
  listFunctionUnits: () =>
    request.get<{ data: FunctionUnitViewMenuItem[] }>('/main-table-views/function-units'),

  listViews: (functionUnitCode: string) =>
    request.get<{ data: MainTableViewSummary[] }>(
      `/main-table-views/function-units/${encodeURIComponent(functionUnitCode)}/views`,
    ),

  queryData: (viewId: number, params: MainTableViewQueryParams = {}) => {
    const { filters, ...rest } = params
    const query: Record<string, string | number | undefined> = { ...rest }
    if (filters && Object.keys(filters).length > 0) {
      query.filters = JSON.stringify(filters)
    }
    return request.get<{ data: MainTableViewDataPage }>(`/main-table-views/${viewId}/data`, {
      params: query,
    })
  },

  exportCsv: (viewId: number, maxRows = 10000) =>
    request.get<Blob>(`/main-table-views/${viewId}/export`, {
      params: { maxRows },
      responseType: 'blob',
    }),

  importCsv: (viewId: number, file: File, onProgress?: ImportProgressCallback) => {
    const formData = new FormData()
    formData.append('file', file)
    let uploadFinished = false
    return request.post<{ data: MainTableViewImportResult }>(
      `/main-table-views/${viewId}/import`,
      formData,
      {
        headers: { 'Content-Type': undefined },
        skipGlobalErrorHandler: true,
        onUploadProgress: progressEvent => {
          if (!onProgress) return
          const total = progressEvent.total ?? 0
          if (total > 0) {
            const ratio = progressEvent.loaded / total
            onProgress(Math.min(50, Math.round(ratio * 50)), 'upload')
            if (progressEvent.loaded >= total && !uploadFinished) {
              uploadFinished = true
              onProgress(52, 'process')
            }
          } else if (!uploadFinished) {
            onProgress(15, 'upload')
          }
        },
      } as AxiosRequestConfig & { skipGlobalErrorHandler?: boolean },
    )
  },
}
