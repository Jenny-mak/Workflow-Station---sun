<template>
  <div class="delegations-page">
    <div class="page-header">
      <h1>{{ t('delegation.title') }}</h1>
      <el-button
        type="primary"
        @click="showCreateDialog"
      >
        {{ t('delegation.create') }}
      </el-button>
    </div>

    <el-tabs
      v-model="activeTab"
      @tab-change="onTabChange"
    >
      <el-tab-pane
        :label="t('delegation.myDelegations')"
        name="my"
      >
        <div class="portal-card">
          <el-table
            :data="displayMyRows"
            stripe
            table-layout="fixed"
            style="width: 100%;"
            :span-method="mySpanMethod"
            :row-class-name="groupRowClassName"
          >
            <el-table-column
              v-for="(field, idx) in orderedMyFields"
              :key="field"
              :prop="field"
              :width="myColWidth(field, myWidthFallback(field))"
              :fixed="field === 'actions' ? 'right' : undefined"
              show-overflow-tooltip
            >
              <template #header>
                <PortalListColumnHeader
                  :label="myColumnLabel(field)"
                  :width="myColWidth(field, myWidthFallback(field))"
                  :has-filter="field !== 'actions' && myHasFilter(field)"
                  :sort-direction="field !== 'actions' ? mySortDirection(field) : null"
                  :is-grouped="field !== 'actions' && myIsGrouped(field)"
                  :can-move-left="field !== 'actions' && myCanMoveLeft(field)"
                  :can-move-right="field !== 'actions' && myCanMoveRight(field)"
                  :sortable="field !== 'actions'"
                  :filterable="field !== 'actions'"
                  :groupable="field !== 'actions'"
                  :movable="field !== 'actions'"
                  :date-like="myIsDateColumn(field)"
                  @sort-asc="onMySort(field, 'ASC')"
                  @sort-desc="onMySort(field, 'DESC')"
                  @group-by="onMyGroup(field)"
                  @filter="myOpenFilter(field, myColumnLabel(field))"
                  @clear-filter="onMyClearFilter(field)"
                  @move-left="myMoveLeft(field)"
                  @move-right="myMoveRight(field)"
                  @resize="(w) => myOnResize(field, w)"
                  @resize-end="myOnResizeEnd"
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
                <template v-else-if="field === 'delegateId'">
                  {{ row.delegateId }}
                </template>
                <template v-else-if="field === 'delegationType'">
                  {{ t(`delegation.${row.delegationType.toLowerCase()}`) }}
                </template>
                <template v-else-if="field === 'startTime'">
                  {{ row.startTime }}
                </template>
                <template v-else-if="field === 'endTime'">
                  {{ row.endTime }}
                </template>
                <template v-else-if="field === 'status'">
                  <el-tag
                    :type="getStatusType(row.status)"
                    size="small"
                  >
                    {{ t(`delegation.${row.status.toLowerCase()}`) }}
                  </el-tag>
                </template>
                <template v-else-if="field === 'actions'">
                  <div style="white-space: nowrap; display: flex; gap: 4px; align-items: center; flex-wrap: nowrap;">
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
              </template>
            </el-table-column>
          </el-table>

          <PortalListPagination
            v-model:current-page="myPagination.page"
            v-model:page-size="myPagination.size"
            :total="myPagination.total"
            :visible="true"
            @change="loadDelegations"
          />

          <PortalListFilterDialog
            v-model="myFilterDialogVisible"
            :title="myFilterDialogField
              ? `${t('mainTableView.colFilterBy')}: ${myFilterDialogField.label}`
              : t('mainTableView.colFilterBy')"
            :initial="myFilterDialogField
              ? myColState.filters[myFilterDialogField.field]
              : null"
            :column="myFilterColumn"
            :options="myFilterOptions"
            :options-loading="myFilterOptionsLoading"
            @search="myFilterSearch"
            @apply="onMyApplyFilter"
            @clear="onMyClearFilter()"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane
        :label="t('delegation.auditRecords')"
        name="audit"
      >
        <div class="portal-card">
          <el-table
            :data="displayAuditRows"
            stripe
            table-layout="fixed"
            style="width: 100%;"
            :span-method="auditSpanMethod"
            :row-class-name="groupRowClassName"
          >
            <el-table-column
              v-for="(field, idx) in orderedAuditFields"
              :key="field"
              :prop="field"
              :width="auditColWidth(field, auditWidthFallback(field))"
              show-overflow-tooltip
            >
              <template #header>
                <PortalListColumnHeader
                  :label="auditColumnLabel(field)"
                  :width="auditColWidth(field, auditWidthFallback(field))"
                  :has-filter="auditHasFilter(field)"
                  :sort-direction="auditSortDirection(field)"
                  :is-grouped="auditIsGrouped(field)"
                  :can-move-left="auditCanMoveLeft(field)"
                  :can-move-right="auditCanMoveRight(field)"
                  :date-like="auditIsDateColumn(field)"
                  @sort-asc="onAuditSort(field, 'ASC')"
                  @sort-desc="onAuditSort(field, 'DESC')"
                  @group-by="onAuditGroup(field)"
                  @filter="auditOpenFilter(field, auditColumnLabel(field))"
                  @clear-filter="onAuditClearFilter(field)"
                  @move-left="auditMoveLeft(field)"
                  @move-right="auditMoveRight(field)"
                  @resize="(w) => auditOnResize(field, w)"
                  @resize-end="auditOnResizeEnd"
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
                <template v-else>
                  {{ row[field] }}
                </template>
              </template>
            </el-table-column>
          </el-table>

          <PortalListPagination
            v-model:current-page="auditPagination.page"
            v-model:page-size="auditPagination.size"
            :disabled="auditLoading"
            :total="auditPagination.total"
            :visible="true"
            @change="onAuditPageChange"
          />

          <PortalListFilterDialog
            v-model="auditFilterDialogVisible"
            :title="auditFilterDialogField
              ? `${t('mainTableView.colFilterBy')}: ${auditFilterDialogField.label}`
              : t('mainTableView.colFilterBy')"
            :initial="auditFilterDialogField
              ? auditColState.filters[auditFilterDialogField.field]
              : null"
            :column="auditFilterColumn"
            :options="auditFilterOptions"
            :options-loading="auditFilterOptionsLoading"
            @search="auditFilterSearch"
            @apply="onAuditApplyFilter"
            @clear="onAuditClearFilter()"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="createDialogVisible"
      :title="t('delegation.create')"
      width="560px"
      @opened="onCreateDialogOpened"
    >
      <el-form
        :model="createForm"
        label-width="auto"
        label-position="left"
      >
        <el-form-item
          :label="t('delegation.delegateTo')"
          required
        >
          <el-select
            v-model="createForm.delegateId"
            filterable
            remote
            clearable
            reserve-keyword
            :placeholder="t('delegation.selectDelegate')"
            :remote-method="searchDelegates"
            :loading="userSearchLoading"
            style="width: 100%;"
          >
            <el-option
              v-for="u in userOptions"
              :key="u.id"
              :label="`${u.name} (${u.username})`"
              :value="u.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          :label="t('delegation.delegationType')"
          required
        >
          <el-select
            v-model="createForm.delegationType"
            style="width: 100%;"
          >
            <el-option
              value="ALL"
              :label="t('delegation.all')"
            />
            <el-option
              value="PARTIAL"
              :label="t('delegation.partial')"
            />
            <el-option
              value="TEMPORARY"
              :label="t('delegation.temporary')"
            />
            <el-option
              value="URGENT"
              :label="t('delegation.urgent')"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="createForm.delegationType === 'PARTIAL'"
          :label="t('delegation.processTypes')"
          required
        >
          <el-select
            v-model="createForm.processTypes"
            multiple
            filterable
            clearable
            :placeholder="t('delegation.selectProcessTypes')"
            :loading="processOptionsLoading"
            style="width: 100%;"
          >
            <el-option
              v-for="p in processOptions"
              :key="p.key"
              :label="p.name || p.key"
              :value="p.key"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          :label="t('delegation.startTime')"
          :required="createForm.delegationType === 'TEMPORARY'"
        >
          <el-date-picker
            v-model="createForm.startTime"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%;"
          />
        </el-form-item>
        <el-form-item
          :label="t('delegation.endTime')"
          :required="createForm.delegationType === 'TEMPORARY'"
        >
          <el-date-picker
            v-model="createForm.endTime"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%;"
          />
        </el-form-item>
        <el-form-item :label="t('delegation.reason')">
          <el-input
            v-model="createForm.reason"
            type="textarea"
            :rows="3"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          :loading="createSubmitting"
          @click="submitCreate"
        >
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { debounce } from 'lodash-es'
import {
  getDelegationRules,
  createDelegationRule,
  suspendDelegationRule,
  resumeDelegationRule,
  deleteDelegationRule,
  getDelegationAuditRecords,
  getDelegationRuleColumns,
  getDelegationAuditColumns,
  type DelegationRule
} from '@/api/delegation'
import { userApi, type UserOption } from '@/api/user'
import { processApi, type ProcessDefinition } from '@/api/process'
import { resolveUserFacingHttpMessage } from '@/utils/httpErrorMessage'
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
  type PortalListColumnMeta,
  type PortalListSortDirection,
} from '@/utils/portalListGridRuntime'
import { usePortalListFilterMeta } from '@/composables/usePortalListFilterMeta'

