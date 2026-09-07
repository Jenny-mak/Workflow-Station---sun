import type { Ref } from 'vue'
import type { FormField } from '@/components/FormRenderer.vue'
import {
  isFormCreateRuleReadonly,
  applyDesignerHideFlagToFormField,
  isRowRule,
  isColRule,
  getRuleChildren,
  getRowGutter,
  getColSpan,
  extractRowColumnFields,
  isTabsRule,
  isCardRule,
  isCollapseRule,
  convertAuxiliaryLayoutField,
  extractTabsFromTabsRule,
  extractCollapsePanelsFromRule,
  getLayoutKey,
  getLayoutLabel,
} from '@/components/formRendererHelpers'
import { convertFormCreateRule } from './useProcessStartRuleConverter'
import type { UploadSceneFlagsArg } from '@/utils/applyUploadPropsFromRule'

// 递归提取字段。form-create 的 subForm/tableForm/tableFormColumn 为包装节点：不生成字段，
// 但必须落到下方对 `children` 的递归，否则子表行内字段全部丢失。
const FC_SKIP_TYPES = new Set(['subForm', 'tableForm', 'tableFormColumn'])

/**
 * 创建递归字段提取器。逻辑与原 useProcessStartFormParsing 内联实现逐行一致；
 * lookup / relation view 配置通过参数传入。
 */
