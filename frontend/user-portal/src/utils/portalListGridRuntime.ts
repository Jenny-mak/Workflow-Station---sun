import {
  clampColumnWidth,
  COLUMN_WIDTH_MAX,
  COLUMN_WIDTH_MIN,
} from '@/utils/mainTableViewColumnResizeCursor'

export { clampColumnWidth, COLUMN_WIDTH_MAX, COLUMN_WIDTH_MIN }

/** Same operators as Main Table Views / DW DesignerList, plus the calendar-day ones. */
export type PortalListFilterOperator =
  | 'contains'
  | 'eq'
  | 'ne'
  | 'startsWith'
  | 'endsWith'
  | 'notContains'
  | 'isNull'
  | 'isNotNull'
  | 'on'
  | 'before'
  | 'after'
  | 'between'

export interface PortalListColumnFilter {
  operator: PortalListFilterOperator
  value: string
}

/** Mirrors the backend `PortalListColumnMeta.Kind`. */
export type PortalListColumnKind = 'TEXT' | 'ENUM' | 'USER' | 'DATETIME'

/**
 * What a list column is and what the backend will accept for it.
 * Served by the owning list's `/columns` endpoint so the header menu and the whitelist
 * that actually filters the query can never drift apart.
 */
export interface PortalListColumnMeta {
  field: string
  kind: PortalListColumnKind
  filterable: boolean
  sortable: boolean
  groupable: boolean
  operators: PortalListFilterOperator[]
  options: string[]
}

/** A choice offered for an ENUM / USER column; the label is already localized. */
export interface PortalListFilterOption {
  value: string
  label: string
}

/** Matches `PortalColumnFilterSupport.DATE_RANGE_SEPARATOR`. */
export const PORTAL_LIST_DATE_RANGE_SEPARATOR = ','

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/

/**
 * Calendar days carried by a date filter value (`between` carries two).
 * Returns an empty array when the value is not readable as such, which the dialog treats
 * as "nothing picked yet" and the client-side matcher treats as no match.
 */
export function parsePortalListFilterDays(
  operator: PortalListFilterOperator,
  value: string,
): string[] {
  const expected = operator === 'between' ? 2 : 1
  const parts = String(value ?? '').split(PORTAL_LIST_DATE_RANGE_SEPARATOR)
  if (parts.length !== expected) return []
  const days = parts.map(p => p.trim().slice(0, 10))
  return days.every(d => ISO_DAY.test(d)) ? days : []
}

export function formatPortalListFilterDays(days: string[]): string {
  return days.join(PORTAL_LIST_DATE_RANGE_SEPARATOR)
}

/**
 * Drop persisted filters the backend would now reject — the column is gone, is no longer
 * filterable, or the operator was never valid for its kind. Session state outlives deploys,
 * so without this a stale filter would turn every page load into a 400.
 */
export function pruneUnsupportedFilters(
  state: PortalListColumnState,
  columns: PortalListColumnMeta[],
): boolean {
  if (!columns.length) return false
  const byField = new Map(columns.map(c => [c.field, c]))
  let changed = false
  for (const field of Object.keys(state.filters)) {
    const column = byField.get(field)
    if (column?.filterable && column.operators.includes(state.filters[field].operator)) continue
    delete state.filters[field]
    changed = true
  }
  return changed
}

export type PortalListSortDirection = 'ASC' | 'DESC'

export interface PortalListColumnState {
  columnOrder: string[]
  groupBy: string | null
  widths: Record<string, number>
  filters: Record<string, PortalListColumnFilter>
  sort: { field: string; direction: PortalListSortDirection } | null
}

export type PortalListGroupHeader = {
  _isGroupHeader: true
  _groupLabel: string
  _groupCount: number
}

export type PortalListDisplayRow<T> = (T & { _isGroupHeader?: false }) | PortalListGroupHeader

export function createDefaultPortalListColumnState(): PortalListColumnState {
  return {
    columnOrder: [],
    groupBy: null,
    widths: {},
    filters: {},
    sort: null,
  }
}

export function defaultColumnWidth(field: string, fallback = 140): number {
  void field
  return fallback
}

export function columnWidthOf(
  state: PortalListColumnState,
  field: string,
  fallback = 140,
): number {
  return state.widths[field] ?? fallback
}

export function setColumnWidthOf(
  state: PortalListColumnState,
  field: string,
  width: number,
): void {
  state.widths[field] = clampColumnWidth(width)
}

/**
 * Merge known fields into columnOrder: keep persisted order for known fields,
 * append any new fields at the end.
 */
export function initColumnOrder(fields: string[], state: PortalListColumnState): void {
  if (!fields.length) {
    state.columnOrder = []
    return
  }
  if (!state.columnOrder.length) {
    state.columnOrder = [...fields]
    return
  }
  const existing = new Set(state.columnOrder)
  const merged = state.columnOrder.filter(n => fields.includes(n))
  for (const n of fields) {
    if (!existing.has(n)) merged.push(n)
  }
  state.columnOrder = merged
}

