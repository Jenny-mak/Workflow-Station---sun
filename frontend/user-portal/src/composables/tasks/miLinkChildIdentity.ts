/**
 * MI link-child row identity: structural FK resolution, participant ownership checks,
 * parent/child row pairing and row quality scoring.
 */

import {
  isAllocatedUuidPrimaryKey,
  MI_LINK_CHILD_SCALAR_KEYS,
  normalizeMiLinkMatchId,
  rowValueIsOwnAllocatedPrimaryKey,
} from './internal'

/**
 * The designer config a caller must supply to interpret a link-child row's parent reference.
 *
 * <p>`fieldDefinitions` is the binding's own field metadata — the single source of truth for which
 * columns are foreign keys. `miCollectionTableId` narrows multi-FK children to the FK that points
 * at the MI collection. Both come straight off the sub-table binding.
 *
 * <p>`primaryKeyFields` is the designer's declared primary key for the CHILD table. It exists so
 * row-identity code never falls back to the literal column name {@code id}: People's PK is
 * {@code idqc} in the Multi-Instance Subtask Demo, and reading {@code row.id} there silently
 * answered "this row has no allocated PK" for every row — which made
 * {@code collapseMiLinkChildRowsToOnePerParticipant} treat two distinct rows as fragments of one
 * and merge them, so a participant's second People row vanished on reload.
 */
export interface MiChildFkConfig {
  fieldDefinitions?: Array<{
    fieldName?: string
    isForeignKey?: boolean
    isPrimaryKey?: boolean
    refTableId?: number | null
  }> | null
  bindingForeignKeyField?: string | null
  bindingLinkMode?: string | null
  miCollectionTableId?: number | null
  /** Designer primary key of the child table (`binding.primaryKeyFields`). */
  primaryKeyFields?: string[] | null
}

/**
 * The child table's designer primary key columns, from the binding's own config.
 *
 * <p>Prefers the binding's `primaryKeyFields`, else the `isPrimaryKey` field definitions. Returns
 * an empty array when neither is available — callers must then skip PK-dependent logic rather than
 * guess a column name.
 */
export function resolveMiChildPrimaryKeyColumns(config?: MiChildFkConfig | null): string[] {
  const declared = (config?.primaryKeyFields ?? [])
    .map(f => String(f ?? '').trim())
    .filter(Boolean)
  if (declared.length > 0) return declared
  return (config?.fieldDefinitions ?? [])
    .filter(f => f?.isPrimaryKey)
    .map(f => String(f?.fieldName ?? '').trim())
    .filter(Boolean)
}

/**
 * 「这个值是这一行自己被分配的主键吗」——**先读配置**，配置取不到才退回形状判据。
 *
 * <p>原来这里直接调 {@link isAllocatedUuidPrimaryKey}（UUID 正则）。dev 实测 8 张配了主键的表
 * 有 5 张用 `prefixedSequence`（`Corr-000004` / `Test-000017`），全都通不过正则；而 MI 参与者表
 * `subtable` 恰恰就是 `prefixedSequence`，等于这条判据在最关键的表上恒假。
 *
 * <p>配置解析得出主键列时按配置回答（`Corr-000004` 与 UUID 一视同仁）；解析不出时保留旧的
 * 形状判据，**不改变既有行为**——本模块多处逻辑依赖它的返回值分支，静默翻转比判错更危险。
 */
function isOwnAllocatedPrimaryKey(
  value: unknown,
  row: Record<string, unknown> | null | undefined,
  config?: MiChildFkConfig | null,
  participantId?: unknown,
): boolean {
  const byConfig = rowValueIsOwnAllocatedPrimaryKey(
    value, row, resolveMiChildPrimaryKeyColumns(config), participantId,
  )
  if (byConfig !== null) return byConfig
  return isAllocatedUuidPrimaryKey(value)
}

