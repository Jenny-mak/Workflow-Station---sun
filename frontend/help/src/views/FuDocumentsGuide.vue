<template>
  <GuideArticle
    test-id="fu-documents-guide-page"
    page-title-key="fuDocumentsGuide.pageTitle"
    intro-key="fuDocumentsGuide.intro"
    crumb-key="fuDocumentsGuide.crumb"
    flow-title-key="fuDocumentsGuide.flowTitle"
    :flow-keys="flowKeys"
    :related="related"
    :jump-links="jumpLinks"
    :sections="sections"
  />
</template>

<script setup lang="ts">
import GuideArticle, { type GuideJump, type GuideRelated, type GuideSection } from '@/components/GuideArticle.vue'

const flowKeys = [
  'fuDocumentsGuide.flow1',
  'fuDocumentsGuide.flow2',
  'fuDocumentsGuide.flow3',
  'fuDocumentsGuide.flow4',
  'fuDocumentsGuide.flow5',
]

const related: GuideRelated[] = [{ to: '/ai-studio', titleKey: 'guides.aiStudio.title' }]

const jumpLinks: GuideJump[] = [
  { anchor: 'editor', titleKey: 'fuDocumentsGuide.editorTitle' },
  { anchor: 'import', titleKey: 'fuDocumentsGuide.importTitle' },
  { anchor: 'history', titleKey: 'fuDocumentsGuide.historyTitle' },
  { anchor: 'travel', titleKey: 'fuDocumentsGuide.travelTitle' },
]

const sections: GuideSection[] = [
  {
    titleKey: 'fuDocumentsGuide.whatTitle',
    bodyKey: 'fuDocumentsGuide.whatBody',
    figure: { src: 'guides/dw-fu-documents.png', captionKey: 'fuDocumentsGuide.editorFigure' },
  },
  {
    anchor: 'editor',
    titleKey: 'fuDocumentsGuide.editorTitle',
    bodyKey: 'fuDocumentsGuide.editorBody',
    sampleLayout: 'block',
    samples: [
      { code: 'Edit', hintKey: 'fuDocumentsGuide.catEdit' },
      { code: 'Preview', hintKey: 'fuDocumentsGuide.catPreview' },
      { code: 'Import', hintKey: 'fuDocumentsGuide.catImport' },
      { code: 'Download', hintKey: 'fuDocumentsGuide.catDownload' },
      { code: 'Version History', hintKey: 'fuDocumentsGuide.catHistory' },
      { code: 'Save', hintKey: 'fuDocumentsGuide.catSave' },
      { code: 'Unsaved', hintKey: 'fuDocumentsGuide.catUnsaved' },
      { code: 'v1.2 · designer · 2026-09-20 14:30 · Manual edit', hintKey: 'fuDocumentsGuide.catVersionLine' },
      { code: 'No document yet', hintKey: 'fuDocumentsGuide.catEmpty' },
    ],
  },
  {
    anchor: 'import',
    titleKey: 'fuDocumentsGuide.importTitle',
    bodyKeys: ['fuDocumentsGuide.importBody', 'fuDocumentsGuide.downloadBody'],
    sampleLayout: 'block',
    samples: [
      { code: '.md  .markdown  .txt', hintKey: 'fuDocumentsGuide.catFileTypes' },
      { code: 'Replace', hintKey: 'fuDocumentsGuide.catReplace' },
      { code: 'Purchase_Request-requirements-v1.2.md', hintKey: 'fuDocumentsGuide.catFileName' },
    ],
  },
  {
    anchor: 'history',
    titleKey: 'fuDocumentsGuide.historyTitle',
    bodyKey: 'fuDocumentsGuide.historyBody',
    figure: { src: 'guides/dw-fu-documents-history.png', captionKey: 'fuDocumentsGuide.historyFigure' },
    sampleLayout: 'block',
    samples: [
      { code: 'Current', hintKey: 'fuDocumentsGuide.catCurrent' },
      { code: 'View', hintKey: 'fuDocumentsGuide.catView' },
      { code: 'Compare with current', hintKey: 'fuDocumentsGuide.catCompare' },
      { code: 'Restore', hintKey: 'fuDocumentsGuide.catRestore' },
      { code: 'Manual edit', hintKey: 'fuDocumentsGuide.srcManual' },
      { code: 'Imported', hintKey: 'fuDocumentsGuide.srcImported' },
      { code: 'Cloned', hintKey: 'fuDocumentsGuide.srcCloned' },
      { code: 'Restored from v1.1', hintKey: 'fuDocumentsGuide.srcRestored' },
      { code: 'Rolled back to version 1.0.2', hintKey: 'fuDocumentsGuide.srcRollback' },
      { code: 'AI Studio · Table Design confirmed', hintKey: 'fuDocumentsGuide.srcAiSync' },
      { code: 'AI Studio · full check', hintKey: 'fuDocumentsGuide.srcAiFull' },
      { code: 'AI Studio · one-click generation', hintKey: 'fuDocumentsGuide.srcOneClick' },
    ],
  },
  {
    anchor: 'travel',
    titleKey: 'fuDocumentsGuide.travelTitle',
    bodyKey: 'fuDocumentsGuide.travelBody',
  },
  {
    titleKey: 'fuDocumentsGuide.failTitle',
    failKeys: [
      'fuDocumentsGuide.failType',
      'fuDocumentsGuide.failSize',
      'fuDocumentsGuide.failEmptyFile',
      'fuDocumentsGuide.failConflict',
      'fuDocumentsGuide.failReadOnly',
    ],
  },
]
</script>
