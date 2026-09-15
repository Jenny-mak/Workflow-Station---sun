import { describe, expect, it, vi, beforeEach } from 'vitest'
import { ref } from 'vue'

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
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
  ElMessageBox: {
    confirm: vi.fn(async () => {}),
  },
}))
vi.mock('../customActionReturnFlows', () => ({
  createCustomActionReturnFlows: () => ({
    handleRollbackAction: vi.fn(),
    handleDraftAction: vi.fn(),
    handleWithdrawAction: vi.fn(),
  }),
}))
vi.mock('../customActionFormPopup', () => ({
  createCustomActionFormPopup: () => ({
    openFormPopup: vi.fn(),
    handleFormPopupSubTableUpdate: vi.fn(),
    submitFormPopup: vi.fn(),
  }),
}))
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCustomActions } from '../useCustomActions'

/**
 * Delegate / Transfer / Urge are Action-driven: the buttons only exist when a
 * DELEGATE / TRANSFER / URGE Action is configured in the Developer Workstation
 * and bound to the user task node, so handleCustomAction must dispatch them.
 */
describe('useCustomActions task-operation dispatch', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  function createComposable(handlers: Record<string, () => void> = {}) {
    return useCustomActions({
      taskInfo: ref({}),
      subTableBindings: ref([]),
      formData: ref({}),
      submitting: ref(false),
      saveCurrentTaskForm: vi.fn(async () => {}),
      validateSubTableAssigneesForComplete: () => true,
      approveDialogVisible: ref(false),
      approveDialogTitle: ref(''),
      currentApproveAction: ref(''),
      approveForm: { comment: '' },
      loadTaskDetail: vi.fn(async () => {}),
      ...handlers,
    })
  }

  const cases = [
    { actionType: 'DELEGATE', handler: 'onDelegate' },
    { actionType: 'TRANSFER', handler: 'onTransfer' },
    { actionType: 'URGE', handler: 'onUrge' },
  ]

  for (const { actionType, handler } of cases) {
    it(`routes a ${actionType} action to ${handler}`, () => {
      const spy = vi.fn()
      const { handleCustomAction } = createComposable({ [handler]: spy })
      handleCustomAction({ actionId: 1, actionName: actionType, actionType } as never)
      expect(spy).toHaveBeenCalledTimes(1)
      expect(ElMessage.warning).not.toHaveBeenCalled()
    })

    it(`warns when a ${actionType} action has no ${handler} host callback`, () => {
      const { handleCustomAction } = createComposable()
      handleCustomAction({ actionId: 1, actionName: actionType, actionType } as never)
      expect(ElMessage.warning).toHaveBeenCalledTimes(1)
    })
  }

  it('leaves unrelated action types on the unknown-type branch', () => {
    const { handleCustomAction } = createComposable({ onUrge: vi.fn() })
    handleCustomAction({ actionId: 1, actionName: 'x', actionType: 'NOT_A_TYPE' } as never)
    expect(ElMessage.warning).toHaveBeenCalledTimes(1)
  })

  it('opens approve dialog without confirm when confirmMessage is empty', async () => {
    const approveDialogVisible = ref(false)
    const approveCommentRequired = ref(false)
    const { handleCustomAction } = useCustomActions({
      taskInfo: ref({}),
      subTableBindings: ref([]),
      formData: ref({}),
      submitting: ref(false),
      saveCurrentTaskForm: vi.fn(async () => {}),
      validateSubTableAssigneesForComplete: () => true,
      approveDialogVisible,
      approveDialogTitle: ref(''),
      currentApproveAction: ref(''),
      approveForm: { comment: '' },
      approveCommentRequired,
      loadTaskDetail: vi.fn(async () => {}),
    })
    handleCustomAction({
      actionId: '1',
      actionName: 'Approve',
      actionType: 'APPROVE',
      configJson: '{"requireComment":true}',
    })
    await Promise.resolve()
    expect(ElMessageBox.confirm).not.toHaveBeenCalled()
    expect(approveDialogVisible.value).toBe(true)
    expect(approveCommentRequired.value).toBe(true)
  })

  it('shows confirmMessage then skips dialog when the user cancels', async () => {
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
    const approveDialogVisible = ref(false)
    const { handleCustomAction } = useCustomActions({
      taskInfo: ref({}),
      subTableBindings: ref([]),
      formData: ref({}),
      submitting: ref(false),
      saveCurrentTaskForm: vi.fn(async () => {}),
      validateSubTableAssigneesForComplete: () => true,
      approveDialogVisible,
      approveDialogTitle: ref(''),
      currentApproveAction: ref(''),
      approveForm: { comment: '' },
      loadTaskDetail: vi.fn(async () => {}),
    })
    handleCustomAction({
      actionId: '1',
      actionName: 'Approve',
      actionType: 'APPROVE',
      configJson: '{"confirmMessage":"Sure?"}',
    })
    await Promise.resolve()
    await Promise.resolve()
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      'Sure?',
      'common.confirm',
      expect.objectContaining({ type: 'warning' }),
    )
    expect(approveDialogVisible.value).toBe(false)
  })

  it('passes the action into onDelegate so Require Comment can be applied', () => {
    const spy = vi.fn()
    const action = {
      actionId: '9',
      actionName: 'Delegate',
      actionType: 'DELEGATE',
      configJson: '{"requireComment":true}',
    }
    const { handleCustomAction } = createComposable({ onDelegate: spy })
    handleCustomAction(action as never)
    expect(spy).toHaveBeenCalledWith(action)
  })

  it('toasts and does not open the approve dialog when configJson is invalid', async () => {
    const approveDialogVisible = ref(false)
    const { handleCustomAction } = useCustomActions({
      taskInfo: ref({}),
      subTableBindings: ref([]),
      formData: ref({}),
      submitting: ref(false),
      saveCurrentTaskForm: vi.fn(async () => {}),
      validateSubTableAssigneesForComplete: () => true,
      approveDialogVisible,
      approveDialogTitle: ref(''),
      currentApproveAction: ref(''),
      approveForm: { comment: '' },
      loadTaskDetail: vi.fn(async () => {}),
    })
    handleCustomAction({
      actionId: '1',
      actionName: 'Approve',
      actionType: 'APPROVE',
      configJson: 'not-json',
    } as never)
    await Promise.resolve()
    expect(ElMessage.error).toHaveBeenCalledWith('task.configParseFailed')
    expect(approveDialogVisible.value).toBe(false)
    expect(ElMessageBox.confirm).not.toHaveBeenCalled()
  })
})
