// ---------------------------------------------------------------------------
// FieldRenderer — upload URL resolution + file list (Task 6.8, Req 24)
// ---------------------------------------------------------------------------
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import type { FieldRendererProps, FieldRendererEmit } from './types'
import { FILE_PREVIEW_PLAYLIST_KEY, openFilePreviewFromList } from '@/composables/filePreview/useFilePreview'
import { isCannotDownload } from '@/utils/filePreview'
import { extractFileLinks } from '@platform-shared/list/fileNames'
import {
  DEFAULT_UPLOAD_MAX_FILES,
  DEFAULT_UPLOAD_MAX_FILE_SIZE_MB,
  isInflightUploadStatus,
  joinTargetFileNames,
  splitUploadFileList,
  toElUploadFileList,
  uploadValueFingerprint,
} from '@platform-shared/upload/uploadFieldValue'
import { queuedUploadRequest } from '@platform-shared/upload/queuedUploadRequest'
import { isUploadUnauthorizedError } from '@platform-shared/upload/uploadAuthRefresh'
import { isAnyUploadType } from '@platform-shared/upload/uploadRuleType'
import { clearUploadWidgetState, setUploadWidgetState } from '@platform-shared/upload/uploadSubmitGate'
import type { UploadDetailFile } from '@platform-shared/upload/FormUploadFileDetails.vue'

const DEFAULT_UPLOAD_URL = '/api/v1/upload'

export function useFieldUpload(props: FieldRendererProps, emit: FieldRendererEmit) {
  const { t } = useI18n()
  const playlist = inject(FILE_PREVIEW_PLAYLIST_KEY, null)
  const widgetId = `field-upload:${props.field.key}`
  const resolvedUploadUrl = computed(() => {
    if (props.uploadUrl) return props.uploadUrl
    if (props.field.uploadUrl && props.field.uploadUrl !== '/') return props.field.uploadUrl
    return DEFAULT_UPLOAD_URL
  })

  const uploadLimit = computed(() => props.field.uploadLimit ?? DEFAULT_UPLOAD_MAX_FILES)
  const uploadMaxFileSizeMb = computed(
    () => props.field.uploadMaxFileSizeMb ?? DEFAULT_UPLOAD_MAX_FILE_SIZE_MB,
  )
  const uploadMultiple = computed(() => uploadLimit.value > 1)
  const fileList = ref<Array<{ name: string; url: string; status?: string; percentage?: number }>>([])
  const detailsOpen = ref(false)
  const detailsFile = ref<UploadDetailFile | null>(null)

  watch(
    () => props.modelValue,
    (val) => {
      if (!isAnyUploadType(props.field.type)) return
      if (fileList.value.some((item) => isInflightUploadStatus(item.status))) return
      const next = toElUploadFileList(val)
      if (uploadValueFingerprint(fileList.value) === uploadValueFingerprint(next)) return
      fileList.value = next
    },
    { immediate: true },
  )

  watch(
    fileList,
    (list) => setUploadWidgetState(widgetId, list),
    { immediate: true, deep: true },
  )
  onBeforeUnmount(() => clearUploadWidgetState(widgetId))

  function persistFromList(list: Array<{ url?: string; name?: string; status?: string; response?: unknown }>) {
    const { stored, display } = splitUploadFileList(list, uploadLimit.value)
    fileList.value = display
    emit('update:modelValue', stored)
    const target = props.field.fileNameTargetField
    if (target && props.formData) {
      props.formData[target] = joinTargetFileNames(extractFileLinks(stored))
    }
  }

  function onUploadChange(
    _file: unknown,
    uploadFiles?: Array<{ url?: string; name?: string; status?: string; response?: unknown }>,
  ) {
    if (!uploadFiles) return
    fileList.value = splitUploadFileList(uploadFiles, uploadLimit.value).display
  }

  function onUploadSuccess(
    response: unknown,
    file: { name?: string; url?: string; uid?: number },
    uploadFiles?: Array<{ url?: string; name?: string; status?: string; response?: unknown }>,
  ) {
    persistFromList(uploadFiles ?? [...fileList.value, {
      url: String(file.url || ''),
      name: String(file.name || ''),
      status: 'success',
      response,
    }])
    emit('upload:success', response, file, props.field.key)
  }

  function onUploadRemove(
    file: unknown,
    uploadFiles?: Array<{ url?: string; name?: string; status?: string; response?: unknown }>,
  ) {
    persistFromList(uploadFiles ?? [])
    emit('upload:remove', file, props.field.key)
  }

  function onUploadExceed() {
    ElMessage.warning(t('upload.limitExceed', { limit: uploadLimit.value }))
  }

  function onUploadError(error: unknown) {
    if (isUploadUnauthorizedError(error)) {
      ElMessage.error(t('upload.sessionExpired'))
      return
    }
    ElMessage.error(t('upload.failed'))
  }

  function onSizeExceed(maxMb: number) {
    ElMessage.warning(t('upload.sizeExceed', { size: maxMb }))
  }

  function previewCurrentFile(file?: { name?: string; url?: string }) {
    const links = extractFileLinks(props.modelValue)
    const url = file?.url || links[0]?.url || ''
    if (!url) return
    const name = file?.name || links.find((l) => l.url === url)?.name || links[0]?.name || url
    openFilePreviewFromList(
      { url, name, cannotDownload: isCannotDownload(props.field.cannotDownload) },
      playlist?.collect() ?? [],
    )
  }

  function openDetails(file: UploadDetailFile): void {
    detailsFile.value = file
    detailsOpen.value = true
  }

  return {
    resolvedUploadUrl,
    uploadLimit,
    uploadMaxFileSizeMb,
    uploadMultiple,
    fileList,
    httpRequest: queuedUploadRequest,
    onUploadSuccess,
    onUploadChange,
    onUploadRemove,
    onUploadExceed,
    onUploadError,
    onSizeExceed,
    previewCurrentFile,
    detailsOpen,
    detailsFile,
    openDetails,
  }
}