/** Count designer business columns filled on a link-child row (excludes id/FK/MI meta). */
export function miLinkChildRowBusinessFieldRank(row: Record<string, unknown>): number {
  let n = 0
  for (const [k, v] of Object.entries(row)) {
    if (k.startsWith('__') || MI_LINK_CHILD_SCALAR_KEYS.has(k)) continue
    if (k === 'sub_task_id' || k === 'main_id') continue
    if (v === undefined || v === null) continue
    if (typeof v === 'string' && v.trim() === '') continue
    n++
  }
  return n
}

/**
 * MI parent row ↔ child binding row pairing for nested {@code __subTables__} patch.
 * Must be the same multi-instance element — never match on shared {@code task_status} alone (all active rows are IN_PROGRESS).
 */
/**
 * Which columns of a link-child row hold its reference to the MI participant row.
 *
 * <p>Resolved from the DESIGNER CONFIG, never from a list of column names. A field is a structural
 * parent reference when the designer marked it `isForeignKey`; when the MI collection's table id is
 * known, only FKs that actually point at it count, so a child with several FKs (parent + a lookup)
 * cannot pick the wrong one.
 *
 * <p>The legacy `bindingForeignKeyField` of an `miParticipantRow` binding is excluded for the same
 * reason {@link filterStructuralFkMetasForBinding} excludes it on the write path: on the collection
 * binding that field names the collection's OWN primary key, not a parent reference.
 */
export function resolveMiChildStructuralFkColumns(config: MiChildFkConfig | null | undefined): string[] {
  const collectionTid = config?.miCollectionTableId != null ? Number(config.miCollectionTableId) : null
  // `foreignKeyField` on an `miParticipantRow` binding names the COLLECTION's own primary key, not a
  // reference to a parent — the same exclusion the write path applies in filterStructuralFkMetasForBinding.
  const isCollectionBinding = String(config?.bindingLinkMode ?? '').trim() === 'miParticipantRow'
  const bindingFk = String(config?.bindingForeignKeyField ?? '').trim()
  const out: string[] = []
  for (const f of config?.fieldDefinitions ?? []) {
    if (!f?.isForeignKey) continue
    const name = String(f.fieldName ?? '').trim()
    if (!name) continue
    if (isCollectionBinding && name.toLowerCase() === bindingFk.toLowerCase()) continue
    if (collectionTid != null && Number.isFinite(collectionTid)) {
      const ref = f.refTableId != null ? Number(f.refTableId) : null
      if (ref == null || !Number.isFinite(ref) || ref !== collectionTid) continue
    }
    out.push(name)
  }
  // No field definitions reached this caller (older binding payloads carry only the binding row's
  // own `foreign_key_field`). That column IS designer config — the FK the binding was wired with —
  // so it is a legitimate second source, unlike a list of column names nobody configured.
  //
  // **只在真的没有字段级定义时才回退。** 有定义、却被上面的 refTableId 过滤全部排除掉时，
  // 那是一个**确定的答案**（"这张表没有指向 collection 的外键"），不是"信息不足"——
  // 此时回退 bindingFk 等于把刚刚正确排除的那一列又放回来：共享附件表指向主表的外键
  // （main_idvab → main）于是被当成"指向参与者"，整行被泄漏过滤丢掉，
  // 实测 assignment 任务加的附件保存进库却渲染 0 行。
  const hasFieldLevelFk = (config?.fieldDefinitions ?? []).some(f => f?.isForeignKey)
  if (out.length === 0 && !hasFieldLevelFk && bindingFk && !isCollectionBinding) return [bindingFk]
  return out
}

/**
 * First non-empty structural FK value on a link-child row (People → participant).
 *
 * <p>`config` names the FK columns; it comes from the designer field definitions, so renaming
 * `sub_task_id` to anything else keeps working. Passing no config means "this caller cannot see the
 * binding", and the answer is `null` — refuse to guess rather than fall back to a column-name list.
 * A hardcoded list silently mis-answered BOTH ways once the demo FU's keys were renamed: a foreign
 * row read as unowned (claimed by the current user) and the user's own row read as someone else's
 * (dropped from the payload on save).
 */
/**
 * Build {@link MiChildFkConfig} straight off a sub-table binding. Use this at every call site that
 * has a binding in scope so the FK columns come from the designer, not from a name list.
 */
