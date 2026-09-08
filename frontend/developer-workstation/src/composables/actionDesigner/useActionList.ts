import { ref, reactive } from 'vue'
import type { Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ActionDefinition } from '@/api/functionUnit'

interface UseActionListOptions {
  functionUnitId: number
  selectedAction: Ref<ActionDefinition | null>
  actionConfig: Record<string, any>
  store: {
    fetchActions: (functionUnitId: number) => Promise<unknown>
    fetchForms: (functionUnitId: number) => Promise<unknown>
    fetchProcess: (functionUnitId: number) => Promise<unknown>
    createAction: (functionUnitId: number, payload: Record<string, unknown>) => Promise<unknown>
    updateAction: (functionUnitId: number, id: number | string, payload: Record<string, unknown>) => Promise<unknown>
    deleteAction: (functionUnitId: number, id: number | string) => Promise<unknown>
  }
  t: (key: string, params?: Record<string, unknown>) => string
  // 注入回调（wrapper 闭包破环，避免与 binding composable 的循环依赖）
  parseActionBindingsFromBpmn: () => void
}

/**
 * 动作列表与编辑器生命周期：列表加载、选择/返回、创建/保存/删除，
 * 以及动作类型的本地化标签。
 */
export function useActionList(options: UseActionListOptions) {
  const { functionUnitId, selectedAction, actionConfig, store, t, parseActionBindingsFromBpmn } = options

  const loading = ref(false)
  const showCreateDialog = ref(false)
  const createForm = reactive({ actionName: '', actionType: 'APPROVE', buttonColor: '', description: '' })

  const actionTypeLabel = (type: string) => {
    const map: Record<string, string> = {
      APPROVE: t('action.approve'),
      REJECT: t('action.reject'),
      TRANSFER: t('action.transfer'),
      DELEGATE: t('action.delegate'),
      URGE: t('action.urge'),
      ROLLBACK: t('action.rollback'),
      WITHDRAW: t('action.withdraw'),
      DRAFT: t('action.draft'),
      SAVE: t('action.saveDraft'),
      PROCESS_SUBMIT: t('action.processSubmit'),
      PROCESS_REJECT: t('action.processReject'),
      COMPOSITE: t('action.composite'),
      API_CALL: t('action.apiCall'),
      FORM_POPUP: t('action.formPopup'),
      CUSTOM_SCRIPT: t('action.customScript')
    }
    return map[type] || type
  }

  async function loadActions() {
    loading.value = true
    try {
      await store.fetchActions(functionUnitId)
      await store.fetchForms(functionUnitId)
      await store.fetchProcess(functionUnitId)
      // 解析BPMN XML获取动作绑定信息
      parseActionBindingsFromBpmn()
    } finally {
      loading.value = false
    }
  }

  function handleSelectAction(row: ActionDefinition) {
    selectedAction.value = { ...row }
  }

  function handleBackToList() {
    selectedAction.value = null
  }

  async function handleCreateAction() {
    try {
      await store.createAction(functionUnitId, {
        actionName: createForm.actionName,
        actionType: createForm.actionType,
        buttonColor: createForm.buttonColor || null,
        description: createForm.description,
        configJson: {}
      })
      ElMessage.success(t('action.createSuccess'))
      showCreateDialog.value = false
      Object.assign(createForm, { actionName: '', actionType: 'APPROVE', buttonColor: '', description: '' })
      loadActions()
    } catch (e: any) {
      ElMessage.error(e.response?.data?.message || t('action.createFailed'))
    }
  }

  async function handleSaveAction() {
    if (!selectedAction.value) return

    try {
      await store.updateAction(functionUnitId, selectedAction.value.id, {
        actionName: selectedAction.value.actionName,
        actionType: selectedAction.value.actionType,
        buttonColor: selectedAction.value.buttonColor || null,
        description: selectedAction.value.description,
        configJson: actionConfig
      })
      ElMessage.success(t('action.saveSuccess'))
      loadActions()
    } catch (e: any) {
      ElMessage.error(e.response?.data?.message || t('action.saveFailed'))
    }
  }

  async function handleDeleteAction(row: ActionDefinition) {
    await ElMessageBox.confirm(t('action.deleteConfirm'), t('action.confirmTitle'), { type: 'warning' })
    try {
      await store.deleteAction(functionUnitId, row.id)
      ElMessage.success(t('action.deleteSuccess'))
      loadActions()
    } catch (e: any) {
      ElMessage.error(e.response?.data?.message || t('action.deleteFailed'))
    }
  }

  return {
    loading,
    showCreateDialog,
    createForm,
    actionTypeLabel,
    loadActions,
    handleSelectAction,
    handleBackToList,
    handleCreateAction,
    handleSaveAction,
    handleDeleteAction,
  }
}
