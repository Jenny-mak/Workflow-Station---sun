import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import type { useI18n } from 'vue-i18n'
import { permissionApi, type PermissionRequestRecord } from '@/api/permission'
import { PORTAL_LIST_DEFAULT_PAGE_SIZE } from '@/constants/portalListPagination'
import type { PermissionListLoadOpts } from '@/composables/permissions/useMyRequests'

type TFn = ReturnType<typeof useI18n>['t']

export interface UseApprovalsDeps {
  /** 审批后刷新「我的申请」进行中列表 */
  loadPendingRequests: () => void
  /** 审批后刷新「我的申请」已完成列表 */
  loadHistoryRequests: () => void
  /** 刷新全局待审批计数（pendingApproval store） */
  fetchPendingCount: () => Promise<unknown> | unknown
}

function unwrapPage(res: unknown): {
  content: PermissionRequestRecord[]
  totalElements: number
  groupCounts?: Record<string, number>
} {
  const r = res as {
    data?: {
      content?: PermissionRequestRecord[]
      totalElements?: number
      groupCounts?: Record<string, number>
    }
    content?: PermissionRequestRecord[]
    totalElements?: number
    groupCounts?: Record<string, number>
  }
  if (r?.data?.content) {
    return {
      content: r.data.content,
      totalElements: Number(r.data.totalElements || 0),
      groupCounts: r.data.groupCounts,
    }
  }
  if (r?.content) {
    return {
      content: r.content,
      totalElements: Number(r.totalElements || 0),
      groupCounts: r.groupCounts,
    }
  }
  if (Array.isArray(res)) {
    return { content: res as PermissionRequestRecord[], totalElements: (res as PermissionRequestRecord[]).length }
  }
  return { content: [], totalElements: 0 }
}

/** 审批侧：审批人身份、待审批/审批历史列表、批准/拒绝对话框与操作。 */
export function useApprovals(t: TFn, deps: UseApprovalsDeps) {
  const approvalTab = ref('pendingApproval')
  const isApprover = ref(false)
  const approverPendingList = ref<PermissionRequestRecord[]>([])
  const approverHistoryList = ref<PermissionRequestRecord[]>([])
  const loadingApproverPending = ref(false)
  const loadingApproverHistory = ref(false)
  const approveDialogVisible = ref(false)
  const rejectDialogVisible = ref(false)
  const currentApproverRequest = ref<PermissionRequestRecord | null>(null)
  const approveComment = ref('')
  const rejectComment = ref('')
  const submittingApproval = ref(false)
  const approvalPendingTotal = ref(0)
  const approvalHistoryTotal = ref(0)
  const approvalPendingGroupCounts = ref<Record<string, number> | null>(null)
  const approvalHistoryGroupCounts = ref<Record<string, number> | null>(null)

  const approverPendingPagination = reactive({
    page: 1,
    size: PORTAL_LIST_DEFAULT_PAGE_SIZE,
  })
  const approverHistoryPagination = reactive({
    page: 1,
    size: PORTAL_LIST_DEFAULT_PAGE_SIZE,
  })

  let lastApproverPendingOpts: PermissionListLoadOpts | undefined
  let lastApproverHistoryOpts: PermissionListLoadOpts | undefined

  const checkApproverStatus = async () => {
    try {
      const res = (await permissionApi.isApprover()) as { data?: { isApprover?: boolean }; isApprover?: boolean }
      isApprover.value = res?.data?.isApprover ?? res?.isApprover ?? false
    } catch (e) {
      console.error('Failed to check approver status:', e)
      isApprover.value = false
    }
  }

  const loadApproverPending = async (opts?: PermissionListLoadOpts) => {
    if (!isApprover.value) return
    lastApproverPendingOpts = opts
    loadingApproverPending.value = true
    try {
      const res = await permissionApi.getPendingApprovals({
        page: approverPendingPagination.page - 1,
        size: approverPendingPagination.size,
        sortField: opts?.sortField,
        sortDirection: opts?.sortDirection,
        filters: opts?.filters,
        groupBy: opts?.groupBy,
      })
      const page = unwrapPage(res)
      approverPendingList.value = page.content
      approvalPendingTotal.value = page.totalElements
      approvalPendingGroupCounts.value = page.groupCounts && Object.keys(page.groupCounts).length
        ? page.groupCounts
        : null
    } catch (e) {
      console.error('Failed to load pending approvals:', e)
      approverPendingList.value = []
      approvalPendingTotal.value = 0
      approvalPendingGroupCounts.value = null
      ElMessage.error(t('permission.loadFailed'))
    } finally {
      loadingApproverPending.value = false
    }
  }

  const loadApproverHistory = async (opts?: PermissionListLoadOpts) => {
    if (!isApprover.value) return
    lastApproverHistoryOpts = opts
    loadingApproverHistory.value = true
    try {
      const res = await permissionApi.getApprovalHistory({
        page: approverHistoryPagination.page - 1,
        size: approverHistoryPagination.size,
        sortField: opts?.sortField,
        sortDirection: opts?.sortDirection,
        filters: opts?.filters,
        groupBy: opts?.groupBy,
      })
      const page = unwrapPage(res)
      approverHistoryList.value = page.content
      approvalHistoryTotal.value = page.totalElements
      approvalHistoryGroupCounts.value = page.groupCounts && Object.keys(page.groupCounts).length
        ? page.groupCounts
        : null
    } catch (e) {
      console.error('Failed to load approval history:', e)
      approverHistoryList.value = []
      approvalHistoryTotal.value = 0
      approvalHistoryGroupCounts.value = null
      ElMessage.error(t('permission.loadFailed'))
    } finally {
      loadingApproverHistory.value = false
    }
  }

  const onApprovalTabChange = (tab: string | number) => {
    if (String(tab) === 'approvalHistory') {
      loadApproverHistory(lastApproverHistoryOpts)
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
      await loadApproverPending(lastApproverPendingOpts)
      await deps.fetchPendingCount()
      approverHistoryList.value = []
      deps.loadPendingRequests()
      deps.loadHistoryRequests()
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } }; message?: string }
      const msg = err.response?.data?.message || err.message || t('approval.approveFailed')
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
      await loadApproverPending(lastApproverPendingOpts)
      await deps.fetchPendingCount()
      approverHistoryList.value = []
      deps.loadPendingRequests()
      deps.loadHistoryRequests()
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } }; message?: string }
      const msg = err.response?.data?.message || err.message || t('approval.rejectFailed')
      ElMessage.error(msg)
    } finally {
      submittingApproval.value = false
    }
  }

  return {
    approvalTab,
    isApprover,
    approverPendingList,
    approverHistoryList,
    loadingApproverPending,
    loadingApproverHistory,
    approveDialogVisible,
    rejectDialogVisible,
    currentApproverRequest,
    approveComment,
    rejectComment,
    submittingApproval,
    approvalPendingCount: approvalPendingTotal,
    approvalPendingTotal,
    approvalHistoryTotal,
    approvalPendingGroupCounts,
    approvalHistoryGroupCounts,
    approverPendingPagination,
    approverHistoryPagination,
    checkApproverStatus,
    loadApproverPending,
    loadApproverHistory,
    onApprovalTabChange,
    showApproveDialog,
    showRejectDialog,
    handleApprove,
    handleReject
  }
}
