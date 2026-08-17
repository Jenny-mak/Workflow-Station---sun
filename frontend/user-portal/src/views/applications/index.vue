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

      <!-- Drafts: server page + filters/sort/groupBy -->
      <template v-if="activeTab === 'DRAFT'">
        <el-table
          v-loading="loading"
          class="portal-list-grid"
          :data="displayDraftRows"
          stripe
          table-layout="fixed"
          :span-method="draftSpanMethod"
          :row-class-name="groupRowClassName"
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
            v-for="(field, idx) in orderedDraftFields"
            :key="field"
            :prop="field"
            :width="draftCols.width(field, draftWidthFallback(field))"
            :fixed="field === 'actions' ? 'right' : undefined"
            show-overflow-tooltip
          >
            <template #header>
              <PortalListColumnHeader
                :label="draftColumnLabel(field)"
                :width="draftCols.width(field, draftWidthFallback(field))"
                :has-filter="field !== 'actions' && draftCols.hasFilter(field)"
                :sort-direction="field !== 'actions' ? draftCols.sortDirection(field) : null"
                :is-grouped="field !== 'actions' && draftCols.isGrouped(field)"
                :can-move-left="field !== 'actions' && draftCols.canMoveLeft(field)"
                :can-move-right="field !== 'actions' && draftCols.canMoveRight(field)"
                :sortable="field !== 'actions'"
                :filterable="field !== 'actions'"
                :groupable="field !== 'actions'"
                :movable="field !== 'actions'"
                :date-like="field === 'updatedAt'"
                @sort-asc="onDraftSort(field, 'ASC')"
                @sort-desc="onDraftSort(field, 'DESC')"
                @group-by="onDraftGroup(field)"
                @filter="draftCols.openFilter(field, draftColumnLabel(field))"
                @clear-filter="onDraftClearFilter(field)"
                @move-left="draftCols.moveLeft(field)"
                @move-right="draftCols.moveRight(field)"
                @resize="(w) => draftCols.onResize(field, w)"
                @resize-end="draftCols.onResizeEnd"
              />
            </template>
            <template #default="{ row }">
              <template v-if="isPortalListGroupHeader(row)">
                <div
                  v-if="idx === 0"
                  class="group-header-cell"
                >
                  <strong>{{ row._groupLabel }}</strong>
                  <span class="group-count">({{ row._groupCount }})</span>
                </div>
              </template>
              <template v-else-if="field === 'processDefinitionName'">
                <el-link
                  type="primary"
                  @click="continueDraft(row)"
                >
                  {{ row.processDefinitionName }}
                </el-link>
              </template>
              <template v-else-if="field === 'updatedAt'">
                {{ formatDate(row.updatedAt) }}
              </template>
              <template v-else-if="field === 'actions'">
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
            </template>
          </el-table-column>
        </el-table>

        <PortalListPagination
          v-model:current-page="draftPagination.page"
          v-model:page-size="draftPagination.size"
          :disabled="loading"
          :total="draftPagination.total"
          :visible="true"
          @change="onDraftPageChange"
        />

        <PortalListFilterDialog
          v-model="draftCols.filterDialogVisible"
          :title="draftCols.filterDialogField
            ? `${t('mainTableView.colFilterBy')}: ${draftCols.filterDialogField.label}`
            : t('mainTableView.colFilterBy')"
          :initial="draftCols.filterDialogField
            ? draftCols.state.filters[draftCols.filterDialogField.field]
            : null"
          @apply="onDraftApplyFilter"
          @clear="onDraftClearFilter()"
        />
      </template>

      <!-- Applications: server filter/sort; group headers on current page -->
      <template v-else>
        <el-table
          v-loading="loading"
          class="portal-list-grid application-table"
          :data="displayApplicationRows"
          stripe
          table-layout="fixed"
          :span-method="appSpanMethod"
          :row-class-name="groupRowClassName"
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
            v-for="(field, idx) in orderedAppFields"
            :key="field"
            :prop="field"
            :width="colWidth(field, appWidthFallback(field))"
            :fixed="field === 'actions' ? 'right' : undefined"
            :align="field === 'status' || field === 'actions' ? 'center' : undefined"
            show-overflow-tooltip
          >
            <template #header>
              <PortalListColumnHeader
                :label="appColumnLabel(field)"
                :width="colWidth(field, appWidthFallback(field))"
                :has-filter="field !== 'actions' && hasFilter(field)"
                :sort-direction="field !== 'actions' && field !== 'requestId' ? sortDirection(field) : null"
                :is-grouped="field !== 'actions' && isGrouped(field)"
                :can-move-left="field !== 'actions' && canMoveLeft(field)"
                :can-move-right="field !== 'actions' && canMoveRight(field)"
                :sortable="field !== 'actions' && field !== 'requestId'"
                :filterable="field !== 'actions'"
                :groupable="field !== 'actions'"
                :movable="field !== 'actions'"
                :date-like="field === 'startTime'"
                @sort-asc="onSort(field, 'ASC')"
                @sort-desc="onSort(field, 'DESC')"
                @group-by="onGroup(field)"
                @filter="openFilter(field, appColumnLabel(field))"
                @clear-filter="onClearColumnFilter(field)"
                @move-left="moveLeft(field)"
                @move-right="moveRight(field)"
                @resize="(w) => onColResize(field, w)"
                @resize-end="onColResizeEnd"
              />
            </template>
            <template #default="{ row }">
              <template v-if="isPortalListGroupHeader(row)">
                <div
                  v-if="idx === 0"
                  class="group-header-cell"
                >
                  <strong>{{ row._groupLabel }}</strong>
                  <span class="group-count">({{ row._groupCount }})</span>
                </div>
              </template>
              <template v-else-if="field === 'requestId'">
                <el-link
                  type="primary"
                  @click="viewDetail(row)"
                >
                  {{ row.requestId || '-' }}
                </el-link>
              </template>
              <template v-else-if="field === 'businessKey'">
                {{ row.businessKey || row.processDefinitionName }}
              </template>
              <template v-else-if="field === 'currentStepName'">
                {{ row.currentStepName || row.currentNode || '-' }}
              </template>
              <template v-else-if="field === 'currentAssignee'">
                {{ row.currentAssignee || '-' }}
              </template>
              <template v-else-if="field === 'startTime'">
                {{ formatDate(row.startTime) }}
              </template>
              <template v-else-if="field === 'status'">
                <el-tag
                  :type="getStatusType(row.status)"
                  size="small"
                  effect="light"
                >
                  {{ getStatusLabel(row.status) }}
                </el-tag>
              </template>
              <template v-else-if="field === 'actions'">
                <div
                  v-if="row.status === 'RUNNING'"
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
                  v-else
                  class="no-action"
                >-</span>
              </template>
            </template>
          </el-table-column>
        </el-table>

        <PortalListPagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :disabled="loading"
          :total="pagination.total"
          :visible="true"
          @change="loadApplications"
        />

        <PortalListFilterDialog
          v-model="filterDialogVisible"
          :title="filterDialogField
            ? `${t('mainTableView.colFilterBy')}: ${filterDialogField.label}`
            : t('mainTableView.colFilterBy')"
          :initial="filterDialogField
            ? colState.filters[filterDialogField.field]
            : null"
          @apply="onApplyColumnFilter"
          @clear="onClearColumnFilter()"
        />
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { formatDate } from '@/utils/dateFormat'
import { processApi } from '@/api/process'
import PortalListPagination from '@/components/portal-list/PortalListPagination.vue'
import PortalListColumnHeader from '@/components/portal-list/PortalListColumnHeader.vue'
import PortalListFilterDialog from '@/components/portal-list/PortalListFilterDialog.vue'
import { usePortalListColumnState } from '@/composables/usePortalListColumnState'
import { PORTAL_LIST_DEFAULT_PAGE_SIZE } from '@/constants/portalListPagination'
import {
  applyGroupHeaders,
  isPortalListGroupHeader,
  normalizeGroupCounts,
  portalListGroupSpanMethod,
  type PortalListColumnFilter,
  type PortalListSortDirection,
} from '@/utils/portalListGridRuntime'

