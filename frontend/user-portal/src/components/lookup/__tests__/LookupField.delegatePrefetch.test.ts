import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import LookupField from '../LookupField.vue'
import { relationTableApi } from '@/api/relationTable'

vi.mock('@/api/relationTable', () => ({
  relationTableApi: {
    searchForLookup: vi.fn().mockResolvedValue({ data: [] }),
    getViewFields: vi.fn().mockResolvedValue({ data: [] }),
  },
}))

vi.mock('../fetchLookupRowByPrimaryKey', () => ({
  fetchLookupRowByPrimaryKey: vi.fn().mockResolvedValue(null),
}))

const baseProps = {
  tableId: -1_000_000_001,
  searchFields: ['id', 'username', 'display_name'],
  displayField: 'display_name',
  displayFields: ['username', 'display_name', 'full_name', 'email', 'employee_id'],
  selectedDisplayField: 'display_name',
  viewFields: [
    { fieldName: 'username', displayLabel: 'Username', sortOrder: 0, visible: true },
    { fieldName: 'display_name', displayLabel: 'Display Name', sortOrder: 1, visible: true },
    { fieldName: 'full_name', displayLabel: 'Full Name', sortOrder: 2, visible: true },
    { fieldName: 'email', displayLabel: 'Email', sortOrder: 3, visible: true },
    { fieldName: 'employee_id', displayLabel: 'Employee ID', sortOrder: 4, visible: true },
  ],
  placeholder: 'Click to search',
  prefetchLimit: 200,
  remoteFilter: true,
}

const stubs = {
  ElInput: true,
  ElIcon: true,
  ElTable: true,
  ElTableColumn: true,
  Teleport: true,
}

function pageOf(n: number) {
  return Array.from({ length: n }, (_, i) => ({
    id: `u-${i}`,
    username: `user${i}`,
    display_name: `User ${i}`,
  }))
}

describe('LookupField delegate prefetch / remote search', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(relationTableApi.searchForLookup).mockResolvedValue({ data: pageOf(200) })
    vi.mocked(relationTableApi.getViewFields).mockResolvedValue({ data: [] })
  })

  it('loads one page on first open when prefetchLimit is set', async () => {
    const wrapper = mount(LookupField, {
      props: baseProps,
      global: { stubs },
    })
    await flushPromises()
    await (wrapper.vm as { handleFocus: () => void }).handleFocus()
    await flushPromises()

    expect(relationTableApi.searchForLookup).toHaveBeenCalledTimes(1)
    expect(relationTableApi.searchForLookup).toHaveBeenCalledWith(
      -1_000_000_001,
      expect.objectContaining({ keyword: '', limit: 200, offset: 0 }),
    )
    wrapper.unmount()
  })

  it('uses viewFields displayLabel for column headers', async () => {
    const wrapper = mount(LookupField, {
      props: baseProps,
      global: { stubs },
    })
    await nextTick()
    const cols = (wrapper.vm as { visibleColumns: Array<{ prop: string; label: string }> }).visibleColumns
    expect(cols).toEqual(expect.arrayContaining([
      expect.objectContaining({ prop: 'username', label: 'Username' }),
      expect.objectContaining({ prop: 'display_name', label: 'Display Name' }),
    ]))
    expect(cols.some(c => c.label === 'username' || c.label === 'USERNAME')).toBe(false)
    wrapper.unmount()
  })

  it('sends the typed keyword to the server when remoteFilter is on', async () => {
    const wrapper = mount(LookupField, {
      props: baseProps,
      attachTo: document.body,
      global: {
        plugins: [ElementPlus],
        stubs: { Teleport: true, ElIcon: true, ElTable: true, ElTableColumn: true },
      },
    })
    await flushPromises()
    await (wrapper.vm as { handleFocus: () => void }).handleFocus()
    await flushPromises()
    vi.mocked(relationTableApi.searchForLookup).mockClear()
    vi.mocked(relationTableApi.searchForLookup).mockResolvedValue({
      data: [{ id: 'u-li', username: 'lisi', display_name: 'Li Si' }],
    })

    const input = wrapper.find('input')
    expect(input.exists()).toBe(true)
    await input.setValue('li')
    await new Promise(resolve => setTimeout(resolve, 350))
    await flushPromises()

    expect(relationTableApi.searchForLookup).toHaveBeenCalledWith(
      -1_000_000_001,
      expect.objectContaining({ keyword: 'li', limit: 200 }),
    )
    wrapper.unmount()
  })

  it('omits excludePrimaryKeys from remoteFilter results', async () => {
    const wrapper = mount(LookupField, {
      props: { ...baseProps, excludePrimaryKeys: ['u-0'] },
      global: { stubs },
    })
    await flushPromises()
    await (wrapper.vm as { handleFocus: () => void }).handleFocus()
    await flushPromises()
    const rows = (wrapper.vm as { filteredResults: Array<{ id: string }> }).filteredResults
    expect(rows.some((row) => row.id === 'u-0')).toBe(false)
    expect(rows.some((row) => row.id === 'u-1')).toBe(true)
    wrapper.unmount()
  })
})
