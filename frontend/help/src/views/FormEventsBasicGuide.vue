<template>
  <GuideArticle
    test-id="form-events-basic-guide-page"
    page-title-key="formEventsBasic.pageTitle"
    intro-key="formEventsBasic.intro"
    crumb-key="formEventsBasic.crumb"
    flow-title-key="formEventsBasic.flowTitle"
    :flow-keys="flowKeys"
    :related="related"
    :sections="sections"
  />
</template>

<script setup lang="ts">
import GuideArticle, { type GuideRelated, type GuideSection } from '@/components/GuideArticle.vue'
import { BASIC_INDEX_RELATED } from '@/formCtlBasic'

const flowKeys = [
  'formEventsBasic.flow1',
  'formEventsBasic.flow2',
  'formEventsBasic.flow3',
  'formEventsBasic.flow4',
]

const related: GuideRelated[] = [
  ...BASIC_INDEX_RELATED,
  { to: '/form-events#params', titleKey: 'guides.formEvents.title' },
]

const sections: GuideSection[] = [
  {
    titleKey: 'formEventsBasic.whatTitle',
    bodyKey: 'formEventsBasic.whatBody',
    figureBeside: true,
    figure: {
      src: 'guides/dw-form-ctl-basic-palette.png',
      captionKey: 'formEventsBasic.paletteFigure',
    },
  },
]
</script>
