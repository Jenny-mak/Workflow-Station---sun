/**
 * Build Form Preview items from saved form config (no live designer refs).
 * Used by Process Debug node-form panel and similar read-only previews.
 *
 * Fallback-audit note: the `|| []` / `|| {}` defaults throughout this file read OPTIONAL
 * config sections (per-binding relationViews / subListViews / lookup fields) where absence
 * legitimately means "not configured" — they are defaults, not swallowed errors. This is a
 * read-only preview; nothing here is persisted.
 */

import type { FormDefinition, TableBinding, TableDefinition } from '@/api/functionUnit'
import type { FormPreviewItem, PreviewSubTableBinding, RecordNotePreviewConfig } from '@/components/designer/formPreviewTypes'
import { flattenRuleLayoutContainers, getRuleChildren, isCardRule, getLayoutLabel, walkFormCreateRules, withSubTableBindingIdInProps } from '@/utils/formDesigner'
import { mapFormCreateRulesReadonlyDeep } from '@/utils/formCreateRuleUtils'
import { syncFormRulesWithTableFields } from '@/utils/formFieldMeta'
import { derivePreviewColumns, parseLookupConfig } from '@/utils/formPreview'
import { resolveRelationViewEntry } from '@/utils/formConfigBindingResolve'
import {
  resolveActionFormCanvasRule,
  selectPreviewCanvasTableBinding,
} from '@/utils/actionFormCanvasRule'
import { unionListViewWithSubFormUploadColumns } from '@platform-shared/upload/unionUploadColumns'

const FC_SKIP_PREVIEW = new Set(['subForm', 'tableForm', 'tableFormColumn', 'group', 'el-row', 'el-col'])

export interface SavedFormPreviewBuildOptions {
  form: FormDefinition
  tables: TableDefinition[]
  /** Optional extra bindings from API when form.tableBindings is incomplete */
  tableBindings?: TableBinding[]
  t: (key: string) => string
}

function normalizeRulesForPreview(rawRule: any[]): any[] {
  return (rawRule || []).map((r: any) => {
    const rp = r.props || {}
    if (r.type === 'transfer') {
      return {
        ...r,
        type: 'el-transfer',
        props: {
          data: (rp.options ?? []).map((o: any) => ({ key: o.value, label: o.label })),
          titles: [rp.leftTitle || 'Source', rp.rightTitle || 'Target'],
          filterable: true,
        },
      }
    }
    if (r.type === 'cascader') {
      return {
        ...r,
        type: 'el-cascader',
        props: {
          options: rp.options ?? [],
          props: rp.cascaderProps || rp.props,
          placeholder: rp.placeholder || 'Please select',
          clearable: true,
        },
      }
    }
    if (r.prefix || r.suffix) {
      const { prefix, suffix, ...rest } = r
      return rest
    }
    return r
  })
}

function getTableName(tables: TableDefinition[], tableId: number, fallback?: string): string {
  const table = tables.find(tb => tb.id === tableId)
  return table?.tableDisplayName || table?.tableName || fallback || ''
}

function mapDataTypeToPreviewColumnType(dataType: string): string | undefined {
  const dt = (dataType || '').toUpperCase()
  if (dt === 'FILE') return 'upload'
  if (dt.includes('INT') || dt === 'BIGINT' || dt.includes('DECIMAL') || dt.includes('NUMERIC') || dt.includes('FLOAT') || dt.includes('DOUBLE')) {
    return 'number'
  }
  if (dt === 'DATE') return 'date'
  if (dt.includes('TIMESTAMP') || dt === 'DATETIME') return 'datetime'
  if (dt === 'BOOLEAN' || dt === 'BOOL') return 'switch'
  return undefined
}

