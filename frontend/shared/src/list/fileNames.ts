/**
 * Filename extraction for FILE list cells. Same contract as the Portal grid / CSV
 * and as {@code ListFileNameSql} on the backend.
 */

export interface FileLink {
  url: string
  name: string
}

const UPLOAD_URL_RE = /\/(api\/v\d+\/)?upload\/files\//

export function isUploadUrl(value: string): boolean {
  return UPLOAD_URL_RE.test(value)
}

/** Filename from an upload URL, preferring a recorded original-name query param, else the last segment. */
export function fileDisplayText(value: string): string {
  const qIdx = value.indexOf('?')
  if (qIdx >= 0) {
    const params = new URLSearchParams(value.substring(qIdx + 1))
    const original = params.get('originalName')
      || params.get('fileName')
      || params.get('filename')
      || params.get('name')
    if (original && original.trim() !== '') {
      try {
        return decodeURIComponent(original)
      } catch {
        return original
      }
    }
  }
  const path = value.substring(0, qIdx >= 0 ? qIdx : undefined).split('#')[0]
  const last = path.substring(path.lastIndexOf('/') + 1)
  try {
    return decodeURIComponent(last || value)
  } catch {
    return last || value
  }
}

function fileLinkFrom(value: unknown): FileLink | null {
  if (typeof value === 'string') {
    return isUploadUrl(value) ? { url: value, name: fileDisplayText(value) } : null
  }
  if (value && typeof value === 'object') {
    const obj = value as Record<string, unknown>
    const rawUrl = obj.url ?? obj.fileUrl ?? obj.path ?? obj.downloadUrl
    if (typeof rawUrl === 'string' && rawUrl.trim() !== '') {
      const name = typeof obj.name === 'string' && obj.name.trim() !== ''
        ? obj.name
        : fileDisplayText(rawUrl)
      return { url: rawUrl, name }
    }
  }
  return null
}

/** Extract every downloadable file link from a cell value (handles single, object, and array values). */
export function extractFileLinks(value: unknown): FileLink[] {
  if (value == null) return []
  if (Array.isArray(value)) {
    return value.map(fileLinkFrom).filter((l): l is FileLink => l !== null)
  }
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (trimmed.startsWith('[') || trimmed.startsWith('{')) {
      try {
        return extractFileLinks(JSON.parse(trimmed) as unknown)
      } catch {
        // FALLBACK(ux): truncated FILE(255) JSON still yields complete URLs, never one blob URL.
        return extractUploadUrlsFromText(trimmed)
      }
    }
  }
  const single = fileLinkFrom(value)
  return single ? [single] : []
}

const UPLOAD_URL_TOKEN_RE = /\/(?:api\/v\d+\/)?upload\/files\/[A-Za-z0-9._-]+(?:\?[^"'\s\\]*)?/g

function isCompleteUploadUrl(url: string): boolean {
  const qIdx = url.indexOf('?')
  if (qIdx < 0) return true
  const query = url.substring(qIdx + 1)
  return query.includes('=') && !query.endsWith('=')
}

function extractUploadUrlsFromText(text: string): FileLink[] {
  const out: FileLink[] = []
  const seen = new Set<string>()
  for (const match of text.match(UPLOAD_URL_TOKEN_RE) ?? []) {
    if (!isCompleteUploadUrl(match) || seen.has(match)) continue
    seen.add(match)
    out.push({ url: match, name: fileDisplayText(match) })
  }
  return out
}

export function extractFileNames(value: unknown): string[] {
  return extractFileLinks(value).map((link) => link.name)
}
