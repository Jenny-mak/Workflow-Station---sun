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
        <div
          v-loading="rulesLoading"
          class="portal-card list-tab-card"
        >
          <div
            ref="rulesGridScrollRef"
            class="list-data-grid-scroll"
          >
            <div
              class="list-data-grid-inner"
              :style="rulesGridInnerStyle"
            >
              <el-table
                :data="rulesDisplayRows"
                stripe
                :fit="rulesGridFits"
                table-layout="fixed"
                style="width: 100%;"
                class="list-data-grid"
                :class="{ 'list-data-grid--fit': rulesGridFits }"
                :span-method="rulesSpanMethod(1)"
                :row-class-name="rulesRowClassName"
              >
                <template #empty>
                  <div
                    v-if="rulesLoading"
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
                  v-for="(col, colIndex) in rulesDisplayColumns"
                  :key="col.field"
                  :prop="col.field"
                  :width="rulesGridFits ? undefined : rulesWidthOf(col.field)"
                  :min-width="rulesGridFits ? rulesWidthOf(col.field) : undefined"
                  show-overflow-tooltip
                >
                  <template #header>
                    <ListColumnHeader
                      :column="col"
                      :sort="rulesSort.field === col.field ? rulesSort.direction : null"
                      :grouped="rulesGroupBy === col.field"
                      :filtered="!!rulesColumnFilters[col.field]"
                      :width="rulesWidthOf(col.field)"
                      :show-move="rulesDisplayColumns.length > 1"
                      :can-move-left="colIndex > 0"
                      :can-move-right="colIndex < rulesDisplayColumns.length - 1"
                      @sort-change="(direction: 'ASC' | 'DESC') => onRulesSort(col.field, direction)"
                      @clear-sort="onRulesClearSort"
                      @group-change="(grouped: boolean) => onRulesGroup(col.field, grouped)"
                      @filter-open="rulesOpenFilter(col.field)"
                      @clear-filter="onRulesClearFilter(col.field)"
                      @move="(direction: 'left' | 'right') => rulesMoveColumn(col.field, direction)"
                      @width-change="(width: number) => rulesSetWidth(col.field, width)"
                      @width-commit="rulesPersistWidths"
                    />
                  </template>
                  <template #default="{ row }">
                    <template v-if="isListGroupHeaderRow(row)">
                      <div class="group-header-cell">
                        <strong>{{ rulesGroupHeaderLabel(row._groupLabel) }}</strong>
                        <span class="group-count">({{ row._groupCount }})</span>
                      </div>
                    </template>
                    <el-tag
                      v-else-if="col.field === 'status'"
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
                  min-width="200"
                  fixed="right"
                >
                  <template #default="{ row }">
                    <template v-if="!isListGroupHeaderRow(row)">
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
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
          <ListPagination
            v-model:page="rulesPagination.page"
            v-model:size="rulesPagination.size"
            :total="rulesPagination.total"
            :loading="rulesLoading"
            @change="loadRules"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane
        :label="t('delegation.proxyTasks')"
        name="proxy"
      >
        <div class="portal-card">
          <el-empty :description="t('delegation.noProxyTasks')" />
        </div>
      </el-tab-pane>

      <el-tab-pane
        :label="t('delegation.auditRecords')"
        name="audit"
      >
        <div
          v-loading="auditLoading"
          class="portal-card list-tab-card"
        >
          <div
            ref="auditGridScrollRef"
            class="list-data-grid-scroll"
          >
            <div
              class="list-data-grid-inner"
              :style="auditGridInnerStyle"
            >
              <el-table
                :data="auditDisplayRows"
                stripe
                :fit="auditGridFits"
                table-layout="fixed"
                style="width: 100%;"
                class="list-data-grid"
                :class="{ 'list-data-grid--fit': auditGridFits }"
                :span-method="auditSpanMethod(0)"
                :row-class-name="auditRowClassName"
              >
                <template #empty>
                  <div
                    v-if="auditLoading"
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
                  v-for="(col, colIndex) in auditDisplayColumns"
                  :key="col.field"
                  :prop="col.field"
                  :width="auditGridFits ? undefined : auditWidthOf(col.field)"
                  :min-width="auditGridFits ? auditWidthOf(col.field) : undefined"
                  show-overflow-tooltip
                >
                  <template #header>
                    <ListColumnHeader
                      :column="col"
                      :sort="auditSort.field === col.field ? auditSort.direction : null"
                      :grouped="auditGroupBy === col.field"
                      :filtered="!!auditColumnFilters[col.field]"
                      :width="auditWidthOf(col.field)"
                      :show-move="auditDisplayColumns.length > 1"
                      :can-move-left="colIndex > 0"
                      :can-move-right="colIndex < auditDisplayColumns.length - 1"
                      @sort-change="(direction: 'ASC' | 'DESC') => onAuditSort(col.field, direction)"
                      @clear-sort="onAuditClearSort"
                      @group-change="(grouped: boolean) => onAuditGroup(col.field, grouped)"
                      @filter-open="auditOpenFilter(col.field)"
                      @clear-filter="onAuditClearFilter(col.field)"
                      @move="(direction: 'left' | 'right') => auditMoveColumn(col.field, direction)"
                      @width-change="(width: number) => auditSetWidth(col.field, width)"
                      @width-commit="auditPersistWidths"
                    />
                  </template>
                  <template #default="{ row }">
                    <template v-if="isListGroupHeaderRow(row)">
                      <div class="group-header-cell">
                        <strong>{{ auditGroupHeaderLabel(row._groupLabel) }}</strong>
                        <span class="group-count">({{ row._groupCount }})</span>
                      </div>
                    </template>
                    <span
                      v-else-if="col.field === 'createdAt'"
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
            v-model:page="auditPagination.page"
            v-model:size="auditPagination.size"
            :total="auditPagination.total"
            :loading="auditLoading"
            @change="loadAudit"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <ListFilterDialog
      v-model:visible="rulesFilterDialog.visible"
      :column="rulesActiveFilterColumn"
      :filter="rulesActiveFilter"
      :remote-search="searchListFilterUsers"
      @apply="onRulesFilterApply"
      @clear="onRulesFilterClear"
    />
    <ListFilterDialog
      v-model:visible="auditFilterDialog.visible"
      :column="auditActiveFilterColumn"
      :filter="auditActiveFilter"
      :remote-search="searchListFilterUsers"
      @apply="onAuditFilterApply"
      @clear="onAuditFilterClear"
    />

    <el-dialog
      v-model="createDialogVisible"
      :title="t('delegation.create')"
      width="500px"
    >
      <el-form
        :model="createForm"
        label-width="auto"
        label-position="left"
      >
        <el-form-item :label="t('delegation.delegateTo')">
          <el-select
            v-model="createForm.delegateId"
            filterable
            :placeholder="t('delegation.selectDelegate')"
            style="width: 100%;"
          >
            <el-option
              label="Li Si"
              value="user_2"
            />
            <el-option
              label="Wang Wu"
              value="user_3"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('delegation.delegationType')">
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
        <el-form-item :label="t('delegation.startTime')">
          <el-date-picker
            v-model="createForm.startTime"
            type="datetime"
            style="width: 100%;"
          />
        </el-form-item>
        <el-form-item :label="t('delegation.endTime')">
          <el-date-picker
            v-model="createForm.endTime"
            type="datetime"
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
          @click="submitCreate"
        >
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'
import {
  queryDelegationRules,
  queryDelegationAudit,
  createDelegationRule,
  suspendDelegationRule,
  resumeDelegationRule,
  deleteDelegationRule,
  type DelegationRule,
  type DelegationAudit,
} from '@/api/delegation'
import { usePortalListGrid } from '@/composables/list/usePortalListGrid'
import { searchListFilterUsers } from '@/composables/list/searchListFilterUsers'
import { formatDate } from '@/utils/dateFormat'

