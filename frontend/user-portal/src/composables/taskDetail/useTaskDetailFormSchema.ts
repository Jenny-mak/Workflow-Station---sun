import type { FormField } from '@/components/FormRenderer.vue'
import {
  legacyBindingIdAliases,
  isFormCreateRuleReadonly,
} from '@/components/formRendererHelpers'
import {
  resolveAssigneeFieldForBinding,
} from '@/utils/subTableAssignment'
import {
  applyFieldDefinitionsToFormFields,
} from '@/utils/subTableRowRuntime'
import {
  flattenSubFormRuleLayoutContainers,
  isDialogMappableSubFormRule,
  mergeListViewFieldColumn,
  deriveColumnsFromRelationFieldDefinitions,
  resolveSubTableSchemaByTableId,
  resolveSubListViewColumnsForBinding,
  unionListViewWithSubFormUploadColumns,
} from '@/components/subTableAddDialogHelpers'
import type { TaskDetailCtx } from './context'
import { assignSensitiveMaskColumnProps } from '@/utils/applySensitiveMaskFromRule'
import { stampCannotDownloadProp, cannotDownloadFieldKeysFromForms } from '@/utils/applyUploadPropsFromRule'

export interface TaskDetailFormSchemaFns {
  deriveColumnsFromBinding: (
    binding: any,
    subForms?: Record<string, any>,
    config?: Record<string, any>,
  ) => Array<{ field: string; label: string; type?: string; required?: boolean; options?: Array<{ label: string; value: any }>; props?: Record<string, any> }>
  resolveSubFormDesign: (
    binding: any,
    subForms?: Record<string, any>,
  ) => { formFields: FormField[]; formOptions?: Record<string, any> }
  isSyntheticLookupField: (fieldName?: string) => boolean
  isAssigneeLikeLabel: (label?: string) => boolean
  buildLookupColumnProps: (rawLookupConfig: unknown) => Record<string, any>
}

