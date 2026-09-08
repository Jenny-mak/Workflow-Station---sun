import { describe, expect, it } from 'vitest'
import { unionListViewWithSubFormUploadColumns } from '../unionUploadColumns'

describe('unionListViewWithSubFormUploadColumns', () => {
  it('appends sub-form Advanced Upload fields missing from the list view', () => {
    const list = [
      { field: 'id', type: 'text' },
      { field: 'main_id', type: 'text' },
      { field: 'file', type: 'upload' },
    ]
    const subForm = [
      { field: 'file', type: 'upload' },
      { field: 'Fzwomtqznizdakc', type: 'advancedUpload', label: 'Meeting Doc' },
    ]
    const next = unionListViewWithSubFormUploadColumns(list, subForm)
    expect(next.map(c => c.field)).toEqual(['id', 'main_id', 'file', 'Fzwomtqznizdakc'])
    expect(next[3].type).toBe('upload')
  })

  it('does not duplicate FILE columns already on the list view', () => {
    const list = [{ field: 'file', type: 'upload' }, { field: 'Fzwomtqznizdakc', type: 'upload' }]
    const subForm = [{ field: 'Fzwomtqznizdakc', type: 'advancedUpload' }]
    expect(unionListViewWithSubFormUploadColumns(list, subForm)).toEqual(list)
  })
})
