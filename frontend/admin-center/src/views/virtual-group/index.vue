<template>
  <div class="page-container">
    <PageHeader :title="t('menu.virtualGroup')">
      <template #actions>
        <el-button @click="exportGridCsv('virtual-groups')">
          <el-icon><Download /></el-icon>{{ t('common.export') }}
        </el-button>
        <el-button
          v-if="!readOnly"
          type="primary"
          @click="showCreateDialog"
        >
          <el-icon><Plus /></el-icon>{{ t('virtualGroup.create') }}
        </el-button>
      </template>
    </PageHeader>

    <el-tabs v-model="activeTab">
      <el-tab-pane
        :label="t('virtualGroup.tabSystem')"
        name="SYSTEM"
      />
      <el-tab-pane
        :label="t('virtualGroup.tabCustom')"
        name="CUSTOM"
      />
      <el-tab-pane
        :label="t('virtualGroup.tabDeveloper')"
        name="DEVELOPER"
      />
    </el-tabs>

    <div class="toolbar">
      <el-input
        v-model="searchKeyword"
        :placeholder="t('virtualGroup.searchPlaceholder')"
        clearable
        style="width: 300px;"
        @keyup.enter="fetchGroups"
        @clear="fetchGroups"
      />
      <el-button
        type="primary"
        @click="fetchGroups"
      >
        {{ t('common.search') }}
      </el-button>
    </div>

    <el-empty
      v-if="!loading && pagination.total === 0"
      :description="searchKeyword.trim() ? t('virtualGroup.noSearchResults') : t('virtualGroup.noGroupsInTab')"
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
<el-tag
                  v-if="col.field === 'type'"
                  :type="typeTagType(row.type)"
                >
                  {{ t(virtualGroupTypeKey(row.type)) }}
                </el-tag>
                <template v-else-if="col.field === 'boundRoleName'">
                  <template v-if="row.boundRoleName">
                    <span>{{ row.boundRoleName }}</span>
                  </template>
                  <span
                    v-else
                    class="text-muted"
                  >-</span>
                </template>
                <el-tag
                  v-else-if="col.field === 'boundRoleType' && row.boundRoleType"
                  size="small"
                  :type="row.boundRoleType === 'BU_BOUNDED' ? 'warning' : 'success'"
                >
                  {{ t(roleTypeKey(row.boundRoleType)) }}
                </el-tag>
                <span
                  v-else-if="col.field === 'adGroup'"
                  :class="{ 'text-muted': !row.adGroup }"
                >{{ row.adGroup || '-' }}</span>
                <el-tag
                  v-else-if="col.field === 'status'"
                  :type="row.status === 'ACTIVE' ? 'success' : 'info'"
                  size="small"
                >
                  {{ row.status === 'ACTIVE' ? t('virtualGroup.active') : t('virtualGroup.inactive') }}
                </el-tag>
                <template v-else>
                  {{ row[col.field as keyof typeof row] ?? '-' }}
                </template>
              </template>
            </el-table-column>
            <el-table-column
              :label="t('common.operation')"
              :width="ACTIONS_COL_WIDTH"
              fixed="right"
            >
              <template #header>
                {{ t('common.operation') }}
              </template>
              <template #default="{ row }">
                <div
                  
                  class="row-actions"
                >
                  <el-button
                    v-if="!readOnly"
                    link
                    type="primary"
                    @click="showEditDialog(row)"
                  >
                    {{ t('virtualGroup.edit') }}
                  </el-button>
                  <el-button
                    link
                    type="primary"
                    @click="showMembersDialog(row)"
                  >
                    {{ t('virtualGroup.members') }}
                  </el-button>
                  <el-button
                    link
                    type="primary"
                    @click="showRolesDialog(row)"
                  >
                    {{ t('virtualGroup.bindRoles') }}
                  </el-button>
                  <el-button
                    v-if="!readOnly && row.type !== 'SYSTEM'"
                    link
                    type="primary"
                    :loading="statusToggleLoadingId === row.id"
                    @click="handleToggleStatus(row)"
                  >
                    {{ row.status === 'ACTIVE' ? t('virtualGroup.deactivate') : t('virtualGroup.activate') }}
                  </el-button>
                  <el-button
                    v-if="!readOnly && row.type !== 'SYSTEM'"
                    link
                    type="danger"
                    @click="handleDelete(row.id)"
                  >
                    {{ t('virtualGroup.delete') }}
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
        @change="fetchGroups"
      />
    </el-card>

    <ListFilterDialog
      v-model:visible="filterDialog.visible"
      :column="activeFilterColumn"
      :filter="activeFilter"
      @apply="onFilterApply"
      @clear="onFilterClear"
    />

    <VirtualGroupFormDialog
      v-model="formDialogVisible"
      :group="currentGroup"
      @success="handleCreateSuccess"
    />
    <VirtualGroupMembersDialog
      v-model="membersDialogVisible"
      :group="currentGroup"
      :read-only="readOnly"
    />
    <VirtualGroupRolesDialog
      v-model="rolesDialogVisible"
      :group="currentGroup"
      :read-only="readOnly"
      @success="fetchGroups"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { Download, Plus } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import VirtualGroupFormDialog from './components/VirtualGroupFormDialog.vue'
import VirtualGroupMembersDialog from './components/VirtualGroupMembersDialog.vue'
import VirtualGroupRolesDialog from './components/VirtualGroupRolesDialog.vue'
import { useVirtualGroup } from '@/composables/modules/useVirtualGroup'
import { virtualGroupTypeKey, roleTypeKey } from '@/utils/format'
import { hasPermission, PERMISSIONS } from '@/utils/permission'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'

const { t } = useI18n()
const readOnly = computed(() => !hasPermission(PERMISSIONS.USER_WRITE))

const {
  loading,
  activeTab,
  searchKeyword,
  formDialogVisible,
  membersDialogVisible,
  rolesDialogVisible,
  currentGroup,
  statusToggleLoadingId,
  fetchGroups,
  showCreateDialog,
  showEditDialog,
  showMembersDialog,
  showRolesDialog,
  handleDelete,
  handleCreateSuccess,
  handleToggleStatus,
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
} = useVirtualGroup()

function typeTagType(type: string): 'warning' | 'info' | 'success' {
  if (type === 'SYSTEM') return 'warning'
  if (type === 'DEVELOPER') return 'success'
  return 'info'
}

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  void fetchGroups()
}

function onClearSort() {
  clearSort()
  void fetchGroups()
}


function onClearFilter(field: string) {
  clearFilter(field)
  void fetchGroups()
}

function onFilterApply(filter: ListColumnFilter) {
  applyFilter(filter)
  void fetchGroups()
}

function onFilterClear() {
  onClearFilter(filterDialog.field)
}

onMounted(() => { void fetchGroups() })
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.row-actions {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  white-space: nowrap;
}

.text-muted {
  color: var(--el-text-color-secondary);
}
</style>
