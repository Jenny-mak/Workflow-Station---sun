import { describe, expect, it } from 'vitest'
import {
  appendClonedAdvancedUploadRules,
  cloneAdvancedUploadRuleForOtherCanvas,
  copyStartFormAdvancedUploadAcrossCanvases,
  findTaskSceneProcessForm,
  missingStartFormAdvancedUploadRules,
  pairSubBindingsByTableId,
} from '../copyStartFormAdvancedUpload'

const startRules = [
  { type: 'advancedUpload', field: 'fileupload', title: 'Meeting Doc', props: { limit: 4 } },
  { type: 'advancedUpload', field: 'advancedUpload', title: 'Advanced Upload2', props: { maxFiles: 10 } },
  { type: 'input', field: 'meeting_name', title: 'Meeting Name' },
]

describe('copyStartFormAdvancedUpload', () => {
  it('finds the TASK-scene PROCESS form, not the REQUEST clone', () => {
    const start = findTaskSceneProcessForm([
      { formType: 'PROCESS', scene: 'REQUEST', formName: 'Main (My Request)' },
      { formType: 'PROCESS', scene: 'TASK', formName: 'Main' },
      { formType: 'TASK', scene: 'TASK', formName: 'Assign Task' },
    ])
    expect(start?.formName).toBe('Main')
  })

  it('lists New Request Advanced Upload fields missing from Assign Task / My Request', () => {
    const missing = missingStartFormAdvancedUploadRules(
      startRules,
      [
        { type: 'upload', field: 'fileupload', title: 'Meeting Doc' },
        { type: 'upload', field: 'file', title: 'file' },
      ],
    )
    expect(missing.map(r => r.field)).toEqual(['advancedUpload'])
  })

  it('is empty when the current canvas already has those Advanced Upload fields', () => {
    const missing = missingStartFormAdvancedUploadRules(startRules, startRules)
    expect(missing).toEqual([])
  })

  it('clones with a new designer id but keeps the same field key', () => {
    const cloned = cloneAdvancedUploadRuleForOtherCanvas(
      { type: 'advancedUpload', field: 'advancedUpload', title: 'Advanced Upload2', _fc_id: 'id_old' },
      { readonly: true, idSuffix: 'advancedUpload0' },
    )
    expect(cloned.field).toBe('advancedUpload')
    expect(cloned.title).toBe('Advanced Upload2')
    expect(cloned._fc_id).toBe('id_advancedUpload0')
    expect(cloned.readonly).toBe(true)
    expect((cloned.props as { readonly?: boolean }).readonly).toBe(true)
  })

  it('appends cloned widgets onto the current canvas rule list', () => {
    const current = [{ type: 'upload', field: 'fileupload', title: 'Meeting Doc' }]
    const missing = missingStartFormAdvancedUploadRules(startRules, current)
    const next = appendClonedAdvancedUploadRules(current, missing, true)
    expect(next.map((r: { field?: string }) => r.field)).toEqual(['fileupload', 'advancedUpload'])
  })

  it('pairs Assign Task sub-bindings to New Request by physical tableId', () => {
    const pairs = pairSubBindingsByTableId(
      [
        { id: 10, bindingType: 'PRIMARY', tableId: 1 },
        { id: 103, bindingType: 'SUB', tableId: 50012 },
      ],
      [
        { id: 20, bindingType: 'PRIMARY', tableId: 1 },
        { id: 201, bindingType: 'SUB', tableId: 50012 },
      ],
    )
    expect(pairs).toEqual([{ startBindingId: 103, currentBindingId: 201 }])
  })

  it('copies Advanced Upload onto matching attachment sub-forms and keeps field', () => {
    const result = copyStartFormAdvancedUploadAcrossCanvases({
      startMainRules: startRules,
      startSubForms: {
        '103': [
          { type: 'upload', field: 'file', title: 'file' },
          { type: 'advancedUpload', field: 'Fzwomtqznizdakc', title: 'Docs' },
        ],
      },
      startBindings: [
        { id: 10, bindingType: 'PRIMARY', tableId: 1 },
        { id: 103, bindingType: 'SUB', tableId: 50012 },
      ],
      currentMainRules: [{ type: 'upload', field: 'fileupload', title: 'Meeting Doc' }],
      currentSubForms: {
        '201': [{ type: 'upload', field: 'file', title: 'file' }],
      },
      currentBindings: [
        { id: 20, bindingType: 'PRIMARY', tableId: 1 },
        { id: 201, bindingType: 'SUB', tableId: 50012 },
      ],
      readonly: false,
    })
    expect(result.mainChanged).toBe(true)
    expect(result.changedSubBindingIds).toEqual([201])
    expect(result.addedCount).toBe(2)
    expect((result.subForms['201'] as Array<{ field?: string }>).map(r => r.field))
      .toEqual(['file', 'Fzwomtqznizdakc'])
    expect((result.subForms['201'] as Array<{ field?: string }>).at(-1)?.field).toBe('Fzwomtqznizdakc')
  })

  it('My Request copy is readonly and does not invent a new field key', () => {
    const result = copyStartFormAdvancedUploadAcrossCanvases({
      startMainRules: [{ type: 'advancedUpload', field: 'F490mtqzn36qahc', title: 'Docs' }],
      startSubForms: {},
      startBindings: [{ id: 10, bindingType: 'PRIMARY', tableId: 1 }],
      currentMainRules: [],
      currentSubForms: {},
      currentBindings: [{ id: 20, bindingType: 'PRIMARY', tableId: 1 }],
      readonly: true,
    })
    const cloned = result.mainRules[0] as { field?: string; readonly?: boolean; props?: { readonly?: boolean } }
    expect(cloned.field).toBe('F490mtqzn36qahc')
    expect(cloned.readonly).toBe(true)
    expect(cloned.props?.readonly).toBe(true)
  })
})
