<template>
  <GuideArticle
    test-id="form-events-guide-page"
    page-title-key="formEventsGuide.pageTitle"
    intro-key="formEventsGuide.intro"
    crumb-key="formEventsGuide.crumb"
    flow-title-key="formEventsGuide.flowTitle"
    :flow-keys="flowKeys"
    :jump-links="jumpLinks"
    :related="related"
    :sections="sections"
    wide
  />
</template>

<script setup lang="ts">
import GuideArticle, {
  type GuideJump,
  type GuideRelated,
  type GuideSection,
} from '@/components/GuideArticle.vue'

const flowKeys = [
  'formEventsGuide.flow1',
  'formEventsGuide.flow2',
  'formEventsGuide.flow3',
  'formEventsGuide.flow4',
  'formEventsGuide.flow5',
]

const jumpLinks: GuideJump[] = [
  { anchor: 'when', titleKey: 'formEventsGuide.jumpWhen' },
  { anchor: 'values', titleKey: 'formEventsGuide.jumpValues' },
  { anchor: 'set-shapes', titleKey: 'formEventsGuide.jumpSetShapes' },
  { anchor: 'required', titleKey: 'formEventsGuide.jumpRequired' },
  { anchor: 'visibility', titleKey: 'formEventsGuide.jumpVis' },
  { anchor: 'errors', titleKey: 'formEventsGuide.jumpErrors' },
  { anchor: 'disabled', titleKey: 'formEventsGuide.jumpDisabled' },
  { anchor: 'options', titleKey: 'formEventsGuide.jumpOptions' },
  { anchor: 'notify', titleKey: 'formEventsGuide.jumpNotify' },
  { anchor: 'lookup', titleKey: 'formEventsGuide.jumpLookup' },
  { anchor: 'chrome', titleKey: 'formEventsGuide.jumpChrome' },
  { anchor: 'hooks', titleKey: 'formEventsGuide.jumpHooks' },
  { anchor: 'form-level', titleKey: 'formEventsGuide.jumpForm' },
]

const related: GuideRelated[] = [
  { to: '/form-events-basic', titleKey: 'guides.formEventsBasic.title' },
  { to: '/form-events-extend', titleKey: 'guides.formEventsExtend.title' },
  { to: '/form-events-layout', titleKey: 'guides.formEventsLayout.title' },
  { to: '/computed-fields', titleKey: 'guides.computedFields.title' },
]

const canvasFigure = {
  src: 'guides/dw-form-events-canvas.png',
  captionKey: 'formEventsGuide.canvasFigure',
}

const howTo = {
  sampleLayout: 'block' as const,
}

