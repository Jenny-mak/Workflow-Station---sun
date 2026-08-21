import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import RelationTables from '../relation-tables/index.vue'
import { relationTableApi, type RelationTableQueryRequest } from '@/api/relationTable'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
  createI18n: () => ({ global: { t: (key: string) => key } }),
}))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
vi.mock('@/api/relationTable', () => ({
  relationTableApi: {
    getVisibleTables: vi.fn(),
    queryTableData: vi.fn(),
    getFieldDefinitions: vi.fn(),
  },
}))
vi.mock('@/composables/list/searchListFilterUsers', () => ({
  searchListFilterUsers: vi.fn().mockResolvedValue([]),
}))

const api = vi.mocked(relationTableApi)

const TABLES = [
  { id: 11, tableName: 'orders', displayName: 'Orders', status: 'DEPLOYED', enabled: true, portalVisible: true, currentVersion: 1, permissionLevel: 'READONLY' },
  { id: 22, tableName: 'vendors', displayName: 'Vendors', status: 'DEPLOYED', enabled: true, portalVisible: true, currentVersion: 1, permissionLevel: 'READONLY' },
]

const COLUMNS = [
  { field: 'code', label: 'Code', kind: 'TEXT', filterable: true, sortable: true, groupable: false, operators: ['contains', 'eq'], options: [] },
]

/** Every queryTableData call recorded as [tableId, request]. */
const calls = () => api.queryTableData.mock.calls as unknown as [number, RelationTableQueryRequest][]
const lastCall = () => calls()[calls().length - 1]

let wrapper: VueWrapper | null = null

async function mountPage() {
  wrapper = mount(RelationTables, {
    global: {
      plugins: [ElementPlus],
      stubs: { LookupField: true, LookupViewDisplay: true },
    },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  api.getVisibleTables.mockResolvedValue({ data: TABLES } as never)
  api.getFieldDefinitions.mockResolvedValue({ data: [] } as never)
  api.queryTableData.mockResolvedValue({
    data: { columns: COLUMNS, content: [{ code: 'A-1', status: 'ACTIVE' }], totalElements: 1, page: 0, size: 20 },
  } as never)
})

describe('relation tables — query state is per table', () => {
  it('renders headers from the declared columns, not from the keys of the first row', async () => {
    const w = await mountPage()
    // `status` rides along on every row for the Active/Inactive toggle but is not declared,
    // so deriving columns from row keys (the old behavior) would leak it into the grid.
    expect(w.text()).toContain('Code')
    expect(w.findAll('.el-table__cell .list-col-label').map((n) => n.text())).toEqual(['Code'])
  })

  it('supplies a width to every declared column so the shared resize handle is present', async () => {
    const w = await mountPage()
    const headers = w.findAllComponents({ name: 'ListColumnHeader' })
    expect(headers).toHaveLength(1)
    expect(headers[0].props('width')).toBe(120)
    expect(headers[0].find('.col-resize-handle').exists()).toBe(true)
  })

  it('switching tables queries the new table with no filter, sort or search carried over', async () => {
    const w = await mountPage()
    expect(lastCall()[0]).toBe(11)

    // Filter and sort table A, then switch to table B.
    const header = w.findComponent({ name: 'ListColumnHeader' })
    header.vm.$emit('filter-open')
    await flushPromises()
    const dialog = w.findComponent({ name: 'ListFilterDialog' })
    dialog.vm.$emit('apply', { operator: 'contains', value: 'A-1' })
    await flushPromises()
    expect(lastCall()[1].filters).toEqual([{ field: 'code', operator: 'contains', value: 'A-1' }])

    header.vm.$emit('sort-change', 'DESC')
    await flushPromises()
    expect(lastCall()[1].sortField).toBe('code')

    await w.findAll('.table-list-panel .el-menu-item')[1].trigger('click')
    await flushPromises()

    const [tableId, request] = lastCall()
    expect(tableId).toBe(22)
    expect(request.filters).toBeUndefined()
    expect(request.sortField).toBeUndefined()
    expect(request.search).toBeUndefined()
    expect(request.page).toBe(0)
  })

  it('applying a filter and changing sort both reset to the first page', async () => {
    const w = await mountPage()
    const header = w.findComponent({ name: 'ListColumnHeader' })

    header.vm.$emit('filter-open')
    await flushPromises()
    w.findComponent({ name: 'ListFilterDialog' }).vm.$emit('apply', { operator: 'eq', value: 'x' })
    await flushPromises()
    expect(lastCall()[1].page).toBe(0)

    header.vm.$emit('sort-change', 'ASC')
    await flushPromises()
    expect(lastCall()[1].page).toBe(0)
    expect(lastCall()[1].sortDirection).toBe('ASC')
  })

})