/** Fields in persisted order (falls back to input order when empty). */
export function orderedFields(fields: string[], state: PortalListColumnState): string[] {
  if (!state.columnOrder.length) return fields
  const set = new Set(fields)
  const ordered = state.columnOrder.filter(f => set.has(f))
  for (const f of fields) {
    if (!ordered.includes(f)) ordered.push(f)
  }
  return ordered
}

export function moveColumn(
  state: PortalListColumnState,
  field: string,
  direction: 'left' | 'right',
): void {
  const idx = state.columnOrder.indexOf(field)
  if (idx < 0) return
  const target = direction === 'left' ? idx - 1 : idx + 1
  if (target < 0 || target >= state.columnOrder.length) return
  const next = [...state.columnOrder]
  ;[next[idx], next[target]] = [next[target], next[idx]]
  state.columnOrder = next
}

export function activeFiltersForApi(
  filters: Record<string, PortalListColumnFilter>,
): Record<string, PortalListColumnFilter> {
  const out: Record<string, PortalListColumnFilter> = {}
  for (const [field, f] of Object.entries(filters)) {
    if (!f) continue
    if (f.operator === 'isNull' || f.operator === 'isNotNull') {
      out[field] = { operator: f.operator, value: '' }
      continue
    }
    if (f.value?.trim()) {
      out[field] = { operator: f.operator, value: f.value.trim() }
    }
  }
  return out
}

/** Client-side MTV-aligned filter match (for small lists / current-page chrome). */
export function matchPortalListFilter(
  cell: unknown,
  filter: PortalListColumnFilter,
): boolean {
  const text = cell == null ? '' : String(cell)
  const expected = filter.value ?? ''
  switch (filter.operator) {
    case 'eq':
      return text.toLowerCase() === expected.toLowerCase()
    case 'ne':
      return text.toLowerCase() !== expected.toLowerCase()
    case 'contains':
      return text.toLowerCase().includes(expected.toLowerCase())
    case 'notContains':
      return !text.toLowerCase().includes(expected.toLowerCase())
    case 'startsWith':
      return text.toLowerCase().startsWith(expected.toLowerCase())
    case 'endsWith':
      return text.toLowerCase().endsWith(expected.toLowerCase())
    case 'isNull':
      return text.trim() === ''
    case 'isNotNull':
      return text.trim() !== ''
    case 'on':
    case 'before':
    case 'after':
    case 'between': {
      const days = parsePortalListFilterDays(filter.operator, expected)
      const cellDay = text.trim().slice(0, 10)
      if (!days.length || !ISO_DAY.test(cellDay)) return false
      if (filter.operator === 'on') return cellDay === days[0]
      if (filter.operator === 'before') return cellDay < days[0]
      if (filter.operator === 'after') return cellDay > days[0]
      const [from, to] = days[0] <= days[1] ? days : [days[1], days[0]]
      return cellDay >= from && cellDay <= to
    }
    default:
      return true
  }
}

export function comparePortalListValues(a: unknown, b: unknown): number {
  if (a == null && b == null) return 0
  if (a == null) return -1
  if (b == null) return 1
  if (typeof a === 'number' && typeof b === 'number') return a - b
  const sa = String(a)
  const sb = String(b)
  const na = Number(sa)
  const nb = Number(sb)
  if (!Number.isNaN(na) && !Number.isNaN(nb) && sa.trim() !== '' && sb.trim() !== '') {
    return na - nb
  }
  return sa.localeCompare(sb, undefined, { sensitivity: 'base' })
}

/**
 * Filter then sort a full in-memory list (same operators as Main Table Views).
 * Used when the API returns an array (e.g. delegation rules) rather than a page.
 */
export function applyPortalListClientRuntime<T extends Record<string, unknown>>(
  rows: T[],
  filters: Record<string, PortalListColumnFilter>,
  sort: { field: string; direction: PortalListSortDirection } | null,
  getCell?: (row: T, field: string) => unknown,
): T[] {
  const active = activeFiltersForApi(filters)
  const cellOf = getCell ?? ((row: T, field: string) => row[field])
  let out = rows
  const entries = Object.entries(active)
  if (entries.length) {
    out = out.filter(row =>
      entries.every(([field, filter]) => matchPortalListFilter(cellOf(row, field), filter)),
    )
  }
  if (sort?.field) {
    const dir = sort.direction === 'DESC' ? -1 : 1
    out = [...out].sort(
      (a, b) => comparePortalListValues(cellOf(a, sort.field), cellOf(b, sort.field)) * dir,
    )
  }
  return out
}

