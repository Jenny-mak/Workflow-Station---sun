/**
 * MI link-child corruption scrub / repair for {@code __subTables__} payloads, plus nested-row
 * flattening into the top-level variables map before submit / after load.
 */

import { isAllocatedUuidPrimaryKey, MI_LINK_CHILD_SCALAR_KEYS, rowValueIsOwnAllocatedPrimaryKey } from './internal'
import { PLATFORM_ROW_UUID_FIELD } from '@/utils/subTableRowIdentity'
import { subTableFieldValueKey } from './subTableCore'
import { mergeSubTableRowsByRowId } from './subTableRowMerge'
import type { MiChildFkConfig } from './miLinkChildIdentity'
import { stripForeignParticipantIdIdwFromLinkChildRow,
  miChildFkConfigOfBinding,
  resolveMiChildPrimaryKeyColumns,
} from './miLinkChildIdentity'

/**
 * Link Form / 「表格下表单」在编辑态常把子表行只写在 {@code parentRow.__subTables__[childBindingId]}，而流程变量提交
 * ({@code __subTables__} 顶层 map) 需要同一份行也挂在 {@code __subTables__[childKey]}，待办加载的
 * {@code getSavedSubTableRows} 才能命中。本函数在原位多轮提升（处理链式嵌套）；入参应为普通 JSON 形态的对象。
 */
function normalizeFkIdForMatchLocal(v: unknown): string | null {
  if (v === undefined || v === null) return null
  const s = String(v).trim()
  return s === '' ? null : s
}

function miLinkChildRowHasFormPayload(rec: Record<string, unknown>): boolean {
  for (const [k, v] of Object.entries(rec)) {
    if (k.startsWith('__') || MI_LINK_CHILD_SCALAR_KEYS.has(k)) continue
    if (v === undefined || v === null) continue
    if (typeof v === 'string' && v.trim() === '') continue
    return true
  }
  return false
}

function repairMiCorruptLinkChildRowId(
  rec: Record<string, unknown>,
  parentKey: string
): Record<string, unknown> {
  const n = Number(parentKey)
  const fixedId = Number.isFinite(n) && String(n) === parentKey ? n : parentKey
  return { ...rec, id: fixedId }
}

/**
 * MI link-child rows may carry another participant's stale {@code id} while {@code id_idw} matches the parent
 * expansion key (runtime: id=44, id_idw=88). Thin placeholders are dropped; rows with real form payload keep
 * sex/age/etc. and get {@code id} aligned to the parent expansion key.
 *
 * When {@code id} is already an allocated UUID PK, {@code id_idw} mirroring the parent expansion key is
 * erroneous — strip {@code id_idw} instead of overwriting the UUID (People Save path).
 */
