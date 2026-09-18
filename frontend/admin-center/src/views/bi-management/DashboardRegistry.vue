<template>
  <div class="page-container">
    <PageHeader :title="t('bi.dashboard.pageTitle')">
      <template #actions>
        <el-button @click="exportGridCsv('bi-dashboard-registry')">
          <el-icon><Download /></el-icon>{{ t('common.export') }}
        </el-button>
        <el-button
          type="primary"
          :loading="syncing"
          @click="handleSync"
        >
          <el-icon><Refresh /></el-icon>{{ t('bi.dashboard.syncDashboards') }}
        </el-button>
      </template>
    </PageHeader>

    <el-card class="search-card">
      <el-form
        :inline="true"
        :model="query"
        class="search-form"
      >
        <el-form-item :label="t('bi.dashboard.searchTitle')">
          <el-input
            v-model="query.title"
            :placeholder="t('bi.dashboard.searchTitlePlaceholder')"
            clearable
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item :label="t('bi.dashboard.searchTags')">
          <el-input
            v-model="query.tags"
            :placeholder="t('bi.dashboard.searchTagsPlaceholder')"
            clearable
            style="width: 160px"
          />
        </el-form-item>
        <el-form-item :label="t('bi.dashboard.filterStatus')">
          <el-select
            v-model="query.status"
            :placeholder="t('bi.dashboard.filterStatusPlaceholder')"
            clearable
            style="width: 140px"
          >
            <el-option
              :label="t('bi.dashboard.statusActive')"
              value="ACTIVE"
            />
            <el-option
              :label="t('bi.dashboard.statusManualInactive')"
              value="MANUAL_INACTIVE"
            />
            <el-option
              :label="t('bi.dashboard.statusAutoInactive')"
              value="AUTO_INACTIVE"
            />
          </el-select>
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
<template v-if="col.field === 'isDefaultLanding'">
                  <el-tag
                    v-if="row.isDefaultLanding"
                    type="success"
                    size="small"
                  >
                    {{ t('bi.dashboard.yes') }}
                  </el-tag>
                  <el-tag
                    v-else
                    type="info"
                    size="small"
                  >
                    {{ t('bi.dashboard.no') }}
                  </el-tag>
                </template>
                <el-tag
                  v-else-if="col.field === 'status'"
                  :type="biDashboardStatusTagType(row.status) as 'success' | 'warning' | 'info'"
                  size="small"
                >
                  {{ t(biDashboardStatusKey(row.status)) }}
                </el-tag>
                <span v-else-if="col.field === 'lastSyncedAt'">
                  {{ row.lastSyncedAt ? formatDateTime(row.lastSyncedAt) : '-' }}
                </span>
                <span
                  v-else-if="col.field === 'supersetRoleNames'"
                  :title="row.supersetRoleNames?.length ? undefined : t('bi.dashboard.supersetRolesUnrestricted')"
                >
                  {{ row.supersetRoleNames?.length ? row.supersetRoleNames.join(', ') : '-' }}
                </span>
                <template v-else>
                  {{ row[col.field] || '-' }}
                </template>
              </template>
            </el-table-column>
            <el-table-column
              :label="t('bi.dashboard.colActions')"
              :width="ACTIONS_COL_WIDTH"
              fixed="right"
              align="center"
            >
              <template #header>
                {{ t('bi.dashboard.colActions') }}
              </template>
              <template #default="{ row }">
                <div
                  
                  style="display: flex; align-items: center; justify-content: center; flex-wrap: nowrap; white-space: nowrap; gap: 4px;"
                >
                  <el-button
                    link
                    type="primary"
                    size="small"
                    @click="showEditDialog(row)"
                  >
                    {{ t('bi.dashboard.edit') }}
                  </el-button>
                  <el-button
                    v-if="row.status === 'ACTIVE'"
                    link
                    type="warning"
                    size="small"
                    @click="handleToggleStatus(row)"
                  >
                    {{ t('bi.dashboard.disable') }}
                  </el-button>
                  <el-button
                    v-else-if="row.status === 'MANUAL_INACTIVE'"
                    link
                    type="success"
                    size="small"
                    @click="handleToggleStatus(row)"
                  >
                    {{ t('bi.dashboard.enable') }}
                  </el-button>
                  <el-button
                    v-else
                    link
                    type="info"
                    size="small"
                    disabled
                  >
                    {{ t('bi.dashboard.enable') }}
                  </el-button>
                  <el-button
                    link
                    type="danger"
                    size="small"
                    @click="handleDelete(row)"
                  >
                    {{ t('bi.dashboard.delete') }}
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
        @change="loadDashboards"
      />
    </el-card>

    <DashboardEditDialog
      v-model="editDialogVisible"
      :edit-form="editForm"
      :edit-loading="editLoading"
      @submit="handleEditSubmit"
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
import { onMounted, onActivated } from 'vue'
import { useI18n } from 'vue-i18n'
import { Download, Refresh, Search, Refresh as RefreshIcon } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { useBiDashboard } from '@/composables/modules/useBiDashboard'
import { biDashboardStatusKey, biDashboardStatusTagType, formatDateTime } from '@/utils/format'
import DashboardEditDialog from './components/DashboardEditDialog.vue'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'

const { t } = useI18n()

const {
  loading, syncing, editLoading, query,
  editDialogVisible, editForm,
  handleSearch, handleReset, handleSync, loadDashboards,
  showEditDialog, handleEditSubmit, handleToggleStatus, handleDelete,
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
} = useBiDashboard()

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  void loadDashboards()
}

function onClearSort() {
  clearSort()
  void loadDashboards()
}


function onClearFilter(field: string) {
  clearFilter(field)
  void loadDashboards()
}

function onFilterApply(filter: ListColumnFilter) {
  applyFilter(filter)
  void loadDashboards()
}

function onFilterClear() {
  onClearFilter(filterDialog.field)
}

onMounted(() => { void loadDashboards() })
onActivated(() => { void loadDashboards() })
</script>
