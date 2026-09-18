<template>
  <div class="list-data-grid-host">
    <div
      :ref="bindScrollRef"
      class="list-data-grid-scroll"
    >
      <div
        class="list-data-grid-inner"
        :style="gridInnerStyle"
      >
        <el-table
          v-loading="loading"
          :data="displayRows"
          stripe
          :fit="false"
          table-layout="fixed"
          scrollbar-always-on
          class="list-data-grid table-fixed-actions"
          :class="{ 'list-data-grid--fit': gridFits }"
          :height="gridTableHeight || '100%'"
          style="width: 100%"
          @selection-change="handleGridSelectionChange"
        >
          <el-table-column
            type="selection"
            :width="selectionColumnWidth"
            fixed="left"
          />
          <el-table-column
            v-for="(col, colIndex) in displayColumns"
            :key="col.field"
            :prop="'data.' + col.field"
            :width="widthOf(col.field)"
            show-overflow-tooltip
          >
            <template #header>
              <ListColumnHeader
                :column="col"
                :sort="sort.field === col.field ? sort.direction : null"
                :filtered="!!columnFilters[col.field]"
                :width="widthOf(col.field)"
                :show-move="displayColumns.length > 1"
                :can-move-left="colIndex > 0"
                :can-move-right="colIndex < displayColumns.length - 1"
                @sort-change="(direction: 'ASC' | 'DESC') => onSort(col.field, direction)"
                @clear-sort="onClearSort"
                @filter-open="openFilter(col.field)"
                @clear-filter="onClearFilter(col.field)"
                @move="(direction: 'left' | 'right') => moveColumn(col.field, direction)"
                @width-change="(width: number) => setWidth(col.field, width)"
                @width-commit="persistWidths"
              />
            </template>
            <template #default="{ row }">
              <span>{{ formatCell(col, row) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="Status"
            :width="statusWidth"
          >
            <template #default="{ row }">
              <el-tag
                :type="isRowDisabled(row) ? 'danger' : 'success'"
                size="small"
              >
                {{ isRowDisabled(row) ? 'Inactive' : 'Active' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            v-if="canWrite"
            label="Actions"
            :width="actionsWidth"
            fixed="right"
            align="center"
          >
            <template #header>
              Actions
            </template>
            <template #default="{ row }">
              <div class="action-cell">
                <el-button
                  link
                  type="primary"
                  size="small"
                  @click="emit('edit', row)"
                >
                  Edit
                </el-button>
                <el-button
                  v-if="isRowDisabled(row)"
                  link
                  type="success"
                  size="small"
                  @click="emit('enable', row)"
                >
                  Active
                </el-button>
                <el-button
                  v-else
                  link
                  type="warning"
                  size="small"
                  @click="emit('disable', row)"
                >
                  Inactive
                </el-button>
                <el-button
                  link
                  type="danger"
                  size="small"
                  @click="emit('delete', row)"
                >
                  Delete
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <ListPagination
      v-model:page="pagination.page"
      v-model:size="pagination.size"
      :total="pagination.total"
      :loading="loading"
      @change="emit('fetch')"
    />

    <ListFilterDialog
      v-model:visible="filterDialog.visible"
      :column="activeFilterColumn"
      :filter="activeFilter"
      :remote-search="searchListFilterUsers"
      @apply="onFilterApply"
      @clear="onFilterClear"
    />
  </div>
</template>

<script setup lang="ts">
import type { ComponentPublicInstance, Ref } from 'vue'
import type { ListColumnFilter, ListColumnMeta } from '@platform-shared/list/columnMeta'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import { searchListFilterUsers } from '@/composables/list/searchListFilterUsers'
import { useAdminListGrid } from '@/composables/list/useAdminListGrid'
import { formatRelationCellDisplay } from '@/components/lookup/lookupHelpers'
import type { RelationTableDataRow } from '@/api/relationTable'

type ListGrid = ReturnType<typeof useAdminListGrid<RelationTableDataRow>>

const props = defineProps<{
  grid: ListGrid
  loading: boolean
  canWrite: boolean
  statusWidth: number
  actionsWidth: number
  isRowDisabled: (row: RelationTableDataRow) => boolean
  formatDateTime: (value: unknown) => string
}>()

const emit = defineEmits<{
  fetch: []
  edit: [row: RelationTableDataRow]
  enable: [row: RelationTableDataRow]
  disable: [row: RelationTableDataRow]
  delete: [row: RelationTableDataRow]
}>()

const {
  displayColumns,
  displayRows,
  columnFilters,
  gridFits,
  gridTableHeight,
  gridInnerStyle,
  activeFilterColumn,
  activeFilter,
  sort,
  filterDialog,
  pagination,
  widthOf,
  setWidth,
  persistWidths,
  moveColumn,
  openFilter,
  applySort,
  clearSort,
  applyFilter,
  clearFilter,
  selectionColumnWidth,
  handleGridSelectionChange,
} = props.grid

function bindScrollRef(el: Element | ComponentPublicInstance | null) {
  const node = el instanceof Element ? el : el?.$el ?? null
  ;(props.grid.gridScrollRef as Ref<Element | null>).value =
    node instanceof Element ? node : null
}

function formatCell(col: ListColumnMeta, row: RelationTableDataRow): string {
  const value = row.data?.[col.field]
  if (col.kind === 'DATETIME') return props.formatDateTime(value)
  return formatRelationCellDisplay(value)
}

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  emit('fetch')
}
function onClearSort() {
  clearSort()
  emit('fetch')
}
function onClearFilter(field: string) {
  clearFilter(field)
  emit('fetch')
}
function onFilterApply(filter: ListColumnFilter) {
  applyFilter(filter)
  emit('fetch')
}
function onFilterClear() {
  onClearFilter(filterDialog.field)
}
</script>
