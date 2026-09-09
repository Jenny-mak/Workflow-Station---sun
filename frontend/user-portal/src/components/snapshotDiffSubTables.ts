import type { FormField, FormTab } from './formRendererHelpers'
import { flattenAllFormFieldSegments } from './formRendererHelpers'
import { formatSnapshotDisplayValue } from './snapshotDiffHelpers'
import {
  isCanonicalStoreKey,
  readSubTableRows,
  subTableStoreKey,
  type SubTableStoreBindingLike,
} from '@/composables/tasks/subTableStore'

export interface SnapshotSubTableColumnSource {
  field?: string
  label?: string
  type?: string
  columnType?: string
  displayName?: string
  columnLabel?: string
  props?: Record<string, unknown>
}

/**
 * Form-widget → table connection. {@code bindingId} only finds the binding on the canvas;
 * row identity is {@link subTableStoreKey} (`dw:<name>` / `rt:<name>`).
 */
export interface SnapshotSubTableBindingSource extends SubTableStoreBindingLike {
  bindingId: number
  tableType?: string
  bindingType?: string
  columns?: SnapshotSubTableColumnSource[]
  /**
   * 这张表配置的主键列（`dw_field_definitions.is_primary_key`）。
   *
   * <p>快照行与实时行按身份配对，而身份是配置：以 `correspondence_id` 为主键的表，
   * 不匹配任何「像主键的列名」名单。缺省时只认平台生成键。
   */
  primaryKeyFields?: string[] | null
}

export interface SnapshotSubTableTarget {
  storeKey: string
  fallbackLabel: string
}

export interface SnapshotSubTableColumn {
  field: string
  label: string
  type?: string
}

export interface SnapshotSubTableSection {
  storeKey: string
  tableLabel: string
  columns: SnapshotSubTableColumn[]
  snapshotRows: Record<string, unknown>[]
}

const SKIP_COL_TYPES = new Set(['linkForm', 'subTable', 'inlineSubForm'])

function designerLabel(raw: string): string {
  const label = String(raw || '').trim()
  return label && !label.startsWith('__') ? label : ''
}

function snapshotBag(snapshotValues: Record<string, unknown>): Record<string, unknown> | null {
  const bag = snapshotValues.__subTables__
  if (!bag || typeof bag !== 'object' || Array.isArray(bag)) return null
  return bag as Record<string, unknown>
}

function asPlainRows(raw: unknown): Record<string, unknown>[] {
  if (!Array.isArray(raw)) return []
  return raw.filter((row): row is Record<string, unknown> =>
    !!row && typeof row === 'object' && !Array.isArray(row))
}

function widgetBinding(
  bindings: SnapshotSubTableBindingSource[] | undefined,
  bindingId: number,
): SnapshotSubTableBindingSource | undefined {
  return (bindings || []).find(item => Number(item.bindingId) === bindingId)
}

function bindingForStoreKey(
  bindings: SnapshotSubTableBindingSource[] | undefined,
  storeKey: string,
): SnapshotSubTableBindingSource | undefined {
  return (bindings || []).find(item => subTableStoreKey(item) === storeKey)
}

/** Form-order sub-table widgets, keyed by the table store key (not bindingId). */
export function collectSnapshotSubTableTargets(
  fields: FormField[],
  bindings?: SnapshotSubTableBindingSource[],
  tabs?: FormTab[],
  fieldsAfterTabs?: FormField[],
): SnapshotSubTableTarget[] {
  const out: SnapshotSubTableTarget[] = []
  const seen = new Set<string>()
  for (const field of flattenAllFormFieldSegments(fields, tabs, fieldsAfterTabs)) {
    if (field.type !== 'subTable' && field.type !== 'inlineSubForm') continue
    const bindingId = field._bindingId != null ? Number(field._bindingId) : Number.NaN
    if (!Number.isFinite(bindingId)) continue
    const binding = widgetBinding(bindings, bindingId)
    if (!binding || isSnapshotRelationLikeBinding(binding)) continue
    const storeKey = subTableStoreKey(binding)
    if (!storeKey || seen.has(storeKey)) continue
    seen.add(storeKey)
    out.push({ storeKey, fallbackLabel: designerLabel(field.label) })
  }
  return out
}

/** Rows for one designer table. BindingId keys are not a data identity. */
export function snapshotSubTableRows(
  snapshotValues: Record<string, unknown>,
  bindingOrStoreKey: SnapshotSubTableBindingSource | string,
): Record<string, unknown>[] {
  const bag = snapshotBag(snapshotValues)
  if (!bag) return []
  if (typeof bindingOrStoreKey === 'string') {
    return isCanonicalStoreKey(bindingOrStoreKey) ? asPlainRows(bag[bindingOrStoreKey]) : []
  }
  return asPlainRows(readSubTableRows(bag, bindingOrStoreKey))
}

