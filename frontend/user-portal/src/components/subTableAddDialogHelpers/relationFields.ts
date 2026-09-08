import { legacyBindingIdAliases } from '../formRendererHelpers'
import type { ColumnType, DialogColumn, RelationFieldDef, SubListViewColumn } from './types'
import { mergeListViewFieldColumn } from './fileColumns'
import { isAnyUploadType } from '@platform-shared/upload/uploadRuleType'

/** Apply designer table display names to list / dialog column labels. */
export function enrichColumnsWithTableFieldDisplayNames(
  columns: DialogColumn[],
  tableId: number | null | undefined,
  fieldIndex: Map<number, RelationFieldDef[]>,
): DialogColumn[] {
  if (tableId == null || !Number.isFinite(Number(tableId))) return columns
  const fields = fieldIndex.get(Number(tableId))
  if (!fields?.length) return columns
  const labelByField = new Map(
    fields
      .filter(f => f.fieldName)
      .map(f => [String(f.fieldName), String(f.displayName || f.fieldName)]),
  )
  return columns.map(col => {
    const label = labelByField.get(col.field)
    return label ? { ...col, label } : col
  })
}

/** Resolve FK/PK metadata: prefer tableBindings payload, fall back to function-unit dataTables. */
export function resolveBindingFieldDefinitions(
  binding: {
    tableId?: number | null
    fieldDefinitions?: Array<Record<string, unknown>>
  },
  fieldIndex: Map<number, RelationFieldDef[]>,
): Array<Record<string, unknown>> {
  const fromBinding = binding.fieldDefinitions
  if (Array.isArray(fromBinding) && fromBinding.length > 0) {
    return fromBinding
  }
  const tableId = binding.tableId != null ? Number(binding.tableId) : NaN
  if (!Number.isFinite(tableId)) return []
  const fields = fieldIndex.get(tableId)
  if (!fields?.length) return []
  return fields
    .filter(f => f.fieldName)
    .map(f => ({
      fieldName: f.fieldName,
      isPrimaryKey: f.isPrimaryKey,
      isForeignKey: f.isForeignKey,
      refTableId: f.refTableId,
      refPrimaryKeyFields: f.refPrimaryKeyFields,
      pkGeneration: f.pkGeneration ?? f.pkGenerationJson,
      pkGenerationJson: f.pkGenerationJson ?? f.pkGeneration,
      fkDisplayMode: f.fkDisplayMode,
    }))
}

/** Build parent table metadata map for ensureParentRowsForChildAdd (PRIMARY + SUB tables). */
export function buildParentTablesByIdFromBindings(
  bindings: Array<{
    tableId?: number | null
    bindingType?: string
    fieldDefinitions?: Array<Record<string, unknown>>
  }>,
  fieldIndex: Map<number, RelationFieldDef[]>,
): Record<number, { fieldDefinitions: Array<Record<string, unknown>> }> {
  const out: Record<number, { fieldDefinitions: Array<Record<string, unknown>> }> = {}
  for (const b of bindings) {
    if (b.bindingType !== 'PRIMARY' && b.bindingType !== 'SUB') continue
    if (b.tableId == null) continue
    const tid = Number(b.tableId)
    const defs = resolveBindingFieldDefinitions(b, fieldIndex)
    if (defs.length > 0) {
      out[tid] = { fieldDefinitions: defs }
    }
  }
  return out
}

/** Map dw_field_definitions / dataTables dataType to portal sub-table column type (aligns with developer-workstation preview). */
export function mapRelationFieldDataTypeToColumnType(dataType: string): ColumnType | undefined {
  const dt = (dataType || '').toUpperCase()
  if (dt === 'FILE') return 'upload'
  if (
    dt.includes('INT')
    || dt === 'BIGINT'
    || dt.includes('DECIMAL')
    || dt.includes('NUMERIC')
    || dt.includes('FLOAT')
    || dt.includes('DOUBLE')
  ) {
    return 'number'
  }
  if (dt === 'DATE') return 'date'
  if (dt.includes('TIMESTAMP') || dt === 'DATETIME') return 'datetime'
  if (dt === 'BOOLEAN' || dt === 'BOOL') return 'switch'
  return undefined
}

