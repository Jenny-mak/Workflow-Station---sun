<template>
  <div
    v-loading="loading"
    class="portal-card list-tab-card"
  >
    <div class="list-grid-toolbar">
      <el-input
        v-model="ruleKeyword"
        :placeholder="t('common.search')"
        clearable
        style="width: 240px;"
        @keydown.enter.prevent="runSearch"
        @clear="runSearch"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-button
        type="primary"
        :icon="Download"
        @click="exportGridCsv('delegation-rules')"
      >
        {{ t('common.export') }}
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
          style="width: 100%;"
          class="list-data-grid"
          :class="{ 'list-data-grid--fit': gridFits }"
          scrollbar-always-on
          :height="gridTableHeight || '100%'"
          @selection-change="handleGridSelectionChange"
        >
          <template #empty>
            <div
              v-if="loading"
              class="table-empty-loading"
            >
              <el-icon class="table-empty-loading__icon is-loading">
                <Loading />
              </el-icon>
              <span>{{ t('common.loading') }}</span>
            </div>
            <span v-else>{{ t('delegation.noRules') }}</span>
          </template>
          <el-table-column
            type="selection"
            :width="selectionColumnWidth"
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
                v-if="col.field === 'status'"
                :type="getStatusType(row.status)"
                size="small"
              >
                {{ statusLabel(row.status) }}
              </el-tag>
              <span v-else-if="col.field === 'delegationType'">
                {{ typeLabel(row.delegationType) }}
              </span>
              <span
                v-else-if="col.field === 'startTime' || col.field === 'endTime' || col.field === 'createdAt'"
                style="white-space: nowrap;"
              >{{ formatDate(row[col.field as keyof DelegationRule] as string | undefined) }}</span>
              <template v-else>
                {{ row[col.field as keyof DelegationRule] || '-' }}
              </template>
            </template>
          </el-table-column>
          <el-table-column
            :label="t('common.actions')"
            :width="ACTIONS_COL_WIDTH"
            :min-width="ACTIONS_COL_WIDTH"
            fixed="right"
          >
            <template #default="{ row }">
              <div class="row-actions">
                <el-button
                  v-if="row.status === 'ACTIVE'"
                  size="small"
                  @click="handleSuspend(row)"
                >
                  {{ t('delegation.suspend') }}
                </el-button>
                <el-button
                  v-if="row.status === 'SUSPENDED'"
                  size="small"
                  @click="handleResume(row)"
                >
                  {{ t('delegation.resume') }}
                </el-button>
                <el-button
                  type="danger"
                  size="small"
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
      @change="load"
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
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Loading, Search } from '@element-plus/icons-vue'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'
import {
  queryDelegationRules,
  suspendDelegationRule,
  resumeDelegationRule,
  deleteDelegationRule,
  type DelegationRule,
} from '@/api/delegation'
import { usePortalListGrid } from '@/composables/list/usePortalListGrid'
import { searchListFilterUsers } from '@/composables/list/searchListFilterUsers'
import { formatDate } from '@/utils/dateFormat'


const ACTIONS_COL_WIDTH = 200

const { t } = useI18n()
const loading = ref(true)
const ruleKeyword = ref('')

const {
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
  gridTableHeight,
  gridInnerStyle,
  selectionColumnWidth,
  widthOf,
  setWidth,
  persistWidths,
  beginQuery,
  isCurrentQuery,
  applyPage,
  handleGridSelectionChange,
  setQuickFilter,
  exportGridCsv,
  buildQuery,
  moveColumn,
  openFilter,
  applyFilter,
  clearFilter,
  applySort,
  clearSort,
} = usePortalListGrid<DelegationRule>({
  storageKey: 'portal-list-layout:delegation-rules',
  extraWidth: ACTIONS_COL_WIDTH,
  selection: true,
})

function getStatusType(status: string): 'success' | 'info' | 'warning' {
  const map: Record<string, 'success' | 'info' | 'warning'> = {
    ACTIVE: 'success',
    INACTIVE: 'info',
    EXPIRED: 'info',
    SUSPENDED: 'warning',
  }
  return map[status] || 'info'
}

function statusLabel(status: string | undefined): string {
  if (!status) return '-'
  const key = `delegation.${status.toLowerCase()}`
  const translated = t(key)
  return translated === key ? status : translated
}

function typeLabel(type: string | undefined): string {
  if (!type) return '-'
  const key = `delegation.${type.toLowerCase()}`
  const translated = t(key)
  return translated === key ? type : translated
}

async function load() {
  const seq = beginQuery()
  loading.value = true
  try {
    const res = await queryDelegationRules(buildQuery())
    if (!isCurrentQuery(seq)) return
    applyPage(res.data, 'delegations/query response is missing its column declaration')
  } catch (error) {
    if (!isCurrentQuery(seq)) return
    if (!(error as { response?: unknown })?.response) {
      ElMessage.error(error instanceof Error ? error.message : t('delegation.loadFailed'))
    }
  } finally {
    if (isCurrentQuery(seq)) loading.value = false
  }
}

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  load()
}

function onClearSort() {
  clearSort()
  load()
}


function onClearFilter(field: string) {
  clearFilter(field)
  load()
}

function onFilterApply(filter: ListColumnFilter) {
  applyFilter(filter)
  load()
}

function onFilterClear() {
  onClearFilter(filterDialog.field)
}

function runSearch() {
  setQuickFilter('reason', ruleKeyword.value)
  void load()
}

async function handleSuspend(row: DelegationRule) {
  try {
    await suspendDelegationRule(row.id)
    ElMessage.success(t('delegation.suspendSuccess'))
    await load()
  } catch {
    // interceptor
  }
}

async function handleResume(row: DelegationRule) {
  try {
    await resumeDelegationRule(row.id)
    ElMessage.success(t('delegation.resumeSuccess'))
    await load()
  } catch {
    // interceptor
  }
}

async function handleDelete(row: DelegationRule) {
  await ElMessageBox.confirm(t('delegation.deleteConfirm'), t('common.info'), { type: 'warning' })
  try {
    await deleteDelegationRule(row.id)
    ElMessage.success(t('delegation.deleteSuccess'))
    await load()
  } catch {
    // interceptor
  }
}

onMounted(() => {
  load()
})

defineExpose({ reload: load })
</script>

<style lang="scss" scoped>
@import '@/styles/listDataGrid.scss';

.table-empty-loading {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
  padding: 24px 0;

  &__icon {
    font-size: 18px;
  }
}

.row-actions {
  white-space: nowrap;
  display: flex;
  gap: 4px;
  align-items: center;
  flex-wrap: nowrap;
}

.list-tab-card {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
