import { describe, expect, it } from 'vitest'
import {
  DEFAULT_UPLOAD_MAX_FILES,
  DEFAULT_UPLOAD_MAX_FILE_SIZE_MB,
  PLATFORM_UPLOAD_MAX_FILE_SIZE_MB,
  extractStoredUploadUrl,
  fileExceedsUploadSize,
  formatUploadCellText,
  joinTargetFileNames,
  persistFromUploadFileList,
  persistUploadValue,
  resolveUploadMaxFileSizeMb,
  resolveUploadMaxFiles,
  splitUploadFileList,
  uploadValueFingerprint,
  isDuplicateUploadFile,
  rejectUploadFileReason,
} from '../uploadFieldValue'

describe('resolveUploadMaxFiles', () => {
  it('defaults legacy generator limit:1 + multiple:false to 10', () => {
    expect(resolveUploadMaxFiles({ multiple: false, limit: 1 })).toBe(DEFAULT_UPLOAD_MAX_FILES)
    expect(resolveUploadMaxFiles({})).toBe(DEFAULT_UPLOAD_MAX_FILES)
    expect(resolveUploadMaxFiles(null)).toBe(DEFAULT_UPLOAD_MAX_FILES)
  })

  it('honors explicit maxFiles including 1', () => {
    expect(resolveUploadMaxFiles({ maxFiles: 1, multiple: false, limit: 1 })).toBe(1)
    expect(resolveUploadMaxFiles({ maxFiles: 5 })).toBe(5)
  })

  it('honors multiple:true + limit when maxFiles is absent', () => {
    expect(resolveUploadMaxFiles({ multiple: true, limit: 3 })).toBe(3)
  })

  it('honors an explicit designer limit that is not the legacy limit:1 leftover', () => {
    expect(resolveUploadMaxFiles({ multiple: false, limit: 4 })).toBe(4)
    expect(resolveUploadMaxFiles({ limit: 4 })).toBe(4)
  })
})

describe('resolveUploadMaxFileSizeMb', () => {
  it('defaults unconfigured fields to 10MB', () => {
    expect(resolveUploadMaxFileSizeMb({})).toBe(DEFAULT_UPLOAD_MAX_FILE_SIZE_MB)
    expect(resolveUploadMaxFileSizeMb(null)).toBe(DEFAULT_UPLOAD_MAX_FILE_SIZE_MB)
  })

  it('honors an explicit size up to the 50MB platform cap', () => {
    expect(resolveUploadMaxFileSizeMb({ maxFileSizeMb: 20 })).toBe(20)
    expect(resolveUploadMaxFileSizeMb({ maxFileSizeMb: 99 })).toBe(PLATFORM_UPLOAD_MAX_FILE_SIZE_MB)
  })

  it('rejects files over the field cap', () => {
    expect(fileExceedsUploadSize({ size: 10 * 1024 * 1024 }, 10)).toBe(false)
    expect(fileExceedsUploadSize({ size: 10 * 1024 * 1024 + 1 }, 10)).toBe(true)
  })
})

describe('persistUploadValue', () => {
  const files = [
    { url: '/api/v1/upload/files/a.pdf?originalName=a.pdf', name: 'a.pdf' },
    { url: '/api/v1/upload/files/b.pdf?originalName=b.pdf', name: 'b.pdf' },
  ]

  it('writes a URL string when only one file is kept', () => {
    expect(persistUploadValue(files, 1)).toBe(files[0].url)
    expect(persistUploadValue([files[0]], 10)).toBe(files[0].url)
  })

  it('writes JSON when more than one file is stored', () => {
    expect(persistUploadValue(files, 10)).toBe(JSON.stringify(files))
  })
})

describe('persistFromUploadFileList', () => {
  it('keeps every successful file and ignores in-flight ones', () => {
    const stored = persistFromUploadFileList(
      [
        { status: 'success', url: '/api/v1/upload/files/a?originalName=a.pdf', name: 'a.pdf' },
        { status: 'uploading', url: '', name: 'b.pdf' },
        {
          status: 'success',
          response: { data: { url: '/api/v1/upload/files/c?originalName=c.pdf' } },
          name: 'c.pdf',
        },
      ],
      10,
    )
    expect(stored).toBe(JSON.stringify([
      { url: '/api/v1/upload/files/a?originalName=a.pdf', name: 'a.pdf' },
      { url: '/api/v1/upload/files/c?originalName=c.pdf', name: 'c.pdf' },
    ]))
  })
})

