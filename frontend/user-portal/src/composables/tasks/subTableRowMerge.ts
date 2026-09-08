/** Sub-table row merging by primary key / rowKey, including MI dashboard status-aware conflict resolution. */

import {
  incomingIsStrictNonMiKeySubset, mergeMiCurrentNodeForTerminal, mergeMiCurrentNodeInFlight,
  mergeMiCurrentNodePreferPrevious, mergeMiTaskStatusPreferTerminal, miDashboardSliceRichness,
  miSubFormOrdinalHint, miTaskStatusIsTerminal, roughNonEmptyFieldCount,
} from './internal'
import {
  resolveMiDashboardFieldNames,
  type MiDashboardFieldNames,
} from './subTableBindingKinds'
import { PLATFORM_ROW_UUID_FIELD } from '@/utils/subTableRowIdentity'

const ROW_KEY_MERGE_SEP = '\u001f'

/**
 * Normalize PK / rowKey scalar values so merge maps treat `555` and `"555"` as the same row
 * (JSON.stringify would not; this caused duplicate MI rows in My Request when variables mixed types).
 */
function scalarForMergeKey(v: unknown): string | null {
  if (v == null) return null
  if (typeof v === 'number' && Number.isFinite(v)) return String(v)
  if (typeof v === 'bigint') return String(v)
  if (typeof v === 'string') {
    const t = v.trim()
    return t === '' ? null : t
  }
  if (typeof v === 'boolean') return v ? 'true' : 'false'
  try {
    return JSON.stringify(v)
  } catch {
    const s = String(v).trim()
    return s === '' ? null : s
  }
}

function canonicalRowKeyFromPayload(r: Record<string, unknown>): string | null {
  const rk = r.rowKey
  if (rk && typeof rk === 'object' && !Array.isArray(rk)) {
    const o = rk as Record<string, unknown>
    return Object.keys(o)
      .sort()
      .map(k => {
        const part = scalarForMergeKey(getRowValueIgnoreCase(o, k) ?? o[k])
        return part != null ? `${k}=${part}` : `${k}=${JSON.stringify(o[k])}`
      })
      .join(ROW_KEY_MERGE_SEP)
  }
  return null
}

/**
 * Same idea as {@code SubTableRowKeySupport.getRowValueIgnoreCase} — form rows use mixed key casing.
 */
function getRowValueIgnoreCase(row: Record<string, unknown>, key: string): unknown {
  if (!row || !key) return undefined
  if (Object.prototype.hasOwnProperty.call(row, key)) {
    const v = row[key]
    if (v != null && !(typeof v === 'string' && v.trim() === '')) return v
  }
  const lower = key.toLowerCase()
  for (const k of Object.keys(row)) {
    if (k.startsWith('__')) continue
    if (k.toLowerCase() === lower) {
      const v = row[k]
      if (v != null && !(typeof v === 'string' && v.trim() === '')) return v
    }
  }
  return undefined
}

/**
 * PK column value on row or nested {@code rowKey}. For a single-column PK, matches
 * {@code SubTableRowKeySupport.rowKeyFromVariableRow}: {@code rowId}, and the platform {@code id}/{@code id_idw} alias pair.
 */
