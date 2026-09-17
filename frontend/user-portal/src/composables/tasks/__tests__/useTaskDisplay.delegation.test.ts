import { describe, expect, it, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { useUserStore } from '@/stores/user'
import { useTaskDisplay } from '@/composables/tasks/useTaskDisplay'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string, params?: Record<string, string>) =>
      params?.name ? `${key}:${params.name}` : key,
  }),
}))

describe('useTaskDisplay standing overlay copy', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useUserStore().setUserInfo({
      id: 'user-b',
      username: 'b',
      name: 'Bee',
      email: 'b@example.com',
      roles: ['user'],
    })
  })

  it('shows on-behalf-of for standing DELEGATED overlay', () => {
    const taskInfo = ref({
      assignmentType: 'DELEGATED',
      assignee: 'user-a',
      assigneeName: 'Alice',
      delegatorId: 'user-a',
    })
    const { getDelegationStatusDisplay } = useTaskDisplay(taskInfo)
    expect(getDelegationStatusDisplay()).toBe('task.onBehalfOf:Alice')
  })

  it('shows on-behalf-of for BU+Role delegatee who is not assignee', () => {
    const taskInfo = ref({
      assignmentType: 'USER',
      assignee: 'user-a',
      assigneeName: 'Alice',
      delegatedTargetType: 'BU_ROLE',
      delegatedBuCode: 'HK',
      delegatedRoleCode: 'APPROVER',
    })
    const { getDelegationStatusDisplay } = useTaskDisplay(taskInfo)
    expect(getDelegationStatusDisplay()).toBe('task.onBehalfOf:Alice')
  })
})
