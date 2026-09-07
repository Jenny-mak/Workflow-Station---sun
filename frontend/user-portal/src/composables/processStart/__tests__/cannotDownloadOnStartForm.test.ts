import { describe, expect, it } from 'vitest'
import { convertFormCreateRule } from '../useProcessStartRuleConverter'
import { mapSubFormRuleToDialogColumns } from '@/components/subTableAddDialogHelpers/subFormCanvasColumns'
import { mergeListViewFieldColumn } from '@/components/subTableAddDialogHelpers/fileColumns'

const emptyLookup = { lookupDbConfigs: {}, relationViewConfigs: {} }

describe('process start cannotDownload (New Request)', () => {
  it('maps Advanced Upload Meeting Doc to an upload control, not text', () => {
    const field = convertFormCreateRule({
      type: 'advancedUpload',
      field: 'fileupload',
      title: 'Meeting Doc',
      props: { action: '/api/v1/upload', limit: 4, multiple: false },
    })
    expect(field?.type).toBe('upload')
    expect(field?.advancedUpload).toBe(true)
    expect(field?.key).toBe('fileupload')
    expect(field?.uploadLimit).toBe(4)
  })

  it('maps a unique Advanced Upload field key to the drop-zone upload type', () => {
    const field = convertFormCreateRule({
      type: 'advancedUpload',
      field: 'F490mtqzn36qahc',
      title: 'Advanced Upload',
      props: { action: '/api/v1/upload', maxFiles: 10 },
    })
    expect(field?.type).toBe('upload')
    expect(field?.advancedUpload).toBe(true)
    expect(field?.key).toBe('F490mtqzn36qahc')
    expect(field?.type).not.toBe('text')
  })
  it('inherits the switch from other FU forms onto a start-form upload without the prop', () => {
    const field = convertFormCreateRule(
      { type: 'upload', field: 'fileupload', title: 'Meeting Doc', props: {} },
      new Set(['fileupload']),
    )
    expect(field?.cannotDownload).toBe(true)
    expect(field?.advancedUpload).toBe(true)
  })

  it('copies rule-level cannotDownload onto the start-form upload field', () => {
    const field = convertFormCreateRule({
      type: 'upload',
      field: 'fileupload',
      cannotDownload: true,
      props: {},
    })
    expect(field?.cannotDownload).toBe(true)
  })

  it('stamps sub-form upload columns from rule-level cannotDownload', () => {
    const [col] = mapSubFormRuleToDialogColumns(
      [{ type: 'upload', field: 'line_file', title: 'Line File', cannotDownload: true, props: {} }],
      emptyLookup,
    )
    expect(col.props?.cannotDownload).toBe(true)
  })

  it('stamps sub-form upload columns from other-form blocked keys', () => {
    const [col] = mapSubFormRuleToDialogColumns(
      [{ type: 'upload', field: 'line_file', title: 'Line File', props: {} }],
      emptyLookup,
      new Set(['line_file']),
    )
    expect(col.props?.cannotDownload).toBe(true)
  })

  it('stamps list-view upload columns from rule-level cannotDownload', () => {
    const col = mergeListViewFieldColumn(
      { fieldName: 'line_file', comment: 'file', dataType: 'FILE' },
      null,
      { type: 'upload', cannotDownload: true, props: {} },
    )
    expect(col.props?.cannotDownload).toBe(true)
  })
})
