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
    props: { functionUnitId: 7, type: 'REQUIREMENTS', readonly },
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
  })
})
