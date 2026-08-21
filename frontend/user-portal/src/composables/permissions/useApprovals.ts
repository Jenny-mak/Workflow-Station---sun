import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { useI18n } from 'vue-i18n'
import { permissionApi, type PermissionRequestRecord } from '@/api/permission'

type TFn = ReturnType<typeof useI18n>['t']

export interface UseApprovalsDeps {
  /** 审批后刷新「我的申请」列表 */
  reloadMyLists: () => void
  /** 审批后刷新审批侧列表 */
  reloadApprovalLists: () => void
  /** 刷新全局待审批计数（pendingApproval store） */
  fetchPendingCount: () => Promise<unknown> | unknown
}

/** 审批侧：审批人身份、批准/拒绝对话框与操作（列表由 PermissionRequestSharedList 负责）。 */
export function useApprovals(t: TFn, deps: UseApprovalsDeps) {
  const approvalTab = ref('pendingApproval')
  const isApprover = ref(false)
  const approveDialogVisible = ref(false)
  const rejectDialogVisible = ref(false)
  const currentApproverRequest = ref<PermissionRequestRecord | null>(null)
  const approveComment = ref('')
  const rejectComment = ref('')
  const submittingApproval = ref(false)

  const checkApproverStatus = async () => {
    try {
      const res = (await permissionApi.isApprover()) as { data?: { isApprover?: boolean }; isApprover?: boolean }
      isApprover.value = res?.data?.isApprover ?? res?.isApprover ?? false
    } catch (e) {
      console.error('Failed to check approver status:', e)
      isApprover.value = false
    }
  }

  const showApproveDialog = (row: PermissionRequestRecord) => {
    currentApproverRequest.value = row
    approveComment.value = ''
    approveDialogVisible.value = true
  }

  const showRejectDialog = (row: PermissionRequestRecord) => {
    currentApproverRequest.value = row
    rejectComment.value = ''
    rejectDialogVisible.value = true
  }

  const handleApprove = async () => {
    if (!currentApproverRequest.value) return
    submittingApproval.value = true
    try {
      await permissionApi.approveRequest(currentApproverRequest.value.id, approveComment.value || undefined)
      ElMessage.success(t('approval.approveSuccess'))
      approveDialogVisible.value = false
      deps.reloadApprovalLists()
      await deps.fetchPendingCount()
      deps.reloadMyLists()
    } catch (e: any) {
      const msg = e.response?.data?.message || e.message || t('approval.approveFailed')
      ElMessage.error(msg)
    } finally {
      submittingApproval.value = false
    }
  }

  const handleReject = async () => {
    if (!currentApproverRequest.value) return
    if (!rejectComment.value.trim()) {
      ElMessage.warning(t('approval.rejectReasonRequired'))
      return
    }
    submittingApproval.value = true
    try {
      await permissionApi.rejectRequest(currentApproverRequest.value.id, rejectComment.value)
      ElMessage.success(t('approval.rejectSuccess'))
      rejectDialogVisible.value = false
      deps.reloadApprovalLists()
      await deps.fetchPendingCount()
      deps.reloadMyLists()
    } catch (e: any) {
      const msg = e.response?.data?.message || e.message || t('approval.rejectFailed')
      ElMessage.error(msg)
    } finally {
      submittingApproval.value = false
    }
  }

  return {
    approvalTab,
    isApprover,
    approveDialogVisible,
    rejectDialogVisible,
    currentApproverRequest,
    approveComment,
    rejectComment,
    submittingApproval,
    checkApproverStatus,
    showApproveDialog,
    showRejectDialog,
    handleApprove,
    handleReject
  }
}