function deriveColumnsFromSubFormRule(rawRule: any[]): any[] {
  const rule = flattenRuleLayoutContainers(rawRule)
  if (!rule.length) return []
  return rule.map((r: any) => {
    const rProps = r.props || {}
    let type: string | undefined
    if (r.type === 'input') {
      if (rProps.type === 'textarea') type = 'textarea'
      else type = 'text'
    } else if (r.type === 'inputNumber') type = 'number'
    else if (r.type === 'select') type = 'select'
    else if (r.type === 'switch') type = 'switch'
    else if (r.type === 'datePicker') type = rProps.type === 'datetime' ? 'datetime' : 'date'
    else if (r.type === 'upload' || r.type === 'advancedUpload') type = 'upload'
    else type = r.type
    return {
      field: r.field,
      label: r.title || r.field,
      type,
      ...(rProps.action ? { props: { action: rProps.action } } : {}),
    }
  })
}

function deriveColumnsFromTable(tables: TableDefinition[], tableId: number): any[] {
  const table = tables.find(tb => tb.id === tableId)
  const fields = (table as { fieldDefinitions?: Array<Record<string, unknown>> } | undefined)?.fieldDefinitions || []
  return [...fields]
    .sort((a, b) => (Number(a.sortOrder) || 0) - (Number(b.sortOrder) || 0))
    .map(f => {
      const type = mapDataTypeToPreviewColumnType(String(f.dataType ?? ''))
      return {
        field: String(f.fieldName ?? ''),
        label: String(f.displayName || f.fieldName || ''),
        type,
        minWidth: type === 'upload' ? 180 : 100,
      }
    })
    .filter(col => col.field.length > 0)
}

function resolveLookupPreviewConfig(
  rawLookupConfig: string,
  config: Record<string, any>,
  tables: TableDefinition[],
  bindingId?: number,
  tableBindings: TableBinding[] = [],
) {
  const lookupConfig = parseLookupConfig(rawLookupConfig)
  const savedRelationView = bindingId
    ? (resolveRelationViewEntry(config.relationViews, bindingId, tableBindings)
      ?? (config.relationViews || {})[bindingId])
    : null
  const binding = (config as any)._bindings?.find?.((b: TableBinding) => b.id === bindingId)
  const table = binding ? tables.find(tb => tb.id === binding.tableId) : undefined
  const fieldDefs = ((table as any)?.fieldDefinitions || []).map((f: any) => ({
    fieldName: f.fieldName,
    dataType: f.dataType,
    displayName: f.displayName,
  }))
  return {
    placeholder: 'Click to search',
    searchFields: lookupConfig.searchFields || [],
    displayFields: lookupConfig.displayFields || [],
    selectedDisplayField: lookupConfig.selectedDisplayField || lookupConfig.displayField || '',
    filterConditions: Array.isArray(lookupConfig.filterConditions) ? lookupConfig.filterConditions : [],
    viewFields: lookupConfig.showBackfillView === false ? [] : (savedRelationView?.viewFields || []),
    fieldDefs,
    showBackfillView: lookupConfig.showBackfillView !== false,
    bindingId,
  }
}

