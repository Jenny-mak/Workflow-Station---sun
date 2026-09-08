import { isFormCreateRuleReadonly } from '@/components/formRendererHelpers'
import { resolveAssigneeFieldForBinding } from '@/utils/subTableAssignment'
import {
  flattenSubFormRuleLayoutContainers,
  isDialogMappableSubFormRule,
  mergeListViewFieldColumn,
  mergeMissingTableFieldColumns,
  deriveColumnsFromRelationFieldDefinitions,
  resolveSubTableSchemaByTableId,
  resolveSubListViewColumnsForBinding,
  unionListViewWithSubFormUploadColumns,
} from '@/components/subTableAddDialogHelpers'
import {
  isSyntheticLookupField,
  isAssigneeLikeLabel,
} from './subTableRowHelpers'
import type { ApplicationDetailCtx } from './context'
import { assignSensitiveMaskColumnProps } from '@/utils/applySensitiveMaskFromRule'
import { stampCannotDownloadProp, cannotDownloadFieldKeysFromForms } from '@/utils/applyUploadPropsFromRule'

type DerivedColumn = {
  field: string
  label: string
  // 保持宽松的 string：这里的列来自 any/关联表字段定义等松散来源，
  // 收窄成 ColumnType 会把不兼容一路推到 deriveColumnsFromBinding 等内部函数，
  // 属于真正的重构而非类型噪音清理。相关 2 条 TS2322 暂留在基线里。
  type?: string
  required?: boolean
  options?: Array<{ label: string; value: any }>
  props?: Record<string, any>
}

export interface ApplicationDetailColumnsFns {
  buildLookupColumnProps: (rawLookupConfig: unknown) => Record<string, any>
  deriveColumnsFromBinding: (binding: any, formConfig?: Record<string, any>) => DerivedColumn[]
  resolveSubTableBindingColumnsForPortal: (
    b: {
      bindingId?: number
      tableId?: number | null
      tableName?: string
      foreignKeyField?: string | null
    },
    formConfig: Record<string, any>,
    contentForms?: any[] | null,
  ) => DerivedColumn[]
}

