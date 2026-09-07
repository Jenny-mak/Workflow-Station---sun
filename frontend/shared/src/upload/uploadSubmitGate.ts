import { ElMessage } from 'element-plus'

export type UploadSubmitBlock = 'inflight' | 'failed'

interface WidgetState {
  inflight: boolean
  failed: boolean
}

const widgets = new Map<string, WidgetState>()

export function isInflightUploadItem(status?: string): boolean {
  return status === 'uploading' || status === 'ready'
}

export function isFailedUploadItem(status?: string): boolean {
  return status === 'fail'
}

export function setUploadWidgetState(
  id: string,
  files: Array<{ status?: string }>,
): void {
  widgets.set(id, {
    inflight: files.some((file) => isInflightUploadItem(file.status)),
    failed: files.some((file) => isFailedUploadItem(file.status)),
  })
}

export function clearUploadWidgetState(id: string): void {
  widgets.delete(id)
}

export function getUploadSubmitBlock(): UploadSubmitBlock | null {
  let inflight = false
  let failed = false
  for (const state of widgets.values()) {
    if (state.inflight) inflight = true
    if (state.failed) failed = true
  }
  if (inflight) return 'inflight'
  if (failed) return 'failed'
  return null
}

export function warnIfUploadsBlocking(messages: {
  inflight: string
  failed: string
}): boolean {
  const block = getUploadSubmitBlock()
  if (block === 'inflight') {
    ElMessage.warning(messages.inflight)
    return false
  }
  if (block === 'failed') {
    ElMessage.error(messages.failed)
    return false
  }
  return true
}
