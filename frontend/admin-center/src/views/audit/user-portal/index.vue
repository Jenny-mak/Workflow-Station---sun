<template>
  <div class="page-container audit-list-page">
    <PageHeader :title="t('upAudit.title')" />

    <!-- Filter Area -->
    <div class="filter-card">
      <el-form :inline="true" :model="query" class="search-form">
        <el-form-item :label="t('audit.dateRange')">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            :start-placeholder="t('common.startDate')"
            :end-placeholder="t('common.endDate')"
            value-format="YYYY-MM-DD"
            :shortcuts="dateShortcuts"
            style="width: 260px"
          />
        </el-form-item>
        <el-form-item :label="t('audit.operator')">
          <el-input
            v-model="query.username"
            clearable
            :placeholder="t('audit.usernamePlaceholder')"
            style="width: 130px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item :label="t('upAudit.functionUnit')">
          <el-select
            v-model="query.functionUnitCode"
            clearable
            filterable
            :placeholder="t('common.selectPlaceholder')"
            style="width: 220px"
          >
            <el-option
              v-for="item in functionUnitCodes"
              :key="item.code"
              :label="item.name || item.code || '-'"
              :value="item.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('upAudit.changeType')">
          <el-select
            v-model="query.changeType"
            clearable
            :placeholder="t('common.selectPlaceholder')"
            style="width: 170px"
          >
            <el-option
              :label="t('upAudit.actionFIELD_UPDATE')"
              value="FIELD_UPDATE"
            />
            <el-option
              :label="t('upAudit.actionSUB_TABLE_ROW_ADD')"
              value="SUB_TABLE_ROW_ADD"
            />
            <el-option
              :label="t('upAudit.actionSUB_TABLE_ROW_UPDATE')"
              value="SUB_TABLE_ROW_UPDATE"
            />
            <el-option
              :label="t('upAudit.actionSUB_TABLE_ROW_DELETE')"
              value="SUB_TABLE_ROW_DELETE"
            />
            <el-option
              :label="t('upAudit.actionPROCESS_INITIATION')"
              value="PROCESS_INITIATION"
            />
            <el-option
              :label="t('upAudit.actionRECORD_NOTE_ADD')"
              value="RECORD_NOTE_ADD"
            />
            <el-option
              :label="t('upAudit.actionRECORD_NOTE_UPDATE')"
              value="RECORD_NOTE_UPDATE"
            />
            <el-option
              :label="t('upAudit.actionRECORD_NOTE_DELETE')"
              value="RECORD_NOTE_DELETE"
            />
            <el-option
              :label="t('upAudit.actionCLAIM')"
              value="CLAIM"
            />
            <el-option
              :label="t('upAudit.actionUNCLAIM')"
              value="UNCLAIM"
            />
            <el-option
              :label="t('upAudit.actionFORCE_UNCLAIM')"
              value="FORCE_UNCLAIM"
            />
            <el-option
              :label="t('upAudit.actionREASSIGN')"
              value="REASSIGN"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('upAudit.processInstanceId')">
          <el-input
            v-model="query.processInstanceId"
            clearable
            :placeholder="t('upAudit.processInstanceSearchPlaceholder')"
            style="width: 220px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleSearch">
            <el-icon><Search /></el-icon>{{ t('common.search') }}
          </el-button>
          <el-button @click="handleReset">
            {{ t('common.reset') }}
          </el-button>
          <el-button
            type="primary"
            :loading="exporting"
            style="margin-left: 8px"
            @click="openExportDialog"
          >
            <el-icon><Download /></el-icon>{{ t('audit.batchExport') }}
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <div
      ref="tableCardRef"
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
            ref="tableRef"
            :data="displayRows"
            stripe
            size="small"
            highlight-current-row
            :fit="false"
            table-layout="fixed"
            style="width: 100%"
            class="list-data-grid"
            :class="{ 'list-data-grid--fit': gridFits }"
            scrollbar-always-on
            :height="tableHeight"
          >
            <template #empty>
              <el-empty :description="t('upAudit.emptyText')">
                <el-button
                  type="primary"
                  @click="handleReset"
                >
                  {{ t('audit.resetFilter') }}
                </el-button>
              </el-empty>
            </template>
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
                  v-if="col.field === 'changeType'"
                  :type="changeTypeTag(row.changeType)"
                  size="small"
                >
                  {{ changeTypeText(t, row.changeType) }}
                </el-tag>
                <template v-else-if="col.field === 'functionUnitCode'">
                  {{ row.functionUnitName || row.functionUnitCode || '-' }}
                </template>
                <template v-else-if="col.field === 'processInstanceId'">
                  <span :title="row.processInstanceId">
                    {{ row.processTitle || row.processInstanceId || '-' }}
                  </span>
                </template>
                <template v-else-if="col.field === 'stageId'">
                  {{ row.stageName || row.stageId || '-' }}
                </template>
                <template v-else-if="col.field === 'subTableName'">
                  {{ row.subTableDisplayName || row.subTableName || '-' }}
                </template>
                <template v-else-if="col.field === 'fieldName'">
                  {{ row.fieldLabel || row.fieldName || '-' }}
                </template>
                <template v-else-if="col.field === 'oldValue'">
                  {{ truncateValue(row.oldValue, 50) }}
                </template>
                <template v-else-if="col.field === 'newValue'">
                  {{ truncateValue(row.newValue, 50) }}
                </template>
                <template v-else-if="col.field === 'userName'">
                  {{ row.userName || row.userId || '-' }}
                </template>
                <span
                  v-else-if="col.field === 'timestamp'"
                  style="white-space: nowrap"
                >{{ formatTimestamp(row.timestamp) }}</span>
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
              <template #default="{ row }">
                <el-button
                  
                  link
                  type="primary"
                  @click="showDetail(row)"
                >
                  {{ t('common.view') }}
                </el-button>
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
        @change="handleSearch"
      />
    </div>

    <!-- Detail Dialog -->
    <UserPortalAuditDetailDialog
      v-model:visible="detailDialogVisible"
      :record="currentRecord"
    />

    <!-- Export Dialog -->
    <AuditExportDialog
      v-model="exportDialogVisible"
      :export-fields="ALL_EXPORT_FIELDS"
      :total="total"
      :exporting="exporting"
      @export="(format, fields) => doExport(format, fields)"
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
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Search, Download } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import AuditExportDialog from '@/views/audit/components/AuditExportDialog.vue'
import UserPortalAuditDetailDialog from './components/UserPortalAuditDetailDialog.vue'
import { useUserPortalAudit } from '@/composables/modules/useUserPortalAudit'
import { searchListFilterUsers } from '@/composables/list/searchListFilterUsers'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'
import { useListTableFitHeight } from '@/composables/list/useListTableFitHeight'

