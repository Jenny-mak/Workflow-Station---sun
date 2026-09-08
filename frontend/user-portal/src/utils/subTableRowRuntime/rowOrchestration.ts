/**
 * Sub-table add-row orchestration (PRD S5) — guard, FK fill, PK allocate, MI participant seeding.
 * Parity with developer-workstation Form Preview runtime.
 */
import type { DialogColumn } from '../../components/subTableAddDialogHelpers'
import { buildInitialRow } from '../../components/subTableAddDialogHelpers'
import {
  type RowAddContext,
  applyFkToInitialRow,
  guardBeforeChildRowAdd,
  resolveForeignKeyValues,
} from '../tableFkRuntime'
import { applyFkPresentationToDialogColumns } from './columnPresentation'
import { ensureRowIdentity } from '../subTableRowIdentity'
import {
  allocateChildRowAutoPrimaryKeys,
  ensureParentRowsForChildAdd,
} from './primaryKeyAllocation'
import {
  type AllocatePrimaryKeysFn,
  type BindingFieldDefinition,
  type BindingLinkMode,
  bindingForeignKeyFieldIsRowPrimaryKey,
  filterStructuralFkMetasForBinding,
  toFieldFkMetas,
} from './types'

/** Seed MI link-child FK columns (designer structural FKs e.g. sub_task_id; not row PK id). */
export function seedLinkChildForeignKeysFromParentRow(
  row: Record<string, unknown>,
  fieldDefinitions: BindingFieldDefinition[] | undefined | null,
  options: {
    bindingForeignKeyField?: string | null
    bindingLinkMode?: BindingLinkMode | string | null
    primaryKeyFields?: string[] | null
    parentParticipantRow: Record<string, unknown>
    parentTableId: number | null | undefined
    legacyFkSeed?: string | number | null
  },
): Record<string, unknown> {
  const out = { ...row }
  // id_idw is the MI collection participant key. Writing it onto a link-child row whose own
  // PK differs creates the #1435 corrupt mirror: hydration then refuses to bind the row and
  // every Save allocates a fresh row UUID (id churn). Structural linkage uses sub_task_id etc.
  const rowPks = (options.primaryKeyFields ?? []).map(f => String(f).trim().toLowerCase())
  const isForbiddenParticipantMirror = (col: string): boolean =>
    col.trim().toLowerCase() === 'id_idw' && !rowPks.includes('id_idw')
  const legacy = options.bindingForeignKeyField?.trim()
  const legacyIsRowPk = bindingForeignKeyFieldIsRowPrimaryKey(legacy, {
    primaryKeyFields: options.primaryKeyFields,
    fieldDefinitions,
  })
  if (
    legacy
    && !legacyIsRowPk
    && !isForbiddenParticipantMirror(legacy)
    && options.legacyFkSeed != null
    && String(options.legacyFkSeed).trim() !== ''
    && (out[legacy] == null || out[legacy] === '')
  ) {
    out[legacy] = options.legacyFkSeed
  }
  const parentTid = options.parentTableId != null ? Number(options.parentTableId) : NaN
  if (!Number.isFinite(parentTid)) return out
  const fkMetas = filterStructuralFkMetasForBinding(toFieldFkMetas(fieldDefinitions), {
    bindingLinkMode: options.bindingLinkMode,
    bindingForeignKeyField: options.bindingForeignKeyField,
  })
  if (fkMetas.length === 0) return out
  const ctx: RowAddContext = {
    primaryFormData: {},
    ancestorRowsByTableId: { [parentTid]: options.parentParticipantRow },
  }
  const fkValues = resolveForeignKeyValues(fkMetas, ctx)
  for (const [k, v] of Object.entries(fkValues)) {
    if (isForbiddenParticipantMirror(k)) continue
    if (out[k] == null || out[k] === '') out[k] = v
  }
  return out
}