const RULES_COL_WIDTHS: Record<string, number> = {
  delegateId: 140,
  delegationType: 140,
  startTime: 170,
  endTime: 170,
  status: 110,
  reason: 180,
  createdAt: 170,
}

const AUDIT_COL_WIDTHS: Record<string, number> = {
  operationType: 150,
  delegatorId: 140,
  delegateId: 140,
  taskId: 140,
  operationResult: 120,
  createdAt: 170,
}

const { t } = useI18n()
const activeTab = ref('my')
const createDialogVisible = ref(false)
const rulesLoading = ref(true)
const auditLoading = ref(false)
const auditLoaded = ref(false)

const {
  displayColumns: rulesDisplayColumns,
  displayRows: rulesDisplayRows,
  groupBy: rulesGroupBy,
  columnFilters: rulesColumnFilters,
  sort: rulesSort,
  filterDialog: rulesFilterDialog,
  pagination: rulesPagination,
  activeFilterColumn: rulesActiveFilterColumn,
  activeFilter: rulesActiveFilter,
  gridScrollRef: rulesGridScrollRef,
  gridFits: rulesGridFits,
  gridInnerStyle: rulesGridInnerStyle,
  widthOf: rulesWidthOf,
  setWidth: rulesSetWidth,
  persistWidths: rulesPersistWidths,
  beginQuery: rulesBeginQuery,
  isCurrentQuery: rulesIsCurrentQuery,
  applyPage: rulesApplyPage,
  buildQuery: rulesBuildQuery,
  moveColumn: rulesMoveColumn,
  openFilter: rulesOpenFilter,
  applyFilter: rulesApplyFilter,
  clearFilter: rulesClearFilter,
  applySort: rulesApplySort,
  clearSort: rulesClearSort,
  applyGroup: rulesApplyGroup,
  rowClassName: rulesRowClassName,
  spanMethod: rulesSpanMethod,
  groupHeaderLabel: rulesGroupHeaderLabel,
  isListGroupHeaderRow,
} = usePortalListGrid<DelegationRule>({
  storageKey: 'portal-list-layout:delegation-rules',
  extraWidth: 200,
  defaultWidthOf: (field) => RULES_COL_WIDTHS[field] ?? 120,
})