const { t } = useI18n()

const {
  loading, exporting,
  total,
  detailDialogVisible, currentRecord,
  dateRange,
  query, functionUnitCodes,
  exportDialogVisible,
  ALL_EXPORT_FIELDS,
  dateShortcuts,
  changeTypeTag, changeTypeText, formatTimestamp, truncateValue,
  handleSearch, handleReset,
  showDetail,
  openExportDialog, doExport,
  ACTIONS_COL_WIDTH,
  displayColumns,
  displayRows,
  columnFilters,
  sort,
  filterDialog,
  pagination,
  activeFilterColumn,
  activeFilter,
  gridScrollRef,
  gridFits,
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
} = useUserPortalAudit()

const tableCardRef = ref<HTMLElement | null>(null)
const { tableHeight } = useListTableFitHeight(tableCardRef, gridScrollRef, () => displayRows.value.length)

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  void handleSearch()
}
function onClearSort() {
  clearSort()
  void handleSearch()
}
function onClearFilter(field: string) {
  clearFilter(field)
  void handleSearch()
}
function onFilterApply(filter: ListColumnFilter) {
  applyFilter(filter)
  void handleSearch()
}
function onFilterClear() {
  onClearFilter(filterDialog.field)
}
</script>

<style scoped>
.filter-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 16px 16px 0;
  margin-bottom: 12px;
}

</style>