export function scrubMiCorruptLinkChildRowsForParent(
  subTables: Record<string, unknown>,
  parentIdIdw: string | number,
  options?: {
    skipSliceKeys?: Set<string> | null
    /** 按切片 key 取该表的 FK 配置。没有对应配置时不做 FK 相关修复（不猜列名）。 */
    fkConfigForSliceKey?: ((sliceKey: string) => MiChildFkConfig | null) | null
  },
): void {
  const key = normalizeFkIdForMatchLocal(parentIdIdw)
  if (key == null) return
  const skipKeys = options?.skipSliceKeys ?? null

  /**
   * MI collection (Sub Task / dashboard) slices keep {@code id_idw} as the participant primary key, so
   * {@code id_idw === parentIdIdw} is legitimate — never strip it there. The strip/repair logic targets
   * link-child slices (People etc.) only; on collection slices we leave rows untouched but still recurse
   * nested so genuine link-child rows under a participant parent are still cleaned.
   */
  const repairSlice = (
    rows: unknown[],
    isCollectionSlice: boolean,
    sliceFkConfig: MiChildFkConfig | null,
  ): unknown[] => {
    const out: unknown[] = []
    for (const row of rows) {
      if (!row || typeof row !== 'object') {
        out.push(row)
        continue
      }
      const rec = row as Record<string, unknown>
      const cidw = normalizeFkIdForMatchLocal(rec.id_idw)
      const cid = normalizeFkIdForMatchLocal(rec.id)
      if (!isCollectionSlice && cidw === key && cid != null && cid !== key) {
        if (miLinkChildRowHasFormPayload(rec)) {
          // 按配置判「这是不是本行自己被分配的主键」；配置取不到才退回 UUID 形状判据。
          // 形状判据对 `prefixedSequence` 主键（Corr-000004 / Test-000017）恒假。
          if (rowValueIsOwnAllocatedPrimaryKey(
            rec.id, rec, resolveMiChildPrimaryKeyColumns(sliceFkConfig), key,
          ) ?? isAllocatedUuidPrimaryKey(rec.id)) {
            const cleaned = { ...rec }
            delete cleaned.id_idw
            const nest = cleaned.__subTables__
            if (nest && typeof nest === 'object' && !Array.isArray(nest)) {
              scrubMiCorruptLinkChildRowsForParent(nest as Record<string, unknown>, parentIdIdw, options)
            }
            out.push(cleaned)
          } else {
            const repaired = repairMiCorruptLinkChildRowId(rec, key)
            const nest = repaired.__subTables__
            if (nest && typeof nest === 'object' && !Array.isArray(nest)) {
              scrubMiCorruptLinkChildRowsForParent(nest as Record<string, unknown>, parentIdIdw, options)
            }
            out.push(repaired)
          }
        }
        continue
      }
      // Heal a current-participant link-child row that carries a DIFFERENT participant's id_idw (legacy
      // corruption from the seed/collapse leak, #1444): structural FK already anchors it here and id is a
      // UUID, so the foreign id_idw is spurious and would make load-side participant filters reject the row.
      const healed =
        !isCollectionSlice
          ? stripForeignParticipantIdIdwFromLinkChildRow(rec, parentIdIdw, sliceFkConfig)
          : rec
      const nest = healed.__subTables__
      if (nest && typeof nest === 'object' && !Array.isArray(nest)) {
        scrubMiCorruptLinkChildRowsForParent(nest as Record<string, unknown>, parentIdIdw, options)
      }
      out.push(healed)
    }
    return out
  }

  for (const [sliceKey, val] of Object.entries(subTables)) {
    if (!Array.isArray(val)) continue
    const cleaned = repairSlice(
      val,
      skipKeys?.has(String(sliceKey)) ?? false,
      options?.fkConfigForSliceKey?.(String(sliceKey)) ?? null,
    )
    subTables[sliceKey] = cleaned
    subTables[String(sliceKey)] = cleaned
  }
}

/**
 * A previously hoisted nested row that went through backend PK/FK enrichment gains fields the
 * still-un-enriched nested origin lacks (row_id, FK column, audit columns). The row_id-based merge
 * can never re-match the two, so every persist cycle appended a duplicate (ATM Demo: nested
 * attachment doubled in the TODO top-level table). Detect that shape: every non-empty business field
 * of the nested copy equals the flat row's value, and the flat row carries at least one extra
 * non-empty field — then the flat row IS this nested row, post-enrichment.
 *
 * Object-valued cells (LOOKUP selections, file descriptors) take part in the comparison via
 * {@link subTableFieldValueKey}: skipping them made two grandchild rows that differ ONLY by their
 * lookup selection look identical, so the second one was dropped instead of hoisted.
 */
function nestedCopyMatchesEnrichedFlatRow(
  nested: Record<string, unknown>,
  flat: Record<string, unknown>
): boolean {
  let comparedFields = 0
  for (const [k, v] of Object.entries(nested)) {
    if (k.startsWith('__')) continue
    const nv = subTableFieldValueKey(v)
    if (nv == null) continue
    const fv = subTableFieldValueKey(flat[k])
    if (fv == null || fv !== nv) return false
    comparedFields++
  }
  if (comparedFields === 0) return false
  for (const [k, v] of Object.entries(flat)) {
    if (k.startsWith('__')) continue
    if (subTableFieldValueKey(v) == null) continue
    if (subTableFieldValueKey(nested[k]) == null) return true
  }
  return false
}

