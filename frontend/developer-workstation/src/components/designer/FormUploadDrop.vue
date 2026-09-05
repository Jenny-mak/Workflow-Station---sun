<template>
  <div class="form-upload-drop-wrap">
    <FormUploadDropZone
      :action="resolvedAction"
      :accept="accept || ''"
      :limit="resolvedLimit"
      :multiple="resolvedLimit > 1"
      :disabled="disabled"
      :file-list="liveList"
      :http-request="resolvedRequest"
      :drag-text="t('form.uploadDragText')"
      :click-text="t('form.uploadClickText')"
      :handle-success="onSuccess"
      :handle-change="onLiveChange"
      :handle-remove="onRemove"
      :handle-exceed="onExceed"
    />
    <FormUploadFileDetails
      :files="detailFiles"
      :readonly="disabled"
      :labels="detailLabels"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { UploadRequestOptions, UploadUserFile } from 'element-plus'
import FormUploadDropZone from '@platform-shared/upload/FormUploadDropZone.vue'
import FormUploadFileDetails from '@platform-shared/upload/FormUploadFileDetails.vue'
import {
  resolveUploadMaxFiles,
  splitUploadFileList,
  toElUploadFileList,
} from '@platform-shared/upload/uploadFieldValue'
import { queuedUploadRequest } from '@platform-shared/upload/queuedUploadRequest'

type LiveFile = { url?: string; name?: string; status?: string; response?: unknown }

const props = defineProps<{
  modelValue?: unknown
  action?: string
  accept?: string
  limit?: number
  maxFiles?: number
  multiple?: boolean
  disabled?: boolean
  httpRequest?: (options: UploadRequestOptions) => XMLHttpRequest | Promise<unknown> | void
  onChange?: (_file: unknown, list?: LiveFile[]) => void
  onSuccess?: (res: unknown, file?: LiveFile, list?: LiveFile[]) => void
  onRemove?: (_file: unknown, list?: LiveFile[]) => void
  onExceed?: () => void
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: unknown): void
  (e: 'change', value: unknown): void
}>()

const { t } = useI18n()

const resolvedAction = computed(() =>
  props.action && props.action !== '/' ? props.action : '/api/v1/upload',
)
const resolvedLimit = computed(() => resolveUploadMaxFiles({
  maxFiles: props.maxFiles,
  limit: props.limit,
  multiple: props.multiple,
}))
const fileList = computed((): UploadUserFile[] => {
  if (Array.isArray(props.modelValue) && props.modelValue.length) {
    return props.modelValue as UploadUserFile[]
  }
  return toElUploadFileList(props.modelValue)
})
const liveList = ref<LiveFile[]>([])
watch(fileList, (next) => {
  if (next.some((item) => item.url)) liveList.value = next
}, { immediate: true })
const detailFiles = computed(() => liveList.value
  .filter((item) => item.url)
  .map((item) => ({ url: String(item.url), name: String(item.name || item.url) })))
function resolvedRequest(options: UploadRequestOptions): XMLHttpRequest | Promise<unknown> | void {
  if (typeof props.httpRequest === 'function') return props.httpRequest(options)
  return queuedUploadRequest(options)
}
const detailLabels = computed(() => ({
  description: t('form.fileNet.description'),
  callbackUrl: t('form.fileNet.callbackUrl'),
  status: t('form.fileNet.status'),
  completed: t('form.fileNet.statusCompleted'),
  saveFailed: t('form.fileNet.saveFailed'),
}))

function publishLiveList(list: LiveFile[]) {
  const { stored, display } = splitUploadFileList(list, resolvedLimit.value)
  liveList.value = display
  emit('update:modelValue', stored)
  emit('change', stored)
}

function onLiveChange(_file: unknown, list?: LiveFile[]) {
  if (!list) return
  liveList.value = list
  publishLiveList(list)
  props.onChange?.(_file, list)
}

function onSuccess(res: unknown, file?: LiveFile, list?: LiveFile[]) {
  if (list) {
    liveList.value = list
    publishLiveList(list)
  }
  props.onSuccess?.(res, file, list)
}

function onRemove(_file: unknown, list?: LiveFile[]) {
  liveList.value = list ?? []
  publishLiveList(list ?? [])
  props.onRemove?.(_file, list)
}

function onExceed() {
  props.onExceed?.()
}
</script>