const sections: GuideSection[] = [
  {
    titleKey: 'formEventsGuide.whatTitle',
    bodyKey: 'formEventsGuide.whatBody',
  },
  {
    titleKey: 'formEventsGuide.openTitle',
    bodyKey: 'formEventsGuide.openBody',
    samples: [
      { code: 'Edit', hintKey: 'formEventsGuide.openSample' },
      { code: 'Create', hintKey: 'formEventsGuide.createSample' },
      { code: 'Save', hintKey: 'formEventsGuide.saveSample' },
      { code: 'Ok', hintKey: 'formEventsGuide.okSample' },
    ],
  },
  {
    anchor: 'when',
    titleKey: 'formEventsGuide.whenTitle',
    bodyKey: 'formEventsGuide.whenBody',
    figure: { src: 'guides/dw-form-events.png', captionKey: 'formEventsGuide.openFigure' },
    figureBeside: true,
    sampleLayout: 'block',
    samples: [
      { code: 'change', hintKey: 'formEventsGuide.whenChange' },
      { code: 'blur', hintKey: 'formEventsGuide.whenBlur' },
      { code: 'focus', hintKey: 'formEventsGuide.whenFocus' },
      { code: 'click', hintKey: 'formEventsGuide.whenClick' },
      { code: 'hook_load', hintKey: 'formEventsGuide.whenHookLoad' },
      { code: 'hook_mounted', hintKey: 'formEventsGuide.whenHookMounted' },
      { code: 'hook_deleted', hintKey: 'formEventsGuide.whenHookDeleted' },
      { code: 'hook_watch', hintKey: 'formEventsGuide.whenHookWatch' },
      { code: 'hook_value', hintKey: 'formEventsGuide.whenHookValue' },
      { code: 'hook_hidden', hintKey: 'formEventsGuide.whenHookHidden' },
      { code: 'hook_titleClick', hintKey: 'formEventsGuide.whenHookTitleClick' },
      { code: 'Custom', hintKey: 'formEventsGuide.whenCustom' },
    ],
  },
  {
    anchor: 'values',
    titleKey: 'formEventsGuide.valuesTitle',
    intentKey: 'formEventsGuide.valuesIntent',
    beforeKey: 'formEventsGuide.valuesBefore',
    afterKey: 'formEventsGuide.valuesAfter',
    noteKey: 'formEventsGuide.valuesNote',
    figure: canvasFigure,
    figureBeside: true,
    ...howTo,
    samples: [
      { code: "api.setValue('requester', user && user.displayName)", hintKey: 'formEventsGuide.apiSetValue' },
      { code: "var title = api.getValue('request_title')", hintKey: 'formEventsGuide.apiGetValue' },
      { code: "api.setValue({ requester: user && user.displayName, cost_center: '' })", hintKey: 'formEventsGuide.apiSetMany' },
      { code: 'var snapshot = api.form', hintKey: 'formEventsGuide.apiForm' },
    ],
  },
  {
    anchor: 'set-shapes',
    titleKey: 'formEventsGuide.setShapesTitle',
    bodyKey: 'formEventsGuide.setShapesBody',
    bodyKeys: [
      'formEventsGuide.setShapeString',
      'formEventsGuide.setShapeNumber',
      'formEventsGuide.setShapeOption',
      'formEventsGuide.setShapeSwitch',
      'formEventsGuide.setShapeKeys',
      'formEventsGuide.setShapeDate',
      'formEventsGuide.setShapeRange',
      'formEventsGuide.setShapePath',
      'formEventsGuide.setShapeUpload',
      'formEventsGuide.setShapeClear',
    ],
  },
  {
    anchor: 'required',
    titleKey: 'formEventsGuide.requiredTitle',
    intentKey: 'formEventsGuide.requiredIntent',
    beforeKey: 'formEventsGuide.requiredBefore',
    afterKey: 'formEventsGuide.requiredAfter',
    noteKey: 'formEventsGuide.requiredNote',
    ...howTo,
    samples: [
      { code: "api.required(value === 'A', ['start_date', 'end_date'])", hintKey: 'formEventsGuide.apiRequired' },
      { code: "api.required(true, ['start_date', 'end_date'])", hintKey: 'formEventsGuide.apiRequiredOn' },
      { code: "api.required(false, ['start_date', 'end_date'])", hintKey: 'formEventsGuide.apiRequiredOff' },
      { code: "var must = api.requiredStatus('start_date')", hintKey: 'formEventsGuide.apiRequiredStatus' },
    ],
  },
  {
    anchor: 'visibility',
    titleKey: 'formEventsGuide.visTitle',
    intentKey: 'formEventsGuide.visIntent',
    beforeKey: 'formEventsGuide.visBefore',
    afterKey: 'formEventsGuide.visAfter',
    noteKey: 'formEventsGuide.visNote',
    ...howTo,
    samples: [
      { code: "api.hidden(true, 'cost_center')", hintKey: 'formEventsGuide.apiHidden' },
      { code: "api.hidden(false, 'cost_center')", hintKey: 'formEventsGuide.apiHiddenOff' },
      { code: "api.display(true, 'notes')", hintKey: 'formEventsGuide.apiDisplay' },
      { code: "api.display(false, 'notes')", hintKey: 'formEventsGuide.apiDisplayOff' },
      { code: "if (api.hiddenStatus('cost_center')) { api.hidden(false, 'cost_center') }", hintKey: 'formEventsGuide.apiHiddenStatus' },
      { code: "if (!api.displayStatus('notes')) { api.display(false, 'notes') }", hintKey: 'formEventsGuide.apiDisplayStatus' },
    ],
  },
    {
    anchor: 'errors',
    titleKey: 'formEventsGuide.errorsTitle',
    intentKey: 'formEventsGuide.errorsIntent',
    beforeKey: 'formEventsGuide.errorsBefore',
    afterKey: 'formEventsGuide.errorsAfter',
    noteKey: 'formEventsGuide.errorsNote',
    figure: {
      src: 'guides/dw-form-events-preview-errors.png',
      captionKey: 'formEventsGuide.errorsFigure',
    },
    figureBeside: true,
    ...howTo,
    samples: [
      { code: "if (!value) { api.setFieldError('request_title', 'Title is required') } else { api.clearFieldError('request_title') }", hintKey: 'formEventsGuide.apiSetError' },
      { code: "api.clearFieldError('request_title')", hintKey: 'formEventsGuide.apiClearError' },
    ],
  },
    {
    anchor: 'disabled',
    titleKey: 'formEventsGuide.disabledTitle',
    intentKey: 'formEventsGuide.disabledIntent',
    beforeKey: 'formEventsGuide.disabledBefore',
    afterKey: 'formEventsGuide.disabledAfter',
    noteKey: 'formEventsGuide.disabledNote',
    figure: {
      src: 'guides/dw-form-events-preview-disabled.png',
      captionKey: 'formEventsGuide.disabledFigure',
    },
    figureBeside: true,
    ...howTo,
    samples: [
      { code: "if (value === 'A') { api.disabled(true, 'cost_center') } else { api.disabled(false, 'cost_center') }", hintKey: 'formEventsGuide.apiDisabledOn' },
      { code: "api.disabled(false, 'cost_center')", hintKey: 'formEventsGuide.apiDisabledOff' },
      { code: "var locked = api.disabledStatus('cost_center')", hintKey: 'formEventsGuide.apiDisabledStatus' },
    ],
  },
  {
    anchor: 'options',
    titleKey: 'formEventsGuide.optionsTitle',
    intentKey: 'formEventsGuide.optionsIntent',
    beforeKey: 'formEventsGuide.optionsBefore',
    afterKey: 'formEventsGuide.optionsAfter',
    noteKey: 'formEventsGuide.optionsNote',
    ...howTo,
    samples: [
      { code: "api.setOptions('scenario', [{ label: 'A', value: 'A' }])", hintKey: 'formEventsGuide.apiSetOptions' },
      { code: "api.addOption('scenario', { label: 'C', value: 'C' })", hintKey: 'formEventsGuide.apiAddOption' },
      { code: "api.removeOption('scenario', 'B')", hintKey: 'formEventsGuide.apiRemoveOption' },
      { code: "api.clearOptions('scenario')", hintKey: 'formEventsGuide.apiClearOptions' },
      { code: "api.resetOptions('scenario')", hintKey: 'formEventsGuide.apiResetOptions' },
    ],
  },
    {
    anchor: 'notify',
    titleKey: 'formEventsGuide.notifyTitle',
    intentKey: 'formEventsGuide.notifyIntent',
    beforeKey: 'formEventsGuide.notifyBefore',
    afterKey: 'formEventsGuide.notifyAfter',
    noteKey: 'formEventsGuide.notifyNote',
    figure: {
      src: 'guides/dw-form-events-preview-notify.png',
      captionKey: 'formEventsGuide.notifyFigure',
    },
    figureBeside: true,
    ...howTo,
    samples: [
      { code: "api.setFormNotification('Check the title', 'WARNING', 'title-warn')", hintKey: 'formEventsGuide.apiSetNotify' },
      { code: "api.clearFormNotification('title-warn')", hintKey: 'formEventsGuide.apiClearNotify' },
    ],
  },
  {
    anchor: 'lookup',
    titleKey: 'formEventsGuide.lookupTitle',
    intentKey: 'formEventsGuide.lookupIntent',
    beforeKey: 'formEventsGuide.lookupBefore',
    afterKey: 'formEventsGuide.lookupAfter',
    noteKey: 'formEventsGuide.lookupNote',
    ...howTo,
    samples: [
      { code: "api.setLookupFilter(field, [{ fieldName: 'status', value: 'Active', matchType: 'eq' }])", hintKey: 'formEventsGuide.apiSetLookupFilter' },
      { code: 'api.clearLookupFilter(field)', hintKey: 'formEventsGuide.apiClearLookupFilter' },
      { code: 'api.refresh(field)', hintKey: 'formEventsGuide.apiRefreshLookup' },
    ],
  },
  {
    anchor: 'chrome',
    titleKey: 'formEventsGuide.chromeTitle',
    intentKey: 'formEventsGuide.chromeIntent',
    beforeKey: 'formEventsGuide.chromeBefore',
    afterKey: 'formEventsGuide.chromeAfter',
    noteKey: 'formEventsGuide.chromeNote',
    ...howTo,
    samples: [
      { code: "api.setFocus('request_title')", hintKey: 'formEventsGuide.apiSetFocus' },
      { code: "api.setLabel('cost_center', 'Charge code')", hintKey: 'formEventsGuide.apiSetLabel' },
      { code: "var text = api.getLabel('cost_center')", hintKey: 'formEventsGuide.apiGetLabel' },
      { code: "api.resetLabel('cost_center')", hintKey: 'formEventsGuide.apiResetLabel' },
    ],
  },
  {
    anchor: 'params',
    titleKey: 'formEventsGuide.paramsTitle',
    bodyKey: 'formEventsGuide.paramsBody',
    bodyKeys: ['formEventsGuide.paramsRare'],
    sampleLayout: 'block',
    samples: [
      { code: 'var api = $inject.api', hintKey: 'formEventsGuide.paramInject' },
      { code: "api.setValue('requester', value)", hintKey: 'formEventsGuide.paramApi' },
      { code: 'var current = value', hintKey: 'formEventsGuide.paramValue' },
      { code: "if (field === 'scenario') { api.required(value === 'A', ['start_date', 'end_date']) }", hintKey: 'formEventsGuide.paramField' },
      { code: 'var name = user && user.displayName', hintKey: 'formEventsGuide.paramUser' },
    ],
  },
  {
    anchor: 'user',
    titleKey: 'formEventsGuide.userTitle',
    bodyKey: 'formEventsGuide.userBody',
    sampleLayout: 'block',
    samples: [
      { code: 'var bu = user && user.activeBusinessUnitName', hintKey: 'formEventsGuide.userBuSample' },
      { code: 'var role = user && user.activeRoleName', hintKey: 'formEventsGuide.userRoleSample' },
      { code: "api.setValue('requester', user && user.displayName)", hintKey: 'formEventsGuide.userSetSample' },
      { code: 'userId / username / email', hintKey: 'formEventsGuide.userIdSample' },
      { code: 'activeBusinessUnitId / activeRoleId', hintKey: 'formEventsGuide.userIdsSample' },
      { code: 'roles / language', hintKey: 'formEventsGuide.userExtraSample' },
    ],
  },
  {
    anchor: 'hooks',
    titleKey: 'formEventsGuide.hooksTitle',
    bodyKey: 'formEventsGuide.hooksBody',
    sampleLayout: 'block',
    samples: [
      { code: "api.setValue('requester', user && user.displayName)", hintKey: 'formEventsGuide.hookLoad' },
      { code: "api.hidden(!user, 'cost_center')", hintKey: 'formEventsGuide.hookMounted' },
      { code: "api.clearFieldError('request_title')", hintKey: 'formEventsGuide.hookDeleted' },
      { code: "api.required(!!api.getValue('scenario'), ['start_date'])", hintKey: 'formEventsGuide.hookWatch' },
      { code: "if (value === 'A') { api.required(true, ['start_date', 'end_date']) }", hintKey: 'formEventsGuide.hookValue' },
      { code: "api.clearFieldError('cost_center')", hintKey: 'formEventsGuide.hookHidden' },
      { code: "api.setValue('notes', 'Opened from label')", hintKey: 'formEventsGuide.hookTitleClick' },
    ],
  },
  {
    anchor: 'form-level',
    titleKey: 'formEventsGuide.formLevelTitle',
    intentKey: 'formEventsGuide.formLevelIntent',
    beforeKey: 'formEventsGuide.formLevelBefore',
    afterKey: 'formEventsGuide.formLevelAfter',
    noteKey: 'formEventsGuide.formLevelNote',
    figure: {
      src: 'guides/dw-form-events-form-tab.png',
      captionKey: 'formEventsGuide.formLevelFigure',
    },
    figureBeside: true,
    ...howTo,
    samples: [
      { code: "if (field === 'scenario') { api.required(value === 'A', ['start_date', 'end_date']); api.hidden(value !== 'A', 'notes') }", hintKey: 'formEventsGuide.formOnChangeScript' },
      { code: 'beforeSubmit — return false to stop Save', hintKey: 'formEventsGuide.formBeforeSubmit' },
      { code: 'onChange — field, value, options', hintKey: 'formEventsGuide.formOnChange' },
      { code: 'onSubmit — formData, api', hintKey: 'formEventsGuide.formOnSubmit' },
      { code: 'onReset — api', hintKey: 'formEventsGuide.formOnReset' },
      { code: 'onCreated — api', hintKey: 'formEventsGuide.formOnCreated' },
      { code: 'onMounted — api', hintKey: 'formEventsGuide.formOnMounted' },
      { code: 'onReload — api', hintKey: 'formEventsGuide.formOnReload' },
      { code: 'beforeFetch — config, data', hintKey: 'formEventsGuide.formBeforeFetch' },
    ],
  },
  {
    titleKey: 'formEventsGuide.failTitle',
    failKeys: [
      'formEventsGuide.failUser',
      'formEventsGuide.failPreview',
      'formEventsGuide.failScript',
      'formEventsGuide.failSave',
      'formEventsGuide.failCustom',
      'formEventsGuide.failReadonly',
      'formEventsGuide.failOptions',
      'formEventsGuide.failLookup',
      'formEventsGuide.failBlocks',
      'formEventsGuide.failSetValue',
    ],
  },
]
</script>