export function defaultAttachmentListColumns(): DialogColumn[] {
  return [
    { field: 'id', label: 'id', minWidth: 100 },
    { field: 'main_id', label: 'main_id', minWidth: 100 },
    mergeListViewFieldColumn(
      { fieldName: 'file', comment: 'file', dataType: 'FILE' },
      { field: 'file', label: 'file', minWidth: 180 },
      null,
    ),
  ]
}

/** Fallback columns from relation-table field definitions when subListViews / subForm are empty (e.g. attachment on copied forms). */
export function deriveColumnsFromRelationFieldDefinitions(fields: RelationFieldDef[]): DialogColumn[] {
  return [...fields]
    .sort((a, b) => (Number(a.sortOrder) || 0) - (Number(b.sortOrder) || 0))
    .map(f => {
      const fieldName = String(f.fieldName ?? '').trim()
      if (!fieldName) return null
      const type = mapRelationFieldDataTypeToColumnType(String(f.dataType ?? ''))
      const label = String(f.displayName || fieldName)
      if (isAnyUploadType(type)) {
        return mergeListViewFieldColumn(
          { fieldName, comment: label, dataType: 'FILE' },
          { field: fieldName, label, minWidth: 180 },
          null,
        )
      }
      return {
        field: fieldName,
        label,
        ...(type ? { type } : {}),
        minWidth: 100,
      }
    })
    .filter((col): col is DialogColumn => col != null)
}

import { isAuditField } from './rowInit'

/**
 * DW-parity fallback: designer-configured columns (subListViews / sub-form rule) are the
 * source of truth — when any exist, they are returned untouched so the portal shows exactly
 * the columns designed in Developer Workstation. Only when the form has no designed columns
 * at all (e.g. attachment bindings without a configured list view) do we fall back to the
 * table schema, mirroring DW's derivePreviewColumnsFromTable fallback.
 */
export function mergeMissingTableFieldColumns(
  columns: DialogColumn[],
  tableFields: RelationFieldDef[] | undefined,
): DialogColumn[] {
  if (columns.length > 0) return columns
  if (!tableFields?.length) return columns
  const fromTable = deriveColumnsFromRelationFieldDefinitions(tableFields)
  for (const col of fromTable) {
    if (isAuditField(col.field)) col.readonly = true
  }
  return fromTable
}

/** Index relation-table field definitions from function-unit {@code dataTables} content items. */
export function buildRelationTableFieldIndexFromDataTables(
  dataTables: unknown[] | undefined | null,
): Map<number, RelationFieldDef[]> {
  const out = new Map<number, RelationFieldDef[]>()
  if (!Array.isArray(dataTables)) return out
  for (const item of dataTables) {
    if (!item || typeof item !== 'object') continue
    const rec = item as Record<string, unknown>
    let parsed: Record<string, unknown> = {}
    try {
      const raw = rec.data
      parsed =
        typeof raw === 'string'
          ? JSON.parse(raw || '{}')
          : raw && typeof raw === 'object'
            ? (raw as Record<string, unknown>)
            : {}
    } catch {
      continue
    }
    const fields = (parsed.fieldDefinitions ?? parsed.fields) as RelationFieldDef[] | undefined
    if (!Array.isArray(fields) || fields.length === 0) continue
    const tid = Number(parsed.id ?? parsed.tableId ?? rec.sourceId)
    if (Number.isFinite(tid)) out.set(tid, fields)
  }
  return out
}

function formHasSubTableSchemaForBinding(
  formConfig: Record<string, unknown>,
  subForms: Record<string, unknown>,
  bindingId: number | string,
): boolean {
  const sid = String(bindingId)
  const sf = (subForms[bindingId] ?? subForms[sid]) as { rule?: unknown[] } | undefined
  if (sf?.rule && Array.isArray(sf.rule) && sf.rule.length > 0) return true
  const subListViews = formConfig.subListViews as Record<string, { columns?: unknown[] }> | undefined
  const lv = subListViews?.[bindingId] ?? subListViews?.[sid]
  return !!(lv?.columns && Array.isArray(lv.columns) && lv.columns.length > 0)
}