/** Fold the dropped nested copy's own nested slices into the matched flat row so grandchild rows survive. */
function absorbNestedSubTablesIntoFlatRow(
  nested: Record<string, unknown>,
  flat: Record<string, unknown>
): void {
  const nSub = nested.__subTables__
  if (!nSub || typeof nSub !== 'object' || Array.isArray(nSub)) return
  const fSubRaw = flat.__subTables__
  const fSub =
    fSubRaw && typeof fSubRaw === 'object' && !Array.isArray(fSubRaw)
      ? (fSubRaw as Record<string, unknown>)
      : {}
  for (const [k, arr] of Object.entries(nSub as Record<string, unknown>)) {
    if (!Array.isArray(arr) || arr.length === 0) continue
    const cur = fSub[k]
    fSub[k] =
      Array.isArray(cur) && cur.length > 0
        ? mergeSubTableRowsByRowId(arr as any[], cur as any[], null)
        : [...(arr as any[])]
  }
  flat.__subTables__ = fSub
}

/**
 * 父行的标识。**当前实现是按列名猜的，已知在主键不叫这三个名字的表上失效。**
 *
 * <p><b>原注释是错的</b>，说「`row_id` 是平台行标识，由平台写入，不是猜业务列名」。实测不成立：
 * `ATM_Transaction` 的 `row_id` 是**设计器配置的主键**（`is_primary_key=true`，策略
 * `prefixedSequence`），值形如 `ATM-DC-PW-TRANS-000004`；同一行另有真正的平台键
 * `platformRowUuid`。也就是说这里读到的一直是配置主键，只是碰巧那张表的主键就叫 `row_id`，
 * 于是被误认为读的是平台键。
 *
 * <p><b>已知缺陷。</b>子表的结构外键存的是父行**配置主键**的值（实测
 * `related_transaction_id = 'ATM-DC-PW-TRANS-000004'`）。父表主键若叫 `correspondence_id`、
 * `case_number` 等，这三个名字一个都不匹配 → 返回 null → 下面「删到空」的分支直接 `continue`，
 * **父行下最后一行删不掉、刷新后复活**。这与 `miLinkChildRows` 当年用 UUID 正则判主键、
 * 导致所有 `prefixedSequence` 主键的表集体失效是同一族问题。
 *
 * <p><b>已修。</b>{@link flattenNestedSubTableRowsIntoPayload} 现在收「slice key → 该表配置主键」
 * 的映射，持有 binding 的调用方（`useProcessStartSubTables` / `useTaskForm`）已透传，
 * 本函数按配置主键 → 平台标识的顺序解析；两者都取不到就返回 null（判不出，不猜）。
 */
function resolveFlattenParentKey(
  parentRow: Record<string, unknown>,
  primaryKeyFields?: readonly string[] | null,
): string | null {
  // 配置优先：主键叫什么由设计器决定（`correspondence_id` / `case_number` / …）。
  for (const pk of primaryKeyFields ?? []) {
    const name = String(pk ?? '').trim()
    if (!name) continue
    const v = normalizeFkIdForMatchLocal(parentRow[name])
    if (v != null) return v
  }
  // 平台生成的行标识：未配主键的表靠它。两者都取不到 = 判不出父行身份，返回 null，
  // 调用方（「删到空」分支）据此跳过，不动任何行——保守侧。
  //
  // 这里曾有一段 `['row_id','id_idw','id']` 兜底。已删除：主键列名是配置、按表生成，
  // 写死三个拼法只对碰巧叫这些名字的表有效，对 `correspondence_id`、`case_number` 等
  // 一律解析不出——而它给人的错觉是「已经兜住了」，反而掩盖了缺陷。
  return normalizeFkIdForMatchLocal(parentRow[PLATFORM_ROW_UUID_FIELD])
}

/**
 * 顶层的这一行是不是挂在 `parentKey` 这个父行下。
 *
 * <p>扫该行的所有标量字段找与 `parentKey` 相等的值 —— 命中即认为是它的外键。
 * 这里没有 binding 的 `fieldDefinitions` 可读，但判据仍是**值相等**而不是列名猜测：
 * 父行标识是平台生成的唯一串（`ATM-DC-PW-TRANS-000007` / UUID），
 * 不会与无关业务字段偶然相等。一个都对不上就返回 false（不动它）。
 */
function flattenRowBelongsToParent(row: unknown, parentKey: string): boolean {
  if (!row || typeof row !== 'object') return false
  for (const [k, v] of Object.entries(row as Record<string, unknown>)) {
    if (k === '__subTables__' || k.startsWith('__')) continue
    if (normalizeFkIdForMatchLocal(v) === parentKey) return true
  }
  return false
}

