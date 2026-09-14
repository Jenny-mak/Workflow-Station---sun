<template>
  <GuideArticle
    test-id="table-design-guide-page"
    page-title-key="tableDesignGuide.pageTitle"
    intro-key="tableDesignGuide.intro"
    crumb-key="tableDesignGuide.crumb"
    flow-title-key="tableDesignGuide.flowTitle"
    :flow-keys="flowKeys"
    :related="related"
    :jump-links="jumpLinks"
    :sections="sections"
  />
</template>

<script setup lang="ts">
import GuideArticle, { type GuideJump, type GuideRelated, type GuideSection } from '@/components/GuideArticle.vue'

const flowKeys = [
  'tableDesignGuide.flow1',
  'tableDesignGuide.flow2',
  'tableDesignGuide.flow3',
  'tableDesignGuide.flow4',
  'tableDesignGuide.flow5',
]

const related: GuideRelated[] = [
  { to: '/computed-fields', titleKey: 'guides.computedFields.title' },
  { to: '/view-design', titleKey: 'guides.viewDesign.title' },
  { to: '/form-events-basic', titleKey: 'guides.formEventsBasic.title' },
]

const jumpLinks: GuideJump[] = [
  { anchor: 'fields', titleKey: 'tableDesignGuide.fieldsTitle' },
  { anchor: 'pk', titleKey: 'tableDesignGuide.pkTitle' },
  { anchor: 'fk', titleKey: 'tableDesignGuide.fkTitle' },
  { anchor: 'request-id', titleKey: 'tableDesignGuide.requestIdTitle' },
]

const sections: GuideSection[] = [
  {
    titleKey: 'tableDesignGuide.whatTitle',
    bodyKey: 'tableDesignGuide.whatBody',
    figure: { src: 'guides/dw-table-design.png', captionKey: 'tableDesignGuide.fieldsFigure' },
  },
  {
    anchor: 'fields',
    titleKey: 'tableDesignGuide.fieldsTitle',
    bodyKey: 'tableDesignGuide.fieldsBody',
    sampleLayout: 'block',
    samples: [
      { code: 'Table Display Name', hintKey: 'tableDesignGuide.catDisplayName' },
      { code: 'Table Name', hintKey: 'tableDesignGuide.catTableName' },
      { code: 'Table Type', hintKey: 'tableDesignGuide.catTableType' },
      { code: 'Field Display Name', hintKey: 'tableDesignGuide.catFieldDisplay' },
      { code: 'Field Name', hintKey: 'tableDesignGuide.catFieldName' },
      { code: 'Data Type', hintKey: 'tableDesignGuide.catDataType' },
      { code: 'Length', hintKey: 'tableDesignGuide.catLength' },
      { code: 'Precision', hintKey: 'tableDesignGuide.catPrecision' },
      { code: 'Scale', hintKey: 'tableDesignGuide.catScale' },
      { code: 'Nullable', hintKey: 'tableDesignGuide.catNullable' },
      { code: 'Default Value', hintKey: 'tableDesignGuide.catDefault' },
      { code: 'Computed', hintKey: 'tableDesignGuide.catComputed' },
    ],
  },
  {
    anchor: 'pk',
    titleKey: 'tableDesignGuide.pkTitle',
    bodyKey: 'tableDesignGuide.pkBody',
    sampleLayout: 'block',
    samples: [
      { code: 'Primary Key', hintKey: 'tableDesignGuide.catPk' },
      { code: 'PK Generation', hintKey: 'tableDesignGuide.catPkGen' },
      { code: 'UUID', hintKey: 'tableDesignGuide.catPkUuid' },
      { code: 'Manual', hintKey: 'tableDesignGuide.catPkManual' },
      { code: 'Auto increment', hintKey: 'tableDesignGuide.catPkAuto' },
      { code: 'Prefixed sequence', hintKey: 'tableDesignGuide.catPkPrefixed' },
      { code: 'Date + daily sequence', hintKey: 'tableDesignGuide.catPkDaily' },
      { code: 'Date + monthly sequence', hintKey: 'tableDesignGuide.catPkMonthly' },
      { code: 'Custom format', hintKey: 'tableDesignGuide.catPkCustom' },
    ],
  },
  {
    anchor: 'fk',
    titleKey: 'tableDesignGuide.fkTitle',
    bodyKey: 'tableDesignGuide.fkBody',
    sampleLayout: 'block',
    samples: [
      { code: 'Foreign Key', hintKey: 'tableDesignGuide.catFk' },
      { code: 'Ref Table', hintKey: 'tableDesignGuide.catRefTable' },
      { code: 'Ref PK Fields', hintKey: 'tableDesignGuide.catRefPk' },
      { code: 'Relations', hintKey: 'tableDesignGuide.catRelations' },
    ],
  },
  {
    anchor: 'request-id',
    titleKey: 'tableDesignGuide.requestIdTitle',
    bodyKey: 'tableDesignGuide.requestIdBody',
    sampleLayout: 'block',
    samples: [{ code: 'Request ID', hintKey: 'tableDesignGuide.catRequestId' }],
  },
  {
    titleKey: 'tableDesignGuide.failTitle',
    failKeys: [
      'tableDesignGuide.failDisplayName',
      'tableDesignGuide.failTableName',
      'tableDesignGuide.failFieldName',
      'tableDesignGuide.failPkNullable',
      'tableDesignGuide.failRequestId',
    ],
  },
]
</script>
