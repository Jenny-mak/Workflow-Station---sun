<template>
  <div class="tasks-page">
    <div class="page-header">
      <h1>{{ t('task.title') }}</h1>
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
            :span-method="spanMethod(1)"
            :row-class-name="rowClassName"
            :selectable="(row: object) => !isListGroupHeaderRow(row)"
            @selection-change="handleSelectionChange"
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
              <span v-else>{{ t('task.noTasks') }}</span>
            </template>
            <el-table-column
              type="selection"
              width="50"
              fixed
            />
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
                <span
                  v-else-if="col.field === 'assignmentType'"
                  class="assignment-type"
                  :class="getAssignmentClass(row)"
                >
                  {{ t(`task.${getAssignmentKey(row)}`) }}
                </span>
                <span
                  v-else-if="col.field === 'priority'"
                  class="priority"
                  :class="getPriorityClass(row.priority)"
                >
                  {{ getPriorityLabel(row.priority) }}
                </span>
                <span
                  v-else-if="col.field === 'createTime' || col.field === 'dueDate'"
                  style="white-space: nowrap;"
                  :class="{ overdue: col.field === 'dueDate' && row.isOverdue }"
                >
                  {{ formatDate(row[col.field]) }}
                  <el-tag
                    v-if="col.field === 'dueDate' && row.isOverdue"
                    type="danger"
                    size="small"
                    style="margin-left: 4px;"
                  >
                    {{ t('task.overdue') }}
                  </el-tag>
                </span>
                <template v-else>
                  {{ row[col.field as keyof TaskInfo] || '-' }}
                </template>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>

      <div
        v-if="selectedTasks.length > 0"
        class="batch-actions"
      >
        <span>{{ t('task.selected', { count: selectedTasks.length }) }}</span>
        <el-button
          type="warning"
          size="small"
          @click="handleBatchUrge"
        >
          {{ t('task.batchUrge') }}
        </el-button>
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

    <el-dialog
      v-model="actionDialogVisible"
      :title="actionDialogTitle"
      width="500px"
      class="task-action-form"
    >
      <el-form
        :model="actionForm"
        label-width="100px"
      >
        <el-form-item
          v-if="currentAction !== 'urge' && currentAction !== 'batchUrge'"
          :label="t('task.targetUser')"
          required
        >
          <el-input
            v-model="actionForm.targetUserId"
            :placeholder="t('task.enterUserId')"
          />
        </el-form-item>
        <el-form-item :label="t('common.reason')">
          <el-input
            v-model="actionForm.reason"
            type="textarea"
            :rows="3"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="actionDialogVisible = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          @click="submitAction"
        >
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'
import { queryTodoTasks, batchUrgeTasks, type TaskInfo } from '@/api/task'
import { usePortalListGrid } from '@/composables/list/usePortalListGrid'
import { formatDate } from '@/utils/dateFormat'
import { usePendingTaskStore } from '@/stores/pendingTask'

const TODO_COL_WIDTHS: Record<string, number> = {
  requestId: 140,
  taskName: 160,
  currentStepName: 160,
  processDefinitionName: 160,
  assignmentType: 130,
  initiatorName: 120,
  priority: 100,
  createTime: 170,
  dueDate: 180,
}

const pendingTaskStore = usePendingTaskStore()
const { t } = useI18n()
const router = useRouter()
const loading = ref(true)
const selectedTasks = ref<TaskInfo[]>([])

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
  storageKey: 'portal-list-layout:todo-tasks',
  extraWidth: 50,
  defaultWidthOf: (field) => TODO_COL_WIDTHS[field] ?? 120,
})

const actionDialogVisible = ref(false)
const actionDialogTitle = ref('')
const currentAction = ref('')
const actionForm = reactive({
  targetUserId: '',
  reason: '',
})