const { t } = useI18n()

const activeTab = ref('my')
const createDialogVisible = ref(false)
const createSubmitting = ref(false)
const userSearchLoading = ref(false)
const userOptions = ref<UserOption[]>([])
const processOptionsLoading = ref(false)
const processOptions = ref<ProcessDefinition[]>([])

const allDelegationList = ref<DelegationRule[]>([])
const myGroupCounts = ref<Record<string, number> | null>(null)
const auditLoading = ref(false)

const myPagination = reactive({ page: 1, size: PORTAL_LIST_DEFAULT_PAGE_SIZE, total: 0 })
const auditPagination = reactive({ page: 1, size: PORTAL_LIST_DEFAULT_PAGE_SIZE, total: 0 })

const MY_DATA_FIELDS = ['delegateId', 'delegationType', 'startTime', 'endTime', 'status']
const AUDIT_DATA_FIELDS = ['operationType', 'delegatorId', 'delegateId', 'operationResult', 'createdAt']

const myCols = usePortalListColumnState('delegations-my')
const {
  state: myColState,
  filterDialogVisible: myFilterDialogVisible,
  filterDialogField: myFilterDialogField,
  width: myColWidth,
  onResize: myOnResize,
  onResizeEnd: myOnResizeEnd,
  toggleSort: myToggleSort,
  toggleGroup: myToggleGroup,
  moveLeft: myMoveLeft,
  moveRight: myMoveRight,
  canMoveLeft: myCanMoveLeft,
  canMoveRight: myCanMoveRight,
  ensureOrder: myEnsureOrder,
  orderedColumnFields: myOrderedColumnFields,
  openFilter: myOpenFilter,
  applyFilter: myApplyFilter,
  clearFilter: myClearFilter,
  hasFilter: myHasFilter,
  sortDirection: mySortDirection,
  isGrouped: myIsGrouped,
  activeFilters: myActiveFilters,
} = myCols
myEnsureOrder(MY_DATA_FIELDS)

