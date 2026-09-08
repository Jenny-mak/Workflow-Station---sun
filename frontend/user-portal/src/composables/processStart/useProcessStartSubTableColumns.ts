import type { Ref } from 'vue'
import {
  buildLookupColumnProps,
  enrichLookupColumnPropsFromSubFormRule,
  flattenSubFormRuleLayoutContainers,
  mapSubFormRuleToDialogColumns,
  mergeListViewFieldColumn,
  parseLookupConfig,
  resolveSubFormDialogColumnsForBinding,
  resolveSubListViewColumnsForBinding,
  unionListViewWithSubFormUploadColumns,
  type DialogColumn,
} from '@/components/subTableAddDialogHelpers'
import { resolveAssigneeFieldForBinding } from '@/utils/subTableAssignment'
import type { UploadSceneFlagsArg } from '@/utils/applyUploadPropsFromRule'

export function isSyntheticLookupField(fieldName?: string): boolean {
  return !fieldName || String(fieldName).startsWith('lookup:')
}

export function isAssigneeLikeLabel(label?: string): boolean {
  const normalized = String(label || '').trim().toLowerCase()
  return /assignee|处理人|負責人|经办人|經辦人/.test(normalized)
}

/**
 * 创建子表绑定的展示列推导器。逻辑与原 useProcessStartFormParsing 内联实现逐行一致；
 * lookup / relation view 配置通过参数传入。
 */
export function createSubTableColumnDeriver(deps: {
  lookupDbConfigs: Ref<Record<string, { tableId: number; searchFields: string[]; displayField: string; viewFields: any[] }>>
  relationViewConfigs: Ref<Record<string, { viewFields: any[]; allFields: any[] }>>
  cannotDownloadFieldKeys?: () => UploadSceneFlagsArg
}) {
  const { lookupDbConfigs, relationViewConfigs, cannotDownloadFieldKeys } = deps

  const lookupCtx = () => ({
    lookupDbConfigs: lookupDbConfigs.value,
    relationViewConfigs: relationViewConfigs.value,
  })

  const blockedKeys = (): Set<string> | undefined => {
    const flags = cannotDownloadFieldKeys?.()
    if (!flags) return undefined
    return flags instanceof Set ? flags : flags.cannotDownload
  }

  /** Form-design canvas columns for Add/Edit dialog (DW Form Preview parity). */
  const deriveDialogColumnsFromBinding = (
    binding: any,
    subForms?: Record<string, any>,
  ): DialogColumn[] =>
    resolveSubFormDialogColumnsForBinding(binding, subForms, lookupCtx(), blockedKeys())

  // Derive display columns for a sub-table binding based on table type
  const deriveColumnsFromBinding = (
    binding: any,
    subForms?: Record<string, any>,
    formConfig?: Record<string, any>,
  ): DialogColumn[] => {
    const subFormRule = flattenSubFormRuleLayoutContainers(
      binding.subFormConfig?.rule ||
      subForms?.[binding.bindingId]?.rule ||
      subForms?.[String(binding.bindingId)]?.rule,
    )

    const subFormColumns: DialogColumn[] =
      subFormRule && Array.isArray(subFormRule) && subFormRule.length > 0
        ? mapSubFormRuleToDialogColumns(subFormRule, lookupCtx(), blockedKeys())
        : []

    const config = formConfig || {}
    const listColumns = resolveSubListViewColumnsForBinding(
      config,
      binding.bindingId,
      subFormColumns.map(col => col.field),
    )

    if (Array.isArray(listColumns) && listColumns.length > 0) {
      const ruleByField = new Map(
        (Array.isArray(subFormRule) ? subFormRule : []).map((ruleItem: any) => [ruleItem?.field, ruleItem]),
      )
      const subFormColumnByField = new Map(subFormColumns.map(col => [col.field, col]))
      const assigneeField = resolveAssigneeFieldForBinding(binding as never)
      return enrichLookupColumnPropsFromSubFormRule(
        unionListViewWithSubFormUploadColumns(
          listColumns.map((column: any): DialogColumn => {
        if (column.columnType === 'linkForm') {
          return {
            field: column.fieldName || `linkForm:${column.componentId || binding.bindingId}`,
            label: column.columnLabel || column.displayName || column.linkText || 'Link Form',
            type: 'linkForm' as DialogColumn['type'],
            minWidth: column.minWidth || 120,
            props: {
              linkText: column.linkText || 'Details',
              componentId: column.componentId,
              boundSubTableBindingId: column.boundSubTableBindingId,
              boundSubTableName: column.boundSubTableName,
            },
          }
        }
        if (column.columnType === 'lookup') {
          const label = column.columnLabel || column.displayName || 'Lookup'
          const field =
            isSyntheticLookupField(column.fieldName) && isAssigneeLikeLabel(label) && assigneeField
              ? assigneeField
              : (column.fieldName || `lookup:${binding.bindingId}`)
          const listLookupCfg = parseLookupConfig(column.lookupConfig || '{}')
          const relationView = listLookupCfg.bindingId
            ? relationViewConfigs.value[listLookupCfg.bindingId]
            : undefined
          return {
            field,
            label,
            type: 'lookup',
            minWidth: 260,
            props: buildLookupColumnProps(column.lookupConfig || '{}', {
              relationViewFields: relationView?.viewFields as Array<Record<string, unknown>> | undefined,
            }),
          }
        }

        const fieldRule = ruleByField.get(column.fieldName)
        const baseColumn = subFormColumnByField.get(column.fieldName)
        if (fieldRule?.type === 'lookup' || fieldRule?.props?.lookupConfig || baseColumn?.type === 'lookup') {
          const rawCfg = fieldRule?.props?.lookupConfig || baseColumn?.props?.lookupConfig || '{}'
          const mergedLookupCfg = parseLookupConfig(rawCfg)
          const dbCfg = lookupDbConfigs.value[column.fieldName]
          const relationView = mergedLookupCfg.bindingId
            ? relationViewConfigs.value[mergedLookupCfg.bindingId]
            : undefined
          return {
            ...(baseColumn || {}),
            field: column.fieldName,
            label: column.displayName || column.columnLabel || baseColumn?.label || fieldRule?.title || column.fieldName,
            type: 'lookup',
            minWidth: column.minWidth || baseColumn?.minWidth || 260,
            placeholder: fieldRule?.props?.placeholder || baseColumn?.placeholder,
            props: buildLookupColumnProps(rawCfg, {
              dbCfg,
              relationViewFields: relationView?.viewFields as Array<Record<string, unknown>> | undefined,
            }),
          }
        }

        return mergeListViewFieldColumn(column, baseColumn, fieldRule, blockedKeys())
          }),
          subFormColumns,
        ),
        subFormRule,
      )
    }

    return enrichLookupColumnPropsFromSubFormRule(subFormColumns, subFormRule)
  }

  return { deriveColumnsFromBinding, deriveDialogColumnsFromBinding }
}
