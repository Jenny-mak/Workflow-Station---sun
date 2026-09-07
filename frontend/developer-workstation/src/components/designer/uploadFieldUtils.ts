import {
  extractFileLinks,
} from '@platform-shared/list/fileNames'
import {
  extractStoredUploadUrl,
  persistUploadValue,
  resolveUploadMaxFiles,
} from '@platform-shared/upload/uploadFieldValue'
import { isAnyUploadType } from '@platform-shared/upload/uploadRuleType'

export { extractStoredUploadUrl as extractUploadUrlFromResponse }

export function isStoredFileUrl(value: unknown): value is string {
  if (typeof value !== 'string' || !value.trim()) return false
  return /\/upload\/files\//i.test(value.trim())
}

export function isLikelyFileStorageFieldName(fieldName: string): boolean {
  const f = String(fieldName || '').trim().toLowerCase()
  if (!f) return false
  if (f === 'file_name' || f === 'filename' || f.endsWith('_name')) return false
  return f === 'file' || f === 'fileupload' || f === 'attachment' || f.endsWith('_file')
}

/** Demo file URL for Form Preview list cells (matches backend {@code FileUploadController} shape). */
export function buildMockPreviewFileUrl(originalName = 'sample-document.pdf'): string {
  const safe = String(originalName || 'sample-document.pdf').replace(/[^\w.\-()+ ]/g, '_')
  const stored = `preview-${encodeURIComponent(safe).replace(/%/g, '')}`
  return `/api/v1/upload/files/${stored}?originalName=${encodeURIComponent(safe)}`
}

/** Normalize upload cell values (string URL, el-upload file list, or response object) to a fetchable URL. */
export function resolveUploadCellUrl(value: unknown): string | null {
  if (value == null || value === '') return null
  if (typeof value === 'string') {
    const trimmed = value.trim()
    return trimmed || null
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      const url = resolveUploadCellUrl(item)
      if (url) return url
    }
    return null
  }
  if (typeof value === 'object') {
    const fromResponse = extractUploadUrlFromResponse(value)
    if (fromResponse) return fromResponse
    const o = value as Record<string, unknown>
    const valuePayload = o.value
    if (valuePayload != null && typeof valuePayload === 'object') {
      const nested = resolveUploadCellUrl(valuePayload)
      if (nested) return nested
    }
    const direct = o.url ?? o.fileUrl ?? o.file_url
    if (typeof direct === 'string' && direct.trim()) return direct.trim()
    const response = o.response
    if (response && typeof response === 'object') {
      const nested = resolveUploadCellUrl((response as Record<string, unknown>).data ?? response)
      if (nested) return nested
    }
  }
  return null
}

/** Parse upload API response for the original display filename. */
export function extractUploadNameFromResponse(res: unknown, fallbackFile?: { name?: string }): string {
  if (res != null && typeof res === 'object') {
    const o = res as Record<string, unknown>
    const data = o.data
    if (data != null && typeof data === 'object') {
      const name = (data as Record<string, unknown>).name
      if (typeof name === 'string' && name.trim()) return name.trim()
    }
    const name = o.name
    if (typeof name === 'string' && name.trim()) return name.trim()
  }
  if (fallbackFile?.name && String(fallbackFile.name).trim()) {
    return String(fallbackFile.name).trim()
  }
  return ''
}

export type FormCreateUploadFile = {
  name: string
  url: string
  status: 'success'
  uid?: number
  /** form-create fcUpload emits `file.value || file.url` — must be object to keep display name */
  value?: { url: string; name: string }
}

/** form-create upload v-model: object[] so fcUpload keeps human-readable file.name (not URL basename). */
export function buildFormCreateUploadValue(url: string, displayName?: string): FormCreateUploadFile[] {
  const resolvedUrl = String(url || '').trim()
  if (!resolvedUrl) return []
  const name = (displayName && displayName.trim()) || getFilenameFromUrl(resolvedUrl)
  const payload = { url: resolvedUrl, name }
  return [{ name, url: resolvedUrl, status: 'success', value: payload }]
}

export function buildFormCreateUploadValues(
  value: unknown,
  fallbackName?: string,
): FormCreateUploadFile[] {
  const links = extractFileLinks(value)
  if (links.length === 0) return []
  return links.map((link, index) => {
    const name = index === 0 && fallbackName ? fallbackName : link.name
    return { name, url: link.url, status: 'success' as const, value: { url: link.url, name } }
  })
}

/** Re-apply upload field value after form-create fcUpload strips names to URL strings. */
export function syncFormCreateUploadFieldValue(
  formData: { value: Record<string, unknown> },
  field: string,
  url: string,
  displayName?: string,
): void {
  const next = buildFormCreateUploadValue(url, displayName)
  const current = formData.value[field]
  const currentUrl = resolveUploadCellUrl(current)
  const currentName = Array.isArray(current) && current[0] && typeof current[0] === 'object'
    ? String((current[0] as { name?: string }).name || '').trim()
    : typeof current === 'object' && current != null && !Array.isArray(current)
      ? String((current as { name?: string }).name || '').trim()
      : ''
  if (currentUrl === next[0]?.url && currentName === next[0]?.name) return
  formData.value[field] = next
}

