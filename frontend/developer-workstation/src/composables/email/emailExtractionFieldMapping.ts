import type { ExtractionFieldRule } from '@/api/emailMonitor'
import type { SubTableFieldOption } from './useProcessFormSubBindings'

export const ATTRIBUTE_SOURCES = [
  'SUBJECT', 'FROM', 'TO', 'CC', 'REPLY_TO', 'DATE', 'MESSAGE_ID',
] as const

export const BODY_SOURCES = ['TEXT_AND_HTML', 'TEXT', 'HTML', 'HEADER', 'CONST'] as const

export const ATTACHMENT_SOURCES = ['ATTACHMENTS'] as const

export const LOCKED_ATTRIBUTE_SOURCES = ['FROM', 'TO', 'CC', 'REPLY_TO', 'DATE', 'MESSAGE_ID'] as const

const BODY_TYPES = ['LABEL', 'BETWEEN', 'REGEX', 'CONST', 'HEADER'] as const
const SUBJECT_TYPES = ['DIRECT', 'LABEL', 'BETWEEN', 'REGEX'] as const

export function isAttachmentsSource(source?: string): boolean {
  return source === 'ATTACHMENTS'
}

export function isFileField(option: SubTableFieldOption): boolean {
  return String(option.dataType || '').toUpperCase() === 'FILE'
}

export function isLockedAttributeSource(source?: string): boolean {
  return LOCKED_ATTRIBUTE_SOURCES.includes(source as typeof LOCKED_ATTRIBUTE_SOURCES[number])
    || isAttachmentsSource(source)
}

export function typesForSource(source?: string): string[] {
  if (isAttachmentsSource(source)) {
    return ['DIRECT']
  }
  if (source === 'SUBJECT') {
    return [...SUBJECT_TYPES]
  }
  return [...BODY_TYPES]
}

export function onSourceChange(row: ExtractionFieldRule): void {
  if (isLockedAttributeSource(row.source)) {
    row.type = 'DIRECT'
    return
  }
  if (row.source === 'SUBJECT' && !SUBJECT_TYPES.includes(row.type as typeof SUBJECT_TYPES[number])) {
    row.type = 'DIRECT'
    return
  }
  if (row.type === 'DIRECT') {
    row.type = 'LABEL'
  }
}

export function targetOptionsForRow(
  row: ExtractionFieldRule,
  mainFieldOptions: SubTableFieldOption[],
): SubTableFieldOption[] {
  const base = isAttachmentsSource(row.source)
    ? mainFieldOptions.filter(isFileField)
    : mainFieldOptions
  const trimmed = row.target?.trim()
  if (trimmed && !base.some((f) => f.fieldName === trimmed)) {
    return [{ fieldName: trimmed, displayName: trimmed }, ...base]
  }
  return base
}

export function invalidAttachmentTargets(
  fields: ExtractionFieldRule[] | undefined,
  mainFieldOptions: SubTableFieldOption[],
): string[] {
  if (!fields?.length) {
    return []
  }
  const fileNames = new Set(
    mainFieldOptions.filter(isFileField).map((f) => f.fieldName),
  )
  const invalid: string[] = []
  for (const row of fields) {
    if (!isAttachmentsSource(row.source)) {
      continue
    }
    const target = row.target?.trim()
    if (target && !fileNames.has(target)) {
      invalid.push(target)
    }
  }
  return invalid
}

export function previewAttachmentNames(sampleNames?: string): string {
  const names = (sampleNames ?? '')
    .split(/[,;\n]+/)
    .map((n) => n.trim())
    .filter(Boolean)
  return names.length ? names.join(', ') : '—'
}