/** Seed MI participant link (e.g. HMDC Attachment.row_id = transaction row_id) before PK allocate. */
export function applyMiParticipantRowSeedToInitialRow(
  row: Record<string, unknown>,
  options: {
    fieldDefinitions?: BindingFieldDefinition[]
    bindingLinkMode?: BindingLinkMode | string | null
    bindingForeignKeyField?: string | null
    primaryKeyFields?: string[] | null
    miParticipantRowId?: string | number | null
    miParentParticipantRow?: Record<string, unknown> | null
    miParentTableId?: number | null
  },
): Record<string, unknown> {
  if (options.bindingLinkMode !== 'miParticipantRow') return row
  const seedId = options.miParticipantRowId
  if (seedId == null || String(seedId).trim() === '') return row
  const parentRow =
    options.miParentParticipantRow && typeof options.miParentParticipantRow === 'object'
      ? options.miParentParticipantRow
      : ({ row_id: seedId } as Record<string, unknown>)
  let next = seedLinkChildForeignKeysFromParentRow(row, options.fieldDefinitions, {
    bindingForeignKeyField: options.bindingForeignKeyField,
    bindingLinkMode: options.bindingLinkMode,
    primaryKeyFields: options.primaryKeyFields,
    parentParticipantRow: parentRow,
    parentTableId: options.miParentTableId,
    legacyFkSeed: seedId,
  })
  const legacy = options.bindingForeignKeyField?.trim()
  if (
    legacy
    && bindingForeignKeyFieldIsRowPrimaryKey(legacy, {
      primaryKeyFields: options.primaryKeyFields,
      fieldDefinitions: options.fieldDefinitions,
    })
    && (next[legacy] == null || String(next[legacy]).trim() === '')
  ) {
    next = { ...next, [legacy]: seedId }
  }
  return next
}

export async function prepareSubTableAddRow(options: {
  columns: DialogColumn[]
  fieldDefinitions?: BindingFieldDefinition[]
  rowAddContext: RowAddContext
  tableId?: number | null
  tableDisplayName?: string
  primaryTableDisplayName?: string
  primaryTableId?: number | null
  parentTablesById?: Record<number, { fieldDefinitions: BindingFieldDefinition[] }>
  functionUnitId?: string | null
  allocatePrimaryKeys?: AllocatePrimaryKeysFn
  requireFkGuard?: boolean
  autoEnsurePrimaryRecord?: boolean
  /**
   * When true, all PK allocation (parent + child) is deferred until Save.
   * Add dialog opens without allocating keys; FK fill uses existing parent values only.
   */
  deferPkAllocationUntilSave?: boolean
  /** @deprecated Use deferPkAllocationUntilSave */
  deferChildPkAllocationUntilSave?: boolean
  bindingLinkMode?: BindingLinkMode | string | null
  bindingForeignKeyField?: string | null
  primaryKeyFields?: string[] | null
  miParticipantRowId?: string | number | null
  miParentParticipantRow?: Record<string, unknown> | null
  miParentTableId?: number | null
  t?: (key: string, params?: Record<string, unknown>) => string
}): Promise<
  | { ok: true; initialRow: Record<string, unknown>; dialogColumns: DialogColumn[]; primaryFormDataPatch?: Record<string, unknown> }
  | { ok: false; message: string }
