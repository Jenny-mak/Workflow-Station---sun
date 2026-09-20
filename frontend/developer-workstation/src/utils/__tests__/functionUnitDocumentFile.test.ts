import { describe, it, expect } from 'vitest'
import {
  DOCUMENT_MAX_BYTES,
  DocumentFileRejected,
  documentFileName,
  readDocumentFile
} from '../functionUnitDocumentFile'

const file = (name: string, content: string) => new File([content], name, { type: 'text/markdown' })

describe('readDocumentFile', () => {
  it('reads markdown, stripping the BOM and normalising line endings', async () => {
    await expect(readDocumentFile(file('req.MD', '\uFEFF# Title\r\nbody\r'))).resolves.toBe('# Title\nbody\n')
  })

  it('rejects other file types', async () => {
    await expect(readDocumentFile(file('req.docx', 'x'))).rejects.toMatchObject({ reason: 'UNSUPPORTED_TYPE' })
  })

  it('rejects files above the backend limit instead of truncating', async () => {
    const tooBig = file('req.md', 'a'.repeat(DOCUMENT_MAX_BYTES + 1))
    await expect(readDocumentFile(tooBig)).rejects.toBeInstanceOf(DocumentFileRejected)
    await expect(readDocumentFile(tooBig)).rejects.toMatchObject({ reason: 'TOO_LARGE' })
  })

  it('rejects blank files', async () => {
    await expect(readDocumentFile(file('req.txt', ' \n '))).rejects.toMatchObject({ reason: 'EMPTY' })
  })
})

describe('documentFileName', () => {
  it('combines the function unit name, document type and display version', () => {
    expect(documentFileName('Leave Request / HR', 'REQUIREMENTS', { version: 5, majorVersion: 2, minorVersion: 3 }))
      .toBe('Leave_Request_HR-requirements-v2.3.md')
  })

  it('omits the parts it does not have', () => {
    expect(documentFileName(undefined, 'DESIGN', null)).toBe('design.md')
  })
})