describe('splitUploadFileList', () => {
  it('does not drop uploading rows when the first file succeeds', () => {
    const a = { status: 'success' as const, url: '/api/v1/upload/files/a?originalName=a.pdf', name: 'a.pdf', uid: 1 }
    const b = { status: 'uploading' as const, url: '', name: 'b.pdf', uid: 2 }
    const c = { status: 'ready' as const, url: '', name: 'c.pdf', uid: 3 }
    const { stored, display } = splitUploadFileList([a, b, c], 10)
    expect(stored).toBe(a.url)
    expect(display).toEqual([a, b, c])
    expect(display[1]).toBe(b)
  })

  it('keeps the live rows when nothing has succeeded yet', () => {
    const live = [
      { status: 'uploading' as const, name: 'a.pdf', url: '' },
      { status: 'uploading' as const, name: 'b.pdf', url: '' },
    ]
    const { stored, display } = splitUploadFileList(live, 10)
    expect(stored).toBe('')
    expect(display).toEqual(live)
  })

  it('copies the stored URL onto a success row that only has response.data.url', () => {
    const live = {
      status: 'success' as const,
      name: 'c.pdf',
      url: '',
      response: { data: { url: '/api/v1/upload/files/c?originalName=c.pdf' } },
    }
    const { stored, display } = splitUploadFileList([live], 10)
    expect(stored).toBe('/api/v1/upload/files/c?originalName=c.pdf')
    expect(display[0].url).toBe('/api/v1/upload/files/c?originalName=c.pdf')
  })
})

describe('cell helpers', () => {
  it('joins companion names and fingerprints URLs', () => {
    expect(joinTargetFileNames([{ name: 'a.pdf' }, { name: 'b.docx' }])).toBe('a.pdf; b.docx')
    const value = [
      { url: '/api/v1/upload/files/a?originalName=a.pdf', name: 'a.pdf' },
      { url: '/api/v1/upload/files/b?originalName=b.pdf', name: 'b.pdf' },
    ]
    expect(formatUploadCellText(value).text).toBe('a.pdf +1')
    expect(uploadValueFingerprint(value)).toBe(
      '/api/v1/upload/files/a?originalName=a.pdf\0/api/v1/upload/files/b?originalName=b.pdf',
    )
  })

  it('reads nested ApiResponse urls', () => {
    expect(extractStoredUploadUrl({ data: { url: '/api/v1/upload/files/x' } })).toBe(
      '/api/v1/upload/files/x',
    )
  })
})

describe('duplicate upload names', () => {
  const existing = [
    { name: 'Invoice.PDF', url: '/api/v1/upload/files/a?originalName=Invoice.PDF', status: 'success' },
  ]

  it('rejects the same original name case-insensitively', () => {
    expect(isDuplicateUploadFile('invoice.pdf', existing)).toBe(true)
    expect(isDuplicateUploadFile('other.pdf', existing)).toBe(false)
  })

  it('allows retrying a failed row with the same name', () => {
    expect(isDuplicateUploadFile('a.pdf', [{ name: 'a.pdf', status: 'fail' }])).toBe(false)
  })

  it('reads the originalName from a stored URL when name is empty', () => {
    expect(isDuplicateUploadFile('a.pdf', [
      { url: '/api/v1/upload/files/x?originalName=a.pdf', status: 'success' },
    ])).toBe(true)
  })

  it('prefers size over duplicate when the file is too large', () => {
    expect(rejectUploadFileReason(
      { name: 'invoice.pdf', size: 11 * 1024 * 1024 },
      existing,
      10,
    )).toBe('size')
  })

  it('returns duplicate when the name is already present', () => {
    expect(rejectUploadFileReason(
      { name: 'invoice.pdf', size: 1024 },
      existing,
      10,
    )).toBe('duplicate')
  })
})
