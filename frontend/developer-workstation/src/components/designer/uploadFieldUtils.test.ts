import { describe, expect, it } from 'vitest'
import { alignUploadFieldsToColumns } from './uploadFieldUtils'

describe('alignUploadFieldsToColumns', () => {
  it('copies every file when the list column name differs from the rule field', () => {
    const files = [
      { url: '/api/v1/upload/files/a?originalName=a.pdf', name: 'a.pdf' },
      { url: '/api/v1/upload/files/b?originalName=b.pdf', name: 'b.pdf' },
    ]
    const row: Record<string, unknown> = { invoice_file: files }
    alignUploadFieldsToColumns(
      row,
      [{ field: 'invoice', type: 'upload', props: { maxFiles: 10 } }],
      ['invoice_file'],
    )
    expect(row.invoice).toBe(JSON.stringify(files))
  })
})
