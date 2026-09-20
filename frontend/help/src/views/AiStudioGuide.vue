<template>
  <GuideArticle
    test-id="ai-studio-guide-page"
    page-title-key="aiStudioGuide.pageTitle"
    intro-key="aiStudioGuide.intro"
    crumb-key="aiStudioGuide.crumb"
    flow-title-key="aiStudioGuide.flowTitle"
    :flow-keys="flowKeys"
    :related="related"
    :jump-links="jumpLinks"
    :sections="sections"
  />
</template>

<script setup lang="ts">
import GuideArticle, { type GuideJump, type GuideRelated, type GuideSection } from '@/components/GuideArticle.vue'

const flowKeys = [
  'aiStudioGuide.flow1',
  'aiStudioGuide.flow2',
  'aiStudioGuide.flow3',
  'aiStudioGuide.flow4',
  'aiStudioGuide.flow5',
  'aiStudioGuide.flow6',
]

const related: GuideRelated[] = [
  { to: '/fu-documents', titleKey: 'guides.fuDocuments.title' },
  { to: '/table-design', titleKey: 'guides.tableDesign.title' },
  { to: '/view-design', titleKey: 'guides.viewDesign.title' },
  { to: '/email-send', titleKey: 'guides.emailSend.title' },
]

const jumpLinks: GuideJump[] = [
  { anchor: 'modes', titleKey: 'aiStudioGuide.modesTitle' },
  { anchor: 'one-click', titleKey: 'aiStudioGuide.oneClickTitle' },
  { anchor: 'result', titleKey: 'aiStudioGuide.resultTitle' },
]

const sections: GuideSection[] = [
  {
    titleKey: 'aiStudioGuide.whatTitle',
    bodyKey: 'aiStudioGuide.whatBody',
    figure: { src: 'guides/dw-ai-studio-entry.png', captionKey: 'aiStudioGuide.entryFigure' },
  },
  {
    anchor: 'modes',
    titleKey: 'aiStudioGuide.modesTitle',
    bodyKey: 'aiStudioGuide.modesBody',
    sampleLayout: 'block',
    samples: [
      { code: 'Start a new AI design', hintKey: 'aiStudioGuide.catNew' },
      { code: 'Continue AI draft', hintKey: 'aiStudioGuide.catContinue' },
      { code: 'One-click generate', hintKey: 'aiStudioGuide.catOneClick' },
      { code: 'Open AI Studio', hintKey: 'aiStudioGuide.catOpen' },
      { code: 'Cancel', hintKey: 'aiStudioGuide.catCancel' },
    ],
  },
  {
    anchor: 'one-click',
    titleKey: 'aiStudioGuide.oneClickTitle',
    bodyKeys: ['aiStudioGuide.oneClickBody', 'aiStudioGuide.oneClickScope'],
    figure: { src: 'guides/dw-ai-studio-one-click.png', captionKey: 'aiStudioGuide.oneClickFigure' },
    sampleLayout: 'block',
    samples: [
      { code: 'What should this function unit do?', hintKey: 'aiStudioGuide.catInput' },
      { code: 'Generate', hintKey: 'aiStudioGuide.catGenerate' },
      { code: 'Replace the current design?', hintKey: 'aiStudioGuide.catConfirm' },
    ],
  },
  {
    anchor: 'result',
    titleKey: 'aiStudioGuide.resultTitle',
    bodyKeys: ['aiStudioGuide.resultBody', 'aiStudioGuide.resultDocs'],
    figure: { src: 'guides/dw-ai-studio-result.png', captionKey: 'aiStudioGuide.resultFigure' },
    sampleLayout: 'block',
    samples: [
      { code: 'Stop', hintKey: 'aiStudioGuide.catStop' },
      { code: 'Proposed change', hintKey: 'aiStudioGuide.catCard' },
      { code: 'Pre-check', hintKey: 'aiStudioGuide.catPreCheck' },
      { code: 'Applied', hintKey: 'aiStudioGuide.catApplied' },
      { code: 'Apply', hintKey: 'aiStudioGuide.catApply' },
      { code: 'Let AI fix it', hintKey: 'aiStudioGuide.catFix' },
      { code: 'Confirm phase', hintKey: 'aiStudioGuide.catConfirmPhase' },
    ],
  },
  {
    titleKey: 'aiStudioGuide.failTitle',
    failKeys: [
      'aiStudioGuide.failNoInput',
      'aiStudioGuide.failCredentials',
      'aiStudioGuide.failErrors',
      'aiStudioGuide.failAutoApply',
      'aiStudioGuide.failGeneration',
      'aiStudioGuide.failUndo',
      'aiStudioGuide.failNotGenerated',
    ],
  },
]
</script>