function rowValueForPkFieldSingle(row: Record<string, unknown>, field: string, pkFieldCount: number): unknown {
  const f = String(field || '').trim()
  if (!f) return undefined

  let v = getRowValueIgnoreCase(row, f)
  if (v != null) return v

  const rk = row.rowKey
  if (rk && typeof rk === 'object' && !Array.isArray(rk)) {
    const rko = rk as Record<string, unknown>
    v = getRowValueIgnoreCase(rko, f)
    if (v != null) return v
  }

  if (pkFieldCount !== 1) return undefined

  v = getRowValueIgnoreCase(row, 'rowId')
  if (v != null) return v
  if (rk && typeof rk === 'object' && !Array.isArray(rk)) {
    v = getRowValueIgnoreCase(rk as Record<string, unknown>, 'rowId')
    if (v != null) return v
  }

  /**
   * `id` ⇄ `id_idw`：**同一物理行在两种表示间的已知别名**，不是「猜主键叫什么」。
   *
   * <p>我曾把它当作猜名字删掉，被 MI 门禁挡下（`mergeSubTableRowsMiMerge` /
   * `mergeMiCollectionSubTableRows`）。它编码的是一个真实运行时形态：同一行由两个来源送达，
   * MI collection 展开侧暴露 `id_idw`、hydrate 侧只暴露 `id`
   * （实测 `{id_idw: 1123}` 与 `{id: 1123}` 是同一行），删掉后两者合不到一起、行重复。
   *
   * <p>与被删掉的那些名单的区别：**这里 `f` 已经是配置主键**（上面按 `f` 查过了），
   * 本段只在配置主键恰好叫 `id`/`id_idw` 时，去另一种表示里找**同一个值**；
   * 而被删的那些是在完全不知道主键叫什么时拿名字去撞。
   */
  const fl = f.toLowerCase()
  if (fl === 'id' || fl === 'id_idw') {
    const alias = fl === 'id' ? 'id_idw' : 'id'
    v = getRowValueIgnoreCase(row, alias)
    if (v != null) return v
    if (rk && typeof rk === 'object' && !Array.isArray(rk)) {
      v = getRowValueIgnoreCase(rk as Record<string, unknown>, alias)
      if (v != null) return v
    }
  }

  return undefined
}

function compositePkMergeKey(row: Record<string, unknown>, pkFieldNames: string[]): string | null {
  const parts: string[] = []
  const n = pkFieldNames.length
  for (const f of pkFieldNames) {
    const part = scalarForMergeKey(rowValueForPkFieldSingle(row, f, n))
    if (part == null) return null
    parts.push(`${f}=${part}`)
  }
  return `__pk__${parts.join('\u001e')}`
}

/**
 * True when every designer PK column resolves on the row (incl. nested {@code rowKey} /
 * {@code id}↔{@code id_idw} aliases used by {@link mergeSubTableRowsByRowId}).
 */
export function rowResolvesDesignerPrimaryKey(
  row: unknown,
  pkFieldNames?: string[] | null,
): boolean {
  if (!row || typeof row !== 'object') return false
  const pkCols =
    Array.isArray(pkFieldNames) && pkFieldNames.length > 0
      ? pkFieldNames.map(f => String(f).trim()).filter(Boolean)
      : []
  if (pkCols.length === 0) return false
  return compositePkMergeKey(row as Record<string, unknown>, pkCols) != null
}

/**
 * True when two rows denote the SAME logical row.
 *
 * <p>Uses the designer PK when both rows resolve it; otherwise falls back to the same content
 * fingerprint {@link mergeSubTableRowsByRowId} keys on, so callers do not have to reimplement (and
 * drift from) that key resolution. Used to decide whether a row still present in a derivative
 * nested cache also still exists in the authoritative live rows.
 */
export function sameSubTableRow(
  a: unknown,
  b: unknown,
  pkFieldNames?: string[] | null,
): boolean {
  if (!a || typeof a !== 'object' || !b || typeof b !== 'object') return false
  const pkCols =
    Array.isArray(pkFieldNames) && pkFieldNames.length > 0
      ? pkFieldNames.map(f => String(f).trim()).filter(Boolean)
      : []
  const ra = a as Record<string, unknown>
  const rb = b as Record<string, unknown>
  if (pkCols.length > 0) {
    const ka = compositePkMergeKey(ra, pkCols)
    const kb = compositePkMergeKey(rb, pkCols)
    if (ka != null && kb != null) return ka === kb
  }
  // No resolvable designer PK on both sides: fall back to the platform-generated row identity, then
  // to the canonical `rowKey`. Returns false when neither side carries any identity — "cannot tell"
  // must not read as "same row", or a caller pruning stale copies would drop a row it cannot
  // identify.
  //
  // This step used to try `['id', 'row_id', 'rowId']` — guesses at what a designer might have named
  // their key. Measured in dev: 13 tables declare a business column called `id` and NOT ONE of them
  // declares it as the primary key (Loan Application, Applicant Information, Collateral Details, …),
  // and all 13 configure no primary key at all — so they land in exactly this branch and two rows
  // whose unrelated business `id` values collide were silently merged into one. `row_id` was doubly
  // wrong: the doc block below already says there is "deliberately NO `row_id` step" because it is a
  // per-snapshot value that differs between the engine-variables copy and the portal copy of the
  // same physical row, which merged one MI participant's row over another's.
  for (const idField of [PLATFORM_ROW_UUID_FIELD]) {
    const va = scalarForMergeKey(getRowValueIgnoreCase(ra, idField))
    const vb = scalarForMergeKey(getRowValueIgnoreCase(rb, idField))
    if (va != null && vb != null) return va === vb
  }
  const cka = canonicalRowKeyFromPayload(ra)
  const ckb = canonicalRowKeyFromPayload(rb)
  return cka != null && ckb != null && cka === ckb
}

