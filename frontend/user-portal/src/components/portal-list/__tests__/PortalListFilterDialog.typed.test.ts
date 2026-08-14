import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PortalListFilterDialog from '../PortalListFilterDialog.vue'
import type { PortalListColumnMeta } from '@/utils/portalListGridRuntime'

/**
 * The header filter dialog must offer the control that fits the column's declared kind:
 * a day picker for timestamps, a picker for closed code lists, free text otherwise —
 * and only the operators the backend accepts for that kind.
 */

vi.mock('vue-i18n', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-i18n')>()
  return { ...actual, useI18n: () => ({ t: (key: string) => key }) }
})

const ElDialogStub = {
  name: 'ElDialog',
  props: ['modelValue', 'title'],
  template: '<div class="stub-dialog"><slot /><slot name="footer" /></div>',
}
const ElFormStub = { name: 'ElForm', template: '<div><slot /></div>' }
const ElFormItemStub = { name: 'ElFormItem', props: ['label'], template: '<div class="stub-item"><slot /></div>' }
const ElSelectStub = {
  name: 'ElSelect',
  props: ['modelValue', 'remote', 'loading'],
  template: '<div class="stub-select"><slot /></div>',
}
const ElOptionStub = {
  name: 'ElOption',
  props: ['label', 'value'],
  template: '<div class="stub-option" :data-value="value">{{ label }}</div>',
}
const ElDatePickerStub = {
  name: 'ElDatePicker',
  props: ['modelValue', 'type'],
  template: '<div class="stub-date-picker" :data-type="type" />',
}
const ElInputStub = { name: 'ElInput', props: ['modelValue'], template: '<div class="stub-input" />' }
const ElButtonStub = { name: 'ElButton', template: '<button><slot /></button>' }

const global = {
  stubs: {
    ElDialog: ElDialogStub,
    ElForm: ElFormStub,
    ElFormItem: ElFormItemStub,
    ElSelect: ElSelectStub,
    ElOption: ElOptionStub,
    ElDatePicker: ElDatePickerStub,
    ElInput: ElInputStub,
    ElButton: ElButtonStub,
  },
}

const dateColumn: PortalListColumnMeta = {
  field: 'startTime',
  kind: 'DATETIME',
  filterable: true,
  sortable: true,
  groupable: true,
  operators: ['on', 'before', 'after', 'between', 'isNotNull', 'isNull'],
  options: [],
}

const enumColumn: PortalListColumnMeta = {
  field: 'status',
  kind: 'ENUM',
  filterable: true,
  sortable: true,
  groupable: true,
  operators: ['eq', 'ne', 'isNotNull', 'isNull'],
  options: ['ACTIVE', 'SUSPENDED'],
}

/** The dialog fills its draft when it opens, so tests must transition closed → open. */
async function openDialog(props: Record<string, unknown>) {
  const wrapper = mount(PortalListFilterDialog, {
    props: { modelValue: false, title: 'Filter', ...props },
    global,
  })
  await wrapper.setProps({ modelValue: true })
  return wrapper
}

describe('PortalListFilterDialog value control by column kind', () => {
  it('falls back to free text and the full operator set without column meta', async () => {
    const wrapper = await openDialog({})

    expect(wrapper.find('.stub-input').exists()).toBe(true)
    expect(wrapper.find('.stub-date-picker').exists()).toBe(false)
    expect(wrapper.findAll('.stub-option')).toHaveLength(8)
    wrapper.unmount()
  })

  it('offers only the operators the column kind accepts', async () => {
    const wrapper = await openDialog({ column: enumColumn })

    const operatorOptions = wrapper.findAll('.stub-select')[0].findAll('.stub-option')
    expect(operatorOptions.map(o => o.attributes('data-value')))
      .toEqual(['eq', 'ne', 'isNotNull', 'isNull'])
    wrapper.unmount()
  })

  it('renders a picker of the caller-localized choices for an enum column', async () => {
    const wrapper = await openDialog({
      column: enumColumn,
      initial: { operator: 'eq', value: 'ACTIVE' },
      options: [
        { value: 'ACTIVE', label: 'Active' },
        { value: 'SUSPENDED', label: 'Suspended' },
      ],
    })

    const selects = wrapper.findAll('.stub-select')
    expect(selects).toHaveLength(2)
    expect(selects[1].findAll('.stub-option').map(o => o.text())).toEqual(['Active', 'Suspended'])
    expect(wrapper.find('.stub-input').exists()).toBe(false)
    wrapper.unmount()
  })

  it('renders a day picker for a timestamp column', async () => {
    const wrapper = await openDialog({
      column: dateColumn,
      initial: { operator: 'on', value: '2026-03-05' },
    })

    expect(wrapper.find('.stub-date-picker').attributes('data-type')).toBe('date')
    expect(wrapper.find('.stub-input').exists()).toBe(false)
    wrapper.unmount()
  })

  it('switches the day picker to a range for the between operator', async () => {
    const wrapper = await openDialog({
      column: dateColumn,
      initial: { operator: 'between', value: '2026-03-01,2026-03-31' },
    })

    expect(wrapper.find('.stub-date-picker').attributes('data-type')).toBe('daterange')
    wrapper.unmount()
  })

  it('hides the value control for operators that take no value', async () => {
    const wrapper = await openDialog({
      column: dateColumn,
      initial: { operator: 'isNotNull', value: '' },
    })

    expect(wrapper.find('.stub-date-picker').exists()).toBe(false)
    expect(wrapper.find('.stub-input').exists()).toBe(false)
    expect(wrapper.findAll('.stub-select')).toHaveLength(1)
    wrapper.unmount()
  })

  it('asks the owner to search people when a user column opens', async () => {
    const userColumn: PortalListColumnMeta = { ...enumColumn, field: 'delegateId', kind: 'USER', options: [] }
    const wrapper = await openDialog({ column: userColumn })

    expect(wrapper.emitted('search')?.[0]).toEqual([''])
    wrapper.unmount()
  })
})
