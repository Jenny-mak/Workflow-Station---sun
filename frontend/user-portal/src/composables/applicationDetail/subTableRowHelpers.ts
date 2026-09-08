import type { FormField } from '@/components/FormRenderer.vue'
import type { HistoryRecord } from '@/types/historyRecord'
import { resolveAssigneeFieldForBinding } from '@/utils/subTableAssignment'
import { isAuditField } from '@/components/subTableAddDialogHelpers'
import {
  mergeSubTableRowsByRowId,
  dropSubsumedSubTableRows,
  isSharedAttachmentFileBinding,
  rowIsSelfOwnedByStructuralFk,
  miChildFkConfigOfBinding,
} from '@/composables/tasks/shared'
import { readSubTableRows, type SubTableStoreBindingLike } from '@/composables/tasks/subTableStore'
import { SUB_TABLE_STRUCTURAL_FK_KEYS } from '@/composables/taskDetail/subTableRowUtils'
import type { MiKindFieldDef } from '@/composables/tasks/miBindingKindFromConfig'
import { getActiveMiFieldNames } from '@/composables/tasks/useMiConfig'
import { USER_ID_KEY, USER_KEY } from '@/api/auth'

/** Consistent with request interceptor; used to determine if the initiator is viewing their own application */
export function getPortalUserId(): string | null {
  let userId = localStorage.getItem(USER_ID_KEY)
  if (!userId) {
    const userStr = localStorage.getItem(USER_KEY)
    if (userStr) {
      try {
        const user = JSON.parse(userStr)
        userId = user.userId || user.id
      } catch {
        /* ignore */
      }
    }
  }
  return userId || null
}

/** Align with tasks/detail.vue: variables may key __subTables__ by table name or binding id. */
export function normalizeSubTableName(name?: string): string {
  return String(name || '').trim().toLowerCase()
}

/**
 * 规范 key（`dw:<name>` / `rt:<name>`）优先 —— 一张表一份数据，binding 不是数据身份。
 *
 * <p>规范 key 命中即返回：同一张表被多个 MI binding 读取（Assign Task / Sub task / Main）时，
 * 它们解析到同一个 key，不存在「取到兄弟 binding 的陈旧行」的歧义。**空数组也算命中**——
 * 表确实没有行，回退到旧 key 会把刚清空的表填回陈旧副本。
 *
 * <p>回退路径只认 binding id、不做表名兜底：旧结构里 `__subTables__` 还带一个共享展示名 key
 * （如 `Participants`），多个 binding 各自保存时互相覆盖，无从判断落在里面的行属于谁，
 * 按名字猜会把兄弟 binding 的陈旧行当成本 binding 的数据。旧数据下某 binding 在自己
 * id key 下没有行，就是真的没有。
 */
export function getSavedSubTableRowsFromVariables(
  savedSubTables: Record<string, any> | null | undefined,
  rawBinding: { bindingId: number } & SubTableStoreBindingLike,
  _primaryKeyFields?: string[] | null
): any[] | undefined {
  if (!savedSubTables || typeof savedSubTables !== 'object') return undefined

  const canonical = readSubTableRows(savedSubTables, rawBinding)
  if (canonical) return dropSubsumedSubTableRows(canonical as any[])

  // 过渡期：规范 key 落地前保存的实例仍是 binding id key。
  const v = savedSubTables[rawBinding.bindingId] ?? savedSubTables[String(rawBinding.bindingId)]
  if (!Array.isArray(v) || v.length === 0) return undefined
  return dropSubsumedSubTableRows(v)
}

export type SubTableBindingAlignable = {
  bindingId?: number
  tableId?: number | null
  tableName: string
  data: any[]
  primaryKeyFields?: string[]
  designerTableName?: string
  // binding 分类（共享附件 / participant-child / collection）**全部读这两项配置**。
  // 漏在类型里 = 调用点被迫 `as {...}` 窄化，把配置藏起来，分类静默降级。
  foreignKeyField?: string | null
  bindingLinkMode?: string | null
  fieldDefinitions?: MiKindFieldDef[] | null
}

/**
 * Copied forms (e.g. subform_copy) get a new bindingId while runtime data still lives under the original key;
 * MI may only persist one row under the new id — merge all bindings that share tableId (or display name) for My Request.
 *
 * Previous logic bucketed only by `tid:*` OR `tn:*`. When one form binding had `tableId` and another did not
 * (same relation table, different form metadata), they landed in separate groups and `length < 2` skipped merge —
 * common when the process advances (e.g. assignment step) and the current form uses a new binding row while
 * `__subTables__` keys still match an earlier step. Union-find merges by equal numeric tableId OR equal normalized
 * display name, then a column-overlap pass fills bindings that still have no rows.
 */
