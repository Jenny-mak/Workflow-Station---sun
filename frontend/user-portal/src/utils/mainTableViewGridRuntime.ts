import type { MainTableViewDataRow, MainTableViewFieldColumn } from '@/api/mainTableView'
import { clampColumnWidth } from '@/utils/mainTableViewColumnResizeCursor'
import { formatMainTableViewCell } from '@/utils/mainTableViewCsvExport'

export { COLUMN_WIDTH_MIN, COLUMN_WIDTH_MAX, clampColumnWidth } from '@/utils/mainTableViewColumnResizeCursor'

export type GridSortDirection = 'ASC' | 'DESC'

export interface GridColumnFilter {
  operator: string
  value: string
}

export interface GridRuntimeState {
  columnOrder: string[]
  columnWidths: Record<string, number>
  sort: { fieldName: string; direction: GridSortDirection } | null
  filters: Record<string, GridColumnFilter>
  groupBy: string | null
}

export type GridDisplayRow =
  | (MainTableViewDataRow & { _isGroupHeader?: false })
  | {
      _isGroupHeader: true
      _groupLabel: string
      _groupCount: number
      processInstanceId?: string
      values?: Record<string, unknown>
    }

export function createDefaultGridRuntime(): GridRuntimeState {
  return {
    columnOrder: [],
    columnWidths: {},
    sort: null,
    filters: {},
    groupBy: null,
  }
}

export function initColumnOrder(columns: MainTableViewFieldColumn[], state: GridRuntimeState): void {
  const names = columns.map(c => c.fieldName)
  if (!state.columnOrder.length) {
    state.columnOrder = [...names]
    return
  }
  const existing = new Set(state.columnOrder)
  const merged = state.columnOrder.filter(n => names.includes(n))
  for (const n of names) {
    if (!existing.has(n)) merged.push(n)
  }
  state.columnOrder = merged
}

export function orderedColumns(
  columns: MainTableViewFieldColumn[],
  state: GridRuntimeState,
): MainTableViewFieldColumn[] {
  if (!state.columnOrder.length) return columns
  const map = new Map(columns.map(c => [c.fieldName, c]))
  return state.columnOrder
    .map(name => map.get(name))
    .filter((c): c is MainTableViewFieldColumn => !!c)
}

export function columnWidth(
  col: MainTableViewFieldColumn,
  state: GridRuntimeState,
): number {
  return clampColumnWidth(state.columnWidths[col.fieldName] ?? col.columnWidth ?? 120)
}

export function setColumnWidth(
  state: GridRuntimeState,
  fieldName: string,
  width: number,
): void {
  state.columnWidths[fieldName] = clampColumnWidth(width)
}

function cellText(row: MainTableViewDataRow, fieldName: string): string {
  const v = row.values[fieldName]
  if (v == null) return ''
  // Use the same display formatting as cells so group labels show filenames (not upload URLs) and
  // lookup/FK display names (not "[object Object]"). formatMainTableViewCell returns '-' for empties.
  const formatted = formatMainTableViewCell(v)
  return formatted === '-' ? '' : formatted
}

function compareValues(a: unknown, b: unknown): number {
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

function matchesColumnFilter(value: unknown, filter: GridColumnFilter): boolean {
  const text = value == null ? '' : String(value)
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
    default:
      return true
  }
}

export function applyGridRuntime(
  rows: MainTableViewDataRow[],
  state: GridRuntimeState,
): MainTableViewDataRow[] {
  let out = [...rows]

  const filterEntries = Object.entries(state.filters).filter(([, f]) => {
    if (f.operator === 'isNull' || f.operator === 'isNotNull') return true
    return (f.value ?? '').trim() !== ''
  })
  if (filterEntries.length) {
    out = out.filter(row =>
      filterEntries.every(([field, filter]) =>
        matchesColumnFilter(row.values[field], filter),
      ),
    )
  }

  if (state.sort) {
    const { fieldName, direction } = state.sort
    out.sort((a, b) => {
      const cmp = compareValues(a.values[fieldName], b.values[fieldName])
      return direction === 'DESC' ? -cmp : cmp
    })
  }

  return out
}