export function miChildFkConfigOfBinding(
  binding: {
    fieldDefinitions?: MiChildFkConfig['fieldDefinitions']
    foreignKeyField?: string | null
    bindingLinkMode?: string | null
    primaryKeyFields?: string[] | null
  } | null | undefined,
  miCollectionTableId?: number | null,
): MiChildFkConfig | null {
  if (!binding) return null
  return {
    fieldDefinitions: binding.fieldDefinitions ?? null,
    bindingForeignKeyField: binding.foreignKeyField ?? null,
    bindingLinkMode: binding.bindingLinkMode ?? null,
    miCollectionTableId: miCollectionTableId ?? null,
    primaryKeyFields: binding.primaryKeyFields ?? null,
  }
}

export function resolveMiChildStructuralParentFk(
  childRow: Record<string, unknown>,
  config?: MiChildFkConfig | null,
): string | null {
  for (const fk of resolveMiChildStructuralFkColumns(config)) {
    const cv = normalizeMiLinkMatchId(childRow[fk])
    if (cv) return cv
  }
  return null
}

/**
 * True when a row carries a structural FK (sub_task_id / participant_id / parentId / …) that
 * points back at its OWN primary key. The same physical table is often shared by several form
 * bindings in one process (e.g. an MI participant row read by Assign Task / Sub task / Main);
 * only the binding whose form actually owns the row's writes stamps this self-reference — peer
 * bindings that merely carry an initialization-time copy never populate it. Used to prefer the
 * genuinely-owning binding's field values when merging same-table peers (see
 * {@link ../applicationDetail/subTableRowHelpers}'s applyUnionFindMergeToBindingList), instead of
 * an arbitrary array-order tiebreak that can let a stale copy silently win.
 */
export function rowIsSelfOwnedByStructuralFk(
  row: Record<string, unknown>,
  pkFields: string[] | null | undefined,
  config?: MiChildFkConfig | null,
): boolean {
  if (!pkFields?.length) return false
  const pkValue = pkFields
    .map(f => normalizeMiLinkMatchId(row[f]))
    .find(v => v != null)
  if (pkValue == null) return false
  for (const fk of resolveMiChildStructuralFkColumns(config)) {
    const fkValue = normalizeMiLinkMatchId(row[fk])
    if (fkValue != null && fkValue === pkValue) return true
  }
  return false
}

/**
 * Legacy People rows sometimes carry another participant's stale {@code sub_task_id} while
 * {@code id}/{@code id_idw} already match the current MI element (sub form1 save → sub form2 load).
 * Participant filter would drop those rows and lose age/sex/name from the prior step.
 */
export function repairMisassignedLinkChildStructuralFk(
  row: Record<string, unknown>,
  participantId: string | number,
  config?: MiChildFkConfig | null,
): Record<string, unknown> {
  const pid = normalizeMiLinkMatchId(participantId)
  if (!pid) return row

  const structuralFk = resolveMiChildStructuralParentFk(row, config)
  if (structuralFk === pid) return row

  const childIdIdw = normalizeMiLinkMatchId(row.id_idw)
  const legacyId = normalizeMiLinkMatchId(row.id)
  const rowKeyedToParticipant =
    (childIdIdw === pid && !isOwnAllocatedPrimaryKey(childIdIdw, row, config, pid))
    || (legacyId === pid && !isOwnAllocatedPrimaryKey(legacyId, row, config, pid))

  if (!rowKeyedToParticipant) return row

  // Only repair FK columns the designer actually declared. Writing every name from a guessed list
  // stamped columns the table does not have, which then travelled into the saved row.
  const fkColumns = resolveMiChildStructuralFkColumns(config)
  if (fkColumns.length === 0) return row
  const out = { ...row }
  for (const fk of fkColumns) {
    const cv = normalizeMiLinkMatchId(out[fk])
    if (!cv || cv !== pid) {
      out[fk] = pid
    }
  }
  return out
}

