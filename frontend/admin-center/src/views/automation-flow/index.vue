<template>
  <div class="page-container">
    <PageHeader :title="t('automationFlow.title')">
      <template #actions>
        <el-button @click="exportGridCsv('automation-flows')">
          <el-icon><Download /></el-icon>{{ t('common.export') }}
        </el-button>
        <el-button @click="loadFlows">
          <el-icon><Refresh /></el-icon>{{ t('common.refresh') }}
        </el-button>
        <el-button
          type="primary"
          @click="importDialogVisible = true"
        >
          <el-icon><Upload /></el-icon>{{ t('automationFlow.import') }}
        </el-button>
      </template>
    </PageHeader>

    <el-card
      v-loading="loading"
      class="table-card"
    >
      <div class="toolbar">
        <el-input
          v-model="keyword"
          :placeholder="t('automationFlow.searchPlaceholder')"
          clearable
          style="width: 280px"
          @keyup.enter="loadFlows"
          @clear="loadFlows"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button
          type="primary"
          @click="loadFlows"
        >
          {{ t('common.search') }}
        </el-button>
        <span class="flow-count">{{ t('automationFlow.total', { count: pagination.total }) }}</span>
      </div>

      <div
        ref="gridScrollRef"
        class="list-data-grid-scroll"
      >
        <div
          class="list-data-grid-inner"
          :style="isCompact ? undefined : gridInnerStyle"
        >
          <el-table
            :data="displayRows"
            stripe
            :fit="false"
            table-layout="fixed"
            style="width: 100%"
            class="list-data-grid"
            :class="{ 'list-data-grid--fit': gridFits && !isCompact }"
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
              v-for="(col, colIndex) in tableColumns"
              :key="col.field"
              :prop="col.field"
              :width="widthOf(col.field)"
              :show-overflow-tooltip="!(isCompact && col.field === 'displayName')"
            >
              <template #header>
                <ListColumnHeader
                  :column="col"
                  :sort="sort.field === col.field ? sort.direction : null"
                  :filtered="!!columnFilters[col.field]"
                  :width="widthOf(col.field)"
                  :show-move="tableColumns.length > 1"
                  :can-move-left="colIndex > 0"
                  :can-move-right="colIndex < tableColumns.length - 1"
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
<div
                  v-if="col.field === 'displayName'"
                  class="flow-name"
                >
                  <span class="flow-name__title">{{ row.displayName }}</span>
                  <span
                    v-if="isCompact"
                    class="flow-name__meta"
                  >{{ compactMeta(row) }}</span>
                </div>
                <div
                  v-else-if="col.field === 'id'"
                  class="flow-identity"
                >
                  <code class="flow-identity__id">{{ row.id }}</code>
                  <span
                    v-if="row.flowKey && row.flowKey !== row.id"
                    class="flow-identity__origin"
                    :title="row.flowKey"
                  >{{ t('automationFlow.migratedFrom', { key: row.flowKey }) }}</span>
                </div>
                <el-tag
                  v-else-if="col.field === 'readiness'"
                  :type="readiness(row).type"
                  size="small"
                  :effect="readiness(row).effect"
                  disable-transitions
                >
                  {{ t(readiness(row).labelKey) }}
                </el-tag>
                <span v-else-if="col.field === 'ownerName'">{{ row.ownerName || '—' }}</span>
                <span v-else-if="col.field === 'updated'">{{ formatDate(row.updated) }}</span>
                <template v-else>
                  {{ row[col.field] ?? '-' }}
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
                    link
                    type="primary"
                    size="small"
                    :loading="exportingId === row.id"
                    @click="handleExport(row)"
                  >
                    {{ t('automationFlow.export') }}
                  </el-button>
                  <el-dropdown
                    trigger="click"
                    @command="(cmd: string) => handleRowCommand(cmd, row)"
                  >
                    <el-button
                      link
                      size="small"
                      :loading="actingId === row.id"
                      :aria-label="t('common.operation')"
                    >
                      <el-icon><MoreFilled /></el-icon>
                    </el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item command="structure">
                          {{ t('automationFlow.viewStructure') }}
                        </el-dropdown-item>
                        <el-dropdown-item
                          command="toggle"
                          :disabled="!row.published"
                        >
                          {{ row.status === 'ENABLED' ? t('automationFlow.disable') : t('automationFlow.enable') }}
                        </el-dropdown-item>
                        <el-dropdown-item command="transfer">
                          {{ t('automationFlow.transfer') }}
                        </el-dropdown-item>
                        <el-dropdown-item
                          command="delete"
                          divided
                        >
                          <span class="danger-item">{{ t('common.delete') }}</span>
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
        @change="loadFlows"
      />
    </el-card>

    <FlowStructureDialog
      v-model="structureDialogVisible"
      :flow-id="structureFlow?.id ?? ''"
      :flow-name="structureFlow?.displayName ?? ''"
    />

    <el-dialog
      v-model="transferDialogVisible"
      :title="t('automationFlow.transferTitle')"
      width="520px"
    >
      <el-form label-width="auto">
        <el-form-item :label="t('automationFlow.displayName')">
          <span>{{ transferFlowRow?.displayName }}</span>
        </el-form-item>
        <el-form-item :label="t('automationFlow.workspace')">
          <span>{{ transferFlowRow?.workspaceName }}</span>
        </el-form-item>
        <el-form-item :label="t('automationFlow.transferTarget')">
          <el-select
            v-model="transferTargetId"
            style="width: 100%"
            :placeholder="t('automationFlow.transferTargetPlaceholder')"
          >
            <el-option
              v-for="ws in workspaceOptions"
              :key="ws.id"
              :label="ws.publicWorkspace ? t('automationFlow.workspacePublic') : ws.name"
              :value="ws.id"
              :disabled="ws.name === transferFlowRow?.workspaceName"
            />
          </el-select>
          <div class="import-hint">
            {{ t('automationFlow.transferHint') }}
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button
          type="primary"
          :loading="transferring"
          :disabled="!transferTargetId"
          @click="handleTransfer"
        >
          {{ t('automationFlow.transferConfirm') }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="importDialogVisible"
      :title="t('automationFlow.importTitle')"
      width="520px"
      @closed="resetImportDialog"
    >
      <el-form label-width="auto">
        <el-form-item :label="t('automationFlow.importFile')">
          <el-upload
            :show-file-list="true"
            :auto-upload="false"
            :limit="1"
            accept=".json"
            @change="onImportFileChange"
            @remove="importFile = null"
          >
            <el-button>{{ t('automationFlow.chooseFile') }}</el-button>
          </el-upload>
        </el-form-item>
        <el-form-item :label="t('automationFlow.importWorkspace')">
          <el-select
            v-model="importWorkspaceId"
            style="width: 100%"
            @change="onImportWorkspaceChange"
          >
            <el-option
              v-for="ws in workspaceOptions"
              :key="ws.id"
              :label="ws.publicWorkspace ? t('automationFlow.workspacePublic') : ws.name"
              :value="ws.id"
            />
          </el-select>
          <span class="import-hint">{{ t('automationFlow.importWorkspaceHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('automationFlow.publishLabel')">
          <el-switch v-model="importPublish" />
          <span class="import-hint">{{ t('automationFlow.publishHint') }}</span>
        </el-form-item>
        <el-form-item
          v-if="connectionChecks.length > 0"
          :label="t('automationFlow.connectionsTitle')"
        >
          <div class="connection-list">
            <div
              v-for="item in connectionChecks"
              :key="item.externalId"
              class="connection-item"
            >
              <el-tag
                :type="item.exists ? 'success' : 'danger'"
                size="small"
                disable-transitions
              >
                {{ item.exists ? t('automationFlow.connectionExists') : t('automationFlow.connectionMissing') }}
              </el-tag>
              <code>{{ item.externalId }}</code>
              <span
                v-if="item.pieceName"
                class="connection-piece"
              >{{ shortPieceName(item.pieceName) }}</span>
            </div>
            <div
              v-if="hasMissingConnections"
              class="connection-warning"
            >
              {{ t('automationFlow.connectionsHint') }}
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button
          type="primary"
          :loading="importing"
          :disabled="!importFile"
          @click="handleImport"
        >
          {{ t('automationFlow.importConfirm') }}
        </el-button>
      </template>
    </el-dialog>

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
import { Download, MoreFilled, Refresh, Search, Upload } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import FlowStructureDialog from '@/components/automation-flow/FlowStructureDialog.vue'
import { formatDate } from '@/utils/format'
import { useAutomationFlow } from '@/composables/modules/useAutomationFlow'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'

const { t } = useI18n()

const {
  loading,
  keyword,
  exportingId,
  actingId,
  structureDialogVisible,
  structureFlow,
  importDialogVisible,
  importFile,
  importPublish,
  importWorkspaceId,
  workspaceOptions,
  onImportWorkspaceChange,
  transferDialogVisible,
  transferFlowRow,
  transferTargetId,
  transferring,
  handleTransfer,
  importing,
  connectionChecks,
  hasMissingConnections,
  isCompact,
  tableColumns,
  compactMeta,
  readiness,
  shortPieceName,
  loadFlows,
  handleExport,
  handleRowCommand,
  onImportFileChange,
  resetImportDialog,
  handleImport,
  ACTIONS_COL_WIDTH,
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
} = useAutomationFlow()

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  void loadFlows()
}

function onClearSort() {
  clearSort()
  void loadFlows()
}


function onClearFilter(field: string) {
  clearFilter(field)
  void loadFlows()
}

function onFilterApply(filter: ListColumnFilter) {
  applyFilter(filter)
  void loadFlows()
}

function onFilterClear() {
  onClearFilter(filterDialog.field)
}

onMounted(() => { void loadFlows() })
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.flow-count {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.danger-item {
  color: var(--el-color-danger);
}

.flow-name {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.flow-name__title,
.flow-name__meta {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.flow-name__meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}

.flow-identity {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.flow-identity__id,
.flow-identity__origin {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.flow-identity__origin {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.table-card :deep(.el-scrollbar__bar.is-horizontal) {
  opacity: 1;
  height: 8px;
}

.import-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-left: 10px;
}

.connection-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.connection-item {
  display: flex;
  align-items: center;
  gap: 8px;
  line-height: 22px;
}

.connection-piece {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.connection-warning {
  color: var(--el-color-warning);
  font-size: 12px;
  line-height: 1.4;
}
</style>
