import type { ColumnType, DialogColumn } from './types'
import { stampCannotDownloadProp } from '@/utils/applyUploadPropsFromRule'
import { isAnyUploadType } from '@platform-shared/upload/uploadRuleType'

/** Stored upload path/URL from the platform file service. */
export function isStoredFileUrl(value: unknown): value is string {
  if (typeof value !== 'string' || !value.trim()) return false
  return /\/upload\/files\//i.test(value.trim())
}

/** DB / designer field names that store file URLs (not display-name companion columns). */
export function isLikelyFileStorageFieldName(fieldName: string): boolean {
  const f = String(fieldName || '').trim().toLowerCase()
  if (!f) return false
  if (f === 'file_name' || f === 'filename' || f.endsWith('_name')) return false
  return f === 'file' || f === 'fileupload' || f === 'attachment' || f.endsWith('_file')
}

/** Whether a sub-table column should render as upload (dialog + table). */
export function isUploadColumn(
  col: Pick<DialogColumn, 'type' | 'field'>,
  cellValue?: unknown,
): boolean {
  if (isAnyUploadType(col.type)) return true
  if (isStoredFileUrl(cellValue)) return true
  return isLikelyFileStorageFieldName(col.field)
}

export function inferColumnTypeFromFieldAndValue(
  fieldName: string,
  sampleValue?: unknown,
): ColumnType | undefined {
  if (isStoredFileUrl(sampleValue)) return 'upload'
  if (isLikelyFileStorageFieldName(fieldName)) return 'upload'
  return undefined
}

/**
 * Infer portal column type from sub-list view field metadata when subForm rules are absent
 * (common for nested sub-tables that only define subListViews columns).
 */
export function resolveListColumnFieldType(
  column: { dataType?: string; fieldType?: string; fieldName?: string },
  fieldRule?: { type?: string } | null,
  baseColumn?: { type?: string } | null,
): ColumnType | undefined {
  if (baseColumn?.type && baseColumn.type !== 'text') return baseColumn.type as ColumnType
  if (fieldRule?.type === 'upload' || fieldRule?.type === 'advancedUpload') return 'upload'
  const dt = String(column.dataType || column.fieldType || '').toUpperCase()
  if (dt === 'FILE') return 'upload'
  if (column.fieldName && isLikelyFileStorageFieldName(column.fieldName)) return 'upload'
  if (baseColumn?.type) return baseColumn.type as ColumnType
  return undefined
}

const DEFAULT_UPLOAD_PROPS = {
  action: '/api/v1/upload',
  accept: '',
} as const

/** Merge a subListViews field column with optional subForm rule/base column metadata. */
export function mergeListViewFieldColumn(
  column: {
    fieldName: string
    comment?: string
    displayName?: string
    columnLabel?: string
    minWidth?: number
    dataType?: string
    fieldType?: string
  },
  baseColumn?: Partial<DialogColumn> | null,
  fieldRule?: { type?: string; title?: string; props?: Record<string, unknown>; cannotDownload?: unknown; canNotDownload?: unknown } | null,
  blockedFieldKeys?: Set<string>,
): DialogColumn {
  const type = resolveListColumnFieldType(column, fieldRule, baseColumn)
  const label = column.displayName || column.columnLabel || baseColumn?.label || fieldRule?.title || column.fieldName
  const minWidth = column.minWidth || baseColumn?.minWidth || (type === 'upload' ? 180 : 100)
  const props: Record<string, unknown> = { ...(baseColumn?.props || {}) }
  if (type === 'upload') {
    if (props.action == null) props.action = fieldRule?.props?.action ?? DEFAULT_UPLOAD_PROPS.action
    if (props.accept == null) props.accept = fieldRule?.props?.accept ?? DEFAULT_UPLOAD_PROPS.accept
    if (fieldRule?.props?.multiple != null && props.multiple == null) props.multiple = fieldRule.props.multiple
    if (fieldRule?.props?.maxFiles != null && props.maxFiles == null) props.maxFiles = fieldRule.props.maxFiles
    if (fieldRule?.props?.maxFileSizeMb != null && props.maxFileSizeMb == null) {
      props.maxFileSizeMb = fieldRule.props.maxFileSizeMb
    }
    if (fieldRule?.props?.limit != null && props.limit == null) props.limit = fieldRule.props.limit
    if (fieldRule?.props?.fileNameTargetField != null) {
      props.fileNameTargetField = fieldRule.props.fileNameTargetField
    }
    stampCannotDownloadProp(props, fieldRule?.props, column.fieldName, blockedFieldKeys, fieldRule)
  }
  return {
    ...(baseColumn || {}),
    field: column.fieldName,
    label,
    ...(type ? { type } : {}),
    minWidth,
    ...(Object.keys(props).length > 0 ? { props } : {}),
  }
}

/** Upgrade inferred/plain-text columns to upload when field metadata or sample values indicate FILE storage. */
export function normalizeSubTableColumns(
  columns: DialogColumn[],
  sampleRows?: Array<Record<string, unknown>>,
): DialogColumn[] {
  const row0 = sampleRows?.[0]
  return columns.map(col => {
    if (isAnyUploadType(col.type)) return col
    const sample = row0?.[col.field]
    if (!isUploadColumn(col, sample)) return col
    return mergeListViewFieldColumn(
      { fieldName: col.field, comment: col.label, dataType: 'FILE' },
      col,
      null,
    )
  })
}