const {
  displayColumns: auditDisplayColumns,
  displayRows: auditDisplayRows,
  groupBy: auditGroupBy,
  columnFilters: auditColumnFilters,
  sort: auditSort,
  filterDialog: auditFilterDialog,
  pagination: auditPagination,
  activeFilterColumn: auditActiveFilterColumn,
  activeFilter: auditActiveFilter,
  gridScrollRef: auditGridScrollRef,
  gridFits: auditGridFits,
  gridInnerStyle: auditGridInnerStyle,
  widthOf: auditWidthOf,
  setWidth: auditSetWidth,
  persistWidths: auditPersistWidths,
  beginQuery: auditBeginQuery,
  isCurrentQuery: auditIsCurrentQuery,
  applyPage: auditApplyPage,
  buildQuery: auditBuildQuery,
  moveColumn: auditMoveColumn,
  openFilter: auditOpenFilter,
  applyFilter: auditApplyFilter,
  clearFilter: auditClearFilter,
  applySort: auditApplySort,
  clearSort: auditClearSort,
  applyGroup: auditApplyGroup,
  rowClassName: auditRowClassName,
  spanMethod: auditSpanMethod,
  groupHeaderLabel: auditGroupHeaderLabel,
} = usePortalListGrid<DelegationAudit>({
  storageKey: 'portal-list-layout:delegation-audit',
  defaultWidthOf: (field) => AUDIT_COL_WIDTHS[field] ?? 120,
})

const createForm = reactive({
  delegateId: '',
  delegationType: 'ALL',
  startTime: null as string | null,
  endTime: null as string | null,
  reason: '',
})