const auditCols = usePortalListColumnState('delegations-audit')
const {
  state: auditColState,
  filterDialogVisible: auditFilterDialogVisible,
  filterDialogField: auditFilterDialogField,
  width: auditColWidth,
  onResize: auditOnResize,
  onResizeEnd: auditOnResizeEnd,
  toggleSort: auditToggleSort,
  toggleGroup: auditToggleGroup,
  moveLeft: auditMoveLeft,
  moveRight: auditMoveRight,
  canMoveLeft: auditCanMoveLeft,
  canMoveRight: auditCanMoveRight,
  ensureOrder: auditEnsureOrder,
  orderedColumnFields: auditOrderedColumnFields,
  openFilter: auditOpenFilter,
  applyFilter: auditApplyFilter,
  clearFilter: auditClearFilter,
  hasFilter: auditHasFilter,
  sortDirection: auditSortDirection,
  isGrouped: auditIsGrouped,
  activeFilters: auditActiveFilters,
} = auditCols
auditEnsureOrder(AUDIT_DATA_FIELDS)

const orderedMyFields = computed(() => [...myOrderedColumnFields(MY_DATA_FIELDS), 'actions'])
const orderedAuditFields = computed(() => auditOrderedColumnFields(AUDIT_DATA_FIELDS))

function unwrapColumns(res: unknown): PortalListColumnMeta[] {
  const data = (res as { data?: unknown })?.data ?? res
  if (!Array.isArray(data)) throw new Error('Unexpected column metadata payload')
  return data as PortalListColumnMeta[]
}

