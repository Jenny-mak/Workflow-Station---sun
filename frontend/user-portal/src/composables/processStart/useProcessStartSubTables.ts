import type { Ref } from 'vue'
import { writeSubTableRows, subTableStoreKey } from '@/composables/tasks/subTableStore'
import {
  flattenNestedSubTableRowsIntoPayload,
  normalizeSubTableRowsForBinding,
} from '@/composables/tasks/shared'
import {
  deriveColumnsFromRelationFieldDefinitions,
  mergeMissingTableFieldColumns,
  resolveSubTableSchemaByTableId,
  enrichColumnsWithTableFieldDisplayNames,
  type RelationFieldDef,
  type DialogColumn,
} from '@/components/subTableAddDialogHelpers'
import type { ProcessStartSubTableBinding } from './useProcessStartState'

/**
 * Sub-table column resolution + draft/submit payload assembly for the start form.
 * Logic identical to the original start.vue inline functions; mutable caches passed by reference.
 */
export function createProcessStartSubTables(deps: {
  caches: {
    cachedContentForms: unknown[]
    cachedRelationTableFieldIndex: Map<number, RelationFieldDef[]>
  }
  subTableBindings: Ref<ProcessStartSubTableBinding[]>
  /** From the form-parsing composable — sub-table display column derivation. */
  deriveColumnsFromBinding: (
    binding: any,
    subForms?: Record<string, any>,
    formConfig?: Record<string, any>,
  ) => DialogColumn[]
}) {
  const { caches, subTableBindings, deriveColumnsFromBinding } = deps

  function resolveSubTableBindingColumnsForStart(
    b: {
      bindingId?: number
      tableId?: number | null
      tableName?: string
      foreignKeyField?: string | null
      subFormConfig?: { rule?: unknown[] }
    },
    subForms: Record<string, any>,
    formConfig: Record<string, any>,
  ): ReturnType<typeof deriveColumnsFromBinding> {
    let columns = deriveColumnsFromBinding(b, subForms, formConfig)
    const tableIdNum = b.tableId != null ? Number(b.tableId) : NaN
    if ((!Array.isArray(columns) || columns.length === 0) && Number.isFinite(tableIdNum) && caches.cachedContentForms.length > 0) {
      const alt = resolveSubTableSchemaByTableId(tableIdNum, caches.cachedContentForms, b.bindingId)
      if (alt) {
        columns = deriveColumnsFromBinding({ ...b, bindingId: alt.bindingId }, alt.subForms, alt.formConfig)
      }
      if ((!columns || columns.length === 0) && caches.cachedRelationTableFieldIndex.has(tableIdNum)) {
        columns = deriveColumnsFromRelationFieldDefinitions(caches.cachedRelationTableFieldIndex.get(tableIdNum)!)
      }
    }
    // DW parity: designed columns are returned untouched; table schema is only a
    // fallback when no columns were designed for this binding.
    if (Number.isFinite(tableIdNum)) {
      columns = mergeMissingTableFieldColumns(
        Array.isArray(columns) ? columns : [],
        caches.cachedRelationTableFieldIndex.get(tableIdNum),
      )
    }
    if (Number.isFinite(tableIdNum) && columns?.length) {
      columns = enrichColumnsWithTableFieldDisplayNames(columns, tableIdNum, caches.cachedRelationTableFieldIndex)
    }
    return Array.isArray(columns) ? columns : []
  }

  /** Match task detail / autosave: key __subTables__ by binding id and table display name so downstream forms with new bindingIds can resolve rows. */
  function buildStartFormSubTablesPayload(): Record<string, unknown> {
    const subTables: Record<string, unknown> = {}
    // 每个 slice key 对应那张表配置的主键列——flatten 的「删到空」分支要靠它解析父行标识。
    const pkBySliceKey: Record<string, readonly string[] | undefined> = {}
    for (const b of subTableBindings.value) {
      const rows = normalizeSubTableRowsForBinding(Array.isArray(b.data) ? b.data : [])
      writeSubTableRows(subTables, b, rows)
      const key = subTableStoreKey(b)
      if (key) pkBySliceKey[key] = (b as { primaryKeyFields?: string[] }).primaryKeyFields ?? undefined
    }
    flattenNestedSubTableRowsIntoPayload(subTables, 8, pkBySliceKey)
    return subTables
  }

  return {
    resolveSubTableBindingColumnsForStart,
    buildStartFormSubTablesPayload,
  }
}
