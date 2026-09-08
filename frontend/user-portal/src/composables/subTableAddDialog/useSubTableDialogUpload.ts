import { onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { isUploadColumn } from '@/components/subTableAddDialogHelpers'
import type { DialogColumn } from '@/components/subTableAddDialogHelpers'
import { extractFileLinks } from '@platform-shared/list/fileNames'
import {
  joinTargetFileNames,
  resolveUploadMaxFileSizeMb,
  resolveUploadMaxFiles,
  splitUploadFileList,
  toElUploadFileList,
} from '@platform-shared/upload/uploadFieldValue'
import { queuedUploadRequest } from '@platform-shared/upload/queuedUploadRequest'
import { isUploadUnauthorizedError } from '@platform-shared/upload/uploadAuthRefresh'
import { clearUploadWidgetState, setUploadWidgetState } from '@platform-shared/upload/uploadSubmitGate'

type DialogT = (key: string, named?: Record<string, unknown>) => string
type UploadListItem = { name: string; url: string; status?: string; response?: unknown }

/**
 * One finished upload as el-upload hands it back.
 *
 * Named rather than written inline because `SubTableAddDialog`'s `:handle-success` has to state
 * this same shape: a bare object type literal in a template attribute is unparseable — vue-tsc
 * reads `{ name?: string }` there as an object *literal* and fails on the `?:`.
 */
export type UploadedFile = { name?: string; url?: string }

export function useSubTableDialogUpload(
  formData: Ref<Record<string, any>>,
  columns: () => DialogColumn[],
  t: DialogT,
) {
  const uploadFileLists = ref<Record<string, UploadListItem[]>>({})

  function maxFilesOf(col: DialogColumn): number {
    return resolveUploadMaxFiles(col.props)
  }

  function maxFileSizeMbOf(col: DialogColumn): number {
    return resolveUploadMaxFileSizeMb(col.props)
  }

  watch(
    uploadFileLists,
    (lists) => {
      for (const [field, list] of Object.entries(lists)) {
        setUploadWidgetState(`dialog-upload:${field}`, list)
      }
    },
    { deep: true },
  )
  onBeforeUnmount(() => {
    for (const field of Object.keys(uploadFileLists.value)) {
      clearUploadWidgetState(`dialog-upload:${field}`)
    }
  })

  function writeLiveList(col: DialogColumn, list: UploadListItem[]) {
    const { stored, display } = splitUploadFileList(list, maxFilesOf(col))
    formData.value[col.field] = stored
    const links = extractFileLinks(stored)
    uploadFileLists.value = { ...uploadFileLists.value, [col.field]: display }
    const target = col.props?.fileNameTargetField
    if (target && columns().some((c) => c.field === target)) {
      formData.value[target] = joinTargetFileNames(links)
    }
  }

  function backfillUploadNames() {
    const next: Record<string, UploadListItem[]> = {}
    for (const col of columns()) {
      if (!isUploadColumn(col, formData.value[col.field])) continue
      next[col.field] = toElUploadFileList(formData.value[col.field])
    }
    uploadFileLists.value = next
  }

  function resetUploadNames() {
    for (const field of Object.keys(uploadFileLists.value)) {
      clearUploadWidgetState(`dialog-upload:${field}`)
    }
    uploadFileLists.value = {}
  }

  function handleUploadSuccess(
    res: unknown,
    file: UploadedFile,
    col: DialogColumn,
    uploadFiles?: UploadListItem[],
  ) {
    const list = uploadFiles ?? [
      ...toElUploadFileList(formData.value[col.field]),
      { name: String(file.name || ''), url: String(file.url || ''), status: 'success', response: res },
    ]
    writeLiveList(col, list)
  }

  function handleUploadRemove(col: DialogColumn, uploadFiles?: UploadListItem[]) {
    writeLiveList(col, uploadFiles ?? [])
  }

  function handleUploadChange(col: DialogColumn, uploadFiles?: UploadListItem[]) {
    if (!uploadFiles) return
    writeLiveList(col, uploadFiles)
  }

  function handleUploadError(col: DialogColumn, error?: unknown) {
    if (isUploadUnauthorizedError(error)) {
      ElMessage.error(t('upload.sessionExpired'))
      return
    }
    ElMessage.error(t('subTable.uploadFailed', { field: col.label }))
  }

  function handleSizeExceed(col: DialogColumn) {
    ElMessage.warning(t('upload.sizeExceed', { size: maxFileSizeMbOf(col) }))
  }

  function handleUploadExceed(col: DialogColumn) {
    ElMessage.warning(t('upload.limitExceed', { limit: maxFilesOf(col) }))
  }

  function handleDuplicate(_col: DialogColumn, name: string) {
    ElMessage.warning(t('upload.duplicate', { name }))
  }

  function clearUpload(col: DialogColumn) {
    writeLiveList(col, [])
  }

  return {
    uploadFileLists,
    httpRequest: queuedUploadRequest,
    maxFilesOf,
    maxFileSizeMbOf,
    isMultiple: (col: DialogColumn) => maxFilesOf(col) > 1,
    backfillUploadNames,
    resetUploadNames,
    handleUploadSuccess,
    handleUploadRemove,
    handleUploadChange,
    handleUploadError,
    handleSizeExceed,
    handleUploadExceed,
    handleDuplicate,
    clearUpload,
  }
}
