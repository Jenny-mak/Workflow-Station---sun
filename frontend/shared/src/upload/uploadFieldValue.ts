import { extractFileLinks, fileDisplayText, type FileLink } from '../list/fileNames'

/** Default max files when the designer has not set {@code maxFiles}. */
export const DEFAULT_UPLOAD_MAX_FILES = 10

/** Hard cap for the designer number input. */
export const ABSOLUTE_UPLOAD_MAX_FILES = 50

/** Default per-field max size when the designer has not set {@code maxFileSizeMb}. */
export const DEFAULT_UPLOAD_MAX_FILE_SIZE_MB = 10

/** Platform hard cap for a single uploaded file. */
export const PLATFORM_UPLOAD_MAX_FILE_SIZE_MB = 50

export const PLATFORM_UPLOAD_MAX_FILE_SIZE_BYTES =
  PLATFORM_UPLOAD_MAX_FILE_SIZE_MB * 1024 * 1024

export interface StoredUploadFile {
  url: string
  name: string
}

/**
 * Resolve how many files an Upload/FILE field accepts.
 * Old generator wrote {@code multiple:false} + {@code limit:1}; that is not designer intent.
 * Explicit {@code maxFiles} wins. Else honor {@code limit} unless it is that leftover 1.
 */
export function resolveUploadMaxFiles(props?: Record<string, unknown> | null): number {
  const maxFiles = props?.maxFiles
  if (isPositiveInt(maxFiles)) return clampMaxFiles(maxFiles)
  if (isPositiveInt(props?.limit)) {
    if (props?.multiple === false && props.limit === 1) return DEFAULT_UPLOAD_MAX_FILES
    return clampMaxFiles(props.limit)
  }
  return DEFAULT_UPLOAD_MAX_FILES
}

export function isUploadMultiple(props?: Record<string, unknown> | null): boolean {
  return resolveUploadMaxFiles(props) > 1
}

/** Per-field max size in MB. Unconfigured fields stay at 10; designer may raise up to 50. */
export function resolveUploadMaxFileSizeMb(props?: Record<string, unknown> | null): number {
  const raw = props?.maxFileSizeMb
  if (isPositiveInt(raw)) return Math.min(Math.floor(raw), PLATFORM_UPLOAD_MAX_FILE_SIZE_MB)
  return DEFAULT_UPLOAD_MAX_FILE_SIZE_MB
}

export function fileExceedsUploadSize(file: { size: number }, maxFileSizeMb: number): boolean {
  const mb = Math.min(Math.max(1, Math.floor(maxFileSizeMb)), PLATFORM_UPLOAD_MAX_FILE_SIZE_MB)
  return file.size > mb * 1024 * 1024
}

export function normalizeUploadFileName(name: string): string {
  return name.trim().toLowerCase()
}

export function uploadFileDisplayName(file: { name?: string; url?: string }): string {
  if (typeof file.name === 'string' && file.name.trim()) return file.name.trim()
  if (typeof file.url === 'string' && file.url.trim()) return fileDisplayText(file.url)
  return ''
}

/** Same original name already in the list (failed rows may be retried). */
export function isDuplicateUploadFile(
  incomingName: string,
  existing: Array<{ name?: string; url?: string; status?: string }>,
): boolean {
  const key = normalizeUploadFileName(incomingName)
  if (!key) return false
  return existing.some((item) => {
    if (item.status === 'fail') return false
    return normalizeUploadFileName(uploadFileDisplayName(item)) === key
  })
}

export function rejectUploadFileReason(
  file: { name: string; size: number },
  existing: Array<{ name?: string; url?: string; status?: string }>,
  maxFileSizeMb?: number,
): 'size' | 'duplicate' | null {
  if (maxFileSizeMb != null && fileExceedsUploadSize(file, maxFileSizeMb)) return 'size'
  if (isDuplicateUploadFile(file.name, existing)) return 'duplicate'
  return null
}

/**
 * Persist uploaded files as a Flowable-safe string: one URL, or JSON of {url,name}[].
 * Returning a JS array makes Flowable store Java serializable bytes that My Request cannot show.
 */