/**
 * @param primaryKeyFieldsBySliceKey 每个 slice key 对应那张表**配置的主键列**。
 *        给了它，{@link resolveFlattenParentKey} 才能按配置解析父行标识，而不是猜
 *        `row_id` / `id_idw` / `id` 三个名字——主键叫 `correspondence_id`、`case_number`
 *        的表在猜名字下解析不出父行标识，「删到空」分支直接跳过，
 *        表现为**父行下最后一行删不掉、刷新后复活**。
 *        不传时保持旧的猜名字行为（调用方手上没有 binding 时的退路）。
 */
export function flattenNestedSubTableRowsIntoPayload(
  subTables: Record<string, unknown>,
  maxPasses = 8,
  primaryKeyFieldsBySliceKey?: Readonly<Record<string, readonly string[] | null | undefined>> | null,
): void {
  for (let pass = 0; pass < maxPasses; pass++) {
    let touched = false
    for (const [parentSliceKey, val] of Object.entries(subTables)) {
      if (!Array.isArray(val)) continue
      for (const row of val) {
        if (!row || typeof row !== 'object') continue
        const nest = (row as Record<string, unknown>).__subTables__
        if (!nest || typeof nest !== 'object') continue
        for (const [childKey, childVal] of Object.entries(nest)) {
          /**
           * **删到空**：这个父行的嵌套切片被清空了，顶层里属于它的行必须一并删掉。
           *
           * <p>此前这里对空数组直接 `continue`，于是「这个父行已经没有子行了」这条信息
           * 永远传不到顶层，顶层残留的旧行成了唯一真相 —— 用户在 Link Form 里删掉最后一行、
           * 刷新后它又回来（实测 task a736e30f：`tx[0].__subTables__` 已是 `[]`，
           * 顶层却仍有 `Corr-000039`）。删到只剩一行时不暴露，因为非空会走下面的合并。
           *
           * <p>只删**外键确实指向本父行**的行；父行标识解析不出、或顶层行没有可比外键时
           * 一律不动（保守侧，避免误删别的父行的数据）。
           */
          if (Array.isArray(childVal) && childVal.length === 0) {
            const prevTop = subTables[childKey]
            if (!Array.isArray(prevTop) || prevTop.length === 0) continue
            const parentKey = resolveFlattenParentKey(
              row as Record<string, unknown>,
              primaryKeyFieldsBySliceKey?.[parentSliceKey],
            )
            if (parentKey == null) continue
            const kept = (prevTop as any[]).filter(
              r => !flattenRowBelongsToParent(r, parentKey),
            )
            if (kept.length !== prevTop.length) {
              subTables[childKey] = kept
              touched = true
            }
            continue
          }
          if (!Array.isArray(childVal) || childVal.length === 0) continue
          const prev = subTables[childKey]
          const prevRows = Array.isArray(prev) ? [...(prev as any[])] : []
          // A nested copy whose flat counterpart already went through PK enrichment must merge into
          // it, not append next to it (see nestedCopyMatchesEnrichedFlatRow).
          let hoistRows = childVal as any[]
          if (prevRows.length > 0) {
            hoistRows = hoistRows.filter(r => {
              if (!r || typeof r !== 'object') return true
              const rec = r as Record<string, unknown>
              const enriched = prevRows.find(
                p =>
                  p &&
                  typeof p === 'object' &&
                  nestedCopyMatchesEnrichedFlatRow(rec, p as Record<string, unknown>)
              )
              if (!enriched) return true
              absorbNestedSubTablesIntoFlatRow(rec, enriched as Record<string, unknown>)
              return false
            })
          }
          // The existing top-level slice is the authoritative binding data; a nested copy is a
          // derivative cache that may lag behind (e.g. a prior userTask's collection row still holds a
          // stale link-child age while the current task already persisted a fresh one). Merge with the
          // nested copy as the base so the authoritative top-level row's filled fields win, while
          // nested-only rows are still hoisted and empty top-level fields are filled. (#1443)
          const merged =
            prevRows.length > 0
              ? mergeSubTableRowsByRowId(hoistRows, prevRows, null)
              : mergeSubTableRowsByRowId(prevRows, childVal as any[], null)
          subTables[childKey] = merged
          subTables[String(childKey)] = merged
          touched = true
        }
      }
    }
    if (!touched) break
  }
}