const { t } = useI18n()
const router = useRouter()

const APP_DATA_FIELDS = [
  'requestId',
  'businessKey',
  'currentStepName',
  'currentAssignee',
  'startTime',
  'status',
] as const

const DRAFT_DATA_FIELDS = ['processDefinitionName', 'updatedAt'] as const

const cols = usePortalListColumnState('applications-my-requests')
const {
  state: colState,
  filterDialogVisible,
  filterDialogField,
  width: colWidth,
  onResize: onColResize,
  onResizeEnd: onColResizeEnd,
  toggleSort,
  toggleGroup,
  moveLeft,
  moveRight,
  canMoveLeft,
  canMoveRight,
  ensureOrder,
  orderedColumnFields,
  openFilter,
  applyFilter,
  clearFilter,
  hasFilter,
  sortDirection,
  isGrouped,
  activeFilters,
} = cols

const draftCols = usePortalListColumnState('applications-drafts')

ensureOrder([...APP_DATA_FIELDS])
draftCols.ensureOrder([...DRAFT_DATA_FIELDS])

const orderedAppFields = computed(() => [
  ...orderedColumnFields([...APP_DATA_FIELDS]),
  'actions',
])

const orderedDraftFields = computed(() => [
  ...draftCols.orderedColumnFields([...DRAFT_DATA_FIELDS]),
  'actions',
])

