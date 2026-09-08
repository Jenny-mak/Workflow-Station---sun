import { toRaw } from 'vue'
import type { FormField } from '@/components/FormRenderer.vue'
import { isAuditField } from '@/components/subTableAddDialogHelpers'
import {
  flattenNestedSubTableRowsIntoPayload,
  mergeSubTableRowsByRowId,
} from '@/composables/tasks/shared'
import { getActiveMiFieldNames } from '@/composables/tasks/useMiConfig'

export function normalizeSubTableName(name?: string): string {
  return String(name || '').trim().toLowerCase()
}

/**
 * Whole-form readonly lock from `TaskFormData.fieldPermissions` — true only when EVERY field
 * actually on the MAIN TABLE canvas (per `configJson.rule`) is explicitly READONLY.
 *
 * A field with no `fieldPermissions` entry at all defaults to editable (same default the
 * backend submit-time filter uses) — so an unconfigured field, not just an explicit EDITABLE
 * one, is enough to keep the whole form open. Checking only the *configured subset* (as an
 * earlier version of this function did) is wrong: a form with one READONLY field and four
 * entirely unconfigured (implicitly editable) fields would otherwise look "100% READONLY" and
 * incorrectly lock everything.
 *
 * Composite `${bindingId}:${fieldName}` sub-table keys never count toward this check — a form
 * whose only configured permissions are sub-table field permissions (main-table fields
 * unconfigured) must not lock the whole form. `mainTableRule` (not just the configured-keys
 * subset) is also needed to filter out foreign/stale bare keys: `mergeTaskPermissionsForFields`
 * could (before a same-session fix) write a SUB-table field's auto-PK/FK-readonly permission
 * under its own bare field name (e.g. a sub-table's `id_idw`/`main_id`) into this same flat
 * map, colliding with the main-table namespace.
 */
export function isWholeFormLockedByFieldPermissions(
  fieldPermissions: Record<string, unknown> | null | undefined,
  mainTableRule?: unknown,
): boolean {
  if (!fieldPermissions) return false
  const mainTableFieldNames = (Array.isArray(mainTableRule) ? mainTableRule : [])
    .map(r => (r && typeof r === 'object' ? (r as Record<string, unknown>).field : undefined))
    .filter((f): f is string => typeof f === 'string' && f.length > 0)
  if (mainTableFieldNames.length === 0) return false
  return mainTableFieldNames.every(field => {
    const permission = fieldPermissions[field]
    return permission != null && String(permission).toUpperCase() === 'READONLY'
  })
}

/** Same form / node may place multiple sub-tables backed by identical relation-table metadata — never resolve {@code __subTables__} by display/designer-name/tableId keys then (everyone steals the same slice). */
export function bindingIdsPreferStrictSubTableLookup(
  bindings: Array<{ bindingId: number; tableId?: number | null; tableName: string; designerTableName?: string }>,
): Set<number> {
  const ambiguous = new Set<number>()
  if (!Array.isArray(bindings) || bindings.length <= 1) return ambiguous

  const bump = (m: Map<string, Set<number>>, key: string, bid: number) => {
    if (!key) return
    const nk = normalizeSubTableName(key)
    if (!nk) return
    let s = m.get(nk)
    if (!s) {
      s = new Set()
      m.set(nk, s)
    }
    s.add(bid)
  }

  const buckets = new Map<string, Set<number>>()
  for (const b of bindings) {
    bump(buckets, b.tableName, b.bindingId)
    if (typeof b.designerTableName === 'string' && b.designerTableName.trim())
      bump(buckets, b.designerTableName, b.bindingId)
    if (b.tableId != null && Number.isFinite(Number(b.tableId))) {
      bump(buckets, `__rtid:${Number(b.tableId)}`, b.bindingId)
    }
  }
  for (const s of buckets.values()) {
    if (s.size > 1) {
      for (const id of s) ambiguous.add(id)
    }
  }
  return ambiguous
}

export function subTableBindingMatches(
  target: { bindingId: number; tableName: string; designerTableName?: string; tableId?: number | null },
  source: { bindingId: number; tableName: string; designerTableName?: string; tableId?: number | null }
): boolean {
  const targetDesignerName = normalizeSubTableName(target.designerTableName)
  const sourceDesignerName = normalizeSubTableName(source.designerTableName)
  if (targetDesignerName && sourceDesignerName && targetDesignerName === sourceDesignerName) return true
  const targetName = normalizeSubTableName(target.tableName)
  const sourceName = normalizeSubTableName(source.tableName)
  const sameDesignerTable = target.tableId != null && source.tableId != null && Number(target.tableId) === Number(source.tableId)
  return target.bindingId === source.bindingId || sameDesignerTable || (!!targetName && targetName === sourceName)
}

/** Recursively unwrap Vue Proxy at every level — returns a plain clone. */
export function deepToRaw(obj: any, seen?: WeakSet<object>): any {
  const raw = toRaw(obj)
  if (raw === null || raw === undefined || typeof raw !== 'object') return raw
  const visited = seen instanceof WeakSet ? seen : new WeakSet<object>()
  if (visited.has(raw)) return raw
  visited.add(raw)
  if (Array.isArray(raw)) return raw.map(item => deepToRaw(item, visited))
  if (raw instanceof Date) return new Date(raw.getTime())
  if (raw instanceof Map) {
    const m = new Map()
    raw.forEach((v, k) => m.set(deepToRaw(k, visited), deepToRaw(v, visited)))
    return m
  }
  if (raw instanceof Set) {
    const s = new Set()
    raw.forEach(v => s.add(deepToRaw(v, visited)))
    return s
  }
  const result: Record<string, any> = {}
  for (const key in raw) {
    if (Object.prototype.hasOwnProperty.call(raw, key)) {
      result[key] = deepToRaw(raw[key], visited)
    }
  }
  return result
}

