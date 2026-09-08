<template>
  <GuideArticle
    test-id="up-tasks-to-claim-guide-page"
    page-title-key="upTasksToClaimGuide.pageTitle"
    intro-key="upTasksToClaimGuide.intro"
    crumb-key="upTasksToClaimGuide.crumb"
    flow-title-key="upTasksToClaimGuide.flowTitle"
    :flow-keys="flowKeys"
    :sections="sections"
  />
</template>

<script setup lang="ts">
import GuideArticle, { type GuideSection } from '@/components/GuideArticle.vue'

const flowKeys = [
  'upTasksToClaimGuide.flow1',
  'upTasksToClaimGuide.flow2',
  'upTasksToClaimGuide.flow3',
  'upTasksToClaimGuide.flow4',
]

const sections: GuideSection[] = [
  {
    anchor: 'list',
    titleKey: 'upTasksToClaimGuide.listTitle',
    bodyKey: 'upTasksToClaimGuide.listBody',
  },
  {
    anchor: 'claim',
    titleKey: 'upTasksToClaimGuide.claimTitle',
    bodyKey: 'upTasksToClaimGuide.claimBody',
    samples: [
      { code: 'Claim', hintKey: 'upTasksToClaimGuide.claimSample' },
      { code: 'Claim all', hintKey: 'upTasksToClaimGuide.claimAllSample' },
      { code: 'Unclaim', hintKey: 'upTasksToClaimGuide.unclaimSample' },
      { code: 'Unclaim all', hintKey: 'upTasksToClaimGuide.unclaimAllSample' },
      { code: 'N selected · Claim', hintKey: 'upTasksToClaimGuide.claimSelectedSample' },
      { code: 'N selected · Unclaim', hintKey: 'upTasksToClaimGuide.unclaimSelectedSample' },
      { code: 'Claimed By', hintKey: 'upTasksToClaimGuide.claimedBySample' },
    ],
  },
  {
    anchor: 'auto-claim',
    titleKey: 'upTasksToClaimGuide.autoClaimTitle',
    bodyKey: 'upTasksToClaimGuide.autoClaimBody',
    samples: [
      { code: 'Auto-claim on open', hintKey: 'upTasksToClaimGuide.autoClaimSample' },
    ],
  },
  {
    anchor: 'auto-preview',
    titleKey: 'upTasksToClaimGuide.autoPreviewTitle',
    bodyKey: 'upTasksToClaimGuide.autoPreviewBody',
    samples: [
      { code: 'Auto-preview on open', hintKey: 'upTasksToClaimGuide.autoPreviewSample' },
    ],
  },
  {
    anchor: 'file-preview',
    titleKey: 'upTasksToClaimGuide.filePreviewTitle',
    bodyKey: 'upTasksToClaimGuide.filePreviewBody',
    samples: [
      { code: 'Fit', hintKey: 'upTasksToClaimGuide.filePreviewSample' },
    ],
  },
  {
    anchor: 'detail',
    titleKey: 'upTasksToClaimGuide.detailTitle',
    bodyKey: 'upTasksToClaimGuide.detailBody',
  },
  {
    anchor: 'leader',
    titleKey: 'upTasksToClaimGuide.leaderTitle',
    bodyKey: 'upTasksToClaimGuide.leaderBody',
    samples: [
      { code: 'Force Unclaim', hintKey: 'upTasksToClaimGuide.forceUnclaimSample' },
      { code: 'Reassign', hintKey: 'upTasksToClaimGuide.reassignSample' },
    ],
  },
  {
    anchor: 'apply',
    titleKey: 'upTasksToClaimGuide.applyTitle',
    bodyKey: 'upTasksToClaimGuide.applyBody',
  },
  {
    anchor: 'upgrade',
    titleKey: 'upTasksToClaimGuide.upgradeTitle',
    bodyKey: 'upTasksToClaimGuide.upgradeBody',
  },
  { titleKey: 'upTasksToClaimGuide.failTitle', bodyKey: 'upTasksToClaimGuide.failBody' },
]
</script>