const loadTasks = async () => {
  const seq = beginQuery()
  loading.value = true
  try {
    const res = await queryTodoTasks(buildQuery())
    if (!isCurrentQuery(seq)) return
    applyPage(res.data, 'todo/query response is missing its column declaration')
    pendingTaskStore.syncCountFromListTotal(res.data.totalElements)
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

const handleSelectionChange = (selection: TaskInfo[]) => {
  selectedTasks.value = selection.filter((row) => !isListGroupHeaderRow(row))
}

const viewTask = (task: TaskInfo) => {
  router.push(`/tasks/${task.taskId}`)
}

const handleBatchUrge = () => {
  currentAction.value = 'batchUrge'
  actionDialogTitle.value = t('task.batchUrge')
  actionForm.reason = ''
  actionDialogVisible.value = true
}

const submitAction = async () => {
  try {
    if (currentAction.value === 'batchUrge') {
      const taskIds = selectedTasks.value.map((task) => task.taskId)
      await batchUrgeTasks(taskIds, actionForm.reason)
      ElMessage.success(t('common.success'))
    }
    actionDialogVisible.value = false
    loadTasks()
  } catch (error) {
    const msg =
      (error as { response?: { data?: { message?: string } }; message?: string })?.response?.data?.message
      ?? (error as { message?: string })?.message
      ?? t('common.error')
    ElMessage.error(msg)
  }
}

const getAssignmentKey = (task: TaskInfo) => {
  const bpmn = task.bpmnAssigneeType?.trim().toUpperCase()
  if (bpmn === 'INITIATOR' || bpmn === 'PROCESS_INITIATOR') {
    return 'processInitiator'
  }
  if (bpmn === 'FIXED_BU_ROLE') {
    return 'fixedBuRole'
  }
  if (bpmn === 'BU_ROLE') {
    return 'buRole'
  }
  const type = task.assignmentType
  const map: Record<string, string> = {
    USER: 'user',
    VIRTUAL_GROUP: 'virtualGroup',
    DEPT_ROLE: 'deptRole',
    DELEGATED: 'delegated',
    CANDIDATE_USERS: 'candidateUsers',
  }
  return map[type] || 'user'
}

const getAssignmentClass = (task: TaskInfo) => {
  const key = getAssignmentKey(task)
  const map: Record<string, string> = {
    user: 'user',
    processInitiator: 'user',
    virtualGroup: 'virtual-group',
    deptRole: 'dept-role',
    delegated: 'delegated',
    candidateUsers: 'virtual-group',
    fixedBuRole: 'virtual-group',
    buRole: 'virtual-group',
  }
  return map[key] || 'user'
}

const getPriorityLabel = (priority: string | number | undefined): string => {
  if (!priority) return t('task.normal')
  if (typeof priority === 'string') {
    const upperPriority = priority.toUpperCase()
    if (['URGENT', 'HIGH', 'NORMAL', 'LOW'].includes(upperPriority)) {
      return t(`task.${upperPriority.toLowerCase()}`)
    }
  }
  if (typeof priority === 'number') {
    if (priority >= 75) return t('task.urgent')
    if (priority >= 50) return t('task.high')
    if (priority >= 25) return t('task.normal')
    return t('task.low')
  }
  return t('task.normal')
}

const getPriorityClass = (priority: string | number | undefined): string => {
  if (!priority) return 'normal'
  if (typeof priority === 'string') {
    const upperPriority = priority.toUpperCase()
    if (['URGENT', 'HIGH', 'NORMAL', 'LOW'].includes(upperPriority)) {
      return upperPriority.toLowerCase()
    }
  }
  if (typeof priority === 'number') {
    if (priority >= 75) return 'urgent'
    if (priority >= 50) return 'high'
    if (priority >= 25) return 'normal'
    return 'low'
  }
  return 'normal'
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

.tasks-page {
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

  .batch-actions {
    display: flex;
    align-items: center;
    gap: 16px;
    margin-top: 12px;
    padding: 12px 16px;
    background: #f5f7fa;
    border-radius: 4px;
    flex-shrink: 0;

    span {
      color: var(--text-secondary);
    }
  }

  .overdue {
    color: var(--error-red);
  }
}
</style>

<style lang="scss">
.task-action-form .el-form-item__label {
  white-space: nowrap;
}
</style>
