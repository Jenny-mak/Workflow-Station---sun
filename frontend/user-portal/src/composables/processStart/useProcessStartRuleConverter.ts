import type { FormField } from '@/components/FormRenderer.vue'
import { isFormCreateRuleReadonly, applyDesignerHideFlagToFormField } from '@/components/formRendererHelpers'
import { applyRuleDefaultToFormField } from '@/utils/formCreateRuleDefaults'
import { applyFormCreateValidationToFormField } from '@/utils/formCreateValidateRules'
import { applySensitiveMaskFromRule } from '@/utils/applySensitiveMaskFromRule'
import { applyUploadPropsFromRule, type UploadSceneFlagsArg } from '@/utils/applyUploadPropsFromRule'
import { mapDesignerUploadToPortalFieldType } from '@platform-shared/upload/uploadRuleType'

/**
 * 将单条 form-create 规则转换为 FormRenderer 字段。
 * 逻辑与原 useProcessStartFormParsing 内联实现逐行一致；无响应式依赖。
 */
export function convertFormCreateRule(rule: any, blockedFieldKeys?: UploadSceneFlagsArg): FormField | null {
  if (!rule || !rule.field) return null

  // 确定日期类型
  let dateType = 'date'
  if (rule.props?.type === 'datetime' || rule.props?.type === 'datetimerange') {
    dateType = 'datetime'
  } else if (rule.props?.type === 'daterange') {
    dateType = 'daterange'
  }

    const typeMap: Record<string, string> = {
    'input': 'text',
    'inputNumber': 'number',
    'select': 'select',
    'radio': 'radio',
    'checkbox': 'checkbox',
    'switch': 'switch',
    'datePicker': dateType,
    'DatePicker': dateType,
    'date-picker': dateType,
    'el-date-picker': dateType,
    'timePicker': 'time',
    'TimePicker': 'time',
    'time-picker': 'time',
    'el-time-picker': 'time',
    'cascader': 'cascader',
    'rate': 'rate',
    'slider': 'slider',
    'colorPicker': 'colorPicker',
    'treeSelect': 'treeselect',
    'upload': 'upload',
    'advancedUpload': 'upload',
    'editor': 'editor',
    'signature': 'signature',
    'transfer': 'transfer'
  }

  const field: FormField = {
    key: rule.field,
    label: rule.title || rule.field,
    type: mapDesignerUploadToPortalFieldType(rule.type) || typeMap[rule.type] || 'text',
    placeholder: rule.props?.placeholder || '',
    span: rule.col?.span || 24
  }
  applyFormCreateValidationToFormField(field, rule as Record<string, unknown>)

  // 处理选项 (rule.options or rule.props.options)
  const rawOptions = rule.options || rule.props?.options
  if (rawOptions) {
    if (rule.type === 'cascader') {
      // Cascader needs full hierarchical options with children
      field.options = rawOptions
    } else {
      field.options = rawOptions.map((opt: any) => ({
        label: opt.label || opt.value,
        value: opt.value
      }))
    }
  }

  // 处理级联选择器 props
  if (rule.type === 'cascader') {
    field.cascaderProps = rule.props?.props || rule.props?.cascaderProps
  }

  // 处理 textarea
  if (rule.type === 'input' && rule.props?.type === 'textarea') {
    field.type = 'textarea'
    field.rows = rule.props?.rows || 3
  }

  // 处理 password
  if (rule.type === 'input' && rule.props?.type === 'password') {
    field.type = 'password'
  }

  applySensitiveMaskFromRule(field, rule)

  // 处理 timePicker isRange → timerange
  if (rule.type === 'timePicker' && rule.props?.isRange === true) {
    field.type = 'timerange'
  }

  // 处理数字输入
  if (rule.type === 'inputNumber') {
    field.min = rule.props?.min
    field.max = rule.props?.max
    field.step = rule.props?.step
    field.precision = rule.props?.precision
  }

  // 处理评分
  if (rule.type === 'rate') { field.max = rule.props?.max || 5 }

  // 处理滑块
  if (rule.type === 'slider') { field.min = rule.props?.min ?? 0; field.max = rule.props?.max ?? 100; field.step = rule.props?.step || 1 }

  applyRuleDefaultToFormField(field, rule as Record<string, unknown>)
  applyUploadPropsFromRule(field, rule, blockedFieldKeys)

  if (rule.type === 'userSelect' || rule.type === 'user') {
    field.type = 'user'
  }

  if (isFormCreateRuleReadonly(rule)) {
    field.readonly = true
  }

  applyDesignerHideFlagToFormField(field, rule)

  return field
}
