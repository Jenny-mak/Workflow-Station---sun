import { describe, expect, it } from 'vitest'
import { extractFileNames } from '../fileNames'

/** Same samples as {@code ListFileNameSqlTest} / design list-file-name-filter §9. */
const URL_UUID_AND_REPORT = '/api/v1/upload/files/abc123?originalName=report.pdf'
const OBJECT_NAMED_CONTRACT = { url: '/api/v1/upload/files/x', name: '合同.pdf' }

describe('extractFileNames', () => {
  it('uses originalName and ignores the uuid path segment', () => {
    expect(extractFileNames(URL_UUID_AND_REPORT)).toEqual(['report.pdf'])
  })

  it('uses the object name when present', () => {
    expect(extractFileNames(OBJECT_NAMED_CONTRACT)).toEqual(['合同.pdf'])
  })

  it('ORs names from a multi-file array', () => {
    expect(extractFileNames([
      '/api/v1/upload/files/a?originalName=a.pdf',
      '/api/v1/upload/files/b?originalName=b.pdf',
    ])).toEqual(['a.pdf', 'b.pdf'])
  })

  it('parses a JSON array stored as a string (Flowable string variable)', () => {
    expect(extractFileNames(JSON.stringify([
      { url: '/api/v1/upload/files/a?originalName=a.pdf', name: 'a.pdf' },
      { url: '/api/v1/upload/files/b?originalName=b.pdf', name: 'b.pdf' },
    ]))).toEqual(['a.pdf', 'b.pdf'])
  })

  it('does not treat a truncated JSON array as a single file URL', () => {
    const truncated = '[{"url":"/api/v1/upload/files/a.png?originalName=a.png","name":"a.png"},{"url":"/api/v1/upload/files/b.webp?orig'
    expect(extractFileNames(truncated)).toEqual(['a.png'])
  })

  it('treats a non-upload string as no file', () => {
    expect(extractFileNames('https://example.com/abc123')).toEqual([])
  })

  it('treats empty cells as no file', () => {
    expect(extractFileNames(null)).toEqual([])
    expect(extractFileNames([])).toEqual([])
    expect(extractFileNames('')).toEqual([])
  })
})
