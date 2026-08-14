<script setup lang="ts">
import { Expand, Fold } from '@element-plus/icons-vue'
import PortalListColumnHeader from '@/components/portal-list/PortalListColumnHeader.vue'
import PortalListFilterDialog from '@/components/portal-list/PortalListFilterDialog.vue'
import { useMainTableViewPage } from '@/composables/mainTableView/useMainTableViewPage'

const {
  t, Search, Download, Refresh, dataLoading, functionUnits, selectedViewId, searchKeyword,
  gridColumns, allRows, dataTotal, currentPage, pageSize, gridRuntime, filterDialogVisible, filterDialogField,
  tableRef, selectedTableRows,
  importProgressVisible, importProgressPercent, importProgressPhase, importProgressFileName,
  importResultVisible, importResult, importProgressLabel, importResultStatus, importResultHeadline,
  selectedFuCode, selectedViewMeta, showExportButton, selectedFu, displayColumns, groupedViews,
  viewListCollapsed, viewSearchKeyword, filteredGroupedViews, handleSelectView,
  MTV_SELECTION_COL_WIDTH, gridTotalColumnWidth, gridInnerStyle, gridScrollRef, gridFits, gridTableKey,
  processedRows, groupedRows, pagedRows, displayTotal,
  handleSearch, handlePageChange, handleSizeChange, formatCell, isRowSelectable, getRowKey, onSelectionChange, openRow, columnIndex,
  isFkLinkCell, openFkTarget, isLookupLinkCell, openLookupTarget, isFileLinkCell, fileLinksOf, downloadFile,
  handleColumnCommand, applyColumnFilter, clearColumnFilter, handleColumnResize, handleColumnResizeEnd,
  handleExport, mtvHeaderCellClassName, rowClassName, spanMethod,
  loadData, columnWidth, isDateLikeColumn, isGroupHeaderRow,
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
          :default-active="selectedViewId ? String(selectedViewId) : ''"
          unique-opened
          @select="handleSelectView"
        >
          <el-menu-item-group
            v-for="group in filteredGroupedViews"
            :key="group.tableId ?? group.label"
            :title="group.label"
          >
            <el-menu-item
              v-for="v in group.views"
              :key="v.id"
              :index="String(v.id)"
            >
              <span class="mtv-view-option">
                <span class="mtv-view-option-name">{{ v.viewName }}</span>
                <el-tag
                  v-if="v.isDefault"
                  size="small"
                  type="info"
                  effect="plain"
                >
                  {{ t('mainTableView.defaultTag') }}
                </el-tag>
              </span>
            </el-menu-item>
          </el-menu-item-group>
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
            <span
              v-if="dataTotal > allRows.length"
              class="grid-hint"
            >
              {{ t('mainTableView.rowsTruncated', { shown: allRows.length, total: dataTotal }) }}
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
              :fit="gridFits"
              :table-layout="gridFits ? 'auto' : 'fixed'"
              style="width: 100%;"
              class="mtv-data-grid"
              :class="{ 'mtv-data-grid--fit': gridFits }"
              :header-cell-class-name="mtvHeaderCellClassName"
              :span-method="spanMethod"
              :row-class-name="rowClassName"
              @row-click="(row: GridDisplayRow) => openRow(row)"
              @selection-change="onSelectionChange"
            >
          <el-table-column
            type="selection"
            width="48"
            :selectable="isRowSelectable"
            reserve-selection
          />
          <el-table-column
            v-for="col in displayColumns"
            :key="col.fieldName"
            :prop="col.fieldName"
            :width="gridFits ? undefined : columnWidth(col, gridRuntime)"
            :min-width="gridFits ? columnWidth(col, gridRuntime) : undefined"
            show-overflow-tooltip
          >
            <template #header>
              <PortalListColumnHeader
                :label="col.displayLabel"
                :width="columnWidth(col, gridRuntime)"
                :date-like="isDateLikeColumn(col)"
                :can-move-left="columnIndex(col.fieldName) > 0"
                :can-move-right="columnIndex(col.fieldName) < gridRuntime.columnOrder.length - 1"
                :is-grouped="gridRuntime.groupBy === col.fieldName"
                :has-filter="!!gridRuntime.filters[col.fieldName]"
                :sort-direction="gridRuntime.sort?.fieldName === col.fieldName ? gridRuntime.sort.direction : null"
                @sort-asc="handleColumnCommand(col, 'sortAsc')"
                @sort-desc="handleColumnCommand(col, 'sortDesc')"
                @group-by="handleColumnCommand(col, 'groupBy')"
                @filter="handleColumnCommand(col, 'filterBy')"
                @clear-filter="handleColumnCommand(col, 'clearFilter')"
                @move-left="handleColumnCommand(col, 'moveLeft')"
                @move-right="handleColumnCommand(col, 'moveRight')"
                @resize="(width) => handleColumnResize(col.fieldName, width)"
                @resize-end="handleColumnResizeEnd"
              />
            </template>
            <template #default="{ row }">
              <template v-if="isGroupHeaderRow(row)">
                <div class="group-header-cell">
                  <strong>{{ row._groupLabel }}</strong>
                  <span class="group-count">({{ row._groupCount }})</span>
                </div>
              </template>
              <template v-else-if="isFileLinkCell(col, row)">
                <span class="mtv-file-cell">
                  <a
                    v-for="(file, fi) in fileLinksOf(col, row)"
                    :key="fi"
                    class="mtv-file-link"
                    :title="t('mainTableView.downloadFile')"
                    @click.stop="downloadFile(file)"
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
          <el-pagination
            v-model:current-page="currentPage"
            background
            :page-size="pageSize"
            :total="displayTotal"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="handlePageChange"
            @size-change="handleSizeChange"
          />
        </div>
        </template>

        <el-empty
          v-else
          :description="t('mainTableView.selectFuAndView')"
        />
      </div>
    </div>

    <PortalListFilterDialog
      v-model="filterDialogVisible"
      :title="filterDialogField
        ? `${t('mainTableView.colFilterBy')}: ${filterDialogField.displayLabel}`
        : t('mainTableView.colFilterBy')"
      :initial="filterDialogField
        ? (gridRuntime.filters[filterDialogField.fieldName] ?? null)
        : null"
      @apply="applyColumnFilter"
      @clear="clearColumnFilter"
    />

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

<style scoped lang="scss">
.page-container {
  padding: 16px 20px;
  height: 100%;
  min-width: 0;
  max-width: 100%;
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
  align-items: flex-start;
  min-width: 0;
  max-width: 100%;
}
.view-list-panel {
  width: 240px;
  flex-shrink: 0;
  align-self: stretch;
  min-height: calc(100vh - 160px);
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
  min-height: calc(100vh - 160px);
  min-width: 0;
  max-width: 100%;
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
  max-width: 100%;
  overflow-x: auto;
  overflow-y: visible;
}
.mtv-data-grid-inner {
  display: block;
}
:deep(.mtv-data-grid .el-table__body-wrapper),
:deep(.mtv-data-grid .el-table__header-wrapper) {
  overflow-x: visible !important;
}
:deep(.mtv-data-grid .el-table__inner-wrapper) {
  width: 100% !important;
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
:deep(.el-table__row:not(.group-header-row)) {
  cursor: pointer;
}
:deep(.group-header-row) {
  background: var(--el-fill-color-light) !important;
  cursor: default;
  font-weight: 600;
}
.group-header-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}
.group-count {
  color: var(--el-text-color-secondary);
  font-weight: normal;
  font-size: 12px;
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

/* Left-nav view row: name on the left, Default tag pushed to the right. */
.mtv-view-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
}
.mtv-view-option-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Per-table group header in the left nav — uppercased + muted so grouping reads clearly. */
.view-list-panel :deep(.el-menu-item-group__title) {
  padding: 8px 12px 4px;
  font-size: 12px;
  font-weight: 700;
  color: var(--el-text-color-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
</style>