/**
 * Stable display order for sub-table rows. The row data can be populated by more than one async source
 * (process variables, MI overlay, sub-table enrichment), and whichever lands first sets the Map insertion
 * order in the merge — so the rendered order can flip between page loads. Sorting by a stable per-row key
 * here makes the order deterministic regardless of which source arrived first.
 *
 * Only sorts when EVERY row exposes the same stable key (id_idw → sub_task_id → id), so tables that have a
 * meaningful insertion order but no such key keep their original order untouched.
 */
const STABLE_ORDER_KEYS = ['id_idw', 'sub_task_id', 'id'] as const

function stableRowSortValue(row: Record<string, unknown>, key: string): string | null {
  const v = row?.[key]
  if (v == null) return null
  const s = String(v).trim()
  return s === '' ? null : s
}

function sortRowsByStableKey<T extends Record<string, unknown>>(rows: T[]): T[] {
  if (rows.length < 2) return rows
  const key = STABLE_ORDER_KEYS.find(k => rows.every(r => stableRowSortValue(r, k) != null))
  if (!key) return rows
  // Natural compare so Test-000002 < Test-000010 (numeric-aware), stable for ties.
  const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })
  return rows
    .map((row, index) => ({ row, index }))
    .sort((a, b) => {
      const c = collator.compare(stableRowSortValue(a.row, key)!, stableRowSortValue(b.row, key)!)
      return c !== 0 ? c : a.index - b.index
    })
    .map(x => x.row)
}

/** Union-find merge of row snapshots across bindings that share tableId or display name. */
export function applyUnionFindMergeToBindingList(all: SubTableBindingAlignable[]) {
  if (all.length === 0) return

  const parent = all.map((_, i) => i)
  const find = (i: number): number => {
    if (parent[i] !== i) parent[i] = find(parent[i])
    return parent[i]
  }
  const union = (i: number, j: number) => {
    const ri = find(i)
    const rj = find(j)
    if (ri !== rj) parent[ri] = rj
  }

  for (let i = 0; i < all.length; i++) {
    for (let j = i + 1; j < all.length; j++) {
      const a = all[i]!
      const b = all[j]!
      if (isSharedAttachmentFileBinding(a) || isSharedAttachmentFileBinding(b)) {
        continue
      }
      const tnA = normalizeSubTableName(a.tableName)
      const tnB = normalizeSubTableName(b.tableName)
      const tidA = a.tableId != null && !Number.isNaN(Number(a.tableId)) ? Number(a.tableId) : null
      const tidB = b.tableId != null && !Number.isNaN(Number(b.tableId)) ? Number(b.tableId) : null
      if (tidA != null && tidB != null && tidA === tidB) {
        union(i, j)
      } else if (tnA.length > 0 && tnA === tnB) {
        union(i, j)
      }
    }
  }

  const byRoot = new Map<number, SubTableBindingAlignable[]>()
  for (let i = 0; i < all.length; i++) {
    const r = find(i)
    if (!byRoot.has(r)) byRoot.set(r, [])
    byRoot.get(r)!.push(all[i]!)
  }

  for (const group of byRoot.values()) {
    let pkFields: string[] | undefined
    for (const b of group) {
      const pks = (b as SubTableBindingAlignable).primaryKeyFields
      if (!pkFields?.length && Array.isArray(pks) && pks.length > 0) {
        pkFields = pks.map(f => String(f).trim()).filter(Boolean)
      }
    }
    /**
     * A physical table shared by several form bindings (e.g. an MI participant table read by
     * Assign Task / Sub task / Main) can carry a different snapshot of the same row per binding —
     * only the binding whose form actually owns the row's writes keeps it current; the others hold
     * an initialization-time copy that never gets edited again. mergeSubTableRowsByRowId's contract
     * is "later argument wins for non-empty fields", so folding by array order let an arbitrary peer
     * silently overwrite the genuinely-owning binding's fields (e.g. a stale `name` from a copy
     * clobbering the value the owning sub-task actually saved). Fold rows carrying a structural
     * self-reference FK (rowIsSelfOwnedByStructuralFk — sub_task_id/participant_id/parentId/… equal
     * to the row's own PK) in LAST so they win, regardless of which binding or array position they
     * came from; rows with no such marker (plain/non-MI tables) keep the prior array-order fold.
     */
    let merged: any[] = []
    const selfOwnedChunks: any[][] = []
    for (const b of group) {
      const rows = Array.isArray(b.data) ? b.data : []
      // FK 列名按该 binding 的设计器字段定义解析，不猜列名。
      const bFkConfig = miChildFkConfigOfBinding(b as never)
      const isOwn = (r: any) =>
        r && typeof r === 'object' && rowIsSelfOwnedByStructuralFk(r, pkFields, bFkConfig)
      const ownRows = rows.filter(isOwn)
      const restRows = rows.filter((r: any) => !isOwn(r))
      merged = mergeSubTableRowsByRowId(merged, restRows, pkFields)
      if (ownRows.length > 0) selfOwnedChunks.push(ownRows)
    }
    for (const chunk of selfOwnedChunks) {
      merged = mergeSubTableRowsByRowId(merged, chunk, pkFields)
    }
    if (merged.length === 0) continue
    const snapshot = sortRowsByStableKey(merged.map(r => ({ ...r })))
    for (const b of group) {
      b.data = snapshot
    }
  }
}

