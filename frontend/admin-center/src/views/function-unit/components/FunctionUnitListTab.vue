<template>
  <div>
    <div class="fu-toolbar">
      <el-input
        v-model="searchKeyword"
        :placeholder="t('functionUnit.searchPlaceholder')"
        clearable
        style="width: 300px;"
      />
      <el-button @click="exportGridCsv('function-units')">
        <el-icon><Download /></el-icon>{{ t('common.export') }}
      </el-button>
      <template v-if="selectedUnits.length > 0">
        <span class="fu-selected">{{ t('functionUnit.selected', { count: selectedUnits.length }) }}</span>
        <el-button
          type="success"
          size="small"
          @click="handleBatchEnable"
        >
          {{ t('functionUnit.batchEnable') }}
        </el-button>
        <el-button
          type="warning"
          size="small"
          @click="handleBatchDisable"
        >
          {{ t('functionUnit.batchDisable') }}
        </el-button>
        <el-button
          type="danger"
          size="small"
          @click="handleBatchDelete"
        >
          {{ t('functionUnit.batchDelete') }}
        </el-button>
      </template>
    </div>

    <el-card
      v-loading="loading"
      class="table-card"
    >
      <div
        :ref="bindScrollRef"
        class="list-data-grid-scroll"
      >
        <div
          class="list-data-grid-inner"
          :style="gridInnerStyle"
        >
          <el-table
            :data="displayRows"
            stripe
            :fit="false"
            table-layout="fixed"
            style="width: 100%"
            class="list-data-grid"
            :class="{ 'list-data-grid--fit': gridFits }"
            scrollbar-always-on
            :height="gridTableHeight || '100%'"
            @selection-change="onSelectionChange"
          >
            <el-table-column
              type="selection"
              :width="selectionWidth"
            />
            <el-table-column
              v-for="(col, colIndex) in displayColumns"
              :key="col.field"
              :prop="col.field"
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
<el-tag
                  v-if="col.field === 'status'"
                  :type="functionUnitStatusType(row.status)"
                >
                  {{ t(functionUnitStatusKey(row.status)) }}
                </el-tag>
                <el-switch
                  v-else-if="col.field === 'enabled'"
                  v-model="row.enabled"
                  :loading="row._enabledLoading"
                  @change="() => handleEnabledChange(row, row.enabled)"
                />
                <template v-else>
                  {{ row[col.field as keyof typeof row] ?? '-' }}
                </template>
              </template>
            </el-table-column>
            <el-table-column
              :label="t('common.actions')"
              :width="actionsWidth"
              fixed="right"
            >
              <template #header>
                {{ t('common.actions') }}
              </template>
              <template #default="{ row }">
                <div
                  
                  class="row-actions"
                >
                  <el-button
                    link
                    type="primary"
                    @click="showAccessDialog(row)"
                  >
                    {{ t('functionUnit.access') }}
                  </el-button>
                  <el-button
                    v-if="canValidateFunctionUnit(row.status)"
                    link
                    type="primary"
                    :loading="validateLoadingId === row.id"
                    @click="handleValidate(row)"
                  >
                    {{ t('functionUnit.validate') }}
                  </el-button>
                  <el-tooltip
                    v-if="!canDeployFunctionUnit(row.status)"
                    :content="t('functionUnit.deployRequiresValidation')"
                  >
                    <span>
                      <el-button
                        link
                        type="primary"
                        disabled
                      >
                        {{ t('functionUnit.deploy') }}
                      </el-button>
                    </span>
                  </el-tooltip>
                  <el-button
                    v-else
                    link
                    type="primary"
                    :loading="deployLoadingId === row.id"
                    @click="handleDeploy(row)"
                  >
                    {{ t('functionUnit.deploy') }}
                  </el-button>
                  <el-button
                    link
                    type="primary"
                    @click="showVersions(row)"
                  >
                    {{ t('functionUnit.versions') }}
                  </el-button>
                  <el-button
                    link
                    type="danger"
                    @click="handleRollback(row)"
                  >
                    {{ t('functionUnit.rollback') }}
                  </el-button>
                  <el-button
                    link
                    type="danger"
                    @click="handleDeleteClick(row)"
                  >
                    {{ t('common.delete') }}
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
        @change="fetchRows"
      />
    </el-card>

    <ListFilterDialog
      v-model:visible="filterDialog.visible"
      :column="activeFilterColumn"
      :filter="activeFilter"
      @apply="onFilterApply"
      @clear="onFilterClear"
    />
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { ComponentPublicInstance, Ref } from 'vue'
import { Download } from '@element-plus/icons-vue'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'
import {
  canDeployFunctionUnit,
  canValidateFunctionUnit,
  functionUnitStatusKey,
  functionUnitStatusType,
} from '@/utils/format'
import type { FunctionUnitRow } from '@/composables/modules/useFunctionUnitLists'
import { useAdminListGrid } from '@/composables/list/useAdminListGrid'

type ListGrid = ReturnType<typeof useAdminListGrid<FunctionUnitRow>>

const { t } = useI18n()

const props = defineProps<{
  grid: ListGrid
  loading: boolean
  selectedUnits: FunctionUnitRow[]
  selectionWidth: number
  actionsWidth: number
  validateLoadingId: string | null
  deployLoadingId: string | null
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
  handleGridSelectionChange,
  exportGridCsv,
} = props.grid

const emit = defineEmits<{
  'update:searchKeyword': [value: string]
  fetch: []
  'selection-change': [rows: FunctionUnitRow[]]
  'enabled-change': [row: FunctionUnitRow, enabled: boolean]
  'show-access': [row: FunctionUnitRow]
  validate: [row: FunctionUnitRow]
  deploy: [row: FunctionUnitRow]
  'show-versions': [row: FunctionUnitRow]
  rollback: [row: FunctionUnitRow]
  'delete-click': [row: FunctionUnitRow]
  'batch-enable': []
  'batch-disable': []
  'batch-delete': []
}>()

const searchKeyword = defineModel<string>('searchKeyword', { required: true })

function onSelectionChange(rows: FunctionUnitRow[]) {
  handleGridSelectionChange(rows)
  emit('selection-change', rows)
}

function bindScrollRef(el: Element | ComponentPublicInstance | null) {
  const node = el instanceof Element ? el : el?.$el ?? null
  ;(props.grid.gridScrollRef as Ref<Element | null>).value =
    node instanceof Element ? node : null
}

function fetchRows() {
  emit('fetch')
}
function handleBatchEnable() {
  emit('batch-enable')
}
function handleBatchDisable() {
  emit('batch-disable')
}
function handleBatchDelete() {
  emit('batch-delete')
}
function handleEnabledChange(row: FunctionUnitRow, enabled: boolean) {
  emit('enabled-change', row, enabled)
}
function showAccessDialog(row: FunctionUnitRow) {
  emit('show-access', row)
}
function handleValidate(row: FunctionUnitRow) {
  emit('validate', row)
}
function handleDeploy(row: FunctionUnitRow) {
  emit('deploy', row)
}
function showVersions(row: FunctionUnitRow) {
  emit('show-versions', row)
}
function handleRollback(row: FunctionUnitRow) {
  emit('rollback', row)
}
function handleDeleteClick(row: FunctionUnitRow) {
  emit('delete-click', row)
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

<style scoped>
.fu-toolbar {
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.fu-selected {
  color: #909399;
  font-size: 13px;
}
.row-actions {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  white-space: nowrap;
}
</style>