/** Apply display name + value payload on el-upload / form-create file entry. */
export function applyUploadFileDisplayMeta(
  file: { url?: string; name?: string; value?: unknown; response?: unknown },
  url: string,
  displayName: string,
): void {
  file.url = url
  file.name = displayName
  file.value = { url, name: displayName }
}

/** When opening a form-create dialog, turn stored URL strings into upload file-list values. */
export function hydrateUploadFieldsForFormCreate(
  formData: Record<string, unknown>,
  uploadRules: Array<{ field: string; props?: Record<string, unknown> }>,
): void {
  for (const rule of uploadRules) {
    const field = rule.field
    const raw = formData[field]
    const links = extractFileLinks(raw)
    if (links.length === 0) {
      if (raw === '' || raw == null) formData[field] = []
      continue
    }
    const nameTarget = rule.props?.fileNameTargetField as string | undefined
    const savedName =
      nameTarget && formData[nameTarget] != null && String(formData[nameTarget]).trim()
        ? String(formData[nameTarget]).trim()
        : undefined
    formData[field] = buildFormCreateUploadValues(raw, links.length === 1 ? savedName : undefined)
  }
}

/** Copy upload values from form-create rule fields onto list column fields when names differ. */
export function alignUploadFieldsToColumns(
  row: Record<string, unknown>,
  columns: Array<{ field: string; type?: string; props?: Record<string, unknown> }>,
  uploadRuleFields: string[],
): void {
  for (const col of columns) {
    if (!col.field) continue
    if (!isAnyUploadType(col.type) && !isLikelyFileStorageFieldName(col.field)) continue
    if (resolveUploadCellUrl(row[col.field])) continue

    for (const ruleField of uploadRuleFields) {
      const links = extractFileLinks(row[ruleField])
      if (links.length === 0) continue
      const colKey = col.field.toLowerCase()
      const ruleKey = ruleField.toLowerCase()
      if (colKey === ruleKey || colKey.includes(ruleKey) || ruleKey.includes(colKey)) {
        row[col.field] = persistUploadValue(links, resolveUploadMaxFiles(col.props))
        break
      }
    }
  }
}

export function normalizeUploadFieldsInRow(
  row: Record<string, unknown>,
  columns: Array<{ field: string; type?: string; props?: Record<string, unknown> }>,
): void {
  for (const col of columns) {
    if (!col.field) continue
    if (!isAnyUploadType(col.type) && !isLikelyFileStorageFieldName(col.field)) continue
    const links = extractFileLinks(row[col.field])
    row[col.field] = persistUploadValue(links, resolveUploadMaxFiles(col.props))
  }
}

export function isUploadColumn(
  // Accepts any column shape exposing string `type`/`field` (DialogColumn, designer ColumnConfig, …);
  // only these two members are read.
  col: { type?: string; field: string },
  cellValue?: unknown,
): boolean {
  if (isAnyUploadType(col.type)) return true
  if (extractFileLinks(cellValue).length > 0) return true
  if (isStoredFileUrl(cellValue)) return true
  return isLikelyFileStorageFieldName(col.field)
}

export function getFilenameFromUrl(url: string, savedName?: string): string {
  if (savedName) return savedName
  if (!url) return 'unknown file'
  try {
    const parsed = new URL(url, window.location.origin)
    const fromQuery = parsed.searchParams.get('originalName')
      || parsed.searchParams.get('fileName')
      || parsed.searchParams.get('filename')
      || parsed.searchParams.get('name')
    if (fromQuery) return decodeURIComponent(fromQuery)
    const pathPart = parsed.pathname.split('/').pop()
    return pathPart || 'unknown file'
  } catch {
    const queryStr = String(url).includes('?') ? String(url).split('?')[1] : ''
    if (queryStr) {
      const params = new URLSearchParams(queryStr)
      const fromQuery = params.get('originalName') || params.get('fileName')
      if (fromQuery) {
        try {
          return decodeURIComponent(fromQuery)
        } catch {
          return fromQuery
        }
      }
    }
    const [pathPart] = String(url).split('?')
    return pathPart.split('/').pop() || 'unknown file'
  }
}

/** Ensure list/preview columns use type upload when field or sample values indicate FILE storage. */
export function normalizeSubTableColumns<T extends { field: string; type?: string; label?: string; minWidth?: number; props?: Record<string, unknown> }>(
  columns: T[],
  sampleRows?: Array<Record<string, unknown>>,
): T[] {
  const row0 = sampleRows?.[0]
  return columns.map((col) => {
    if (isAnyUploadType(col.type)) return col
    const sample = row0?.[col.field]
    if (!isUploadColumn(col, sample)) return col
    const props = { ...(col.props || {}), action: '/api/v1/upload' }
    return {
      ...col,
      type: 'upload',
      minWidth: col.minWidth && col.minWidth >= 180 ? col.minWidth : 180,
      props,
    }
  })
}

export function resolveFileFetchUrl(url: string): string {
  const trimmed = String(url || '').trim()
  if (!trimmed) return trimmed
  if (/^https?:\/\//i.test(trimmed)) return trimmed
  if (trimmed.startsWith('/')) return `${window.location.origin}${trimmed}`
  return trimmed
}
