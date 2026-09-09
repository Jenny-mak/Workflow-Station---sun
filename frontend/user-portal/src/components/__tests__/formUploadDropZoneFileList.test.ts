import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import FormUploadDropZone from '@platform-shared/upload/FormUploadDropZone.vue'
import type { UploadFileListItem } from '@platform-shared/upload/uploadFieldValue'

/**
 * el-upload types its `file-list` as `UploadUserFile[]` — `name` required, `status` one of four
 * literals — while every caller here holds the looser live-list shape. The component bridges the
 * two; these cases pin that bridge, because getting it wrong silently drops rows out of
 * el-upload's own bookkeeping (its limit count and the lists it hands back to on-change).
 */
function mountZone(fileList: UploadFileListItem[]) {
  return mount(FormUploadDropZone, {
    props: {
      action: '/api/v1/upload',
      dragText: 'Drop here',
      clickText: 'browse',
      failLabel: 'Failed',
      removeLabel: 'Remove',
      fileList,
    },
    global: { plugins: [ElementPlus] },
  })
}

describe('FormUploadDropZone file list bridge', () => {
  it('names a row by its url when the row has no name yet', () => {
    const wrapper = mountZone([{ url: 'https://files/inv-9.pdf' }])
    expect(wrapper.text()).toContain('https://files/inv-9.pdf')
  })

  it('renders one card per live row', () => {
    const wrapper = mountZone([
      { name: 'a.pdf', url: 'https://files/a.pdf', status: 'success' },
      { name: 'b.pdf', url: 'https://files/b.pdf', status: 'uploading', percentage: 40 },
    ])
    expect(wrapper.findAll('.form-upload-cards > *')).toHaveLength(2)
    expect(wrapper.text()).toContain('a.pdf')
    expect(wrapper.text()).toContain('b.pdf')
  })

  it('keeps a row whose status is not one el-upload knows', () => {
    // Stored rows can carry any status string; the row must still show rather than be dropped.
    const wrapper = mountZone([{ name: 'legacy.pdf', url: 'https://files/legacy.pdf', status: 'archived' }])
    expect(wrapper.findAll('.form-upload-cards > *')).toHaveLength(1)
    expect(wrapper.text()).toContain('legacy.pdf')
  })

  it('renders with no file list at all', () => {
    const wrapper = mountZone([])
    expect(wrapper.find('[data-testid="form-upload-drop"]').exists()).toBe(true)
    expect(wrapper.findAll('.form-upload-cards > *')).toHaveLength(0)
  })
})
