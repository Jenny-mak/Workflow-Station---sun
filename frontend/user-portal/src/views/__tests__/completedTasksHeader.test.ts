import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import CompletedTasks from '../tasks/completed.vue'
import { queryCompletedTasks } from '@/api/task'
import type { ListColumnMeta } from '@platform-shared/list/columnMeta'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/api/task', () => ({
  queryCompletedTasks: vi.fn(),
}))

const api = vi.mocked(queryCompletedTasks)

const COLUMNS: ListColumnMeta[] = [
  { field: 'requestId', label: 'task.requestId', kind: 'TEXT', filterable: false, sortable: false, groupable: false, operators: [] },
  { field: 'taskName', label: 'task.taskName', kind: 'TEXT', filterable: true, sortable: true, groupable: false, operators: ['contains', 'eq'] },
  { field: 'currentStepName', label: 'task.currentStep', kind: 'TEXT', filterable: false, sortable: false, groupable: false, operators: [] },
  { field: 'processDefinitionName', label: 'task.processName', kind: 'TEXT', filterable: true, sortable: true, groupable: false, operators: ['contains'] },
  { field: 'action', label: 'task.action', kind: 'ENUM', filterable: true, sortable: true, groupable: true, operators: ['eq', 'ne'], options: [{ value: 'approved', label: 'action.approved' }] },
  { field: 'createTime', label: 'task.createTime', kind: 'DATETIME', filterable: true, sortable: true, groupable: false, operators: ['on', 'between'] },
  { field: 'completedTime', label: 'task.completedTime', kind: 'DATETIME', filterable: true, sortable: true, groupable: false, operators: ['between'] },
  { field: 'durationInMillis', label: 'task.duration', kind: 'NUMBER', filterable: true, sortable: true, groupable: false, operators: ['gt', 'between'] },
]

const lastRequest = () => api.mock.calls[api.mock.calls.length - 1][0]

let wrapper: VueWrapper | null = null

async function mountPage() {
  wrapper = mount(CompletedTasks, {
    global: { plugins: [ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  api.mockResolvedValue({
    data: { columns: COLUMNS, content: [], totalElements: 0, page: 0, size: 20 },
  } as never)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('completed tasks — shared list header', () => {
  it('renders a shared header with a resize handle on every declared column', async () => {
    const w = await mountPage()
    const headers = w.findAllComponents({ name: 'ListColumnHeader' })
    expect(headers.map((h) => h.props('column').field)).toEqual(COLUMNS.map((c) => c.field))
    expect(headers.every((h) => h.find('.col-resize-handle').exists())).toBe(true)
    // Move is on for the whole grid, so even display-only columns get a header menu.
    expect(headers.every((h) => h.find('.list-col-trigger').exists())).toBe(true)
    const requestId = headers[0].props('column') as ListColumnMeta
    expect(requestId.filterable).toBe(false)
    expect(requestId.sortable).toBe(false)
    expect(requestId.groupable).toBe(false)
  })

  it('applying a filter and changing sort both reset to the first page', async () => {
    const w = await mountPage()
    const header = w.findAllComponents({ name: 'ListColumnHeader' })[1]

    header.vm.$emit('filter-open')
    await flushPromises()
    w.findComponent({ name: 'ListFilterDialog' }).vm.$emit('apply', { operator: 'contains', value: 'review' })
    await flushPromises()
    expect(lastRequest().page).toBe(0)
    expect(lastRequest().filters).toEqual([{ field: 'taskName', operator: 'contains', value: 'review' }])

    header.vm.$emit('sort-change', 'ASC')
    await flushPromises()
    expect(lastRequest().page).toBe(0)
    expect(lastRequest().sortField).toBe('taskName')
    expect(lastRequest().sortDirection).toBe('ASC')
  })

  it('grouping sends groupBy from the declared ENUM column', async () => {
    const w = await mountPage()
    const actionHeader = w.findAllComponents({ name: 'ListColumnHeader' })
      .find((h) => h.props('column').field === 'action')
    expect(actionHeader).toBeTruthy()
    actionHeader!.vm.$emit('group-change', true)
    await flushPromises()
    expect(lastRequest().groupBy).toBe('action')
    expect(lastRequest().page).toBe(0)
  })

  it('moving a column does not refetch', async () => {
    const w = await mountPage()
    const callsBefore = api.mock.calls.length
    const header = w.findAllComponents({ name: 'ListColumnHeader' })[1]
    header.vm.$emit('move', 'right')
    await flushPromises()
    expect(api.mock.calls.length).toBe(callsBefore)
    const fields = w.findAllComponents({ name: 'ListColumnHeader' }).map((h) => h.props('column').field)
    expect(fields[0]).toBe('requestId')
    expect(fields[1]).toBe('currentStepName')
    expect(fields[2]).toBe('taskName')
  })
}, 20_000)