/**
 * True when a link-child row already belongs to a DIFFERENT MI participant (e.g. People placeholder
 * rows for sibling sub-tasks carried in the same binding). The current participant's Save MUST NOT
 * seed the current FK / allocate a PK on these rows — doing so makes them falsely claim the current
 * participant and {@link collapseMiLinkChildRowsToOnePerParticipant} then merges them into one corrupt
 * row (cross-participant {@code id_idw} contamination, #1444). Fresh rows with no participant identity
 * yet (new current row) and the current participant's own rows are NOT foreign.
 */
export function linkChildRowIsForeignParticipantPlaceholder(
  row: Record<string, unknown>,
  myRowId: string | number,
  config?: MiChildFkConfig | null,
): boolean {
  const pid = normalizeMiLinkMatchId(myRowId)
  if (!pid) return false
  const structuralFk = resolveMiChildStructuralParentFk(row, config)
  if (structuralFk) return structuralFk !== pid
  const idIdw = normalizeMiLinkMatchId(row.id_idw)
  // No structural FK yet: a participant-style id_idw pointing at someone else marks a foreign placeholder.
  return !!idIdw && idIdw !== pid && !isOwnAllocatedPrimaryKey(idIdw, row, config, pid)
}

/**
 * The current participant's link-child row (People) uses {@code id} (UUID) as PK and a structural FK
 * ({@code sub_task_id}) as the participant link. Its own {@code id_idw} must NEVER hold a participant id
 * — least of all a DIFFERENT participant's — or load-side participant filters reject the fresh row and
 * fall back to a stale nested copy (#1444). Strip such a corrupt {@code id_idw} when the structural FK
 * already anchors the row to the current participant and {@code id} is an allocated UUID.
 */
export function stripForeignParticipantIdIdwFromLinkChildRow(
  row: Record<string, unknown>,
  myRowId: string | number,
  config?: MiChildFkConfig | null,
): Record<string, unknown> {
  const pid = normalizeMiLinkMatchId(myRowId)
  if (!pid) return row
  const idIdw = normalizeMiLinkMatchId(row.id_idw)
  if (!idIdw || idIdw === pid) return row
  if (isOwnAllocatedPrimaryKey(idIdw, row, config, pid)) return row
  const structuralFk = resolveMiChildStructuralParentFk(row, config)
  if (structuralFk !== pid) return row
  if (!isOwnAllocatedPrimaryKey(normalizeMiLinkMatchId(row.id), row, config, pid)) return row
  const out = { ...row }
  delete out.id_idw
  return out
}

/**
 * Prefer rows with a properly allocated PK over legacy rows where {@code id} was copied from the
 * participant id.
 *
 * <p>「有没有正确分配的主键」按**配置**判定。这里曾内联一份 UUID 正则（与
 * `isAllocatedUuidPrimaryKey` 同款、当时漏改），于是 `prefixedSequence` 主键
 * （`Corr-000004` / `Test-000017`）拿不到 100 分只能拿 40 —— 同一参与者的两行比较时，
 * 真正带分配主键的那行可能输给一行垃圾数据。
 */
export function scoreMiLinkChildRowQuality(
  row: Record<string, unknown>,
  config?: MiChildFkConfig | null,
): number {
  let score = 0
  const structuralFk = resolveMiChildStructuralParentFk(row, config)
  const id = normalizeMiLinkMatchId(row.id)
  if (id) {
    if (isOwnAllocatedPrimaryKey(id, row, config, structuralFk)) {
      score += 100
    } else if (structuralFk && id !== structuralFk) {
      score += 40
    } else if (structuralFk && id === structuralFk) {
      score -= 80
    }
  } else {
    score -= 50
  }
  for (const [k, v] of Object.entries(row)) {
    if (k.startsWith('__') || MI_LINK_CHILD_SCALAR_KEYS.has(k)) continue
    if (v === undefined || v === null) continue
    if (typeof v === 'string' && v.trim() === '') continue
    score += 1
  }
  return score
}

