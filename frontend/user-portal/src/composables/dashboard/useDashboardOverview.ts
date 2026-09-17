import { ref } from 'vue'
import {
  getDashboardOverview,
  type ProcessOverview,
  type RecentTask,
  type TaskOverview
} from '@/api/dashboard'
import { usePendingTaskStore } from '@/stores/pendingTask'

// 仪表盘概览数据加载：任务 / 流程 / 个人绩效 / 最近任务
export function useDashboardOverview() {
  const pendingTaskStore = usePendingTaskStore()

  const loading = ref(true)
  /**
   * 加载失败必须能被页面看见：以前这里只 console.error，
   * 首页就退化成一屏 0，和「今天真的没事」长得一模一样，
   * 用户会因此漏掉该处理的审批。
   */
  const loadFailed = ref(false)
  /** 数据取回的时刻；后端还压了一层缓存，页面必须说清自己有多新。 */
  const loadedAt = ref<Date | null>(null)

  const taskOverview = ref<TaskOverview>({
    pendingCount: 0,
    claimableCount: 0,
    todoCount: 0,
    overdueCount: 0,
    completedTodayCount: 0,
    avgProcessingHours: 0,
    urgentCount: 0,
    highPriorityCount: 0,
    teamPendingCount: 0,
    teamOverdueCount: 0,
    teamCompletedTodayCount: 0
  })

  const processOverview = ref<ProcessOverview>({
    initiatedCount: 0,
    inProgressCount: 0,
    completedThisMonthCount: 0,
    draftCount: 0,
    approvalRate: 0,
    typeDistribution: {}
  })

  const recentTasks = ref<RecentTask[]>([])

  const loadDashboardData = async () => {
    loading.value = true
    loadFailed.value = false
    try {
      const res = await getDashboardOverview()
      // API 返回格式: { success: true, data: { taskOverview, processOverview, recentTasks } }
      const data = res.data || res
      if (data) {
        taskOverview.value = data.taskOverview || taskOverview.value
        processOverview.value = data.processOverview || processOverview.value
        recentTasks.value = data.recentTasks || []
        if (data.taskOverview != null && typeof data.taskOverview.pendingCount === 'number') {
          pendingTaskStore.syncCountFromListTotal(data.taskOverview.pendingCount)
        }
      }
      loadedAt.value = new Date()
    } catch (error) {
      console.error('Failed to load dashboard data:', error)
      loadFailed.value = true
    } finally {
      loading.value = false
    }
  }

  return {
    loading,
    loadFailed,
    loadedAt,
    taskOverview,
    processOverview,
    recentTasks,
    loadDashboardData
  }
}
