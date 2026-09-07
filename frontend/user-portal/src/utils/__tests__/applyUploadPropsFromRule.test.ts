import { describe, expect, it } from 'vitest'
import type { FormField } from '@/components/formRendererHelpers'
import {
  applyUploadPropsFromRule,
  collectCannotDownloadFieldKeysFromForms,
  uploadSceneFlagsFromForms,
} from '../applyUploadPropsFromRule'

function field(): FormField {
  return { key: 'fileupload', label: 'Meeting Doc', type: 'upload' }
}

describe('applyUploadPropsFromRule', () => {
  it('defaults upload url and leaves download allowed when the switch is absent', () => {
    const f = field()
    applyUploadPropsFromRule(f, { type: 'upload', props: {} })
    expect(f.uploadUrl).toBe('/api/v1/upload')
    expect(f.cannotDownload).toBeUndefined()
    expect(f.advancedUpload).toBe(false)
    expect(f.uploadLimit).toBeUndefined()
    expect(f.uploadMaxFileSizeMb).toBeUndefined()
  })

  it('keeps stock limit on native Basic upload', () => {
    const f = field()
    applyUploadPropsFromRule(f, { type: 'upload', props: { limit: 1, multiple: false } })
    expect(f.advancedUpload).toBe(false)
    expect(f.uploadLimit).toBe(1)
  })

  it('promotes a text-typed field to upload when the designer rule is Advanced Upload', () => {
    const f: FormField = { key: 'fileupload', label: 'Meeting Doc', type: 'text' }
    applyUploadPropsFromRule(f, { type: 'advancedUpload', props: { action: '/api/v1/upload' } })
    expect(f.type).toBe('upload')
    expect(f.advancedUpload).toBe(true)
  })

  it('treats Advanced Upload without maxFiles as the platform default of 10', () => {
    const f = field()
    applyUploadPropsFromRule(f, { type: 'advancedUpload', props: {} })
    expect(f.advancedUpload).toBe(true)
    expect(f.uploadLimit).toBe(10)
    expect(f.uploadMaxFileSizeMb).toBe(10)
  })

  it('honors explicit maxFiles:1', () => {
    const f = field()
    applyUploadPropsFromRule(f, { type: 'upload', props: { maxFiles: 1 } })
    expect(f.advancedUpload).toBe(true)
    expect(f.uploadLimit).toBe(1)
  })

  it('honors explicit maxFileSizeMb up to 50', () => {
    const f = field()
    applyUploadPropsFromRule(f, { type: 'upload', props: { maxFileSizeMb: 20 } })
    expect(f.advancedUpload).toBe(true)
    expect(f.uploadMaxFileSizeMb).toBe(20)
  })

  it('copies fileNameTargetField', () => {
    const f = field()
    applyUploadPropsFromRule(f, { type: 'upload', props: { fileNameTargetField: 'file_name' } })
    expect(f.fileNameTargetField).toBe('file_name')
  })

  it('sets cannotDownload from rule-level designer switch (itemConfig.rule field)', () => {
    const f = field()
    applyUploadPropsFromRule(f, { type: 'upload', cannotDownload: true, props: {} })
    expect(f.cannotDownload).toBe(true)
  })

  it('sets cannotDownload from form-create native canNotDownload', () => {
    const f = field()
    applyUploadPropsFromRule(f, { type: 'upload', props: { canNotDownload: true } })
    expect(f.cannotDownload).toBe(true)
  })

  it('ignores fileNet props and keeps upload behavior unchanged', () => {
    const f = field()
    applyUploadPropsFromRule(f, {
      type: 'upload',
      props: { fileNet: { enabled: true, headerInfo: [] } },
    })
    expect(f.uploadUrl).toBe('/api/v1/upload')
    expect(f.cannotDownload).toBeUndefined()
    expect(f.advancedUpload).toBe(true)
    expect(f.uploadLimit).toBe(10)
    expect((f as { fileNet?: unknown }).fileNet).toBeUndefined()
  })

  it('inherits cannotDownload from other FU forms for the same field key', () => {
    const f = field()
    const blocked = collectCannotDownloadFieldKeysFromForms([
      {
        data: {
          rule: [{ type: 'upload', field: 'fileupload', props: { cannotDownload: true } }],
        },
      },
    ])
    applyUploadPropsFromRule(f, { type: 'upload', props: {} }, blocked)
    expect(f.cannotDownload).toBe(true)
    expect(f.advancedUpload).toBe(true)
  })

  it('honors designer limit:4 on Advanced Upload when maxFiles is absent', () => {
    const f = field()
    applyUploadPropsFromRule(f, {
      type: 'advancedUpload',
      props: { action: '/api/v1/upload', limit: 4, multiple: false },
    })
    expect(f.advancedUpload).toBe(true)
    expect(f.uploadLimit).toBe(4)
  })

  it('inherits Advanced Upload max files onto a REQUEST-scene native clone', () => {
    const f = field()
    applyUploadPropsFromRule(
      f,
      { type: 'upload', props: { limit: 1, multiple: false, readonly: true } },
      {
        cannotDownload: new Set(['fileupload']),
        maxFiles: new Map([['fileupload', 4]]),
      },
    )
    expect(f.advancedUpload).toBe(true)
    expect(f.cannotDownload).toBe(true)
    expect(f.uploadLimit).toBe(4)
  })
})

describe('collectCannotDownloadFieldKeysFromForms', () => {
  it('reads Meeting Doc from PROCESS Main even when the REQUEST copy has no switch', () => {
    const keys = collectCannotDownloadFieldKeysFromForms([
      {
        data: JSON.stringify({
          rule: [{ type: 'upload', field: 'fileupload', title: 'Meeting Doc', props: { cannotDownload: true, canNotDownload: false } }],
        }),
      },
      {
        data: JSON.stringify({
          rule: [{ type: 'upload', field: 'fileupload', title: 'Meeting Doc', props: {} }],
        }),
      },
    ])
    expect(keys.has('fileupload')).toBe(true)
  })

  it('finds cannotDownload on an upload nested in a card (New Request layout)', () => {
    const keys = collectCannotDownloadFieldKeysFromForms([
      {
        data: {
          rule: [{
            type: 'el-card',
            props: {
              children: [
                { type: 'upload', field: 'meeting_doc', props: { cannotDownload: true } },
              ],
            },
          }],
        },
      },
    ])
    expect(keys.has('meeting_doc')).toBe(true)
  })

  it('finds rule-level cannotDownload on a sub-form upload', () => {
    const keys = collectCannotDownloadFieldKeysFromForms([
      {
        data: {
          rule: [],
          subForms: {
            '12': {
              rule: [{ type: 'upload', field: 'line_file', cannotDownload: true, props: {} }],
            },
          },
        },
      },
    ])
    expect(keys.has('line_file')).toBe(true)
  })
})

describe('uploadSceneFlagsFromForms', () => {
  it('copies Advanced Upload max files from TASK Main onto the scene flags', () => {
    const flags = uploadSceneFlagsFromForms([
      {
        data: {
          rule: [{
            type: 'advancedUpload',
            field: 'fileupload',
            props: { limit: 4, multiple: false, cannotDownload: true },
          }],
        },
      },
    ])
    expect(flags.cannotDownload.has('fileupload')).toBe(true)
    expect(flags.maxFiles.get('fileupload')).toBe(4)
  })
})