export function miParentRowAlignsWithChildRow(
  parentRow: Record<string, unknown>,
  childRow: Record<string, unknown>,
  config?: MiChildFkConfig | null,
  /** MI collection 的设计器主键列（`binding.primaryKeyFields`）。 */
  collectionPrimaryKeyFields?: string[] | null,
): boolean {
  // 参与者标识**先读设计器主键**，再退回历史列名表。
  //
  // 只认 id_idw / rowId / participant_id / participantId / id 时，一旦设计器把 collection 主键
  // 改成别的名字（本 demo 是 id_idwxwc），parentPk 恒为 undefined → 下面的结构 FK 比较拿不到
  // 参与者值，于是**每一行都判不属于这个宿主行**：嵌套切片被过滤成 0，
  // PortalFormFields 的 participant-scoped 分支再返回 []，用户刚存的 People 行刷新后全不见。
  // 实测 sub_task_idqc="Test-000004" 与宿主 id_idwxwc="Test-000004" 明明相等却被判 false。
  let parentPkNorm: string | null = null
  for (const pk of collectionPrimaryKeyFields ?? []) {
    const name = String(pk ?? '').trim()
    if (!name) continue
    const v = normalizeMiLinkMatchId(parentRow[name])
    if (v) {
      parentPkNorm = v
      break
    }
  }
  if (parentPkNorm == null) {
    const parentPk =
      parentRow.id_idw
      ?? parentRow.rowId
      ?? parentRow.participant_id
      ?? parentRow.participantId
      ?? parentRow.id
    parentPkNorm = normalizeMiLinkMatchId(parentPk)
  }

  /**
   * Structural FK is authoritative for link-child rows: a child's own {@code id_idw} is its OWN PK
   * (sequential ids can collide with another participant's id), so once {@code sub_task_id} etc. is set
   * parentage MUST be decided by it alone — never by comparing the child's id_idw.
   */
  const structuralFk = resolveMiChildStructuralParentFk(childRow, config)
  if (structuralFk) {
    return parentPkNorm != null && structuralFk === parentPkNorm
  }

  const parentIdIdw = normalizeMiLinkMatchId(parentRow.id_idw)
  const childIdIdw = normalizeMiLinkMatchId(childRow.id_idw)
  if (parentIdIdw && childIdIdw && parentIdIdw === childIdIdw) return true

  const parentId = normalizeMiLinkMatchId(parentRow.id)
  const childId = normalizeMiLinkMatchId(childRow.id)
  if (parentId && childId && parentId === childId) return true

  if (!parentPkNorm) return false
  /** Legacy link-form rows keyed child PK {@code id} to parent id_idw when no structural FK. */
  const cv = normalizeMiLinkMatchId(childRow.id)
  if (cv && cv === parentPkNorm) return true
  return false
}

/**
 * True when a link-child row (People, subtable2, …) belongs to the current MI participant.
 * Normally {@code sub_task_id} (structural FK) is authoritative; the child's own {@code id_idw} is NOT a
 * participant key (it is the child's own PK and may collide with another participant's id).
 * Exception: when structural FK is stale but {@code id_idw} + allocated UUID {@code id} still anchor the row
 * to the current participant (sub form1 save → sub form2 load).
 */