export function createApplicationDetailColumns(ctx: ApplicationDetailCtx): ApplicationDetailColumnsFns {
  const { lookupDbConfigs, relationViewConfigs } = ctx

  /** Align with tasks/detail.vue: relation view + lookup config for sub-table list columns. */
  function buildLookupColumnProps(rawLookupConfig: unknown): Record<string, any> {
    let lookupCfg: any = {}
    try {
      lookupCfg = typeof rawLookupConfig === 'string' ? JSON.parse(rawLookupConfig || '{}') : (rawLookupConfig || {})
    } catch {
      lookupCfg = {}
    }
    const relationView = lookupCfg.bindingId ? relationViewConfigs.value[lookupCfg.bindingId] : undefined
    return {
      lookupConfig: typeof rawLookupConfig === 'string' ? rawLookupConfig : JSON.stringify(lookupCfg || {}),
      tableId: lookupCfg.tableId || 0,
      searchFields: lookupCfg.searchFields || [],
      displayField: lookupCfg.displayFields?.[0] || '',
      displayFields: lookupCfg.displayFields || [],
      selectedDisplayField: lookupCfg.selectedDisplayField || lookupCfg.displayField || '',
      filterConditions: Array.isArray(lookupCfg.filterConditions) ? lookupCfg.filterConditions : [],
      viewFields: lookupCfg.showBackfillView === false ? [] : (relationView?.viewFields || []),
      showBackfillView: lookupCfg.showBackfillView !== false
    }
  }

  // Derive display columns for a sub-table binding from the designer config.
  // List-view column order comes from subListViews; control types/options come from subForm (same as process start / task detail).
  // `visitedBindingIds` guards the alt-schema recursion below: two forms binding the same
  // physical table can resolve each other as "alt" and ping-pong forever (stack overflow).
  const deriveColumnsFromBinding = (binding: any, formConfig?: Record<string, any>, visitedBindingIds?: Set<number>): Array<{ field: string; label: string; type?: string; required?: boolean; options?: Array<{ label: string; value: any }>; props?: Record<string, any> }> => {
    const subFormRule = flattenSubFormRuleLayoutContainers(
      binding.subFormConfig?.rule ||
      formConfig?.subForms?.[binding.bindingId]?.rule ||
      formConfig?.subForms?.[String(binding.bindingId)]?.rule,
    )

    const subFormColumns =
      subFormRule && Array.isArray(subFormRule) && subFormRule.length > 0
        ? subFormRule.filter(isDialogMappableSubFormRule).map((r: any) => {
        const rProps = r.props || {}
        let type: string | undefined

        if (r.type === 'input') {
          if (rProps.type === 'textarea') type = 'textarea'
          else if (rProps.type === 'password') type = 'password'
          else type = 'text'
        } else if (r.type === 'inputNumber') {
          type = 'number'
        } else if (r.type === 'select') {
          type = 'select'
        } else if (r.type === 'radio') {
          type = 'radio'
        } else if (r.type === 'switch') {
          type = 'switch'
        } else if (r.type === 'datePicker') {
          type = rProps.type === 'datetime' ? 'datetime' : 'date'
        } else if (r.type === 'timePicker') {
          type = rProps.isRange === true ? 'timerange' : 'time'
        } else if (r.type === 'treeSelect') {
          type = 'treeselect'
        } else if (r.type === 'elTreeSelect') {
          type = 'treeselect'
        } else if (r.type === 'tree') {
          type = 'tree'
        } else if (r.type === 'upload' || r.type === 'advancedUpload') {
          type = 'upload'
        } else if (r.type === 'userSelect' || r.type === 'user') {
          type = 'user'
        } else if (r.type === 'departmentSelect' || r.type === 'department') {
          type = 'department'
        } else if (r.type === 'colorPicker') {
          type = 'colorPicker'
        } else if (r.type === 'rate') {
          type = 'rate'
        } else if (r.type === 'slider') {
          type = 'slider'
        } else if (r.type === 'editor') {
          type = 'editor'
        } else if (r.type === 'signature') {
          type = 'signature'
        } else if (r.type === 'transfer') {
          type = 'transfer'
        } else if (r.type === 'cascader') {
          type = 'cascader'
        } else if (r.type === 'lookup') {
          type = 'lookup'
        } else {
          type = r.type as any
        }

        // Collect options from rule.options or rule.props.options
        const rawOptions = r.options || rProps.options
        const options = rawOptions
          ? (type === 'cascader' ? rawOptions : rawOptions.map((o: any) => ({ label: o.label ?? o.value, value: o.value })))
          : undefined

        // Pass through relevant props
        const passProps: Record<string, any> = {}
        const propKeys = [
          'action', 'accept', 'multiple', 'precision', 'min', 'max', 'rows', 'maxlength', 'fileNameTargetField', 'cannotDownload',
          'isRange', 'valueFormat', 'startPlaceholder', 'endPlaceholder', 'treeData', 'checkStrictly',
          'showAlpha', 'allowHalf', 'step', 'cascaderProps', 'leftTitle', 'rightTitle',
        ]
        for (const key of propKeys) {
          if (rProps[key] !== undefined) passProps[key] = rProps[key]
        }
        stampCannotDownloadProp(
          passProps,
          rProps,
          typeof r.field === 'string' ? r.field : undefined,
          cannotDownloadFieldKeysFromForms(ctx.cachedContentForms),
          r,
        )
        assignSensitiveMaskColumnProps(passProps, type, rProps)
        if (rProps.data !== undefined) passProps.treeData = rProps.data
        if (rProps.nodeKey !== undefined) passProps.nodeKey = rProps.nodeKey
        if (rProps.showCheckbox !== undefined) passProps.showCheckbox = rProps.showCheckbox
        if (rProps.props !== undefined) passProps.labelProps = rProps.props
        // cascader: map props.props to cascaderProps if not already set
        if (type === 'cascader' && rProps.props && !passProps.cascaderProps) passProps.cascaderProps = rProps.props

        // lookup — same merge as tasks/detail.vue (rt_lookup_configs + relationViews)
        if (type === 'lookup') {
          let lookupCfg: any = {}
          try {
            const raw = rProps.lookupConfig
            lookupCfg = typeof raw === 'string' ? JSON.parse(raw || '{}') : (raw || {})
          } catch { lookupCfg = {} }
          const dbCfg = lookupDbConfigs.value[r.field]
          const relationView = lookupCfg.bindingId ? relationViewConfigs.value[lookupCfg.bindingId] : undefined
          passProps.lookupConfig = rProps.lookupConfig || '{}'
          passProps.tableId = lookupCfg.tableId || dbCfg?.tableId || 0
          passProps.searchFields = lookupCfg.searchFields || dbCfg?.searchFields || []
          passProps.displayField = lookupCfg.displayFields?.[0] || dbCfg?.displayField || ''
          passProps.displayFields = lookupCfg.displayFields || []
          passProps.selectedDisplayField = lookupCfg.selectedDisplayField || lookupCfg.displayField || ''
          passProps.filterConditions = Array.isArray(lookupCfg.filterConditions) ? lookupCfg.filterConditions : []
          passProps.viewFields = lookupCfg.showBackfillView === false
            ? []
            : (relationView?.viewFields || dbCfg?.viewFields || [])
          passProps.showBackfillView = lookupCfg.showBackfillView !== false
        }

        // Sync options into props.options so SubTableAddDialog can read from col.props?.options
        if (options) passProps.options = options

        const required = r.validate?.some((v: any) => v.required) || false
        // form-create uses `disabled` to mark a field as read-only
        const readonly = isFormCreateRuleReadonly(r)

        return {
          field: r.field,
          label: r.title || r.field,
          type,
          required,
          ...(readonly ? { readonly } : {}),
          ...(options ? { options } : {}),
          ...(Object.keys(passProps).length > 0 ? { props: passProps } : {}),
        }
      })
        : []

    const listColumns = resolveSubListViewColumnsForBinding(
      formConfig,
      binding.bindingId,
      subFormColumns.map(c => c.field),
    )

    if (Array.isArray(listColumns) && listColumns.length > 0) {
      const ruleByField = new Map(
        (Array.isArray(subFormRule) ? subFormRule : []).map((ruleItem: any) => [ruleItem?.field, ruleItem])
      )
      const subFormColumnByField = new Map(subFormColumns.map(col => [col.field, col]))
      const assigneeField = resolveAssigneeFieldForBinding(binding as never)
      const mappedOut = listColumns
        .filter((col: any) => col && col.fieldName)
        .map((column: any) => {
          if (column.columnType === 'linkForm') {
            return {
              field: column.fieldName || `linkForm:${column.componentId || binding.bindingId}`,
              label: column.columnLabel || column.displayName || column.linkText || 'Link Form',
              type: 'linkForm',
              minWidth: column.minWidth || 120,
              props: {
                linkText: column.linkText || 'Details',
                componentId: column.componentId,
                boundSubTableBindingId: column.boundSubTableBindingId,
                boundSubTableName: column.boundSubTableName
              }
            }
          }
          if (column.columnType === 'lookup') {
            const label = column.columnLabel || column.displayName || 'Lookup'
            const field =
              isSyntheticLookupField(column.fieldName) && isAssigneeLikeLabel(label) && assigneeField
                ? assigneeField
                : (column.fieldName || `lookup:${binding.bindingId}`)
            return {
              field,
              label,
              type: 'lookup',
              minWidth: 260,
              props: buildLookupColumnProps(column.lookupConfig || '{}')
            }
          }

          const fieldRule = ruleByField.get(column.fieldName)
          const baseColumn = subFormColumnByField.get(column.fieldName)
          if (fieldRule?.type === 'lookup' || fieldRule?.props?.lookupConfig || baseColumn?.type === 'lookup') {
            return {
              ...(baseColumn || {}),
              field: column.fieldName,
              label: column.displayName || column.columnLabel || baseColumn?.label || fieldRule?.title || column.fieldName,
              type: 'lookup',
              minWidth: column.minWidth || baseColumn?.minWidth || 260,
              props: buildLookupColumnProps(fieldRule?.props?.lookupConfig || baseColumn?.props?.lookupConfig || '{}')
            }
          }

          return mergeListViewFieldColumn(column, baseColumn, fieldRule)
        })
      return unionListViewWithSubFormUploadColumns(mappedOut, subFormColumns)
    }

    const tableId = binding.tableId != null ? Number(binding.tableId) : NaN
    if (Number.isFinite(tableId) && ctx.cachedContentForms.length > 0) {
      const visited = visitedBindingIds ?? new Set<number>()
      if (Number.isFinite(Number(binding.bindingId))) visited.add(Number(binding.bindingId))
      const alt = resolveSubTableSchemaByTableId(tableId, ctx.cachedContentForms, visited)
      if (alt) {
        const fromAlt = deriveColumnsFromBinding(
          { ...binding, bindingId: alt.bindingId },
          alt.formConfig,
          visited,
        )
        if (fromAlt.length > 0) return fromAlt
      }
      const tableFields = ctx.cachedRelationTableFieldIndex.get(tableId)
      if (tableFields?.length) {
        const fromTable = deriveColumnsFromRelationFieldDefinitions(tableFields)
        if (fromTable.length > 0) return fromTable
      }
    }

    return subFormColumns
  }

  function resolveSubTableBindingColumnsForPortal(
    b: {
      bindingId?: number
      tableId?: number | null
      tableName?: string
      foreignKeyField?: string | null
    },
    formConfig: Record<string, any>,
    contentForms?: any[] | null,
  ): ReturnType<typeof deriveColumnsFromBinding> {
    let columns = deriveColumnsFromBinding(b, formConfig)
    const tableIdNum = b.tableId != null ? Number(b.tableId) : NaN
    const forms = contentForms ?? ctx.cachedContentForms
    if ((!Array.isArray(columns) || columns.length === 0) && Number.isFinite(tableIdNum) && forms.length > 0) {
      const alt = resolveSubTableSchemaByTableId(tableIdNum, forms, b.bindingId)
      if (alt) {
        const visited = new Set<number>()
        if (Number.isFinite(Number(b.bindingId))) visited.add(Number(b.bindingId))
        columns = deriveColumnsFromBinding({ ...b, bindingId: alt.bindingId }, alt.formConfig, visited)
      }
      if ((!columns || columns.length === 0) && ctx.cachedRelationTableFieldIndex.has(tableIdNum)) {
        columns = deriveColumnsFromRelationFieldDefinitions(ctx.cachedRelationTableFieldIndex.get(tableIdNum)!)
      }
    }
    // DW parity: designed columns are returned untouched; table schema is only a
    // fallback when no columns were designed for this binding.
    if (Number.isFinite(tableIdNum)) {
      columns = mergeMissingTableFieldColumns(
        Array.isArray(columns) ? columns : [],
        ctx.cachedRelationTableFieldIndex.get(tableIdNum),
      )
    }
    return Array.isArray(columns) ? columns : []
  }

  return {
    buildLookupColumnProps,
    deriveColumnsFromBinding,
    resolveSubTableBindingColumnsForPortal,
  }
}
