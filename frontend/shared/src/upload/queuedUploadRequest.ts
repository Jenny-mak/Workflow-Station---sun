import { MAX_UPLOAD_CONCURRENCY, sharedUploadQueue } from './uploadQueue'
import { refreshUploadAuth, UPLOAD_HTTP_UNAUTHORIZED } from './uploadAuthRefresh'

/**
 * Subset of Element Plus {@code UploadRequestOptions} used by the shared XHR.
 *
 * `data` and `headers` mirror Element Plus exactly rather than the narrower shapes this file
 * used to declare: el-upload really can hand over a `[Blob, filename]` tuple or a `Headers`
 * object, and declaring them away both hid that from {@link buildUploadBody} / {@link applyHeaders}
 * and made {@link queuedUploadRequest} unassignable to el-upload's own `http-request` prop.
 */
export interface QueuedUploadRequestOptions {
  action: string
  method?: string
  data?: Record<string, string | Blob | [Blob, string]>
  filename?: string
  file: File
  headers?: Headers | Record<string, string | number | null | undefined>
  withCredentials?: boolean
  // Method syntax, not property syntax: these are callbacks el-upload supplies, and it declares
  // narrower parameters than this queue can promise to pass (it reports a plain Error where
  // el-upload types an UploadAjaxError). Method syntax is checked bivariantly, which is what a
  // callback contract wants; property syntax would reject el-upload's own options object.
  onSuccess(response: unknown): void
  onError(error: Error): void
  onProgress?(evt: { percent: number }): void
}

/**
 * Custom {@code el-upload} http-request: same one-file POST as the native action,
 * gated to {@link MAX_UPLOAD_CONCURRENCY}, returning the XHR so Element Plus can abort.
 */
export function queuedUploadRequest(options: QueuedUploadRequestOptions): XMLHttpRequest {
  const xhr = new XMLHttpRequest()
  const session = { aborted: false, sent: false, notified: false }
  const nativeAbort = xhr.abort.bind(xhr)
  xhr.abort = () => {
    session.aborted = true
    if (session.sent) nativeAbort()
    else notifyError(options, session, new Error('UPLOAD_ABORTED'))
  }

  void sharedUploadQueue().run(() => runQueuedPost(xhr, options, session))
  return xhr
}

function notifyError(
  options: QueuedUploadRequestOptions,
  session: { notified: boolean },
  error: Error,
): void {
  if (session.notified) return
  session.notified = true
  options.onError(error)
}

async function runQueuedPost(
  xhr: XMLHttpRequest,
  options: QueuedUploadRequestOptions,
  session: { aborted: boolean; sent: boolean; notified: boolean },
): Promise<void> {
  if (session.aborted) return
  session.sent = true
  await postOneFile(xhr, options, session)
}

function postOneFile(
  xhr: XMLHttpRequest,
  options: QueuedUploadRequestOptions,
  session: { aborted: boolean; notified: boolean },
): Promise<void> {
  return new Promise((resolve) => {
    xhr.open((options.method || 'post').toUpperCase(), options.action)
    xhr.withCredentials = options.withCredentials !== false
    applyHeaders(xhr, options.headers)
    xhr.upload.onprogress = (evt) => {
      if (evt.total > 0 && options.onProgress) {
        options.onProgress({ percent: (evt.loaded / evt.total) * 100 })
      }
    }
    xhr.onload = () => {
      if (!session.aborted) finishXhr(xhr, options, session)
      resolve()
    }
    xhr.onerror = () => {
      notifyError(options, session, new Error('UPLOAD_FAILED'))
      resolve()
    }
    xhr.onabort = () => {
      notifyError(options, session, new Error('UPLOAD_ABORTED'))
      resolve()
    }
    xhr.send(buildUploadBody(options))
  })
}

function applyHeaders(xhr: XMLHttpRequest, headers?: QueuedUploadRequestOptions['headers']): void {
  if (!headers) return
  if (headers instanceof Headers) {
    headers.forEach((value, key) => xhr.setRequestHeader(key, value))
    return
  }
  for (const [key, value] of Object.entries(headers)) {
    // A null / undefined header value means "do not send this header" in el-upload.
    if (value === null || value === undefined) continue
    xhr.setRequestHeader(key, String(value))
  }
}

function buildUploadBody(options: QueuedUploadRequestOptions): FormData {
  const body = new FormData()
  body.append(options.filename || 'file', options.file, options.file.name)
  if (options.data) {
    for (const [key, value] of Object.entries(options.data)) {
      // el-upload's `[blob, filename]` form maps onto FormData's three-argument append; without
      // this the tuple stringified into the body as "[object Blob],name".
      if (Array.isArray(value)) body.append(key, value[0], value[1])
      else body.append(key, value)
    }
  }
  return body
}

function finishXhr(
  xhr: XMLHttpRequest,
  options: QueuedUploadRequestOptions,
  session: { notified: boolean },
): void {
  if (xhr.status === 401) {
    void refreshUploadAuth().finally(() => {
      notifyError(options, session, new Error(UPLOAD_HTTP_UNAUTHORIZED))
    })
    return
  }
  if (xhr.status < 200 || xhr.status >= 300) {
    notifyError(options, session, new Error(`UPLOAD_HTTP_${xhr.status}`))
    return
  }
  const raw = xhr.responseText
  try {
    options.onSuccess(raw ? JSON.parse(raw) : {})
  } catch {
    options.onSuccess(raw)
  }
}

export { MAX_UPLOAD_CONCURRENCY }
