<template>
  <GuideArticle
    test-id="computed-field-guide-page"
    page-title-key="computedFieldGuide.pageTitle"
    intro-key="computedFieldGuide.intro"
    crumb-key="computedFieldGuide.crumb"
    flow-title-key="computedFieldGuide.flowTitle"
    :flow-keys="flowKeys"
    :related="related"
    :sections="sections"
  />
</template>

<script setup lang="ts">
import GuideArticle, { type GuideRelated, type GuideSection } from '@/components/GuideArticle.vue'

const flowKeys = [
  'computedFieldGuide.flow1',
  'computedFieldGuide.flow2',
  'computedFieldGuide.flow3',
  'computedFieldGuide.flow4',
]

const related: GuideRelated[] = [
  { to: '/table-design', titleKey: 'guides.tableDesign.title' },
  { to: '/form-events', titleKey: 'guides.formEvents.title' },
  { to: '/email-send#template', titleKey: 'guides.emailSend.title' },
  { to: '/email-monitor', titleKey: 'guides.emailMonitor.title' },
]

const sections: GuideSection[] = [
  {
    titleKey: 'computedFieldGuide.basicsTitle',
    bodyKey: 'computedFieldGuide.basicsBody',
    figure: { src: 'guides/dw-table-design.png', captionKey: 'computedFieldGuide.basicsFigure' },
  },
  {
    titleKey: 'computedFieldGuide.thisRowTitle',
    bodyKey: 'computedFieldGuide.thisRowBody',
    samples: [{ code: 'quantity * unit_price', hintKey: 'computedFieldGuide.thisRowSample' }],
  },
  {
    titleKey: 'computedFieldGuide.relatedRowsTitle',
    bodyKey: 'computedFieldGuide.relatedRowsBody',
    samples: [
      { code: 'SUM(help_pr_line.line_total)', hintKey: 'computedFieldGuide.sampleSum' },
      { code: 'AVG(help_pr_line.line_total)', hintKey: 'computedFieldGuide.sampleAvg' },
      { code: 'MIN(help_pr_line.line_total) / MAX(help_pr_line.line_total)', hintKey: 'computedFieldGuide.sampleMinMax' },
      { code: 'COUNT(help_pr_line)', hintKey: 'computedFieldGuide.sampleCountRows' },
      { code: 'COUNT(help_pr_line.line_total)', hintKey: 'computedFieldGuide.sampleCountCells' },
    ],
  },
  {
    titleKey: 'computedFieldGuide.parentTitle',
    bodyKey: 'computedFieldGuide.parentBody',
    samples: [{ code: 'help_pr.request_title', hintKey: 'computedFieldGuide.parentSample' }],
  },
  {
    titleKey: 'computedFieldGuide.datesTitle',
    bodyKey: 'computedFieldGuide.datesBody',
    samples: [
      { code: 'end_date - start_date', hintKey: 'computedFieldGuide.dateMinus' },
      { code: 'DATEDIFF(start_date, end_date)', hintKey: 'computedFieldGuide.dateDatediff' },
    ],
  },
  {
    titleKey: 'computedFieldGuide.logicTitle',
    bodyKey: 'computedFieldGuide.logicBody',
    samples: [
      { code: 'IF(grand_total > 5000, "Y", "N")', hintKey: 'computedFieldGuide.fnIf' },
      { code: 'AND(...) / OR(...)', hintKey: 'computedFieldGuide.fnAndOr' },
      { code: 'SWITCH(value, match1, result1, …, default?)', hintKey: 'computedFieldGuide.fnSwitch' },
      { code: 'COALESCE(...)', hintKey: 'computedFieldGuide.fnCoalesce' },
    ],
  },
  { titleKey: 'computedFieldGuide.mathTitle', bodyKey: 'computedFieldGuide.mathBody' },
  { titleKey: 'computedFieldGuide.textTitle', bodyKey: 'computedFieldGuide.textBody' },
  { titleKey: 'computedFieldGuide.errorsTitle', bodyKey: 'computedFieldGuide.errorsBody' },
  {
    titleKey: 'computedFieldGuide.relationTitle',
    bodyKey: 'computedFieldGuide.relationBody',
    anchor: 'relation',
  },
]
</script>
