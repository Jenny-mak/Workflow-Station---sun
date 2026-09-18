<!--
  Automation Runs — flow 执行记录（只读运维视图）。

  从 Developer Workstation 的 Automation → Run History 迁来：DW 只在 dev 存在（不进 K8S
  部署集），而"某次自动化为什么失败"是生产运维问题，与 piece 目录 / flow 迁移同属 Admin Center。
  可见集与 AP 自己的 Runs 页一致（PRODUCTION 且未归档），行点击展开该次运行的完整 JSON。
-->
<template>
  <div class="page-container">
    <PageHeader :title="t('automationRun.title')">
      <template #actions>
        <el-button @click="exportGridCsv('automation-runs')">
          <el-icon><Download /></el-icon>{{ t('common.export') }}
        </el-button>
        <el-button @click="loadRuns">
          <el-icon><Refresh /></el-icon>{{ t('common.refresh') }}
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
          :placeholder="t('automationRun.searchPlaceholder')"
          clearable
          style="width: 280px"
          @keyup.enter="loadRuns"
          @clear="loadRuns"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button
          type="primary"
          @click="loadRuns"
        >
          {{ t('common.search') }}
        </el-button>
        <span class="run-count">{{ t('automationRun.total', { count: pagination.total }) }}</span>
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
            class="list-data-grid run-grid"
            :class="{ 'list-data-grid--fit': gridFits && !isCompact }"
            scrollbar-always-on
            :height="gridTableHeight || '100%'"
            @row-click="onRowClick"
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
              :show-overflow-tooltip="!(isCompact && col.field === 'flowDisplayName')"
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
                  v-if="col.field === 'flowDisplayName'"
                  class="run-flow"
                >
                  <span class="run-flow__title">{{ row.flowDisplayName }}</span>
                  <span
                    v-if="isCompact"
                    class="run-flow__meta"
                  >{{ compactMeta(row) }}</span>
                </div>
                <el-tag
                  v-else-if="col.field === 'status'"
                  :type="statusTone(row.status).type"
                  size="small"
                  :effect="statusTone(row.status).effect"
                  disable-transitions
                >
                  {{ statusLabel(row.status) }}
                </el-tag>
                <span v-else-if="col.field === 'startTime'">{{ row.startTime ? formatDate(row.startTime) : '—' }}</span>
                <span v-else-if="col.field === 'durationMs'">{{ formatDuration(row.durationMs) }}</span>
                <span
                  v-else-if="col.field === 'failedStepName'"
                  :class="{ 'run-failed-step': !!row.failedStepName }"
                  :title="row.failedStepMessage || ''"
                >{{ row.failedStepName || '—' }}</span>
                <template v-else>
                  {{ row[col.field] ?? '—' }}
                </template>
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
        @change="loadRuns"
      />
    </el-card>

    <el-drawer
      v-model="detailVisible"
      :title="t('automationRun.detailTitle')"
      size="46%"
    >
      <div
        v-loading="loadingDetail"
        class="run-detail"
      >
        <el-descriptions
          v-if="detailRun"
          :column="1"
          size="small"
          border
          class="run-detail__summary"
        >
          <el-descriptions-item :label="t('automationRun.flow')">
            {{ detailRun.flowDisplayName }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('automationRun.status')">
            <el-tag
              :type="statusTone(detailRun.status).type"
              size="small"
              :effect="statusTone(detailRun.status).effect"
              disable-transitions
            >
              {{ statusLabel(detailRun.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('automationRun.started')">
            {{ detailRun.startTime ? formatDate(detailRun.startTime) : '—' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('automationRun.duration')">
            {{ formatDuration(detailRun.durationMs) }}
          </el-descriptions-item>
          <el-descriptions-item
            v-if="detailRun.failedStepName"
            :label="t('automationRun.failedStep')"
          >
            <div class="run-detail__failure">
              <span>{{ detailRun.failedStepName }}</span>
              <span
                v-if="detailRun.failedStepMessage"
                class="run-detail__failure-message"
              >{{ detailRun.failedStepMessage }}</span>
            </div>
          </el-descriptions-item>
        </el-descriptions>

        <pre
          v-if="detailJson"
          class="run-detail__json"
        >{{ detailJson }}</pre>
        <el-empty
          v-else-if="!loadingDetail"
          :description="t('automationRun.detailUnavailable')"
        />
      </div>
    </el-drawer>

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
import { Download, Refresh, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { formatDate } from '@/utils/format'
import { useAutomationFlowRun } from '@/composables/modules/useAutomationFlowRun'
import type { AutomationFlowRunSummary } from '@/api/automationFlowRun'
import type { TableColumnCtx } from 'element-plus'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'

const { t } = useI18n()

const {
  loading,
  keyword,
  isCompact,
  tableColumns,
  statusTone,
  statusLabel,
  formatDuration,
  compactMeta,
  loadRuns,
  detailVisible,
  loadingDetail,
  detailRun,
  detailJson,
  openRunDetail,
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
} = useAutomationFlowRun()

function onRowClick(row: AutomationFlowRunSummary, column: TableColumnCtx<AutomationFlowRunSummary>) {
  if (column.type !== 'selection') void openRunDetail(row)
}

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  void loadRuns()
}

function onClearSort() {
  clearSort()
  void loadRuns()
}

function onClearFilter(field: string) {
  clearFilter(field)
  void loadRuns()
}

function onFilterApply(filter: ListColumnFilter) {
  applyFilter(filter)
  void loadRuns()
}

function onFilterClear() {
  onClearFilter(filterDialog.field)
}

onMounted(() => { void loadRuns() })
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.run-count {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.run-grid :deep(.el-table__row) {
  cursor: pointer;
}

.run-flow {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.run-flow__title,
.run-flow__meta {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.run-flow__meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.run-failed-step {
  color: var(--el-color-danger);
}

.table-card :deep(.el-scrollbar__bar.is-horizontal) {
  opacity: 1;
  height: 8px;
}

.run-detail {
  min-height: 200px;
}

.run-detail__summary {
  margin-bottom: 14px;
}

.run-detail__failure {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.run-detail__failure-message {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  word-break: break-word;
}

.run-detail__json {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
