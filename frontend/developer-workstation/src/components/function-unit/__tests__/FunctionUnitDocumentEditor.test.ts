import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import en from '@/i18n/locales/en'

vi.mock('@/api/functionUnitDocument', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/functionUnitDocument')>()
  return {
    ...actual,
    functionUnitDocumentApi: { current: vi.fn(), save: vi.fn() }
  }
})

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  ElMessageBox: { confirm: vi.fn() }
}))

import { ElMessage, ElMessageBox } from 'element-plus'
import { functionUnitDocumentApi } from '@/api/functionUnitDocument'
import FunctionUnitDocumentEditor from '../FunctionUnitDocumentEditor.vue'
import { readAsText } from '@/utils/functionUnitDocumentFile'

const api = functionUnitDocumentApi as unknown as {
  current: ReturnType<typeof vi.fn>
  save: ReturnType<typeof vi.fn>
}
const confirmMock = ElMessageBox.confirm as unknown as ReturnType<typeof vi.fn>

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

const doc = (version: number, content: string) => ({
  documentType: 'REQUIREMENTS', version, majorVersion: 1, minorVersion: version, content,
  summary: 'MANUAL', createdBy: 'alice', createdAt: '2026-09-17T00:00:00Z'
})

const conflict = { response: { status: 409, data: { error: { message: 'changed' } } } }

function mountEditor(readonly = false) {
  return mount(FunctionUnitDocumentEditor, {
    props: { functionUnitId: 7, functionUnitName: 'Leave Request', type: 'REQUIREMENTS', readonly },
    global: {
      plugins: [i18n],
      directives: { loading: {} },
      stubs: {
        'el-input': {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
        },
        'el-button': {
          props: ['disabled'],
          emits: ['click'],
          template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>'
        },
        MarkdownRenderer: true,
        DesignerHelpLink: true,
        FunctionUnitDocumentHistory: true
      }
    }
  })
}

function saveButton(wrapper: ReturnType<typeof mountEditor>) {
  return wrapper.findAll('button').find(b => b.text() === 'Save')!
}

