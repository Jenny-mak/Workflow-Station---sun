import { describe, expect, it, vi, beforeEach } from 'vitest'
import { ref } from 'vue'

const pushMock = vi.fn()
const delegateTaskMock = vi.fn(async () => ({}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
}))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}))
vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    warning: vi.fn(),
    error: vi.fn(),
  },
}))
vi.mock('@/api/task', () => ({
  completeTask: vi.fn(),
  delegateTask: (...args: unknown[]) => delegateTaskMock(...args),
  transferTask: vi.fn(),
  urgeTask: vi.fn(),
}))
vi.mock('@/api/user', () => ({
  userApi: {
    searchUsers: vi.fn(async () => []),
  },
}))
vi.mock('@/utils/subTableAssignment', () => ({
  resolveAssigneeFieldForBinding: () => 'assignee_user_id',
  allSubTableRowsHaveAssignee: () => true,
}))
import { ElMessage } from 'element-plus'
import { completeTask } from '@/api/task'
import { useTaskActions } from '../useTaskActions'

describe('useTaskActions submitAction delegate payload', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  function createActions(actionForm: Record<string, string>) {
    return useTaskActions({
      taskId: 'task-1',
      taskInfo: ref({}),
      subTableBindings: ref([]),
      formData: ref({}),
      submitting: ref(false),
      approveDialogVisible: ref(false),
      approveDialogTitle: ref(''),
      currentApproveAction: ref(''),
      approveForm: { comment: '' },
      actionDialogVisible: ref(true),
      actionDialogTitle: ref(''),
      currentAction: ref('delegate'),
      actionForm: actionForm as any,
      userOptions: ref([]),
      userSearchLoading: ref(false),
      loadTaskDetail: vi.fn(async () => {}),
    })
  }

  it('posts USER delegate body', async () => {
    const actions = createActions({
      targetUserId: 'user-b',
      reason: 'leave',
      targetType: 'USER',
    })
    await actions.submitAction()
    expect(delegateTaskMock).toHaveBeenCalledWith('task-1', {
      delegatedTargetType: 'USER',
      delegatedTo: 'user-b',
      reason: 'leave',
    })
  })

  it('posts BU_ROLE delegate body with codes', async () => {
    const actions = createActions({
      targetUserId: '',
      reason: 'coverage',
      targetType: 'BU_ROLE',
      delegatedBuCode: 'HK',
      delegatedRoleCode: 'APPROVER',
    })
    await actions.submitAction()
    expect(delegateTaskMock).toHaveBeenCalledWith('task-1', {
      delegatedTargetType: 'BU_ROLE',
      delegatedBuCode: 'HK',
      delegatedRoleCode: 'APPROVER',
      reason: 'coverage',
    })
  })

  it('warns when BU_ROLE pair is incomplete', async () => {
    const actions = createActions({
      targetUserId: '',
      reason: '',
      targetType: 'BU_ROLE',
      delegatedBuCode: 'HK',
      delegatedRoleCode: '',
    })
    await actions.submitAction()
    expect(delegateTaskMock).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalled()
  })

  it('blocks delegate submit when Require Comment is on and reason is empty', async () => {
    const actions = createActions({
      targetUserId: 'user-b',
      reason: '',
      targetType: 'USER',
    })
    actions.handleDelegate({
      actionId: '1',
      actionName: 'Delegate',
      actionType: 'DELEGATE',
      configJson: '{"requireComment":true}',
    })
    await actions.submitAction()
    expect(delegateTaskMock).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalled()
  })

  it('allows empty reason when Require Comment is off', async () => {
    const actionForm = {
      targetUserId: 'user-b',
      reason: '',
      targetType: 'USER',
    }
    const actions = createActions(actionForm)
    actions.handleDelegate({
      actionId: '1',
      actionName: 'Delegate',
      actionType: 'DELEGATE',
      configJson: '{}',
    })
    actionForm.targetUserId = 'user-b'
    actionForm.targetType = 'USER'
    await actions.submitAction()
    expect(delegateTaskMock).toHaveBeenCalled()
  })
})

describe('useTaskActions submitApprove Require Comment', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('blocks complete when comment is required and empty', async () => {
    const actions = useTaskActions({
      taskId: 'task-1',
      taskInfo: ref({}),
      subTableBindings: ref([]),
      formData: ref({}),
      submitting: ref(false),
      approveDialogVisible: ref(true),
      approveDialogTitle: ref(''),
      currentApproveAction: ref('APPROVE'),
      approveForm: { comment: '' },
      actionDialogVisible: ref(false),
      actionDialogTitle: ref(''),
      currentAction: ref(''),
      actionForm: { targetUserId: '', reason: '' } as never,
      userOptions: ref([]),
      userSearchLoading: ref(false),
      loadTaskDetail: vi.fn(async () => {}),
    })
    actions.approveCommentRequired.value = true
    await actions.submitApprove()
    expect(completeTask).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalled()
  })
})

describe('useTaskActions invalid Action configJson', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('does not open the delegate dialog when configJson is invalid', () => {
    const actionDialogVisible = ref(false)
    const actions = useTaskActions({
      taskId: 'task-1',
      taskInfo: ref({}),
      subTableBindings: ref([]),
      formData: ref({}),
      submitting: ref(false),
      approveDialogVisible: ref(false),
      approveDialogTitle: ref(''),
      currentApproveAction: ref(''),
      approveForm: { comment: '' },
      actionDialogVisible,
      actionDialogTitle: ref(''),
      currentAction: ref(''),
      actionForm: { targetUserId: '', reason: '' } as never,
      userOptions: ref([]),
      userSearchLoading: ref(false),
      loadTaskDetail: vi.fn(async () => {}),
    })
    actions.handleDelegate({
      actionId: '1',
      actionName: 'Delegate',
      actionType: 'DELEGATE',
      configJson: '{',
    })
    expect(actionDialogVisible.value).toBe(false)
    expect(ElMessage.error).toHaveBeenCalled()
  })
})
