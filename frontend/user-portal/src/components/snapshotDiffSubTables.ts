import type { FormField, FormTab } from './formRendererHelpers'
import { flattenAllFormFieldSegments } from './formRendererHelpers'
import { formatSnapshotDisplayValue } from './snapshotDiffHelpers'

export interface SnapshotSubTableColumnSource {
  field?: string
  label?: string
  type?: string
  columnType?: string
  displayName?: string
  columnLabel?: string
  props?: Record<string, unknown>
}

export interface SnapshotSubTableBindingSource {
  bindingId: number
  tableId?: number | null
  tableName?: string
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
  bindingId: number
  fallbackLabel: string
}

export interface SnapshotSubTableColumn {
  field: string
  label: string
  type?: string
}

export interface SnapshotSubTableSection {
  bindingId: number
  tableLabel: string
  columns: SnapshotSubTableColumn[]
  snapshotRows: Record<string, unknown>[]
}

const SKIP_COL_TYPES = new Set(['linkForm', 'subTable', 'inlineSubForm'])

function designerLabel(raw: string): string {
  const label = String(raw || '').trim()
  return label && !label.startsWith('__') ? label : ''
}

/** Form-order sub-table / inline-sub-form widgets (designer labels, not `__subTable_*` keys). */
export function collectSnapshotSubTableTargets(
  fields: FormField[],
  tabs?: FormTab[],
  fieldsAfterTabs?: FormField[],
): SnapshotSubTableTarget[] {
  const out: SnapshotSubTableTarget[] = []
  const seen = new Set<number>()
  for (const field of flattenAllFormFieldSegments(fields, tabs, fieldsAfterTabs)) {
    if (field.type !== 'subTable' && field.type !== 'inlineSubForm') continue
    const bindingId = field._bindingId != null ? Number(field._bindingId) : Number.NaN
    if (!Number.isFinite(bindingId) || seen.has(bindingId)) continue
    seen.add(bindingId)
    out.push({ bindingId, fallbackLabel: designerLabel(field.label) })
  }
  return out
}