export function createTaskDetailFormSchema(ctx: TaskDetailCtx): TaskDetailFormSchemaFns {
  const { lookupDbConfigs, relationViewConfigs } = ctx

  function resolveSubFormDesign(binding: any, subForms?: Record<string, any>): { formFields: FormField[]; formOptions?: Record<string, any> } {
    const design =
      binding.subFormConfig ||
      subForms?.[binding.bindingId] ||
      subForms?.[String(binding.bindingId)] ||
      {}
    let rule = Array.isArray(design.rule) ? design.rule : []
    let options = design.options
    if (rule.length === 0 && binding.tableId != null && Number.isFinite(Number(binding.tableId))) {
      const alt = resolveSubTableSchemaByTableId(Number(binding.tableId), ctx.cachedContentForms, binding.bindingId)
      if (alt) {
        const altDesign = alt.subForms[alt.bindingId] ?? alt.subForms[String(alt.bindingId)] ?? {}
        if (Array.isArray(altDesign.rule) && altDesign.rule.length > 0) {
          rule = altDesign.rule
          options = altDesign.options ?? options
        }
      }
    }
    return {
      formFields:
        rule.length > 0
          ? applyFieldDefinitionsToFormFields(ctx.extractFieldsRecursive(rule), binding.fieldDefinitions)
          : [],
      formOptions: options
    }
  }

  // Derive display columns for a sub-table binding based on table metadata.
  // `visitedBindingIds` guards the alt-schema recursion below: two forms binding the same
  // physical table can resolve each other as "alt" and ping-pong forever (stack overflow).
  const deriveColumnsFromBinding = (binding: any, subForms?: Record<string, any>, config?: Record<string, any>, visitedBindingIds?: Set<number>): Array<{ field: string; label: string; type?: string; required?: boolean; options?: Array<{ label: string; value: any }>; props?: Record<string, any> }> => {
    // Consistent with process/start: prefer subFormConfig on binding, then configJson.subForms (supports string/number key)
    let subFormRule: any[] | undefined
    if (binding.subFormConfig?.rule) {
      subFormRule = binding.subFormConfig.rule
    } else if (subForms) {
      for (const alias of legacyBindingIdAliases(binding.bindingId)) {
        const entry = subForms[alias] ?? subForms[String(alias)]
        if (entry?.rule) {
          subFormRule = entry.rule
          break
        }
      }
    }
    if (subFormRule) subFormRule = flattenSubFormRuleLayoutContainers(subFormRule) as any[]

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
        } else {
          // fallback: pass through unknown types directly so SubTableAddDialog can handle them
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
        // 'tree' and 'elTreeSelect' store tree data in props.data — map to treeData
        if (rProps.data !== undefined) passProps.treeData = rProps.data
        // pass through nodeKey and showCheckbox for tree type
        if (rProps.nodeKey !== undefined) passProps.nodeKey = rProps.nodeKey
        if (rProps.showCheckbox !== undefined) passProps.showCheckbox = rProps.showCheckbox
        if (rProps.props !== undefined) passProps.labelProps = rProps.props
        // cascader: map props.props to cascaderProps if not already set
        if (type === 'cascader' && rProps.props && !passProps.cascaderProps) passProps.cascaderProps = rProps.props

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
      config,
      binding.bindingId,
      subFormColumns.map(c => c.field),
    )

    // 与 applications/detail 一致：只要有「列表视图」列（含 linkForm），即使用之；不能强依赖 subForm 行布局存在，否则 Link 列不会进运行时而无法解析 subtable2。
    if (Array.isArray(listColumns) && listColumns.length > 0) {
      const ruleByField = new Map(
        (Array.isArray(subFormRule) ? subFormRule : []).map((ruleItem: any) => [ruleItem?.field, ruleItem])
      )
      const subFormColumnByField = new Map(subFormColumns.map(col => [col.field, col]))
      const assigneeField = resolveAssigneeFieldForBinding(binding as never)
      const mappedOut = listColumns.map((column: any) => {
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
          const field = isSyntheticLookupField(column.fieldName) && isAssigneeLikeLabel(label) && assigneeField
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
            placeholder: fieldRule?.props?.placeholder || baseColumn?.placeholder,
            props: buildLookupColumnProps(fieldRule?.props?.lookupConfig || baseColumn?.props?.lookupConfig || '{}')
          }
        }

        return mergeListViewFieldColumn(column, baseColumn, fieldRule)
      })
      return unionListViewWithSubFormUploadColumns(mappedOut, subFormColumns)
    }

    if (subFormColumns.length > 0) return subFormColumns

    const tableId = binding.tableId != null ? Number(binding.tableId) : NaN
    if (Number.isFinite(tableId)) {
      const visited = visitedBindingIds ?? new Set<number>()
      if (Number.isFinite(Number(binding.bindingId))) visited.add(Number(binding.bindingId))
      const alt = resolveSubTableSchemaByTableId(tableId, ctx.cachedContentForms, visited)
      if (alt) {
        const fromAlt = deriveColumnsFromBinding(
          { ...binding, bindingId: alt.bindingId },
          alt.subForms,
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

  function isSyntheticLookupField(fieldName?: string): boolean {
    return !fieldName || String(fieldName).startsWith('lookup:')
  }

  function isAssigneeLikeLabel(label?: string): boolean {
    const normalized = String(label || '').trim().toLowerCase()
    return /assignee|处理人|負責人|经办人|經辦人/.test(normalized)
  }

  function buildLookupColumnProps(rawLookupConfig: unknown): Record<string, any> {
    let lookupCfg: any = {}
    try {
      lookupCfg = typeof rawLookupConfig === 'string' ? JSON.parse(rawLookupConfig || '{}') : (rawLookupConfig || {})
    } catch { lookupCfg = {} }
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

  return {
    deriveColumnsFromBinding,
    resolveSubFormDesign,
    isSyntheticLookupField,
    isAssigneeLikeLabel,
    buildLookupColumnProps,
  }
}