/**
 * Merge sub-table rows for the same logical table. Later rows win on field conflicts.
 *
 * {@code task_status} / {@code task_current_node} (multi-instance dashboard mirrors) use terminal-wins merge so a stale
 * {@code IN_PROGRESS} slice cannot overwrite {@code COMPLETED} when bindings align the same physical row.
 *
 * Key resolution order:
 * 1. Designer primary key columns (`pkFieldNames`) when present and all parts are resolvable
 *    (from top-level row and/or nested `rowKey`). This must beat raw `rowKey` so My Request
 *    merges snapshots that use only `id` with ones that attach a full `rowKey` map.
 * 2. The platform-generated row identity (`platformRowUuid`) when no designer PK list — before bare
 *    `rowKey`, so mixed payloads dedupe.
 * 3. Flowable / platform `rowKey` object canonical string.
 * 4. Stable content fingerprint — when PK values are missing so rows are not dropped.
 *
 * There is deliberately NO step keyed on a guessed column name — not `id`, `rowId`, nor `row_id`.
 * Which column identifies a row is configuration, and it arrives as `pkFieldNames`; a name match
 * proves nothing about what the value means. `row_id` was doubly wrong even as a guess: it is a
 * per-snapshot frontend value, so the same physical row carries different ones in the
 * engine-variables copy and the portal `subTableData` copy, and keying on it merged one MI
 * participant's row over another's and submitted the wrong row. A table whose designer PK genuinely
 * IS `row_id` (e.g. ATM_Transaction) still keys on it — through `pkFieldNames` like any other PK.
 */
