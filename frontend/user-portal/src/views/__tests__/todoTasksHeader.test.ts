import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import TodoTasks from '../tasks/index.vue'
import { queryTodoTasks } from '@/api/task'

vi.mock('@/api/task', () => ({
  queryTodoTasks: vi.fn(),
  batchUrgeTasks: vi.fn(),
}))

vi.mock('@/stores/pendingTask', () => ({
  usePendingTaskStore: () => ({
    syncCountFromListTotal: vi.fn(),
  }),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

const api = vi.mocked(queryTodoTasks)

function mountPage() {
  const i18n = createI18n({
    legacy: false,
    locale: 'en',
    messages: {
      en: {
        common: { loading: 'Loading', cancel: 'Cancel', confirm: 'Confirm', success: 'OK', error: 'Error', reason: 'Reason' },
        task: {
          title: 'To Do',
          noTasks: 'None',
          loadFailed: 'Failed',
          selected: '{count} selected',
          batchUrge: 'Batch',
          overdue: 'Overdue',
          normal: 'Normal',
          user: 'User',
          requestId: 'Request ID',
          taskName: 'Task Name',
          currentStep: 'Step',
          processName: 'Process',
          assignmentType: 'Type',
          initiator: 'Initiator',
          priority: 'Priority',
          createTime: 'Created',
          dueDate: 'Due',
        },
      },
    },
  })
  return mount(TodoTasks, {
    global: {
      plugins: [i18n],
      stubs: {
        'el-table': true,
        'el-table-column': true,
        'el-dialog': true,
        'el-form': true,
        'el-form-item': true,
        'el-input': true,
        'el-button': true,
        'el-link': true,
        'el-tag': true,
        'el-icon': true,
        ListColumnHeader: true,
        ListFilterDialog: true,
        ListPagination: true,
      },
    },
  })
}

describe('To Do shared list', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockResolvedValue({
      data: {
        columns: [
          { field: 'requestId', label: 'task.requestId', kind: 'TEXT', operators: ['contains'], filterable: true, sortable: true, groupable: true },
          { field: 'taskName', label: 'task.taskName', kind: 'TEXT', operators: ['contains'], filterable: true, sortable: true, groupable: true },
        ],
        content: [],
        groups: [],
        page: 0,
        size: 20,
        totalElements: 0,
      },
    } as never)
  })

  it('loads via /tasks/todo/query and renders ListColumnHeader chrome', async () => {
    const w = mountPage()
    await flushPromises()
    expect(api).toHaveBeenCalled()
    const body = api.mock.calls[0][0]
    expect(body).toMatchObject({ page: 0, size: 20 })
    expect(w.text()).toContain('To Do')
  })
})