/** Fast path: current form bindings only — enough for first paint on initiator My Request. */

/**
 * When `__subTables__` keys do not match any bindingId/table label on the current form, merge-by-table may still
 * leave an empty `data` array. Pick a saved row list whose first row shares enough column names with the binding's
 * list-view columns (conservative threshold) so initiator My Request shows prior-step sub-table rows.
 */
/**
 * Columns + inline-form field keys used to match variable slices and detect MI placeholder rows.
 * Non-discriminative keys are excluded — they appear in every sibling sub-table's rows and would
 * let the fuzzy backfill "claim" another table's rows (e.g. an empty Attachment table adopting a
 * Transaction row):
 *  - system audit fields (created_at / created_by / updated_at / updated_by — auto-appended to every table)
 *  - structural FK / runtime row keys (row_id, id_idw, sub_task_id, …)
 *  - the binding's own foreign key to the main table (e.g. case_id / main_id)
 */
export function collectSubTableBindingMatchKeys(b: {
  columns?: Array<{ field?: string }>
  formFields?: FormField[]
  foreignKeyField?: string | null
}): Set<string> {
  const fieldSet = new Set<string>()
  const fk = String(b.foreignKeyField ?? '').trim().toLowerCase()
  const add = (key: string) => {
    const lk = key.toLowerCase()
    if (isAuditField(key)) return
    if (SUB_TABLE_STRUCTURAL_FK_KEYS.has(lk)) return
    if (fk && lk === fk) return
    fieldSet.add(key)
  }
  for (const c of b.columns || []) {
    if (typeof c?.field === 'string' && c.field.length > 0) add(c.field)
  }
  const walkFormFields = (fields?: FormField[]) => {
    if (!Array.isArray(fields)) return
    for (const f of fields) {
      if (f.type === 'tabs' && Array.isArray(f.tabs)) {
        for (const tab of f.tabs) walkFormFields(tab.fields)
      } else if (f.type === 'collapse' && Array.isArray(f.collapsePanels)) {
        for (const panel of f.collapsePanels) walkFormFields(panel.fields)
      } else if (f.type === 'card' || f.type === 'row' || f.type === 'col') walkFormFields(f.children)
      else if (typeof f.key === 'string' && f.key.length > 0) add(f.key)
    }
  }
  walkFormFields(b.formFields)
  return fieldSet
}

export const SUB_TABLE_MI_PLACEHOLDER_KEYS = new Set([
  'assignee_user_id',
  'assignee_id',
  'assignee_display_name',
  'task_status',
  'task_current_node',
  'sub_task_status',
  'sub_task_current_node',
  'task_id',
  'task_definition_key',
])

/**
 * 「这个列是 MI 运行时元数据，而不是用户填的业务数据」。
 *
 * <p>上面的集合是**跨 FU 的已知名字并集**（历史上出现过的各种拼法），但它无法覆盖某个 FU
 * 在 Sub-Task Config 里自定义的列名 —— 那样的列会被误判成业务数据。故先问当前 FU 的配置。
 */
export function isMiPlaceholderKey(lowerKey: string): boolean {
  if (SUB_TABLE_MI_PLACEHOLDER_KEYS.has(lowerKey)) return true
  const { statusField, currentNodeField } = getActiveMiFieldNames()
  // 未配置的列名为 null —— 没有这一列，也就无从"是"它。
  return (!!statusField && lowerKey === statusField.toLowerCase())
    || (!!currentNodeField && lowerKey === currentNodeField.toLowerCase())
}

/**
 * 从 `taskDetail/subTableRowUtils` re-export —— 这里曾有一份**同名同注释的手抄副本**，
 * 且已漂移：少了 `'id'` 和 `'main_id'`，导致同一行在 My Request 页与 To Do 页
 * 对「算不算空行」给出相反答案。名单只应有一处定义。
 */
export { SUB_TABLE_STRUCTURAL_FK_KEYS }

export function pickSubTableRowValueIgnoreKeyCase(o: Record<string, unknown>, key: string): unknown {
  if (Object.prototype.hasOwnProperty.call(o, key)) return o[key]
  const want = key.toLowerCase()
  for (const rk of Object.keys(o)) {
    if (rk.toLowerCase() === want) return o[rk]
  }
  return undefined
}

