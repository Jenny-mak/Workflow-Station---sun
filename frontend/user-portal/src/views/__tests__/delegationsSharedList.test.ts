import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import DelegationsPage from '../delegations/index.vue'
import { queryDelegationRules, queryDelegationAudit } from '@/api/delegation'
import type { ListColumnMeta } from '@platform-shared/list/columnMeta'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('@/api/delegation', () => ({
  queryDelegationRules: vi.fn(),
  queryDelegationAudit: vi.fn(),
  createDelegationRule: vi.fn(),
  suspendDelegationRule: vi.fn(),
  resumeDelegationRule: vi.fn(),
  deleteDelegationRule: vi.fn(),
}))
vi.mock('@/composables/list/searchListFilterUsers', () => ({
  searchListFilterUsers: vi.fn(async () => []),
}))

const rulesApi = vi.mocked(queryDelegationRules)
const auditApi = vi.mocked(queryDelegationAudit)

const RULE_COLUMNS: ListColumnMeta[] = [
  { field: 'delegateId', label: 'delegation.delegateTo', kind: 'USER', filterable: true, sortable: true, groupable: true, operators: ['eq'] },
  { field: 'delegationType', label: 'delegation.delegationType', kind: 'ENUM', filterable: true, sortable: true, groupable: true, operators: ['eq'], options: [{ value: 'FULL', label: 'delegation.full' }] },
  { field: 'status', label: 'delegation.status', kind: 'ENUM', filterable: true, sortable: true, groupable: true, operators: ['eq'], options: [{ value: 'ACTIVE', label: 'delegation.active' }] },
  { field: 'startTime', label: 'delegation.startTime', kind: 'DATETIME', filterable: true, sortable: true, groupable: false, operators: ['on', 'between'] },
  { field: 'endTime', label: 'delegation.endTime', kind: 'DATETIME', filterable: true, sortable: true, groupable: false, operators: ['on', 'between'] },
  { field: 'reason', label: 'delegation.reason', kind: 'TEXT', filterable: true, sortable: false, groupable: false, operators: ['contains'] },
  { field: 'createdAt', label: 'common.createdAt', kind: 'DATETIME', filterable: true, sortable: true, groupable: false, operators: ['between'] },
]

const emptyPage = (columns: ListColumnMeta[]) => ({
  data: { columns, content: [], totalElements: 0, page: 0, size: 20, groups: [] },
})

let wrapper: VueWrapper | null = null

async function mountPage() {
  wrapper = mount(DelegationsPage, {
    global: { plugins: [ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  rulesApi.mockResolvedValue(emptyPage(RULE_COLUMNS) as never)
  auditApi.mockResolvedValue(emptyPage([]) as never)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('Delegations shared list', () => {
  it('loads rules via /delegations/query and renders ListColumnHeader chrome', async () => {
    const w = await mountPage()
    expect(rulesApi).toHaveBeenCalled()
    const headers = w.findAllComponents({ name: 'ListColumnHeader' })
    expect(headers.length).toBeGreaterThan(0)
    expect(headers.every((h) => h.find('.col-resize-handle').exists())).toBe(true)
  })

  it('applying a filter resets to the first page', async () => {
    const w = await mountPage()
    const header = w.findAllComponents({ name: 'ListColumnHeader' })[0]
    header.vm.$emit('filter-open')
    await flushPromises()
    w.findComponent({ name: 'ListFilterDialog' }).vm.$emit('apply', { operator: 'eq', value: 'u1' })
    await flushPromises()
    const last = rulesApi.mock.calls[rulesApi.mock.calls.length - 1][0]
    expect(last.page).toBe(0)
    expect(last.filters).toEqual([{ field: 'delegateId', operator: 'eq', value: 'u1' }])
  })
})
