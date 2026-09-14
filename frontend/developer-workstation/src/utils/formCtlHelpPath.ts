/**
 * Help path for the selected Form Design control (Basic + Extend).
 * Keep in sync with frontend/help/src/formCtlBasic.ts and formCtlExtend.ts.
 */
export function formCtlHelpPath(rule: Record<string, unknown> | null | undefined): string {
  if (!rule) return '/form-events-basic'
  const type = String(rule.type ?? '')
  const props =
    rule.props && typeof rule.props === 'object'
      ? (rule.props as Record<string, unknown>)
      : {}
  if (type === 'input') {
    if (props.type === 'textarea') return '/form-ctl-textarea'
    if (props.type === 'password') return '/form-ctl-password'
    return '/form-ctl-input'
  }
  const byType: Record<string, string> = {
    textarea: '/form-ctl-textarea',
    password: '/form-ctl-password',
    inputNumber: '/form-ctl-input-number',
    radio: '/form-ctl-radio',
    checkbox: '/form-ctl-checkbox',
    select: '/form-ctl-select',
    switch: '/form-ctl-switch',
    slider: '/form-ctl-slider',
    rate: '/form-ctl-rate',
    datePicker: props.type === 'datetimerange' || props.type === 'daterange' || props.type === 'monthrange'
      ? '/form-ctl-date-range'
      : '/form-ctl-date',
    dateRange: '/form-ctl-date-range',
    timePicker: props.isRange ? '/form-ctl-time-range' : '/form-ctl-time',
    timeRange: '/form-ctl-time-range',
    cascader: '/form-ctl-cascader',
    colorPicker: '/form-ctl-color-picker',
    upload: '/form-ctl-upload',
    tree: '/form-ctl-tree',
    elTreeSelect: '/form-ctl-tree-select',
    transfer: '/form-ctl-transfer',
    elTransfer: '/form-ctl-transfer',
    editor: '/form-ctl-editor',
    fcEditor: '/form-ctl-editor',
    subTable: '/form-ctl-sub-table',
    lookup: '/form-ctl-lookup',
    inlineSubForm: '/form-events-extend#inlineSubForm',
    linkForm: '/form-events-extend#linkForm',
    owner: '/form-events-extend#owner',
    recordNote: '/form-events-extend#recordNote',
    miAssignment: '/form-events-extend#miAssignment',
    advancedUpload: '/form-upload',
  }
  return byType[type] ?? '/form-events-basic'
}