/** Delegation enum codes reuse the labels the table cells already render. */
function delegationEnumLabel(_field: string, code: string): string {
  const key = `delegation.${code.toLowerCase()}`
  const label = t(key)
  return label === key ? code : label
}

const {
  ensureColumns: ensureMyColumns,
  isDateColumn: myIsDateColumn,
  openColumn: myFilterColumn,
  filterOptions: myFilterOptions,
  optionsLoading: myFilterOptionsLoading,
  onSearch: myFilterSearch,
  dispose: disposeMyFilterMeta,
} = usePortalListFilterMeta({
  loadColumns: async () => unwrapColumns(await getDelegationRuleColumns()),
  state: myColState,
  openField: myFilterDialogField,
  enumLabel: delegationEnumLabel,
})

const {
  ensureColumns: ensureAuditColumns,
  isDateColumn: auditIsDateColumn,
  openColumn: auditFilterColumn,
  filterOptions: auditFilterOptions,
  optionsLoading: auditFilterOptionsLoading,
  onSearch: auditFilterSearch,
  dispose: disposeAuditFilterMeta,
} = usePortalListFilterMeta({
  loadColumns: async () => unwrapColumns(await getDelegationAuditColumns()),
  state: auditColState,
  openField: auditFilterDialogField,
  enumLabel: delegationEnumLabel,
})

function myWidthFallback(field: string): number {
  const map: Record<string, number> = {
    delegateId: 140, delegationType: 140, startTime: 170, endTime: 170, status: 120, actions: 220,
  }
  return map[field] ?? 140
}
function myColumnLabel(field: string): string {
  const map: Record<string, string> = {
    delegateId: t('delegation.delegateTo'),
    delegationType: t('delegation.delegationType'),
    startTime: t('delegation.startTime'),
    endTime: t('delegation.endTime'),
    status: t('delegation.status'),
    actions: t('common.actions'),
  }
  return map[field] ?? field
}
function auditWidthFallback(field: string): number {
  const map: Record<string, number> = {
    operationType: 160, delegatorId: 140, delegateId: 140, operationResult: 140, createdAt: 170,
  }
  return map[field] ?? 140
}
function auditColumnLabel(field: string): string {
  const map: Record<string, string> = {
    operationType: t('delegation.operationType'),
    delegatorId: t('delegation.delegator'),
    delegateId: t('delegation.delegate'),
    operationResult: t('delegation.result'),
    createdAt: t('delegation.time'),
  }
  return map[field] ?? field
}

const displayMyRows = computed(() =>
  applyGroupHeaders(
    allDelegationList.value as unknown as Record<string, unknown>[],
    myColState.groupBy,
    undefined,
    myGroupCounts.value,
  ) as unknown as DelegationRule[],
)

/** Audit: server page + filters/sort/groupBy */
const auditRawList = ref<Array<Record<string, unknown>>>([])
const auditGroupCounts = ref<Record<string, number> | null>(null)

const displayAuditRows = computed(() =>
  applyGroupHeaders(auditRawList.value, auditColState.groupBy, undefined, auditGroupCounts.value),
)

function groupRowClassName({ row }: { row: unknown }) {
  return isPortalListGroupHeader(row) ? 'group-header-row' : ''
}
function mySpanMethod({ row, columnIndex }: { row: unknown; columnIndex: number }) {
  return portalListGroupSpanMethod(row, columnIndex, MY_DATA_FIELDS.length, 0)
}
function auditSpanMethod({ row, columnIndex }: { row: unknown; columnIndex: number }) {
  return portalListGroupSpanMethod(row, columnIndex, AUDIT_DATA_FIELDS.length, 0)
}

const createForm = reactive({
  delegateId: '',
  delegationType: 'ALL',
  processTypes: [] as string[],
  startTime: null as string | null,
  endTime: null as string | null,
  reason: ''
})

const getStatusType = (status: string) => {
  const map: Record<string, string> = {
    ACTIVE: 'success',
    INACTIVE: 'info',
    EXPIRED: 'info',
    SUSPENDED: 'warning'
  }
  return map[status] || 'info'
}