export function mergeSubTableRowsByRowId(
  existing: any[] | undefined,
  incoming: any[],
  pkFieldNames?: string[] | null,
  miFields?: MiDashboardFieldNames | null
): any[] {
  const { statusField: miStatusField, currentNodeField: miCurrentNodeField } =
    resolveMiDashboardFieldNames(miFields)
  const byId = new Map<string, any>()
  let keyFallbackSeq = 0
  const pkCols =
    Array.isArray(pkFieldNames) && pkFieldNames.length > 0
      ? pkFieldNames.map(f => String(f).trim()).filter(Boolean)
      : null

  const contentKeyNoPk = (r: Record<string, unknown>): string => {
    try {
      const keys = Object.keys(r).filter(k => k !== '__subTables__' && !k.startsWith('__'))
      if (keys.length === 0) return '__empty__'
      return keys.map(k => `${k}:${JSON.stringify(r[k])}`).join('\u001f')
    } catch {
      return `__key_err__${keyFallbackSeq++}`
    }
  }

  /**
   * Two snapshots may describe the same PK row; a later "list-only" payload often carries {@code ''} / {@code null}
   * or an empty {@code __subTables__} map and must not wipe richer fields from process start / nested hydration.
   */
  const mergeRowSnapshotsPreferFilled = (
    previous: Record<string, unknown>,
    incoming: Record<string, unknown>
  ): Record<string, unknown> => {
    const MI_STATUS_KEY = miStatusField
    const MI_NODE_KEY = miCurrentNodeField
    const out: Record<string, unknown> = { ...previous }
    for (const [key, val] of Object.entries(incoming)) {
      if (val === undefined) continue
      if (key === MI_STATUS_KEY || key === MI_NODE_KEY) continue

      if (key === 'assignee' || key === 'assignee_user_id' || key === 'assignee_id') {
        out[key] = val
        if (!('assignee_display_name' in incoming)) {
          delete out.assignee_display_name
        }
        continue
      }

      if (key === '__subTables__') {
        const pSub = previous[key]
        if (val !== null && typeof val === 'object' && !Array.isArray(val)) {
          const nObj = val as Record<string, unknown>
          if (Object.keys(nObj).length === 0) {
            continue
          }
          if (pSub !== null && typeof pSub === 'object' && !Array.isArray(pSub)) {
            out[key] = { ...(pSub as Record<string, unknown>), ...nObj }
          } else {
            out[key] = { ...nObj }
          }
        } else if (val !== null && val !== undefined) {
          out[key] = val
        }
        continue
      }

      if (val === null) {
        const cur = out[key]
        if (cur !== undefined && cur !== null) continue
        out[key] = val
        continue
      }

      if (typeof val === 'string' && val.trim() === '') {
        const cur = out[key]
        if (
          cur !== undefined &&
          cur !== null &&
          !(typeof cur === 'string' && cur.trim() === '')
        ) {
          continue
        }
      }

      out[key] = val
    }

    // 本 FU 没配 MI 进度列 → 没有可做 terminal-wins 的状态/节点列，普通合并即为结果。
    if (MI_STATUS_KEY === null || MI_NODE_KEY === null) {
      return out
    }

    const mergedStatus = mergeMiTaskStatusPreferTerminal(out[MI_STATUS_KEY], incoming[MI_STATUS_KEY])
    if (mergedStatus !== undefined) {
      out[MI_STATUS_KEY] = mergedStatus
    }
    const statusStr = String(out[MI_STATUS_KEY] ?? '').trim()
    const terminal = miTaskStatusIsTerminal(statusStr)
    const rp = miDashboardSliceRichness(previous, miStatusField, miCurrentNodeField) * 512 + roughNonEmptyFieldCount(previous)
    const ri = miDashboardSliceRichness(incoming, miStatusField, miCurrentNodeField) * 512 + roughNonEmptyFieldCount(incoming)
    let mergedNode: string | undefined
    if (terminal) {
      mergedNode = mergeMiCurrentNodeForTerminal(out[MI_NODE_KEY], incoming[MI_NODE_KEY])
    } else {
      const oAccum = miSubFormOrdinalHint(out[MI_NODE_KEY])
      const oIncoming = miSubFormOrdinalHint(incoming[MI_NODE_KEY])
      // Furthest BPMN sub form step wins over slice richness (thin id row vs fat id_idw-only stale row).
      if (oAccum !== null && oIncoming !== null && oIncoming !== oAccum) {
        mergedNode =
          oIncoming > oAccum
            ? String(incoming[MI_NODE_KEY] ?? '').trim()
            : String(out[MI_NODE_KEY] ?? '').trim()
      } else if (ri > rp) {
        mergedNode = mergeMiCurrentNodeInFlight(out[MI_NODE_KEY], incoming[MI_NODE_KEY])
      } else if (rp > ri) {
        mergedNode = mergeMiCurrentNodePreferPrevious(out[MI_NODE_KEY], incoming[MI_NODE_KEY])
      } else if (incomingIsStrictNonMiKeySubset(previous, incoming, miStatusField, miCurrentNodeField)) {
        mergedNode = mergeMiCurrentNodePreferPrevious(out[MI_NODE_KEY], incoming[MI_NODE_KEY])
      } else {
        mergedNode = mergeMiCurrentNodeInFlight(out[MI_NODE_KEY], incoming[MI_NODE_KEY])
      }
    }
    if (mergedNode !== undefined) {
      out[MI_NODE_KEY] = mergedNode
    }

    return out
  }

  const add = (r: any) => {
    if (!r || typeof r !== 'object') return
    const o = r as Record<string, unknown>
    let k: string | null = null

    if (pkCols?.length) {
      const pkKey = compositePkMergeKey(o, pkCols)
      if (pkKey != null) k = pkKey
    }

    if (k == null && !pkCols?.length) {
      const rawId = rowValueForPkFieldSingle(o, 'id', 1)
      const idPart = scalarForMergeKey(rawId)
      if (idPart != null) k = idPart
    }

    if (k == null) {
      const canon = canonicalRowKeyFromPayload(o)
      if (canon != null && canon !== '') {
        k = `__rowKey__${canon}`
      }
    }

    if (k == null) {
      k = `__noid__${contentKeyNoPk(o)}`
    }

    const cur = byId.get(k)
    byId.set(
      k,
      cur ? mergeRowSnapshotsPreferFilled(cur as Record<string, unknown>, o) : { ...r },
    )
  }
  for (const r of existing || []) add(r)
  for (const r of incoming || []) add(r)
  return Array.from(byId.values())
}
