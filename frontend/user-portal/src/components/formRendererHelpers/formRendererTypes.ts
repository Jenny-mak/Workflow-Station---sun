/**
 * Shared FormRenderer types — extracted so they can be imported by both the Vue
 * component (which uses <script setup>) and unit/property tests.
 */

/**
 * Which portal block a form is being rendered inside. To Do and My Requests each
 * have their own form design now, so this no longer selects a layout — it only
 * labels the surrounding block (a task page still embeds a process-form section).
 */
export type PortalViewContext = 'assigneeTodo' | 'initiatorRequest'

export interface FormField {
  key: string
  label: string
  type: string
  required?: boolean
  placeholder?: string
  span?: number
  options?: Array<{ label: string; value: string | number }>
  multiple?: boolean
  filterable?: boolean
  maxLength?: number
  min?: number
  max?: number
  step?: number
  precision?: number
  rows?: number
  activeText?: string
  inactiveText?: string
  cascaderProps?: object
  currency?: string
  alertTitle?: string
  alertType?: 'success' | 'warning' | 'info' | 'error'
  userOptions?: Array<{ id: string; name: string }>
  buOptions?: Array<{ id: string; name: string; code?: string }>
  rules?: Array<Record<string, unknown>>
  defaultValue?: string | number | boolean | null
  tabName?: string
  uploadUrl?: string
  uploadAccept?: string
  uploadLimit?: number
  uploadMaxFileSizeMb?: number
  /** true = Extend Advanced Upload (cards / FileNet). false/omit = Basic native Upload. */
  advancedUpload?: boolean
  fileNameTargetField?: string
  /** Designer upload prop: hide Portal download when true. Missing/false = download allowed. */
  cannotDownload?: boolean
  _bindingId?: number  // set when type === 'subTable' | 'inlineSubForm'
  /** RecordNote panel config; only present when type === 'recordNote'. */
  _recordNote?: {
    scope: 'TABLE' | 'RECORD'
    panelTitle?: string
    allowAttachment?: boolean
    maxFileSizeMb?: number
    allowEditOwn?: boolean
    /** Opt-in delete switch; absent/false hides the note Delete button. */
    allowDelete?: boolean
    /**
     * Designer Readonly switch. Opt-in: absent/false lets the task handler comment on a
     * To Do form. Has no bearing on request-form notes, which the function unit's audit
     * roles govern server-side.
     */
    readonly?: boolean
    pageSize?: number
  }
  /** Designer-driven portal display strategy; only present when type === 'subTable'. */
  /**
   * 子表逐操作权限（type === 'subTable'）：设计器右侧属性面板 props.allowAdd/allowEdit/allowDelete。
   * undefined => 视为放开（回退 editable，历史表单三项全开）；显式 false => 隐藏该操作。
   */
  allowAdd?: boolean
  allowEdit?: boolean
  allowDelete?: boolean
  /**
   * Presentation, designed on the form canvas. `compactCells` keeps lookup/user
   * cells to a single line instead of expanding a detail block below them.
   */
  compactCells?: boolean
  children?: FormField[] // set for layout containers such as card
  /** Nested el-tabs inside a tab pane (type === 'tabs'). */
  tabs?: FormTab[]
  /** Nested el-collapse panels (type === 'collapse'). */
  collapsePanels?: FormCollapsePanel[]
  /** fcTitle size hint (type === 'title'). */
  titleSize?: string
  /** Static HTML block (type === 'html'). */
  htmlContent?: string
  /** Per-field read-only from designer rule props.readonly / disabled. */
  readonly?: boolean
  /** Designer "Hide" — form-create rule.hidden; field omitted from layout when true. */
  hidden?: boolean
  /**
   * Original form-create rule node (`on` / `_on` / `hook` / `_hook`).
   * Form-below-table and Add/Edit dialog Event runtime read this; layout render ignores it.
   */
  sourceRule?: Record<string, unknown>
  /** Row layout gutter (type === 'row'). */
  gutter?: number
  /**
   * Display-only sensitive mask from designer Input props.sensitiveMask.
   * Present only when enabled for plain text Input (not textarea/password).
   * Per-form: each form's fields carry their own config; forms do not override each other.
   */
  sensitiveMask?: import('@/utils/sensitiveMask').SensitiveMaskConfig
}

export interface FormTab {
  name: string
  label: string
  fields: FormField[]
}

export interface FormCollapsePanel {
  name: string
  label: string
  fields: FormField[]
}

export interface FormLayoutBuckets {
  fields: FormField[]
  tabs: FormTab[]
  fieldsAfterTabs: FormField[]
}

/** Minimal binding shape for link-form target / placement helpers. */
export interface SubTableBindingLinkRef {
  bindingId: number
  columns?: Array<{ type?: string; props?: Record<string, unknown> }>
  subMode?: string
}

// ---------------------------------------------------------------------------
// Business Logic Config types — configJson 完整结构与子类型
// 所有新增字段均为可选（optional），确保旧版 configJson 向后兼容
// ---------------------------------------------------------------------------

/** configJson 完整结构 */
export interface FormBusinessLogicConfig {
  rule: any[]  // FormCreateRule (form-create rule objects)
  options: Record<string, any>
  subForms: Record<string, SubFormConfig>
  // 业务逻辑扩展（所有字段可选，向后兼容旧版 configJson）
  formulas?: FormulaRule[]
  linkages?: LinkageRule[]
  crossFieldRules?: CrossFieldRule[]
  summaryRules?: SummaryRule[]
  subTableValidation?: Record<string, SubTableValidationConfig>
}

export interface FormulaRule {
  targetField: string
  expression: string       // mathjs 安全表达式
  dependsOn: string[]
}

export interface LinkageRule {
  sourceField: string
  targetField: string
  linkageType: 'option-filtering' | 'value-auto-fill' | 'field-state-change'
  filterConfig?: {
    filterField: string
    filterOperator: 'equals' | 'contains' | 'in'
    filterSource: '$source'
  }
  valueMapping?: Record<string, any>
  stateConfig?: {
    condition: ConditionExpression
    disabled?: boolean
    required?: boolean
  }
}

export interface ConditionExpression {
  field: string
  operator: 'equals' | 'not-equals' | 'contains' | 'greater-than' | 'less-than' | 'is-empty' | 'is-not-empty'
  value?: any
  logic?: 'AND' | 'OR'
  children?: ConditionExpression[]
}

export interface CrossFieldRule {
  fields: string[]
  operator: 'greater-than' | 'less-than' | 'equals' | 'not-equals' | 'date-after' | 'date-before'
  message: string
  targetField: string
}

export interface SummaryRule {
  sourceBindingId: number
  sourceColumn: string
  targetField: string
  aggregation: 'SUM' | 'AVG' | 'COUNT' | 'MIN' | 'MAX'
}

export interface SubTableValidationConfig {
  minRows?: number
  maxRows?: number
  columnRules?: Record<string, ValidationRule[]>
}

export interface SubFormConfig {
  rule: any[]  // FormCreateRule
  options?: Record<string, any>
  rowFormulas?: RowFormulaRule[]
}

export interface RowFormulaRule {
  targetColumn: string
  expression: string       // mathjs 安全表达式
  dependsOn: string[]
}

export interface ValidationRule {
  type: 'required' | 'pattern' | 'number' | 'email' | 'phone' | 'custom'
  pattern?: string
  min?: number
  max?: number
  minLength?: number
  maxLength?: number
  message: string
}
