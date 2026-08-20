<template>
  <div class="completed-tasks-page">
    <div class="page-header">
      <h1>{{ t('task.completedTasks') }}</h1>
    </div>

    <div
      v-loading="loading"
      class="portal-card"
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
            :span-method="spanMethod(0)"
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
              <span v-else>{{ t('task.noCompletedTasks') }}</span>
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
                  @click="viewTask(row)"
                >
                  {{ row.requestId || '-' }}
                </el-link>
                <template v-else-if="col.field === 'currentStepName'">
                  {{ row.currentStepName || row.taskName || '-' }}
                </template>
                <el-tag
                  v-else-if="col.field === 'action' && !row.multiInstanceSubTask"
                  :type="getActionTagType(row.action)"
                  size="small"
                  style="white-space: nowrap;"
                >
                  {{ t(`action.${row.action || 'completed'}`) }}
                </el-tag>
                <span
                  v-else-if="col.field === 'action'"
                >-</span>
                <span
                  v-else-if="col.field === 'createTime' || col.field === 'completedTime'"
                  style="white-space: nowrap;"
                >{{ formatDate(row[col.field]) }}</span>
                <span
                  v-else-if="col.field === 'durationInMillis'"
                  style="white-space: nowrap;"
                >{{ formatDuration(row.durationInMillis) }}</span>
                <template v-else>
                  {{ row[col.field as keyof TaskInfo] || '-' }}
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
        @change="loadTasks"
      />
    </div>

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
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'
import { queryCompletedTasks, type TaskInfo } from '@/api/task'
import { usePortalListGrid } from '@/composables/list/usePortalListGrid'
import { formatDate } from '@/utils/dateFormat'

const COMPLETED_COL_WIDTHS: Record<string, number> = {
  requestId: 160,
  taskName: 170,
  currentStepName: 170,
  processDefinitionName: 170,
  action: 130,
  createTime: 170,
  completedTime: 180,
  durationInMillis: 120,
}

const { t } = useI18n()
const router = useRouter()
const loading = ref(true)

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
} = usePortalListGrid<TaskInfo>({
  storageKey: 'portal-list-layout:completed-tasks',
  defaultWidthOf: (field) => COMPLETED_COL_WIDTHS[field] ?? 120,
})

const loadTasks = async () => {
  const seq = beginQuery()
  loading.value = true
  try {
    const res = await queryCompletedTasks(buildQuery())
    if (!isCurrentQuery(seq)) return
    applyPage(res.data, 'completed/query response is missing its column declaration')
  } catch (error) {
    if (!isCurrentQuery(seq)) return
    if (!(error as { response?: unknown })?.response) {
      ElMessage.error(error instanceof Error ? error.message : t('task.loadFailed'))
    }
  } finally {
    if (isCurrentQuery(seq)) loading.value = false
  }
}

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  loadTasks()
}

function onClearSort() {
  clearSort()
  loadTasks()
}

function onGroup(field: string, grouped: boolean) {
  applyGroup(field, grouped)
  loadTasks()
}

function onClearFilter(field: string) {
  clearFilter(field)
  loadTasks()
}

function onFilterApply(filter: ListColumnFilter) {
  applyFilter(filter)
  loadTasks()
}

function onFilterClear() {
  onClearFilter(filterDialog.field)
}

const viewTask = (task: TaskInfo) => {
  const query: Record<string, string> = {}
  if (task.completedTime) {
    query.snapshotTime = task.completedTime
  }
  if (task.taskName) {
    query.snapshotTaskName = task.taskName
  }
  if (task.taskId) {
    query.snapshotTaskId = task.taskId
  }
  if (task.taskDefinitionKey) {
    query.snapshotTaskDefinitionKey = task.taskDefinitionKey
  }
  if (task.processInstanceId) {
    query.processInstanceId = String(task.processInstanceId)
  }
  if (task.processDefinitionKey) {
    query.processDefinitionKey = String(task.processDefinitionKey)
  }
  router.push({ path: `/tasks/${task.taskId}`, query })
}

const formatDuration = (ms: number | undefined) => {
  if (!ms) return '-'
  const seconds = Math.floor(ms / 1000)
  const minutes = Math.floor(seconds / 60)
  const hours = Math.floor(minutes / 60)
  const days = Math.floor(hours / 24)

  if (days > 0) {
    return `${days}d${hours % 24}h`
  } else if (hours > 0) {
    return `${hours}h${minutes % 60}m`
  } else if (minutes > 0) {
    return `${minutes}m`
  } else {
    return `${seconds}s`
  }
}

const getActionTagType = (action: string): 'success' | 'warning' | 'info' | 'danger' | 'primary' => {
  const typeMap: Record<string, 'success' | 'warning' | 'info' | 'danger' | 'primary'> = {
    approved: 'success',
    rejected: 'danger',
    transferred: 'warning',
    delegated: 'info',
    completed: 'primary',
  }
  return typeMap[action] || 'primary'
}

onMounted(() => {
  loadTasks()
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

.completed-tasks-page {
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
}
</style>
