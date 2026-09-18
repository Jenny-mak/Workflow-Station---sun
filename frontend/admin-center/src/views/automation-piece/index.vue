<template>
  <div class="page-container">
    <PageHeader :title="t('automationPiece.title')">
      <template #actions>
        <el-button @click="exportGridCsv('automation-pieces')">
          <el-icon><Download /></el-icon>{{ t('common.export') }}
        </el-button>
        <el-button @click="loadPieces">
          <el-icon><Refresh /></el-icon>{{ t('common.refresh') }}
        </el-button>
        <el-upload
          :show-file-list="false"
          :auto-upload="false"
          accept=".tgz,.tar.gz"
          @change="handleImportFile"
        >
          <el-button
            type="primary"
            :loading="importing"
          >
            <el-icon><Upload /></el-icon>{{ t('automationPiece.import') }}
          </el-button>
        </el-upload>
      </template>
    </PageHeader>

    <el-card
      v-loading="loading"
      class="table-card"
    >
      <div class="toolbar">
        <el-input
          v-model="keyword"
          :placeholder="t('automationPiece.searchPlaceholder')"
          clearable
          style="width: 280px"
          @keyup.enter="loadPieces"
          @clear="loadPieces"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button
          type="primary"
          @click="loadPieces"
        >
          {{ t('common.search') }}
        </el-button>
        <span class="piece-count">{{ t('automationPiece.total', { count: pagination.total }) }}</span>
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
              type="expand"
              :width="EXPAND_COL_WIDTH"
            >
              <template #default="{ row }">
                <div
                  
                  class="piece-detail"
                >
                  <p
                    v-if="row.description"
                    class="piece-desc"
                  >
                    {{ row.description }}
                  </p>
                  <div
                    v-if="row.actionNames.length"
                    class="detail-line"
                  >
                    <span class="detail-label">{{ t('automationPiece.actions') }}:</span>
                    <el-tag
                      v-for="a in row.actionNames"
                      :key="a"
                      size="small"
                      class="detail-tag"
                    >{{ a }}</el-tag>
                  </div>
                  <div
                    v-if="row.triggerNames.length"
                    class="detail-line"
                  >
                    <span class="detail-label">{{ t('automationPiece.triggers') }}:</span>
                    <el-tag
                      v-for="tr in row.triggerNames"
                      :key="tr"
                      size="small"
                      type="warning"
                      class="detail-tag"
                    >{{ tr }}</el-tag>
                  </div>
                  <div class="detail-line">
                    <span class="detail-label">{{ t('automationPiece.packageName') }}:</span>
                    <code>{{ row.name }}</code>
                  </div>
                  <div class="detail-line">
                    <span class="detail-label">{{ t('automationPiece.runtime') }}:</span>
                    <el-tag
                      :type="row.hasArchive ? 'success' : 'info'"
                      size="small"
                      effect="plain"
                    >
                      {{ row.hasArchive ? t('automationPiece.runtimeArchive') : t('automationPiece.runtimeBaked') }}
                    </el-tag>
                    <span class="detail-hint">
                      {{ row.hasArchive ? t('automationPiece.runtimeArchiveTip') : t('automationPiece.runtimeBakedTip') }}
                    </span>
                  </div>
                  <div
                    v-if="row.authors.length"
                    class="detail-line"
                  >
                    <span class="detail-label">{{ t('automationPiece.authors') }}:</span>
                    {{ row.authors.join(', ') }}
                  </div>
                </div>
              </template>
            </el-table-column>
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
<code v-if="col.field === 'name'">{{ row.name }}</code>
                <template v-else-if="col.field === 'version'">
                  <el-select
                    v-if="versionOptions(row).length > 1"
                    :model-value="row.version"
                    size="small"
                    class="version-select"
                    @change="(v: string) => pickVersion(row, v)"
                  >
                    <el-option
                      v-for="opt in versionOptions(row)"
                      :key="opt.version"
                      :label="opt.version"
                      :value="opt.version"
                    />
                  </el-select>
                  <span v-else>{{ row.version }}</span>
                </template>
                <el-tag
                  v-else-if="col.field === 'pieceType'"
                  :type="row.pieceType === 'OFFICIAL' ? 'info' : 'success'"
                  size="small"
                >
                  {{ row.pieceType }}
                </el-tag>
                <el-switch
                  v-else-if="col.field === 'disabled'"
                  :model-value="!row.disabled"
                  :loading="togglingKey === row.name"
                  @change="(val: string | number | boolean) => handleToggle(row, val as boolean)"
                />
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
                <div >
                  <el-button
                    link
                    type="primary"
                    size="small"
                    :loading="exportingKey === rowKey(row)"
                    @click="handleExport(row)"
                  >
                    {{ t('automationPiece.export') }}
                  </el-button>
                  <el-button
                    link
                    type="danger"
                    size="small"
                    :loading="deletingKey === rowKey(row)"
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
        @change="loadPieces"
      />
    </el-card>

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
import { Download, Refresh, Search, Upload } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { formatDate } from '@/utils/format'
import { useAutomationPiece } from '@/composables/modules/useAutomationPiece'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'

const { t } = useI18n()

const {
  loading,
  keyword,
  exportingKey,
  togglingKey,
  deletingKey,
  importing,
  handleExport,
  handleImportFile,
  handleToggle,
  handleDelete,
  loadPieces,
  rowKey,
  versionOptions,
  pickVersion,
  EXPAND_COL_WIDTH,
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
} = useAutomationPiece()

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  void loadPieces()
}

function onClearSort() {
  clearSort()
  void loadPieces()
}


function onClearFilter(field: string) {
  clearFilter(field)
  void loadPieces()
}

function onFilterApply(filter: ListColumnFilter) {
  applyFilter(filter)
  void loadPieces()
}

function onFilterClear() {
  onClearFilter(filterDialog.field)
}

onMounted(() => { void loadPieces() })
</script>

<style scoped>
.version-select {
  width: 100px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.piece-count {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.piece-detail {
  padding: 8px 48px;
}

.piece-desc {
  margin: 0 0 8px;
  color: var(--el-text-color-secondary);
}

.detail-line {
  margin-bottom: 6px;
  line-height: 24px;
}

.detail-label {
  color: var(--el-text-color-secondary);
  margin-right: 8px;
}

.detail-tag {
  margin-right: 6px;
}

.detail-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-left: 8px;
}
</style>