function onMySort(field: string, direction: PortalListSortDirection) {
  myToggleSort(field, direction)
  myPagination.page = 1
  void loadDelegations()
}

function onMyGroup(field: string) {
  myToggleGroup(field)
  myPagination.page = 1
  void loadDelegations()
}

function onMyApplyFilter(filter: PortalListColumnFilter) {
  myApplyFilter(filter)
  myPagination.page = 1
  void loadDelegations()
}

function onMyClearFilter(field?: string) {
  myClearFilter(field)
  myPagination.page = 1
  void loadDelegations()
}

function onAuditSort(field: string, direction: PortalListSortDirection) {
  auditToggleSort(field, direction)
  auditPagination.page = 1
  void loadAudit()
}

function onAuditGroup(field: string) {
  auditToggleGroup(field)
  auditPagination.page = 1
  void loadAudit()
}

function onAuditApplyFilter(filter: PortalListColumnFilter) {
  auditApplyFilter(filter)
  auditPagination.page = 1
  void loadAudit()
}

function onAuditClearFilter(field?: string) {
  auditClearFilter(field)
  auditPagination.page = 1
  void loadAudit()
}

const searchDelegatesRaw = async (keyword: string) => {
  userSearchLoading.value = true
  try {
    userOptions.value = await userApi.searchUsers(keyword || '')
  } catch (error) {
    userOptions.value = []
    ElMessage.error(resolveUserFacingHttpMessage(error, t) || t('task.searchUserFailed'))
  } finally {
    userSearchLoading.value = false
  }
}

const searchDelegates = debounce((keyword: string) => {
  void searchDelegatesRaw(keyword)
}, 300)

const loadProcessOptions = async () => {
  processOptionsLoading.value = true
  try {
    const res = await processApi.getDefinitions()
    const data = (res as { data?: ProcessDefinition[] }).data ?? res
    processOptions.value = Array.isArray(data) ? data : []
  } catch (error) {
    processOptions.value = []
    ElMessage.error(resolveUserFacingHttpMessage(error, t))
  } finally {
    processOptionsLoading.value = false
  }
}

const showCreateDialog = () => {
  createForm.delegateId = ''
  createForm.delegationType = 'ALL'
  createForm.processTypes = []
  createForm.startTime = null
  createForm.endTime = null
  createForm.reason = ''
  userOptions.value = []
  createDialogVisible.value = true
}

const onCreateDialogOpened = () => {
  searchDelegatesRaw('')
  if (processOptions.value.length === 0) {
    void loadProcessOptions()
  }
}

const validateCreateForm = (): boolean => {
  if (!createForm.delegateId) {
    ElMessage.warning(t('delegation.selectDelegate'))
    return false
  }
  if (createForm.delegationType === 'PARTIAL' && createForm.processTypes.length === 0) {
    ElMessage.warning(t('delegation.processTypesRequired'))
    return false
  }
  if (createForm.delegationType === 'TEMPORARY' && (!createForm.startTime || !createForm.endTime)) {
    ElMessage.warning(t('delegation.temporaryTimeRequired'))
    return false
  }
  if ((createForm.startTime == null) !== (createForm.endTime == null)) {
    ElMessage.warning(t('delegation.invalidTimeRange'))
    return false
  }
  if (createForm.startTime && createForm.endTime && createForm.endTime <= createForm.startTime) {
    ElMessage.warning(t('delegation.invalidTimeRange'))
    return false
  }
  return true
}

const submitCreate = async () => {
  if (!validateCreateForm()) return
  createSubmitting.value = true
  try {
    await createDelegationRule({
      delegateId: createForm.delegateId,
      delegationType: createForm.delegationType,
      processTypes:
        createForm.delegationType === 'PARTIAL' ? [...createForm.processTypes] : undefined,
      startTime: createForm.startTime || undefined,
      endTime: createForm.endTime || undefined,
      reason: createForm.reason || undefined
    })
    ElMessage.success(t('delegation.createSuccess'))
    createDialogVisible.value = false
    await loadDelegations()
  } catch (error) {
    ElMessage.error(resolveUserFacingHttpMessage(error, t))
  } finally {
    createSubmitting.value = false
  }
}

