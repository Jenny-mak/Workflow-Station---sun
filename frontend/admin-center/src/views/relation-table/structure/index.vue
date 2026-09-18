<template>
  <div class="page-container">
    <PageHeader title="Table Structure">
      <template #actions>
        <el-button
          @click="router.push('/relation-tables/structure/er-diagram')"
        >
          <el-icon><Share /></el-icon>View ER Diagram
        </el-button>
        <el-button
          type="primary"
          @click="router.push('/relation-tables/structure/create')"
        >
          <el-icon><Plus /></el-icon>Create Table
        </el-button>
      </template>
    </PageHeader>

    <div class="structure-layout">
      <div class="fu-list-panel">
        <div class="panel-title">
          {{ t('relationTable.functionUnit') }}
        </div>
        <el-menu
          :default-active="selectedGroupKey"
          @select="(index: string) => (selectedGroupKey = index)"
        >
          <el-menu-item index="">
            <span>{{ t('relationTable.allFunctionUnits') }}</span>
          </el-menu-item>
          <el-menu-item
            v-for="group in functionUnitGroups"
            :key="group.key"
            :index="group.key"
          >
            <el-tooltip
              :content="groupLabel(group)"
              placement="top"
              :show-after="400"
            >
              <span class="group-title">{{ groupLabel(group) }} ({{ group.count }})</span>
            </el-tooltip>
          </el-menu-item>
        </el-menu>
      </div>

      <div
        v-loading="loading"
        class="table-card"
      >
        <div class="grid-toolbar">
          <el-input
            v-model="structureKeyword"
            clearable
            placeholder="Search..."
            style="width: 240px"
            @keyup.enter="runStructureSearch"
            @clear="runStructureSearch"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-button type="primary" @click="runStructureSearch">
            {{ t('common.search') }}
          </el-button>
          <el-button @click="exportGridCsv('relation-table-structures')">
            <el-icon><Download /></el-icon>{{ t('common.export') }}
          </el-button>
        </div>
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
<span v-if="col.field === 'currentVersion'">v{{ row.currentVersion }}</span>
                  <el-tag
                    v-else-if="col.field === 'status'"
                    :type="statusTagType(row.status)"
                    size="small"
                  >
                    {{ row.status }}
                  </el-tag>
                  <el-switch
                    v-else-if="col.field === 'enabled'"
                    v-model="row.enabled"
                    :loading="enableLoadingMap[row.id]"
                    @change="(val: string | number | boolean) => handleToggleEnabled(row, val as boolean)"
                  />
                  <el-switch
                    v-else-if="col.field === 'portalVisible'"
                    v-model="row.portalVisible"
                    :loading="portalLoadingMap[row.id]"
                    :disabled="!row.enabled"
                    @change="(val: string | number | boolean) => handleTogglePortalVisibility(row, val as boolean)"
                  />
                  <span v-else-if="col.field === 'createdAt'">{{ formatDate(row.createdAt) }}</span>
                  <span v-else-if="col.field === 'updatedAt'">{{ formatDate(row.updatedAt) }}</span>
                  <template v-else>
                    {{ row[col.field as keyof typeof row] ?? '-' }}
                  </template>
                </template>
              </el-table-column>
              <el-table-column
                label="Actions"
                :width="ACTIONS_COL_WIDTH"
                fixed="right"
                align="center"
              >
                <template #header>
                  Actions
                </template>
                <template #default="{ row }">
                  <div
                    
                    class="action-cell"
                  >
                    <el-button
                      link
                      type="warning"
                      size="small"
                      @click="handleEdit(row)"
                    >
                      Edit
                    </el-button>
                    <el-button
                      link
                      type="primary"
                      size="small"
                      @click="handleDeploy(row)"
                    >
                      Deploy
                    </el-button>
                    <el-button
                      link
                      type="primary"
                      size="small"
                      @click="handleVersions(row)"
                    >
                      Version
                    </el-button>
                    <el-dropdown
                      trigger="click"
                      @command="(cmd: string) => handleActionCommand(cmd, row)"
                    >
                      <el-button
                        link
                        type="primary"
                        size="small"
                      >
                        More<el-icon class="el-icon--right"><ArrowDown /></el-icon>
                      </el-button>
                      <template #dropdown>
                        <el-dropdown-menu>
                          <el-dropdown-item command="erDiagram">
                            ER Diagram
                          </el-dropdown-item>
                          <el-dropdown-item command="compare">
                            Compare
                          </el-dropdown-item>
                          <el-dropdown-item command="rollback">
                            Rollback
                          </el-dropdown-item>
                          <el-dropdown-item command="access">
                            Access
                          </el-dropdown-item>
                          <el-dropdown-item
                            command="delete"
                            divided
                          >
                            <span class="danger-item">Delete</span>
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
          @change="fetchTableList"
        />
      </div>
    </div>

    <VersionDialog
      v-model="showVersionDialog"
      :table-id="currentTable?.id"
      :table-name="currentTable?.tableName"
      @rollback-success="fetchTableList"
    />

    <AccessConfigDialog
      v-model="showAccessDialog"
      :table-id="currentTable?.id"
      :table-name="currentTable?.tableName"
    />

    <VersionCompareDialog
      v-model="showCompareDialog"
      :table-id="currentTable?.id"
      :table-name="currentTable?.tableName"
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
import { onMounted, onActivated, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowDown, Download, Plus, Search, Share } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { relationTableStatusType as statusTagType, formatDate } from '@/utils/format'
import VersionDialog from './components/VersionDialog.vue'
import AccessConfigDialog from './components/AccessConfigDialog.vue'
import VersionCompareDialog from './components/VersionCompareDialog.vue'
import { useRelationTable } from '@/composables/modules/useRelationTable'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'
import type { RelationTableResponse } from '@/api/relationTable'

