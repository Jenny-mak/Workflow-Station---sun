import { computed, reactive, ref, toValue, type MaybeRefOrGetter } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ListColumnFilter, ListColumnMeta } from '@platform-shared/list/columnMeta'
import { useListColumnLayout } from '@platform-shared/list/useListColumnLayout'
import { exportTableCsv } from '@platform-shared/list/tableExport'

export const PORTAL_LIST_SELECTION_WIDTH = 48

export interface PortalListPagePayload<T> {
  columns: ListColumnMeta[]
  content: T[]
  totalElements: number
}

/**
 * Host-owned grid state for a shared-list page: column order, filters, sort,
 * session widths, and stale-response protection. The composable does not fetch.
 */
export function usePortalListGrid<T extends object>(opts: {
  storageKey: string
  extraWidth?: MaybeRefOrGetter<number>
  fillViewport?: MaybeRefOrGetter<boolean>
  /** When set, only these fields render / persist order. Other declared columns stay on the payload. */
  visibleFields?: MaybeRefOrGetter<readonly string[] | undefined>
  /** Return a pixel width to override header-fit for that field; omit / undefined keeps the default. */
  defaultWidthOf?: (field: string) => number | undefined
  selection?: MaybeRefOrGetter<boolean>
}) {
  const { t } = useI18n()
  const columns = ref<ListColumnMeta[]>([])
  const columnOrder = ref<string[]>([])
  const rows = ref<T[]>([]) as { value: T[] }
  const gridSelectedRows = ref<T[]>([]) as { value: T[] }
  const columnFilters = ref<Record<string, ListColumnFilter>>({})
  const sort = reactive<{ field: string | null; direction: 'ASC' | 'DESC' | null }>({
    field: null,
    direction: null,
  })
  const filterDialog = reactive({ visible: false, field: '' })
  const pagination = reactive({ page: 1, size: 20, total: 0 })
  let querySeq = 0

  const displayColumns = computed<ListColumnMeta[]>(() => {
    const localized = visibleDeclaredColumns().map((col) => localizeColumn(col, t))
    if (columnOrder.value.length === 0) return localized
    const byField = new Map(localized.map((col) => [col.field, col]))
    const ordered: ListColumnMeta[] = []
    for (const field of columnOrder.value) {
      const col = byField.get(field)
      if (col) ordered.push(col)
    }
    for (const col of localized) {
      if (!columnOrder.value.includes(col.field)) ordered.push(col)
    }
    return ordered
  })

  const displayRows = computed(() => rows.value)
  const gridExtraWidth = computed(
    () => toValue(opts.extraWidth ?? 0) + (toValue(opts.selection ?? false) ? PORTAL_LIST_SELECTION_WIDTH : 0),
  )

  const layoutFields = computed(() => displayColumns.value.map((col) => col.field))
  const { gridScrollRef, gridFits, gridTableHeight, gridInnerStyle, widthOf, setWidth, persistWidths } =
    useListColumnLayout({
      storageKey: opts.storageKey,
      fields: layoutFields,
      extraWidth: gridExtraWidth,
      fillViewport: opts.fillViewport,
      defaultWidthOf: opts.defaultWidthOf,
      labelOf: (field) => displayColumns.value.find((col) => col.field === field)?.label ?? field,
      kindOf: (field) => displayColumns.value.find((col) => col.field === field)?.kind,
    })

  const activeFilterColumn = computed(
    () => displayColumns.value.find((col) => col.field === filterDialog.field) ?? null,
  )
  const activeFilter = computed(() => columnFilters.value[filterDialog.field] ?? null)

  function beginQuery(): number {
    querySeq += 1
    return querySeq
  }

  function isCurrentQuery(seq: number): boolean {
    return seq === querySeq
  }

  function applyPage(page: PortalListPagePayload<T>, missingColumnsMessage: string): void {
    if (!Array.isArray(page.columns) || page.columns.length === 0) {
      throw new Error(missingColumnsMessage)
    }
    if (!Array.isArray(page.content)) {
      throw new Error('list page is missing its row array')
    }
    columns.value = page.columns
    syncColumnOrderFromServer(page.columns)
    rows.value = page.content
    gridSelectedRows.value = []
    pagination.total = page.totalElements
  }

  function handleGridSelectionChange(selection: T[]): void {
    gridSelectedRows.value = selection
  }

  function setQuickFilter(field: string, value: string): void {
    const next = { ...columnFilters.value }
    const keyword = value.trim()
    if (keyword) next[field] = { operator: 'contains', value: keyword }
    else delete next[field]
    columnFilters.value = next
    resetPage()
  }

  function exportGridCsv(filename: string, exportColumns: ListColumnMeta[] = displayColumns.value): void {
    exportTableCsv({
      rows: gridSelectedRows.value.length > 0 ? gridSelectedRows.value : rows.value,
      columns: exportColumns,
      filename,
    })
  }

  function buildQuery(): {
    page: number
    size: number
    filters?: Array<ListColumnFilter & { field: string }>
    sortField?: string
    sortDirection?: 'ASC' | 'DESC'
  } {
    const body: {
      page: number
      size: number
      filters?: Array<ListColumnFilter & { field: string }>
      sortField?: string
      sortDirection?: 'ASC' | 'DESC'
    } = { page: pagination.page - 1, size: pagination.size }
    const filters = Object.entries(columnFilters.value).map(([field, filter]) => ({ field, ...filter }))
    if (filters.length > 0) body.filters = filters
    if (sort.field && sort.direction) {
      body.sortField = sort.field
      body.sortDirection = sort.direction
    }
    return body
  }

  function readStoredColumnOrder(): string[] {
    try {
      const raw = sessionStorage.getItem(`${opts.storageKey}:order`)
      if (!raw) return []
      const parsed = JSON.parse(raw) as { columnOrder?: string[] }
      return Array.isArray(parsed.columnOrder)
        ? parsed.columnOrder.filter((f) => typeof f === 'string')
        : []
    } catch {
      // FALLBACK(ux): unreadable layout costs remembered order only; the row data is unaffected.
      return []
    }
  }

  function persistColumnOrder(): void {
    try {
      sessionStorage.setItem(
        `${opts.storageKey}:order`,
        JSON.stringify({ columnOrder: [...columnOrder.value] }),
      )
    } catch {
      // FALLBACK(ux): quota errors must not interrupt the move the user just performed.
    }
  }

  function declaredFieldsForLayout(declared: ListColumnMeta[]): string[] {
    const all = declared.map((c) => c.field)
    const visible = toValue(opts.visibleFields)
    if (visible == null) return all
    const allow = new Set(visible)
    return all.filter((field) => allow.has(field))
  }

  function visibleDeclaredColumns(): ListColumnMeta[] {
    const visible = toValue(opts.visibleFields)
    if (visible == null) return columns.value
    const allow = new Set(visible)
    return columns.value.filter((col) => allow.has(col.field))
  }

  function syncColumnOrderFromServer(declared: ListColumnMeta[]): void {
    const declaredFields = declaredFieldsForLayout(declared)
    const stored = readStoredColumnOrder()
    const next: string[] = []
    for (const field of stored) {
      if (declaredFields.includes(field) && !next.includes(field)) next.push(field)
    }
    for (const field of declaredFields) {
      if (!next.includes(field)) next.push(field)
    }
    columnOrder.value = next
  }

  function moveColumn(field: string, direction: 'left' | 'right'): void {
    const order = [...columnOrder.value]
    const index = order.indexOf(field)
    if (index < 0) return
    const swapWith = direction === 'left' ? index - 1 : index + 1
    if (swapWith < 0 || swapWith >= order.length) return
    ;[order[index], order[swapWith]] = [order[swapWith], order[index]]
    columnOrder.value = order
    persistColumnOrder()
  }

  function resetPage(): void {
    pagination.page = 1
  }

  function openFilter(field: string): void {
    filterDialog.field = field
    filterDialog.visible = true
  }

  function applyFilter(filter: ListColumnFilter): void {
    columnFilters.value = { ...columnFilters.value, [filterDialog.field]: filter }
    filterDialog.visible = false
    resetPage()
  }

  function clearFilter(field: string): void {
    const next = { ...columnFilters.value }
    delete next[field]
    columnFilters.value = next
    filterDialog.visible = false
    resetPage()
  }

  function applySort(field: string, direction: 'ASC' | 'DESC'): void {
    sort.field = field
    sort.direction = direction
    resetPage()
  }

  function clearSort(): void {
    sort.field = null
    sort.direction = null
    resetPage()
  }

  return {
    columns,
    displayColumns,
    displayRows,
    gridSelectedRows,
    selectionColumnWidth: PORTAL_LIST_SELECTION_WIDTH,
    columnFilters,
    sort,
    filterDialog,
    pagination,
    activeFilterColumn,
    activeFilter,
    gridScrollRef,
    gridFits,
    gridTableHeight,
    gridInnerStyle,
    widthOf,
    setWidth,
    persistWidths,
    beginQuery,
    isCurrentQuery,
    applyPage,
    handleGridSelectionChange,
    setQuickFilter,
    exportGridCsv,
    buildQuery,
    moveColumn,
    resetPage,
    openFilter,
    applyFilter,
    clearFilter,
    applySort,
    clearSort,
  }
}

function localizeColumn(col: ListColumnMeta, t: (key: string) => string): ListColumnMeta {
  return {
    ...col,
    label: t(col.label),
    options: col.options?.map((option) => ({ ...option, label: t(option.label) })),
  }
}
