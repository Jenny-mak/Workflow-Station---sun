/**
 * Constants for form-create default event handling: wrapper prefixes/suffixes,
 * form-level / component event definitions, layout types, and per-type extra events.
 */

export const FC_FN_PREFIX = '[[FORM-CREATE-PREFIX-'
export const FC_FN_SUFFIX = '-FORM-CREATE-SUFFIX]]'
export const FC_COMPONENT_EVENT_PREFIX = '$FNX:'

export const FC_WRAPPER_RE =
  /^\[\[FORM-CREATE-PREFIX-function\s([\s\S]*)\}-FORM-CREATE-SUFFIX\]\]$/

/** Form tab → Form event (stored on options). Matches @form-create/designer form.js eventConfig. */
export const FORM_LEVEL_EVENT_DEFS: ReadonlyArray<{ name: string; params: string }> = [
  { name: 'onSubmit', params: 'formData, api' },
  { name: 'onReset', params: 'api' },
  { name: 'onCreated', params: 'api' },
  { name: 'onMounted', params: 'api' },
  { name: 'onReload', params: 'api' },
  { name: 'onChange', params: 'field, value, options' },
  { name: 'beforeSubmit', params: 'formData, data' },
  { name: 'beforeFetch', params: 'config, data' },
] as const

/** Component lifecycle hooks (stored on rule._hook). */
export const COMPONENT_HOOK_NAMES = [
  'load',
  'mounted',
  'deleted',
  'watch',
  'value',
  'hidden',
  'titleClick',
] as const

/** Common DOM events (stored on rule.on). */
export const COMMON_COMPONENT_ON_EVENTS = [
  'change',
  'blur',
  'focus',
  'input',
  'click',
  'clear',
] as const

export const LAYOUT_TYPES = new Set([
  'fcRow',
  'col',
  'elCard',
  'elTabs',
  'elTabPane',
  'elCollapse',
  'elCollapseItem',
  'fcTitle',
  'html',
  'div',
  'elDivider',
  'elAlert',
  'space',
])

/** Per-type extra events (form-create designer locale). */
export const TYPE_ON_EVENTS: Record<string, string[]> = {
  input: ['change'],
  textarea: ['change'],
  password: ['change'],
  inputNumber: ['change'],
  radio: ['change'],
  checkbox: ['change'],
  select: ['change', 'removeTag', 'visibleChange'],
  switch: ['change'],
  slider: ['change'],
  rate: ['change'],
  datePicker: ['change', 'calendarChange', 'panelChange'],
  timePicker: ['change'],
  dateRange: ['change', 'calendarChange'],
  timeRange: ['change'],
  cascader: ['change', 'expandChange', 'removeTag'],
  upload: ['remove', 'preview', 'error', 'progress', 'exceed'],
  advancedUpload: ['remove', 'preview', 'error', 'progress', 'exceed'],
  elTreeSelect: ['change', 'removeTag'],
  tree: ['nodeClick', 'checkChange', 'nodeExpand', 'nodeCollapse'],
  elTabs: ['tabClick', 'tabChange', 'tabAdd', 'tabRemove'],
  elTransfer: ['leftCheckChange', 'rightCheckChange'],
  lookup: ['change'],
  subTable: ['change'],
  linkForm: ['change'],
}
