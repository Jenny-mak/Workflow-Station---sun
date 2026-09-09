import { ref, type Ref } from 'vue'
import type { MainTableViewDataRow } from '@/api/mainTableView'
import { findMyTaskRefs, type MyTaskRef } from '@/api/task'

/**
 * Per-row "you have a task here" markers for the Views grid.
 *
 * Rows are matched to tasks by process instance id — the platform's own key, which a MAIN row
 * already carries. A designed business field (case number, case id, whatever this function unit
 * named it) is deliberately not used: its name is per-FU designer configuration, so keying off it
 * would make the marker work in some function units and silently break in others.
 *
 * One request per page of rows. A per-row lookup would be one round trip per row.
 */
export function useMainTableViewRowTasks(rows: Ref<MainTableViewDataRow[]>) {
  const tasksByInstance = ref<Record<string, MyTaskRef[]>>({})
  /** Only the reply to the most recent load may land; page and view changes race. */
  let latestLoad = 0

  /**
   * @param enabled markers apply to MAIN (Request) views only — a SUB view's rows are parts of a
   *   request, not requests, so there is no single task to open from one.
   * @param onFailure told when the lookup failed, so the page can say so
   */
  async function loadRowTasks(enabled: boolean, onFailure: () => void): Promise<void> {
    const seq = ++latestLoad
    if (!enabled) {
      tasksByInstance.value = {}
      return
    }
    const ids = [...new Set(
      rows.value
        .map(row => row.processInstanceId)
        .filter((id): id is string => typeof id === 'string' && id.length > 0),
    )]
    if (ids.length === 0) {
      tasksByInstance.value = {}
      return
    }
    try {
      const res = await findMyTaskRefs(ids)
      if (seq !== latestLoad) return
      tasksByInstance.value = res.data ?? {}
    } catch {
      if (seq !== latestLoad) return
      // FALLBACK(ux): the marker is a shortcut to a task, not the record — the row data beside it
      // is already loaded and stays readable. Markers are cleared rather than left stale, and the
      // caller reports the failure, so an empty column is never read as "you have no tasks here".
      tasksByInstance.value = {}
      onFailure()
    }
  }

  /** This row's To Do tasks, in To Do order. Empty when the row holds none of the user's tasks. */
  function rowTasksOf(row: Pick<MainTableViewDataRow, 'processInstanceId'>): MyTaskRef[] {
    const processInstanceId = row?.processInstanceId
    if (!processInstanceId) return []
    return tasksByInstance.value[String(processInstanceId)] ?? []
  }

  return { loadRowTasks, rowTasksOf }
}
