<template>
  <div class="page-container">
    <PageHeader :title="t('menu.userList')">
      <template #actions>
        <el-button @click="exportGridCsv('users')">
          <el-icon><Download /></el-icon>{{ t('common.export') }}
        </el-button>
        <el-button
          v-if="canWriteUser"
          type="primary"
          @click="showCreateDialog"
        >
          <el-icon><Plus /></el-icon>{{ t('user.createUser') }}
        </el-button>
      </template>
    </PageHeader>
    
    <el-card class="search-card">
      <el-form
        :inline="true"
        :model="query"
        class="search-form"
      >
        <el-form-item :label="t('user.keyword')">
          <el-input
            v-model="query.keyword"
            :placeholder="t('user.keywordPlaceholder')"
            clearable
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item :label="t('common.status')">
          <el-select
            v-model="query.status"
            :placeholder="t('user.selectStatus')"
            clearable
            style="width: 120px"
          >
            <el-option
              :label="t('user.active')"
              value="ACTIVE"
            />
            <el-option
              :label="t('user.disabled')"
              value="DISABLED"
            />
            <el-option
              :label="t('user.locked')"
              value="LOCKED"
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
            <el-icon><Refresh /></el-icon>{{ t('common.reset') }}
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
<span
                  v-if="col.field === 'entityManagerName' || col.field === 'functionManagerName'"
                  :class="row[col.field] ? 'manager-name' : 'no-manager'"
                >{{ row[col.field] || '-' }}</span>
                <el-tag
                  v-else-if="col.field === 'status'"
                  :type="statusTagType(row.status)"
                  size="small"
                >
                  {{ t(userStatusKey(row.status)) }}
                </el-tag>
                <template v-else>
                  {{ row[col.field] || '-' }}
                </template>
              </template>
            </el-table-column>
            <el-table-column
              :label="t('common.actions')"
              :width="ACTIONS_COL_WIDTH"
              fixed="right"
            >
              <template #header>
                {{ t('common.actions') }}
              </template>
              <template #default="{ row }">
                <div
                  
                  style="display: flex; align-items: center; flex-wrap: nowrap; white-space: nowrap;"
                >
                  <el-button
                    v-if="canWriteUser"
                    link
                    type="primary"
                    size="small"
                    @click="showEditDialog(row)"
                  >
                    {{ t('common.edit') }}
                  </el-button>
                  <el-button
                    link
                    type="primary"
                    size="small"
                    @click="showDetailDialog(row)"
                  >
                    {{ t('common.view') }}
                  </el-button>
                  <el-dropdown
                    v-if="canWriteUser"
                    @command="(cmd: string) => handleCommand(row, cmd)"
                  >
                    <el-button
                      link
                      type="primary"
                      size="small"
                      :title="t('common.operation')"
                    >
                      <el-icon><MoreFilled /></el-icon>
                    </el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item
                          v-if="row.status !== 'ACTIVE'"
                          command="enable"
                        >
                          <el-icon><CircleCheck /></el-icon>{{ t('common.enable') }}
                        </el-dropdown-item>
                        <el-dropdown-item
                          v-if="row.status === 'ACTIVE'"
                          command="disable"
                        >
                          <el-icon><CircleClose /></el-icon>{{ t('common.disable') }}
                        </el-dropdown-item>
                        <el-dropdown-item
                          v-if="row.status === 'LOCKED'"
                          command="unlock"
                        >
                          <el-icon><Unlock /></el-icon>{{ t('user.unlock') }}
                        </el-dropdown-item>
                        <el-dropdown-item
                          v-if="canDeleteUser"
                          command="delete"
                          divided
                        >
                          <el-icon><Delete /></el-icon>{{ t('common.delete') }}
                        </el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
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
        @change="loadUsers"
      />
    </el-card>
    
    <!-- User form dialog -->
    <UserFormDialog 
      v-model="formDialogVisible" 
      :user="currentUser" 
      @success="handleSearch" 
    />
    
    <!-- User detail dialog -->
    <UserDetailDialog
      v-model="detailDialogVisible"
      :user-id="currentUserId"
    />
    
    <!-- Batch import dialog -->
    <UserImportDialog
      v-model="importDialogVisible"
      @success="handleSearch"
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
import { onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  Plus, Search, Refresh, MoreFilled,
  CircleCheck, CircleClose, Unlock, Delete, Download
} from '@element-plus/icons-vue'
import { statusTagType, userStatusKey } from '@/utils/format'
import { useUser } from '@/composables/modules/useUser'
import { searchListFilterUsers } from '@/composables/list/searchListFilterUsers'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'
import UserFormDialog from './components/UserFormDialog.vue'
import UserDetailDialog from './components/UserDetailDialog.vue'
import UserImportDialog from './components/UserImportDialog.vue'
import PageHeader from '@/components/PageHeader.vue'

const { t } = useI18n()

const {
  loading,
  query,
  formDialogVisible,
  detailDialogVisible,
  importDialogVisible,
  currentUser,
  currentUserId,
  canWriteUser,
  canDeleteUser,
  handleSearch,
  handleReset,
  loadUsers,
  showCreateDialog,
  showEditDialog,
  showDetailDialog,
  handleCommand,
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
} = useUser()

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  void loadUsers()
}

function onClearSort() {
  clearSort()
  void loadUsers()
}


function onClearFilter(field: string) {
  clearFilter(field)
  void loadUsers()
}

function onFilterApply(filter: ListColumnFilter) {
  applyFilter(filter)
  void loadUsers()
}

function onFilterClear() {
  onClearFilter(filterDialog.field)
}

onMounted(() => {
  void loadUsers()
})
</script>