export function cloneSubTableRows(rows: any[]): any[] {
  // Deep-unwrap all Vue proxies recursively — avoids JSON.stringify getter traps.
  // For 41 rows × ~10 cols this is ~410 property reads, no serialization overhead.
  return rows.map(row => deepToRaw(row))
}

/** Clone + flatten __subTables__ once for rehydrate / MI resync (JSON clone avoids reactive getter traps). */
export function cloneAndFlattenSubTablesMap(savedMap: Record<string, unknown>): Record<string, unknown> {
  const flattened = JSON.parse(JSON.stringify(savedMap)) as Record<string, unknown>
  flattenNestedSubTableRowsIntoPayload(flattened)
  return flattened
}

export function cloneSubTableBindings<T extends Array<{ data: any[] }>>(bindings: T): T {
  return bindings.map(binding => ({
    ...binding,
    data: cloneSubTableRows(Array.isArray(binding.data) ? binding.data : [])
  })) as T
}

export function yieldToMain(): Promise<void> {
  return new Promise(resolve => {
    requestAnimationFrame(() => { setTimeout(resolve, 0) })
  })
}

/**
 * When no binding key matches variables, pick a saved row list by column / sub-form field overlap.
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
 * <p>上面的集合是跨 FU 的已知名字并集，覆盖不了某个 FU 在 Sub-Task Config 里自定义的列名，
 * 那样的列会被误判成业务数据，故先问当前 FU 的配置。
 */
export function isMiPlaceholderKey(lowerKey: string): boolean {
  if (SUB_TABLE_MI_PLACEHOLDER_KEYS.has(lowerKey)) return true
  const { statusField, currentNodeField } = getActiveMiFieldNames()
  // 未配置的列名为 null —— 没有这一列，也就无从"是"它。
  return (!!statusField && lowerKey === statusField.toLowerCase())
    || (!!currentNodeField && lowerKey === currentNodeField.toLowerCase())
}

/**
 * FK / MI keys that must not satisfy {@link subTableRowsLackSavedFieldPayload} alone (sub_task_id
 * without age still blank).
 *
 * <p><b>本文件是这份名单的唯一定义处</b>，`applicationDetail/subTableRowHelpers` 从这里 re-export。
 * 两处曾各自维护一份**同名同注释**的副本，并且已经漂移：那一份少了 `'id'` 和 `'main_id'`，
 * 于是同一行在 To Do 页被判「只有结构字段、算空行」、在 My Request 页却判「有业务数据」。
 *
 * <p>注意这**不是**行身份判定：它回答的是「这一列算不算用户填的业务数据」，没有单一配置源
 * （结构外键列名由各表自己定义，而这里要覆盖所有表的通用形态）。判宽判窄只影响
 * 「这行算不算空」，不会把两行错配成同一行——所以这里保留名单是合理的，
 * 与身份链路上按名字猜主键不是一回事。
 */
export const SUB_TABLE_STRUCTURAL_FK_KEYS = new Set([
  'sub_task_id',
  'sub_taskid',
  'id_idw',
  'id',
  'participant_id',
  'parent_id',
  'row_id',
  'main_id',
])

export function pickSubTableRowValueIgnoreKeyCase(o: Record<string, unknown>, key: string): unknown {
  if (Object.prototype.hasOwnProperty.call(o, key)) return o[key]
  const want = key.toLowerCase()
  for (const rk of Object.keys(o)) {
    if (rk.toLowerCase() === want) return o[rk]
  }
  return undefined
}

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

export type SubTableBindingAlignable = {
  bindingId?: number
  tableId?: number | null
  tableName: string
  data: any[]
  primaryKeyFields?: string[]
}

/** Union-find + merged snapshot assignment — shared by full-cardinality align vs diagram-only align. */
export function applyUnionFindMergedRowSnapshots(all: SubTableBindingAlignable[]): void {
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
      const tnA = normalizeSubTableName(a.tableName)
      const tnB = normalizeSubTableName(b.tableName)
      const tidA = a.tableId != null && !Number.isNaN(Number(a.tableId)) ? Number(a.tableId) : null
      const tidB = b.tableId != null && !Number.isNaN(Number(b.tableId)) ? Number(b.tableId) : null
      const sameById = tidA != null && tidB != null && tidA === tidB
      const sameByName = tnA.length > 0 && tnA === tnB
      if (!sameById && !sameByName) continue

      const bidA = a.bindingId
      const bidB = b.bindingId
      if (
        bidA !== undefined &&
        bidB !== undefined &&
        bidA !== bidB &&
        Array.isArray(a.data) &&
        Array.isArray(b.data) &&
        a.data.length > 0 &&
        b.data.length > 0
      ) {
        continue
      }
      union(i, j)
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
      const pks = b.primaryKeyFields
      if (!pkFields?.length && Array.isArray(pks) && pks.length > 0) {
        pkFields = pks.map(f => String(f).trim()).filter(Boolean)
      }
    }
    let merged: any[] = []
    for (const b of group) {
      merged = mergeSubTableRowsByRowId(merged, Array.isArray(b.data) ? b.data : [], pkFields)
    }
    if (merged.length === 0) continue
    const snapshot = merged.map(r => ({ ...r }))
    for (const b of group) {
      b.data = snapshot
    }
  }
}
