import { describe, expect, it } from 'vitest'
import type { ExtractionFieldRule } from '@/api/emailMonitor'
import {
  invalidAttachmentTargets,
  isAttachmentsSource,
  onSourceChange,
  previewAttachmentNames,
  targetOptionsForRow,
  typesForSource,
} from '../emailExtractionFieldMapping'
import type { SubTableFieldOption } from '../useProcessFormSubBindings'

const fileField: SubTableFieldOption = { fieldName: 'quote_files', displayName: 'Quotes', dataType: 'FILE' }
const textField: SubTableFieldOption = { fieldName: 'title', displayName: 'Title', dataType: 'VARCHAR' }

describe('emailExtractionFieldMapping', () => {
  it('locks ATTACHMENTS to DIRECT only', () => {
    expect(isAttachmentsSource('ATTACHMENTS')).toBe(true)
    expect(typesForSource('ATTACHMENTS')).toEqual(['DIRECT'])
    const row: ExtractionFieldRule = { target: '', source: 'ATTACHMENTS', type: 'LABEL' }
    onSourceChange(row)
    expect(row.type).toBe('DIRECT')
  })

  it('lists only FILE targets for ATTACHMENTS', () => {
    const row: ExtractionFieldRule = { target: '', source: 'ATTACHMENTS', type: 'DIRECT' }
    expect(targetOptionsForRow(row, [fileField, textField]).map((f) => f.fieldName)).toEqual(['quote_files'])
  })

  it('rejects ATTACHMENTS mapped to a non-FILE column', () => {
    const fields: ExtractionFieldRule[] = [
      { target: 'title', source: 'ATTACHMENTS', type: 'DIRECT' },
    ]
    expect(invalidAttachmentTargets(fields, [fileField, textField])).toEqual(['title'])
    expect(invalidAttachmentTargets(
      [{ target: 'quote_files', source: 'ATTACHMENTS', type: 'DIRECT' }],
      [fileField, textField],
    )).toEqual([])
  })

  it('previews sample attachment filenames', () => {
    expect(previewAttachmentNames('a.pdf, b.docx')).toBe('a.pdf, b.docx')
    expect(previewAttachmentNames('')).toBe('—')
  })
})
