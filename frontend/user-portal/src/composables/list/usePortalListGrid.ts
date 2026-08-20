import { computed, reactive, ref, type MaybeRefOrGetter } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ListColumnFilter, ListColumnMeta } from '@platform-shared/list/columnMeta'
import {
  insertListGroupHeaders,
  isListGroupHeaderRow,
  type ListGroupCount,
} from '@platform-shared/list/insertGroupHeaders'
import { useListColumnLayout } from '@/composables/list/useListColumnLayout'

export interface PortalListPagePayload<T> {
  columns: ListColumnMeta[]
  content: T[]
  groups?: ListGroupCount[]
  totalElements: number
}

/**
 * Host-owned grid state for a shared-list page: column order, filters, sort, grouping,
 * session widths, and stale-response protection. The composable does not fetch.
 */
export function usePortalListGrid<T extends object>(opts: {
  storageKey: string
  extraWidth?: MaybeRefOrGetter<number>
  defaultWidthOf?: (field: string) => number
}) {
  const { t } = useI18n()
  const columns = ref<ListColumnMeta[]>([])
  const columnOrder = ref<string[]>([])
  const rows = ref<T[]>([]) as { value: T[] }
  const groups = ref<ListGroupCount[]>([])
  const groupBy = ref<string | null>(null)
  const columnFilters = ref<Record<string, ListColumnFilter>>({})
  const sort = reactive<{ field: string | null; direction: 'ASC' | 'DESC' | null }>({
    field: null,
    direction: null,
  })
  const filterDialog = reactive({ visible: false, field: '' })
  const pagination = reactive({ page: 1, size: 20, total: 0 })
  let querySeq = 0

  const displayColumns = computed<ListColumnMeta[]>(() => {
    const localized = columns.value.map((col) => localizeColumn(col, t))
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

  const displayRows = computed(() =>
    insertListGroupHeaders(rows.value, groupBy.value, groups.value),
  )

  const layoutFields = computed(() => displayColumns.value.map((col) => col.field))
  const { gridScrollRef, gridFits, gridInnerStyle, widthOf, setWidth, persistWidths } =
    useListColumnLayout({
      storageKey: opts.storageKey,
      fields: layoutFields,
      extraWidth: opts.extraWidth,
      defaultWidthOf: opts.defaultWidthOf,
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
    const pageGroups = page.groups ?? []
    for (const group of pageGroups) {
      if (typeof group.count !== 'number') {
        throw new Error('list group is missing count — the page and its group counts came from different queries')
      }
    }
    columns.value = page.columns
    syncColumnOrderFromServer(page.columns)
    rows.value = page.content
    groups.value = pageGroups
    pagination.total = page.totalElements
  }

  function buildQuery(): {
    page: number
    size: number
    filters?: Array<ListColumnFilter & { field: string }>
    sortField?: string
    sortDirection?: 'ASC' | 'DESC'
    groupBy?: string
  } {
    const body: {
      page: number
      size: number
      filters?: Array<ListColumnFilter & { field: string }>
      sortField?: string
      sortDirection?: 'ASC' | 'DESC'
      groupBy?: string
    } = { page: pagination.page - 1, size: pagination.size }
    const filters = Object.entries(columnFilters.value).map(([field, filter]) => ({ field, ...filter }))
    if (filters.length > 0) body.filters = filters
    if (sort.field && sort.direction) {
      body.sortField = sort.field
      body.sortDirection = sort.direction
    }
    if (groupBy.value) body.groupBy = groupBy.value
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

  function syncColumnOrderFromServer(declared: ListColumnMeta[]): void {
    const declaredFields = declared.map((c) => c.field)
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

  function applyGroup(field: string, grouped: boolean): void {
    groupBy.value = grouped ? field : null
    resetPage()
  }

  function rowClassName({ row }: { row: object }): string {
    return isListGroupHeaderRow(row) ? 'group-header-row' : ''
  }

  function spanMethod(
    extraColumns: number,
  ): (args: { row: object; columnIndex: number }) => { rowspan: number; colspan: number } {
    return ({ row, columnIndex }) => {
      if (!isListGroupHeaderRow(row)) {
        return { rowspan: 1, colspan: 1 }
      }
      if (columnIndex === 0) {
        return { rowspan: 1, colspan: displayColumns.value.length + extraColumns }
      }
      return { rowspan: 0, colspan: 0 }
    }
  }

  function groupHeaderLabel(raw: string): string {
    const col = displayColumns.value.find((c) => c.field === groupBy.value)
    const option = col?.options?.find((o) => o.value === raw)
    return option?.label ?? raw
  }

  return {
    columns,
    displayColumns,
    displayRows,
    groupBy,
    columnFilters,
    sort,
    filterDialog,
    pagination,
    activeFilterColumn,
    activeFilter,
    gridScrollRef,
    gridFits,
    gridInnerStyle,
    widthOf,
    setWidth,
    persistWidths,
    beginQuery,
    isCurrentQuery,
    applyPage,
    buildQuery,
    moveColumn,
    resetPage,
    openFilter,
    applyFilter,
    clearFilter,
    applySort,
    clearSort,
    applyGroup,
    rowClassName,
    spanMethod,
    groupHeaderLabel,
    isListGroupHeaderRow,
  }
}

function localizeColumn(col: ListColumnMeta, t: (key: string) => string): ListColumnMeta {
  return {
    ...col,
    label: t(col.label),
    options: col.options?.map((option) => ({ ...option, label: t(option.label) })),
  }
}
