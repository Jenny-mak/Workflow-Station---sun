<template>
  <li class="help-sample">
    <GuideCodeBlock
      v-if="isSnippet"
      :code="sample.code"
      :lang="sample.lang"
    />
    <GuideCodeChip
      v-else
      :code="sample.code"
    />
    <span class="help-sample-hint"><GuideLinkedText :text-key="sample.hintKey" /></span>
  </li>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import GuideCodeBlock from '@/components/GuideCodeBlock.vue'
import GuideCodeChip from '@/components/GuideCodeChip.vue'
import { resolveHelpCodeKind, type HelpCodeKind, type HelpCodeLang } from '@/utils/helpCodeSnippet'
import GuideLinkedText from '@/components/GuideLinkedText.vue'

const props = defineProps<{
  sample: {
    code: string
    hintKey: string
    kind?: HelpCodeKind
    lang?: HelpCodeLang
  }
}>()

const isSnippet = computed(
  () => resolveHelpCodeKind(props.sample.code, props.sample.kind) === 'snippet',
)
</script>
