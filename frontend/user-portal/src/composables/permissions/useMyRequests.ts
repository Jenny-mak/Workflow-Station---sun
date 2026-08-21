import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { useI18n } from 'vue-i18n'
import { permissionApi, type PermissionRequestRecord } from '@/api/permission'

type TFn = ReturnType<typeof useI18n>['t']

export interface UseMyRequestsDeps {
  /** After cancel, reload my-request shared lists (pending + completed). */
  reloadMyLists: () => void
}

/** 「我的申请」：tab 状态与取消申请（列表由 PermissionRequestSharedList 负责）。 */
export function useMyRequests(t: TFn, deps: UseMyRequestsDeps) {
  const myRequestTab = ref('inProgress')

  const cancelRequest = async (row: PermissionRequestRecord) => {
    try {
      await ElMessageBox.confirm(t('permission.cancelConfirm'), t('common.warning'), {
        type: 'warning'
      })

      await permissionApi.cancelRequest(row.id)
      ElMessage.success(t('permission.cancelSuccess'))
      deps.reloadMyLists()
    } catch (e: unknown) {
      if (e !== 'cancel') {
        ElMessage.error(t('permission.cancelFailed'))
      }
    }
  }

  return {
    myRequestTab,
    cancelRequest
  }
}
