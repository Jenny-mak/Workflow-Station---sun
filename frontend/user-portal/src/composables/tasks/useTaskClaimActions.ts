import { type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { claimBatch, claimTask, reassignTask, unclaimBatch, unclaimTask } from '@/api/task'
import { resolveUserFacingHttpMessage } from '@/utils/httpErrorMessage'

type BatchSlice = {
  claimed: number
  skipped: number
  failed: number
  remaining: number
  attemptedTaskIds?: string[]
}

/**
 * Claim / Unclaim / Claim all / Unclaim all for BU Role pool tasks. Shared by To Do and task detail.
 * Callers pass skipGlobalErrorHandler APIs so this composable owns the toast.
 */
export function useTaskClaimActions(options: {
  reload: () => Promise<void>
  actingTaskId?: Ref<string | null>
  submitting?: Ref<boolean>
}) {
  const { t } = useI18n()

  async function run(taskId: string, action: () => Promise<unknown>, successKey: string): Promise<void> {
    if (options.actingTaskId) {
      options.actingTaskId.value = taskId
    }
    if (options.submitting) {
      options.submitting.value = true
    }
    try {
      await action()
      ElMessage.success(t(successKey))
      await options.reload()
    } catch (error) {
      ElMessage.error(resolveUserFacingHttpMessage(error, (key) => t(key)))
      await options.reload()
    } finally {
      if (options.actingTaskId) {
        options.actingTaskId.value = null
      }
      if (options.submitting) {
        options.submitting.value = false
      }
    }
  }

  function claim(taskId: string): Promise<void> {
    if (typeof taskId !== 'string' || !taskId.trim()) {
      ElMessage.error(t('task.notFound'))
      return Promise.resolve()
    }
    return run(taskId, () => claimTask(taskId), 'task.claimSuccess')
  }

  function unclaim(taskId: string, assignmentType: string, assignee: string): Promise<void> {
    return run(
      taskId,
      () => unclaimTask(taskId, assignmentType, assignee),
      'task.unclaimSuccess',
    )
  }

  async function forceUnclaim(
    taskId: string,
    assignmentType: string,
    assignee: string,
    holderName?: string,
  ): Promise<void> {
    try {
      await ElMessageBox.confirm(
        t('task.forceUnclaimConfirm', { user: holderName || assignee || '-' }),
        t('task.forceUnclaim'),
        { type: 'warning' },
      )
    } catch {
      return
    }
    return run(
      taskId,
      () => unclaimTask(taskId, assignmentType, assignee),
      'task.forceUnclaimSuccess',
    )
  }

  function reassign(taskId: string, targetUserId: string): Promise<void> {
    if (typeof taskId !== 'string' || !taskId.trim() || !targetUserId.trim()) {
      ElMessage.error(t('task.notFound'))
      return Promise.resolve()
    }
    return run(taskId, () => reassignTask(taskId, targetUserId.trim()), 'task.reassignSuccess')
  }

  async function runConfirmedBatch(
    confirmKey: string,
    titleKey: string,
    emptyKey: string,
    doneKey: string,
    post: (exclude: string[]) => Promise<{ data?: BatchSlice }>,
    confirmParams?: Record<string, number>,
  ): Promise<void> {
    try {
      await ElMessageBox.confirm(
        confirmParams ? t(confirmKey, confirmParams) : t(confirmKey),
        t(titleKey),
        { type: 'warning' },
      )
    } catch {
      return
    }
    if (options.submitting) {
      options.submitting.value = true
    }
    try {
      const totals = await runBatchLoop(post)
      if (totals.claimed === 0 && totals.failed === 0) {
        ElMessage.success(t(emptyKey))
      } else {
        ElMessage.success(t(doneKey, totals))
      }
      await options.reload()
    } catch (error) {
      ElMessage.error(resolveUserFacingHttpMessage(error, (key) => t(key)))
      await options.reload()
    } finally {
      if (options.submitting) {
        options.submitting.value = false
      }
    }
  }

  function claimAll(): Promise<void> {
    return runConfirmedBatch(
      'task.claimAllConfirm',
      'task.claimAll',
      'task.claimAllEmpty',
      'task.claimAllDone',
      (exclude) => claimBatch({ excludeTaskIds: exclude }) as Promise<{ data?: BatchSlice }>,
    )
  }

  function unclaimAll(): Promise<void> {
    return runConfirmedBatch(
      'task.unclaimAllConfirm',
      'task.unclaimAll',
      'task.unclaimAllEmpty',
      'task.unclaimAllDone',
      (exclude) => unclaimBatch({ excludeTaskIds: exclude }) as Promise<{ data?: BatchSlice }>,
    )
  }

  function runSelectedBatch(
    includeTaskIds: string[],
    selectedCount: number,
    emptyKey: string,
    confirmKey: string,
    titleKey: string,
    doneKey: string,
    post: (ids: string[], exclude: string[]) => Promise<{ data?: BatchSlice }>,
  ): Promise<void> {
    const ids = includeTaskIds.filter((id) => typeof id === 'string' && id.trim() !== '')
    if (ids.length === 0) {
      ElMessage.success(t(emptyKey))
      return Promise.resolve()
    }
    return runConfirmedBatch(
      confirmKey,
      titleKey,
      emptyKey,
      doneKey,
      (exclude) => post(ids, exclude),
      { eligible: ids.length, selected: selectedCount },
    )
  }

  function claimSelected(includeTaskIds: string[], selectedCount: number): Promise<void> {
    return runSelectedBatch(
      includeTaskIds,
      selectedCount,
      'task.claimSelectedEmpty',
      'task.claimSelectedConfirm',
      'task.claim',
      'task.claimAllDone',
      (ids, exclude) =>
        claimBatch({ excludeTaskIds: exclude, includeTaskIds: ids }) as Promise<{ data?: BatchSlice }>,
    )
  }

  function unclaimSelected(includeTaskIds: string[], selectedCount: number): Promise<void> {
    return runSelectedBatch(
      includeTaskIds,
      selectedCount,
      'task.unclaimSelectedEmpty',
      'task.unclaimSelectedConfirm',
      'task.unclaim',
      'task.unclaimAllDone',
      (ids, exclude) =>
        unclaimBatch({ excludeTaskIds: exclude, includeTaskIds: ids }) as Promise<{ data?: BatchSlice }>,
    )
  }

  /**
   * To Do Request-ID click: optionally claim a free pool row, then the caller navigates.
   * No success toast and no list reload (the user is leaving the list).
   */
  async function prepareTodoOpen(
    task: { taskId: string; claimable?: boolean },
    autoClaim: boolean,
  ): Promise<void> {
    if (!autoClaim || !task.claimable) {
      return
    }
    if (typeof task.taskId !== 'string' || !task.taskId.trim()) {
      return
    }
    try {
      await claimTask(task.taskId)
    } catch (error) {
      ElMessage.error(resolveUserFacingHttpMessage(error, (key) => t(key)))
      throw error
    }
  }

  return {
    claim,
    unclaim,
    forceUnclaim,
    reassign,
    claimAll,
    unclaimAll,
    claimSelected,
    unclaimSelected,
    prepareTodoOpen,
  }
}

async function runBatchLoop(
  post: (exclude: string[]) => Promise<{ data?: BatchSlice }>,
): Promise<{ claimed: number; skipped: number; failed: number }> {
  const exclude: string[] = []
  let claimed = 0
  let skipped = 0
  let failed = 0
  for (;;) {
    const res = await post([...exclude])
    const data = res.data
    const attempted = data?.attemptedTaskIds
    if (!data || !Array.isArray(attempted) || attempted.length === 0) {
      break
    }
    exclude.push(...attempted)
    claimed += data.claimed
    skipped += data.skipped
    failed += data.failed
    if (data.remaining <= 0) {
      break
    }
  }
  return { claimed, skipped, failed }
}
