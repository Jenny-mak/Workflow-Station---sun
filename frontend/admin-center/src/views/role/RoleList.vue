<template>
  <div class="page-container">
    <PageHeader :title="t('menu.roleList')">
      <template #actions>
        <el-button @click="exportGridCsv('roles')">
          <el-icon><Download /></el-icon>{{ t('common.export') }}
        </el-button>
        <el-button
          v-if="canWriteRole && activeTab === 'CUSTOM'"
          type="primary"
          @click="showCreateDialog"
        >
          <el-icon><Plus /></el-icon>{{ t('role.createRole') }}
        </el-button>
      </template>
    </PageHeader>

    <el-tabs v-model="activeTab">
      <el-tab-pane
        :label="t('role.tabSystem')"
        name="SYSTEM"
      />
      <el-tab-pane
        :label="t('role.tabCustom')"
        name="CUSTOM"
      />
    </el-tabs>

    <el-form
      :inline="true"
      :model="query"
      class="search-form"
    >
      <el-form-item>
        <el-input
          v-model="roleKeyword"
          clearable
          placeholder="Search role name"
          style="width: 220px"
          @keyup.enter="runSearch"
          @clear="runSearch"
        />
      </el-form-item>
      <el-form-item :label="t('role.roleType')">
        <el-select
          v-model="query.type"
          clearable
          style="width: 150px"
        >
          <el-option
            :label="t('role.buBounded')"
            value="BU_BOUNDED"
          />
          <el-option
            :label="t('role.buUnbounded')"
            value="BU_UNBOUNDED"
          />
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
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button
          type="primary"
          @click="runSearch"
        >
          {{ t('common.search') }}
        </el-button>
        <el-button @click="runReset">
          {{ t('common.reset') }}
        </el-button>
      </el-form-item>
    </el-form>

    <el-empty
      v-if="!loading && pagination.total === 0"
      :description="t('role.noRolesInTab')"
    />

    <el-card
      v-else
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
                <el-tooltip
                  v-if="col.field === 'name'"
                  :content="row.displayName || '-'"
                  placement="top-start"
                  :disabled="!row.displayName"
                  popper-class="role-desc-tooltip"
                >
                  <span style="cursor: default">{{ row.name }}</span>
                </el-tooltip>
                <el-tag
                  v-else-if="col.field === 'type'"
                  :type="roleTypeTagType(row.type) as any"
                  size="small"
                >
                  {{ t(roleTypeKey(row.type)) }}
                </el-tag>
                <el-tag
                  v-else-if="col.field === 'status'"
                  :type="row.status === 'ACTIVE' ? 'success' : 'info'"
                  size="small"
                >
                  {{ row.status === 'ACTIVE' ? t('common.enabled') : t('common.disabled') }}
                </el-tag>
                <el-icon
                  v-else-if="col.field === 'isSystem' && row.isSystem"
                  color="#E6A23C"
                >
                  <Lock />
                </el-icon>
                <template v-else>
                  {{ row[col.field as keyof typeof row] ?? '-' }}
                </template>
              </template>
            </el-table-column>
            <el-table-column
              :label="t('common.operation')"
              :width="ACTIONS_COL_WIDTH"
              fixed="right"
              align="center"
            >
              <template #header>
                {{ t('common.operation') }}
              </template>
              <template #default="{ row }">
                <div
                  
                  class="row-actions"
                >
                  <el-button
                    v-if="!row.isSystem && canWriteRole"
                    link
                    type="primary"
                    @click="showEditDialog(row)"
                  >
                    {{ t('common.edit') }}
                  </el-button>
                  <el-button
                    link
                    type="primary"
                    @click="showMembersDialog(row)"
                  >
                    {{ t('role.members') }}
                  </el-button>
                  <el-button
                    v-if="!row.isSystem && canWriteRole && canDeleteRole"
                    link
                    type="danger"
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
        @change="fetchRoles"
      />
    </el-card>

    <ListFilterDialog
      v-model:visible="filterDialog.visible"
      :column="activeFilterColumn"
      :filter="activeFilter"
      @apply="onFilterApply"
      @clear="onFilterClear"
    />

    <RoleFormDialog
      v-model="formDialogVisible"
      :role="currentRole"
      @success="fetchRoles"
    />
    <RoleMembersDialog
      v-model="membersDialogVisible"
      :role="currentRole"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import PageHeader from '@/components/PageHeader.vue'
import { Download, Lock, Plus } from '@element-plus/icons-vue'
import { roleTypeTagType, roleTypeKey } from '@/utils/format'
import RoleFormDialog from './components/RoleFormDialog.vue'
import RoleMembersDialog from './components/RoleMembersDialog.vue'
import { useRole } from '@/composables/modules/useRole'
import { useTabRefresh } from '@/composables/useTabRefresh'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'

const { t } = useI18n()

const {
  loading,
  canWriteRole,
  canDeleteRole,
  activeTab,
  query,
  formDialogVisible,
  membersDialogVisible,
  currentRole,
  fetchRoles,
  handleSearch,
  handleReset,
  showCreateDialog,
  showEditDialog,
  showMembersDialog,
  handleDelete,
  ACTIONS_COL_WIDTH,
  displayColumns,
  displayRows,
  selectionColumnWidth,
  handleGridSelectionChange,
  exportGridCsv,
  setQuickFilter,
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
} = useRole()

const roleKeyword = ref('')

function runSearch() {
  setQuickFilter('name', roleKeyword.value)
  handleSearch()
}

function runReset() {
  roleKeyword.value = ''
  setQuickFilter('name', '')
  handleReset()
}

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  void fetchRoles()
}

function onClearSort() {
  clearSort()
  void fetchRoles()
}


function onClearFilter(field: string) {
  clearFilter(field)
  void fetchRoles()
}

function onFilterApply(filter: ListColumnFilter) {
  applyFilter(filter)
  void fetchRoles()
}

function onFilterClear() {
  onClearFilter(filterDialog.field)
}

useTabRefresh(fetchRoles)

onMounted(() => {
  void fetchRoles()
})
</script>

<style scoped>
.search-form {
  margin-bottom: 16px;
}

.search-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: nowrap;
  white-space: nowrap;
  gap: 4px;
}
</style>

<style>
.role-desc-tooltip.el-popper {
  background-color: #737373 !important;
  color: #ffffff !important;
  border: 1px solid #808080 !important;
}
.role-desc-tooltip.el-popper .el-popper__arrow::before {
  background-color: #737373 !important;
  border-color: #808080 !important;
}
</style>
