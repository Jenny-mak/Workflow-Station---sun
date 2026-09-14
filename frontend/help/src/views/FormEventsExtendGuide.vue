<template>
  <GuideArticle
    test-id="form-events-extend-guide-page"
    page-title-key="formEventsExtend.pageTitle"
    intro-key="formEventsExtend.intro"
    crumb-key="formEventsExtend.crumb"
    flow-title-key="formEventsExtend.flowTitle"
    :flow-keys="flowKeys"
    :related="related"
    :sections="sections"
  />
</template>

<script setup lang="ts">
import GuideArticle, { type GuideRelated, type GuideSample, type GuideSection } from '@/components/GuideArticle.vue'
import { EXTEND_INDEX_RELATED } from '@/formCtlExtend'

const flowKeys = [
  'formEventsExtend.flow1',
  'formEventsExtend.flow2',
  'formEventsExtend.flow3',
  'formEventsExtend.flow4',
]

const related: GuideRelated[] = [
  ...EXTEND_INDEX_RELATED,
  { to: '/form-events#params', titleKey: 'guides.formEvents.title' },
  { to: '/form-events#lookup', titleKey: 'formEventsGuide.lookupTitle' },
]

function ctl(anchor: string, samples: GuideSample[]): GuideSection {
  return {
    anchor,
    titleKey: `formEventsExtend.${anchor}Title`,
    bodyKey: `formEventsExtend.${anchor}Body`,
    sampleLayout: 'block',
    samples,
  }
}

const sections: GuideSection[] = [
  ctl('inlineSubForm', [
    { code: "api.clearFieldError('item_name')", hintKey: 'formEventsExtend.inlineClick' },
  ]),
  ctl('linkForm', [
    { code: "if (!value) { api.setFieldError(field, 'Open the linked form') } else { api.clearFieldError(field) }", hintKey: 'formEventsExtend.linkFormChange' },
  ]),
  ctl('owner', [
    { code: "if (value) { api.setValue('requester', value) }", hintKey: 'formEventsExtend.ownerChange' },
  ]),
  ctl('recordNote', [
    { code: "api.clearFieldError('notes')", hintKey: 'formEventsExtend.recordNoteClick' },
  ]),
  ctl('miAssignment', [
    { code: "api.clearFieldError('requester')", hintKey: 'formEventsExtend.miClick' },
  ]),
]
</script>
