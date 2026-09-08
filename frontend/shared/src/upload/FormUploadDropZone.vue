<template>
  <div
    class="form-upload-box"
    :class="{ 'is-compact': compact, 'is-disabled': disabled }"
    data-testid="form-upload-drop"
  >
    <el-upload
      v-if="!disabled"
      drag
      class="form-upload-drop"
      :action="action"
      :accept="accept || ''"
      :limit="limit"
      :multiple="multiple"
      :disabled="disabled"
      :file-list="fileList"
      :show-file-list="false"
      :http-request="httpRequest"
      :before-upload="beforeUpload"
      :on-success="handleSuccess"
      :on-change="handleChange"
      :on-remove="handleRemove"
      :on-exceed="handleExceed"
      :on-error="handleError"
      list-type="text"
    >
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">
        {{ dragText }}<em>{{ clickText }}</em>
      </div>
      <template
        v-if="tip"
        #tip
      >
        <div class="el-upload__tip">{{ tip }}</div>
      </template>
    </el-upload>
    <div
      v-if="sortedFiles.length"
      class="form-upload-cards"
    >
      <FormUploadFileCard
        v-for="file in sortedFiles"
        :key="cardKey(file)"
        :name="file.name || fileUrl(file) || ''"
        :url="fileUrl(file)"
        :status="file.status"
        :percent="file.percentage"
        :disabled="disabled"
        :fail-label="failLabel"
        :remove-label="removeLabel"
        :success-status-label="successStatusLabel"
        :uploading-status-label="uploadingStatusLabel"
        @open="openDetails(file)"
        @remove="removeFile(file)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { UploadFilled } from '@element-plus/icons-vue'
import type { UploadRequestOptions, UploadUserFile } from 'element-plus'
import FormUploadFileCard from './FormUploadFileCard.vue'
import { extractStoredUploadUrl, rejectUploadFileReason } from './uploadFieldValue'

const props = defineProps<{
  action: string
  dragText: string
  clickText: string
  accept?: string
  limit?: number
  multiple?: boolean
  disabled?: boolean
  compact?: boolean
  tip?: string
  fileList?: UploadUserFile[]
  maxFileSizeMb?: number
  failLabel: string
  removeLabel: string
  successStatusLabel?: string
  uploadingStatusLabel?: string
  httpRequest?: (options: UploadRequestOptions) => XMLHttpRequest | Promise<unknown> | void
  handleSuccess?: (...args: unknown[]) => void
  handleChange?: (...args: unknown[]) => void
  handleRemove?: (...args: unknown[]) => void
  handleExceed?: (...args: unknown[]) => void
  handleError?: (...args: unknown[]) => void
  handleSizeExceed?: (maxMb: number) => void
  handleDuplicate?: (name: string) => void
  handleOpenDetails?: (file: { url: string; name: string }) => void
}>()

const sortedFiles = computed(() => {
  const list = [...(props.fileList || [])]
  return list.sort((a, b) => String(a.name || a.url || '').localeCompare(String(b.name || b.url || '')))
})

function cardKey(file: UploadUserFile): string {
  return String(file.uid ?? file.url ?? file.name)
}

function beforeUpload(file: File): boolean {
  const reason = rejectUploadFileReason(file, props.fileList || [], props.maxFileSizeMb)
  if (reason === 'size') {
    props.handleSizeExceed?.(props.maxFileSizeMb ?? 0)
    return false
  }
  if (reason === 'duplicate') {
    props.handleDuplicate?.(file.name)
    return false
  }
  return true
}

function fileUrl(file: UploadUserFile): string {
  return extractStoredUploadUrl(file.response) || String(file.url || '').trim()
}

function openDetails(file: UploadUserFile): void {
  const url = fileUrl(file)
  if (!url) return
  props.handleOpenDetails?.({ url, name: String(file.name || url) })
}

function removeFile(file: UploadUserFile): void {
  const next = (props.fileList || []).filter((item) => item !== file)
  props.handleRemove?.(file, next)
}
</script>

<style scoped>
.form-upload-box {
  width: 100%;
  border: 1px dashed var(--el-border-color);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
  padding-bottom: 8px;
}
.form-upload-box.is-disabled {
  padding: 8px;
}
.form-upload-drop {
  width: 100%;
}
.form-upload-drop :deep(.el-upload) {
  width: 100%;
}
.form-upload-drop :deep(.el-upload-dragger) {
  width: 100%;
  border: none;
  background: transparent;
  padding: 18px 12px 8px;
}
.form-upload-box.is-compact :deep(.el-upload-dragger) {
  padding: 10px 8px 4px;
}
.form-upload-box.is-compact :deep(.el-icon--upload) {
  font-size: 28px;
  margin-bottom: 4px;
}
.form-upload-cards {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 0 12px 8px;
}
</style>
