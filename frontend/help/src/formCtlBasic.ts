import type { GuideRelated, GuideSample } from '@/components/GuideArticle.vue'

export interface FormCtlDef {
  id: string
  path: string
  oldHash: string
  navTitleKey: string
  figure?: string
  group?: 'basic' | 'extend'
  includeBasis?: boolean
  includeStyle?: boolean
  flowKeys?: string[]
  eventSamples: GuideSample[]
  shapeSample?: GuideSample
  catalog: GuideSample[]
  failKeys: string[]
  relatedIds: string[]
}

function shared(code: string, hint: string): GuideSample {
  return { code, hintKey: `formCtl.shared.${hint}` }
}

function row(id: string, code: string, hint: string): GuideSample {
  return { code, hintKey: `formCtl.${id}.${hint}` }
}

function shape(id: string, code: string): GuideSample {
  return { code, hintKey: `formCtl.${id}.eventShapeHint` }
}

const BASIS: GuideSample[] = [
  shared('Type', 'headerType'),
  shared('Serial number', 'serial'),
  shared('Field', 'field'),
  shared('Title', 'title'),
  shared('Info', 'info'),
  shared('Label width', 'labelWidth'),
  shared('Control', 'control'),
  shared('Hidden', 'hidden'),
  shared('Is it required', 'required'),
]

const STYLE: GuideSample[] = [
  shared('Width', 'styleWidth'),
  shared('Height', 'styleHeight'),
  shared('Color', 'styleColor'),
  shared('Background color', 'styleBg'),
  shared('Margin', 'styleMargin'),
  shared('Padding', 'stylePadding'),
  shared('Border radius', 'styleRadius'),
  shared('Border', 'styleBorder'),
  shared('Opacity', 'styleOpacity'),
  shared('Scale', 'styleScale'),
  shared('Min Width', 'styleMinW'),
  shared('Min Height', 'styleMinH'),
  shared('Max Width', 'styleMaxW'),
  shared('Max Height', 'styleMaxH'),
  shared('Overflow', 'styleOverflow'),
  shared('Shadow', 'styleShadow'),
  shared('Font', 'styleFont'),
  shared('Position', 'stylePosition'),
  shared('Decoration', 'styleDecoration'),
  shared('font-weight', 'styleWeight'),
]

export const FORM_CTL_STYLE_CATALOG = STYLE

export const FORM_CTL_BASIS_CATALOG = BASIS