const router = useRouter()
const { t } = useI18n()

const {
  loading,
  functionUnitGroups,
  selectedGroupKey,
  COMMON_KEY,
  enableLoadingMap,
  portalLoadingMap,
  currentTable,
  showVersionDialog,
  showAccessDialog,
  showCompareDialog,
  fetchTableList,
  handleToggleEnabled,
  handleTogglePortalVisibility,
  handleAccess,
  handleDeploy,
  handleVersions,
  handleEdit,
  handleRollback,
  handleCompare,
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
} = useRelationTable()

const structureKeyword = ref('')

function runStructureSearch() {
  setQuickFilter('displayName', structureKeyword.value)
  void fetchTableList()
}

function groupLabel(group: { key: string; label: string | null }): string {
  if (group.key === COMMON_KEY) return t('relationTable.common')
  return group.label || t('relationTable.ungrouped')
}

function handleActionCommand(command: string, row: RelationTableResponse) {
  switch (command) {
    case 'erDiagram':
      router.push(`/relation-tables/structure/${row.id}/er-diagram`)
      break
    case 'compare':
      handleCompare(row)
      break
    case 'rollback':
      handleRollback(row)
      break
    case 'access':
      handleAccess(row)
      break
    case 'delete':
      void handleDelete(row)
      break
  }
}

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  void fetchTableList()
}

function onClearSort() {
  clearSort()
  void fetchTableList()
}


function onClearFilter(field: string) {
  clearFilter(field)
  void fetchTableList()
}

function onFilterApply(filter: ListColumnFilter) {
  applyFilter(filter)
  void fetchTableList()
}

function onFilterClear() {
  onClearFilter(filterDialog.field)
}

onMounted(() => {
  void fetchTableList()
})

onActivated(() => {
  void fetchTableList()
})
</script>

<style scoped>
.danger-item {
  color: var(--el-color-danger);
}
.structure-layout {
  display: flex;
  gap: 16px;
  height: calc(100vh - 220px);
  min-height: 480px;
}
.fu-list-panel {
  width: 240px;
  flex-shrink: 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: 4px;
  overflow-y: auto;
  background: var(--el-bg-color);
}
.fu-list-panel :deep(.el-menu-item.is-active) {
  background-color: var(--el-color-primary-light-9, #ecf5ff);
  color: var(--el-color-primary, #409eff);
}
.panel-title {
  padding: 12px 16px;
  font-weight: 600;
  font-size: 14px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.group-title {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.table-card {
  flex: 1;
  min-width: 0;
  overflow: auto;
  background: var(--el-bg-color);
  display: flex;
  flex-direction: column;
}
.grid-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-shrink: 0;
}
.action-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 0;
}
</style>
