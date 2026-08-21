<template>
  <div class="applications-page">
    <div class="page-header">
      <h1>{{ t('application.title') }}</h1>
    </div>

    <div class="portal-card">
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
        <el-tab-pane name="DRAFT">
          <template #label>
            <span>{{ t('application.draftBox') }}</span>
            <el-badge
              v-if="draftCount > 0"
              :value="draftCount"
              :max="99"
              class="draft-badge"
            />
          </template>
        </el-tab-pane>
      </el-tabs>

      <template v-if="activeTab === 'DRAFT'">
        <el-table
          :data="draftList"
          stripe
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
            <span v-else>{{ t('application.noDrafts') }}</span>
          </template>
          <el-table-column
            prop="processDefinitionName"
            :label="t('application.processType')"
            min-width="200"
          >
            <template #default="{ row }">
              <el-link
                type="primary"
                @click="continueDraft(row)"
              >
                {{ row.processDefinitionName }}
              </el-link>
            </template>
          </el-table-column>
          <el-table-column
            prop="updatedAt"
            :label="t('application.saveTime')"
            width="180"
          >
            <template #default="{ row }">
              {{ formatDate(row.updatedAt) }}
            </template>
          </el-table-column>
          <el-table-column
            :label="t('common.actions')"
            width="180"
            fixed="right"
          >
            <template #default="{ row }">
              <el-button
                type="primary"
                size="small"
                @click="continueDraft(row)"
              >
                {{ t('application.continueFilling') }}
              </el-button>
              <el-button
                type="danger"
                size="small"
                @click="handleDeleteDraft(row)"
              >
                {{ t('common.delete') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template v-else>
        <div
          v-loading="loading"
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
              :fit="gridFits"
              table-layout="fixed"
              style="width: 100%;"
              class="list-data-grid"
              :class="{ 'list-data-grid--fit': gridFits }"
              :span-method="spanMethod(1)"
              :row-class-name="rowClassName"
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
                <span v-else>{{ t('application.noApplications') }}</span>
              </template>
              <el-table-column
                v-for="(col, colIndex) in displayColumns"
                :key="col.field"
                :prop="col.field"
                :width="gridFits ? undefined : widthOf(col.field)"
                :min-width="gridFits ? widthOf(col.field) : undefined"
                show-overflow-tooltip
              >
                <template #header>
                  <ListColumnHeader
                    :column="col"
                    :sort="sort.field === col.field ? sort.direction : null"
                    :grouped="groupBy === col.field"
                    :filtered="!!columnFilters[col.field]"
                    :width="widthOf(col.field)"
                    :show-move="displayColumns.length > 1"
                    :can-move-left="colIndex > 0"
                    :can-move-right="colIndex < displayColumns.length - 1"
                    @sort-change="(direction: 'ASC' | 'DESC') => onSort(col.field, direction)"
                    @clear-sort="onClearSort"
                    @group-change="(grouped: boolean) => onGroup(col.field, grouped)"
                    @filter-open="openFilter(col.field)"
                    @clear-filter="onClearFilter(col.field)"
                    @move="(direction: 'left' | 'right') => moveColumn(col.field, direction)"
                    @width-change="(width: number) => setWidth(col.field, width)"
                    @width-commit="persistWidths"
                  />
                </template>
                <template #default="{ row }">
                  <template v-if="isListGroupHeaderRow(row)">
                    <div class="group-header-cell">
                      <strong>{{ groupHeaderLabel(row._groupLabel) }}</strong>
                      <span class="group-count">({{ row._groupCount }})</span>
                    </div>
                  </template>
                  <el-link
                    v-else-if="col.field === 'requestId'"
                    type="primary"
                    @click="viewDetail(row)"
                  >
                    {{ row.requestId || '-' }}
                  </el-link>
                  <template v-else-if="col.field === 'businessKey'">
                    {{ row.businessKey || row.processDefinitionName }}
                  </template>
                  <template v-else-if="col.field === 'currentStepName'">
                    {{ row.currentStepName || row.currentNode || '-' }}
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
              <el-table-column
                :label="t('common.actions')"
                :width="ACTIONS_COL_WIDTH"
                fixed="right"
                align="center"
              >
                <template #default="{ row }">
                  <div
                    v-if="!isListGroupHeaderRow(row) && row.status === 'RUNNING'"
                    class="action-buttons"
                  >
                    <el-button
                      type="warning"
                      size="small"
                      link
                      @click="handleUrge(row)"
                    >
                      {{ t('application.urge') }}
                    </el-button>
                    <el-divider direction="vertical" />
                    <el-button
                      type="primary"
                      size="small"
                      link
                      @click="handleReturnToDraft(row)"
                    >
                      {{ t('application.returnToDraft') }}
                    </el-button>
                    <el-divider direction="vertical" />
                    <el-button
                      type="danger"
                      size="small"
                      link
                      @click="handleWithdraw(row)"
                    >
                      {{ t('application.withdraw') }}
                    </el-button>
                  </div>
                  <span
                    v-else-if="!isListGroupHeaderRow(row)"
                    class="no-action"
                  >-</span>
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
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'
import { processApi, type ProcessInstance } from '@/api/process'
import { usePortalListGrid } from '@/composables/list/usePortalListGrid'
import { searchListFilterUsers } from '@/composables/list/searchListFilterUsers'
import { formatDate } from '@/utils/dateFormat'

const ACTIONS_COL_WIDTH = 200
const APPLICATION_COL_WIDTHS: Record<string, number> = {
  requestId: 160,
  businessKey: 200,
  currentStepName: 170,
  currentAssignee: 240,
  startTime: 170,
  status: 120,
}

interface DraftRow {
  id: number
  processDefinitionKey: string
  processDefinitionName: string
  updatedAt: string
}

const { t } = useI18n()
const router = useRouter()

const activeTab = ref('all')
const loading = ref(true)
const draftList = ref<DraftRow[]>([])
const draftCount = ref(0)

const {
  displayColumns,
  displayRows,
  groupBy,
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
  beginQuery,
  isCurrentQuery,
  applyPage,
  buildQuery,
  moveColumn,
  resetPage,
  openFilter,
  applyFilter,
  clearFilter,
  applySort,
  clearSort,
  applyGroup,
  rowClassName,
  spanMethod,
  groupHeaderLabel,
  isListGroupHeaderRow,
} = usePortalListGrid<ProcessInstance>({
  storageKey: 'portal-list-layout:my-applications',
  extraWidth: ACTIONS_COL_WIDTH,
  defaultWidthOf: (field) => APPLICATION_COL_WIDTHS[field] ?? 120,
})

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

const loadApplications = async () => {
  const seq = beginQuery()
  loading.value = true
  try {
    const status = activeTab.value === 'all' ? undefined : activeTab.value
    const response = await processApi.queryMyApplications({ ...buildQuery(), status })
    if (!isCurrentQuery(seq)) return
    applyPage(response.data, 'my-applications/query response is missing its column declaration')
  } catch (error) {
    if (!isCurrentQuery(seq)) return
    if (!(error as { response?: unknown })?.response) {
      ElMessage.error(error instanceof Error ? error.message : t('application.loadFailed'))
    }
  } finally {
    if (isCurrentQuery(seq)) loading.value = false
  }
}

const loadDrafts = async () => {
  loading.value = true
  try {
    const response = await processApi.getDraftList()
    const data = response.data || response
    draftList.value = Array.isArray(data) ? data : []
    draftCount.value = draftList.value.length
  } catch (error) {
    if (!(error as { response?: unknown })?.response) {
      ElMessage.error(error instanceof Error ? error.message : t('application.loadDraftsFailed'))
    }
    draftList.value = []
  } finally {
    loading.value = false
  }
}

const loadDraftCount = async () => {
  const response = await processApi.getDraftList()
  const data = response.data || response
  if (!Array.isArray(data)) {
    throw new Error('draft list did not return an array')
  }
  draftCount.value = data.length
}

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  loadApplications()
}

function onClearSort() {
  clearSort()
  loadApplications()
}

function onGroup(field: string, grouped: boolean) {
  applyGroup(field, grouped)
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
  if (activeTab.value === 'DRAFT') {
    loadDrafts()
  } else {
    loadApplications()
  }
}

const viewDetail = (row: ProcessInstance) => {
  router.push(`/applications/${row.id}`)
}

const continueDraft = (row: DraftRow) => {
  router.push(`/processes/start/${row.processDefinitionKey}?draft=true`)
}

const handleDeleteDraft = async (row: DraftRow) => {
  try {
    await ElMessageBox.confirm(t('application.deleteDraftConfirm'), t('common.info'), { type: 'warning' })
    await processApi.deleteDraftById(row.id)
    ElMessage.success(t('application.deleteSuccess'))
    loadDrafts()
  } catch (error: unknown) {
    if (error !== 'cancel') {
      ElMessage.error(t('application.deleteFailed'))
    }
  }
}

const handleUrge = async (row: ProcessInstance) => {
  try {
    await processApi.urgeProcess(row.id)
    ElMessage.success(t('application.urgeSuccess'))
  } catch {
    ElMessage.error(t('application.urgeFailed'))
  }
}

const handleReturnToDraft = async (row: ProcessInstance) => {
  try {
    await ElMessageBox.confirm(
      t('application.returnToDraftConfirm'),
      t('application.returnToDraftConfirmTitle'),
      { type: 'warning' },
    )
    await processApi.returnProcessToFirstStep(row.id)
    ElMessage.success(t('application.returnToDraftSuccess'))
    loadApplications()
  } catch (error: unknown) {
    if (error !== 'cancel') {
      ElMessage.error(t('application.returnToDraftFailed'))
    }
  }
}

const handleWithdraw = async (row: ProcessInstance) => {
  try {
    await ElMessageBox.confirm(t('application.withdrawConfirm'), t('common.info'), { type: 'warning' })
    await processApi.withdrawProcess(row.id, t('application.userWithdraw'))
    ElMessage.success(t('application.withdrawSuccess'))
    loadApplications()
  } catch (error: unknown) {
    if (error !== 'cancel') {
      ElMessage.error(t('application.withdrawFailed'))
    }
  }
}

onMounted(() => {
  void loadApplications()
  void loadDraftCount().catch((error: unknown) => {
    if (!(error as { response?: unknown })?.response) {
      ElMessage.error(error instanceof Error ? error.message : t('application.loadDraftsFailed'))
    }
  })
})
</script>

<style lang="scss">
@import '@/styles/listDataGrid.scss';
</style>

<style lang="scss" scoped>
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

.applications-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;

  :deep(.portal-card) {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  .page-header {
    margin-bottom: 20px;
    flex-shrink: 0;

    h1 {
      font-size: 24px;
      font-weight: 500;
      color: var(--text-primary);
      margin: 0;
    }
  }

  .draft-badge {
    margin-left: 6px;

    :deep(.el-badge__content) {
      font-size: 10px;
    }
  }

  .action-buttons {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 0;

    .el-button {
      padding: 4px 8px;
      font-size: 13px;
    }

    .el-divider--vertical {
      margin: 0 4px;
      height: 14px;
    }
  }

  .no-action {
    color: #c0c4cc;
  }
}
</style>