const handleSuspend = async (row: DelegationRule) => {
  try {
    await suspendDelegationRule(row.id)
    ElMessage.success(t('delegation.suspendSuccess'))
    row.status = 'SUSPENDED'
  } catch (error) {
    ElMessage.error(resolveUserFacingHttpMessage(error, t))
  }
}

const handleResume = async (row: DelegationRule) => {
  try {
    await resumeDelegationRule(row.id)
    ElMessage.success(t('delegation.resumeSuccess'))
    row.status = 'ACTIVE'
  } catch (error) {
    ElMessage.error(resolveUserFacingHttpMessage(error, t))
  }
}

const handleDelete = async (row: DelegationRule) => {
  await ElMessageBox.confirm(t('delegation.deleteConfirm'), t('common.info'), { type: 'warning' })
  try {
    await deleteDelegationRule(row.id)
    ElMessage.success(t('delegation.deleteSuccess'))
    await loadDelegations()
  } catch (error) {
    ElMessage.error(resolveUserFacingHttpMessage(error, t))
  }
}

const loadDelegations = async () => {
  try {
    const filters = myActiveFilters()
    const res = await getDelegationRules({
      page: myPagination.page - 1,
      size: myPagination.size,
      sortField: myColState.sort?.field,
      sortDirection: myColState.sort?.direction,
      filters: Object.keys(filters).length ? JSON.stringify(filters) : undefined,
      groupBy: myColState.groupBy || undefined,
    })
    const data = (res as { data?: unknown }).data || res
    if (Array.isArray(data)) {
      allDelegationList.value = data as DelegationRule[]
      myPagination.total = data.length
      myGroupCounts.value = null
    } else {
      const page = data as {
        content?: DelegationRule[]
        totalElements?: number
        groupCounts?: Record<string, number>
      }
      allDelegationList.value = Array.isArray(page.content) ? page.content : []
      myPagination.total = Number(page.totalElements || 0)
      myGroupCounts.value = normalizeGroupCounts(page.groupCounts)
    }
  } catch (error) {
    ElMessage.error(resolveUserFacingHttpMessage(error, t))
    allDelegationList.value = []
    myPagination.total = 0
    myGroupCounts.value = null
  }
}

/** Audit: always server page + filters/sort/groupBy. */
const loadAudit = async () => {
  auditLoading.value = true
  try {
    const filters = auditActiveFilters()
    const res = await getDelegationAuditRecords(auditPagination.page - 1, auditPagination.size, {
      sortField: auditColState.sort?.field,
      sortDirection: auditColState.sort?.direction,
      filters: Object.keys(filters).length ? JSON.stringify(filters) : undefined,
      groupBy: auditColState.groupBy || undefined,
    })
    const data = (res as { data?: { content?: Array<Record<string, unknown>>; totalElements?: number; groupCounts?: Record<string, number> } }).data || res
    const page = data as { content?: Array<Record<string, unknown>>; totalElements?: number; groupCounts?: Record<string, number> }
    auditRawList.value = Array.isArray(page.content) ? page.content : []
    auditPagination.total = Number(page.totalElements || 0)
    auditGroupCounts.value = normalizeGroupCounts(page.groupCounts)
  } catch (error) {
    ElMessage.error(resolveUserFacingHttpMessage(error, t))
    auditRawList.value = []
    auditPagination.total = 0
    auditGroupCounts.value = null
  } finally {
    auditLoading.value = false
  }
}

function onAuditPageChange() {
  void loadAudit()
}

const onTabChange = async (name: string | number) => {
  if (String(name) !== 'audit') return
  await ensureAuditColumns()
  void loadAudit()
}

// Column declarations first: they may drop a persisted filter the backend no longer
// accepts, and querying with one would fail the request instead of the page.
onMounted(async () => {
  await ensureMyColumns()
  void loadDelegations()
})

onBeforeUnmount(() => {
  searchDelegates.cancel()
  disposeMyFilterMeta()
  disposeAuditFilterMeta()
})
</script>

<style lang="scss" scoped>
.delegations-page {
  .page-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;

    h1 {
      font-size: 24px;
      font-weight: 500;
      color: var(--text-primary);
      margin: 0;
    }
  }

  :deep(.el-table .cell) {
    white-space: nowrap;
  }

  :deep(.el-table th .cell) {
    white-space: nowrap;
  }

  :deep(.el-form-item__label) {
    white-space: nowrap;
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
</style>