/**
 * True when no row carries real values for designer list / sub-form fields (only MI assignment columns, etc.).
 * Otherwise backfill from variables is skipped and Link Form modal stays blank for My Request.
 */
export function subTableRowsLackSavedFieldPayload(rows: unknown[] | undefined, fieldKeys: Set<string>): boolean {
  if (fieldKeys.size === 0) return false
  if (!Array.isArray(rows) || rows.length === 0) return true
  const checkKeys = [...fieldKeys].filter(k => {
    const lk = k.toLowerCase()
    return !isMiPlaceholderKey(lk) && !SUB_TABLE_STRUCTURAL_FK_KEYS.has(lk)
  })
  if (checkKeys.length === 0) return true
  for (const row of rows) {
    if (!row || typeof row !== 'object') continue
    const o = row as Record<string, unknown>
    for (const k of checkKeys) {
      const v = pickSubTableRowValueIgnoreKeyCase(o, k)
      if (v === undefined || v === null || v === '') continue
      if (typeof v === 'boolean') return false
      if (typeof v === 'number' && !Number.isNaN(v)) return false
      if (typeof v === 'string' && v.trim() !== '') return false
    }
  }
  return true
}

/**
 * MI / assignee sub-table rows: process-level {@code formData} may contain another participant's link-form
 * fields (last writer). Never treat that as this row's Detail payload unless the row is terminal.
 */
export function isMultiInstanceStyleSubTableRow(row: any): boolean {
  if (!row || typeof row !== 'object') return false
  if (row.task_status !== undefined && row.task_status !== null) return true
  if (row.sub_task_status !== undefined && row.sub_task_status !== null) return true
  if (row.task_id != null && String(row.task_id).trim() !== '') return true
  if (row.task_definition_key != null && String(row.task_definition_key).trim() !== '') return true
  if (row.assignee_user_id != null && String(row.assignee_user_id).trim() !== '') return true
  if (row.assignee_id != null && String(row.assignee_id).trim() !== '') return true
  return false
}

export function rowAssigneeUserId(row: any, assigneeField: string): string | null {
  if (!row || typeof row !== 'object') return null
  const raw = row[assigneeField]
  if (raw == null || raw === '') return null
  if (typeof raw === 'string' || typeof raw === 'number') {
    const s = String(raw).trim()
    return s.length > 0 ? s : null
  }
  if (typeof raw === 'object') {
    const uid = (raw as { userId?: unknown; id?: unknown }).userId ?? (raw as { id?: unknown }).id
    if (uid == null || uid === '') return null
    const s = String(uid).trim()
    return s.length > 0 ? s : null
  }
  return null
}

export const hasAssignmentData = (rows: any[], assigneeField?: string): boolean => {
  if (!Array.isArray(rows) || rows.length === 0) return false
  if (assigneeField) {
    return rows.some(r => r && rowAssigneeUserId(r, assigneeField) != null)
  }
  for (const field of ['assignee_user_id', 'assignee_id']) {
    if (rows.some(r => r && rowAssigneeUserId(r, field) != null)) return true
  }
  return rows.some(r => r && r.assignee_display_name)
}

export function resolveBindingAssigneeField(binding: {
  columns?: Array<{ field?: string }>
  tableName?: string
  data?: any[]
}): string | undefined {
  const assigneeField = resolveAssigneeFieldForBinding(binding as never)
  if (assigneeField && hasAssignmentData(binding.data || [], assigneeField)) {
    return assigneeField
  }
  return undefined
}

export function isSyntheticLookupField(fieldName?: string): boolean {
  return !fieldName || String(fieldName).startsWith('lookup:')
}

export function isAssigneeLikeLabel(label?: string): boolean {
  const normalized = String(label || '').trim().toLowerCase()
  return /assignee|处理人|負責人|经办人|經辦人/.test(normalized)
}

export const getHistoryStatus = (operationType: string): 'completed' | 'current' | 'pending' | 'rejected' => {
  const map: Record<string, 'completed' | 'current' | 'pending' | 'rejected'> = {
    'SUBMIT': 'completed',
    'APPROVE': 'completed',
    'REJECT': 'rejected',
    'DELEGATE': 'completed',
    'TRANSFER': 'completed',
    'CLAIM': 'completed',
    'PENDING': 'current',
    'SEND': 'completed',
  }
  return map[operationType] || 'completed'
}

export const getHistoryAction = (operationType: string): HistoryRecord['action'] => {
  const map: Record<string, NonNullable<HistoryRecord['action']>> = {
    'SUBMIT': 'submit',
    'APPROVE': 'approve',
    'REJECT': 'reject',
    'TRANSFER': 'transfer',
    'DELEGATE': 'delegate',
    'WITHDRAW': 'withdraw',
    'RETURN': 'return',
    'DRAFT': 'draft',
    'DRAFT_TASK': 'draft',
    'SEND': 'send',
  }
  return map[operationType]
}
