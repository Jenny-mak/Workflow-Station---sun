<template>
  <div class="upload-max-files-editor">
    <div class="umf-input-row">
      <el-input-number
        :model-value="resolved"
        :min="1"
        :max="50"
        controls-position="right"
        style="width: 100%"
        @update:model-value="onChange"
      />
      <DesignerHelpLink
        path="/form-upload#max-files"
        :aria-label="t('form.uploadGuideLinkAria')"
        test-id="upload-max-files-guide-link"
      />
    </div>
    <div class="umf-hint">{{ t('form.uploadMaxFilesHint') }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import DesignerHelpLink from '@/components/designer/DesignerHelpLink.vue'
import { formControlTypeStore } from './formControlTypeStore'
import { DEFAULT_UPLOAD_MAX_FILES, resolveUploadMaxFiles } from '@platform-shared/upload/uploadFieldValue'

const props = defineProps<{ modelValue?: number | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: number] }>()
const { t } = useI18n()

const resolved = computed(() => {
  if (typeof props.modelValue === 'number' && props.modelValue >= 1) return props.modelValue
  return resolveUploadMaxFiles({ maxFiles: props.modelValue ?? undefined })
})

function applyToActiveRule(maxFiles: number): void {
  const rule = formControlTypeStore.activeRule
  if (!rule || rule.type !== 'advancedUpload') return
  const next = (rule.props && typeof rule.props === 'object')
    ? rule.props as Record<string, unknown>
    : {}
  rule.props = next
  next.maxFiles = maxFiles
  next.limit = maxFiles
  next.multiple = maxFiles > 1
}

function onChange(next: number | undefined) {
  const maxFiles = typeof next === 'number' && next >= 1 ? Math.floor(next) : DEFAULT_UPLOAD_MAX_FILES
  emit('update:modelValue', maxFiles)
  applyToActiveRule(maxFiles)
}
</script>

<style scoped>
.upload-max-files-editor {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.umf-input-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.umf-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
</style>