> {
  const {
    columns,
    fieldDefinitions = [],
    rowAddContext: initialRowAddContext,
    tableId,
    allocatePrimaryKeys,
    requireFkGuard = true,
    autoEnsurePrimaryRecord = false,
    parentTablesById,
    t = (k) => k,
  } = options

  let rowAddContext = initialRowAddContext

  const fkMetas = filterStructuralFkMetasForBinding(toFieldFkMetas(fieldDefinitions), {
    bindingLinkMode: options.bindingLinkMode,
    bindingForeignKeyField: options.bindingForeignKeyField,
  })

  let primaryFormDataPatch: Record<string, unknown> | undefined

  const deferPkUntilSave =
    options.deferPkAllocationUntilSave === true
    || options.deferChildPkAllocationUntilSave === true

  if (requireFkGuard && fkMetas.length > 0 && !deferPkUntilSave) {
    let missing = guardBeforeChildRowAdd(fkMetas, rowAddContext)
    if (
      missing.length > 0
      && autoEnsurePrimaryRecord
      && allocatePrimaryKeys
      && parentTablesById
      && Object.keys(parentTablesById).length > 0
    ) {
      const ensured = await ensureParentRowsForChildAdd({
        fkMetas,
        rowAddContext,
        parentTablesById,
        allocatePrimaryKeys,
        functionUnitId: options.functionUnitId ?? undefined,
        primaryTableId: options.primaryTableId,
      })
      rowAddContext = ensured.rowAddContext
      primaryFormDataPatch = ensured.primaryFormDataPatch
      missing = guardBeforeChildRowAdd(fkMetas, rowAddContext)
    }
    if (missing.length > 0) {
      const parentName = options.primaryTableDisplayName || t('subTable.mainTableDefault')
      const childName = options.tableDisplayName || t('subTable.childTableDefault')
      return {
        ok: false,
        message: t('subTable.fkGuardMainNotReady', { parentTableName: parentName, childTableName: childName }),
      }
    }
  }

  const { visibleColumns, allColumns } = applyFkPresentationToDialogColumns(columns, fkMetas, fieldDefinitions)
  let row = buildInitialRow(allColumns)
  row = applyFkToInitialRow(row, fkMetas, rowAddContext)
  row = applyMiParticipantRowSeedToInitialRow(row, {
    fieldDefinitions,
    bindingLinkMode: options.bindingLinkMode,
    bindingForeignKeyField: options.bindingForeignKeyField,
    primaryKeyFields: options.primaryKeyFields,
    miParticipantRowId: options.miParticipantRowId,
    miParentParticipantRow: options.miParentParticipantRow,
    miParentTableId: options.miParentTableId,
  })

  if (tableId != null && allocatePrimaryKeys && !deferPkUntilSave) {
    row = await allocateChildRowAutoPrimaryKeys({
      row,
      fieldDefinitions,
      tableId: Number(tableId),
      allocatePrimaryKeys,
      functionUnitId: options.functionUnitId ?? undefined,
      bindingLinkMode: options.bindingLinkMode,
      bindingForeignKeyField: options.bindingForeignKeyField,
      primaryKeyFields: options.primaryKeyFields,
      miParticipantRowId: options.miParticipantRowId,
    })
  }

  // 配置优先：`ensureRowIdentity` 先按这张表配置的主键查，有值就原样返回不动它
  // （`deferPkAllocationUntilSave` 关掉时上面刚分配的 `Corr-000005` 正是这种情况）；
  // 只有**这张表根本没配主键**时才盖一个平台 UUID。
  //
  // dev 实测 24 张表里 13 张未配主键（Loan Application / Applicant Information /
  // Collateral Details …），这些表的行之间原本没有任何可区分的东西，消费方只好去猜
  // `id` / `row_id` 这类列名——而那 13 张表恰恰都有一个非主键的业务 `id` 列，两行业务 id
  // 相同就被静默合并成一行。在行**诞生的这一刻**给它身份，是让下游不必再猜的前提。
  //
  // 放在 PK 分配之后：顺序反了会先盖 UUID，再让配置主键变成第二身份。
  ensureRowIdentity(row, options.primaryKeyFields)

  return {
    ok: true,
    initialRow: row,
    dialogColumns: visibleColumns,
    ...(primaryFormDataPatch ? { primaryFormDataPatch } : {}),
  }
}

/**
 * Save-time orchestration: ensure parent auto-PKs → fill structural FKs → allocate child auto-PKs.
 * Parent keys are always allocated before child keys when the main row is still empty.
 */
export async function finalizeSubTableRowOnSave(options: {
  row: Record<string, unknown>
  fieldDefinitions?: BindingFieldDefinition[]
  rowAddContext: RowAddContext
  tableId: number
  allocatePrimaryKeys: AllocatePrimaryKeysFn
  functionUnitId?: string
  parentTablesById?: Record<number, { fieldDefinitions: BindingFieldDefinition[] }>
  primaryTableId?: number | null
  primaryTableDisplayName?: string
  tableDisplayName?: string
  autoEnsurePrimaryRecord?: boolean
  bindingLinkMode?: BindingLinkMode | string | null
  bindingForeignKeyField?: string | null
  primaryKeyFields?: string[] | null
  miParticipantRowId?: string | number | null
  miParentParticipantRow?: Record<string, unknown> | null
  miParentTableId?: number | null
  /**
   * Sub-table-in-sub-table: the table id of the row this child is nested under. When the
   * parent row is still unsaved, ensureParentRowsForChildAdd allocates its auto PK so this
   * child's structural FK can be filled — that allocation must travel back to the host row,
   * otherwise the parent allocates a second, different PK on its own save and this child's
   * FK points at a value no row ever carries.
   */
  parentTableId?: number | null
  t?: (key: string, params?: Record<string, unknown>) => string
}): Promise<
  | {
      ok: true
      row: Record<string, unknown>
      primaryFormDataPatch?: Record<string, unknown>
      parentRowPatch?: Record<string, unknown>
    }
  | { ok: false; message: string }