function toSubTablePreviewColumns(
  bindingId: number,
  rule: any[],
  config: Record<string, any>,
  tables: TableDefinition[],
  bindings: TableBinding[],
  getSubFormDesign: (id: number) => { rule: any[]; options: any },
  t: (key: string) => string,
): any[] {
  const savedColumns = (config.subListViews || {})[bindingId]?.columns
  if (Array.isArray(savedColumns) && savedColumns.length) {
    const ruleByField = new Map(flattenRuleLayoutContainers(rule).map(r => [r?.field, r]))
    const mapped = savedColumns.map((column: any) => {
      if (column.columnType === 'linkForm') {
        const targetBindingId = column.boundSubTableBindingId || bindingId
        const targetDesign = getSubFormDesign(Number(targetBindingId))
        return {
          field: column.fieldName || `linkForm:${column.componentId || bindingId}`,
          label: column.columnLabel || column.displayName || column.linkText || t('linkForm.defaultLinkText'),
          type: 'linkForm',
          minWidth: 120,
          props: {
            linkText: column.linkText || t('linkForm.defaultLinkText'),
            formRule: targetDesign.rule,
            formOption: targetDesign.options,
            boundSubTableBindingId: targetBindingId,
            componentId: column.componentId,
          },
        }
      }
      const fieldRule = ruleByField.get(column.fieldName)
      const colType =
        fieldRule?.type === 'upload' || fieldRule?.type === 'advancedUpload'
          ? 'upload'
          : mapDataTypeToPreviewColumnType(String(column.dataType ?? column.fieldType ?? ''))
      return {
        field: column.fieldName,
        label: column.displayName || column.columnLabel || fieldRule?.title || column.fieldName,
        type: colType,
        minWidth: colType === 'upload' ? 180 : 100,
      }
    })
    return unionListViewWithSubFormUploadColumns(mapped, deriveColumnsFromSubFormRule(rule))
  }

  const fromRule = deriveColumnsFromSubFormRule(rule)
  if (fromRule.length) return fromRule

  const binding = bindings.find(b => b.id === bindingId)
  if (binding?.tableId) {
    const fromTable = deriveColumnsFromTable(tables, binding.tableId)
    if (fromTable.length) return fromTable
    const table = tables.find(tb => tb.id === binding.tableId)
    const tableType = table?.tableType || (binding.bindingType === 'RELATED' ? 'RELATION' : 'SUB')
    return derivePreviewColumns(tableType, t)
  }
  return []
}

function makeLookupPreviewItem(
  ruleItem: any,
  config: Record<string, any>,
  tables: TableDefinition[],
  tableBindings: TableBinding[],
) {
  const bindingId = parseLookupConfig(ruleItem.props?.lookupConfig || '{}').bindingId
  const previewConfig = resolveLookupPreviewConfig(
    ruleItem.props?.lookupConfig || '{}',
    config,
    tables,
    bindingId,
    tableBindings,
  )
  return {
    kind: 'lookup' as const,
    field: String(ruleItem.field ?? ''),
    rule: ruleItem as Record<string, unknown>,
    label: ruleItem.title || 'Lookup',
    placeholder: ruleItem.props?.placeholder || previewConfig.placeholder,
    searchFields: previewConfig.searchFields,
    displayFields: previewConfig.displayFields,
    selectedDisplayField: previewConfig.selectedDisplayField,
    filterConditions: previewConfig.filterConditions,
    viewFields: previewConfig.viewFields,
    fieldDefs: previewConfig.fieldDefs,
    showBackfillView: previewConfig.showBackfillView,
    bindingId: previewConfig.bindingId,
  }
}

function buildBindingMap(
  options: SavedFormPreviewBuildOptions,
): Map<number, PreviewSubTableBinding> {
  const { form, tables, t } = options
  const config = form.configJson || {}
  const subForms = config.subForms || {}
  const bindings = options.tableBindings?.length
    ? options.tableBindings
    : (form.tableBindings || [])
  const nonPrimary = bindings.filter(b => b.bindingType !== 'PRIMARY')
  const map = new Map<number, PreviewSubTableBinding>()

  // Persisted rules carry _bindingId only at top level; restore props._bindingId so nested
  // subTable placeholders in preview form-create resolve their binding (see withSubTableBindingIdInProps).
  const getSubFormDesign = (bindingId: number) => ({
    rule: withSubTableBindingIdInProps(subForms[bindingId]?.rule || []),
    options: subForms[bindingId]?.options || {},
  })

  for (const b of nonPrimary) {
    const bindingId = b.id as number
    if (bindingId == null) continue
    const { rule, options: subOpt } = getSubFormDesign(bindingId)
    const columns = toSubTablePreviewColumns(bindingId, rule, config, tables, bindings, getSubFormDesign, t)
    map.set(bindingId, {
      bindingId,
      bindingType: b.bindingType,
      bindingMode: b.bindingMode,
      tableName: getTableName(tables, b.tableId, b.tableName),
      tableType: tables.find(tb => tb.id === b.tableId)?.tableType || (b.bindingType === 'RELATED' ? 'RELATION' : ''),
      tableDescription: tables.find(tb => tb.id === b.tableId)?.description || '',
      // FormPreviewItems 把这四个原样塞进 SubTableField 的 config（FK/PK 运行时靠它们解析
      // 外键与字段元数据）。此处原本没有填，四个值在保存态预览里恒为 undefined —— 类型检查
      // 一直在报（TS2339/TS2551），但 `npm run build` 是纯 vite build 不过 vue-tsc，所以没人看见。
      // 取值方式与设计器实时预览路径 composables/formDesigner/useFormPreviewBuild.ts 保持一致。
      tableId: b.tableId,
      fieldDefinitions: tables.find(tb => tb.id === b.tableId)?.fieldDefinitions || [],
      bindingLinkMode: b.bindingLinkMode,
      bindingForeignKeyField: b.foreignKeyField,
      rule,
      option: subOpt,
      columns,
      subMode: b.subMode,
    })
  }

  return map
}