function isLinkFormListColumn(c: SubListViewColumn): boolean {
  if (!c || typeof c !== 'object') return false
  if (c.columnType === 'linkForm') return true
  if (typeof c.dataType === 'string' && c.dataType.toUpperCase() === 'LINK_FORM') return true
  if (typeof c.fieldName === 'string' && c.fieldName.startsWith('linkForm:')) return true
  return false
}

function countListViewDataColumns(cols: SubListViewColumn[]): number {
  return cols.filter(c => c?.fieldName && !isLinkFormListColumn(c)).length
}

/**
 * Resolve designer list-view columns for a binding. When a binding id was recreated, {@code subListViews}
 * may contain only a PK stub under the new id while {@code subForms} still has the full field set —
 * fall back to sub-form columns (same as process start) instead of rendering a single-column table.
 */
export function resolveSubListViewColumnsForBinding(
  formConfig: Record<string, unknown> | null | undefined,
  bindingId: number | string,
  subFormFieldNames: readonly string[] = [],
): SubListViewColumn[] | null {
  if (!formConfig || typeof formConfig !== 'object') return null
  const stv = formConfig.subListViews as Record<string, { columns?: SubListViewColumn[] }> | undefined
  if (!stv || typeof stv !== 'object') return null
  let direct: SubListViewColumn[] | undefined
  for (const alias of legacyBindingIdAliases(bindingId)) {
    const cols = stv[alias]?.columns ?? stv[String(alias)]?.columns
    if (Array.isArray(cols) && cols.length > 0) {
      direct = cols
      break
    }
  }
  if (!Array.isArray(direct) || direct.length === 0) return null

  const subFormCount = subFormFieldNames.length
  if (subFormCount === 0) return direct

  const dataColCount = countListViewDataColumns(direct)
  const covered = new Set(
    direct
      .filter(c => c?.fieldName && !isLinkFormListColumn(c))
      .map(c => String(c.fieldName)),
  )
  const coversAllSubForm = subFormFieldNames.every(f => covered.has(f))
  if (coversAllSubForm) return direct
  // Intentional partial list (designer picked 2+ columns but not every sub-form field)
  if (dataColCount >= 2) return direct
  // Stale stub under a new binding id — prefer sub-form column derivation
  return null
}

/**
 * Copied BPMN forms often assign a new bindingId with empty subListViews while another form in the same FU
 * already configured list/subForm schema for the same physical table ({@code tableId}).
 */
export function resolveSubTableSchemaByTableId(
  tableId: number,
  contentForms: unknown[] | undefined | null,
  excludeBindingId?: number | null | Iterable<number>,
): { formConfig: Record<string, any>; subForms: Record<string, any>; bindingId: number } | null {
  if (!Number.isFinite(tableId) || !Array.isArray(contentForms)) return null
  const excludedIds =
    excludeBindingId == null
      ? null
      : typeof excludeBindingId === 'number'
        ? new Set([excludeBindingId])
        : new Set(excludeBindingId)
  for (const f of contentForms) {
    if (!f || typeof f !== 'object') continue
    const form = f as Record<string, unknown>
    let formConfig: Record<string, any> = {}
    try {
      const raw = form.data
      formConfig =
        typeof raw === 'string'
          ? JSON.parse(raw || '{}')
          : raw && typeof raw === 'object'
            ? (raw as Record<string, any>)
            : {}
    } catch {
      continue
    }
    const subForms = (formConfig.subForms || {}) as Record<string, any>
    const tbs = (form.tableBindings || []) as Array<{ bindingId?: number | string; tableId?: number | null }>
    for (const b of tbs) {
      if (b?.tableId == null || Number(b.tableId) !== Number(tableId)) continue
      const bid = b.bindingId
      if (bid == null || bid === '') continue
      if (excludedIds?.has(Number(bid))) continue
      if (!formHasSubTableSchemaForBinding(formConfig, subForms, bid)) continue
      return { formConfig, subForms, bindingId: Number(bid) }
    }
  }
  return null
}
