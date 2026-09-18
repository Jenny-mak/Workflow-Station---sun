import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import en from '@/i18n/locales/en'
import type { AiStudioDocSync } from '@/api/aiStudioThread'

vi.mock('@/api/functionUnitDocument', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/functionUnitDocument')>()
  return { ...actual, functionUnitDocumentApi: { restore: vi.fn() } }
})

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn() }
}))

import { ElMessage, ElMessageBox } from 'element-plus'
import { functionUnitDocumentApi } from '@/api/functionUnitDocument'
import AiStudioDocSyncCard from '../AiStudioDocSyncCard.vue'

const restoreMock = functionUnitDocumentApi.restore as unknown as ReturnType<typeof vi.fn>
const confirmMock = ElMessageBox.confirm as unknown as ReturnType<typeof vi.fn>

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })

function mountCard(docSync: AiStudioDocSync, canModify = true) {
  return mount(AiStudioDocSyncCard, {
    props: { docSync, functionUnitId: 7, canModify, syncing: false },
    global: {
      plugins: [i18n],
      stubs: {
        'el-button': {
          props: ['disabled'],
          emits: ['click'],
          template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>'
        },
        'el-icon': true,
        DocumentVersionDiffDialog: true
      }
    }
  })
}

const buttons = (w: ReturnType<typeof mountCard>) => w.findAll('button').map(b => b.text())

describe('AiStudioDocSyncCard', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('lists what changed per document and restores the previous version on top of the AI one', async () => {
    const wrapper = mountCard({
      status: 'UPDATED',
      phases: ['TABLE_DESIGN'],
      changeSummary: 'Added the orders table.',
      documents: {
        REQUIREMENTS: { fromVersion: 3, toVersion: 3, fromLabel: 'v1.3', toLabel: 'v1.3' },
        DESIGN: { fromVersion: 2, toVersion: 3, fromLabel: 'v1.2', toLabel: 'v1.3' }
      }
    })

    expect(wrapper.text()).toContain('Documents updated')
    expect(wrapper.text()).toContain('Added the orders table.')
    expect(wrapper.text()).toContain('v1.2 → v1.3')
    expect(wrapper.text()).toContain('No changes')
    expect(buttons(wrapper)).toEqual(['View changes', 'Restore previous version', 'Open documents'])

    confirmMock.mockResolvedValue('confirm')
    restoreMock.mockResolvedValue({ data: { version: 4, majorVersion: 1, minorVersion: 4 } })
    await wrapper.findAll('button')[1].trigger('click')
    await flushPromises()

    expect(restoreMock).toHaveBeenCalledWith(7, 'DESIGN', 2, 3)
    expect(ElMessage.success).toHaveBeenCalledWith('Restored v1.2 as v1.4')
    expect(wrapper.emitted('restored')).toHaveLength(1)
  })

  it('a later edit makes "restore previous" a conflict instead of overwriting it', async () => {
    const wrapper = mountCard({
      status: 'UPDATED', phases: [],
      documents: { DESIGN: { fromVersion: 1, toVersion: 2, fromLabel: 'v1.1', toLabel: 'v1.2' } }
    })
    confirmMock.mockResolvedValue('confirm')
    restoreMock.mockRejectedValue({ response: { status: 409 } })

    await wrapper.findAll('button')[1].trigger('click')
    await flushPromises()

    expect(ElMessage.error).toHaveBeenCalledWith(en.ai.studio.docSync.restoreConflict)
    expect(wrapper.emitted('restored')).toBeUndefined()
  })

  it('shows who blocked the update and offers a re-check', async () => {
    const wrapper = mountCard({
      status: 'SKIPPED', phases: ['FORM_DESIGN'],
      documents: { DESIGN: { fromVersion: 1, toVersion: 2, toLabel: 'v1.2', blockedBy: 'bob' } }
    })

    expect(wrapper.text()).toContain('bob edited it during the update')
    await wrapper.findAll('button').find(b => b.text() === 'Check again')!.trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('failures show the translated reason; read-only members cannot retry or restore', () => {
    const failed = mountCard({
      status: 'FAILED', phases: ['TABLE_DESIGN'], errorCode: 'AI_GATEWAY_TOKEN_MISSING', errorMessage: 'No AMToken'
    }, false)
    expect(failed.text()).toContain('Document update failed')
    expect(failed.text()).toContain(en.ai.error.AI_GATEWAY_TOKEN_MISSING)
    expect(failed.text()).not.toContain('No AMToken')
    expect(buttons(failed)).toEqual([])

    const unknown = mountCard({
      status: 'FAILED', phases: [], errorCode: 'AI_STUDIO_DOC_SYNC_FAILED', errorMessage: 'connection reset'
    })
    expect(unknown.text()).toContain('connection reset')
    expect(buttons(unknown)).toEqual(['Retry'])

    const updated = mountCard({
      status: 'UPDATED', phases: [],
      documents: { DESIGN: { fromVersion: 1, toVersion: 2, fromLabel: 'v1.1', toLabel: 'v1.2' } }
    }, false)
    expect(updated.text()).toContain('Full check')
    expect(buttons(updated)).toEqual(['View changes', 'Open documents'])
  })
})
