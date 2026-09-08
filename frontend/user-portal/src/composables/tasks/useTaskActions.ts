import { unref, type MaybeRef, type Ref } from 'vue'
import { writeSubTableRows, subTableStoreKey, isCanonicalStoreKey } from './subTableStore'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { completeTask, delegateTask, transferTask, urgeTask } from '@/api/task'
import { userApi, type UserOption } from '@/api/user'
import {
  resolveAssigneeFieldForBinding,
  allSubTableRowsHaveAssignee
} from '@/utils/subTableAssignment'
import {
  hasMiAssignmentMarker,
  isAssignmentConfigured,
} from '@/utils/miAssignmentConfig'
import { ensureSubTableMapIdentities } from '@/utils/subTableRowIdentity'
import { warnIfUploadsBlocking } from '@platform-shared/upload/uploadSubmitGate'
function resolveProcessTaskId(source: MaybeRef<string>): string {
  const v = unref(source)
  return typeof v === 'string' ? v.trim() : ''
}
function isDigitsKey(key: string): boolean {
  return /^\d+$/.test(key)
}
/**
 * 每个 slice key 对应的设计器主键列（来自 binding.primaryKeyFields）。
 *
 * <p>身份判定必须读配置：一张以 `correspondence_id` 为主键的表，不匹配任何「像主键的列名」名单。
 * 有了它，已带主键值的行不会被再盖一个平台 UUID。
 */
function primaryKeyFieldsBySliceKey(
  bindings: any[] | null | undefined,
): Record<string, readonly string[] | undefined> {
  const out: Record<string, readonly string[] | undefined> = {}
  for (const b of bindings ?? []) {
    const key = subTableStoreKey(b)
    if (key) out[key] = b?.primaryKeyFields ?? undefined
  }
  return out
}

/**
 * Canonicalize __subTables__ slices for persistence:
 * if numeric bindingId keys exist, keep only numeric keys to avoid alias fan-out.
 * Stamp row identity on the kept slices so deserialized alias copies cannot
 * receive a second UUID after submit.
 */
