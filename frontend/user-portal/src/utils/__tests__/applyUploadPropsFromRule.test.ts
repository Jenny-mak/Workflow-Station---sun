import { describe, expect, it } from 'vitest'
import type { FormField } from '@/components/formRendererHelpers'
import {
  applyUploadPropsFromRule,
  collectCannotDownloadFieldKeysFromForms,
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
    expect(f.uploadLimit).toBe(10)
  })

  it('treats legacy limit:1 + multiple:false as 10 files', () => {
    const f = field()
    applyUploadPropsFromRule(f, { type: 'upload', props: { limit: 1, multiple: false } })
    expect(f.uploadLimit).toBe(10)
  })

  it('honors explicit maxFiles:1', () => {
    const f = field()
    applyUploadPropsFromRule(f, { type: 'upload', props: { maxFiles: 1 } })
    expect(f.uploadLimit).toBe(1)
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
