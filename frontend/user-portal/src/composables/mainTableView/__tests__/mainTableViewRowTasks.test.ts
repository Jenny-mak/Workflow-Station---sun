import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { MainTableViewDataRow } from '@/api/mainTableView'

const findMyTaskRefs = vi.fn()
vi.mock('@/api/task', () => ({
  findMyTaskRefs: (...args: unknown[]) => findMyTaskRefs(...args),
}))

const { useMainTableViewRowTasks } = await import('../useMainTableViewRowTasks')

function row(processInstanceId: string): MainTableViewDataRow {
  return { rowKey: processInstanceId, processInstanceId, values: {} }
}

describe('useMainTableViewRowTasks', () => {
  beforeEach(() => {
    findMyTaskRefs.mockReset()
  })

  /**
   * The whole point of the batch endpoint: a page of rows costs one request. A per-row lookup
   * would put one round trip behind every marker.
   */
  it('asks about every row on the page in a single call', async () => {
    findMyTaskRefs.mockResolvedValue({ data: {} })
    const rows = ref([row('pi-1'), row('pi-2'), row('pi-3')])
    const { loadRowTasks } = useMainTableViewRowTasks(rows)

    await loadRowTasks(true, () => {})

    expect(findMyTaskRefs).toHaveBeenCalledTimes(1)
    expect(findMyTaskRefs).toHaveBeenCalledWith(['pi-1', 'pi-2', 'pi-3'])
  })

  it('sends each process instance once even when the grid repeats it', async () => {
    findMyTaskRefs.mockResolvedValue({ data: {} })
    const rows = ref([row('pi-1'), row('pi-1'), row('pi-2')])
    const { loadRowTasks } = useMainTableViewRowTasks(rows)

    await loadRowTasks(true, () => {})

    expect(findMyTaskRefs).toHaveBeenCalledWith(['pi-1', 'pi-2'])
  })

  it('exposes a row task only for the instance it belongs to', async () => {
    findMyTaskRefs.mockResolvedValue({
      data: { 'pi-2': [{ taskId: 't-9', taskName: 'Approve' }] },
    })
    const rows = ref([row('pi-1'), row('pi-2')])
    const { loadRowTasks, rowTasksOf } = useMainTableViewRowTasks(rows)

    await loadRowTasks(true, () => {})

    expect(rowTasksOf(rows.value[0])).toEqual([])
    expect(rowTasksOf(rows.value[1])).toEqual([{ taskId: 't-9', taskName: 'Approve' }])
  })

  it('keeps every task of a row so the caller can offer a choice', async () => {
    findMyTaskRefs.mockResolvedValue({
      data: { 'pi-1': [{ taskId: 't-1', taskName: 'Legal' }, { taskId: 't-2', taskName: 'Finance' }] },
    })
    const rows = ref([row('pi-1')])
    const { loadRowTasks, rowTasksOf } = useMainTableViewRowTasks(rows)

    await loadRowTasks(true, () => {})

    expect(rowTasksOf(rows.value[0]).map(t => t.taskId)).toEqual(['t-1', 't-2'])
  })

  /** SUB views have many rows per request, so no single task is "this row's". */
  it('skips the lookup entirely when markers do not apply to the view', async () => {
    const rows = ref([row('pi-1')])
    const { loadRowTasks, rowTasksOf } = useMainTableViewRowTasks(rows)

    await loadRowTasks(false, () => {})

    expect(findMyTaskRefs).not.toHaveBeenCalled()
    expect(rowTasksOf(rows.value[0])).toEqual([])
  })

  it('reports a failed lookup instead of leaving markers that no longer hold', async () => {
    findMyTaskRefs.mockResolvedValueOnce({ data: { 'pi-1': [{ taskId: 't-1', taskName: 'Approve' }] } })
    const rows = ref([row('pi-1')])
    const { loadRowTasks, rowTasksOf } = useMainTableViewRowTasks(rows)
    await loadRowTasks(true, () => {})
    expect(rowTasksOf(rows.value[0])).toHaveLength(1)

    findMyTaskRefs.mockRejectedValueOnce(new Error('boom'))
    const onFailure = vi.fn()
    await loadRowTasks(true, onFailure)

    expect(onFailure).toHaveBeenCalledTimes(1)
    expect(rowTasksOf(rows.value[0])).toEqual([])
  })

  /**
   * Switching view or page reissues the lookup. A slow earlier reply must not repaint markers
   * over rows it was never about — that would point the icon at another request's task.
   */
  it('ignores a reply that a newer load has superseded', async () => {
    let releaseFirst: (value: unknown) => void = () => {}
    findMyTaskRefs.mockReturnValueOnce(new Promise((resolve) => { releaseFirst = resolve }))
    findMyTaskRefs.mockResolvedValueOnce({ data: { 'pi-2': [{ taskId: 't-new', taskName: 'Current' }] } })

    const rows = ref([row('pi-1')])
    const { loadRowTasks, rowTasksOf } = useMainTableViewRowTasks(rows)
    const stale = loadRowTasks(true, () => {})

    rows.value = [row('pi-2')]
    await loadRowTasks(true, () => {})

    releaseFirst({ data: { 'pi-1': [{ taskId: 't-old', taskName: 'Stale' }] } })
    await stale

    expect(rowTasksOf(rows.value[0])).toEqual([{ taskId: 't-new', taskName: 'Current' }])
  })
})