const BASIC: FormCtlDef[] = [
  {
    id: 'input',
    path: '/form-ctl-input',
    oldHash: 'input',
    navTitleKey: 'nav.formCtlInput',
    figure: 'guides/dw-form-ctl-input-props.png',
    eventSamples: [
      {
        code: "if (value) { api.setValue('requester', user && user.displayName) }",
        hintKey: 'formCtl.input.eventHint',
      },
    ],
    catalog: [
      row('input', 'Control type', 'catControlType'),
      row('input', 'Sensitive Mask', 'catMask'),
      row('input', 'Mask sensitive value', 'catMaskEnabled'),
      row('input', 'Mask mode', 'catMaskMode'),
      row('input', 'Mask character', 'catMaskChar'),
      row('input', 'Preview', 'catMaskPreview'),
      row('input', 'Reveal plain text on focus', 'catMaskReveal'),
      row('input', 'Type', 'catType'),
      shared('Maximum input length', 'maxlength'),
      shared('Placeholder', 'placeholder'),
      shared('Whether to display the clear button', 'clearable'),
      shared('Disabled', 'disabled'),
      shared('Readonly', 'readonly'),
    ],
    failKeys: ['failField', 'failMask', 'failUser', 'failSave'],
    relatedIds: ['textarea', 'password'],
  },
  {
    id: 'textarea',
    path: '/form-ctl-textarea',
    oldHash: 'textarea',
    navTitleKey: 'nav.formCtlTextarea',
    figure: 'guides/dw-form-ctl-textarea-props.png',
    eventSamples: [
      {
        code: "if (value && value.length > 400) { api.setFieldError('notes', 'Keep notes under 400 characters') }",
        hintKey: 'formCtl.textarea.eventHint',
      },
    ],
    shapeSample: shape('textarea', "api.setValue('notes', 'Keep notes short')"),
    catalog: [
      shared('Disabled', 'disabled'),
      shared('Readonly', 'readonly'),
      shared('Maximum input length', 'maxlength'),
      shared('Whether to display word count statistics', 'showWordLimit'),
      shared('Placeholder', 'placeholder'),
      row('textarea', 'Number of input box rows', 'catRows'),
      row('textarea', 'Whether the height is adaptive', 'catAutosize'),
    ],
    failKeys: ['failField', 'failSave'],
    relatedIds: ['input', 'editor'],
  },
  {
    id: 'password',
    path: '/form-ctl-password',
    oldHash: 'password',
    navTitleKey: 'nav.formCtlPassword',
    figure: 'guides/dw-form-ctl-password-props.png',
    eventSamples: [
      {
        code: "if (value && value.length < 8) { api.setFieldError(field, 'Use at least 8 characters') }",
        hintKey: 'formCtl.password.eventHint',
      },
    ],
    catalog: [
      shared('Disabled', 'disabled'),
      shared('Readonly', 'readonly'),
      shared('Maximum input length', 'maxlength'),
      shared('Placeholder', 'placeholder'),
      shared('Whether to display the clear button', 'clearable'),
    ],
    failKeys: ['failField', 'failSecret', 'failSave'],
    relatedIds: ['input'],
  },
  {
    id: 'inputNumber',
    path: '/form-ctl-input-number',
    oldHash: 'inputNumber',
    navTitleKey: 'nav.formCtlInputNumber',
    figure: 'guides/dw-form-ctl-input-number-props.png',
    eventSamples: [
      {
        code: "if (value < 1) { api.setValue('quantity', 1) }",
        hintKey: 'formCtl.inputNumber.eventHint',
      },
    ],
    catalog: [
      shared('Disabled', 'disabled'),
      row('inputNumber', 'Set the minimum value allowed for the counter', 'catMin'),
      row('inputNumber', 'Set the maximum allowed value of the counter', 'catMax'),
      row('inputNumber', 'Precision of input value', 'catPrecision'),
      row('inputNumber', 'Step', 'catStep'),
      row('inputNumber', 'Whether only multiples of step can be entered', 'catStepStrictly'),
      row('inputNumber', 'Whether to use control buttons', 'catControls'),
      row('inputNumber', 'Control button position', 'catControlsPosition'),
      shared('Placeholder', 'placeholder'),
      shared('Readonly', 'readonly'),
    ],
    failKeys: ['failField', 'failSave'],
    relatedIds: ['slider', 'rate'],
  },
  {
    id: 'radio',
    path: '/form-ctl-radio',
    oldHash: 'radio',
    navTitleKey: 'nav.formCtlRadio',
    figure: 'guides/dw-form-ctl-radio-props.png',
    eventSamples: [
      {
        code: "api.required(value === 'A', ['start_date', 'end_date'])",
        hintKey: 'formCtl.radio.eventHint',
      },
    ],
    shapeSample: shape('radio', "api.setValue(field, 'A')"),
    catalog: [
      shared('Options', 'options'),
      shared('Disabled', 'disabled'),
      row('radio', 'Whether to fill in', 'catInput'),
      row('radio', 'Type', 'catType'),
      row('radio', 'Text color when button form is activated', 'catTextColor'),
      row('radio', 'Fill color and border color when the button form is activated', 'catFill'),
      shared('Readonly', 'readonly'),
    ],
    failKeys: ['failField', 'failOptions', 'failSave'],
    relatedIds: ['checkbox', 'select'],
  },
  {
    id: 'checkbox',
    path: '/form-ctl-checkbox',
    oldHash: 'checkbox',
    navTitleKey: 'nav.formCtlCheckbox',
    figure: 'guides/dw-form-ctl-checkbox-props.png',
    eventSamples: [
      {
        code: "api.required(value && value.length > 0, ['notes'])",
        hintKey: 'formCtl.checkbox.eventHint',
      },
    ],
    shapeSample: shape('checkbox', "api.setValue(field, ['A', 'B'])"),
    catalog: [
      shared('Options', 'options'),
      shared('Disabled', 'disabled'),
      row('checkbox', 'Whether to fill in', 'catInput'),
      row('checkbox', 'Type', 'catType'),
      row('checkbox', 'Minimum number that can be checked', 'catMin'),
      row('checkbox', 'The maximum number that can be checked', 'catMax'),
      row('checkbox', 'Font color when the button is active', 'catTextColor'),
      row('checkbox', 'Border and background color when the button is active', 'catFill'),
      shared('Readonly', 'readonly'),
    ],
    failKeys: ['failField', 'failOptions', 'failSave'],
    relatedIds: ['radio', 'select'],
  },
  {
    id: 'select',
    path: '/form-ctl-select',
    oldHash: 'select',
    navTitleKey: 'nav.formCtlSelect',
    figure: 'guides/dw-form-ctl-select-props.png',
    eventSamples: [
      {
        code: "var on = value === 'A'\napi.required(on, ['start_date', 'end_date'])",
        hintKey: 'formCtl.select.eventHint',
      },
      {
        code: "api.setOptions('scenario', [{ label: 'A', value: 'A' }])",
        hintKey: 'formCtl.select.eventHintOptions',
      },
    ],
    shapeSample: shape('select', "api.setValue('scenario', 'A')"),
    catalog: [
      shared('Options', 'options'),
      row('select', 'Default Value', 'catDefault'),
      row('select', 'Whether there are multiple selections', 'catMultiple'),
      shared('Disabled', 'disabled'),
      row('select', 'Whether the option can be cleared', 'catClearable'),
      row('select', 'Whether to display the selected value as text during multi-selection', 'catCollapse'),
      row('select', 'The maximum number of items that the user can select when multiple-selecting, if it is 0, there is no limit', 'catLimit'),
      shared('Placeholder', 'placeholder'),
      row('select', 'Is it searchable', 'catFilterable'),
      row('select', 'Whether the options are loaded remotely from the server', 'catRemote'),
      row('select', 'Custom remote search methods', 'catRemoteMethod'),
      row('select', 'Whether users are allowed to create new entries', 'catAllowCreate'),
      row('select', 'Text displayed when no search conditions match', 'catNoMatch'),
      row('select', 'Text displayed when option is empty', 'catNoData'),
      row('select', 'When multiple selections are searchable, whether to retain the current search keyword after selecting an option', 'catReserve'),
      row('select', 'Press Enter in the input box and select the first matching item', 'catFirst'),
      shared('Readonly', 'readonly'),
    ],
    failKeys: ['failField', 'failOptions', 'failSave'],
    relatedIds: ['radio', 'checkbox'],
  },
  {
    id: 'switch',
    path: '/form-ctl-switch',
    oldHash: 'switch',
    navTitleKey: 'nav.formCtlSwitch',
    figure: 'guides/dw-form-ctl-switch-props.png',
    eventSamples: [
      {
        code: "api.hidden(!value, 'cost_center')",
        hintKey: 'formCtl.switch.eventHint',
      },
    ],
    shapeSample: shape('switch', 'api.setValue(field, true)'),
    catalog: [
      shared('Disabled', 'disabled'),
      row('switch', 'Width (px)', 'catWidth'),
      row('switch', 'Text description when opening', 'catActiveText'),
      row('switch', 'Text description when closing', 'catInactiveText'),
      row('switch', 'Value when opening', 'catActiveValue'),
      row('switch', 'Value when closed', 'catInactiveValue'),
      row('switch', 'Background color when opening', 'catActiveColor'),
      row('switch', 'Background color when closed', 'catInactiveColor'),
      shared('Readonly', 'readonly'),
    ],
    failKeys: ['failField', 'failSave'],
    relatedIds: ['checkbox'],
  },
  {
    id: 'slider',
    path: '/form-ctl-slider',
    oldHash: 'slider',
    navTitleKey: 'nav.formCtlSlider',
    figure: 'guides/dw-form-ctl-slider-props.png',
    eventSamples: [
      {
        code: "if (value > 80) { api.setFieldError(field, 'Keep the score at 80 or below') }",
        hintKey: 'formCtl.slider.eventHint',
      },
    ],
    shapeSample: shape('slider', 'api.setValue(field, 1)'),
    catalog: [
      row('slider', 'Min', 'catMin'),
      row('slider', 'Max', 'catMax'),
      row('slider', 'Step', 'catStep'),
      shared('Readonly', 'readonly'),
    ],
    failKeys: ['failField', 'failSave'],
    relatedIds: ['inputNumber', 'rate'],
  },
  {
    id: 'rate',
    path: '/form-ctl-rate',
    oldHash: 'rate',
    navTitleKey: 'nav.formCtlRate',
    figure: 'guides/dw-form-ctl-rate-props.png',
    eventSamples: [
      {
        code: "if (value < 3) { api.required(true, ['notes']) }",
        hintKey: 'formCtl.rate.eventHint',
      },
    ],
    shapeSample: shape('rate', 'api.setValue(field, 3)'),
    catalog: [
      row('rate', 'Maximum score', 'catMax'),
      shared('Disabled', 'disabled'),
      row('rate', 'Whether to allow half selection', 'catHalf'),
      row('rate', 'Color of the icon when not selected', 'catVoid'),
      row('rate', 'The color of the icon when it is not selected when read-only', 'catDisabledVoid'),
      row('rate', 'Class name of the icon when not selected', 'catVoidIcon'),
      row('rate', 'The class name of the icon when it is not selected when read-only', 'catDisabledVoidIcon'),
      row('rate', 'Whether to display the current score', 'catShowScore'),
      row('rate', 'Color of auxiliary text', 'catTextColor'),
      row('rate', 'Score display template', 'catTemplate'),
      shared('Readonly', 'readonly'),
    ],
    failKeys: ['failField', 'failSave'],
    relatedIds: ['slider', 'inputNumber'],
  },
  {
    id: 'date',
    path: '/form-ctl-date',
    oldHash: 'datePicker',
    navTitleKey: 'nav.formCtlDate',
    figure: 'guides/dw-form-ctl-date-props.png',
    eventSamples: [
      {
        code: "if (value && formData.end_date && value > formData.end_date) { api.setFieldError('start_date', 'Start must be on or before Need-by') }",
        hintKey: 'formCtl.date.eventHint',
      },
    ],
    shapeSample: shape('date', "api.setValue('start_date', '2026-09-11')"),
    catalog: [
      shared('Readonly', 'readonly'),
      shared('Disabled', 'disabled'),
      row('date', 'Type', 'catType'),
      shared('Whether to display the clear button', 'clearable'),
      row('date', 'Text box can be input', 'catEditable'),
      row('date', 'Placeholder content for non-range selection', 'catPlaceholder'),
      row('date', 'Placeholder content for the start date when selecting the range', 'catStartPh'),
      row('date', 'Placeholder content for the end date when selecting a range', 'catEndPh'),
      row('date', 'Format displayed in the input box', 'catFormat'),
      row('date', 'Alignment', 'catAlign'),
      row('date', 'Separator when selecting range', 'catSep'),
      row('date', 'Unlink the two date panels in the range selector', 'catUnlink'),
    ],
    failKeys: ['failField', 'failSave'],
    relatedIds: ['dateRange', 'time'],
  },
  {
    id: 'dateRange',
    path: '/form-ctl-date-range',
    oldHash: 'dateRange',
    navTitleKey: 'nav.formCtlDateRange',
    figure: 'guides/dw-form-ctl-date-range-props.png',
    eventSamples: [
      {
        code: "if (value && value[0] && value[1]) { api.setValue('start_date', value[0]); api.setValue('end_date', value[1]) }",
        hintKey: 'formCtl.dateRange.eventHint',
      },
    ],
    catalog: [
      shared('Readonly', 'readonly'),
      shared('Disabled', 'disabled'),
      row('dateRange', 'Type', 'catType'),
      shared('Whether to display the clear button', 'clearable'),
      row('dateRange', 'Text box can be input', 'catEditable'),
      row('dateRange', 'Placeholder content for the start date when selecting the range', 'catStartPh'),
      row('dateRange', 'Placeholder content for the end date when selecting a range', 'catEndPh'),
      row('dateRange', 'Format displayed in the input box', 'catFormat'),
      row('dateRange', 'Alignment', 'catAlign'),
      row('dateRange', 'Separator when selecting range', 'catSep'),
      row('dateRange', 'Unlink the two date panels in the range selector', 'catUnlink'),
    ],
    failKeys: ['failField', 'failSave'],
    relatedIds: ['date', 'timeRange'],
  },
  {
    id: 'time',
    path: '/form-ctl-time',
    oldHash: 'timePicker',
    navTitleKey: 'nav.formCtlTime',
    figure: 'guides/dw-form-ctl-time-props.png',
    eventSamples: [
      {
        code: 'if (value) { api.clearFieldError(field) }',
        hintKey: 'formCtl.time.eventHint',
      },
    ],
    shapeSample: shape('time', "api.setValue(field, '09:00:00')"),
    catalog: [
      shared('Readonly', 'readonly'),
      shared('Disabled', 'disabled'),
      row('time', 'Whether to select a time range', 'catRange'),
      shared('Whether to display the clear button', 'clearable'),
      row('time', 'Text box can be input', 'catEditable'),
      row('time', 'Placeholder content for non-range selection', 'catPlaceholder'),
      row('time', 'Placeholder content for the start date when selecting the range', 'catStartPh'),
      row('time', 'Placeholder content for the start date when selecting the range', 'catEndPh'),
      row('time', 'Whether to use arrows for time selection', 'catArrow'),
      row('time', 'Align', 'catAlign'),
    ],
    failKeys: ['failField', 'failSave'],
    relatedIds: ['timeRange', 'date'],
  },
  {
    id: 'timeRange',
    path: '/form-ctl-time-range',
    oldHash: 'timeRange',
    navTitleKey: 'nav.formCtlTimeRange',
    figure: 'guides/dw-form-ctl-time-range-props.png',
    eventSamples: [
      {
        code: "if (value && value[0] && value[1] && value[0] === value[1]) { api.setFieldError(field, 'End time must be after start') }",
        hintKey: 'formCtl.timeRange.eventHint',
      },
    ],
    shapeSample: shape('timeRange', "api.setValue(field, ['09:00:00', '18:00:00'])"),
    catalog: [
      shared('Readonly', 'readonly'),
      shared('Disabled', 'disabled'),
      shared('Whether to display the clear button', 'clearable'),
      row('timeRange', 'Text box can be input', 'catEditable'),
      row('timeRange', 'Placeholder content for the start date when selecting the range', 'catStartPh'),
      row('timeRange', 'Placeholder content for the start date when selecting the range', 'catEndPh'),
      row('timeRange', 'Whether to use arrows for time selection', 'catArrow'),
      row('timeRange', 'Align', 'catAlign'),
    ],
    failKeys: ['failField', 'failSave'],
    relatedIds: ['time', 'dateRange'],
  },
  {
    id: 'cascader',
    path: '/form-ctl-cascader',
    oldHash: 'cascader',
    navTitleKey: 'nav.formCtlCascader',
    figure: 'guides/dw-form-ctl-cascader-props.png',
    eventSamples: [
      {
        code: 'if (value) { api.clearFieldError(field) }',
        hintKey: 'formCtl.cascader.eventHint',
      },
    ],
    shapeSample: shape('cascader', "api.setValue(field, ['group', 'item'])"),
    catalog: [
      shared('Placeholder', 'placeholder'),
      shared('Readonly', 'readonly'),
    ],
    failKeys: ['failField', 'failSave'],
    relatedIds: ['select', 'treeSelect'],
  },
  {
    id: 'colorPicker',
    path: '/form-ctl-color-picker',
    oldHash: 'colorPicker',
    navTitleKey: 'nav.formCtlColorPicker',
    figure: 'guides/dw-form-ctl-color-picker-props.png',
    eventSamples: [
      {
        code: 'if (value) { api.clearFieldError(field) }',
        hintKey: 'formCtl.colorPicker.eventHint',
      },
    ],
    shapeSample: shape('colorPicker', "api.setValue(field, '#409EFF')"),
    catalog: [
      shared('Disabled', 'disabled'),
      row('colorPicker', 'Whether transparency selection is supported', 'catAlpha'),
      row('colorPicker', 'Color format', 'catFormat'),
      row('colorPicker', 'Predefined color', 'catPredefine'),
      shared('Readonly', 'readonly'),
    ],
    failKeys: ['failField', 'failSave'],
    relatedIds: ['input'],
  },
  {
    id: 'upload',
    path: '/form-ctl-upload',
    oldHash: 'upload',
    navTitleKey: 'nav.formCtlUpload',
    figure: 'guides/dw-form-ctl-upload-props.png',
    eventSamples: [
      {
        code: "api.setFieldError(field, 'Upload failed')",
        hintKey: 'formCtl.upload.eventHint',
      },
    ],
    catalog: [
      shared('Disabled', 'disabled'),
      row('upload', 'Upload type', 'catListType'),
      row('upload', 'Whether multiple selection of files is supported', 'catMultiple'),
      row('upload', 'Upload address (required)', 'catAction'),
      row('upload', 'Accept uploaded file types', 'catAccept'),
      row('upload', 'Triggered before uploading a file', 'catBeforeUpload'),
      row('upload', 'Triggered before deleting a file', 'catBeforeRemove'),
      row('upload', 'Triggered when the upload is successful', 'catOnSuccess'),
      row('upload', 'Set upload request headers', 'catHeaders'),
      row('upload', 'Extra parameters attached when uploading', 'catData'),
      row('upload', 'Support sending cookie credential information', 'catCreds'),
      row('upload', 'Whether to upload the file immediately after selecting it', 'catAuto'),
      row('upload', 'Maximum number of uploads allowed', 'catLimit'),
      shared('Readonly', 'readonly'),
    ],
    failKeys: ['failField', 'failAction', 'failSave'],
    relatedIds: [],
  },
  {
    id: 'tree',
    path: '/form-ctl-tree',
    oldHash: 'tree',
    navTitleKey: 'nav.formCtlTree',
    figure: 'guides/dw-form-ctl-tree-props.png',
    eventSamples: [
      {
        code: "api.setValue('cost_center', value)",
        hintKey: 'formCtl.tree.eventHint',
      },
    ],
    catalog: [
      shared('Options', 'options'),
      row('tree', 'Text displayed when the content is empty', 'catEmpty'),
      row('tree', 'Whether to render its child nodes after expanding a tree node for the first time', 'catRender'),
      row('tree', 'Whether to expand all nodes by default', 'catExpandAll'),
      row('tree', 'Whether to expand or contract the node when clicking the node, if it is false, the node will only be expanded or contracted when the arrow icon is clicked.', 'catExpandClick'),
      row('tree', 'Whether to select the node when clicking the node', 'catCheckClick'),
      row('tree', 'Whether to automatically expand the parent node when expanding the child node', 'catAutoParent'),
      row('tree', 'When the check box is displayed, whether the parent and child are strictly not related to each other should be strictly followed', 'catStrict'),
      row('tree', 'Whether to open only one sibling tree node for expansion at a time', 'catAccordion'),
      row('tree', 'Horizontal indent (px) between adjacent level nodes', 'catIndent'),
      row('tree', 'Each tree node is used as an attribute for unique identification, and the entire tree should be unique', 'catNodeKey'),
      shared('Readonly', 'readonly'),
    ],
    failKeys: ['failField', 'failSave'],
    relatedIds: ['treeSelect'],
  },
  {
    id: 'treeSelect',
    path: '/form-ctl-tree-select',
    oldHash: 'elTreeSelect',
    navTitleKey: 'nav.formCtlTreeSelect',
    figure: 'guides/dw-form-ctl-tree-select-props.png',
    eventSamples: [
      {
        code: "api.setValue('cost_center', value)",
        hintKey: 'formCtl.treeSelect.eventHint',
      },
    ],
    catalog: [
      shared('Options', 'options'),
      row('treeSelect', 'Whether there are multiple selections', 'catMultiple'),
      shared('Disabled', 'disabled'),
      row('treeSelect', 'Whether the option can be cleared', 'catClearable'),
      row('treeSelect', 'Whether to display the selected value as text during multi-selection', 'catCollapse'),
      row('treeSelect', 'The maximum number of items that the user can select during multiple selection, if it is 0, there is no limit', 'catLimit'),
      shared('Placeholder', 'placeholder'),
      row('treeSelect', 'Whether to render its child nodes after expanding a tree node for the first time', 'catRender'),
      row('treeSelect', 'Whether to expand all nodes by default', 'catExpandAll'),
      row('treeSelect', 'Whether to expand or shrink nodes when clicking on them', 'catExpandClick'),
      row('treeSelect', 'Whether to select the node when clicking the node', 'catCheckClick'),
      row('treeSelect', 'Each tree node is used as an attribute for unique identification, and the entire tree should be unique', 'catNodeKey'),
      shared('Readonly', 'readonly'),
    ],
    failKeys: ['failField', 'failSave'],
    relatedIds: ['tree', 'select'],
  },
  {
    id: 'transfer',
    path: '/form-ctl-transfer',
    oldHash: 'transfer',
    navTitleKey: 'nav.formCtlTransfer',
    figure: 'guides/dw-form-ctl-transfer-props.png',
    eventSamples: [
      {
        code: 'if (value && value.length) { api.clearFieldError(field) }',
        hintKey: 'formCtl.transfer.eventHint',
      },
    ],
    shapeSample: shape('transfer', "api.setValue(field, ['A', 'B'])"),
    catalog: [
      row('transfer', 'Left Title', 'catLeft'),
      row('transfer', 'Right Title', 'catRight'),
      shared('Readonly', 'readonly'),
    ],
    failKeys: ['failField', 'failSave'],
    relatedIds: ['checkbox'],
  },
  {
    id: 'editor',
    path: '/form-ctl-editor',
    oldHash: 'editor',
    navTitleKey: 'nav.formCtlEditor',
    figure: 'guides/dw-form-ctl-editor-props.png',
    eventSamples: [
      {
        code: "if (value) { api.clearFieldError('notes') }",
        hintKey: 'formCtl.editor.eventHint',
      },
    ],
    shapeSample: shape('editor', "api.setValue('notes', 'Office chairs')"),
    catalog: [
      row('editor', 'Rows', 'catRows'),
      shared('Placeholder', 'placeholder'),
      row('editor', 'Max Length', 'catMax'),
      shared('Readonly', 'readonly'),
    ],
    failKeys: ['failField', 'failSave'],
    relatedIds: ['textarea'],
  },
]