export function persistUploadValue(
  files: Array<{ url: string; name: string }>,
  maxFiles: number,
): string {
  const trimmed = files
    .filter((f) => typeof f.url === 'string' && f.url.trim())
    .slice(0, clampMaxFiles(maxFiles))
    .map((f) => ({ url: f.url.trim(), name: f.name?.trim() || fileDisplayText(f.url) }))
  if (trimmed.length <= 1) return trimmed[0]?.url ?? ''
  return JSON.stringify(trimmed)
}

export function persistFromUploadFileList(
  fileList: Array<{ url?: string; name?: string; status?: string; response?: unknown }>,
  maxFiles: number,
): string {
  const files: StoredUploadFile[] = []
  for (const item of fileList) {
    if (item.status && item.status !== 'success') continue
    stampStoredUploadUrl(item)
    const url = String(item.url || '').trim()
    if (!url) continue
    const name = (typeof item.name === 'string' && item.name.trim()) || fileDisplayText(url)
    files.push({ url, name })
  }
  return persistUploadValue(files, maxFiles)
}

export function isInflightUploadStatus(status?: string): boolean {
  return Boolean(status && status !== 'success')
}

/**
 * Persist only finished files, but keep the live el-upload rows (including uploading)
 * so the first success cannot wipe the rest of a multi-file batch.
 */
export function splitUploadFileList<T extends {
  url?: string
  name?: string
  status?: string
  response?: unknown
}>(
  liveList: T[],
  maxFiles: number,
): { stored: string; display: T[] } {
  const stored = persistFromUploadFileList(liveList, maxFiles)
  const storedUrls = new Set(extractFileLinks(stored).map((link) => link.url))
  const display = liveList.filter((item) => {
    if (isInflightUploadStatus(item.status)) return true
    stampStoredUploadUrl(item)
    const url = String(item.url || '').trim()
    return Boolean(url && storedUrls.has(url))
  })
  return { stored, display }
}

export function joinTargetFileNames(files: Array<{ name: string }>): string {
  return files.map((f) => f.name).filter(Boolean).join('; ')
}

/** Stable key of stored URLs so hydrate can skip rebuilding an unchanged file list. */
export function uploadValueFingerprint(value: unknown): string {
  return extractFileLinks(value).map((l) => l.url).join('\0')
}

export function formatUploadCellText(value: unknown): { text: string; count: number; links: FileLink[] } {
  const links = extractFileLinks(value)
  if (links.length === 0) return { text: '', count: 0, links }
  if (links.length === 1) return { text: links[0].name, count: 1, links }
  return { text: `${links[0].name} +${links.length - 1}`, count: links.length, links }
}

export function toElUploadFileList(value: unknown): Array<{ name: string; url: string; status: 'success' }> {
  return extractFileLinks(value).map((link) => ({
    name: link.name,
    url: link.url,
    status: 'success' as const,
  }))
}

/** Parse upload API body (ApiResponse wrapper or inner payload) to a stored file URL. */
export function extractStoredUploadUrl(res: unknown): string {
  if (res == null) return ''
  if (typeof res === 'string') return res.trim()
  if (typeof res !== 'object') return ''
  const o = res as Record<string, unknown>
  const direct = o.url ?? o.fileUrl ?? o.file_url ?? o.filePath ?? o.file_path
  if (typeof direct === 'string' && direct.trim()) return direct.trim()
  if (o.data != null && o.data !== o) return extractStoredUploadUrl(o.data)
  if (o.response != null && o.response !== o) return extractStoredUploadUrl(o.response)
  return ''
}

/** Copy response.data.url onto the live el-upload row so cards/preview can open. */
export function stampStoredUploadUrl(item: { url?: string; response?: unknown }): void {
  const url = extractStoredUploadUrl(item.response) || String(item.url || '').trim()
  if (url) item.url = url
}

function isPositiveInt(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 1
}

function clampMaxFiles(value: number): number {
  return Math.min(Math.floor(value), ABSOLUTE_UPLOAD_MAX_FILES)
}
