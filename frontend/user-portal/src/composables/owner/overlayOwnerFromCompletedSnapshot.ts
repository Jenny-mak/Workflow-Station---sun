import { sameSubTableRow } from '@/composables/tasks/shared'
import { OWNER_STEP_PREFIX } from '@/composables/owner/useOwnerFieldModel'

export const OWNER_DISPLAY_SUFFIX = '__display'

type OwnerishField = { type?: string; key?: string }
type OwnerishTab = { fields?: OwnerishField[] }
type HistoryLike = { id?: string; taskId?: string; nodeId?: string; nodeName?: string; status?: string }
type BindingLike = {
  bindingId?: number
  primaryKeyFields?: string[]
  columns?: Array<{ field?: string; type?: string }>
  data?: unknown[]
}

export function collectOwnerFieldKeys(fields: OwnerishField[] | undefined): string[] {
  if (!fields) return []
  return fields.filter(f => f.type === 'owner' && !!f.key).map(f => String(f.key))
}

export function collectOwnerKeysFromNode(fields: OwnerishField[], tabs?: OwnerishTab[]): string[] {
  const keys = [...collectOwnerFieldKeys(fields)]
  for (const tab of tabs ?? []) {
    keys.push(...collectOwnerFieldKeys(tab.fields))
  }
  return [...new Set(keys)]
}

/**
 * History statuses that mean the task actually finished and therefore has a
 * `_snapshot_{taskId}`. REJECT completes the task through the same
 * `TaskApprovalCompletionComponent` path as APPROVE and writes a snapshot, so a
 * rejected node must show its frozen handler, not a blank.
 */
const FINISHED_HISTORY_STATUSES = new Set(['completed', 'rejected'])

/**
 * Engine task id of the last finished history row for this BPMN node.
 * Only a real `taskId` can address `_snapshot_{taskId}` — the synthetic history
 * row `id` MUST NOT stand in for it, or every snapshot lookup misses.
 * {@code nodeName} is the BPMN activity name, not the form name.
 */
export function lastCompletedTaskIdForNode(
  history: HistoryLike[],
  nodeId: string,
  nodeName?: string,
): string | null {
  const matches = history.filter(h => {
    if (h.status && !FINISHED_HISTORY_STATUSES.has(h.status)) return false
    return h.nodeId === nodeId || (!!nodeName && h.nodeName === nodeName)
  })
  const last = matches[matches.length - 1]
  return last?.taskId ? String(last.taskId) : null
}

export function snapshotFieldValuesOf(
  variables: Record<string, unknown> | null | undefined,
  taskId: string,
): Record<string, unknown> | null {
  if (!variables || !taskId) return null
  const raw = variables[`_snapshot_${taskId}`]
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const fv = (raw as { fieldValues?: unknown }).fieldValues
  if (!fv || typeof fv !== 'object' || Array.isArray(fv)) return {}
  return fv as Record<string, unknown>
}

function applyOwnerKey(
  target: Record<string, unknown>,
  key: string,
  snapshot: Record<string, unknown> | null,
): void {
  const displayKey = key + OWNER_DISPLAY_SUFFIX
  if (!snapshot || !Object.prototype.hasOwnProperty.call(snapshot, key)) {
    target[key] = ''
    delete target[displayKey]
    return
  }
  target[key] = snapshot[key]
  if (Object.prototype.hasOwnProperty.call(snapshot, displayKey)) {
    target[displayKey] = snapshot[displayKey]
    return
  }
  const stored = String(snapshot[key] ?? '')
  if (stored.startsWith(OWNER_STEP_PREFIX) && stored.length > OWNER_STEP_PREFIX.length) {
    target[displayKey] = stored.slice(OWNER_STEP_PREFIX.length)
  }
}

function snapshotSubTableRows(
  snapshot: Record<string, unknown> | null,
): Record<string, unknown>[] {
  if (!snapshot) return []
  const slices = snapshot.__subTables__
  if (!slices || typeof slices !== 'object' || Array.isArray(slices)) return []
  const rows: Record<string, unknown>[] = []
  for (const raw of Object.values(slices as Record<string, unknown>)) {
    if (!Array.isArray(raw)) continue
    for (const row of raw) {
      if (row && typeof row === 'object' && !Array.isArray(row)) {
        rows.push(row as Record<string, unknown>)
      }
    }
  }
  return rows
}