export function miLinkChildRowBelongsToParticipant(
  row: Record<string, unknown>,
  participantId: string | number,
  config?: MiChildFkConfig | null,
): boolean {
  const pid = normalizeMiLinkMatchId(participantId)
  if (!pid) return false

  const childIdIdw = normalizeMiLinkMatchId(row.id_idw)
  const legacyId = normalizeMiLinkMatchId(row.id)
  const structuralFk = resolveMiChildStructuralParentFk(row, config)

  if (structuralFk) {
    if (structuralFk === pid) {
      if (
        childIdIdw
        && childIdIdw !== structuralFk
        && !isOwnAllocatedPrimaryKey(childIdIdw, row, config, pid)
        && (isOwnAllocatedPrimaryKey(legacyId, row, config, pid) || legacyId === childIdIdw)
      ) {
        return false
      }
      return true
    }
    if (childIdIdw === pid && !isOwnAllocatedPrimaryKey(childIdIdw, row, config, pid)) {
      if (isOwnAllocatedPrimaryKey(legacyId, row, config, pid) || legacyId === pid) return true
      return false
    }
    return false
  }

  if (childIdIdw === pid) return true
  if (legacyId === pid && !isOwnAllocatedPrimaryKey(legacyId, row, config, pid)) return true

  // 刚在弹窗里新增、还没保存过的行：没有结构 FK（FK 是保存时才种下的），也没有指向任何人的
  // id_idw —— 它**只可能**属于正在编辑的这个参与者，因为别人的行不会出现在我的表单里。
  //
  // 判成 false 的后果不是"显示不出来"而是**存不进去**：提交 payload 是按本函数过滤的
  // （useTaskDetailSubTableSync），新行会在发请求前就被剔除，后端连见都没见过。实测用户给
  // People 加两行、Save 无报错、刷新后全没了，就是这条路径。
  //
  // 只在"完全没有参与者标识"时放行；带着别人 FK / id_idw 的行在上面已经 return false
  // （structuralFk 非空的分支上面每条路径都 return 了，走到这里它必为 null）。
  //
  // 注意：本函数看不到**设计器主键**。主键不叫 id_idw / id 时（如 id_idwvvbz），别人的行在这里
  // 同样表现为"无标识"而被放行，所以调用方**不能**拿它当唯一归属依据——findMiIsolatedParentRow
  // 就为此把本函数限定在真有结构 FK 时才用，其余情况回落到按设计器主键排他判定。
  const hasAnyParticipantMark =
    childIdIdw != null && !isOwnAllocatedPrimaryKey(childIdIdw, row, config, pid)
  if (!hasAnyParticipantMark) return true

  return false
}

/**
 * Match a sub-table row to the Flowable MI expansion id ({@code _currentItem.rowId}).
 *
 * <p>`primaryKeyFields` 是设计器为这张表配置的主键（来自 `dw_field_definitions`），**优先**按它匹配 ——
 * 主键叫 `row_id`（ATM_Transaction）或 `id_idwvvbz`（subtable）的表此前只能靠下面的名字列表撞运气。
 *
 * <p>下面的名字列表保留为兜底：`_currentItem.rowId` 可能是设计器 PK，而 hydrate 出来的行只有 SQL
 * `id`（见 findSubTableRowByMiExpansionId 的注释），此时仍需跨字段匹配。
 */
export function rowMatchesMiExpansionId(
  rec: Record<string, unknown>,
  miRowId: string | number,
  primaryKeyFields?: string[] | null,
): boolean {
  const pid = String(miRowId).trim()
  if (!pid) return false
  // 按设计器主键匹配。**没有主键的 binding 不是"配置缺失"**：共享附件（main_id）、非 MI 的
  // 单行子表本来就没有设计器 PK，而本函数会被逐个 peer binding 调用（useSubTableBindings 等）。
  // 在这里抛错会让 Save 整个中断（实测：用户点 Save 报 MI_CONFIG_MISSING）。
  // 没有主键 ⇒ 它不可能是 MI 参与者行 ⇒ 跳过主键匹配，交给下面的跨字段兜底。
  for (const pk of primaryKeyFields ?? []) {
    const name = String(pk ?? '').trim()
    if (!name) continue
    const v = rec[name]
    if (v != null && v !== '' && String(v) === pid) return true
  }
  // 主键未命中时的**跨字段**匹配：`_currentItem.rowId` 可能是设计器 PK，而 hydrate 出来的行
  // 只暴露 SQL `id`（如 6532）。这不是「猜主键叫什么」，而是同一行在两种表示间的已知映射，
  // 且只在按主键匹配失败后才走。
  // `id_idw` 留在兜底名单里：它是历史 MI collection 的展开键，`_currentItem.rowId` 常是它，
  // 而 hydrate 出来的行可能只暴露 SQL `id`。这是同一行在两种表示间的已知映射，
  // 不是"猜某张表的主键叫什么"——设计器主键在上面已经先匹配过了。
  for (const k of ['id_idw', 'rowId', 'id', 'ID', 'RowId'] as const) {
    const v = rec[k]
    if (v != null && v !== '' && String(v) === pid) return true
  }
  return false
}
