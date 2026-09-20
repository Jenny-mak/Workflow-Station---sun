import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { flushPromises } from '@vue/test-utils'
import AiStudioEntryDialog from '@/components/ai/AiStudioEntryDialog.vue'
import { aiStudioDraftStorageKey } from '@/utils/aiStudioDraft'
import { aiStudioThreadApi } from '@/api/aiStudioThread'
import { aiGenerationApi } from '@/api/aiGeneration'
import { functionUnitDocumentApi } from '@/api/functionUnitDocument'
import { aiStudioPendingProposalStorageKey } from '@/utils/aiStudioDraft'
import { ElMessage, ElMessageBox } from 'element-plus'

vi.mock('@/api/aiStudioThread', () => ({
  aiStudioThreadApi: { getState: vi.fn() }
}))
const getState = vi.mocked(aiStudioThreadApi.getState)

vi.mock('@/api/aiGeneration', () => ({
  aiGenerationApi: { studioStartOneClick: vi.fn() }
}))
vi.mock('@/api/functionUnitDocument', () => ({
  functionUnitDocumentApi: { current: vi.fn() }
}))
vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn() },
  ElMessageBox: { confirm: vi.fn() }
}))
const startOneClick = vi.mocked(aiGenerationApi.studioStartOneClick)
const currentDocuments = vi.mocked(functionUnitDocumentApi.current)
const confirm = vi.mocked(ElMessageBox.confirm)

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      common: { cancel: 'Cancel' },
      functionUnit: {
        process: 'Process Design',
        tables: 'Table Design',
        forms: 'Form Design',
        viewDesign: 'View Design',
        actionDesign: 'Action Design',
        automation: 'Automation',
        decisions: 'Decision Design'
      },
      connection: { title: 'Connections' },
      emailTemplate: { title: 'Email Templates' },
      emailMonitor: { title: 'Email Monitors' },
      ai: {
        studio: {
          title: 'Build with AI',
          guideLinkAria: 'Open the Build with AI guideline',
          subtitle: 'Design your Function Unit step by step with AI, while staying in control.',
          newDesign: 'Start a new AI design',
          newDesignDesc: 'Describe your idea and build each phase together.',
          recommended: 'Recommended',
          continueDraft: 'Continue AI draft',
          continueDraftDesc: 'Resume {name} · {phase}',
          noDraft: 'No AI draft to resume for this Function Unit yet.',
          guideTitle: 'AI Studio guides you through',
          step: {
            validation: 'Validation'
          },
          overwriteNote: 'Your existing design will not be overwritten without confirmation.',
          openButton: 'Open AI Studio',
          oneClick: {
            title: 'One-click generate',
            desc: 'Build the whole function unit in one go.',
            readOnly: 'Read-only access.',
            button: 'Generate'
          }
        }
      }
    }
  }
})

// el-dialog 走 teleport，桩掉后直接渲染三个插槽，方便断言内容
const ElDialogStub = {
  props: ['modelValue'],
  template: '<div><slot name="header" /><slot /><slot name="footer" /></div>'
}

const FU_ID = 42