describe('FunctionUnitDocumentEditor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('saves against the version it loaded', async () => {
    api.current.mockResolvedValue({ data: { REQUIREMENTS: doc(2, 'old'), DESIGN: null } })
    api.save.mockResolvedValue({ data: doc(3, 'new') })
    const wrapper = mountEditor()
    await flushPromises()
    expect((wrapper.vm as unknown as { isDirty: boolean }).isDirty).toBe(false)

    await wrapper.find('textarea').setValue('new')
    expect((wrapper.vm as unknown as { isDirty: boolean }).isDirty).toBe(true)
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    expect(api.save).toHaveBeenCalledWith(7, 'REQUIREMENTS', 'new', 2)
    expect(ElMessage.success).toHaveBeenCalledWith('Document saved as v1.3')
    expect(wrapper.text()).toContain('v1.3 · alice')
    expect(wrapper.text()).toContain('Manual edit')
    expect((wrapper.vm as unknown as { isDirty: boolean }).isDirty).toBe(false)
  })

  it('on conflict "Save anyway" re-saves on top of the latest version', async () => {
    api.current
      .mockResolvedValueOnce({ data: { REQUIREMENTS: doc(2, 'old'), DESIGN: null } })
      .mockResolvedValueOnce({ data: { REQUIREMENTS: doc(4, 'theirs'), DESIGN: null } })
    api.save.mockRejectedValueOnce(conflict).mockResolvedValueOnce({ data: doc(5, 'mine') })
    confirmMock.mockRejectedValue('cancel')
    const wrapper = mountEditor()
    await flushPromises()

    await wrapper.find('textarea').setValue('mine')
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    expect(api.save).toHaveBeenNthCalledWith(1, 7, 'REQUIREMENTS', 'mine', 2)
    expect(api.save).toHaveBeenNthCalledWith(2, 7, 'REQUIREMENTS', 'mine', 4)
    expect(ElMessage.error).not.toHaveBeenCalled()
  })

  it('on conflict "Load latest" discards the local edit', async () => {
    api.current
      .mockResolvedValueOnce({ data: { REQUIREMENTS: doc(2, 'old'), DESIGN: null } })
      .mockResolvedValueOnce({ data: { REQUIREMENTS: doc(4, 'theirs'), DESIGN: null } })
    api.save.mockRejectedValueOnce(conflict)
    confirmMock.mockResolvedValue('confirm')
    const wrapper = mountEditor()
    await flushPromises()

    await wrapper.find('textarea').setValue('mine')
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    expect(api.save).toHaveBeenCalledTimes(1)
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('theirs')
  })

  it('read-only members get no editor and no save button', async () => {
    api.current.mockResolvedValue({ data: { REQUIREMENTS: doc(1, 'x'), DESIGN: null } })
    const wrapper = mountEditor(true)
    await flushPromises()

    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.findAll('button').some(b => b.text() === 'Save')).toBe(false)
    expect(wrapper.findAll('button').some(b => b.text() === 'Import')).toBe(false)
    expect(wrapper.findAll('button').some(b => b.text() === 'Download')).toBe(true)
  })

  async function selectFile(wrapper: ReturnType<typeof mountEditor>, file: File) {
    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    // FileReader 在宏任务里回调，flushPromises 等不到
    await new Promise(resolve => setTimeout(resolve, 20))
    await flushPromises()
  }

  it('importing a file loads it as an unsaved draft and saves nothing', async () => {
    api.current.mockResolvedValue({ data: { REQUIREMENTS: doc(2, 'old'), DESIGN: null } })
    const wrapper = mountEditor()
    await flushPromises()

    await selectFile(wrapper, new File(['# Imported\r\n'], 'req.md'))

    expect(confirmMock).not.toHaveBeenCalled()
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('# Imported\n')
    expect((wrapper.vm as unknown as { isDirty: boolean }).isDirty).toBe(true)
    expect(api.save).not.toHaveBeenCalled()
  })

  it('importing over unsaved edits asks first and keeps them when declined', async () => {
    api.current.mockResolvedValue({ data: { REQUIREMENTS: doc(2, 'old'), DESIGN: null } })
    confirmMock.mockRejectedValue('cancel')
    const wrapper = mountEditor()
    await flushPromises()
    await wrapper.find('textarea').setValue('mine')

    await selectFile(wrapper, new File(['# Imported'], 'req.md'))

    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('mine')
  })

  it('rejects an unsupported file without touching the draft', async () => {
    api.current.mockResolvedValue({ data: { REQUIREMENTS: doc(2, 'old'), DESIGN: null } })
    const wrapper = mountEditor()
    await flushPromises()

    await selectFile(wrapper, new File(['PK'], 'req.docx'))

    expect(ElMessage.error).toHaveBeenCalledWith(
      'Only Markdown or plain text files (.md, .markdown, .txt) can be imported')
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('old')
  })

  it('downloads what the editor shows, named after the unit, type and version', async () => {
    api.current.mockResolvedValue({ data: { REQUIREMENTS: doc(2, 'old'), DESIGN: null } })
    const created: Blob[] = []
    URL.createObjectURL = vi.fn((b: Blob) => { created.push(b); return 'blob:x' })
    URL.revokeObjectURL = vi.fn()
    const names: string[] = []
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      names.push(this.download)
    })
    const wrapper = mountEditor()
    await flushPromises()
    await wrapper.find('textarea').setValue('draft text')

    await wrapper.findAll('button').find(b => b.text() === 'Download')!.trigger('click')

    expect(names).toEqual(['Leave_Request-requirements-v1.2.md'])
    expect(await readAsText(created[0])).toBe('draft text')
    click.mockRestore()
  })
})
