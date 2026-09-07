import type { Ref } from 'vue'
import type { FormField, FormTab } from '@/components/FormRenderer.vue'
import { parseFormRulesLayout } from '@/components/formRendererHelpers'
import { convertFormCreateRule } from './useProcessStartRuleConverter'
import { createFieldExtractor } from './useProcessStartFieldExtractor'
import {
  createSubTableColumnDeriver,
  isSyntheticLookupField,
  isAssigneeLikeLabel,
} from './useProcessStartSubTableColumns'
import type { UploadSceneFlagsArg } from '@/utils/applyUploadPropsFromRule'

/**
 * 表单配置解析（form-create 规则 → FormRenderer 字段）以及子表列推导。
 * 逻辑与原 start.vue 内联实现逐行一致；只把作用域内的响应式依赖通过参数传入。
 */
export function createProcessStartFormParsing(deps: {
  // 读：lookup / relation view 配置
  lookupDbConfigs: Ref<Record<string, { tableId: number; searchFields: string[]; displayField: string; viewFields: any[] }>>
  relationViewConfigs: Ref<Record<string, { viewFields: any[]; allFields: any[] }>>
  /** Same inheritance set task / My Request already use (REQUEST scene copies omit the switch). */
  cannotDownloadFieldKeys?: () => UploadSceneFlagsArg
  // 写：表单布局输出
  formConfigJson: Ref<Record<string, unknown> | null>
  formLabelPosition: Ref<'left' | 'right' | 'top'>
  formFormOptions: Ref<Record<string, unknown>>
  formTabs: Ref<FormTab[]>
  formFields: Ref<FormField[]>
  formFieldsAfterTabs: Ref<FormField[]>
}) {
  const {
    lookupDbConfigs,
    relationViewConfigs,
    cannotDownloadFieldKeys,
    formConfigJson,
    formLabelPosition,
    formFormOptions,
    formTabs,
    formFields,
    formFieldsAfterTabs,
  } = deps

  const { extractFieldsRecursive } = createFieldExtractor({
    lookupDbConfigs,
    relationViewConfigs,
    cannotDownloadFieldKeys,
  })
  const { deriveColumnsFromBinding, deriveDialogColumnsFromBinding } = createSubTableColumnDeriver({
    lookupDbConfigs,
    relationViewConfigs,
    cannotDownloadFieldKeys,
  })

  // 解析表单配置 - 将 form-create 规则转换为 FormRenderer 字段
  const parseFormConfig = (configStr: string) => {
    if (!configStr) return

    try {
      const config = typeof configStr === 'string' ? JSON.parse(configStr) : configStr
      formConfigJson.value = config && typeof config === 'object' ? config as Record<string, unknown> : null
      console.log('Parsing form config:', config)

      // 支持两种格式：
      // 1. { rule: [...], options: {...} } - form-create 设计器格式
      // 2. 直接的规则数组 [...]
      let rules = null
      if (config.rule && Array.isArray(config.rule)) {
        rules = config.rule
      } else if (Array.isArray(config)) {
        rules = config
      }

      if (rules) {
        // 提取 labelWidth 配置（忽略后端配置，使用固定值避免 label 被截断）
        // if (config.options?.form?.labelWidth) {
        //   formLabelWidth.value = config.options.form.labelWidth
        // }
        // 提取 labelPosition 配置
        if (config.options?.form?.labelPosition) {
          formLabelPosition.value = config.options.form.labelPosition
        }
        formFormOptions.value = (config.options && typeof config.options === 'object') ? config.options : {}

        const layout = parseFormRulesLayout(rules, (items) => extractFieldsRecursive(items))
        formTabs.value = layout.tabs
        formFields.value = layout.fields
        formFieldsAfterTabs.value = layout.fieldsAfterTabs
        console.log('Parsed form layout:', layout)
      }
    } catch (error) {
      console.error('Failed to parse form config:', error)
    }
  }

  return {
    parseFormConfig,
    extractFieldsRecursive,
    convertFormCreateRule,
    deriveColumnsFromBinding,
    deriveDialogColumnsFromBinding,
    isSyntheticLookupField,
    isAssigneeLikeLabel,
  }
}
