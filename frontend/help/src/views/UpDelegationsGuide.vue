<template>
  <GuideArticle
    test-id="up-delegations-guide-page"
    page-title-key="upDelegationsGuide.pageTitle"
    intro-key="upDelegationsGuide.intro"
    crumb-key="upDelegationsGuide.crumb"
    flow-title-key="upDelegationsGuide.flowTitle"
    :flow-keys="flowKeys"
    :related="related"
    :sections="sections"
  />
</template>

<script setup lang="ts">
import GuideArticle, { type GuideRelated, type GuideSection } from '@/components/GuideArticle.vue'

const flowKeys = [
  'upDelegationsGuide.flow1',
  'upDelegationsGuide.flow2',
  'upDelegationsGuide.flow3',
  'upDelegationsGuide.flow4',
  'upDelegationsGuide.flow5',
]

const related: GuideRelated[] = [
  { to: '/task-delegate', titleKey: 'guides.taskDelegate.title' },
  { to: '/up-tasks-to-claim', titleKey: 'guides.upTasksToClaim.title' },
]

const sections: GuideSection[] = [
  {
    titleKey: 'upDelegationsGuide.openTitle',
    bodyKey: 'upDelegationsGuide.openBody',
    samples: [
      { code: 'Delegations', hintKey: 'upDelegationsGuide.openSample' },
      { code: 'Create Delegation', hintKey: 'upDelegationsGuide.createSample' },
    ],
  },
  {
    titleKey: 'upDelegationsGuide.userTitle',
    bodyKey: 'upDelegationsGuide.userBody',
    samples: [
      { code: 'Specified user', hintKey: 'upDelegationsGuide.userRadioSample' },
      { code: 'Delegate To', hintKey: 'upDelegationsGuide.userFieldSample' },
    ],
  },
  {
    titleKey: 'upDelegationsGuide.buTitle',
    bodyKey: 'upDelegationsGuide.buBody',
    samples: [
      { code: 'Specified BU and Role', hintKey: 'upDelegationsGuide.buRadioSample' },
      { code: 'Business Unit', hintKey: 'upDelegationsGuide.buFieldSample' },
      { code: 'Role', hintKey: 'upDelegationsGuide.roleFieldSample' },
    ],
  },
  {
    titleKey: 'upDelegationsGuide.typeTitle',
    bodyKey: 'upDelegationsGuide.typeBody',
    samples: [
      { code: 'All', hintKey: 'upDelegationsGuide.allSample' },
      { code: 'Partial', hintKey: 'upDelegationsGuide.partialSample' },
      { code: 'Temporary', hintKey: 'upDelegationsGuide.temporarySample' },
    ],
  },
  {
    titleKey: 'upDelegationsGuide.tasksTitle',
    bodyKey: 'upDelegationsGuide.tasksBody',
    samples: [
      { code: 'Delegated', hintKey: 'upDelegationsGuide.tabSample' },
      { code: 'On behalf of', hintKey: 'upDelegationsGuide.onBehalfSample' },
    ],
  },
  {
    titleKey: 'upDelegationsGuide.notTitle',
    bodyKey: 'upDelegationsGuide.notBody',
    samples: [
      { code: 'Delegate', hintKey: 'upDelegationsGuide.buttonSample' },
      { code: 'Transfer', hintKey: 'upDelegationsGuide.transferSample' },
    ],
  },
  { titleKey: 'upDelegationsGuide.failTitle', bodyKey: 'upDelegationsGuide.failBody' },
]
</script>