/** FE column → API sortField whitelist */
const SORT_FIELD_MAP: Record<string, string> = {
  businessKey: 'businessKey',
  currentStepName: 'currentNode',
  currentAssignee: 'currentAssignee',
  startTime: 'startTime',
  status: 'status',
}

/** Drafts: FE processDefinitionName → BE processDefinitionKey for sort/group */
const DRAFT_SORT_FIELD_MAP: Record<string, string> = {
  processDefinitionName: 'processDefinitionKey',
  updatedAt: 'updatedAt',
}

const activeTab = ref('all')
const loading = ref(true)
const pagination = reactive({ page: 1, size: PORTAL_LIST_DEFAULT_PAGE_SIZE, total: 0 })
const draftPagination = reactive({ page: 1, size: PORTAL_LIST_DEFAULT_PAGE_SIZE, total: 0 })
const applicationList = ref<Record<string, unknown>[]>([])
const applicationGroupCounts = ref<Record<string, number> | null>(null)
const draftList = ref<Record<string, unknown>[]>([])
const draftGroupCounts = ref<Record<string, number> | null>(null)
const draftCount = ref(0)

function appWidthFallback(field: string): number {
  const map: Record<string, number> = {
    requestId: 140,
    businessKey: 180,
    currentStepName: 140,
    currentAssignee: 140,
    startTime: 170,
    status: 110,
    actions: 200,
  }
  return map[field] ?? 140
}

function draftWidthFallback(field: string): number {
  const map: Record<string, number> = {
    processDefinitionName: 200,
    updatedAt: 180,
    actions: 180,
  }
  return map[field] ?? 140
}

function appColumnLabel(field: string): string {
  const map: Record<string, string> = {
    requestId: t('application.requestId'),
    businessKey: t('application.processTitle'),
    currentStepName: t('application.currentStep'),
    currentAssignee: t('application.currentAssignee'),
    startTime: t('application.startTime'),
    status: t('application.status'),
    actions: t('common.actions'),
  }
  return map[field] ?? field
}

function draftColumnLabel(field: string): string {
  const map: Record<string, string> = {
    processDefinitionName: t('application.processType'),
    updatedAt: t('application.saveTime'),
    actions: t('common.actions'),
  }
  return map[field] ?? field
}

/**
 * Applications: server groupBy + groupCounts when available; otherwise page-local headers.
 * Status counts are remapped to localized labels so header keys match getCell.
 */
const displayApplicationRows = computed(() => {
  let counts = applicationGroupCounts.value
  if (counts && colState.groupBy === 'status') {
    const mapped: Record<string, number> = {}
    for (const [k, v] of Object.entries(counts)) {
      mapped[getStatusLabel(k)] = v
    }
    counts = mapped
  }
  return applyGroupHeaders(
    applicationList.value,
    colState.groupBy,
    (row, field) => {
      if (field === 'businessKey') return row.businessKey || row.processDefinitionName
      if (field === 'currentStepName') return row.currentStepName || row.currentNode
      if (field === 'status') return getStatusLabel(String(row.status ?? ''))
      return row[field]
    },
    counts,
  )
})

