<template>
  <GuideArticle
    test-id="task-delegate-guide-page"
    page-title-key="taskDelegateGuide.pageTitle"
    intro-key="taskDelegateGuide.intro"
    crumb-key="taskDelegateGuide.crumb"
    flow-title-key="taskDelegateGuide.flowTitle"
    :flow-keys="flowKeys"
    :related="related"
    :sections="sections"
  />
</template>

<script setup lang="ts">
import GuideArticle, { type GuideRelated, type GuideSection } from '@/components/GuideArticle.vue'

const flowKeys = [
  'taskDelegateGuide.flow1',
  'taskDelegateGuide.flow2',
  'taskDelegateGuide.flow3',
  'taskDelegateGuide.flow4',
  'taskDelegateGuide.flow5',
]

const related: GuideRelated[] = [
  { to: '/up-delegations', titleKey: 'guides.upDelegations.title' },
]

const sections: GuideSection[] = [
  {
    titleKey: 'taskDelegateGuide.openTitle',
    bodyKey: 'taskDelegateGuide.openBody',
    samples: [{ code: 'Delegate', hintKey: 'taskDelegateGuide.openSample' }],
  },
  {
    titleKey: 'taskDelegateGuide.userTitle',
    bodyKey: 'taskDelegateGuide.userBody',
    samples: [
      { code: 'Specified user', hintKey: 'taskDelegateGuide.userRadioSample' },
      { code: 'Target User', hintKey: 'taskDelegateGuide.userFieldSample' },
      { code: 'Click to search', hintKey: 'taskDelegateGuide.userSearchSample' },
    ],
  },
  {
    titleKey: 'taskDelegateGuide.buTitle',
    bodyKey: 'taskDelegateGuide.buBody',
    samples: [
      { code: 'Specified BU and Role', hintKey: 'taskDelegateGuide.buRadioSample' },
      { code: 'Business Unit', hintKey: 'taskDelegateGuide.buFieldSample' },
      { code: 'Role', hintKey: 'taskDelegateGuide.roleFieldSample' },
    ],
  },
  {
    titleKey: 'taskDelegateGuide.afterTitle',
    bodyKey: 'taskDelegateGuide.afterBody',
    samples: [
      { code: 'Current Assignee', hintKey: 'taskDelegateGuide.assigneeSample' },
      { code: 'Delegated to', hintKey: 'taskDelegateGuide.delegatedToSample' },
    ],
  },
  {
    titleKey: 'taskDelegateGuide.seeTitle',
    bodyKey: 'taskDelegateGuide.seeBody',
    samples: [
      { code: 'Delegated', hintKey: 'taskDelegateGuide.todoFilterSample' },
      { code: 'On behalf of', hintKey: 'taskDelegateGuide.onBehalfSample' },
    ],
  },
  {
    titleKey: 'taskDelegateGuide.notTitle',
    bodyKey: 'taskDelegateGuide.notBody',
    samples: [
      { code: 'Transfer', hintKey: 'taskDelegateGuide.transferSample' },
      { code: 'Delegations', hintKey: 'taskDelegateGuide.standingSample' },
    ],
  },
  { titleKey: 'taskDelegateGuide.failTitle', bodyKey: 'taskDelegateGuide.failBody' },
]
</script>