/**
 * MI sub-forms render collection-row Owner columns as top-level fields
 * (`row_owner` / `row_case_handler`). Those keys live on `__subTables__`
 * rows, not the snapshot root. Root still wins for MAIN keys (`case_handler`).
 */
export function ownerLookupBag(
  snapshot: Record<string, unknown> | null,
  live: Record<string, unknown>,
): Record<string, unknown> | null {
  if (!snapshot) return null
  const root: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(snapshot)) {
    if (key !== '__subTables__') root[key] = value
  }
  const rows = snapshotSubTableRows(snapshot)
  const matched = rows.find(row => sameSubTableRow(live, row, null))
  const row = matched ?? (rows.length === 1 ? rows[0] : null)
  return row ? { ...row, ...root } : root
}

export function overlayOwnerMainValues(
  live: Record<string, unknown>,
  ownerKeys: string[],
  snapshot: Record<string, unknown> | null,
): Record<string, unknown> {
  const bag = ownerLookupBag(snapshot, live)
  const next = { ...live }
  for (const key of ownerKeys) {
    applyOwnerKey(next, key, bag)
  }
  return next
}

function snapshotRowsForBinding(
  snapshot: Record<string, unknown> | null,
  bindingId: number,
): Record<string, unknown>[] | null {
  if (!snapshot) return null
  const slices = snapshot.__subTables__
  if (!slices || typeof slices !== 'object' || Array.isArray(slices)) return null
  const raw = (slices as Record<string, unknown>)[String(bindingId)]
    ?? (slices as Record<string, unknown>)[bindingId as unknown as string]
  if (Array.isArray(raw)) {
    return raw.filter((row): row is Record<string, unknown> => !!row && typeof row === 'object')
  }
  // Snapshots are keyed by `dw:<table>` / `rt:<table>`, not the form bindingId.
  const all = snapshotSubTableRows(snapshot)
  return all.length > 0 ? all : null
}

export function overlayOwnerOnSubRows(
  rows: Record<string, unknown>[],
  ownerCols: string[],
  snapshotRows: Record<string, unknown>[] | null,
  primaryKeyFields?: string[],
): Record<string, unknown>[] {
  if (ownerCols.length === 0) return rows
  return rows.map(row => {
    const next = { ...row }
    const match = snapshotRows?.find(s => sameSubTableRow(row, s, primaryKeyFields ?? null)) ?? null
    for (const col of ownerCols) {
      applyOwnerKey(next, col, match)
    }
    return next
  })
}

/**
 * Owner values of a node the viewer is *not* currently working on come from that
 * node's completion snapshot (§6.6). When there is no snapshot — no completed
 * task for the node, or an instance old enough that Owner was never frozen —
 * the Owner reads empty. Live values MUST NOT stand in: after the process moves
 * on they hold the next step's handler (or `step:<MI box>`), which is exactly
 * the wrong answer for a historical node.
 */
export function overlayCompletedNodeOwner(input: {
  values: Record<string, unknown>
  fields: OwnerishField[]
  tabs?: OwnerishTab[]
  bindings: BindingLike[]
  nodeId: string
  nodeName?: string
  variables?: Record<string, unknown> | null
  history: HistoryLike[]
}): { values: Record<string, unknown>; bindings: BindingLike[] } {
  const taskId = lastCompletedTaskIdForNode(input.history, input.nodeId, input.nodeName)
  const snapshot = taskId ? snapshotFieldValuesOf(input.variables, taskId) : null
  const ownerKeys = collectOwnerKeysFromNode(input.fields, input.tabs)
  const values = ownerKeys.length > 0
    ? overlayOwnerMainValues(input.values, ownerKeys, snapshot)
    : input.values
  const bindings = input.bindings.map(binding => {
    const ownerCols = (binding.columns ?? [])
      .filter(c => c.type === 'owner' && c.field)
      .map(c => String(c.field))
    if (ownerCols.length === 0 || !Array.isArray(binding.data)) return binding
    const rows = binding.data.filter((r): r is Record<string, unknown> => !!r && typeof r === 'object')
    const snapRows = snapshotRowsForBinding(snapshot, Number(binding.bindingId))
    return {
      ...binding,
      data: overlayOwnerOnSubRows(rows, ownerCols, snapRows, binding.primaryKeyFields),
    }
  })
  return { values, bindings }
}
