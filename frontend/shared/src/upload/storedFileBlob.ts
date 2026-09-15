export type StoredFileFetchResult = 'ok' | 'not-found' | 'failed'

export async function fetchStoredFileBlob(
  url: string,
): Promise<{ ok: true; blob: Blob } | { ok: false; result: Exclude<StoredFileFetchResult, 'ok'> }> {
  try {
    const response = await fetch(url, { credentials: 'include' })
    if (!response.ok) {
      return { ok: false, result: response.status === 404 ? 'not-found' : 'failed' }
    }
    return { ok: true, blob: await response.blob() }
  } catch {
    return { ok: false, result: 'failed' }
  }
}

export function triggerBlobDownload(blob: Blob, filename: string): void {
  const blobUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = blobUrl
  link.download = filename || 'download'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(blobUrl)
}

/** Open a blank tab in the click gesture, then navigate to a blob URL after fetch. */
export async function openStoredFileInNewTab(
  url: string,
): Promise<StoredFileFetchResult | 'blocked'> {
  const tab = window.open('about:blank', '_blank')
  if (!tab) return 'blocked'
  const fetched = await fetchStoredFileBlob(url)
  if (!fetched.ok) {
    tab.close()
    return fetched.result
  }
  const blobUrl = URL.createObjectURL(fetched.blob)
  tab.location.replace(blobUrl)
  window.setTimeout(() => URL.revokeObjectURL(blobUrl), 60_000)
  return 'ok'
}