function columnField(col: SnapshotSubTableColumnSource): string {
  const fromProps = col.props && typeof col.props.field === 'string' ? col.props.field : ''
  return String(col.field || fromProps || '').trim()
}

function columnType(col: SnapshotSubTableColumnSource): string {
  const fromProps = col.props && typeof col.props.columnType === 'string' ? String(col.props.columnType) : ''
  return String(col.columnType || col.type || fromProps || '').trim()
}

function columnLabel(col: SnapshotSubTableColumnSource, field: string): string {
  const fromProps = col.props && typeof col.props.columnLabel === 'string' ? col.props.columnLabel : ''
  return String(col.label || col.columnLabel || col.displayName || fromProps || field).trim()
}

export function snapshotSubTableColumns(binding?: SnapshotSubTableBindingSource): SnapshotSubTableColumn[] {
  const out: SnapshotSubTableColumn[] = []
  const seen = new Set<string>()
  for (const col of binding?.columns || []) {
    const field = columnField(col)
    if (!field || field.startsWith('__') || seen.has(field)) continue
    if (SKIP_COL_TYPES.has(columnType(col))) continue
    const label = columnLabel(col, field)
    if (!label || label.startsWith('__')) continue
    seen.add(field)
    out.push({ field, label, type: columnType(col) || undefined })
  }
  return out
}

function columnsFromRowKeys(rows: Record<string, unknown>[]): SnapshotSubTableColumn[] {
  const first = rows[0]
  if (!first) return []
  return Object.keys(first)
    .filter(key => key && !key.startsWith('__') && key !== 'id')
    .slice(0, 12)
    .map(field => ({ field, label: field.replace(/_/g, ' '), type: 'text' }))
}

/** Lookup catalogs and main-table bindings are not process sub-tables. */
export function isSnapshotRelationLikeBinding(binding?: SnapshotSubTableBindingSource): boolean {
  const bindingType = String(binding?.bindingType || '').toUpperCase()
  const tableType = String(binding?.tableType || '').toUpperCase()
  if (bindingType === 'RELATED' || bindingType === 'PRIMARY') return true
  return tableType === 'RELATION' || tableType === 'MAIN' || tableType === 'LOOKUP'
}

function toSnapshotSubTableSection(
  storeKey: string,
  fallbackLabel: string,
  snapshotValues: Record<string, unknown>,
  bindings?: SnapshotSubTableBindingSource[],
): SnapshotSubTableSection | null {
  const binding = bindingForStoreKey(bindings, storeKey)
  if (isSnapshotRelationLikeBinding(binding)) return null
  const tableLabel = String(binding?.tableName || fallbackLabel || '').trim()
  const snapshotRows = snapshotSubTableRows(snapshotValues, storeKey)
  if (snapshotRows.length === 0) return null
  let columns = snapshotSubTableColumns(binding)
  if (columns.length === 0) columns = columnsFromRowKeys(snapshotRows)
  if (!tableLabel && columns.length === 0) return null
  return { storeKey, tableLabel, columns, snapshotRows }
}

function snapshotBagStoreKeys(snapshotValues: Record<string, unknown>): string[] {
  const bag = snapshotBag(snapshotValues)
  if (!bag) return []
  return Object.keys(bag).filter(isCanonicalStoreKey)
}

function pushUniqueSection(
  sections: SnapshotSubTableSection[],
  seenKeys: Set<string>,
  section: SnapshotSubTableSection,
): void {
  if (seenKeys.has(section.storeKey)) return
  seenKeys.add(section.storeKey)
  sections.push(section)
}

export function buildSnapshotSubTableSections(
  fields: FormField[],
  snapshotValues: Record<string, unknown>,
  bindings?: SnapshotSubTableBindingSource[],
  tabs?: FormTab[],
  fieldsAfterTabs?: FormField[],
): SnapshotSubTableSection[] {
  const sections: SnapshotSubTableSection[] = []
  const seenKeys = new Set<string>()
  for (const target of collectSnapshotSubTableTargets(fields, bindings, tabs, fieldsAfterTabs)) {
    const section = toSnapshotSubTableSection(
      target.storeKey, target.fallbackLabel, snapshotValues, bindings,
    )
    if (section) pushUniqueSection(sections, seenKeys, section)
  }
  for (const storeKey of snapshotBagStoreKeys(snapshotValues)) {
    if (seenKeys.has(storeKey)) continue
    const section = toSnapshotSubTableSection(storeKey, '', snapshotValues, bindings)
    if (!section || !section.tableLabel) continue
    pushUniqueSection(sections, seenKeys, section)
  }
  return sections
}

export function formatSnapshotSubTableCell(
  row: Record<string, unknown>,
  field: string,
  type?: string,
): string {
  return formatSnapshotDisplayValue(row[field], type ? { key: field, label: field, type } : undefined)
}
