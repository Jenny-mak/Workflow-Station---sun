<template>
  <div class="page-container">
    <PageHeader :title="t('bi.rbac.pageTitle')">
      <template #actions>
        <el-button @click="exportGridCsv('bi-rbac-mappings')">
          <el-icon><Download /></el-icon>{{ t('common.export') }}
        </el-button>
        <el-button
          type="success"
          @click="showCreateDialog"
        >
          <el-icon><Plus /></el-icon>{{ t('bi.rbac.createMapping') }}
        </el-button>
        <el-button
          type="primary"
          :loading="syncing"
          @click="handleSync"
        >
          <el-icon><Refresh /></el-icon>{{ t('bi.rbac.syncRoles') }}
        </el-button>
      </template>
    </PageHeader>

    <el-card class="search-card">
      <el-form
        :inline="true"
        :model="query"
        class="search-form"
      >
        <el-form-item :label="t('bi.rbac.searchRoleName')">
          <el-input
            v-model="query.roleName"
            :placeholder="t('bi.rbac.searchRoleNamePlaceholder')"
            clearable
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item :label="t('bi.rbac.filterRoleType')">
          <el-select
            v-model="query.roleType"
            :placeholder="t('bi.rbac.filterRoleTypePlaceholder')"
            clearable
            style="width: 160px"
          >
            <el-option
              :label="t('role.adminRole')"
              value="ADMIN"
            />
            <el-option
              :label="t('role.auditorRole')"
              value="AUDITOR"
            />
            <el-option
              :label="t('role.developerRole')"
              value="DEVELOPER"
            />
            <el-option
              :label="t('role.buBounded')"
              value="BU_BOUNDED"
            />
            <el-option
              :label="t('role.buUnbounded')"
              value="BU_UNBOUNDED"
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
<el-tag
                  v-if="col.field === 'sysRoleType'"
                  size="small"
                >
                  {{ t(roleTypeKey(row.sysRoleType)) }}
                </el-tag>
                <template v-else-if="col.field === 'supersetRoles'">
                  <template v-if="row.supersetRoles && row.supersetRoles.length > 0">
                    <el-tag
                      v-for="sr in row.supersetRoles"
                      :key="sr.id"
                      size="small"
                      class="role-tag"
                    >
                      {{ sr.name }}
                    </el-tag>
                  </template>
                  <span
                    v-else
                    style="color: #c0c4cc"
                  >-</span>
                </template>
                <template v-else>
                  {{ row[col.field] || '-' }}
                </template>
              </template>
            </el-table-column>
            <el-table-column
              :label="t('bi.rbac.colActions')"
              :width="ACTIONS_COL_WIDTH"
              fixed="right"
              align="center"
            >
              <template #header>
                {{ t('bi.rbac.colActions') }}
              </template>
              <template #default="{ row }">
                <div >
                  <el-button
                    link
                    type="primary"
                    size="small"
                    @click="showEditDialog(row)"
                  >
                    {{ t('bi.rbac.editMapping') }}
                  </el-button>
                  <el-button
                    link
                    type="danger"
                    size="small"
                    @click="handleDelete(row)"
                  >
                    {{ t('bi.rbac.delete') }}
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
        @change="loadMappings"
      />
    </el-card>

    <RbacCreateDialog
      ref="createDialogRef"
      v-model="createDialogVisible"
      :create-form="createForm"
      :create-form-rules="createFormRules"
      :unmapped-roles="unmappedRoles"
      :unmapped-roles-loading="unmappedRolesLoading"
      :create-active-superset-roles="createActiveSupersetRoles"
      :create-superset-roles-loading="createSupersetRolesLoading"
      :create-loading="createLoading"
      @submit="handleCreateSubmit"
    />

    <RbacEditDialog
      v-model="editDialogVisible"
      :edit-form="editForm"
      :active-superset-roles="activeSupersetRoles"
      :superset-roles-loading="supersetRolesLoading"
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
import { Download, Plus, Search, Refresh, Refresh as RefreshIcon } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { useBiRbac } from '@/composables/modules/useBiRbac'
import { roleTypeKey } from '@/utils/format'
import RbacCreateDialog from './components/RbacCreateDialog.vue'
import RbacEditDialog from './components/RbacEditDialog.vue'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'

const { t } = useI18n()

const {
  loading, syncing, editLoading, supersetRolesLoading,
  query, editDialogVisible, editForm, activeSupersetRoles,
  createDialogVisible, createLoading, unmappedRolesLoading, createSupersetRolesLoading,
  unmappedRoles, createDialogRef, createForm, createFormRules, createActiveSupersetRoles,
  handleSearch, handleReset, handleSync, loadMappings,
  showEditDialog, handleEditSubmit,
  showCreateDialog, handleCreateSubmit, handleDelete,
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
} = useBiRbac()

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  void loadMappings()
}

function onClearSort() {
  clearSort()
  void loadMappings()
}


function onClearFilter(field: string) {
  clearFilter(field)
  void loadMappings()
}

function onFilterApply(filter: ListColumnFilter) {
  applyFilter(filter)
  void loadMappings()
}

function onFilterClear() {
  onClearFilter(filterDialog.field)
}

onMounted(() => { void loadMappings() })
onActivated(() => { void loadMappings() })
</script>

<style scoped lang="scss">
.role-tag {
  margin-right: 6px;
  margin-bottom: 4px;
}
</style>