export function snapshotSubTableRows(
  snapshotValues: Record<string, unknown>,
  bindingId: number,
): Record<string, unknown>[] {
  const bag = snapshotValues.__subTables__
  if (!bag || typeof bag !== 'object' || Array.isArray(bag)) return []
  const rec = bag as Record<string, unknown>
  const raw = rec[bindingId] ?? rec[String(bindingId)]
  if (!Array.isArray(raw)) return []
  return raw.filter((row): row is Record<string, unknown> => !!row && typeof row === 'object' && !Array.isArray(row))
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

function bindingById(
  bindings: SnapshotSubTableBindingSource[] | undefined,
  bindingId: number,
): SnapshotSubTableBindingSource | undefined {
  return (bindings || []).find(item => Number(item.bindingId) === bindingId)
}

function normalizeTableName(name: string): string {
  return name.trim().toLowerCase().replace(/\s+/g, ' ')
}

/** Same physical table may appear as several binding ids (MI sibling / alias). */
export function snapshotTableSiblingBindingIds(
  bindingId: number,
  bindings?: SnapshotSubTableBindingSource[],
): number[] {
  const ids = new Set<number>([bindingId])
  const self = bindingById(bindings, bindingId)
  if (!self) return [...ids]
  const tableId = self.tableId != null ? Number(self.tableId) : Number.NaN
  const name = normalizeTableName(String(self.tableName || ''))
  for (const item of bindings || []) {
    const otherId = Number(item.bindingId)
    if (!Number.isFinite(otherId)) continue
    const otherTableId = item.tableId != null ? Number(item.tableId) : Number.NaN
    if (Number.isFinite(tableId) && tableId > 0 && otherTableId === tableId) {
      ids.add(otherId)
      continue
    }
    if (name && normalizeTableName(String(item.tableName || '')) === name) ids.add(otherId)
  }
  return [...ids]
}

/** Lookup catalogs and main-table bindings are not process sub-tables. */
export function isSnapshotRelationLikeBinding(binding?: SnapshotSubTableBindingSource): boolean {
  const bindingType = String(binding?.bindingType || '').toUpperCase()
  const tableType = String(binding?.tableType || '').toUpperCase()
  if (bindingType === 'RELATED' || bindingType === 'PRIMARY') return true
  return tableType === 'RELATION' || tableType === 'MAIN' || tableType === 'LOOKUP'
}

function sectionDedupeKey(
  section: SnapshotSubTableSection,
  binding?: SnapshotSubTableBindingSource,
): string {
  const tableId = binding?.tableId != null ? Number(binding.tableId) : Number.NaN
  if (Number.isFinite(tableId) && tableId > 0) return `tid:${tableId}`
  const name = normalizeTableName(section.tableLabel)
  return name ? `name:${name}` : `bid:${section.bindingId}`
}

function toSnapshotSubTableSection(
  bindingId: number,
  fallbackLabel: string,
  snapshotValues: Record<string, unknown>,
  bindings?: SnapshotSubTableBindingSource[],
): SnapshotSubTableSection | null {
  const binding = bindingById(bindings, bindingId)
  if (isSnapshotRelationLikeBinding(binding)) return null
  const tableLabel = String(binding?.tableName || fallbackLabel || '').trim()
  const snapshotRows = snapshotSubTableRows(snapshotValues, bindingId)
  if (snapshotRows.length === 0) return null
  let columns = snapshotSubTableColumns(binding)
  if (columns.length === 0) columns = columnsFromRowKeys(snapshotRows)
  if (!tableLabel && columns.length === 0) return null
  return { bindingId, tableLabel, columns, snapshotRows }
}

function snapshotBagBindingIds(snapshotValues: Record<string, unknown>): number[] {
  const bag = snapshotValues.__subTables__
  if (!bag || typeof bag !== 'object' || Array.isArray(bag)) return []
  return Object.keys(bag as Record<string, unknown>)
    .map(key => Number(key))
    .filter(id => Number.isFinite(id))
}

function pushUniqueSection(
  sections: SnapshotSubTableSection[],
  seenKeys: Map<string, number>,
  section: SnapshotSubTableSection,
  bindings?: SnapshotSubTableBindingSource[],
): void {
  const key = sectionDedupeKey(section, bindingById(bindings, section.bindingId))
  const existingIdx = seenKeys.get(key)
  if (existingIdx == null) {
    seenKeys.set(key, sections.length)
    sections.push(section)
    return
  }
  if (section.snapshotRows.length > sections[existingIdx].snapshotRows.length) {
    sections[existingIdx] = section
  }
}

export function buildSnapshotSubTableSections(
  fields: FormField[],
  snapshotValues: Record<string, unknown>,
  bindings?: SnapshotSubTableBindingSource[],
  tabs?: FormTab[],
  fieldsAfterTabs?: FormField[],
): SnapshotSubTableSection[] {
  const sections: SnapshotSubTableSection[] = []
  const seenIds = new Set<number>()
  const seenKeys = new Map<string, number>()
  for (const target of collectSnapshotSubTableTargets(fields, tabs, fieldsAfterTabs)) {
    const section = toSnapshotSubTableSection(
      target.bindingId, target.fallbackLabel, snapshotValues, bindings,
    )
    if (!section) continue
    seenIds.add(section.bindingId)
    pushUniqueSection(sections, seenKeys, section, bindings)
  }
  for (const bindingId of snapshotBagBindingIds(snapshotValues)) {
    if (seenIds.has(bindingId)) continue
    const section = toSnapshotSubTableSection(bindingId, '', snapshotValues, bindings)
    if (!section || !section.tableLabel) continue
    seenIds.add(bindingId)
    pushUniqueSection(sections, seenKeys, section, bindings)
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