const getStatusType = (status: string): 'success' | 'info' | 'warning' => {
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

const showCreateDialog = () => {
  createForm.delegateId = ''
  createForm.delegationType = 'ALL'
  createForm.startTime = null
  createForm.endTime = null
  createForm.reason = ''
  createDialogVisible.value = true
}

const loadRules = async () => {
  const seq = rulesBeginQuery()
  rulesLoading.value = true
  try {
    const res = await queryDelegationRules(rulesBuildQuery())
    if (!rulesIsCurrentQuery(seq)) return
    rulesApplyPage(res.data, 'delegations/query response is missing its column declaration')
  } catch (error) {
    if (!rulesIsCurrentQuery(seq)) return
    if (!(error as { response?: unknown })?.response) {
      ElMessage.error(error instanceof Error ? error.message : t('delegation.loadFailed'))
    }
  } finally {
    if (rulesIsCurrentQuery(seq)) rulesLoading.value = false
  }
}

const loadAudit = async () => {
  const seq = auditBeginQuery()
  auditLoading.value = true
  try {
    const res = await queryDelegationAudit(auditBuildQuery())
    if (!auditIsCurrentQuery(seq)) return
    auditApplyPage(res.data, 'delegations/audit/query response is missing its column declaration')
    auditLoaded.value = true
  } catch (error) {
    if (!auditIsCurrentQuery(seq)) return
    if (!(error as { response?: unknown })?.response) {
      ElMessage.error(error instanceof Error ? error.message : t('delegation.loadFailed'))
    }
  } finally {
    if (auditIsCurrentQuery(seq)) auditLoading.value = false
  }
}

function onTabChange(name: string | number) {
  if (name === 'audit' && !auditLoaded.value) {
    loadAudit()
  }
}

function onRulesSort(field: string, direction: 'ASC' | 'DESC') {
  rulesApplySort(field, direction)
  loadRules()
}

function onRulesClearSort() {
  rulesClearSort()
  loadRules()
}

function onRulesGroup(field: string, grouped: boolean) {
  rulesApplyGroup(field, grouped)
  loadRules()
}

function onRulesClearFilter(field: string) {
  rulesClearFilter(field)
  loadRules()
}

function onRulesFilterApply(filter: ListColumnFilter) {
  rulesApplyFilter(filter)
  loadRules()
}

function onRulesFilterClear() {
  onRulesClearFilter(rulesFilterDialog.field)
}

function onAuditSort(field: string, direction: 'ASC' | 'DESC') {
  auditApplySort(field, direction)
  loadAudit()
}

function onAuditClearSort() {
  auditClearSort()
  loadAudit()
}

function onAuditGroup(field: string, grouped: boolean) {
  auditApplyGroup(field, grouped)
  loadAudit()
}

function onAuditClearFilter(field: string) {
  auditClearFilter(field)
  loadAudit()
}

function onAuditFilterApply(filter: ListColumnFilter) {
  auditApplyFilter(filter)
  loadAudit()
}

function onAuditFilterClear() {
  onAuditClearFilter(auditFilterDialog.field)
}

const submitCreate = async () => {
  if (!createForm.delegateId) {
    ElMessage.warning(t('delegation.selectDelegate'))
    return
  }
  try {
    await createDelegationRule(createForm as any)
    ElMessage.success(t('delegation.createSuccess'))
    createDialogVisible.value = false
    await loadRules()
  } catch {
    // request interceptor already surfaces API errors
  }
}

const handleSuspend = async (row: DelegationRule) => {
  try {
    await suspendDelegationRule(row.id)
    ElMessage.success(t('delegation.suspendSuccess'))
    await loadRules()
  } catch {
    // request interceptor already surfaces API errors
  }
}

const handleResume = async (row: DelegationRule) => {
  try {
    await resumeDelegationRule(row.id)
    ElMessage.success(t('delegation.resumeSuccess'))
    await loadRules()
  } catch {
    // request interceptor already surfaces API errors
  }
}

const handleDelete = async (row: DelegationRule) => {
  await ElMessageBox.confirm(t('delegation.deleteConfirm'), t('common.info'), { type: 'warning' })
  try {
    await deleteDelegationRule(row.id)
    ElMessage.success(t('delegation.deleteSuccess'))
    await loadRules()
  } catch {
    // request interceptor already surfaces API errors
  }
}

onMounted(() => {
  loadRules()
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

.row-actions {
  white-space: nowrap;
  display: flex;
  gap: 4px;
  align-items: center;
  flex-wrap: nowrap;
}

.delegations-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;

  .page-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
    flex-shrink: 0;

    h1 {
      font-size: 24px;
      font-weight: 500;
      color: var(--text-primary);
      margin: 0;
    }
  }

  :deep(.el-tabs) {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  :deep(.el-tabs__content) {
    flex: 1;
    min-height: 0;
  }

  :deep(.el-tab-pane) {
    height: 100%;
  }

  .list-tab-card {
    height: 100%;
    min-height: 0;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  :deep(.el-form-item__label) {
    white-space: nowrap;
  }
}
</style>
