<template>
  <GuideArticle
    test-id="view-design-guide-page"
    page-title-key="viewDesignGuide.pageTitle"
    intro-key="viewDesignGuide.intro"
    crumb-key="viewDesignGuide.crumb"
    flow-title-key="viewDesignGuide.flowTitle"
    :flow-keys="flowKeys"
    :related="related"
    :jump-links="jumpLinks"
    :sections="sections"
  />
</template>

<script setup lang="ts">
import GuideArticle, { type GuideJump, type GuideRelated, type GuideSection } from '@/components/GuideArticle.vue'

const flowKeys = [
  'viewDesignGuide.flow1',
  'viewDesignGuide.flow2',
  'viewDesignGuide.flow3',
  'viewDesignGuide.flow4',
  'viewDesignGuide.flow5',
]

const related: GuideRelated[] = [
  { to: '/table-design', titleKey: 'guides.tableDesign.title' },
  { to: '/form-ctl-lookup', titleKey: 'nav.formCtlLookup' },
]

const jumpLinks: GuideJump[] = [
  { anchor: 'columns', titleKey: 'viewDesignGuide.columnsTitle' },
  { anchor: 'props', titleKey: 'viewDesignGuide.propsTitle' },
  { anchor: 'access', titleKey: 'viewDesignGuide.accessTitle' },
]

const sections: GuideSection[] = [
    {
      titleKey: 'viewDesignGuide.whatTitle',
      bodyKey: 'viewDesignGuide.whatBody',
      figure: { src: 'guides/dw-view-design.png', captionKey: 'viewDesignGuide.workspaceFigure' },
    },
  {
    anchor: 'columns',
    titleKey: 'viewDesignGuide.columnsTitle',
    bodyKey: 'viewDesignGuide.columnsBody',
    sampleLayout: 'block',
    samples: [
      { code: 'Views', hintKey: 'viewDesignGuide.catViews' },
      { code: 'Generate default views', hintKey: 'viewDesignGuide.catDefaults' },
      { code: 'Create view', hintKey: 'viewDesignGuide.catCreate' },
      { code: 'Table columns', hintKey: 'viewDesignGuide.catTableColumns' },
      { code: 'Add selected', hintKey: 'viewDesignGuide.catAddSelected' },
    ],
  },
  {
    anchor: 'props',
    titleKey: 'viewDesignGuide.propsTitle',
    bodyKey: 'viewDesignGuide.propsBody',
    sampleLayout: 'block',
    samples: [
      { code: 'Name', hintKey: 'viewDesignGuide.catName' },
      { code: 'Show Export button', hintKey: 'viewDesignGuide.catExport' },
      { code: 'Detail form', hintKey: 'viewDesignGuide.catDetail' },
      { code: 'Sort by', hintKey: 'viewDesignGuide.catSort' },
      { code: 'Filter by', hintKey: 'viewDesignGuide.catFilter' },
    ],
  },
  {
    anchor: 'access',
    titleKey: 'viewDesignGuide.accessTitle',
    bodyKey: 'viewDesignGuide.accessBody',
    sampleLayout: 'block',
    samples: [
      { code: 'Access control', hintKey: 'viewDesignGuide.catAccess' },
      { code: 'Business Units', hintKey: 'viewDesignGuide.catBu' },
      { code: 'Roles', hintKey: 'viewDesignGuide.catRoles' },
      { code: 'Select a Business Unit first', hintKey: 'viewDesignGuide.catRolesFirst' },
      { code: 'Only involved users see data', hintKey: 'viewDesignGuide.catInvolved' },
    ],
  },
  {
    titleKey: 'viewDesignGuide.failTitle',
    failKeys: [
      'viewDesignGuide.failPair',
      'viewDesignGuide.failEmpty',
      'viewDesignGuide.failAnd',
      'viewDesignGuide.failMainTable',
    ],
  },
]
</script>
