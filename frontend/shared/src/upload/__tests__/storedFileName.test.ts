import { describe, expect, it } from 'vitest'
import { extractStoredFileName, extractStoredFileNames } from '../storedFileName'
import { normalizeFileNetConfig } from '../fileNetConfig'

describe('extractStoredFileName', () => {
  it('reads the path segment after /upload/files/', () => {
    expect(extractStoredFileName('/api/v1/upload/files/abc.pdf?originalName=x.pdf')).toBe('abc.pdf')
  })

  it('rejects path traversal', () => {
    expect(extractStoredFileName('/api/v1/upload/files/../secret')).toBe('')
  })
})

describe('extractStoredFileNames', () => {
  it('deduplicates stored names', () => {
    expect(extractStoredFileNames([
      '/api/v1/upload/files/a.pdf',
      '/api/v1/upload/files/a.pdf?originalName=1',
    ])).toEqual(['a.pdf'])
  })
})

describe('normalizeFileNetConfig', () => {
  it('defaults enabled to false', () => {
    expect(normalizeFileNetConfig(null).enabled).toBe(false)
    expect(normalizeFileNetConfig({}).enabled).toBe(false)
  })

  it('keeps enabled true when set', () => {
    expect(normalizeFileNetConfig({ enabled: true }).enabled).toBe(true)
  })
})