function mountDialog(extraProps: Record<string, unknown> = {}) {
  return mount(AiStudioEntryDialog, {
    props: { visible: true, functionUnitId: FU_ID, ...extraProps },
    global: {
      plugins: [i18n],
      stubs: {
        'el-dialog': ElDialogStub,
        DesignerHelpLink: true,
        // 不手动 $emit('click')：@click 会经属性透传落在原生 button 上，再 $emit 会双发
        'el-button': { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
        'el-input': {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
        },
        'el-icon': { template: '<i><slot /></i>' }
      }
    }
  })
}

describe('AiStudioEntryDialog', () => {
  beforeEach(() => {
    localStorage.clear()
    getState.mockReset()
    // 默认：后端不可用 → 维持纯本地判断
    getState.mockRejectedValue(new Error('offline'))
    startOneClick.mockReset()
    confirm.mockReset()
    vi.mocked(ElMessage.error).mockReset()
    currentDocuments.mockReset()
    currentDocuments.mockResolvedValue({ data: { REQUIREMENTS: null, DESIGN: null } })
  })

  async function chooseGenerate(wrapper: ReturnType<typeof mountDialog>) {
    await wrapper.findAll('.mode-card')[2].trigger('click')
    return wrapper.findAll('button')[1]
  }

  it('one-click: needs a description, confirms the replacement, submits the job and hands over to the workspace', async () => {
    confirm.mockResolvedValue('confirm' as never)
    startOneClick.mockResolvedValue({ data: { jobId: 'job-1' } } as never)
    const wrapper = mountDialog({ canGenerate: true })
    await flushPromises()
    const button = await chooseGenerate(wrapper)

    // 选了一键生成：引导步骤让位给输入框，没有描述也没有 Requirements 文档时不能提交
    expect(wrapper.findAll('.guide-step')).toHaveLength(0)
    expect(button.text()).toBe('Generate')
    expect(button.attributes('disabled')).toBeDefined()

    await wrapper.find('textarea').setValue('  leave request with manager approval ')
    expect(button.attributes('disabled')).toBeUndefined()
    await button.trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(startOneClick).toHaveBeenCalledWith({ functionUnitId: FU_ID, requirements: 'leave request with manager approval' })
    expect(JSON.parse(localStorage.getItem(aiStudioPendingProposalStorageKey(FU_ID))!))
      .toMatchObject({ jobId: 'job-1', phase: 'PROCESS_DESIGN' })
    expect(wrapper.emitted('open')).toEqual([[{ mode: 'generate', draft: null }]])
    expect(wrapper.emitted('update:visible')).toEqual([[false]])
  })

  it('one-click: declining the confirmation submits nothing', async () => {
    confirm.mockRejectedValue('cancel')
    const wrapper = mountDialog({ canGenerate: true })
    await flushPromises()
    const button = await chooseGenerate(wrapper)
    await wrapper.find('textarea').setValue('leave request')
    await button.trigger('click')
    await flushPromises()

    expect(startOneClick).not.toHaveBeenCalled()
    expect(wrapper.emitted('open')).toBeUndefined()
  })

  it('one-click: an existing Requirements document makes the description optional', async () => {
    currentDocuments.mockResolvedValue({ data: { REQUIREMENTS: { content: '# Req' }, DESIGN: null } } as never)
    const wrapper = mountDialog({ canGenerate: true })
    await flushPromises()
    const button = await chooseGenerate(wrapper)

    expect(button.attributes('disabled')).toBeUndefined()
  })

  it('one-click: a rejected submission stays in the dialog and shows the reason', async () => {
    confirm.mockResolvedValue('confirm' as never)
    startOneClick.mockRejectedValue({ response: { status: 400, data: { error: { message: 'AI credentials are missing' } } } })
    const wrapper = mountDialog({ canGenerate: true })
    await flushPromises()
    const button = await chooseGenerate(wrapper)
    await wrapper.find('textarea').setValue('leave request')
    await button.trigger('click')
    await flushPromises()

    expect(ElMessage.error).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('open')).toBeUndefined()
    expect(localStorage.getItem(aiStudioPendingProposalStorageKey(FU_ID))).toBeNull()
  })

  it('one-click is not offered to read-only members', async () => {
    const wrapper = mountDialog()
    const card = wrapper.findAll('.mode-card')[2]
    expect(card.classes()).toContain('is-disabled')
    await card.trigger('click')
    expect(wrapper.findAll('.mode-card')[0].classes()).toContain('is-selected')
  })

  it('renders title, both mode cards and guide steps in designer-tab order plus Review', () => {
    const wrapper = mountDialog()
    expect(wrapper.text()).toContain('Build with AI')
    expect(wrapper.text()).toContain('Start a new AI design')
    expect(wrapper.text()).toContain('Continue AI draft')
    const steps = wrapper.findAll('.guide-step')
    expect(steps.map(s => s.text())).toEqual([
      '1Process Design',
      '2Table Design',
      '3Form Design',
      '4View Design',
      '5Action Design',
      '6Automation',
      '7Connections',
      '8Email Templates',
      '9Email Monitors',
      '10Decision Design',
      '11Validation'
    ])
  })

  it('without a draft: continue card is disabled and confirm emits mode new', async () => {
    const wrapper = mountDialog()
    const cards = wrapper.findAll('.mode-card')
    expect(cards[1].classes()).toContain('is-disabled')
    expect(cards[1].text()).toContain('No AI draft to resume')

    // 点击禁用卡片不应切换选中
    await cards[1].trigger('click')
    expect(cards[0].classes()).toContain('is-selected')

    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('open')).toEqual([[{ mode: 'new', draft: null }]])
    expect(wrapper.emitted('update:visible')).toEqual([[false]])
  })

  it('with a draft: shows resume text and confirm emits mode continue with the draft', async () => {
    const draft = { name: 'Expense Management', phase: 'TABLE_DESIGN', updatedAt: '2026-08-08T00:00:00Z' }
    localStorage.setItem(aiStudioDraftStorageKey(FU_ID), JSON.stringify(draft))

    const wrapper = mountDialog()
    const cards = wrapper.findAll('.mode-card')
    expect(cards[1].classes()).not.toContain('is-disabled')
    expect(cards[1].text()).toContain('Resume Expense Management · Table Design')

    await cards[1].trigger('click')
    expect(cards[1].classes()).toContain('is-selected')

    await wrapper.findAll('button')[1].trigger('click')
    // loadAiStudioDraft 归一化时会补全 completedPhases
    expect(wrapper.emitted('open')).toEqual([[{ mode: 'continue', draft: { ...draft, completedPhases: [] } }]])
  })

  it('discards a corrupt draft and falls back to the disabled continue card', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    localStorage.setItem(aiStudioDraftStorageKey(FU_ID), '{not json')

    const wrapper = mountDialog()
    expect(wrapper.findAll('.mode-card')[1].classes()).toContain('is-disabled')
    expect(warnSpy).toHaveBeenCalled()
    expect(localStorage.getItem(aiStudioDraftStorageKey(FU_ID))).toBeNull()
    warnSpy.mockRestore()
  })

  it('without a local draft: resumes the team\'s shared progress at the first unconfirmed phase', async () => {
    getState.mockResolvedValue({ data: {
      completedPhases: ['PROCESS_DESIGN', 'TABLE_DESIGN'], updatedBy: 'u2', updatedAt: '2026-09-17T00:00:00Z',
      messageCounts: {}, canModify: true
    } } as never)
    const wrapper = mountDialog({ functionUnitName: 'Expense Management' })
    await flushPromises()
    const cards = wrapper.findAll('.mode-card')
    expect(cards[1].classes()).not.toContain('is-disabled')
    expect(cards[1].text()).toContain('Resume Expense Management · Form Design')
    expect(getState).toHaveBeenCalledWith(FU_ID)
  })

  it('an empty shared state keeps the continue card disabled; a local draft skips the lookup', async () => {
    getState.mockResolvedValue({ data: {
      completedPhases: null, updatedBy: null, updatedAt: null, messageCounts: {}, canModify: true
    } } as never)
    const wrapper = mountDialog()
    await flushPromises()
    expect(wrapper.findAll('.mode-card')[1].classes()).toContain('is-disabled')

    getState.mockClear()
    localStorage.setItem(aiStudioDraftStorageKey(FU_ID), JSON.stringify({ name: 'Local', phase: 'FORM_DESIGN' }))
    mountDialog()
    await flushPromises()
    expect(getState).not.toHaveBeenCalled()
  })
})