export const BASIC_FORM_CONTROLS: readonly FormCtlDef[] = BASIC

export const BASIC_HASH_REDIRECTS: Record<string, string> = Object.fromEntries(
  BASIC.map((c) => [c.oldHash, c.path]),
)

export function formCtlByPath(
  path: string,
  extra: readonly FormCtlDef[] = [],
): FormCtlDef | undefined {
  return BASIC.find((c) => c.path === path) ?? extra.find((c) => c.path === path)
}

export function formCtlRelated(def: FormCtlDef, extra: readonly FormCtlDef[] = []): GuideRelated[] {
  const pool = [...BASIC, ...extra]
  const siblings: GuideRelated[] = def.relatedIds.map((id) => {
    const other = pool.find((c) => c.id === id)
    if (!other) {
      throw new Error(`Unknown related control: ${id}`)
    }
    return { to: other.path, titleKey: other.navTitleKey }
  })
  const index: GuideRelated =
    def.group === 'extend'
      ? { to: '/form-events-extend', titleKey: 'guides.formEventsExtend.title' }
      : { to: '/form-events-basic', titleKey: 'guides.formEventsBasic.title' }
  const links: GuideRelated[] = [
    { to: '/form-events#values', titleKey: 'guides.formEvents.title' },
    index,
  ]
  if (def.id === 'upload') {
    links.unshift({ to: '/form-upload', titleKey: 'guides.formUpload.title' })
  }
  if (def.id === 'lookup') {
    links.unshift({ to: '/form-events#lookup', titleKey: 'formEventsGuide.lookupTitle' })
  }
  return [...siblings, ...links]
}

export const BASIC_INDEX_RELATED: GuideRelated[] = BASIC.map((c) => ({
  to: c.path,
  titleKey: c.navTitleKey,
}))
