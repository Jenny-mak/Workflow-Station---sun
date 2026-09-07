import { describe, expect, it } from 'vitest'
import { injectPreviewUploadHandlers } from '@/utils/formDesigner'

describe('injectPreviewUploadHandlers', () => {
  it('leaves native Basic upload on form-create Upload', () => {
    const formData = { value: {} as Record<string, unknown> }
    const rules = [{
      type: 'upload',
      field: 'fileupload',
      props: { multiple: false, limit: 1 },
    }]
    injectPreviewUploadHandlers(rules, formData)
    expect(rules[0].type).toBe('upload')
    expect(rules[0].props.onChange).toBeUndefined()
  })

  it('turns Advanced Upload Preview into a multi-file drop zone', () => {
    const formData = { value: {} as Record<string, unknown> }
    const rules = [{
      type: 'advancedUpload',
      field: 'fileupload',
      props: { multiple: false, limit: 1 },
    }]
    injectPreviewUploadHandlers(rules, formData)
    expect(rules[0].type).toBe('formUploadDrop')
    expect(rules[0].props.multiple).toBe(true)
    expect(rules[0].props.limit).toBe(10)
    expect(rules[0].props.maxFileSizeMb).toBe(10)
  })

  it('keeps single-file Preview when maxFiles is 1', () => {
    const formData = { value: {} as Record<string, unknown> }
    const rules = [{
      type: 'upload',
      field: 'cover',
      props: { maxFiles: 1, maxFileSizeMb: 20 },
    }]
    injectPreviewUploadHandlers(rules, formData)
    expect(rules[0].type).toBe('formUploadDrop')
    expect(rules[0].props.multiple).toBe(false)
    expect(rules[0].props.limit).toBe(1)
    expect(rules[0].props.maxFileSizeMb).toBe(20)
  })

  it('does not clear in-flight files when the first upload succeeds', () => {
    const formData = { value: {} as Record<string, unknown> }
    const rules = [{ type: 'advancedUpload', field: 'fileupload', props: {} }]
    injectPreviewUploadHandlers(rules, formData)
    const a = { status: 'success', url: '/api/v1/upload/files/a?originalName=a.pdf', name: 'a.pdf' }
    const b = { status: 'uploading', url: '', name: 'b.pdf' }
    rules[0].props.onChange(undefined, [a, b])
    expect(formData.value.fileupload).toEqual([a, b])
  })
})
