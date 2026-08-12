import { reactive, ref, toValue, watch, type MaybeRefOrGetter } from 'vue'
import {
  activeFiltersForApi,
  columnWidthOf,
  createDefaultPortalListColumnState,
  initColumnOrder,
  loadPortalListColumnState,
  moveColumn,
  orderedFields,
  savePortalListColumnState,
  setColumnWidthOf,
  type PortalListColumnFilter,
  type PortalListColumnState,
  type PortalListSortDirection,
} from '@/utils/portalListGridRuntime'

/**
 * Session-backed column widths / filters / sort / group / order for a Portal list
 * (Views-aligned chrome).
 * `listId` may be a ref/computed so Relation Tables can switch per table.
 */
export function usePortalListColumnState(listId: MaybeRefOrGetter<string>) {
  const state = reactive<PortalListColumnState>(
    loadPortalListColumnState(toValue(listId)),
  )
  const filterDialogVisible = ref(false)
  const filterDialogField = ref<{ field: string; label: string } | null>(null)

  function currentListId(): string {
    return toValue(listId)
  }

  function resetFromStorage() {
    const loaded = loadPortalListColumnState(currentListId())
    state.columnOrder = [...loaded.columnOrder]
    state.groupBy = loaded.groupBy
    state.widths = { ...loaded.widths }
    state.filters = { ...loaded.filters }
    state.sort = loaded.sort ? { ...loaded.sort } : null
  }

  watch(
    () => currentListId(),
    () => {
      filterDialogVisible.value = false
      filterDialogField.value = null
      resetFromStorage()
    },
  )

  function persist() {
    savePortalListColumnState(currentListId(), {
      columnOrder: [...state.columnOrder],
      groupBy: state.groupBy,
      widths: { ...state.widths },
      filters: { ...state.filters },
      sort: state.sort ? { ...state.sort } : null,
    })
  }

  function width(field: string, fallback = 140): number {
    return columnWidthOf(state, field, fallback)
  }

  function onResize(field: string, w: number) {
    setColumnWidthOf(state, field, w)
  }

  function onResizeEnd() {
    persist()
  }

  function toggleSort(field: string, direction: PortalListSortDirection) {
    if (state.sort?.field === field && state.sort.direction === direction) {
      state.sort = null
    } else {
      state.sort = { field, direction }
    }
    persist()
  }

  function toggleGroup(field: string) {
    state.groupBy = state.groupBy === field ? null : field
    persist()
  }

  function moveLeft(field: string) {
    if (!state.columnOrder.length) return
    moveColumn(state, field, 'left')
    persist()
  }

  function moveRight(field: string) {
    if (!state.columnOrder.length) return
    moveColumn(state, field, 'right')
    persist()
  }

  function canMoveLeft(field: string): boolean {
    const idx = state.columnOrder.indexOf(field)
    return idx > 0
  }

  function canMoveRight(field: string): boolean {
    const idx = state.columnOrder.indexOf(field)
    return idx >= 0 && idx < state.columnOrder.length - 1
  }

  /** Call when the list's data columns are known (or change). */
  function ensureOrder(fields: string[]) {
    initColumnOrder(fields, state)
    if (state.groupBy && !fields.includes(state.groupBy)) {
      state.groupBy = null
    }
    if (state.sort?.field && !fields.includes(state.sort.field)) {
      state.sort = null
    }
    for (const f of Object.keys(state.filters)) {
      if (!fields.includes(f)) delete state.filters[f]
    }
    persist()
  }

  function orderedColumnFields(fields: string[]): string[] {
    return orderedFields(fields, state)
  }

  function openFilter(field: string, label: string) {
    filterDialogField.value = { field, label }
    filterDialogVisible.value = true
  }

  function applyFilter(filter: PortalListColumnFilter) {
    if (!filterDialogField.value) return
    const field = filterDialogField.value.field
    const needsValue = filter.operator !== 'isNull' && filter.operator !== 'isNotNull'
    if (needsValue && !filter.value.trim()) {
      delete state.filters[field]
    } else {
      state.filters[field] = { ...filter }
    }
    persist()
  }

  function clearFilter(field?: string) {
    const f = field ?? filterDialogField.value?.field
    if (!f) return
    delete state.filters[f]
    persist()
  }

  function hasFilter(field: string): boolean {
    return !!activeFiltersForApi(state.filters)[field]
  }

  function sortDirection(field: string): PortalListSortDirection | null {
    return state.sort?.field === field ? state.sort.direction : null
  }

  function isGrouped(field: string): boolean {
    return state.groupBy === field
  }

  return {
    state,
    filterDialogVisible,
    filterDialogField,
    persist,
    resetFromStorage,
    width,
    onResize,
    onResizeEnd,
    toggleSort,
    toggleGroup,
    moveLeft,
    moveRight,
    canMoveLeft,
    canMoveRight,
    ensureOrder,
    orderedColumnFields,
    openFilter,
    applyFilter,
    clearFilter,
    hasFilter,
    sortDirection,
    isGrouped,
    activeFilters: () => activeFiltersForApi(state.filters),
  }
}
