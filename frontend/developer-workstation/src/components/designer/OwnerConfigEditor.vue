<template>
  <div class="owner-config-editor">
    <div class="owner-config-editor__title">
      <DesignerHelpLink
        path="/form-events-extend#owner"
        :aria-label="t('form.ownerGuideLinkAria')"
        test-id="owner-guide-link"
      />
    </div>
    <el-select
      :model-value="source"
      class="owner-config-editor__select"
      @change="onSourceChange"
    >
      <el-option :label="t('form.ownerSourceCreator')" value="CREATOR" />
      <el-option :label="t('form.ownerSourceCaseHandler')" value="CASE_HANDLER" />
    </el-select>
    <p class="owner-config-editor__hint">{{ t('form.ownerSourceHint') }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import DesignerHelpLink from './DesignerHelpLink.vue'

/**
 * Property-panel editor for Owner `ownerConfig`.
 * Contract (docs/design/owner-field-component.md §4.1): JSON string
 * `{"source":"CREATOR"|"CASE_HANDLER"}`. Legacy CURRENT_ASSIGNEE maps to CASE_HANDLER.
 */
const props = defineProps<{
  modelValue?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const { t } = useI18n()

const source = computed(() => {
  try {
    const parsed = JSON.parse(props.modelValue || '{}') as { source?: unknown }
    return parsed?.source === 'CASE_HANDLER' || parsed?.source === 'CURRENT_ASSIGNEE'
      ? 'CASE_HANDLER'
      : 'CREATOR'
  } catch {
    return 'CREATOR'
  }
})

function onSourceChange(value: string | number | boolean) {
  const next = value === 'CASE_HANDLER' ? 'CASE_HANDLER' : 'CREATOR'
  emit('update:modelValue', JSON.stringify({ source: next }))
}
</script>

<style scoped>
.owner-config-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.owner-config-editor__title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.owner-config-editor__select {
  width: 100%;
}

.owner-config-editor__hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.4;
  color: var(--el-text-color-secondary);
}
</style>