> {
  const {
    row: inputRow,
    fieldDefinitions = [],
    allocatePrimaryKeys,
    parentTablesById,
    autoEnsurePrimaryRecord = false,
    t = (k) => k,
  } = options

  let rowAddContext = options.rowAddContext
  const fkMetas = filterStructuralFkMetasForBinding(toFieldFkMetas(fieldDefinitions), {
    bindingLinkMode: options.bindingLinkMode,
    bindingForeignKeyField: options.bindingForeignKeyField,
  })

  let primaryFormDataPatch: Record<string, unknown> | undefined
  let parentRowPatch: Record<string, unknown> | undefined

  if (fkMetas.length > 0) {
    let missing = guardBeforeChildRowAdd(fkMetas, rowAddContext)
    if (
      missing.length > 0
      && autoEnsurePrimaryRecord
      && parentTablesById
      && Object.keys(parentTablesById).length > 0
    ) {
      const parentTid = options.parentTableId != null ? Number(options.parentTableId) : null
      const parentRowBefore =
        parentTid != null ? rowAddContext.ancestorRowsByTableId?.[parentTid] : undefined
      const ensured = await ensureParentRowsForChildAdd({
        fkMetas,
        rowAddContext,
        parentTablesById,
        allocatePrimaryKeys,
        functionUnitId: options.functionUnitId,
        primaryTableId: options.primaryTableId,
      })
      rowAddContext = ensured.rowAddContext
      primaryFormDataPatch = ensured.primaryFormDataPatch
      if (parentTid != null) {
        const parentRowAfter = rowAddContext.ancestorRowsByTableId?.[parentTid]
        if (parentRowAfter && parentRowAfter !== parentRowBefore) {
          parentRowPatch = { ...parentRowAfter }
        }
      }
      missing = guardBeforeChildRowAdd(fkMetas, rowAddContext)
    }
    if (missing.length > 0) {
      const parentName = options.primaryTableDisplayName || t('subTable.mainTableDefault')
      const childName = options.tableDisplayName || t('subTable.childTableDefault')
      return {
        ok: false,
        message: t('subTable.fkGuardMainNotReady', { parentTableName: parentName, childTableName: childName }),
      }
    }
  }

  let row = applyFkToInitialRow({ ...inputRow }, fkMetas, rowAddContext)
  row = applyMiParticipantRowSeedToInitialRow(row, {
    fieldDefinitions,
    bindingLinkMode: options.bindingLinkMode,
    bindingForeignKeyField: options.bindingForeignKeyField,
    primaryKeyFields: options.primaryKeyFields,
    miParticipantRowId: options.miParticipantRowId,
    miParentParticipantRow: options.miParentParticipantRow,
    miParentTableId: options.miParentTableId,
  })

  row = await allocateChildRowAutoPrimaryKeys({
    row,
    fieldDefinitions,
    tableId: options.tableId,
    allocatePrimaryKeys,
    functionUnitId: options.functionUnitId,
    bindingLinkMode: options.bindingLinkMode,
    bindingForeignKeyField: options.bindingForeignKeyField,
    primaryKeyFields: options.primaryKeyFields,
    miParticipantRowId: options.miParticipantRowId,
  })

  return {
    ok: true,
    row,
    ...(primaryFormDataPatch ? { primaryFormDataPatch } : {}),
    ...(parentRowPatch ? { parentRowPatch } : {}),
  }
}
