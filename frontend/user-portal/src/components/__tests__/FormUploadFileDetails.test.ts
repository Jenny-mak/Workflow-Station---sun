import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import FormUploadFileDetails from '@platform-shared/upload/FormUploadFileDetails.vue'

vi.mock('@platform-shared/upload/fileTransferApi', () => ({
  queryFileTransfers: vi.fn().mockResolvedValue([]),
  updateFileDescription: vi.fn(),
}))

const labels = {
  description: 'File Description',
  callbackUrl: 'Callback URL',
  status: 'Status',
  completed: 'Completed',
  saveFailed: 'save failed',
}

const files = [{ url: '/api/v1/upload/files/report.pdf', name: 'report.pdf' }]

describe('FormUploadFileDetails callback URL click', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('opens the in-app preview on a plain left-click when previewFile is provided', async () => {
    const previewFile = vi.fn()
    const wrapper = mount(FormUploadFileDetails, {
      props: { files, labels, previewFile },
      global: { stubs: { ElInput: true, ElTag: true } },
    })
    await flushPromises()

    const link = wrapper.get('.upload-file-details__link')
    expect(link.attributes('href')).toBe('/api/v1/upload/files/report.pdf')

    await link.trigger('click')
    expect(previewFile).toHaveBeenCalledWith({
      url: '/api/v1/upload/files/report.pdf',
      name: 'report.pdf',
    })
    wrapper.unmount()
  })

  it('does not intercept modifier-clicks so the raw file URL can still open', async () => {
    const previewFile = vi.fn()
    const wrapper = mount(FormUploadFileDetails, {
      props: { files, labels, previewFile },
      global: { stubs: { ElInput: true, ElTag: true } },
    })
    await flushPromises()

    await wrapper.get('.upload-file-details__link').trigger('click', { ctrlKey: true })
    expect(previewFile).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
