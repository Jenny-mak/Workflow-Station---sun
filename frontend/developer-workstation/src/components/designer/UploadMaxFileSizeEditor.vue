<template>
  <div class="upload-max-size-editor">
    <div class="ums-input-row">
      <el-input-number
        :model-value="resolved"
        :min="1"
        :max="50"
        controls-position="right"
        style="width: 100%"
        @update:model-value="onChange"
      />
      <DesignerHelpLink
        path="/form-upload#max-file-size"
        :aria-label="t('form.uploadGuideLinkAria')"
        test-id="upload-max-file-size-guide-link"
      />
    </div>
    <div class="ums-hint">{{ t('form.uploadMaxFileSizeHint') }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import DesignerHelpLink from '@/components/designer/DesignerHelpLink.vue'
import { formControlTypeStore } from './formControlTypeStore'
import {
  DEFAULT_UPLOAD_MAX_FILE_SIZE_MB,
  PLATFORM_UPLOAD_MAX_FILE_SIZE_MB,
  resolveUploadMaxFileSizeMb,
} from '@platform-shared/upload/uploadFieldValue'

const props = defineProps<{ modelValue?: number | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: number] }>()
const { t } = useI18n()

const resolved = computed(() => {
  if (typeof props.modelValue === 'number' && props.modelValue >= 1) return props.modelValue
  return resolveUploadMaxFileSizeMb({ maxFileSizeMb: props.modelValue ?? undefined })
})

function applyToActiveRule(maxFileSizeMb: number): void {
  const rule = formControlTypeStore.activeRule
  if (!rule || rule.type !== 'advancedUpload') return
  const next = (rule.props && typeof rule.props === 'object')
    ? rule.props as Record<string, unknown>
    : {}
  rule.props = next
  next.maxFileSizeMb = maxFileSizeMb
}

function onChange(next: number | undefined) {
  const maxFileSizeMb = typeof next === 'number' && next >= 1
    ? Math.min(Math.floor(next), PLATFORM_UPLOAD_MAX_FILE_SIZE_MB)
    : DEFAULT_UPLOAD_MAX_FILE_SIZE_MB
  emit('update:modelValue', maxFileSizeMb)
  applyToActiveRule(maxFileSizeMb)
}
</script>

<style scoped>
.upload-max-size-editor {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ums-input-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.ums-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
</style>