const displayDraftRows = computed(() => {
  let counts = draftGroupCounts.value
  if (counts && draftCols.state.groupBy === 'processDefinitionName') {
    const byName: Record<string, number> = { ...counts }
    for (const row of draftList.value) {
      const key = String(row.processDefinitionKey ?? '')
      const name = String(row.processDefinitionName ?? '')
      if (key && name && counts[key] != null) byName[name] = counts[key]
    }
    counts = byName
  }
  return applyGroupHeaders(draftList.value, draftCols.state.groupBy, undefined, counts)
})

function groupRowClassName({ row }: { row: unknown }) {
  return isPortalListGroupHeader(row) ? 'group-header-row' : ''
}

function appSpanMethod({ row, columnIndex }: { row: unknown; columnIndex: number }) {
  return portalListGroupSpanMethod(row, columnIndex, APP_DATA_FIELDS.length, 0)
}

function draftSpanMethod({ row, columnIndex }: { row: unknown; columnIndex: number }) {
  return portalListGroupSpanMethod(row, columnIndex, DRAFT_DATA_FIELDS.length, 0)
}

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
  loading.value = true
  try {
    const status = activeTab.value === 'all' ? undefined : activeTab.value
    const filters = activeFilters()
    const sortApi = colState.sort?.field
      ? SORT_FIELD_MAP[colState.sort.field]
      : undefined
    const groupApi = colState.groupBy
      ? (SORT_FIELD_MAP[colState.groupBy] || colState.groupBy)
      : undefined
    const response = await processApi.getMyApplications({
      page: pagination.page - 1,
      size: pagination.size,
      status,
      sortField: sortApi,
      sortDirection: colState.sort?.direction,
      filters: Object.keys(filters).length ? JSON.stringify(filters) : undefined,
      groupBy: groupApi,
    })
    const data = response.data || response
    applicationList.value = data.content || []
    pagination.total = data.totalElements || 0
    applicationGroupCounts.value = normalizeGroupCounts(data.groupCounts)
  } catch (error) {
    console.error('Failed to load applications:', error)
    ElMessage.error(t('application.loadFailed'))
    applicationList.value = []
    pagination.total = 0
    applicationGroupCounts.value = null
  } finally {
    loading.value = false
  }
}

function onSort(field: string, direction: PortalListSortDirection) {
  toggleSort(field, direction)
  pagination.page = 1
  loadApplications()
}

function onGroup(field: string) {
  toggleGroup(field)
  pagination.page = 1
  loadApplications()
}

function onApplyColumnFilter(filter: PortalListColumnFilter) {
  applyFilter(filter)
  pagination.page = 1
  loadApplications()
}

function onClearColumnFilter(field?: string) {
  clearFilter(field)
  pagination.page = 1
  loadApplications()
}

function onDraftSort(field: string, direction: PortalListSortDirection) {
  draftCols.toggleSort(field, direction)
  draftPagination.page = 1
  void loadDrafts()
}

function onDraftGroup(field: string) {
  draftCols.toggleGroup(field)
  draftPagination.page = 1
  void loadDrafts()
}

function onDraftApplyFilter(filter: PortalListColumnFilter) {
  draftCols.applyFilter(filter)
  draftPagination.page = 1
  void loadDrafts()
}

function onDraftClearFilter(field?: string) {
  draftCols.clearFilter(field)
  draftPagination.page = 1
  void loadDrafts()
}

function onDraftPageChange() {
  void loadDrafts()
}

