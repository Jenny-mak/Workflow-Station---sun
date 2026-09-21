import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import DelegationsPage from '../delegations/index.vue'
import { queryDelegationRules, queryDelegationAudit } from '@/api/delegation'
import { queryTodoTasks } from '@/api/task'
import type { ListColumnMeta } from '@platform-shared/list/columnMeta'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
  createI18n: () => ({
    global: { t: (key: string) => key, locale: { value: 'en' } },
    install: () => {},
  }),
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/api/delegation', () => ({
  queryDelegationRules: vi.fn(),
  queryDelegationAudit: vi.fn(),
  createDelegationRule: vi.fn(),
  suspendDelegationRule: vi.fn(),
  resumeDelegationRule: vi.fn(),
  deleteDelegationRule: vi.fn(),
}))
vi.mock('@/api/task', () => ({
  queryTodoTasks: vi.fn(),
}))
vi.mock('@/api/permission', () => ({
  permissionApi: {
    getBusinessUnitsTree: vi.fn(),
    getBusinessUnitRoles: vi.fn(),
  },
}))
vi.mock('@/components/lookup/LookupField.vue', () => ({
  default: { name: 'LookupField', template: '<div />' },
}))
vi.mock('@/composables/list/searchListFilterUsers', () => ({
  searchListFilterUsers: vi.fn(async () => []),
}))

const rulesApi = vi.mocked(queryDelegationRules)
const auditApi = vi.mocked(queryDelegationAudit)
const todoApi = vi.mocked(queryTodoTasks)

const RULE_COLUMNS: ListColumnMeta[] = [
  { field: 'delegateId', label: 'delegation.delegateTo', kind: 'USER', filterable: true, sortable: true, operators: ['eq'] },
  { field: 'delegationType', label: 'delegation.delegationType', kind: 'ENUM', filterable: true, sortable: true, operators: ['eq'], options: [{ value: 'FULL', label: 'delegation.full' }] },
  { field: 'status', label: 'delegation.status', kind: 'ENUM', filterable: true, sortable: true, operators: ['eq'], options: [{ value: 'ACTIVE', label: 'delegation.active' }] },
  { field: 'startTime', label: 'delegation.startTime', kind: 'DATETIME', filterable: true, sortable: true, operators: ['on', 'between'] },
  { field: 'endTime', label: 'delegation.endTime', kind: 'DATETIME', filterable: true, sortable: true, operators: ['on', 'between'] },
  { field: 'reason', label: 'delegation.reason', kind: 'TEXT', filterable: true, sortable: false, operators: ['contains'] },
  { field: 'createdAt', label: 'common.createdAt', kind: 'DATETIME', filterable: true, sortable: true, operators: ['between'] },
]

const emptyPage = (columns: ListColumnMeta[]) => ({
  data: { columns, content: [], totalElements: 0, page: 0, size: 20 },
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
  todoApi.mockResolvedValue(emptyPage([]) as never)
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
    // 超时统一由 vitest.config.ts 的 testTimeout 控制（60s）。此处曾硬编码 15000，
    // 会覆盖全局配置，在覆盖率插桩下反而更容易超时。
  })

  it('gives Action a fixed width and paints suspend/delete buttons on ACTIVE rows', async () => {
    rulesApi.mockResolvedValue({
      data: {
        columns: RULE_COLUMNS,
        content: [{
          id: 41,
          delegatorId: 'u-me',
          delegateId: 'u-other',
          delegateDisplayName: 'Lina Chen',
          delegationType: 'FULL',
          status: 'ACTIVE',
          createdAt: '2026-08-01T00:00:00Z',
          updatedAt: '2026-08-01T00:00:00Z',
        }],
        totalElements: 1,
        page: 0,
        size: 20,
      },
    } as never)
    const w = await mountPage()
    const actionCol = w.findAllComponents({ name: 'ElTableColumn' }).find(
      (col) => col.props('label') === 'common.actions',
    )
    expect(actionCol).toBeTruthy()
    expect(Number(actionCol!.props('width'))).toBe(200)
    expect(w.text()).toContain('Lina Chen')
    expect(w.text()).toContain('delegation.suspend')
    expect(w.text()).toContain('common.delete')
    expect(w.findAll('.row-actions .el-button').length).toBeGreaterThanOrEqual(2)
  })

  it('loads delegated tasks via todo/query when the Delegated tab opens', async () => {
    const w = await mountPage()
    expect(todoApi).not.toHaveBeenCalled()
    const tabs = w.findAll('.el-tabs__item')
    expect(tabs.length).toBeGreaterThanOrEqual(2)
    await tabs[1].trigger('click')
    await flushPromises()
    expect(todoApi).toHaveBeenCalled()
    const body = todoApi.mock.calls[todoApi.mock.calls.length - 1][0]
    expect(body.assignmentTypes).toEqual(['DELEGATED'])
  })

  it('shows who delegated and user-or-BU on the Delegated tab', async () => {
    todoApi.mockResolvedValue({
      data: {
        columns: [
          { field: 'requestId', label: 'task.requestId', kind: 'TEXT', filterable: true, sortable: true, operators: ['contains'] },
          { field: 'functionUnitCode', label: 'task.functionUnit', kind: 'TEXT', filterable: true, sortable: true, operators: ['contains'] },
          { field: 'taskName', label: 'task.taskName', kind: 'TEXT', filterable: true, sortable: true, operators: ['contains'] },
          { field: 'delegatorId', label: 'delegation.delegator', kind: 'USER', filterable: true, sortable: true, operators: ['eq'] },
          { field: 'delegatedTargetType', label: 'delegation.delegatedTargetKind', kind: 'ENUM', filterable: true, sortable: true, operators: ['eq'] },
          { field: 'assignmentType', label: 'task.assignmentType', kind: 'ENUM', filterable: true, sortable: true, operators: ['eq'] },
          { field: 'createTime', label: 'task.createTime', kind: 'DATETIME', filterable: true, sortable: true, operators: ['between'] },
        ],
        content: [{
          taskId: 't-1',
          requestId: 'R-1',
          functionUnitCode: 'owner-demo',
          taskName: 'Review',
          delegatorId: 'user-e2e-lina',
          delegatorName: '李娜',
          delegatedTargetType: 'USER',
          assignmentType: 'DELEGATED',
          createTime: '2026-09-18T00:00:00Z',
        }],
        totalElements: 1,
        page: 0,
        size: 20,
      },
    } as never)
    const w = await mountPage()
    const tabs = w.findAll('.el-tabs__item')
    await tabs[1].trigger('click')
    await flushPromises()
    expect(w.text()).toContain('李娜')
    expect(w.text()).toContain('delegation.specifyUser')
  })
})
