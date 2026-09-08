import { ref, reactive, computed, onMounted, onActivated } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'
import { queryTodoTasks, batchUrgeTasks, type TaskInfo, type TodoTaskQueryRequest } from '@/api/task'
import { usePortalListGrid } from '@/composables/list/usePortalListGrid'
import { useTaskClaimActions } from '@/composables/tasks/useTaskClaimActions'
import {
  readTodoToolbarQuery,
  writeTodoToolbarQuery,
} from '@/composables/tasks/todoToolbarQuery'
import { useUserPreferenceStore } from '@/stores/userPreference'
import { taskPriorityBand, taskPriorityCssClass } from '@/utils/taskPriority'
import { usePendingTaskStore } from '@/stores/pendingTask'

export const CLAIM_ACTION_WIDTH = 280

const TODO_VISIBLE_FIELDS = [
  'requestId',
  'functionUnitCode',
  'taskName',
  'assignmentType',
  'assigneeName',
  'createTime',
] as const

export function useTodoTasksPage() {
  const pendingTaskStore = usePendingTaskStore()
  const preferenceStore = useUserPreferenceStore()
  const { t } = useI18n()
  const router = useRouter()
  const loading = ref(true)
  const selectedTasks = ref<TaskInfo[]>([])
  const storedToolbarQuery = readTodoToolbarQuery()
  const filterForm = reactive({
    assignmentTypes: storedToolbarQuery.assignmentTypes,
    priorities: storedToolbarQuery.priorities,
    keyword: storedToolbarQuery.keyword,
  })

  const grid = usePortalListGrid<TaskInfo>({
    storageKey: 'portal-list-layout:todo-tasks-v3',
    extraWidth: 50 + CLAIM_ACTION_WIDTH,
    visibleFields: TODO_VISIBLE_FIELDS,
  })

  const actionDialogVisible = ref(false)
  const actionDialogTitle = ref('')
  const currentAction = ref('')
  const actionForm = reactive({
    targetUserId: '',
    reason: '',
  })
  const actingTaskId = ref<string | null>(null)
  const claimAllBusy = ref(false)

  const loadTasks = async () => {
    const seq = grid.beginQuery()
    loading.value = true
    try {
      const res = await queryTodoTasks(todoQueryBody())
      if (!grid.isCurrentQuery(seq)) return
      grid.applyPage(res.data, 'todo/query response is missing its column declaration')
      pendingTaskStore.syncCountFromListTotal(res.data.totalElements)
    } catch (error) {
      if (!grid.isCurrentQuery(seq)) return
      if (!(error as { response?: unknown })?.response) {
        ElMessage.error(error instanceof Error ? error.message : t('task.loadFailed'))
      }
    } finally {
      if (grid.isCurrentQuery(seq)) loading.value = false
    }
  }

  const { claim, unclaim, forceUnclaim, reassign, claimAll, unclaimAll, claimSelected, unclaimSelected, prepareTodoOpen } =
    useTaskClaimActions({
      reload: loadTasks,
      actingTaskId,
      submitting: claimAllBusy,
    })

  function todoQueryBody(): TodoTaskQueryRequest {
    const body: TodoTaskQueryRequest = { ...grid.buildQuery() }
    const keyword = filterForm.keyword.trim()
    if (keyword) body.keyword = keyword
    if (filterForm.assignmentTypes.length > 0) body.assignmentTypes = filterForm.assignmentTypes
    if (filterForm.priorities.length > 0) body.priorities = filterForm.priorities
    return body
  }

  function persistToolbarQuery() {
    writeTodoToolbarQuery({
      keyword: filterForm.keyword,
      assignmentTypes: filterForm.assignmentTypes,
      priorities: filterForm.priorities,
    })
  }

  function handleSearch() {
    grid.pagination.page = 1
    persistToolbarQuery()
    loadTasks()
  }

  function handleReset() {
    filterForm.assignmentTypes = []
    filterForm.priorities = []
    filterForm.keyword = ''
    for (const field of Object.keys(grid.columnFilters.value)) {
      grid.clearFilter(field)
    }
    grid.clearSort()
    handleSearch()
  }

  function onSort(field: string, direction: 'ASC' | 'DESC') {
    grid.applySort(field, direction)
    loadTasks()
  }

  function onClearSort() {
    grid.clearSort()
    loadTasks()
  }

  function onClearFilter(field: string) {
    grid.clearFilter(field)
    loadTasks()
  }

  function onFilterApply(filter: ListColumnFilter) {
    grid.applyFilter(filter)
    loadTasks()
  }

  function onFilterClear() {
    onClearFilter(grid.filterDialog.field)
  }

  const handleSelectionChange = (selection: TaskInfo[]) => {
    selectedTasks.value = selection
  }

  const selectedClaimableIds = computed(() =>
    selectedTasks.value.flatMap((task) => (task.claimable && task.taskId ? [task.taskId] : [])),
  )

  const selectedHeldIds = computed(() =>
    selectedTasks.value.flatMap((task) =>
      task.claimedByCurrentUser && task.taskId ? [task.taskId] : []),
  )

  function handleClaimSelected() {
    return claimSelected(selectedClaimableIds.value, selectedTasks.value.length)
  }

  function handleUnclaimSelected() {
    return unclaimSelected(selectedHeldIds.value, selectedTasks.value.length)
  }

  const viewTask = async (task: TaskInfo) => {
    persistToolbarQuery()
    await preferenceStore.load()
    try {
      await prepareTodoOpen(task, preferenceStore.autoClaimOnOpen)
    } catch {
      return
    }
    router.push(`/tasks/${task.taskId}`)
  }

  function handleClaim(task: TaskInfo) {
    return claim(task.taskId)
  }

  function handleUnclaim(task: TaskInfo) {
    return unclaim(task.taskId, task.assignmentType, task.assignee)
  }

  function handleForceUnclaim(task: TaskInfo) {
    return forceUnclaim(task.taskId, task.assignmentType, task.assignee, task.assigneeName)
  }

  const reassignDialogVisible = ref(false)
  const reassignTask = ref<TaskInfo | null>(null)

  function handleReassign(task: TaskInfo) {
    reassignTask.value = task
    reassignDialogVisible.value = true
  }

  function confirmReassign(targetUserId: string) {
    const task = reassignTask.value
    if (!task?.taskId) {
      return Promise.resolve()
    }
    return reassign(task.taskId, targetUserId).then(() => {
      reassignDialogVisible.value = false
      reassignTask.value = null
    })
  }

  function onAutoClaimChange(value: string | number | boolean) {
    return preferenceStore.setAutoClaimOnOpen(value === true)
  }

  function onAutoPreviewChange(value: string | number | boolean) {
    return preferenceStore.setAutoPreviewOnOpen(value === true)
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

  const getPriorityLabel = (priority: string | number | undefined): string => {
    return t(`task.${taskPriorityBand(priority).toLowerCase()}`)
  }

  const getPriorityClass = (priority: string | number | undefined): string => {
    return taskPriorityCssClass(priority)
  }

  let skipActivateReload = true
  onMounted(() => {
    void preferenceStore.load()
    loadTasks()
  })
  onActivated(() => {
    if (skipActivateReload) {
      skipActivateReload = false
      return
    }
    loadTasks()
  })

  return {
    t,
    preferenceStore,
    loading,
    selectedTasks,
    selectedClaimableIds,
    selectedHeldIds,
    filterForm,
    ...grid,
    actionDialogVisible,
    actionDialogTitle,
    actionForm,
    actingTaskId,
    claimAllBusy,
    loadTasks,
    handleSearch,
    handleReset,
    onSort,
    onClearSort,
    onClearFilter,
    onFilterApply,
    onFilterClear,
    handleSelectionChange,
    viewTask,
    handleClaim,
    handleUnclaim,
    handleForceUnclaim,
    handleReassign,
    confirmReassign,
    reassignDialogVisible,
    reassignTask,
    handleClaimAll: claimAll,
    handleUnclaimAll: unclaimAll,
    handleClaimSelected,
    handleUnclaimSelected,
    onAutoClaimChange,
    onAutoPreviewChange,
    handleBatchUrge,
    submitAction,
    getPriorityLabel,
    getPriorityClass,
  }
}
