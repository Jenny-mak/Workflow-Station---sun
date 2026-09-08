import { describe, expect, it, vi, beforeEach } from 'vitest'
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
// ElMessageBox.confirm 的声明返回 MessageBoxData，但确认时实际 resolve 的就是
// 字符串 'confirm'（被测代码正是按这个值走分支）。这里断言的是运行时事实，
// 不是把类型糊过去 —— 改成别的值会让用例失去意义。
import type { MessageBoxData } from 'element-plus'

const { claimTask, unclaimTask, claimBatch, unclaimBatch, reassignTask } = vi.hoisted(() => ({
  claimTask: vi.fn(),
  unclaimTask: vi.fn(),
  claimBatch: vi.fn(),
  unclaimBatch: vi.fn(),
  reassignTask: vi.fn(),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

vi.mock('@/api/task', () => ({
  claimTask: (...args: unknown[]) => claimTask(...args),
  unclaimTask: (...args: unknown[]) => unclaimTask(...args),
  claimBatch: (...args: unknown[]) => claimBatch(...args),
  unclaimBatch: (...args: unknown[]) => unclaimBatch(...args),
  reassignTask: (...args: unknown[]) => reassignTask(...args),
}))

import { useTaskClaimActions } from '../useTaskClaimActions'

describe('useTaskClaimActions', () => {
  beforeEach(() => {
    vi.mocked(ElMessage.success).mockReset()
    vi.mocked(ElMessage.error).mockReset()
    claimTask.mockReset()
    unclaimTask.mockReset()
    claimBatch.mockReset()
    unclaimBatch.mockReset()
    reassignTask.mockReset()
    vi.mocked(ElMessageBox.confirm).mockReset()
  })

  it('reloads after a successful claim', async () => {
    claimTask.mockResolvedValue({})
    const reload = vi.fn().mockResolvedValue(undefined)
    const submitting = ref(false)
    const { claim } = useTaskClaimActions({ reload, submitting })

    await claim('task-1')

    expect(claimTask).toHaveBeenCalledWith('task-1')
    expect(ElMessage.success).toHaveBeenCalledWith('task.claimSuccess')
    expect(reload).toHaveBeenCalledTimes(1)
    expect(submitting.value).toBe(false)
  })

  it('does not call the API when claim is invoked without a task id (detail banner emit)', async () => {
    const reload = vi.fn().mockResolvedValue(undefined)
    const { claim } = useTaskClaimActions({ reload })

    await claim(undefined as unknown as string)

    expect(claimTask).not.toHaveBeenCalled()
    expect(ElMessage.error).toHaveBeenCalledWith('task.notFound')
    expect(reload).not.toHaveBeenCalled()
  })

  it('toasts the body message and still reloads on 403', async () => {
    claimTask.mockRejectedValue({
      response: { status: 403, data: { message: 'Already claimed' } },
      message: 'Request failed with status code 403',
    })
    const reload = vi.fn().mockResolvedValue(undefined)
    const { claim } = useTaskClaimActions({ reload })

    await claim('task-1')

    expect(ElMessage.error).toHaveBeenCalledWith('Already claimed')
    expect(ElMessage.success).not.toHaveBeenCalled()
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('asks for confirmation before force-unclaim and skips the API when cancelled', async () => {
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
    const reload = vi.fn().mockResolvedValue(undefined)
    const { forceUnclaim } = useTaskClaimActions({ reload })

    await forceUnclaim('task-1', 'BU_ROLE', 'alice', 'Alice Chen')

    expect(unclaimTask).not.toHaveBeenCalled()
    expect(reload).not.toHaveBeenCalled()
  })

  it('force-unclaims after confirm', async () => {
    vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm' as unknown as MessageBoxData)
    unclaimTask.mockResolvedValue({})
    const reload = vi.fn().mockResolvedValue(undefined)
    const { forceUnclaim } = useTaskClaimActions({ reload })

    await forceUnclaim('task-1', 'BU_ROLE', 'alice', 'Alice Chen')

    expect(unclaimTask).toHaveBeenCalledWith('task-1', 'BU_ROLE', 'alice')
    expect(ElMessage.success).toHaveBeenCalledWith('task.forceUnclaimSuccess')
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('claimAll confirms once then loops batches until remaining is 0', async () => {
    vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm' as unknown as MessageBoxData)
    claimBatch
      .mockResolvedValueOnce({
        data: { claimed: 100, skipped: 0, failed: 0, remaining: 20, attemptedTaskIds: ['a'] },
      })
      .mockResolvedValueOnce({
        data: { claimed: 20, skipped: 1, failed: 0, remaining: 0, attemptedTaskIds: ['b'] },
      })
    const reload = vi.fn().mockResolvedValue(undefined)
    const { claimAll } = useTaskClaimActions({ reload })

    await claimAll()

    expect(claimBatch).toHaveBeenCalledTimes(2)
    expect(claimBatch.mock.calls[1][0]).toEqual({ excludeTaskIds: ['a'] })
    expect(ElMessage.success).toHaveBeenCalledWith('task.claimAllDone')
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('claimAll stops when the user cancels confirm', async () => {
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
    const reload = vi.fn().mockResolvedValue(undefined)
    const { claimAll } = useTaskClaimActions({ reload })

    await claimAll()

    expect(claimBatch).not.toHaveBeenCalled()
    expect(reload).not.toHaveBeenCalled()
  })

  it('unclaimAll confirms once then loops batches until remaining is 0', async () => {
    vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm' as unknown as MessageBoxData)
    unclaimBatch
      .mockResolvedValueOnce({
        data: { claimed: 100, skipped: 0, failed: 0, remaining: 5, attemptedTaskIds: ['h1'] },
      })
      .mockResolvedValueOnce({
        data: { claimed: 5, skipped: 0, failed: 0, remaining: 0, attemptedTaskIds: ['h2'] },
      })
    const reload = vi.fn().mockResolvedValue(undefined)
    const { unclaimAll } = useTaskClaimActions({ reload })

    await unclaimAll()

    expect(unclaimBatch).toHaveBeenCalledTimes(2)
    expect(unclaimBatch.mock.calls[1][0]).toEqual({ excludeTaskIds: ['h1'] })
    expect(ElMessage.success).toHaveBeenCalledWith('task.unclaimAllDone')
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('claimSelected confirms with counts and sends includeTaskIds', async () => {
    vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm')
    claimBatch.mockResolvedValueOnce({
      data: { claimed: 2, skipped: 0, failed: 0, remaining: 0, attemptedTaskIds: ['a', 'b'] },
    })
    const reload = vi.fn().mockResolvedValue(undefined)
    const { claimSelected } = useTaskClaimActions({ reload })

    await claimSelected(['a', 'b'], 17)

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      'task.claimSelectedConfirm',
      'task.claim',
      expect.objectContaining({ type: 'warning' }),
    )
    expect(claimBatch).toHaveBeenCalledWith({
      excludeTaskIds: [],
      includeTaskIds: ['a', 'b'],
    })
    expect(ElMessage.success).toHaveBeenCalledWith('task.claimAllDone')
  })

  it('claimSelected does not call the API when there are no eligible ids', async () => {
    const reload = vi.fn().mockResolvedValue(undefined)
    const { claimSelected } = useTaskClaimActions({ reload })

    await claimSelected([], 17)

    expect(ElMessageBox.confirm).not.toHaveBeenCalled()
    expect(claimBatch).not.toHaveBeenCalled()
    expect(ElMessage.success).toHaveBeenCalledWith('task.claimSelectedEmpty')
  })

  it('unclaimSelected sends includeTaskIds for held rows only', async () => {
    vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm')
    unclaimBatch.mockResolvedValueOnce({
      data: { claimed: 1, skipped: 0, failed: 0, remaining: 0, attemptedTaskIds: ['mine'] },
    })
    const reload = vi.fn().mockResolvedValue(undefined)
    const { unclaimSelected } = useTaskClaimActions({ reload })

    await unclaimSelected(['mine'], 17)

    expect(unclaimBatch).toHaveBeenCalledWith({
      excludeTaskIds: [],
      includeTaskIds: ['mine'],
    })
    expect(ElMessage.success).toHaveBeenCalledWith('task.unclaimAllDone')
  })

  it('prepareTodoOpen claims without success toast or reload when auto-claim is on', async () => {
    claimTask.mockResolvedValue({})
    const reload = vi.fn().mockResolvedValue(undefined)
    const { prepareTodoOpen } = useTaskClaimActions({ reload })

    await prepareTodoOpen({ taskId: 't1', claimable: true }, true)

    expect(claimTask).toHaveBeenCalledWith('t1')
    expect(ElMessage.success).not.toHaveBeenCalled()
    expect(reload).not.toHaveBeenCalled()
  })

  it('prepareTodoOpen skips claim when auto-claim is off or row is not claimable', async () => {
    const reload = vi.fn().mockResolvedValue(undefined)
    const { prepareTodoOpen } = useTaskClaimActions({ reload })

    await prepareTodoOpen({ taskId: 't1', claimable: true }, false)
    await prepareTodoOpen({ taskId: 't2', claimable: false }, true)

    expect(claimTask).not.toHaveBeenCalled()
  })

  it('prepareTodoOpen toasts on claim error and rethrows so navigation stops', async () => {
    claimTask.mockRejectedValue({
      response: { status: 409, data: { message: 'Already claimed' } },
      message: 'Request failed with status code 409',
    })
    const reload = vi.fn().mockResolvedValue(undefined)
    const { prepareTodoOpen } = useTaskClaimActions({ reload })

    await expect(prepareTodoOpen({ taskId: 't1', claimable: true }, true)).rejects.toBeTruthy()

    expect(ElMessage.error).toHaveBeenCalledWith('Already claimed')
    expect(reload).not.toHaveBeenCalled()
  })

  it('reassigns after the dialog confirms a target user', async () => {
    reassignTask.mockResolvedValue({})
    const reload = vi.fn().mockResolvedValue(undefined)
    const { reassign } = useTaskClaimActions({ reload })

    await reassign('task-1', 'bob')

    expect(reassignTask).toHaveBeenCalledWith('task-1', 'bob')
    expect(ElMessage.success).toHaveBeenCalledWith('task.reassignSuccess')
    expect(reload).toHaveBeenCalledTimes(1)
  })
})
