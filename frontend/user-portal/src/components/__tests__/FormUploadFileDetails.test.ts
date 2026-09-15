import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElForm } from 'element-plus'
import FormUploadFileDetails from '@platform-shared/upload/FormUploadFileDetails.vue'
import { updateFileDescription } from '@platform-shared/upload/fileTransferApi'

vi.mock('@platform-shared/upload/fileTransferApi', () => ({
  queryFileTransfers: vi.fn().mockResolvedValue([]),
  updateFileDescription: vi.fn(),
}))

const labels = {
  description: 'File Description',
  callbackUrl: 'Callback URL',
  status: 'Auto Send to FileNet',
  completed: 'Completed',
  save: 'Save',
  saveSuccess: 'saved',
  saveFailed: 'save failed',
  download: 'Download',
  preview: 'Preview',
  downloadFailed: 'download failed',
  fileNotFound: 'not found',
}

const files = [{ url: '/api/v1/upload/files/report.pdf', name: 'report.pdf' }]

const ElInputStub = {
  props: ['modelValue'],
  emits: ['update:modelValue', 'blur'],
  template:
    '<input :value="modelValue" @input="$emit(\'update:modelValue\', ($event.target).value)" @blur="$emit(\'blur\')" />',
}

const ElButtonStub = {
  props: ['disabled', 'loading', 'type', 'size'],
  template: '<button type="button" :disabled="disabled" @click="$emit(\'click\', $event)"><slot /></button>',
}

describe('FormUploadFileDetails', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(updateFileDescription).mockResolvedValue({
      storedName: 'report.pdf',
      fileDescription: 'hello',
    })
  })

  it('shows Preview and Download next to the file name', async () => {
    const wrapper = mount(FormUploadFileDetails, {
      props: { files, labels },
      global: { stubs: { ElInput: ElInputStub, ElButton: ElButtonStub, ElTag: true } },
    })
    await flushPromises()
    expect(wrapper.get('[data-testid="upload-file-preview"]').text()).toContain('Preview')
    expect(wrapper.get('[data-testid="upload-file-download"]').text()).toContain('Download')
    expect(wrapper.text()).toContain('report.pdf')
    wrapper.unmount()
  })

  it('keeps Download and Preview clickable inside a disabled el-form (My Request)', async () => {
    const previewFile = vi.fn()
    const wrapper = mount(
      {
        components: { ElForm, FormUploadFileDetails },
        template: `
          <el-form disabled>
            <FormUploadFileDetails
              :files="files"
              :labels="labels"
              :preview-file="previewFile"
              readonly
            />
          </el-form>
        `,
        setup() {
          return { files, labels, previewFile }
        },
      },
      { global: { plugins: [ElementPlus] } },
    )
    await flushPromises()
    const preview = wrapper.get('[data-testid="upload-file-preview"]')
    const download = wrapper.get('[data-testid="upload-file-download"]')
    expect(preview.classes()).not.toContain('is-disabled')
    expect(download.classes()).not.toContain('is-disabled')
    expect((preview.element as HTMLButtonElement).disabled).toBe(false)
    expect((download.element as HTMLButtonElement).disabled).toBe(false)
    await preview.trigger('click')
    expect(previewFile).toHaveBeenCalledWith({
      url: '/api/v1/upload/files/report.pdf',
      name: 'report.pdf',
    })
    wrapper.unmount()
  })

  it('opens in-app preview from the Preview button', async () => {
    const previewFile = vi.fn()
    const wrapper = mount(FormUploadFileDetails, {
      props: { files, labels, previewFile },
      global: { stubs: { ElInput: ElInputStub, ElButton: ElButtonStub, ElTag: true } },
    })
    await flushPromises()
    await wrapper.get('[data-testid="upload-file-preview"]').trigger('click')
    expect(previewFile).toHaveBeenCalledWith({
      url: '/api/v1/upload/files/report.pdf',
      name: 'report.pdf',
    })
    wrapper.unmount()
  })

  it('hides Download and FileNet fields when Can not download is on', async () => {
    const wrapper = mount(FormUploadFileDetails, {
      props: { files, labels, cannotDownload: true },
      global: { stubs: { ElInput: ElInputStub, ElButton: ElButtonStub, ElTag: true } },
    })
    await flushPromises()
    expect(wrapper.find('[data-testid="upload-file-download"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="upload-file-preview"]').exists()).toBe(true)
    expect(wrapper.find('.upload-file-details__link').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('/api/v1/upload/files/report.pdf')
    expect(wrapper.text()).not.toContain('Callback URL')
    expect(wrapper.text()).not.toContain('Auto Send to FileNet')
    wrapper.unmount()
  })

  it('hides Callback URL and Auto Send to FileNet for now', async () => {
    const wrapper = mount(FormUploadFileDetails, {
      props: { files, labels },
      global: { stubs: { ElInput: ElInputStub, ElButton: ElButtonStub, ElTag: true } },
    })
    await flushPromises()
    expect(wrapper.find('.upload-file-details__link').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Callback URL')
    expect(wrapper.text()).not.toContain('Auto Send to FileNet')
    wrapper.unmount()
  })

  it('saves the file description only when Save is clicked', async () => {
    const wrapper = mount(FormUploadFileDetails, {
      props: { files, labels },
      global: { stubs: { ElInput: ElInputStub, ElButton: ElButtonStub, ElTag: true } },
    })
    await flushPromises()
    const input = wrapper.get('input')
    await input.setValue('hello')
    await input.trigger('blur')
    expect(updateFileDescription).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="upload-file-save"]').trigger('click')
    await flushPromises()
    expect(updateFileDescription).toHaveBeenCalledWith('report.pdf', 'hello')
    wrapper.unmount()
  })
})