export function createFieldExtractor(deps: {
  lookupDbConfigs: Ref<Record<string, { tableId: number; searchFields: string[]; displayField: string; viewFields: any[] }>>
  relationViewConfigs: Ref<Record<string, { viewFields: any[]; allFields: any[] }>>
  cannotDownloadFieldKeys?: () => UploadSceneFlagsArg
}) {
  const { lookupDbConfigs, relationViewConfigs, cannotDownloadFieldKeys } = deps

  const extractFieldsRecursive = (items: any[]): FormField[] => {
    const fields: FormField[] = []
    for (const item of items) {
      const bindingId = item._bindingId ?? item.props?._bindingId
      if (item.type === 'subTable' && bindingId != null) {
        const subTableField: FormField = {
          key: `__subTable_${bindingId}`,
          label: '',
          type: 'subTable',
          _bindingId: Number(bindingId),
          // 子表逐操作权限：仅显式 false 才下发（undefined 由 SubTableField 回退 editable）
          ...(item.props?.allowAdd === false ? { allowAdd: false } : {}),
          ...(item.props?.allowEdit === false ? { allowEdit: false } : {}),
          ...(item.props?.allowDelete === false ? { allowDelete: false } : {}),
          // Summary presentation designed on the canvas.
          ...(item.props?.compactCells === true ? { compactCells: true } : {}),
          span: 24,
        }
        applyDesignerHideFlagToFormField(subTableField, item)
        fields.push(subTableField)
        continue
      }
      // Inline Form: the bound SUB table's form laid out in place (no grid, no dialog).
      if (item.type === 'inlineSubForm' && bindingId != null) {
        const inlineSubFormField: FormField = {
          key: `__inlineSubForm_${bindingId}`,
          label: '',
          type: 'inlineSubForm',
          _bindingId: Number(bindingId),
          span: 24,
        }
        applyDesignerHideFlagToFormField(inlineSubFormField, item)
        fields.push(inlineSubFormField)
        continue
      }
      const auxField = convertAuxiliaryLayoutField(item, fields.length)
      if (auxField) {
        fields.push(auxField)
        continue
      }
      if (isRowRule(item)) {
        fields.push({
          key: getLayoutKey(item, fields.length, 'row'),
          label: '',
          type: 'row',
          span: 24,
          gutter: getRowGutter(item),
          children: extractRowColumnFields(item, (children) => extractFieldsRecursive(children)),
        } as any)
        continue
      } else if (isColRule(item)) {
        fields.push({
          key: getLayoutKey(item, fields.length, 'col'),
          label: '',
          type: 'col',
          span: getColSpan(item),
          children: extractFieldsRecursive(getRuleChildren(item)),
        } as any)
        continue
      }
      if (isTabsRule(item)) {
        const nestedTabs = extractTabsFromTabsRule(item, (children) => extractFieldsRecursive(children))
        if (nestedTabs.length > 0) {
          fields.push({
            key: getLayoutKey(item, fields.length, 'tabs'),
            label: '',
            type: 'tabs',
            span: 24,
            tabs: nestedTabs,
          } as any)
        }
        continue
      }
      if (isCollapseRule(item)) {
        const collapsePanels = extractCollapsePanelsFromRule(item, (children) => extractFieldsRecursive(children))
        if (collapsePanels.length > 0) {
          fields.push({
            key: getLayoutKey(item, fields.length, 'collapse'),
            label: '',
            type: 'collapse',
            span: 24,
            collapsePanels,
          } as any)
        }
        continue
      }
      if (isCardRule(item)) {
        fields.push({
          key: getLayoutKey(item, fields.length, 'card'),
          label: getLayoutLabel(item),
          type: 'card',
          span: item.col?.span || 24,
          children: extractFieldsRecursive(getRuleChildren(item))
        } as any)
        continue
      } else if (item.type === 'miAssignment') {
        const marker: FormField = {
          key: getLayoutKey(item, fields.length, 'miAssignment'),
          label: '',
          type: 'miAssignment',
          span: 24,
          // Designer Readonly on the block fixes this row's assignment — the block
          // renders no control itself, so the flag only bites once carried here.
          // Mirrors formRendererRuleParsing's marker — keep the extractors in sync.
          readonly: item.props?.readonly === true || item.props?.disabled === true,
        } as any
        applyDesignerHideFlagToFormField(marker, item)
        // Keep the assignee / BU / role rules NESTED under the marker rather than
        // hoisting them alongside it. The dialog's layout pass reads the marker's
        // own children to decide the block's membership — and to drop them with it
        // when the designer's Hide toggle is on. Flattening them here made `hidden`
        // apply to the marker alone, leaking an undesigned Assignee row into the
        // dialog while the block itself correctly disappeared.
        const children = item.children
        if (Array.isArray(children) && children.length > 0) {
          marker.children = extractFieldsRecursive(children)
        }
        fields.push(marker)
        continue
      } else if (item.type === 'recordNote') {
        // New Request: notes are writable before the instance exists — the page
        // anchors them on a draft target id, then re-anchors (adopt) after start.
        const rnProps = item.props || {}
        const rnScope = rnProps.scope === 'TABLE' ? 'TABLE' : 'RECORD'
        fields.push({
          key: `__recordNote_${rnScope.toLowerCase()}`,
          label: '',
          type: 'recordNote',
          span: 24,
          _recordNote: {
            scope: rnScope,
            panelTitle: typeof rnProps.panelTitle === 'string' ? rnProps.panelTitle : undefined,
            allowAttachment: rnProps.allowAttachment !== false,
            maxFileSizeMb: Number(rnProps.maxFileSizeMb) || 10,
            allowEditOwn: rnProps.allowEditOwn !== false,
            // Delete is opt-in (see RecordNoteField): only an explicit true enables it.
            allowDelete: rnProps.allowDelete === true,
            // Carried through even though New Request is not a To Do surface: the same rule
            // tree feeds task forms, and dropping the flag here would silently unset it.
            readonly: rnProps.readonly === true,
            pageSize: Number(rnProps.pageSize) || 5,
          },
        } as any)
      } else if (item.type === 'lookup' && item.field) {
        // Lookup field — parse config from form-create rule props.lookupConfig
        let lookupCfg: any = {}
        try {
          const raw = item.props?.lookupConfig
          lookupCfg = typeof raw === 'string' ? JSON.parse(raw || '{}') : (raw || {})
        } catch { lookupCfg = {} }
        // Merge with rt_lookup_configs fallback
        const dbCfg = lookupDbConfigs.value[item.field]
        // Resolve view fields: prefer configJson.relationViews (designed in developer-workstation),
        // then fall back to rt_view_fields (from getLookupConfigs)
        let resolvedViewFields: any[] = []
        if (lookupCfg.bindingId && relationViewConfigs.value[lookupCfg.bindingId]) {
          resolvedViewFields = relationViewConfigs.value[lookupCfg.bindingId].viewFields || []
        }
        if (!resolvedViewFields.length) {
          resolvedViewFields = dbCfg?.viewFields || []
        }
        const field: any = {
          key: item.field,
          label: item.title || item.field,
          type: 'lookup',
          placeholder: item.props?.placeholder || 'Click to search',
          span: item.col?.span || 24,
          _lookupTableId: lookupCfg.tableId || dbCfg?.tableId || 0,
          _lookupSearchFields: (lookupCfg.searchFields?.length ? lookupCfg.searchFields : null) || dbCfg?.searchFields || [],
          _lookupDisplayField: (lookupCfg.displayFields?.[0]) || dbCfg?.displayField || '',
          _lookupDisplayFields: lookupCfg.displayFields || [],
          _lookupSelectedDisplayField: lookupCfg.selectedDisplayField || lookupCfg.displayField || '',
          _lookupFilterConditions: Array.isArray(lookupCfg.filterConditions) ? lookupCfg.filterConditions : [],
          _lookupDerivedFrom: lookupCfg.derivedFrom,
          _lookupMultiple: lookupCfg.multiple === true,
          _lookupConfig: typeof item.props?.lookupConfig === 'string'
            ? item.props.lookupConfig
            : JSON.stringify(lookupCfg || {}),
          _lookupViewFields: lookupCfg.showBackfillView === false ? [] : resolvedViewFields,
          _lookupShowBackfillView: lookupCfg.showBackfillView !== false
        }
        if (isFormCreateRuleReadonly(item)) {
          field.readonly = true
        }
        applyDesignerHideFlagToFormField(field, item)
        fields.push(field)
      } else if (item.type === 'owner' && item.field) {
        // Owner field — props.ownerConfig ({"source":"CREATOR"|"CURRENT_ASSIGNEE"})
        const field: any = {
          key: item.field,
          label: item.title || item.field,
          type: 'owner',
          span: item.col?.span || 24,
          _ownerConfig: typeof item.props?.ownerConfig === 'string'
            ? item.props.ownerConfig
            : JSON.stringify(item.props?.ownerConfig || {}),
        }
        if (isFormCreateRuleReadonly(item)) {
          field.readonly = true
        }
        applyDesignerHideFlagToFormField(field, item)
        fields.push(field)
      } else if (FC_SKIP_TYPES.has(item.type)) {
        // Traverse children only; `continue` would drop nested sub-table row fields.
      } else if (item.field) {
        const field = convertFormCreateRule(item, cannotDownloadFieldKeys?.())
        if (field) fields.push(field)
      }
      const childItems = getRuleChildren(item)
      if (childItems.length > 0) {
        if (FC_SKIP_TYPES.has(item.type) || (!isCardRule(item) && !isRowRule(item) && !isColRule(item) && !isTabsRule(item) && !isCollapseRule(item))) {
          fields.push(...extractFieldsRecursive(childItems))
        }
      }
    }
    return fields
  }

  return { extractFieldsRecursive }
}