function containsSubTableRule(item: any): boolean {
  let found = false
  walkFormCreateRules([item], (rule) => {
    if (found) return
    // Both rule types bind a SUB table and get their own preview item kind.
    if ((rule.type === 'subTable' || rule.type === 'inlineSubForm')
      && (rule._bindingId ?? (rule.props as Record<string, unknown> | undefined)?._bindingId) != null) {
      found = true
    }
  })
  return found
}

function buildPreviewItems(
  ruleItems: any[],
  localBindingMap: Map<number, PreviewSubTableBinding>,
  config: Record<string, any>,
  tables: TableDefinition[],
  tableBindings: TableBinding[],
  keyPrefix = 'seg',
  seen: WeakSet<object> = new WeakSet<object>(),
  /**
   * The unpruned binding map. `localBindingMap` has entries deleted as bindings get claimed,
   * which is right for de-duplicating grids but wrong for the Inline Form widget: the same
   * binding may also be placed as a grid earlier in the rule, and reading the pruned map made
   * the inline block silently vanish. Defaults to localBindingMap for callers that pass neither.
   */
  fullBindingMap: Map<number, PreviewSubTableBinding> = localBindingMap,
): FormPreviewItem[] {
  const items: FormPreviewItem[] = []
  let currentSegment: any[] = []
  let segmentIndex = 0

  function flushSegment() {
    if (currentSegment.length > 0) {
      items.push({ kind: 'fields', rule: [...currentSegment], modelKey: `${keyPrefix}_${segmentIndex++}` })
      currentSegment = []
    }
  }

  for (const ruleItem of ruleItems) {
    if (ruleItem && typeof ruleItem === 'object') {
      if (seen.has(ruleItem)) continue
      seen.add(ruleItem)
    }

    const itemBindingId = ruleItem._bindingId ?? ruleItem.props?._bindingId ?? null

    if (ruleItem.type === 'subTable' && itemBindingId != null) {
      flushSegment()
      const binding = localBindingMap.get(Number(itemBindingId))
      if (binding) {
        // Summary presentation designed on the canvas.
        const placedProps = (ruleItem?.props ?? {}) as Record<string, unknown>
        items.push({
          kind: 'subTable',
          binding: {
            ...binding,
            compactCells: placedProps.compactCells === true ? true : undefined,
          },
          sourceRule: ruleItem as Record<string, unknown>,
        })
        localBindingMap.delete(Number(itemBindingId))
      }
    } else if (ruleItem.type === 'inlineSubForm' && itemBindingId != null) {
      // Inline Form: render the bound sub-form's rule in place — no grid, no Add button.
      flushSegment()
      const binding = fullBindingMap.get(Number(itemBindingId))
      if (binding) {
        items.push({
          kind: 'inlineSubForm',
          binding,
          modelKey: `${keyPrefix}_inline_${itemBindingId}`,
          sourceRule: ruleItem as Record<string, unknown>,
        })
        // Claim the binding so it is not ALSO rendered as a standalone table below.
        localBindingMap.delete(Number(itemBindingId))
      }
    } else if (isCardRule(ruleItem)) {
      flushSegment()
      items.push({
        kind: 'card',
        title: getLayoutLabel(ruleItem),
        items: buildPreviewItems(
          getRuleChildren(ruleItem),
          localBindingMap,
          config,
          tables,
          tableBindings,
          `card_${segmentIndex++}`,
          new WeakSet<object>(),
          fullBindingMap,
        ),
        modelKey: `${keyPrefix}_card_${segmentIndex}`,
      })
    } else if (ruleItem.type === 'lookup') {
      flushSegment()
      items.push(makeLookupPreviewItem(ruleItem, config, tables, tableBindings))
    } else if (ruleItem.type === 'recordNote') {
      // Same reason as the designer preview path: inside a form-create segment this type
      // resolves to the designer canvas placeholder, not the portal-shaped Notes panel.
      flushSegment()
      items.push({
        kind: 'recordNote',
        config: (ruleItem.props ?? {}) as RecordNotePreviewConfig,
        modelKey: `${keyPrefix}_note_${segmentIndex++}`,
      })
    } else if (FC_SKIP_PREVIEW.has(ruleItem.type)) {
      if (containsSubTableRule(ruleItem)) {
        flushSegment()
        items.push(...buildPreviewItems(
          getRuleChildren(ruleItem),
          localBindingMap,
          config,
          tables,
          tableBindings,
          `${keyPrefix}_layout_${segmentIndex++}`,
          seen,
          fullBindingMap,
        ))
      }
    } else {
      currentSegment.push(ruleItem)
    }
  }

  flushSegment()
  return items
}

