import type { ComputedRef, Ref } from 'vue'
import type { FormDefinition } from '@/api/functionUnit'
import type { SubTableFieldDTO } from '@/api/subTableView'
import { resolveRelationViewEntry, resolveBindingKeyedEntry } from '@/utils/formConfigBindingResolve'
import { flattenRuleLayoutContainers } from '@/utils/formDesigner'
import { parseLookupConfig } from '@/utils/formPreview'
import { isFormCreateRuleReadonly } from '@/utils/formCreateRuleUtils'
import { resolveRuleDefaultValue } from '@/utils/formCreateRuleDefaults'
import { isFormCreateRuleRequired } from '@/utils/formCreateValidateRules'
import { mapDesignerValidateForDialog } from '@/utils/mapDesignerValidateForDialog'
import { assignSensitiveMaskColumnProps } from '@/utils/sensitiveMaskColumnProps'
import { unionListViewWithSubFormUploadColumns } from '@platform-shared/upload/unionUploadColumns'
import type { SubTableListColumnDTO } from './useSubTableViews'

interface UseFormPreviewColumnsOptions {
  store: { tables: any[] }
  selectedForm: Ref<FormDefinition | null>
  designerSubBindings: ComputedRef<Array<{ bindingId: number; tableId: number }>>
  relationViewState: Ref<Record<number, { allFields: any[]; viewFields: any[] }>>
  subTableViewState: Ref<Record<number, { allFields: SubTableFieldDTO[]; viewFields: SubTableListColumnDTO[] }>>
  getSubTableFormDesign: (bindingId: number) => { rule: any[]; options: any }
  resolveDesignerBindingDisplayName: (bindingId: unknown) => string
  t: (key: string, params?: Record<string, unknown>) => string
}

/**
 * Preview column derivation for FormDesigner: maps sub-form rules, saved list
 * view columns or raw table metadata into FormPreviewItems column descriptors,
 * including lookup preview configuration resolution.
 */
