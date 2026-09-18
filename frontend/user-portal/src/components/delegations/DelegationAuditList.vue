<template>
  <div
    v-loading="loading"
    class="portal-card list-tab-card"
  >
    <div class="list-grid-toolbar">
      <el-input
        v-model="auditKeyword"
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
        @click="exportGridCsv('delegation-audit')"
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
            <span v-else>{{ t('delegation.noAudit') }}</span>
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
              <span
                v-if="col.field === 'createdAt'"
                style="white-space: nowrap;"
              >{{ formatDate(row.createdAt) }}</span>
              <template v-else>
                {{ row[col.field as keyof DelegationAudit] || '-' }}
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
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Download, Loading, Search } from '@element-plus/icons-vue'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'
import {
  queryDelegationAudit,
  type DelegationAudit,
} from '@/api/delegation'
import { usePortalListGrid } from '@/composables/list/usePortalListGrid'
import { searchListFilterUsers } from '@/composables/list/searchListFilterUsers'
import { formatDate } from '@/utils/dateFormat'


const { t } = useI18n()
const loading = ref(false)
const loaded = ref(false)
const auditKeyword = ref('')

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
} = usePortalListGrid<DelegationAudit>({
  storageKey: 'portal-list-layout:delegation-audit',
  selection: true,
})

async function load() {
  const seq = beginQuery()
  loading.value = true
  try {
    const res = await queryDelegationAudit(buildQuery())
    if (!isCurrentQuery(seq)) return
    applyPage(res.data, 'delegations/audit/query response is missing its column declaration')
    loaded.value = true
  } catch (error) {
    if (!isCurrentQuery(seq)) return
    if (!(error as { response?: unknown })?.response) {
      ElMessage.error(error instanceof Error ? error.message : t('delegation.loadFailed'))
    }
  } finally {
    if (isCurrentQuery(seq)) loading.value = false
  }
}

function ensureLoaded() {
  if (!loaded.value) {
    void load()
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
  setQuickFilter('operationType', auditKeyword.value)
  void load()
}

defineExpose({ ensureLoaded, reload: load })
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

.list-tab-card {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
