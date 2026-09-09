import type { Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { getTaskHistory } from '@/api/task'
import { submitProcessFormUpdate } from '@/api/processForm'

export function useTaskDetail(options: {
  taskId: string
  isCompletedTask: Ref<boolean>
  historyRecords: Ref<any[]>
  historyError: Ref<string | null>
  taskInfo: Ref<Record<string, any>>
  processFormValues: Ref<Record<string, any>>
  submitting: Ref<boolean>
  loadTaskDetail: () => Promise<void>
}) {
  const { t } = useI18n()

  async function loadTaskHistory() {
    options.historyError.value = null
    try {
      const res = await getTaskHistory(options.taskId)
      const items = (res as any).data || res || []
      const records = (Array.isArray(items) ? items : items.records || items.content || [])
      const filtered = records.filter((item: any) => {
        if (!options.isCompletedTask.value) return true
        const snapshotTime = (options as any)._snapshotTime?.value
        if (!snapshotTime) return true
        const itemTime = item.createTime || item.startTime
        if (!itemTime) return true
        return new Date(itemTime).getTime() <= new Date(snapshotTime).getTime()
      })
      options.historyRecords.value = filtered.map((item: any) => ({
        id: item.id || '',
        taskId: item.taskId || item.id || '',
        nodeId: item.activityId || item.taskId || '',
        nodeName: item.activityName || item.taskName || item.nodeName || '',
        activityType: item.activityType || '',
        assignee: item.assignee || '',
        operator: item.operator || item.assignee || '',
        operatorName: item.operatorName || item.assigneeName || item.operator || item.assignee || '-',
        action: item.operationType || item.action || '',
        status: item.operationType === 'APPROVE' || item.operationType === 'SUBMIT' ? 'completed' : item.operationType === 'REJECT' ? 'rejected' : 'completed',
        startTime: item.startTime || item.createTime || '',
        endTime: item.endTime || item.createTime || '',
        comment: item.comment || item.reason || '',
        duration: item.durationInMillis || 0
      }))
    } catch {
      options.historyError.value = t('task.historyLoadFailed')
    }
  }

  async function handleProcessFormSubmit() {
    const processInstanceId = options.taskInfo.value.processInstanceId
    if (!processInstanceId) return
    options.submitting.value = true
    try {
      await submitProcessFormUpdate(processInstanceId, options.processFormValues.value)
      ElMessage.success(t('task.operationSuccess'))
      await options.loadTaskDetail()
    } catch (e: any) {
      if (e?.response?.status === 403) {
        ElMessage.warning(t('process.notInReturnState'))
      } else {
        ElMessage.error(t('task.operationFailed'))
      }
    } finally {
      options.submitting.value = false
    }
  }

  return { loadTaskHistory, handleProcessFormSubmit }
}
