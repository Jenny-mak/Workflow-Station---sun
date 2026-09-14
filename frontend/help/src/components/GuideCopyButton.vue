<template>
  <button
    type="button"
    class="help-copy-btn"
    :class="{ 'is-copied': copied }"
    :aria-label="copyAria"
    data-testid="help-copy-btn"
    @click="onCopy"
  >
    <svg
      v-if="!copied"
      class="help-copy-icon"
      viewBox="0 0 16 16"
      width="14"
      height="14"
      aria-hidden="true"
    >
      <rect
        x="5.5"
        y="5.5"
        width="8"
        height="8"
        rx="1.2"
        fill="none"
        stroke="currentColor"
        stroke-width="1.4"
      />
      <path
        d="M3.5 10.5H3A1.5 1.5 0 0 1 1.5 9V3A1.5 1.5 0 0 1 3 1.5h6A1.5 1.5 0 0 1 10.5 3v.5"
        fill="none"
        stroke="currentColor"
        stroke-width="1.4"
      />
    </svg>
    <svg
      v-else
      class="help-copy-icon"
      viewBox="0 0 16 16"
      width="14"
      height="14"
      aria-hidden="true"
    >
      <path
        d="M3.2 8.4 6.1 11.2 12.8 4.4"
        fill="none"
        stroke="currentColor"
        stroke-width="1.6"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </svg>
    <span>{{ copied ? t('app.copied') : t('app.copy') }}</span>
  </button>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { copyHelpText } from '@/utils/helpCodeSnippet'

const props = defineProps<{
  text: string
  copyAria: string
}>()

const { t } = useI18n()
const copied = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined

async function onCopy(): Promise<void> {
  const ok = await copyHelpText(props.text)
  if (!ok) return
  copied.value = true
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => {
    copied.value = false
  }, 1600)
}

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})
</script>
