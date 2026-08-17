import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { useI18n } from 'vue-i18n'
import { permissionApi, type PermissionRequestRecord } from '@/api/permission'
import { PORTAL_LIST_DEFAULT_PAGE_SIZE } from '@/constants/portalListPagination'

type TFn = ReturnType<typeof useI18n>['t']

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

/** Server list chrome params (filters JSON string + sort + groupBy). */
export type PermissionListLoadOpts = {
  sortField?: string
  sortDirection?: 'ASC' | 'DESC'
  filters?: string
  groupBy?: string
}

/** 「我的申请」：进行中 / 已完成列表，以及取消申请。 */
export function useMyRequests(t: TFn) {
  const myRequestTab = ref('inProgress')
  const loadingPending = ref(false)
  const loadingHistory = ref(false)
  const pendingList = ref<PermissionRequestRecord[]>([])
  const historyList = ref<PermissionRequestRecord[]>([])
  const pendingTotal = ref(0)
  const historyTotal = ref(0)
  const pendingGroupCounts = ref<Record<string, number> | null>(null)
  const historyGroupCounts = ref<Record<string, number> | null>(null)

  const pendingPagination = reactive({
    page: 1,
    size: PORTAL_LIST_DEFAULT_PAGE_SIZE,
  })
  const historyPagination = reactive({
    page: 1,
    size: PORTAL_LIST_DEFAULT_PAGE_SIZE,
  })

  let lastPendingOpts: PermissionListLoadOpts | undefined
  let lastHistoryOpts: PermissionListLoadOpts | undefined

  const loadPendingRequests = async (opts?: PermissionListLoadOpts) => {
    lastPendingOpts = opts
    loadingPending.value = true
    try {
      const res = await permissionApi.getMyRequests({
        status: 'PENDING',
        page: pendingPagination.page - 1,
        size: pendingPagination.size,
        sortField: opts?.sortField,
        sortDirection: opts?.sortDirection,
        filters: opts?.filters,
        groupBy: opts?.groupBy,
      })
      const page = unwrapPage(res)
      pendingList.value = page.content
      pendingTotal.value = page.totalElements
      pendingGroupCounts.value = page.groupCounts && Object.keys(page.groupCounts).length
        ? page.groupCounts
        : null
    } catch (e) {
      console.error('Failed to load pending requests:', e)
      pendingList.value = []
      pendingTotal.value = 0
      pendingGroupCounts.value = null
      ElMessage.error(t('permission.loadFailed'))
    } finally {
      loadingPending.value = false
    }
  }

  /**
   * History: server `excludePending` for true page/total + filters/sort/groupBy.
   */
  const loadHistoryRequests = async (opts?: PermissionListLoadOpts) => {
    lastHistoryOpts = opts
    loadingHistory.value = true
    try {
      const res = await permissionApi.getMyRequests({
        excludePending: true,
        page: historyPagination.page - 1,
        size: historyPagination.size,
        sortField: opts?.sortField,
        sortDirection: opts?.sortDirection,
        filters: opts?.filters,
        groupBy: opts?.groupBy,
      })
      const page = unwrapPage(res)
      // Defense in depth if an older backend ignores excludePending.
      historyList.value = page.content.filter((r) => r.status !== 'PENDING')
      historyTotal.value = page.totalElements
      historyGroupCounts.value = page.groupCounts && Object.keys(page.groupCounts).length
        ? page.groupCounts
        : null
    } catch (e) {
      console.error('Failed to load history requests:', e)
      historyList.value = []
      historyTotal.value = 0
      historyGroupCounts.value = null
      ElMessage.error(t('permission.loadFailed'))
    } finally {
      loadingHistory.value = false
    }
  }

  // 取消申请
  const cancelRequest = async (row: PermissionRequestRecord) => {
    try {
      await ElMessageBox.confirm(t('permission.cancelConfirm'), t('common.warning'), {
        type: 'warning'
      })

      await permissionApi.cancelRequest(row.id)
      ElMessage.success(t('permission.cancelSuccess'))
      const cancelledRecord: PermissionRequestRecord = {
        ...row,
        status: 'CANCELLED',
        updatedAt: new Date().toISOString()
      }
      pendingList.value = pendingList.value.filter(item => item.id !== row.id)
      pendingTotal.value = Math.max(0, pendingTotal.value - 1)
      historyList.value = [cancelledRecord, ...historyList.value.filter(item => item.id !== row.id)]
      historyTotal.value = historyList.value.length
      void loadPendingRequests(lastPendingOpts)
      void loadHistoryRequests(lastHistoryOpts)
    } catch (e: unknown) {
      if (e !== 'cancel') {
        ElMessage.error(t('permission.cancelFailed'))
      }
    }
  }

  return {
    myRequestTab,
    loadingPending,
    loadingHistory,
    pendingList,
    historyList,
    pendingCount: pendingTotal,
    pendingTotal,
    historyTotal,
    pendingGroupCounts,
    historyGroupCounts,
    pendingPagination,
    historyPagination,
    loadPendingRequests,
    loadHistoryRequests,
    cancelRequest
  }
}