export function useFormPreviewColumns(options: UseFormPreviewColumnsOptions) {
  const {
    store, selectedForm, designerSubBindings, relationViewState, subTableViewState,
    getSubTableFormDesign, resolveDesignerBindingDisplayName, t,
  } = options

  /**
   * Derive columns from sub-form binding rule (supports all 15 field types)
   */
  function deriveColumnsFromBinding(binding: any, subForms?: Record<string, any>) {
    const subFormRule = flattenRuleLayoutContainers(subForms?.[binding.bindingId]?.rule)
    if (subFormRule.length > 0) {
      return subFormRule.map((r: any) => {
        const rProps = r.props || {}
        let type: string | undefined
        if (r.type === 'input') {
          if (rProps.type === 'textarea') type = 'textarea'
          else if (rProps.type === 'password') type = 'password'
          else type = 'text'
        }
        else if (r.type === 'inputNumber') type = 'number'
        else if (r.type === 'select') type = 'select'
        else if (r.type === 'radio') type = 'radio'
        else if (r.type === 'switch') type = 'switch'
        else if (r.type === 'datePicker') type = rProps.type === 'datetime' ? 'datetime' : 'date'
        else if (r.type === 'timePicker') type = rProps.isRange === true ? 'timerange' : 'time'
        else if (r.type === 'treeSelect') type = 'treeselect'
        else if (r.type === 'elTreeSelect') type = 'treeselect'
        else if (r.type === 'tree') type = 'tree'
        else if (r.type === 'upload' || r.type === 'advancedUpload') type = 'upload'
        else if (r.type === 'userSelect' || r.type === 'user') type = 'user'
        else if (r.type === 'departmentSelect' || r.type === 'department') type = 'department'
        else if (r.type === 'colorPicker') type = 'colorPicker'
        else if (r.type === 'rate') type = 'rate'
        else if (r.type === 'slider') type = 'slider'
        else if (r.type === 'editor') type = 'editor'
        else if (r.type === 'signature') type = 'signature'
        else if (r.type === 'transfer') type = 'transfer'
        else if (r.type === 'cascader') type = 'cascader'
        else type = r.type as any
        const rawOptions = r.options || rProps.options
        const options = rawOptions ? (type === 'cascader' ? rawOptions : rawOptions.map((o: any) => ({ label: o.label ?? o.value, value: o.value }))) : undefined
        const passProps: Record<string, any> = {}
        for (const key of [
          'action', 'accept', 'multiple', 'maxFiles', 'maxFileSizeMb', 'precision', 'min', 'max',
          'rows', 'maxlength', 'fileNameTargetField', 'cannotDownload',
          'isRange', 'valueFormat', 'startPlaceholder', 'endPlaceholder', 'treeData', 'checkStrictly',
          'showAlpha', 'allowHalf', 'step', 'cascaderProps', 'leftTitle', 'rightTitle',
        ]) {
          if (rProps[key] !== undefined) passProps[key] = rProps[key]
        }
        assignSensitiveMaskColumnProps(passProps, type, rProps)
        if (rProps.data !== undefined) passProps.treeData = rProps.data
        if (rProps.nodeKey !== undefined) passProps.nodeKey = rProps.nodeKey
        if (rProps.showCheckbox !== undefined) passProps.showCheckbox = rProps.showCheckbox
        if (rProps.props !== undefined) passProps.labelProps = rProps.props
        // cascader: map props.props to cascaderProps if not already set
        if (type === 'cascader' && rProps.props && !passProps.cascaderProps) passProps.cascaderProps = rProps.props
        if (type === 'lookup') {
          const lookupPreviewConfig = resolveLookupPreviewConfig(rProps.lookupConfig || '{}')
          const lookupCfg = parseLookupConfig(rProps.lookupConfig || '{}')
          passProps.lookupConfig = rProps.lookupConfig || '{}'
          passProps.searchFields = lookupPreviewConfig.searchFields
          passProps.displayFields = lookupPreviewConfig.displayFields
          passProps.selectedDisplayField = lookupPreviewConfig.selectedDisplayField
          passProps.filterConditions = lookupPreviewConfig.filterConditions
          passProps.derivedFrom = lookupCfg.derivedFrom
          passProps.multiple = lookupCfg.multiple === true
          passProps.viewFields = lookupPreviewConfig.viewFields
          passProps.fieldDefs = lookupPreviewConfig.fieldDefs
          passProps.showBackfillView = lookupPreviewConfig.showBackfillView
        }
        if (type === 'owner') {
          passProps.ownerConfig = rProps.ownerConfig || '{}'
        }
        if (options) passProps.options = options
        const readonly = isFormCreateRuleReadonly(r)
        const defaultValue = resolveRuleDefaultValue(r as Record<string, unknown>)
        const required = isFormCreateRuleRequired(r as Record<string, unknown>)
        const elRules = mapDesignerValidateForDialog(r as Record<string, unknown>, type)
        return {
          field: r.field,
          label: r.title || r.field,
          type,
          required,
          ...(readonly ? { readonly: true } : {}),
          ...(elRules.length > 0 ? { rules: elRules } : {}),
          ...(options ? { options } : {}),
          ...(Object.keys(passProps).length > 0 ? { props: passProps } : {}),
          ...(defaultValue !== undefined ? { defaultValue } : {}),
          sourceRule: r as Record<string, unknown>,
        }
      })
    }
    return []
  }

  function getRelationFieldDefs(bindingId?: number, config: any = {}) {
    if (!bindingId) return []
    const state = relationViewState.value[bindingId]
    const bindings = selectedForm.value?.tableBindings ?? []
    const saved = resolveRelationViewEntry(config.relationViews, bindingId, bindings)
      ?? (config.relationViews || {})[bindingId]
    const fields = state?.allFields || saved?.allFields || []
    if (fields.length) {
      return fields.map((f: any) => ({
        fieldName: f.fieldName,
        dataType: f.dataType,
        displayName: f.displayName,
      }))
    }

    const binding = designerSubBindings.value.find(b => b.bindingId === bindingId)
    const table = store.tables.find(t => t.id === binding?.tableId)
    return ((table as any)?.fieldDefinitions || []).map((f: any) => ({
      fieldName: f.fieldName,
      dataType: f.dataType,
      displayName: f.displayName,
    }))
  }

  function makeLookupPreviewItem(ruleItem: any, config: any) {
    const previewConfig = resolveLookupPreviewConfig(ruleItem.props?.lookupConfig || '{}', config)
    const lookupConfig = parseLookupConfig(ruleItem.props?.lookupConfig || '{}')
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
      derivedFrom: lookupConfig.derivedFrom,
      multiple: lookupConfig.multiple === true,
      viewFields: previewConfig.viewFields,
      fieldDefs: previewConfig.fieldDefs,
      showBackfillView: previewConfig.showBackfillView,
      bindingId: previewConfig.bindingId,
      readonly: isFormCreateRuleReadonly(ruleItem),
    }
  }

  function resolveLookupPreviewConfig(rawLookupConfig: string, explicitConfig?: any) {
    const config = explicitConfig || selectedForm.value?.configJson || {}
    const lookupConfig = parseLookupConfig(rawLookupConfig)
    const bindingId = lookupConfig.bindingId
    const savedRelationView = bindingId
      ? (resolveRelationViewEntry(config.relationViews, bindingId, selectedForm.value?.tableBindings ?? [])
        ?? (config.relationViews || {})[bindingId])
      : null
    return {
      placeholder: 'Click to search',
      searchFields: lookupConfig.searchFields || [],
      displayFields: lookupConfig.displayFields || [],
      selectedDisplayField: lookupConfig.selectedDisplayField || lookupConfig.displayField || '',
      filterConditions: Array.isArray(lookupConfig.filterConditions) ? lookupConfig.filterConditions : [],
      derivedFrom: lookupConfig.derivedFrom,
      multiple: lookupConfig.multiple === true,
      viewFields: lookupConfig.showBackfillView === false
        ? []
        : (savedRelationView?.viewFields || relationViewState.value[bindingId]?.viewFields || []),
      fieldDefs: getRelationFieldDefs(bindingId, config),
      showBackfillView: lookupConfig.showBackfillView !== false,
      bindingId
    }
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

  /** Fallback columns from Data_Table when list view / sub-form are not configured yet (e.g. attachment). */
  function derivePreviewColumnsFromTable(bindingId: number) {
    const binding = designerSubBindings.value.find(b => b.bindingId === bindingId)
    if (!binding?.tableId) return []
    const table = store.tables.find(t => t.id === binding.tableId)
    const fields = (table as { fieldDefinitions?: Array<Record<string, unknown>> } | undefined)?.fieldDefinitions || []
    return [...fields]
      .sort((a, b) => (Number(a.sortOrder) || 0) - (Number(b.sortOrder) || 0))
      .map((f) => {
        const type = mapDataTypeToPreviewColumnType(String(f.dataType ?? ''))
        return {
          field: String(f.fieldName ?? ''),
          label: String(f.displayName || f.fieldName || ''),
          type,
          minWidth: type === 'upload' ? 180 : 100,
          ...(type === 'upload' ? { props: { action: '/api/v1/upload' } } : {}),
        }
      })
      .filter((col) => col.field.length > 0)
  }

  function toSubTablePreviewColumns(bindingId: number, rule: any[], config: any) {
    const liveColumns = subTableViewState.value[bindingId]?.viewFields
    const bindings = selectedForm.value?.tableBindings ?? []
    const savedEntry = resolveBindingKeyedEntry(config.subListViews, bindingId, bindings, 'SUB')
      ?? (config.subListViews || {})[bindingId]
    const savedColumns = savedEntry?.columns
    const listColumns = liveColumns?.length ? liveColumns : savedColumns
    if (Array.isArray(listColumns) && listColumns.length) {
      const ruleByField = new Map(flattenRuleLayoutContainers(rule).map((ruleItem: any) => [ruleItem?.field, ruleItem]))
      const mapped = listColumns.map((column: any) => {
        if (column.columnType === 'linkForm') {
          const targetBindingId = column.boundSubTableBindingId || bindingId
          const targetFormDesign = getSubTableFormDesign(targetBindingId)
          const boundSubTableName = column.boundSubTableName
            || resolveDesignerBindingDisplayName(targetBindingId)
          return {
            field: column.fieldName || `linkForm:${column.componentId || bindingId}`,
            label: column.columnLabel || column.displayName || column.linkText || t('linkForm.defaultLinkText'),
            type: 'linkForm',
            minWidth: 120,
            props: {
              linkText: column.linkText || t('linkForm.defaultLinkText'),
              formRule: targetFormDesign.rule,
              formOption: targetFormDesign.options,
              boundSubTableName,
              boundSubTableBindingId: targetBindingId,
              componentId: column.componentId,
            }
          }
        }
        if (column.columnType === 'lookup') {
          const lookupPreviewConfig = resolveLookupPreviewConfig(column.lookupConfig || '{}', config)
          const lookupCfg = parseLookupConfig(column.lookupConfig || '{}')
          return {
            field: column.fieldName || `lookup:${bindingId}`,
            label: column.columnLabel || column.displayName || 'Lookup',
            type: 'lookup',
            minWidth: 260,
            placeholder: lookupPreviewConfig.placeholder,
            props: {
              lookupConfig: column.lookupConfig || '{}',
              searchFields: lookupPreviewConfig.searchFields,
              displayFields: lookupPreviewConfig.displayFields,
              selectedDisplayField: lookupPreviewConfig.selectedDisplayField,
              filterConditions: lookupPreviewConfig.filterConditions,
              derivedFrom: lookupCfg.derivedFrom,
              multiple: lookupCfg.multiple === true,
              viewFields: lookupPreviewConfig.viewFields,
              fieldDefs: lookupPreviewConfig.fieldDefs,
              showBackfillView: lookupPreviewConfig.showBackfillView
            }
          }
        }
        const fieldRule = ruleByField.get(column.fieldName)
        if (fieldRule?.type === 'lookup' || fieldRule?.props?.lookupConfig) {
          const lookupPreviewConfig = resolveLookupPreviewConfig(fieldRule.props?.lookupConfig || '{}', config)
          const lookupCfg = parseLookupConfig(fieldRule.props?.lookupConfig || '{}')
          const defaultValue = resolveRuleDefaultValue(fieldRule as Record<string, unknown>)
          return {
            field: column.fieldName,
            label: column.displayName || column.columnLabel || fieldRule.title || column.fieldName,
            type: 'lookup',
            minWidth: 260,
            placeholder: fieldRule.props?.placeholder || lookupPreviewConfig.placeholder,
            ...(defaultValue !== undefined ? { defaultValue } : {}),
            sourceRule: fieldRule as Record<string, unknown>,
            props: {
              lookupConfig: fieldRule.props?.lookupConfig || '{}',
              searchFields: lookupPreviewConfig.searchFields,
              displayFields: lookupPreviewConfig.displayFields,
              selectedDisplayField: lookupPreviewConfig.selectedDisplayField,
              filterConditions: lookupPreviewConfig.filterConditions,
              derivedFrom: lookupCfg.derivedFrom,
              multiple: lookupCfg.multiple === true,
              viewFields: lookupPreviewConfig.viewFields,
              fieldDefs: lookupPreviewConfig.fieldDefs,
              showBackfillView: lookupPreviewConfig.showBackfillView
            }
          }
        }
        if (fieldRule?.type === 'owner') {
          return {
            field: column.fieldName,
            label: column.displayName || column.columnLabel || fieldRule.title || column.fieldName,
            type: 'owner',
            minWidth: 200,
            props: { ownerConfig: fieldRule.props?.ownerConfig || '{}' },
            sourceRule: fieldRule as Record<string, unknown>,
          }
        }
        const colType = (fieldRule?.type === 'upload' || fieldRule?.type === 'advancedUpload')
          ? 'upload'
          : (fieldRule?.type === 'select' || fieldRule?.type === 'radio')
            ? fieldRule.type
            : mapDataTypeToPreviewColumnType(String(column.dataType ?? column.fieldType ?? ''))
        const uploadProps = colType === 'upload'
          ? {
              action: fieldRule?.props?.action || '/api/v1/upload',
              ...(fieldRule?.props?.accept ? { accept: fieldRule.props.accept } : {}),
              ...(fieldRule?.props?.multiple != null ? { multiple: fieldRule.props.multiple } : {}),
              ...(fieldRule?.props?.maxFiles != null ? { maxFiles: fieldRule.props.maxFiles } : {}),
              ...(fieldRule?.props?.maxFileSizeMb != null
                ? { maxFileSizeMb: fieldRule.props.maxFileSizeMb }
                : {}),
              ...(fieldRule?.props?.fileNameTargetField
                ? { fileNameTargetField: fieldRule.props.fileNameTargetField }
                : {}),
              ...(fieldRule?.props?.cannotDownload === true ? { cannotDownload: true } : {}),
            }
          : null
        const passProps: Record<string, unknown> = { ...(uploadProps || {}) }
        const rawOptions = fieldRule?.options || fieldRule?.props?.options
        const options = rawOptions
          ? (colType === 'cascader'
            ? rawOptions
            : rawOptions.map((o: any) => ({ label: o.label ?? o.value, value: o.value })))
          : undefined
        if (options) passProps.options = options
        if (fieldRule?.props) {
          for (const key of [
            'precision', 'min', 'max', 'maxFiles', 'maxFileSizeMb', 'rows', 'maxlength', 'multiple',
            'isRange', 'valueFormat', 'startPlaceholder', 'endPlaceholder',
            'treeData', 'checkStrictly', 'showAlpha', 'allowHalf', 'step',
          ]) {
            if (fieldRule.props[key] !== undefined && passProps[key] === undefined) {
              passProps[key] = fieldRule.props[key]
            }
          }
        }
        const defaultValue = fieldRule
          ? resolveRuleDefaultValue(fieldRule as Record<string, unknown>)
          : undefined
        const required = fieldRule
          ? isFormCreateRuleRequired(fieldRule as Record<string, unknown>)
          : undefined
        const elRules = fieldRule
          ? mapDesignerValidateForDialog(fieldRule as Record<string, unknown>, colType)
          : []
        return {
          field: column.fieldName,
          label: column.displayName || column.columnLabel || fieldRule?.title || column.fieldName,
          type: colType,
          minWidth: colType === 'upload' ? 180 : 100,
          ...(required ? { required: true } : {}),
          ...(fieldRule && isFormCreateRuleReadonly(fieldRule) ? { readonly: true } : {}),
          ...(elRules.length > 0 ? { rules: elRules } : {}),
          ...(options ? { options } : {}),
          ...(Object.keys(passProps).length > 0 ? { props: passProps } : {}),
          ...(defaultValue !== undefined ? { defaultValue } : {}),
          ...(fieldRule ? { sourceRule: fieldRule as Record<string, unknown> } : {}),
        }
      })
      return unionListViewWithSubFormUploadColumns(
        mapped,
        deriveColumnsFromBinding({ bindingId }, { [bindingId]: { rule } }),
      )
    }

    const fromSubFormRule = deriveColumnsFromBinding({ bindingId }, { [bindingId]: { rule } })
    if (fromSubFormRule.length > 0) return fromSubFormRule
    return derivePreviewColumnsFromTable(bindingId)
  }

  return {
    deriveColumnsFromBinding,
    getRelationFieldDefs,
    makeLookupPreviewItem,
    resolveLookupPreviewConfig,
    mapDataTypeToPreviewColumnType,
    derivePreviewColumnsFromTable,
    toSubTablePreviewColumns,
  }
}
