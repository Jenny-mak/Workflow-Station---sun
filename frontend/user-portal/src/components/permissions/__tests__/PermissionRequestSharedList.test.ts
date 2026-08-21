import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import PermissionRequestSharedList from '@/components/permissions/PermissionRequestSharedList.vue'
import { permissionApi } from '@/api/permission'
import type { ListColumnMeta } from '@platform-shared/list/columnMeta'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('@/api/permission', () => ({
  permissionApi: {
    queryPermissionRequests: vi.fn(),
  },
}))
vi.mock('@/composables/list/searchListFilterUsers', () => ({
  searchListFilterUsers: vi.fn(async () => []),
}))

const api = vi.mocked(permissionApi.queryPermissionRequests)

const COLUMNS: ListColumnMeta[] = [
  { field: 'requestType', label: 'permission.requestType', kind: 'ENUM', filterable: true, sortable: true, groupable: true, operators: ['eq'], options: [{ value: 'ROLE', label: 'ROLE' }] },
  { field: 'status', label: 'permission.status', kind: 'ENUM', filterable: true, sortable: true, groupable: true, operators: ['eq'], options: [{ value: 'PENDING', label: 'PENDING' }] },
  { field: 'createdAt', label: 'common.createdAt', kind: 'DATETIME', filterable: true, sortable: true, groupable: false, operators: ['between'] },
]

let wrapper: VueWrapper | null = null

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  api.mockResolvedValue({
    data: { columns: COLUMNS, content: [], totalElements: 0, page: 0, size: 20, groups: [] },
  } as never)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('PermissionRequestSharedList', () => {
  it('loads via /permissions/requests/query for the given scope', async () => {
    wrapper = mount(PermissionRequestSharedList, {
      props: {
        scope: 'MY_PENDING',
        storageKey: 'test-perm-my-pending',
        emptyText: 'empty',
        enabled: true,
      },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    expect(api).toHaveBeenCalled()
    expect(api.mock.calls[0][0].scope).toBe('MY_PENDING')
    expect(wrapper!.findAllComponents({ name: 'ListColumnHeader' }).length).toBeGreaterThan(0)
  })
})