function canonicalizeSubTablesForSubmit(
  input: Record<string, any>,
  bindings?: any[] | null,
): Record<string, any> {
  const keys = Object.keys(input)
  if (keys.length === 0) return {}
  // 规范 key 优先：dw:/rt: 不是数字，若沿用「有数字就只留数字」会把真实数据丢掉。
  const hasCanonical = keys.some(isCanonicalStoreKey)
  const hasNumeric = keys.some(isDigitsKey)
  const out: Record<string, any> = {}
  for (const k of keys) {
    const keep = hasCanonical ? isCanonicalStoreKey(k) : (!hasNumeric || isDigitsKey(k))
    if (keep) out[k] = input[k]
  }
  ensureSubTableMapIdentities(out, primaryKeyFieldsBySliceKey(bindings))
  return out
}
export function useTaskActions(options: {
  /** Flowable task id (prefer backend detail {@link TaskInfo.taskId}, not only route param). */
  taskId: MaybeRef<string>
  taskInfo: Ref<Record<string, any>>
  subTableBindings: Ref<any[]>
  formData: Ref<Record<string, any>>
  /** Whether the current Task Form is entirely read-only. */
  formReadOnly?: MaybeRef<boolean>
  submitting: Ref<boolean>
  approveDialogVisible: Ref<boolean>
  approveDialogTitle: Ref<string>
  currentApproveAction: Ref<string>
  approveForm: { comment: string }
  actionDialogVisible: Ref<boolean>
  actionDialogTitle: Ref<string>
  currentAction: Ref<string>
  actionForm: {
    targetUserId: string
    reason: string
    targetType?: 'USER' | 'BU_ROLE'
    delegatedBuId?: string
    delegatedBuCode?: string
    delegatedRoleCode?: string
  }
  userOptions: Ref<UserOption[]>
  userSearchLoading: Ref<boolean>
  loadTaskDetail: () => Promise<void>
  /** Portal form validation before approve/submit (Element Plus + designer rules). */
  validateTaskForm?: () => Promise<boolean>
  /** MI Save parity: seed FK / merge participant scalars before complete. */
  prepareBeforeComplete?: () => Promise<void>
  /**
   * Build Task Form payload for complete (clone rows, flatten nested __subTables__, MI scrub).
   * When omitted, falls back to legacy merge from formData + bindings (tests only).
   */
  buildFormPayloadForComplete?: () => Record<string, any>
}) {
  const { t } = useI18n()
  const router = useRouter()
  function validateSubTableAssigneesForComplete(): boolean {
    for (const b of options.subTableBindings.value) {
      const hasMarker = hasMiAssignmentMarker(b.formFields)
      if (b.assignmentConfig && !hasMarker) continue
      const config =
        hasMarker && isAssignmentConfigured(b.assignmentConfig)
          ? b.assignmentConfig
          : undefined
      const af = config?.assigneeField
        ?? resolveAssigneeFieldForBinding(b as never)
      if (!af && !config) continue
      if (!allSubTableRowsHaveAssignee(b.data || [], af ?? '', config)) {
        ElMessage.warning(t('task.allParticipantsMustHaveAssignee'))
        return false
      }
    }
    return true
  }
  async function searchUsers(keyword: string) {
    options.userSearchLoading.value = true
    try {
      const data = await userApi.searchUsers(keyword)
      options.userOptions.value = (data || []) as UserOption[]
    } catch (error) {
      console.error('Failed to search users:', error)
    } finally {
      options.userSearchLoading.value = false
    }
  }
  function onActionDialogOpened() {
    if (options.currentAction.value === 'transfer') {
      searchUsers('')
    }
  }
  function handleApprove() {
    if (!validateSubTableAssigneesForComplete()) return
    options.currentApproveAction.value = 'APPROVE'
    options.approveDialogTitle.value = t('task.approve')
    options.approveForm.comment = ''
    options.approveDialogVisible.value = true
  }
  function handleReject() {
    options.currentApproveAction.value = 'REJECT'
    options.approveDialogTitle.value = t('task.reject')
    options.approveForm.comment = ''
    options.approveDialogVisible.value = true
  }
  function handleDelegate() {
    options.currentAction.value = 'delegate'
    options.actionDialogTitle.value = t('task.delegate')
    options.actionForm.targetUserId = ''
    options.actionForm.reason = ''
    options.actionForm.targetType = 'USER'
    options.actionForm.delegatedBuId = ''
    options.actionForm.delegatedBuCode = ''
    options.actionForm.delegatedRoleCode = ''
    options.userOptions.value = []
    options.actionDialogVisible.value = true
  }
  function handleTransfer() {
    options.currentAction.value = 'transfer'
    options.actionDialogTitle.value = t('task.transfer')
    options.actionForm.targetUserId = ''
    options.actionForm.reason = ''
    options.userOptions.value = []
    options.actionDialogVisible.value = true
  }
  function handleUrge() {
    options.currentAction.value = 'urge'
    options.actionDialogTitle.value = t('task.urge')
    options.actionForm.reason = ''
    options.actionDialogVisible.value = true
  }
  function buildLegacyCompleteFormData(): Record<string, any> {
    const currentFormData: Record<string, any> = {}
    for (const key of Object.keys(options.formData.value)) {
      if (!key.startsWith('__')) {
        currentFormData[key] = options.formData.value[key]
      }
    }
    const mergedSub: Record<string, any> = canonicalizeSubTablesForSubmit(
      { ...(options.formData.value.__subTables__ || {}) },
      options.subTableBindings.value,
    )
    // One canonical key per designer table (`dw:<name>` / `rt:<name>`). Writing per-binding keys
    // here would reintroduce the divergence this structure exists to prevent: Approve/Complete goes
    // through the same `__subTables__` the backend row-merges, so a second copy of a row under a
    // binding key could win over the edited one.
    //
    // The former special case for a "participants" binding is gone with it — it existed only to
    // re-stamp that binding's own key after the loop, and it identified the table by the literal
    // name `participants`, which is not how the MI collection table is configured (Sub-Task Config
    // names it) and misses any table called something else.
    for (const b of options.subTableBindings.value) {
      writeSubTableRows(mergedSub, b, Array.isArray(b.data) ? b.data : [])
    }
    currentFormData.__subTables__ = mergedSub
    return currentFormData
  }
  function buildUserSubmittedFormData(engineFormData: Record<string, any>): Record<string, any> {
    const submitted: Record<string, any> = { ...engineFormData }
    delete submitted.__subTables__
    if (unref(options.formReadOnly ?? false)) return submitted
    const engineSubTables = (engineFormData.__subTables__ as Record<string, any>) || {}
    const submittedSubTables: Record<string, any> = {}
    for (const binding of options.subTableBindings.value) {
      if (binding?.bindingMode === 'READONLY') continue
      const key = subTableStoreKey(binding)
      if (key && Object.prototype.hasOwnProperty.call(engineSubTables, key)) {
        // Keep []: an empty current editable grid is an explicit delete-all submission.
        submittedSubTables[key] = engineSubTables[key]
      }
    }
    if (Object.keys(submittedSubTables).length > 0) {
      submitted.__subTables__ = submittedSubTables
    }
    return submitted
  }
  async function submitApprove() {
    if (options.currentApproveAction.value === 'APPROVE' && !validateSubTableAssigneesForComplete()) return
    if (options.currentApproveAction.value === 'APPROVE' && options.validateTaskForm) {
      const valid = await options.validateTaskForm()
      if (!valid) {
        ElMessage.warning(t('processStart.pleaseCompleteForm'))
        return
      }
    }
    if (!warnIfUploadsBlocking({
      inflight: t('upload.waitUntilComplete'),
      failed: t('upload.fixFailedBeforeSubmit'),
    })) return
    options.submitting.value = true
    try {
      if (options.prepareBeforeComplete) {
        await options.prepareBeforeComplete()
      }
      const variables: Record<string, any> = {}
      if (options.currentApproveAction.value === 'APPROVE') {
        variables.approval_result = 'approved'
        variables.approved = true
      } else if (options.currentApproveAction.value === 'REJECT') {
        variables.approval_result = 'rejected'
        variables.approved = false
      }
      if (options.approveForm.comment) {
        variables.approval_comment = options.approveForm.comment
      }
      const built = options.buildFormPayloadForComplete
        ? options.buildFormPayloadForComplete()
        : buildLegacyCompleteFormData()
      const engineFormData: Record<string, any> = { ...built }
      engineFormData.__subTables__ = canonicalizeSubTablesForSubmit(
        (built.__subTables__ as Record<string, any>) || {},
        options.subTableBindings.value,
      )
      const submittedFormData = buildUserSubmittedFormData(engineFormData)
      Object.assign(variables, engineFormData)
      const pid = resolveProcessTaskId(options.taskId)
      if (!pid) {
        ElMessage.warning(t('task.operationFailed'))
        return
      }
      await completeTask(pid, {
        taskId: pid,
        action: options.currentApproveAction.value,
        comment: options.approveForm.comment,
        variables,
        formData: submittedFormData
      })
      ElMessage.success(t('task.operationSuccess'))
      options.approveDialogVisible.value = false
      router.push('/tasks')
    } catch (e) {
      console.error('submitApprove failed:', e)
      // Axios interceptor already showed ApiResponse / HTTP error body; avoid duplicate generic toast.
      if (!(e as { response?: unknown })?.response) {
        ElMessage.error(t('task.operationFailed'))
      }
    } finally {
      options.submitting.value = false
    }
  }
  async function submitAction() {
    if (options.currentAction.value === 'delegate') {
      const targetType = options.actionForm.targetType || 'USER'
      if (targetType === 'BU_ROLE') {
        if (!options.actionForm.delegatedBuCode || !options.actionForm.delegatedRoleCode) {
          ElMessage.warning(t('task.selectBuAndRole'))
          return
        }
      } else if (!options.actionForm.targetUserId) {
        ElMessage.warning(t('task.selectUser'))
        return
      }
    } else if (options.currentAction.value !== 'urge' && !options.actionForm.targetUserId) {
      ElMessage.warning(t('task.selectUser'))
      return
    }
    options.submitting.value = true
    try {
      const pid = resolveProcessTaskId(options.taskId)
      if (options.currentAction.value === 'delegate') {
        const targetType = options.actionForm.targetType || 'USER'
        if (targetType === 'BU_ROLE') {
          await delegateTask(pid, {
            delegatedTargetType: 'BU_ROLE',
            delegatedBuCode: options.actionForm.delegatedBuCode,
            delegatedRoleCode: options.actionForm.delegatedRoleCode,
            reason: options.actionForm.reason
          })
        } else {
          await delegateTask(pid, {
            delegatedTargetType: 'USER',
            delegatedTo: String(options.actionForm.targetUserId),
            reason: options.actionForm.reason
          })
        }
        ElMessage.success(t('task.delegateSuccess'))
      } else if (options.currentAction.value === 'transfer') {
        await transferTask(pid, options.actionForm.targetUserId, options.actionForm.reason)
        ElMessage.success(t('task.transferSuccess'))
      } else if (options.currentAction.value === 'urge') {
        await urgeTask(pid, options.actionForm.reason)
        ElMessage.success(t('task.urgeSuccess'))
      }
      options.actionDialogVisible.value = false
      if (options.currentAction.value === 'transfer') {
        router.push('/tasks')
      } else {
        options.loadTaskDetail()
      }
    } catch (e) {
      console.error('submitAction failed:', e)
    } finally {
      options.submitting.value = false
    }
  }
  return {
    validateSubTableAssigneesForComplete,
    searchUsers,
    onActionDialogOpened,
    handleApprove,
    handleReject,
    handleDelegate,
    handleTransfer,
    handleUrge,
    submitApprove,
    submitAction
  }
}