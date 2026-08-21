<template>
  <div
    v-loading="loading"
    class="permission-shared-list"
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
          :data="displayRows"
          stripe
          :fit="gridFits"
          table-layout="fixed"
          style="width: 100%;"
          class="list-data-grid"
          :class="{ 'list-data-grid--fit': gridFits }"
          :span-method="spanMethod(actionColumns)"
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
            <span v-else>{{ emptyText }}</span>
          </template>
          <el-table-column
            v-for="(col, colIndex) in visibleColumns"
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
                :show-move="visibleColumns.length > 1"
                :can-move-left="colIndex > 0"
                :can-move-right="colIndex < visibleColumns.length - 1"
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
              <el-tag
                v-else-if="col.field === 'requestType'"
                :type="getRequestTypeTag(row.requestType)"
                size="small"
              >
                {{ getRequestTypeLabel(row.requestType) }}
              </el-tag>
              <el-tag
                v-else-if="col.field === 'status'"
                :type="getStatusType(row.status)"
                size="small"
              >
                {{ getStatusLabel(row.status) }}
              </el-tag>
              <span v-else-if="col.field === 'targetName'">{{ getTargetName(row) }}</span>
              <span v-else-if="col.field === 'applicantId'">
                {{ row.applicantUsername || row.applicantId || '-' }}
              </span>
              <span v-else-if="col.field === 'submittedByUserId'">
                <template v-if="row.submittedByUserId && row.submittedByUserId !== row.applicantId">
                  {{ row.submittedByUsername || row.submittedByUserId }}
                </template>
                <template v-else>{{ t('permission.selfBeneficiary') }}</template>
              </span>
              <span
                v-else-if="col.field === 'createdAt' || col.field === 'approvedAt'"
                style="white-space: nowrap;"
              >{{ formatDateTime(row[col.field]) }}</span>
              <template v-else>
                {{ row[col.field as keyof PermissionRequestRecord] || '-' }}
              </template>
            </template>
          </el-table-column>
          <el-table-column
            v-if="actionMode !== 'none'"
            :label="t('common.actions')"
            :min-width="actionMode === 'approve' ? 180 : 100"
            fixed="right"
          >
            <template #default="{ row }">
              <template v-if="!isListGroupHeaderRow(row)">
                <div class="row-actions">
                  <el-button
                    v-if="actionMode === 'cancel' && canCancelAsBeneficiary(row)"
                    type="danger"
                    size="small"
                    link
                    @click="emit('cancel', row)"
                  >
                    {{ t('permission.cancel') }}
                  </el-button>
                  <template v-if="actionMode === 'approve'">
                    <el-button
                      type="success"
                      size="small"
                      @click="emit('approve', row)"
                    >
                      {{ t('permission.approve') }}
                    </el-button>
                    <el-button
                      type="danger"
                      size="small"
                      @click="emit('reject', row)"
                    >
                      {{ t('permission.reject') }}
                    </el-button>
                  </template>
                </div>
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
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter, ListColumnMeta } from '@platform-shared/list/columnMeta'
import { permissionApi, type PermissionRequestRecord } from '@/api/permission'
import { usePortalListGrid } from '@/composables/list/usePortalListGrid'
import { searchListFilterUsers } from '@/composables/list/searchListFilterUsers'
import { usePermissionFormatters } from '@/composables/permissions/usePermissionFormatters'

export type PermissionListScope =
  | 'MY_PENDING'
  | 'MY_COMPLETED'
  | 'APPROVALS_PENDING'
  | 'APPROVALS_HISTORY'

const SCOPE_FIELDS: Record<PermissionListScope, string[]> = {
  MY_PENDING: ['requestType', 'targetName', 'applicantId', 'submittedByUserId', 'reason', 'createdAt'],
  MY_COMPLETED: [
    'requestType', 'targetName', 'applicantId', 'submittedByUserId', 'reason',
    'status', 'approverComment', 'createdAt', 'approvedAt',
  ],
  APPROVALS_PENDING: [
    'applicantId', 'submittedByUserId', 'requestType', 'targetName', 'reason', 'createdAt',
  ],
  APPROVALS_HISTORY: [
    'applicantId', 'submittedByUserId', 'requestType', 'targetName', 'status',
    'approverComment', 'approvedAt',
  ],
}

const COL_WIDTHS: Record<string, number> = {
  requestType: 160,
  targetName: 160,
  applicantId: 140,
  submittedByUserId: 140,
  reason: 160,
  status: 110,
  approverComment: 150,
  createdAt: 170,
  approvedAt: 170,
}

const props = withDefaults(defineProps<{
  scope: PermissionListScope
  storageKey: string
  emptyText: string
  actionMode?: 'none' | 'cancel' | 'approve'
  /** When false, skip auto-load (e.g. non-approver). */
  enabled?: boolean
}>(), {
  actionMode: 'none',
  enabled: true,
})

const emit = defineEmits<{
  cancel: [row: PermissionRequestRecord]
  approve: [row: PermissionRequestRecord]
  reject: [row: PermissionRequestRecord]
  total: [n: number]
}>()

const { t } = useI18n()
const loading = ref(false)
const {
  getStatusType,
  getStatusLabel,
  getRequestTypeTag,
  getRequestTypeLabel,
  getTargetName,
  formatDateTime,
  canCancelAsBeneficiary,
} = usePermissionFormatters(t)

const actionColumns = computed(() => (props.actionMode === 'none' ? 0 : 1))

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
} = usePortalListGrid<PermissionRequestRecord>({
  storageKey: props.storageKey,
  extraWidth: props.actionMode === 'approve' ? 180 : props.actionMode === 'cancel' ? 100 : 0,
  defaultWidthOf: (field) => COL_WIDTHS[field] ?? 120,
})

const visibleColumns = computed<ListColumnMeta[]>(() => {
  const allow = new Set(SCOPE_FIELDS[props.scope])
  return displayColumns.value.filter((col) => allow.has(col.field))
})

async function load() {
  if (!props.enabled) {
    pagination.total = 0
    emit('total', 0)
    return
  }
  const seq = beginQuery()
  loading.value = true
  try {
    const res = await permissionApi.queryPermissionRequests({
      ...buildQuery(),
      scope: props.scope,
    })
    if (!isCurrentQuery(seq)) return
    applyPage(res.data, 'permissions/requests/query response is missing its column declaration')
    emit('total', pagination.total)
  } catch (error) {
    if (!isCurrentQuery(seq)) return
    if (!(error as { response?: unknown })?.response) {
      ElMessage.error(error instanceof Error ? error.message : t('permission.loadFailed'))
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

function onGroup(field: string, grouped: boolean) {
  applyGroup(field, grouped)
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

watch(
  () => [props.scope, props.enabled] as const,
  () => {
    pagination.page = 1
    load()
  },
)

onMounted(() => {
  if (props.enabled) load()
})

defineExpose({ reload: load, total: computed(() => pagination.total) })
</script>

<style lang="scss">
@import '@/styles/listDataGrid.scss';
</style>

<style lang="scss" scoped>
.permission-shared-list {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

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
}
</style>
