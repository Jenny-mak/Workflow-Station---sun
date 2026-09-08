import { describe, expect, it, vi, beforeEach } from 'vitest'
import { ref } from 'vue'
import { PLATFORM_ROW_UUID_FIELD } from '@/utils/subTableRowIdentity'
const pushMock = vi.fn()
const completeTaskMock = vi.fn(async () => ({}))
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
  completeTask: (...args: any[]) => completeTaskMock(...args),
  delegateTask: vi.fn(),
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
import { useTaskActions } from '../useTaskActions'
describe('useTaskActions submitApprove __subTables__ canonicalization', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })
  it('persists only canonical bindingId keys instead of alias keys', async () => {
    const taskActions = useTaskActions({
      taskId: 'task-1',
      taskInfo: ref({}),
      subTableBindings: ref([
        { bindingId: 69, tableName: 'participants', columns: [{ fieldName: 'assignee_user_id' }], data: [{ id: 1 }] },
        { bindingId: 30, tableName: 'subtable2', columns: [], data: [{ id: 2 }] },
      ]),
      formData: ref({
        fieldA: 'x',
        __subTables__: {
          'dw:participants': [{ id: 1 }],
          participants: [{ id: 1 }],
          subtable2: [{ id: 2 }],
        },
      }),
      submitting: ref(false),
      approveDialogVisible: ref(true),
      approveDialogTitle: ref(''),
      currentApproveAction: ref('APPROVE'),
      approveForm: { comment: '' },
      actionDialogVisible: ref(false),
      actionDialogTitle: ref(''),
      currentAction: ref(''),
      actionForm: { targetUserId: '', reason: '' },
      userOptions: ref([]),
      userSearchLoading: ref(false),
      loadTaskDetail: vi.fn(async () => {}),
    })
    await taskActions.submitApprove()
    expect(completeTaskMock).toHaveBeenCalledTimes(1)
    const payload = completeTaskMock.mock.calls[0]?.[1]
    const subTables = payload?.formData?.__subTables__ ?? {}
    expect(Object.keys(subTables).sort()).toEqual(['dw:participants', 'dw:subtable2'])
    expect(subTables.participants).toBeUndefined()
    expect(subTables.subtable2).toBeUndefined()
    expect(Object.keys(payload?.variables?.__subTables__ ?? {}).sort()).toEqual(['dw:participants', 'dw:subtable2'])
  })
  it('stamps the platform row uuid on anonymous canonical rows and leaves alias copies out', async () => {
    const anonymous = { channel: 'Email' }
    const taskActions = useTaskActions({
      taskId: 'task-1',
      taskInfo: ref({}),
      subTableBindings: ref([
        { bindingId: 1301, tableName: 'ACQ Correspondence', columns: [], data: [anonymous] },
      ]),
      formData: ref({
        __subTables__: {
          'dw:acq correspondence': [anonymous],
          'ACQ Correspondence': [{ channel: 'Email' }],
        },
      }),
      submitting: ref(false),
      approveDialogVisible: ref(true),
      approveDialogTitle: ref(''),
      currentApproveAction: ref('APPROVE'),
      approveForm: { comment: '' },
      actionDialogVisible: ref(false),
      actionDialogTitle: ref(''),
      currentAction: ref(''),
      actionForm: { targetUserId: '', reason: '' },
      userOptions: ref([]),
      userSearchLoading: ref(false),
      loadTaskDetail: vi.fn(async () => {}),
    })
    await taskActions.submitApprove()
    const payload = completeTaskMock.mock.calls[0]?.[1]
    const subTables = payload?.variables?.__subTables__ ?? {}
    expect(Object.keys(subTables)).toEqual(['dw:acq correspondence'])
    expect(String(subTables['dw:acq correspondence'][0][PLATFORM_ROW_UUID_FIELD])).not.toBe('')
    expect(subTables['ACQ Correspondence']).toBeUndefined()
  })
  it('uses buildFormPayloadForComplete when provided (Save parity path)', async () => {
    const buildFormPayloadForComplete = vi.fn(() => ({
      fieldA: 'x',
      __subTables__: {
        'dw:participants': [{ id: 1 }],
        participants: [{ id: 1 }],
      },
    }))
    const prepareBeforeComplete = vi.fn(async () => {})
    const taskActions = useTaskActions({
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
      actionForm: { targetUserId: '', reason: '' },
      userOptions: ref([]),
      userSearchLoading: ref(false),
      loadTaskDetail: vi.fn(async () => {}),
      prepareBeforeComplete,
      buildFormPayloadForComplete,
    })
    await taskActions.submitApprove()
    expect(prepareBeforeComplete).toHaveBeenCalledTimes(1)
    expect(buildFormPayloadForComplete).toHaveBeenCalledTimes(1)
    const payload = completeTaskMock.mock.calls[0]?.[1]
    expect(payload?.formData?.__subTables__).toBeUndefined()
    expect(Object.keys(payload?.variables?.__subTables__ ?? {}).sort()).toEqual(['dw:participants'])
  })
  it('submits only current editable bindings while preserving engine-only slices', async () => {
    const taskActions = useTaskActions({
      taskId: 'task-1',
      taskInfo: ref({}),
      subTableBindings: ref([
        { bindingId: 69, tableName: 'participants', bindingMode: 'EDITABLE', data: [] },
        { bindingId: 70, tableName: 'other_a', bindingMode: 'READONLY', data: [{ id: 2 }] },
      ]),
      formData: ref({}),
      submitting: ref(false),
      approveDialogVisible: ref(true),
      approveDialogTitle: ref(''),
      currentApproveAction: ref('APPROVE'),
      approveForm: { comment: '' },
      actionDialogVisible: ref(false),
      actionDialogTitle: ref(''),
      currentAction: ref(''),
      actionForm: { targetUserId: '', reason: '' },
      userOptions: ref([]),
      userSearchLoading: ref(false),
      loadTaskDetail: vi.fn(async () => {}),
      buildFormPayloadForComplete: () => ({
        __subTables__: {
          'dw:participants': [],
          'dw:other_a': [{ id: 2 }],
          'dw:other_b': [{ id: 3 }],
        },
      }),
    })
    await taskActions.submitApprove()
    const payload = completeTaskMock.mock.calls[0]?.[1]
    expect(payload?.formData?.__subTables__).toEqual({ 'dw:participants': [] })
    expect(Object.keys(payload?.variables?.__subTables__ ?? {}).sort())
      .toEqual(['dw:other_a', 'dw:other_b', 'dw:participants'])
  })
  it('does not submit sub-table audit intent for a read-only form', async () => {
    const taskActions = useTaskActions({
      taskId: 'task-1',
      taskInfo: ref({}),
      subTableBindings: ref([{ bindingId: 69, bindingMode: 'EDITABLE', data: [{ id: 1 }] }]),
      formData: ref({}),
      formReadOnly: ref(true),
      submitting: ref(false),
      approveDialogVisible: ref(true),
      approveDialogTitle: ref(''),
      currentApproveAction: ref('APPROVE'),
      approveForm: { comment: '' },
      actionDialogVisible: ref(false),
      actionDialogTitle: ref(''),
      currentAction: ref(''),
      actionForm: { targetUserId: '', reason: '' },
      userOptions: ref([]),
      userSearchLoading: ref(false),
      loadTaskDetail: vi.fn(async () => {}),
      buildFormPayloadForComplete: () => ({ __subTables__: { 'dw:participants': [{ id: 1 }] } }),
    })
    await taskActions.submitApprove()
    const payload = completeTaskMock.mock.calls[0]?.[1]
    expect(payload?.formData?.__subTables__).toBeUndefined()
    // `id` is a business column, not an identity: no binding declared it as the primary key here,
    // so the row is anonymous and receives the platform's generated key. Asserting the row is
    // unchanged would be asserting that a name match counts as identity.
    const participants = payload?.variables?.__subTables__?.['dw:participants']
    expect(participants).toHaveLength(1)
    expect(participants[0].id).toBe(1)
    expect(String(participants[0][PLATFORM_ROW_UUID_FIELD] ?? '')).not.toBe('')
  })
})