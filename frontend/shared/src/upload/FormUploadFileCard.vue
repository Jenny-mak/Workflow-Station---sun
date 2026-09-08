<template>
  <button
    type="button"
    class="upload-file-card"
    :class="{
      'is-success': isSuccess,
      'is-uploading': isUploading,
      'is-fail': isFail,
      'is-disabled': disabled,
    }"
    :disabled="disabled && !isSuccess"
    data-testid="upload-file-card"
    @click="onOpen"
  >
    <span
      class="upload-file-card__status"
      :class="{
        'is-success': isSuccess,
        'is-uploading': isUploading,
        'is-fail': isFail,
      }"
      :aria-label="statusAria"
      data-testid="upload-file-status"
      role="status"
    >
      <svg
        v-if="isSuccess"
        viewBox="0 0 12 12"
        width="10"
        height="10"
        aria-hidden="true"
      >
        <path
          d="M2.2 6.2 L4.8 8.8 L9.8 3.2"
          fill="none"
          stroke="#fff"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
    </span>
    <div class="upload-file-card__icon">{{ ext }}</div>
    <div class="upload-file-card__body">
      <div
        class="upload-file-card__name"
        :title="name"
      >
        {{ name }}
      </div>
      <div
        v-if="isUploading"
        class="upload-file-card__meta"
      >
        {{ Math.round(percent) }}%
      </div>
      <div
        v-else-if="isFail"
        class="upload-file-card__meta is-fail"
      >
        {{ failLabel }}
      </div>
    </div>
    <button
      v-if="!disabled && !isUploading"
      type="button"
      class="upload-file-card__remove"
      :aria-label="removeLabel"
      @click.stop="$emit('remove')"
    >
      ×
    </button>
  </button>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    name: string
    url?: string
    status?: string
    percent?: number
    disabled?: boolean
    failLabel: string
    removeLabel: string
    successStatusLabel?: string
    uploadingStatusLabel?: string
  }>(),
  {
    successStatusLabel: 'Uploaded',
    uploadingStatusLabel: 'Uploading',
  },
)

const emit = defineEmits<{
  open: []
  remove: []
}>()

const isSuccess = computed(() => !props.status || props.status === 'success')
const isUploading = computed(() => props.status === 'uploading' || props.status === 'ready')
const isFail = computed(() => props.status === 'fail')
const percent = computed(() => props.percent ?? 0)
const ext = computed(() => {
  const match = props.name.match(/\.([A-Za-z0-9]{1,6})$/)
  return (match?.[1] || 'FILE').toUpperCase()
})
const statusAria = computed(() => {
  if (isUploading.value) return props.uploadingStatusLabel
  if (isFail.value) return props.failLabel
  return props.successStatusLabel
})

function onOpen(): void {
  if (!isSuccess.value || !props.url) return
  emit('open')
}
</script>

<style scoped>
.upload-file-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  width: 148px;
  min-height: 64px;
  padding: 8px 10px;
  border: none;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  text-align: left;
  transition: all 0.3s ease;
}
.upload-file-card:hover:not(.is-disabled):not(.is-uploading):not(.is-fail) {
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15);
  transform: translateY(-4px);
}
.upload-file-card.is-uploading,
.upload-file-card.is-fail {
  cursor: default;
}
.upload-file-card.is-disabled.is-success {
  cursor: pointer;
}
.upload-file-card.is-fail {
  opacity: 0.85;
}
.upload-file-card__status {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
  box-shadow: 0 0 0 2px #fff;
}
.upload-file-card__status.is-success {
  background: var(--el-color-success, #67c23a);
}
.upload-file-card__status.is-uploading {
  background: var(--el-color-warning, #e6a23c);
}
.upload-file-card__status.is-fail {
  background: var(--el-color-danger, #f56c6c);
}
.upload-file-card__icon {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: #303133;
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
}
.upload-file-card__body {
  min-width: 0;
  flex: 1;
}
.upload-file-card__name {
  font-size: 12px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.upload-file-card__meta {
  margin-top: 2px;
  font-size: 11px;
  color: #909399;
}
.upload-file-card__meta.is-fail {
  color: var(--el-color-danger);
}
.upload-file-card__remove {
  position: absolute;
  top: 2px;
  right: 22px;
  border: none;
  background: transparent;
  color: #909399;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  padding: 2px;
}
</style>