/**
 * Build preview items from persisted form definition (saved configJson).
 */
export function buildSavedFormPreviewItems(options: SavedFormPreviewBuildOptions): FormPreviewItem[] {
  const config = options.form.configJson || {}
  const bindings = options.tableBindings?.length
    ? options.tableBindings
    : (options.form.tableBindings || [])
  const actionCanvas = resolveActionFormCanvasRule({
    formType: options.form.formType,
    tableBindings: bindings,
    topLevelRule: config.rule || [],
    subForms: config.subForms || {},
  })
  let rawRule = actionCanvas.rule
  if (!Array.isArray(rawRule) || !rawRule.length) {
    return []
  }
  rawRule = normalizeRulesForPreview(rawRule)

  const canvasBinding = selectPreviewCanvasTableBinding({
    tableBindings: bindings,
    usedActionCanvas: actionCanvas.usedActionCanvas,
    actionBindingId: actionCanvas.actionBindingId,
  })
  const canvasTable = canvasBinding?.tableId != null
    ? options.tables.find(t => t.id === canvasBinding.tableId)
    : undefined
  const canvasFields = canvasTable?.fieldDefinitions || []
  if (canvasFields.length) {
    rawRule = syncFormRulesWithTableFields(rawRule, canvasFields) as typeof rawRule
  }
  rawRule = mapFormCreateRulesReadonlyDeep(rawRule) as typeof rawRule

  const bindingMap = buildBindingMap(options)
  // Snapshot before the walk prunes it — Inline Form resolves against the full set.
  const fullBindingMap = new Map(bindingMap)
  try {
    return buildPreviewItems(
      rawRule, bindingMap, config, options.tables, bindings, 'seg', new WeakSet<object>(), fullBindingMap,
    )
  } catch (e) {
    // FALLBACK(ux): read-only preview — degrade to plain fields (sub-tables omitted) rather
    // than a blank panel. Log so a systematically broken config is still discoverable.
    console.warn('[savedFormPreviewBuilder] preview build failed; degrading to fields-only preview', e)
    const basicRule = rawRule.filter((r: any) => r.type !== 'subTable')
    return basicRule.length ? [{ kind: 'fields', rule: basicRule, modelKey: 'fallback' }] : []
  }
}
