<template>
  <span class="help-linked-text">
    <template v-for="(part, index) in parts" :key="index">
      <RouterLink
        v-if="part.kind === 'link'"
        class="help-inline-link"
        :to="part.to"
      >{{ t(part.titleKey) }}</RouterLink>
      <template v-else>{{ part.text }}</template>
    </template>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { parseHelpInlineText } from '@/utils/helpInlineLink'

const props = defineProps<{
  textKey: string
}>()

const { t } = useI18n()

const parts = computed(() => parseHelpInlineText(String(t(props.textKey))))
</script>
