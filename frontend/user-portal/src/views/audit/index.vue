<template>
  <div class="audit-page">
    <div class="page-header">
      <h1>{{ t('audit.title') }}</h1>
      <span
        v-if="functionUnitName"
        class="page-header__subtitle"
      >{{ functionUnitName }}</span>
    </div>

    <div
      v-loading="loading"
      class="portal-card"
    >
      <el-alert
        v-if="forbidden"
        type="error"
        :title="t('audit.noAccess')"
        :description="t('audit.noAccessHint')"
        :closable="false"
        show-icon
      />

      <template v-else>
        <el-tabs
          v-model="activeTab"
          @tab-change="handleTabChange"
        >
          <el-tab-pane
            :label="t('common.all')"
            name="all"
          />
          <el-tab-pane
            :label="t('application.running')"
            name="RUNNING"
          />
          <el-tab-pane
            :label="t('application.completed')"
            name="COMPLETED"
          />
          <el-tab-pane
            :label="t('application.withdrawn')"
            name="WITHDRAWN"
          />
          <el-tab-pane
            :label="t('application.rejected')"
            name="REJECTED"
          />
        </el-tabs>

        <div class="list-grid-toolbar">
          <el-input
            v-model="searchKeyword"
            :placeholder="t('common.search')"
            clearable
            data-test="audit-search"
            style="width: 240px;"
            @keydown.enter.prevent="handleSearch"
            @clear="handleSearch"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-button
            type="primary"
            :icon="Download"
            @click="exportGridCsv('audit-requests')"
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
                <span v-else>{{ t('audit.noRequests') }}</span>
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
                  <el-link
                    v-if="col.field === 'requestId'"
                    type="primary"
                    @click="viewDetail(row)"
                  >
                    {{ row.requestId || '-' }}
                  </el-link>
                  <template v-else-if="col.field === 'businessKey'">
                    {{ row.businessKey || row.processDefinitionName }}
                  </template>
                  <template v-else-if="col.field === 'startUserName'">
                    {{ row.startUserName || row.startUserId || '-' }}
                  </template>
                  <span
                    v-else-if="col.field === 'startTime'"
                    style="white-space: nowrap;"
                  >{{ formatDate(row.startTime) }}</span>
                  <el-tag
                    v-else-if="col.field === 'status'"
                    :type="getStatusType(row.status)"
                    size="small"
                    effect="light"
                  >
                    {{ getStatusLabel(row.status) }}
                  </el-tag>
                  <template v-else>
                    {{ row[col.field as keyof ProcessInstance] || '-' }}
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
          @change="loadApplications"
        />
      </template>
    </div>

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
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Download, Loading, Search } from '@element-plus/icons-vue'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'
import { formatDate } from '@/utils/dateFormat'
import { processApi, type AuditFunctionUnit, type ProcessInstance } from '@/api/process'
import { usePortalListGrid } from '@/composables/list/usePortalListGrid'
import { searchListFilterUsers } from '@/composables/list/searchListFilterUsers'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const activeTab = ref('all')
const searchKeyword = ref('')
const loading = ref(true)
/**
 * The router guard cannot express a per-function-unit grant, so access is only
 * known once the list call answers. Until then the page shows its loading state
 * rather than an empty table that looks like "no requests".
 */
const forbidden = ref(false)
const auditFunctionUnits = ref<AuditFunctionUnit[]>([])

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
  exportGridCsv,
  buildQuery,
  moveColumn,
  resetPage,
  openFilter,
  applyFilter,
  clearFilter,
  applySort,
  clearSort,
} = usePortalListGrid<ProcessInstance>({
  storageKey: 'portal-list-layout:fu-applications',
  selection: true,
})

const functionUnitCode = computed(() => String(route.params.functionUnitCode || ''))
const functionUnitName = computed(
  () => auditFunctionUnits.value.find(fu => fu.functionUnitCode === functionUnitCode.value)?.functionUnitName || '',
)

const getStatusType = (status: string): 'success' | 'warning' | 'info' | 'danger' | 'primary' => {
  const map: Record<string, 'success' | 'warning' | 'info' | 'danger' | 'primary'> = {
    RUNNING: 'warning',
    COMPLETED: 'success',
    WITHDRAWN: 'info',
    REJECTED: 'danger',
  }
  return map[status] || 'info'
}

const getStatusLabel = (status: string) => {
  const map: Record<string, string> = {
    RUNNING: t('application.running'),
    COMPLETED: t('application.completed'),
    WITHDRAWN: t('application.withdrawn'),
    REJECTED: t('application.rejected'),
  }
  return map[status] || status
}

const loadFunctionUnits = async () => {
  try {
    const res = await processApi.getAuditFunctionUnits()
    auditFunctionUnits.value = res.data || []
  } catch {
    auditFunctionUnits.value = []
  }
}

const loadApplications = async () => {
  if (!functionUnitCode.value) {
    loading.value = false
    return
  }
  const seq = beginQuery()
  loading.value = true
  forbidden.value = false
  try {
    const status = activeTab.value === 'all' ? undefined : activeTab.value
    const keyword = searchKeyword.value.trim()
    const response = await processApi.queryFunctionUnitApplications(
      functionUnitCode.value,
      { ...buildQuery(), status, ...(keyword ? { keyword } : {}) },
    )
    if (!isCurrentQuery(seq)) return
    applyPage(response.data, 'fu-applications/query response is missing its column declaration')
  } catch (e: unknown) {
    if (!isCurrentQuery(seq)) return
    const msg = e instanceof Error ? e.message : ''
    // Interceptor rejects HTTP 200 + success:false as Error(message), dropping status.
    if (msg.includes('process_detail_access_denied') || /access denied/i.test(msg)) {
      forbidden.value = true
    }
  } finally {
    if (isCurrentQuery(seq)) loading.value = false
  }
}

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  loadApplications()
}

function onClearSort() {
  clearSort()
  loadApplications()
}

function onClearFilter(field: string) {
  clearFilter(field)
  loadApplications()
}

function onFilterApply(filter: ListColumnFilter) {
  applyFilter(filter)
  loadApplications()
}

function onFilterClear() {
  onClearFilter(filterDialog.field)
}

const handleTabChange = () => {
  resetPage()
  loadApplications()
}

/** A new keyword changes the result set, so the old page number no longer addresses anything. */
function handleSearch() {
  resetPage()
  loadApplications()
}

/** Reviewers read a request through the same detail page its initiator sees;
 *  `from=audit` lets the shared detail page's breadcrumb say "All Requests" instead
 *  of always defaulting to "My Requests". */
const viewDetail = (row: ProcessInstance) => {
  if (!row.id) return
  router.push(`/applications/${row.id}?from=audit`)
}

watch(functionUnitCode, () => {
  resetPage()
  activeTab.value = 'all'
  searchKeyword.value = ''
  loadApplications()
})

onMounted(() => {
  void loadFunctionUnits()
  void loadApplications()
})
</script>

<style lang="scss">
@import '@/styles/listDataGrid.scss';
</style>

<style scoped lang="scss">
.audit-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  padding: 0;

  :deep(.portal-card) {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
}

.page-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
  flex-shrink: 0;

  h1 {
    margin: 0;
    font-size: 20px;
    font-weight: 600;
  }

  &__subtitle {
    color: var(--el-text-color-secondary);
    font-size: 14px;
  }
}

.grid-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
  align-items: center;
  flex-shrink: 0;
}

.table-empty-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
}
</style>