/** Coerce API groupCounts (Map / object / Long) to Record<label, number>. */
export function normalizeGroupCounts(raw: unknown): Record<string, number> | null {
  if (!raw || typeof raw !== 'object') return null
  const out: Record<string, number> = {}
  for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
    const n = Number(v)
    if (!Number.isNaN(n)) out[k] = n
  }
  return Object.keys(out).length ? out : null
}

/**
 * Insert group headers for rows already ordered by groupBy (or unsorted).
 * When `counts` is provided (server full-set groupCounts), walk the page in order
 * and use those counts — same as MTV `applyGroupHeadersWithCounts`.
 * Otherwise counts are from the given row set (page or full client list).
 */
export function applyGroupHeaders<T>(
  rows: T[],
  groupBy: string | null,
  getCell?: (row: T, field: string) => unknown,
  counts?: Record<string, number> | null,
): PortalListDisplayRow<T>[] {
  if (!groupBy) return rows as PortalListDisplayRow<T>[]
  const cellOf = getCell ?? ((row: T, field: string) => (row as Record<string, unknown>)[field])
  const labelOf = (row: T): string => {
    const raw = cellOf(row, groupBy)
    return raw == null || String(raw).trim() === '' ? '—' : String(raw)
  }

  if (counts) {
    const out: PortalListDisplayRow<T>[] = []
    let lastLabel: string | null = null
    for (const row of rows) {
      const label = labelOf(row)
      if (label !== lastLabel) {
        out.push({
          _isGroupHeader: true,
          _groupLabel: label,
          _groupCount: counts[label] ?? 0,
        })
        lastLabel = label
      }
      out.push(row as PortalListDisplayRow<T>)
    }
    return out
  }

  const groups = new Map<string, T[]>()
  for (const row of rows) {
    const label = labelOf(row)
    const bucket = groups.get(label) ?? []
    bucket.push(row)
    groups.set(label, bucket)
  }
  const out: PortalListDisplayRow<T>[] = []
  for (const [label, items] of groups) {
    out.push({
      _isGroupHeader: true,
      _groupLabel: label,
      _groupCount: items.length,
    })
    for (const item of items) {
      out.push(item as PortalListDisplayRow<T>)
    }
  }
  return out
}

export function isPortalListGroupHeader(row: unknown): row is PortalListGroupHeader {
  return !!(row as { _isGroupHeader?: boolean } | null)?._isGroupHeader
}

/** First active contains value — used when task APIs only accept a single keyword. */
export function firstContainsFilterValue(
  filters: Record<string, PortalListColumnFilter>,
): string | undefined {
  for (const f of Object.values(activeFiltersForApi(filters))) {
    if (f.operator === 'contains' && f.value.trim()) return f.value.trim()
  }
  return undefined
}

/** True when any Views chrome that needs client-side apply is active. */
export function hasPortalListClientChrome(state: PortalListColumnState): boolean {
  if (state.groupBy) return true
  if (state.sort?.field) return true
  return Object.keys(activeFiltersForApi(state.filters)).length > 0
}

/**
 * el-table span-method helper for group header rows.
 * `leadingFixedCols` = selection (and similar) columns before the first data column.
 */
export function portalListGroupSpanMethod(
  row: unknown,
  columnIndex: number,
  dataColumnCount: number,
  leadingFixedCols = 0,
): { rowspan: number; colspan: number } {
  if (!isPortalListGroupHeader(row)) {
    return { rowspan: 1, colspan: 1 }
  }
  if (columnIndex < leadingFixedCols) {
    return { rowspan: 0, colspan: 0 }
  }
  if (columnIndex === leadingFixedCols) {
    return { rowspan: 1, colspan: dataColumnCount }
  }
  return { rowspan: 0, colspan: 0 }
}

function storageKey(listId: string): string {
  return `portal-list-cols:${listId}`
}

export function loadPortalListColumnState(listId: string): PortalListColumnState {
  try {
    const raw = sessionStorage.getItem(storageKey(listId))
    if (!raw) return createDefaultPortalListColumnState()
    const parsed = JSON.parse(raw) as Partial<PortalListColumnState>
    return {
      columnOrder: Array.isArray(parsed.columnOrder)
        ? parsed.columnOrder.filter((x): x is string => typeof x === 'string')
        : [],
      groupBy: typeof parsed.groupBy === 'string' ? parsed.groupBy : null,
      widths: parsed.widths && typeof parsed.widths === 'object' ? parsed.widths : {},
      filters: parsed.filters && typeof parsed.filters === 'object' ? parsed.filters : {},
      sort: parsed.sort?.field && parsed.sort.direction
        ? { field: parsed.sort.field, direction: parsed.sort.direction }
        : null,
    }
  } catch {
    return createDefaultPortalListColumnState()
  }
}

export function savePortalListColumnState(listId: string, state: PortalListColumnState): void {
  try {
    sessionStorage.setItem(storageKey(listId), JSON.stringify(state))
  } catch {
    // sessionStorage full / private mode — ignore (non-authoritative UX prefs)
  }
}
