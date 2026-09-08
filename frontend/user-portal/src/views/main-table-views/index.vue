<script setup lang="ts">
import { Expand, Fold, List } from '@element-plus/icons-vue'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { GridDisplayRow } from '@/utils/mainTableViewGridRuntime'
import { useMainTableViewPage } from '@/composables/mainTableView/useMainTableViewPage'
import { isMainTableView } from '@/composables/mainTableView/mainTableViewNav'
import { searchListFilterUsers } from '@/composables/list/searchListFilterUsers'

const {
  t, Search, Download, Refresh, dataLoading, selectedViewId, searchKeyword,
  currentPage, pageSize, gridRuntime, filterDialogVisible, filterDialogField,
  filterDraft, widthDialogVisible, widthDialogField, widthDraft, tableRef, selectedTableRows,
  importProgressVisible, importProgressPercent, importProgressFileName,
  importResultVisible, importResult, importProgressLabel, importResultStatus, importResultHeadline,
  selectedFuCode, showExportButton, selectedFu, displayColumns,
  viewListCollapsed, viewSearchKeyword, filteredGroupedViews, selectedTableKey, currentTableViewsSorted, handleSelectTable,
  MTV_SELECTION_COL_WIDTH, gridInnerStyle, gridScrollRef, gridFits, gridTableHeight, gridTableKey,
  pagedRows, displayTotal, toListColumnMeta,
  handleSearch, handlePageChange, formatCell, isRowSelectable, getRowKey, onSelectionChange, openRow, columnIndex,
  isFkLinkCell, openFkTarget, isLookupLinkCell, openLookupTarget, isFileLinkCell, fileLinksOf, previewFile,
  showRowTaskColumn, rowTasksOf, openRowTask, MTV_TASK_COL_WIDTH,
  handleSortChange, handleClearSort, openFilterDialog, openWidthDialog, handleMoveColumn,
  applyColumnFilter, clearColumnFilter, clearFilterFromDialog, applyColumnWidth,
  handleColumnResize, handleColumnResizeEnd, displayWidthOf,
  handleExport, mtvHeaderCellClassName,
  loadData, COLUMN_WIDTH_MIN, COLUMN_WIDTH_MAX,
} = useMainTableViewPage()
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">
        {{ selectedFu?.functionUnitName || t('menu.views') }}
      </span>
    </div>

    <div class="data-layout">
      <!-- Left: View list grouped by table (collapsible) — parity with Relation Tables -->
      <div
        v-if="viewListCollapsed"
        class="view-list-panel collapsed"
      >
        <el-tooltip
          :content="t('common.expand')"
          placement="right"
        >
          <el-button
            text
            class="collapse-toggle"
            @click="viewListCollapsed = false"
          >
            <el-icon><Expand /></el-icon>
          </el-button>
        </el-tooltip>
      </div>
      <div
        v-else
        class="view-list-panel"
      >
        <div class="panel-title">
          <span>{{ t('mainTableView.availableViews') }}</span>
          <el-tooltip
            :content="t('common.collapse')"
            placement="top"
          >
            <el-button
              text
              size="small"
              class="collapse-toggle"
              @click="viewListCollapsed = true"
            >
              <el-icon><Fold /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
        <div style="padding: 6px 8px;">
          <el-input
            v-model="viewSearchKeyword"
            :placeholder="t('mainTableView.searchViews')"
            clearable
            size="small"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
        </div>
        <el-menu
          :key="selectedTableKey || selectedFuCode"
          :default-active="selectedTableKey"
          @select="handleSelectTable"
        >
          <el-menu-item
            v-for="group in filteredGroupedViews"
            :key="group.tableId ?? group.label"
            :index="String(group.tableId ?? group.label)"
          >
            <span class="mtv-view-option-name">{{ group.label }}</span>
            <!-- Signals the click behaviour, which is what differs here: these rows open the
                 request detail page rather than a form designed for the view. -->
            <el-tag
              v-if="isMainTableView(group)"
              size="small"
              type="info"
            >
              {{ t('mainTableView.requestTableTag') }}
            </el-tag>
          </el-menu-item>
        </el-menu>
        <el-empty
          v-if="!filteredGroupedViews.length"
          :description="t('mainTableView.noViews')"
          :image-size="60"
        />
      </div>

      <!-- Right: Data grid -->
      <div
        v-loading="dataLoading"
        class="data-grid-panel"
      >
        <template v-if="selectedFuCode && selectedViewId">
          <div class="grid-toolbar">
            <el-select
              v-model="selectedViewId"
              :placeholder="t('mainTableView.selectView')"
              :disabled="!currentTableViewsSorted.length"
              style="width: 220px;"
            >
              <el-option
                v-for="v in currentTableViewsSorted"
                :key="v.id"
                :label="v.viewName"
                :value="v.id"
              />
            </el-select>
            <el-input
              v-model="searchKeyword"
              :placeholder="t('common.search')"
              clearable
              style="width: 240px;"
              @keyup.enter="handleSearch"
              @clear="handleSearch"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-button
              :icon="Refresh"
              @click="loadData"
            >
              {{ t('common.refresh') }}
            </el-button>
            <el-button
              v-if="showExportButton"
              type="primary"
              :icon="Download"
              @click="handleExport"
            >
              {{ t('common.export') }}
            </el-button>
            <span
              v-if="selectedTableRows.length"
              class="grid-hint"
            >
              {{ t('mainTableView.selectedRows', { count: selectedTableRows.length }) }}
            </span>
          </div>

          <div
            v-if="displayColumns.length"
            ref="gridScrollRef"
            class="mtv-data-grid-scroll"
          >
          <div
            class="mtv-data-grid-inner"
            :style="gridInnerStyle"
          >
            <el-table
              :key="gridTableKey"
              ref="tableRef"
              :data="pagedRows"
              :row-key="getRowKey"
              stripe
              :fit="false"
              table-layout="fixed"
              style="width: 100%;"
              class="mtv-data-grid"
              :class="{ 'mtv-data-grid--fit': gridFits }"
              scrollbar-always-on
              :height="gridTableHeight || '100%'"
              :header-cell-class-name="mtvHeaderCellClassName"
              @row-click="(row: GridDisplayRow) => openRow(row)"
              @selection-change="onSelectionChange"
            >
          <el-table-column
            type="selection"
            :width="MTV_SELECTION_COL_WIDTH"
            :selectable="isRowSelectable"
            reserve-selection
          />
          <el-table-column
            v-if="showRowTaskColumn"
            :width="MTV_TASK_COL_WIDTH"
            align="center"
            class-name="mtv-task-col"
            label-class-name="mtv-task-col"
          >
            <template #default="{ row }">
              <el-popover
                v-if="rowTasksOf(row).length > 1"
                placement="right-start"
                trigger="click"
                :width="260"
                popper-class="mtv-task-popper"
              >
                <template #reference>
                  <span
                    class="mtv-task-flag"
                    :title="t('mainTableView.myTasksOnRow', { count: rowTasksOf(row).length })"
                    @click.stop
                  >
                    <el-icon><List /></el-icon>
                  </span>
                </template>
                <p class="mtv-task-popper__title">{{ t('mainTableView.pickTaskToOpen') }}</p>
                <ul class="mtv-task-popper__list">
                  <li
                    v-for="task in rowTasksOf(row)"
                    :key="task.taskId"
                  >
                    <button
                      type="button"
                      class="mtv-task-popper__item"
                      @click.stop="openRowTask(task)"
                    >
                      {{ task.taskName || t('mainTableView.unnamedTask') }}
                    </button>
                  </li>
                </ul>
              </el-popover>
              <span
                v-else-if="rowTasksOf(row).length === 1"
                class="mtv-task-flag"
                :title="t('mainTableView.openMyTask')"
                @click.stop="openRowTask(rowTasksOf(row)[0])"
              >
                <el-icon><List /></el-icon>
              </span>
            </template>
          </el-table-column>
          <el-table-column
            v-for="col in displayColumns"
            :key="col.fieldName"
            :prop="col.fieldName"
            :width="displayWidthOf(col)"
            show-overflow-tooltip
          >
            <template #header>
              <ListColumnHeader
                :column="toListColumnMeta(col)"
                :sort="gridRuntime.sort?.fieldName === col.fieldName ? gridRuntime.sort.direction : null"
                :filtered="!!gridRuntime.filters[col.fieldName]"
                :width="displayWidthOf(col)"
                show-width
                show-move
                :can-move-left="columnIndex(col.fieldName) > 0"
                :can-move-right="columnIndex(col.fieldName) < gridRuntime.columnOrder.length - 1"
                @sort-change="(direction) => handleSortChange(col, direction)"
                @clear-sort="handleClearSort"
                @filter-open="openFilterDialog(col)"
                @clear-filter="clearColumnFilter(col)"
                @width-open="openWidthDialog(col)"
                @move="(direction) => handleMoveColumn(col, direction)"
                @width-change="(width) => handleColumnResize(col.fieldName, width)"
                @width-commit="handleColumnResizeEnd"
              />
            </template>
            <template #default="{ row }">
              <template v-if="isFileLinkCell(col, row)">
                <span class="mtv-file-cell">
                  <a
                    v-for="(file, fi) in fileLinksOf(col, row)"
                    :key="fi"
                    class="mtv-file-link"
                    :title="t('filePreview.previewFile')"
                    @click.stop="previewFile(file)"
                  >{{ file.name }}</a>
                </span>
              </template>
              <template v-else-if="isLookupLinkCell(col, row)">
                <a
                  class="mtv-fk-link"
                  :title="t('mainTableView.openLookupTable')"
                  @click.stop="openLookupTarget(col, row)"
                >{{ formatCell(col, row) }}</a>
              </template>
              <template v-else-if="isFkLinkCell(col, row)">
                <a
                  class="mtv-fk-link"
                  :title="t('mainTableView.openRelatedRecord')"
                  @click.stop="openFkTarget(col, row)"
                >{{ formatCell(col, row) }}</a>
              </template>
              <template v-else>
                {{ formatCell(col, row) }}
              </template>
            </template>
          </el-table-column>
            </el-table>
          </div>
        </div>

        <div
          v-if="displayTotal > 0"
          class="pagination-wrap"
        >
          <ListPagination
            :page="currentPage"
            :size="pageSize"
            :total="displayTotal"
            :loading="dataLoading"
            @change="({ page, size }) => handlePageChange(page, size)"
          />
        </div>
        </template>

        <el-empty
          v-else
          :description="t('mainTableView.selectFuAndView')"
        />
      </div>
    </div>

    <ListFilterDialog
      v-model:visible="filterDialogVisible"
      :column="filterDialogField ? toListColumnMeta(filterDialogField) : null"
      :filter="filterDraft"
      :remote-search="searchListFilterUsers"
      @apply="applyColumnFilter"
      @clear="clearFilterFromDialog"
    />

    <el-dialog
      v-model="widthDialogVisible"
      :title="widthDialogField ? `${t('mainTableView.colColumnWidth')}: ${widthDialogField.displayLabel}` : ''"
      width="360px"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item :label="t('mainTableView.columnWidthPx')">
          <el-slider
            v-model="widthDraft"
            :min="COLUMN_WIDTH_MIN"
            :max="COLUMN_WIDTH_MAX"
            show-input
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="widthDialogVisible = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          @click="applyColumnWidth"
        >
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="importProgressVisible"
      :title="t('mainTableView.importProgressTitle')"
      width="440px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
      align-center
    >
      <p
        v-if="importProgressFileName"
        class="import-progress-file"
      >
        {{ importProgressFileName }}
      </p>
      <p class="import-progress-label">
        {{ importProgressLabel }}
      </p>
      <el-progress
        :percentage="importProgressPercent"
        :stroke-width="14"
        striped
        striped-flow
      />
    </el-dialog>

    <el-dialog
      v-model="importResultVisible"
      :title="t('mainTableView.importResultTitle')"
      width="540px"
      destroy-on-close
      align-center
    >
      <el-result
        :icon="importResultStatus"
        :title="importResultHeadline"
        :sub-title="importProgressFileName"
      />
      <div
        v-if="importResult"
        class="import-result-stats"
      >
        <div class="import-stat import-stat--created">
          <span class="import-stat-value">{{ importResult.createdCount }}</span>
          <span class="import-stat-label">{{ t('mainTableView.importResultCreated') }}</span>
        </div>
        <div class="import-stat import-stat--updated">
          <span class="import-stat-value">{{ importResult.updatedCount }}</span>
          <span class="import-stat-label">{{ t('mainTableView.importResultUpdated') }}</span>
        </div>
        <div class="import-stat import-stat--skipped">
          <span class="import-stat-value">{{ importResult.skippedCount }}</span>
          <span class="import-stat-label">{{ t('mainTableView.importResultSkipped') }}</span>
        </div>
        <div
          class="import-stat import-stat--errors"
          :class="{ 'import-stat--has-errors': importResult.errorCount > 0 }"
        >
          <span class="import-stat-value">{{ importResult.errorCount }}</span>
          <span class="import-stat-label">{{ t('mainTableView.importResultErrors') }}</span>
        </div>
      </div>
      <div
        v-if="importResult?.errors?.length"
        class="import-error-section"
      >
        <div class="import-error-title">
          {{ t('mainTableView.importErrorList') }}
        </div>
        <el-scrollbar max-height="220px">
          <ul class="import-error-list">
            <li
              v-for="(err, idx) in importResult.errors"
              :key="idx"
            >
              {{ err }}
            </li>
          </ul>
        </el-scrollbar>
      </div>
      <template #footer>
        <el-button
          type="primary"
          @click="importResultVisible = false"
        >
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss">
@import '@/styles/listDataGrid.scss';