const loadDrafts = async () => {
  loading.value = true
  try {
    const filters = draftCols.activeFilters()
    const sortApi = draftCols.state.sort?.field
      ? (DRAFT_SORT_FIELD_MAP[draftCols.state.sort.field] || draftCols.state.sort.field)
      : undefined
    const groupApi = draftCols.state.groupBy
      ? (DRAFT_SORT_FIELD_MAP[draftCols.state.groupBy] || draftCols.state.groupBy)
      : undefined
    const response = await processApi.getDraftList({
      page: draftPagination.page - 1,
      size: draftPagination.size,
      sortField: sortApi,
      sortDirection: draftCols.state.sort?.direction,
      filters: Object.keys(filters).length ? JSON.stringify(filters) : undefined,
      groupBy: groupApi,
    })
    const data = response.data || response
    if (Array.isArray(data)) {
      draftList.value = data
      draftPagination.total = data.length
      draftCount.value = data.length
      draftGroupCounts.value = null
    } else {
      draftList.value = Array.isArray(data.content) ? data.content : []
      draftPagination.total = Number(data.totalElements || 0)
      draftCount.value = draftPagination.total
      draftGroupCounts.value = normalizeGroupCounts(data.groupCounts)
    }
  } catch (error) {
    console.error('Failed to load drafts:', error)
    ElMessage.error(t('application.loadDraftsFailed'))
    draftList.value = []
    draftPagination.total = 0
    draftGroupCounts.value = null
  } finally {
    loading.value = false
  }
}

const loadDraftCount = async () => {
  try {
    const response = await processApi.getDraftList({ page: 0, size: 1 })
    const data = response.data || response
    if (Array.isArray(data)) {
      draftCount.value = data.length
    } else {
      draftCount.value = Number(data.totalElements || 0)
    }
  } catch (error) {
    console.error('Failed to load draft count:', error)
  }
}

const handleTabChange = () => {
  pagination.page = 1
  draftPagination.page = 1
  if (activeTab.value === 'DRAFT') {
    loadDrafts()
  } else {
    loadApplications()
  }
}

const viewDetail = (row: Record<string, unknown>) => {
  router.push(`/applications/${row.id}`)
}

const continueDraft = (row: Record<string, unknown>) => {
  router.push(`/processes/start/${row.processDefinitionKey}?draft=true`)
}

const handleDeleteDraft = async (row: Record<string, unknown>) => {
  try {
    await ElMessageBox.confirm(t('application.deleteDraftConfirm'), t('common.info'), { type: 'warning' })
    await processApi.deleteDraftById(row.id as string | number)
    ElMessage.success(t('application.deleteSuccess'))
    loadDrafts()
  } catch (error: unknown) {
    if (error !== 'cancel') {
      ElMessage.error(t('application.deleteFailed'))
    }
  }
}

const handleUrge = async (row: Record<string, unknown>) => {
  try {
    await processApi.urgeProcess(row.id as string | number)
    ElMessage.success(t('application.urgeSuccess'))
  } catch {
    ElMessage.error(t('application.urgeFailed'))
  }
}

const handleReturnToDraft = async (row: Record<string, unknown>) => {
  try {
    await ElMessageBox.confirm(
      t('application.returnToDraftConfirm'),
      t('application.returnToDraftConfirmTitle'),
      { type: 'warning' },
    )
    await processApi.returnProcessToFirstStep(row.id as string | number)
    ElMessage.success(t('application.returnToDraftSuccess'))
    loadApplications()
  } catch (error: unknown) {
    if (error !== 'cancel') {
      ElMessage.error(t('application.returnToDraftFailed'))
    }
  }
}

const handleWithdraw = async (row: Record<string, unknown>) => {
  try {
    await ElMessageBox.confirm(t('application.withdrawConfirm'), t('common.info'), { type: 'warning' })
    await processApi.withdrawProcess(row.id as string | number, t('application.userWithdraw'))
    ElMessage.success(t('application.withdrawSuccess'))
    loadApplications()
  } catch (error: unknown) {
    if (error !== 'cancel') {
      ElMessage.error(t('application.withdrawFailed'))
    }
  }
}

onMounted(() => {
  void Promise.all([loadApplications(), loadDraftCount()])
})
</script>

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

:deep(.group-header-row) {
  background: var(--el-fill-color-light) !important;
  cursor: default;
  font-weight: 600;
}

.applications-page {
  .page-header {
    margin-bottom: 20px;

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

  .application-table {
    :deep(.el-table__header th) {
      background-color: #fafafa;
      font-weight: 500;
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
}
</style>