export function applyGroupBy(
  rows: MainTableViewDataRow[],
  groupByField: string | null,
): GridDisplayRow[] {
  if (!groupByField) return rows

  const groups = new Map<string, MainTableViewDataRow[]>()
  for (const row of rows) {
    const label = cellText(row, groupByField) || '—'
    const bucket = groups.get(label) ?? []
    bucket.push(row)
    groups.set(label, bucket)
  }

  const out: GridDisplayRow[] = []
  for (const [label, items] of groups) {
    out.push({
      _isGroupHeader: true,
      _groupLabel: label,
      _groupCount: items.length,
    })
    out.push(...items)
  }
  return out
}

/**
 * Insert group headers for a server-ordered page. Counts come from the full filtered set
 * (`groupCounts`), so a mid-group page still shows the correct total.
 */
export function applyGroupHeadersWithCounts(
  rows: MainTableViewDataRow[],
  groupByField: string | null,
  groupCounts: Record<string, number> | null | undefined,
): GridDisplayRow[] {
  if (!groupByField) return rows

  const out: GridDisplayRow[] = []
  let lastLabel: string | null = null
  for (const row of rows) {
    const label = cellText(row, groupByField) || '—'
    if (label !== lastLabel) {
      out.push({
        _isGroupHeader: true,
        _groupLabel: label,
        _groupCount: groupCounts?.[label] ?? 0,
      })
      lastLabel = label
    }
    out.push(row)
  }
  return out
}

/** Active filters only — empty values omitted except isNull / isNotNull. */
export function activeFiltersForQuery(
  filters: Record<string, GridColumnFilter>,
): Record<string, GridColumnFilter> {
  const out: Record<string, GridColumnFilter> = {}
  for (const [field, filter] of Object.entries(filters)) {
    if (!filter) continue
    if (filter.operator === 'isNull' || filter.operator === 'isNotNull') {
      out[field] = filter
      continue
    }
    if ((filter.value ?? '').trim() !== '') {
      out[field] = filter
    }
  }
  return out
}

/**
 * Remove any runtime state (groupBy, sort, filters) that references a field not present in the given
 * columns. Prevents a prior view's grouping/sort/filter from mis-rendering against another view's data.
 */
export function pruneRuntimeToColumns(
  state: GridRuntimeState,
  columns: MainTableViewFieldColumn[],
): void {
  const valid = new Set(columns.map(c => c.fieldName))
  if (state.groupBy && !valid.has(state.groupBy)) {
    state.groupBy = null
  }
  if (state.sort && !valid.has(state.sort.fieldName)) {
    state.sort = null
  }
  for (const field of Object.keys(state.filters)) {
    if (!valid.has(field)) {
      delete state.filters[field]
    }
  }
}

export function moveColumn(
  state: GridRuntimeState,
  fieldName: string,
  direction: 'left' | 'right',
): void {
  const idx = state.columnOrder.indexOf(fieldName)
  if (idx < 0) return
  const target = direction === 'left' ? idx - 1 : idx + 1
  if (target < 0 || target >= state.columnOrder.length) return
  const next = [...state.columnOrder]
  ;[next[idx], next[target]] = [next[target], next[idx]]
  state.columnOrder = next
}

export function isGroupHeaderRow(row: GridDisplayRow): row is Extract<GridDisplayRow, { _isGroupHeader: true }> {
  return !!(row as { _isGroupHeader?: boolean })._isGroupHeader
}

export function loadGridRuntimeFromSession(viewId: number): GridRuntimeState {
  try {
    const raw = sessionStorage.getItem(`portal-mtv-runtime:${viewId}`)
    if (!raw) return createDefaultGridRuntime()
    const parsed = JSON.parse(raw) as GridRuntimeState
    return {
      ...createDefaultGridRuntime(),
      ...parsed,
      filters: parsed.filters ?? {},
      columnWidths: parsed.columnWidths ?? {},
      columnOrder: parsed.columnOrder ?? [],
    }
  } catch {
    return createDefaultGridRuntime()
  }
}

export function saveGridRuntimeToSession(viewId: number, state: GridRuntimeState): void {
  try {
    sessionStorage.setItem(`portal-mtv-runtime:${viewId}`, JSON.stringify(state))
  } catch {
    /* ignore quota errors */
  }
}
