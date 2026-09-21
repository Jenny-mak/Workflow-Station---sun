import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import DelegationCreateDialog from '@/components/delegations/DelegationCreateDialog.vue'
import { createDelegationRule } from '@/api/delegation'
import { processApi } from '@/api/process'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
  createI18n: () => ({
    global: { t: (key: string) => key, locale: { value: 'en' } },
    install: () => {},
  }),
}))

vi.mock('@/api/permission', () => ({
  permissionApi: {
    getBusinessUnitsTree: vi.fn(),
    getBusinessUnitRoles: vi.fn(),
  },
}))

vi.mock('@/api/delegation', () => ({
  createDelegationRule: vi.fn(),
}))

vi.mock('@/api/process', () => ({
  processApi: {
    getDefinitions: vi.fn(),
  },
}))

vi.mock('@/api/auth', () => ({
  getStoredUser: vi.fn(() => ({ userId: 'user-self' })),
  USER_ID_KEY: 'ws_up_user_id',
}))

const LookupFieldStub = {
  name: 'LookupField',
  props: [
    'tableId',
    'searchFields',
    'displayField',
    'displayFields',
    'selectedDisplayField',
    'viewFields',
    'modelValue',
    'prefetchLimit',
    'remoteFilter',
    'excludePrimaryKeys',
  ],
  emits: ['update:modelValue', 'select', 'clear'],
  template:
    '<button class="stub-lookup" type="button" @click="$emit(\'select\', { id: \'user-b\' })">lookup</button>',
}

function mountDialog() {
  return mount(DelegationCreateDialog, {
    props: { visible: true },
    attachTo: document.body,
    global: {
      plugins: [ElementPlus],
      mocks: { $t: (key: string) => key },
      stubs: {
        LookupField: LookupFieldStub,
        ElDialog: {
          template: '<div class="el-dialog"><slot /><slot name="footer" /></div>',
        },
        teleport: true,
        transition: false,
      },
    },
  })
}

describe('DelegationCreateDialog target pickers', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })
  it('uses user lookup instead of hardcoded people', async () => {
    vi.mocked(processApi.getDefinitions).mockResolvedValue({
      data: [{ key: 'leave-fu', name: 'Leave Request Delegation' }],
    } as never)
    const wrapper = mountDialog()
    await nextTick()
    await nextTick()
    expect(wrapper.text()).not.toContain('Li Si')
    expect(wrapper.text()).not.toContain('Wang Wu')
    expect(wrapper.find('[data-testid="delegation-user-lookup"]').exists()).toBe(true)
    const lookup = wrapper.getComponent({ name: 'LookupField' })
    expect(lookup.props('tableId')).toBe(-1_000_000_001)
    expect(lookup.props('excludePrimaryKeys')).toEqual(['user-self'])
    wrapper.unmount()
  })

  it('does not set delegateId when the current user is selected', async () => {
    vi.mocked(processApi.getDefinitions).mockResolvedValue({
      data: [{ key: 'leave-fu', name: 'Leave Request Delegation' }],
    } as never)
    const wrapper = mountDialog()
    await nextTick()
    await nextTick()
    const lookup = wrapper.getComponent({ name: 'LookupField' })
    const vm = wrapper.vm as unknown as { form: { delegateId: string } }
    await lookup.vm.$emit('select', { id: 'user-self' })
    await nextTick()
    expect(vm.form.delegateId).toBe('')
    await lookup.vm.$emit('select', { id: 'user-b' })
    await nextTick()
    expect(vm.form.delegateId).toBe('user-b')
    wrapper.unmount()
  })

  it('requires a BU+Role pair when that target is selected', async () => {
    vi.mocked(processApi.getDefinitions).mockResolvedValue({
      data: [{ key: 'leave-fu', name: 'Leave Request Delegation' }],
    } as never)
    const wrapper = mountDialog()
    await nextTick()
    await nextTick()
    expect(wrapper.text()).toContain('delegation.specifyBuRole')
    expect(wrapper.text()).toContain('delegation.specifyUser')
    wrapper.unmount()
  })

  it('hides Urgent and lists function unit names for Partial', async () => {
    vi.mocked(processApi.getDefinitions).mockResolvedValue({
      data: [
        { key: 'leave-request-delegation-20260915-tj3oye', name: 'Leave Request Delegation' },
        { key: 'owner-demo-20260907-gehibh', name: 'Owner Demo' },
      ],
    } as never)
    const wrapper = mountDialog()
    await nextTick()
    await Promise.resolve()
    await nextTick()
    expect(wrapper.find('[data-testid="delegation-type-select"]').text()).toContain('ALL')
    expect(wrapper.html()).not.toContain('URGENT')
    const vm = wrapper.vm as unknown as {
      form: { delegationType: string }
      fuOptions: Array<{ key: string; name: string }> | { value: Array<{ key: string; name: string }> }
    }
    const listed = Array.isArray(vm.fuOptions) ? vm.fuOptions : vm.fuOptions.value
    expect(listed).toEqual([
      { key: 'leave-request-delegation-20260915-tj3oye', name: 'Leave Request Delegation' },
      { key: 'owner-demo-20260907-gehibh', name: 'Owner Demo' },
    ])
    vm.form.delegationType = 'PARTIAL'
    await nextTick()
    expect(wrapper.find('[data-testid="delegation-process-types"]').exists()).toBe(true)
    expect(listed.map((unit) => unit.name)).toEqual([
      'Leave Request Delegation',
      'Owner Demo',
    ])
    wrapper.unmount()
  })

  it('submits Partial processTypes as function unit keys', async () => {
    vi.mocked(processApi.getDefinitions).mockResolvedValue({
      data: [{ key: 'leave-fu', name: 'Leave Request Delegation' }],
    } as never)
    vi.mocked(createDelegationRule).mockResolvedValue({} as never)
    const wrapper = mountDialog()
    await nextTick()
    await Promise.resolve()
    await nextTick()
    const vm = wrapper.vm as unknown as {
      form: {
        delegateId: string
        delegationType: string
        processTypes: string[]
      }
      submit: () => Promise<void>
    }
    vm.form.delegateId = 'user-b'
    vm.form.delegationType = 'PARTIAL'
    vm.form.processTypes = ['leave-fu']
    await vm.submit()
    expect(createDelegationRule).toHaveBeenCalledWith(
      expect.objectContaining({
        delegationType: 'PARTIAL',
        processTypes: ['leave-fu'],
        delegateId: 'user-b',
      }),
    )
    wrapper.unmount()
  })

  it('does not submit a start time in the past', async () => {
    vi.mocked(processApi.getDefinitions).mockResolvedValue({
      data: [{ key: 'leave-fu', name: 'Leave Request Delegation' }],
    } as never)
    vi.mocked(createDelegationRule).mockResolvedValue({} as never)
    const wrapper = mountDialog()
    await nextTick()
    await Promise.resolve()
    await nextTick()
    const vm = wrapper.vm as unknown as {
      form: { delegateId: string; startTime: Date | null; delegationType: string }
      submit: () => Promise<void>
    }
    vm.form.delegateId = 'user-b'
    vm.form.delegationType = 'ALL'
    vm.form.startTime = new Date(Date.now() - 60_000)
    await vm.submit()
    expect(createDelegationRule).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