/* Teleported to body, so it cannot be reached from the scoped block below. */
.mtv-task-popper {
  .mtv-task-popper__title {
    margin: 0 0 6px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .mtv-task-popper__list {
    margin: 0;
    padding: 0;
    list-style: none;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .mtv-task-popper__item {
    width: 100%;
    padding: 6px 8px;
    border: none;
    border-radius: 4px;
    background: transparent;
    text-align: left;
    font-size: 13px;
    line-height: 1.4;
    color: var(--el-text-color-primary);
    cursor: pointer;

    &:hover { background: var(--el-fill-color-light); }
    &:focus-visible { outline: 1px solid var(--el-color-primary); }
  }
}
</style>

<style scoped lang="scss">
.page-container {
  padding: 16px 20px;
  height: 100%;
  min-width: 0;
  max-width: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.page-header {
  margin-bottom: 16px;
  .page-title {
    font-size: 18px;
    font-weight: 600;
  }
}
.data-layout {
  display: flex;
  gap: 16px;
  align-items: stretch;
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
  max-width: 100%;
}
.view-list-panel {
  width: 240px;
  flex-shrink: 0;
  align-self: stretch;
  min-height: 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: 4px;
  background: var(--el-bg-color);
  overflow-y: auto;
  transition: width 0.2s ease;

  :deep(.el-menu) {
    border-right: none;
  }
  :deep(.el-menu-item.is-active) {
    background-color: var(--el-color-primary-light-9, #ecf5ff);
    color: var(--el-color-primary, #409eff);
  }
  :deep(.el-menu-item.is-active)::before {
    display: none;
  }
}
.view-list-panel.collapsed {
  width: 40px;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding-top: 8px;
  overflow: hidden;
}
.collapse-toggle {
  padding: 4px;
}
.panel-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 8px 8px 16px;
  font-weight: 600;
  font-size: 14px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.data-grid-panel {
  flex: 1;
  min-height: 0;
  min-width: 0;
  max-width: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--el-border-color-light);
  border-radius: 4px;
  padding: 16px;
  background: var(--el-bg-color);
}
.grid-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
  align-items: center;
  flex-shrink: 0;
}
.grid-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.hidden-import-input {
  display: none;
}
.pagination-wrap {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.mtv-data-grid-scroll {
  width: 100%;
  min-width: 0;
  min-height: 0;
  max-width: 100%;
  flex: 1 1 auto;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.mtv-data-grid-inner {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  height: 100%;
}
:deep(.mtv-data-grid.el-table),
:deep(.mtv-data-grid .el-table__inner-wrapper) {
  width: 100% !important;
  max-width: 100%;
  height: 100%;
}
/* When the columns underflow the panel, stretch the scrollbar view + the actual <table> elements to
   fill the width so the grid never renders half-empty. Element Plus' fit mode (table-layout:auto) then
   distributes the slack across columns. The body table sits inside an el-scrollbar whose __view sizes
   to content by default — force that chain (and the header) to 100%. */
:deep(.mtv-data-grid--fit .el-scrollbar__view),
:deep(.mtv-data-grid--fit .el-table__header-wrapper),
:deep(.mtv-data-grid--fit .el-table__body-wrapper),
:deep(.mtv-data-grid--fit .el-table__header),
:deep(.mtv-data-grid--fit .el-table__body) {
  width: 100% !important;
  min-width: 100% !important;
}
.col-header-cell {
  position: static;
  display: flex;
  align-items: center;
  width: 100%;
  min-width: 0;
  min-height: 23px;
  box-sizing: border-box;
  padding-right: 12px;
}
:deep(.mtv-data-grid .el-table__header-wrapper th.mtv-resizable-col-header) {
  position: relative;
  overflow: visible !important;
}
:deep(.mtv-data-grid .el-table__header-wrapper th.mtv-resizable-col-header .cell) {
  position: static !important;
  display: flex !important;
  align-items: center;
  width: 100% !important;
  max-width: none !important;
  overflow: visible !important;
  text-overflow: clip;
}
:deep(.mtv-data-grid .el-table__header-wrapper th.mtv-resizable-col-header:has(.col-resize-handle:hover)),
:deep(.mtv-data-grid .el-table__header-wrapper th.mtv-resizable-col-header:has(.col-resize-handle.is-active)) {
  z-index: 12;
}
:deep(.el-table__row) {
  cursor: pointer;
}

/* Leading marker column: no header label, the icon carries the meaning. */
.mtv-task-col {
  padding-left: 0;
  padding-right: 0;
}

.mtv-task-flag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--el-color-primary);
  cursor: pointer;
  font-size: 15px;

  &:hover { opacity: 0.75; }
}

.mtv-fk-link {
  color: var(--el-color-primary);
  cursor: pointer;

  &:hover { text-decoration: underline; }
}

.mtv-file-cell {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px 10px;
}

.mtv-file-link {
  color: var(--el-color-primary);
  cursor: pointer;

  &:hover { text-decoration: underline; }
}

.import-progress-file {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}

.import-progress-label {
  margin: 0 0 12px;
  font-size: 14px;
  color: var(--el-text-color-primary);
}

.import-result-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin-top: 4px;
}

.import-stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 12px 8px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.import-stat-value {
  font-size: 22px;
  font-weight: 600;
  line-height: 1.2;
}

.import-stat-label {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.import-stat--created .import-stat-value {
  color: var(--el-color-success);
}

.import-stat--updated .import-stat-value {
  color: var(--el-color-primary);
}

.import-stat--skipped .import-stat-value {
  color: var(--el-text-color-regular);
}

.import-stat--errors .import-stat-value {
  color: var(--el-text-color-placeholder);
}

.import-stat--has-errors .import-stat-value {
  color: var(--el-color-danger);
}

.import-error-section {
  margin-top: 16px;
}

.import-error-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-color-danger);
}

.import-error-list {
  margin: 0;
  padding: 0 0 0 18px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}

:deep(.el-result) {
  padding: 12px 0 8px;
}

.mtv-view-option-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  /* Takes the slack so a long table name truncates instead of squeezing the tag beside it. */
  flex: 1;
  min-width: 0;
}
/* el-menu-item is flex by default; the margin keeps the tag off the truncated name. */
.view-list-panel .el-menu-item .el-tag {
  flex-shrink: 0;
  margin-left: 6px;
}
</style>
