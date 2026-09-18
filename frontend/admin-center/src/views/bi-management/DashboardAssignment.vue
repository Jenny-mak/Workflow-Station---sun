<template>
  <div class="page-container">
    <PageHeader :title="t('bi.assignment.pageTitle')">
      <template #actions>
        <el-button @click="exportGridCsv('bi-dashboard-assignments')">
          <el-icon><Download /></el-icon>{{ t('common.export') }}
        </el-button>
        <el-button
          type="primary"
          @click="showCreateDialog"
        >
          <el-icon><Plus /></el-icon>{{ t('bi.assignment.newAssignment') }}
        </el-button>
      </template>
    </PageHeader>

    <el-card class="search-card">
      <el-form
        :inline="true"
        :model="query"
        class="search-form"
      >
        <el-form-item :label="t('bi.assignment.filterTargetType')">
          <el-select
            v-model="query.targetType"
            :placeholder="t('bi.assignment.placeholderTargetType')"
            clearable
            style="width: 160px"
          >
            <el-option
              v-for="opt in targetTypeFilterOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('bi.assignment.filterDashboardTitle')">
          <el-input
            v-model="query.dashboardTitle"
            :placeholder="t('bi.assignment.placeholderDashboardTitle')"
            clearable
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            @click="handleSearch"
          >
            <el-icon><Search /></el-icon>{{ t('common.search') }}
          </el-button>
          <el-button @click="handleReset">
            <el-icon><RefreshIcon /></el-icon>{{ t('common.reset') }}
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card
      v-loading="loading"
      class="table-card"
    >
      <div
        ref="gridScrollRef"
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
                  v-if="col.field === 'targetType'"
                  :type="assignmentTargetTagType(row.targetType)"
                  size="small"
                >
                  {{ t(assignmentTargetTypeKey(row.targetType)) }}
                </el-tag>
                <template v-else-if="col.field === 'layoutMode'">
                  {{ t(layoutModeKey(row.layoutMode)) }}
                </template>
                <template v-else-if="col.field === 'isDefault'">
                  <el-tag
                    v-if="row.isDefault"
                    type="success"
                    size="small"
                  >
                    {{ t('bi.assignment.defaultYes') }}
                  </el-tag>
                  <el-tag
                    v-else
                    type="info"
                    size="small"
                  >
                    {{ t('bi.assignment.defaultNo') }}
                  </el-tag>
                </template>
                <template v-else>
                  {{ row[col.field] ?? '-' }}
                </template>
              </template>
            </el-table-column>
            <el-table-column
              :label="t('bi.assignment.colActions')"
              :width="ACTIONS_COL_WIDTH"
              fixed="right"
              align="center"
            >
              <template #header>
                {{ t('bi.assignment.colActions') }}
              </template>
              <template #default="{ row }">
                <div
                  
                  class="action-cell"
                >
                  <el-button
                    link
                    type="primary"
                    size="small"
                    @click="showEditDialog(row)"
                  >
                    {{ t('common.edit') }}
                  </el-button>
                  <el-button
                    link
                    type="danger"
                    size="small"
                    @click="handleDelete(row)"
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
        @change="loadAssignments"
      />
    </el-card>

    <AssignmentFormDialog
      v-model="dialogVisible"
      :mode="dialogMode"
      :initial-row="editingRow"
      @success="handleSearch"
    />

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
import { onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { Download, Plus, Search, Refresh as RefreshIcon } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { useBiAssignment } from '@/composables/modules/useBiAssignment'
import AssignmentFormDialog from './components/AssignmentFormDialog.vue'
import { assignmentTargetTagType, assignmentTargetTypeKey, layoutModeKey } from '@/utils/format'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'

const { t } = useI18n()

const {
  loading,
  query,
  dialogVisible,
  dialogMode,
  editingRow,
  targetTypeFilterOptions,
  handleSearch,
  handleReset,
  loadAssignments,
  showCreateDialog,
  showEditDialog,
  handleDelete,
  ACTIONS_COL_WIDTH,
  displayColumns,
  displayRows,
  selectionColumnWidth,
  handleGridSelectionChange,
  exportGridCsv,
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
  moveColumn,
  openFilter,
  applyFilter,
  clearFilter,
  applySort,
  clearSort,
} = useBiAssignment()

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  void loadAssignments()
}

function onClearSort() {
  clearSort()
  void loadAssignments()
}


function onClearFilter(field: string) {
  clearFilter(field)
  void loadAssignments()
}

function onFilterApply(filter: ListColumnFilter) {
  applyFilter(filter)
  void loadAssignments()
}

function onFilterClear() {
  onClearFilter(filterDialog.field)
}

onMounted(() => {
  void loadAssignments()
})
</script>
