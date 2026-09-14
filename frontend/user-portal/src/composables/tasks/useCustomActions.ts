import { ref, type Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import type { TaskActionInfo } from '@/api/task'
import type { FormField, FormTab, PortalViewContext } from '@/components/formRendererHelpers'
import { createCustomActionReturnFlows } from './customActionReturnFlows'
import { createCustomActionFormPopup } from './customActionFormPopup'
import type { PreparedFormPopupContext } from './customActionTypes'
import {
  actionConfirmMessage,
  actionRequiresComment,
  parseActionConfigJson,
} from '@/utils/actionButtonConfig'

export type { PreparedFormPopupContext } from './customActionTypes'

export function useCustomActions(options: {
  taskInfo: Ref<Record<string, any>>
  subTableBindings: Ref<any[]>
  formData: Ref<Record<string, any>>
  submitting: Ref<boolean>
  saveCurrentTaskForm: () => Promise<void>
  validateSubTableAssigneesForComplete: () => boolean
  approveDialogVisible: Ref<boolean>
  approveDialogTitle: Ref<string>
  currentApproveAction: Ref<string>
  approveForm: { comment: string }
  approveCommentRequired?: Ref<boolean>
  loadTaskDetail: () => Promise<void>
  /**
   * Resolve the popup's target form content (with tableBindings) from the host's
   * cached function-unit content. Returning null lets the composable surface a
   * "form not found" message without re-fetching.
   */
  resolveFormPopupContent?: (action: TaskActionInfo, config: any) => any | null
  /**
   * Build a FormRenderer-ready context (fields/tabs/subTableBindings/...) from
   * the popup form content + configJson. Implementations should reuse the host's
   * deriveColumnsFromBinding / resolveSubFormDesign / mergeLinkFormTargetBindingsInto
   * helpers so popup rendering matches the host's main-form rendering exactly.
   * Returning null leaves popup state empty (callers should surface an error).
   */
  preparePopupContext?: (formContent: any, formConfig: Record<string, unknown>) => PreparedFormPopupContext | null
  /** Open the Delegate dialog for a DELEGATE Action bound to this task node. */
  onDelegate?: (action: TaskActionInfo) => void
  /** Open the Transfer dialog for a TRANSFER Action bound to this task node. */
  onTransfer?: (action: TaskActionInfo) => void
  /** Open the Urge dialog for an URGE Action bound to this task node. */
  onUrge?: () => void
}) {
  const { t } = useI18n()
  const router = useRouter()

  // Form popup state
  const formPopupVisible = ref(false)
  const formPopupTitle = ref('')
  const formPopupFields = ref<FormField[]>([])
  const formPopupTabs = ref<FormTab[]>([])
  const formPopupData = ref<Record<string, any>>({})
  const formPopupReadOnly = ref(false)
  const formPopupWidth = ref('800px')
  // 'auto'：EP 取最长 label 宽度，弹窗内各行输入框左对齐
  const formPopupLabelWidth = ref('auto')
  const formPopupReadOnlyMode = ref(false)
  const currentFormPopupAction = ref<TaskActionInfo | null>(null)
  /**
   * Sub-table bindings prepared for the popup form by the host (via
   * preparePopupContext); FormRenderer needs these to resolve subTable widgets
   * declared in the popup's canvas rule.
   */
  const formPopupSubTableBindings = ref<any[]>([])
  const formPopupLinkedSubTableBindings = ref<any[] | null>(null)
  const formPopupNativeSubTableBindingIds = ref<number[]>([])
  const formPopupFormConfig = ref<Record<string, unknown>>({})
  const formPopupViewContext = ref<PortalViewContext>('assigneeTodo')

  // Return-style flows (ROLLBACK / DRAFT / WITHDRAW) — delegated, behavior unchanged.
  const { handleRollbackAction, handleDraftAction, handleWithdrawAction } = createCustomActionReturnFlows({
    t,
    router,
    taskInfo: options.taskInfo,
    submitting: options.submitting,
  })

  // FORM_POPUP open / submit / sub-table-update — delegated, behavior unchanged.
  const { openFormPopup, handleFormPopupSubTableUpdate, submitFormPopup } = createCustomActionFormPopup({
    t,
    taskInfo: options.taskInfo,
    submitting: options.submitting,
    loadTaskDetail: options.loadTaskDetail,
    resolveFormPopupContent: options.resolveFormPopupContent,
    preparePopupContext: options.preparePopupContext,
    formPopupVisible,
    formPopupTitle,
    formPopupFields,
    formPopupTabs,
    formPopupData,
    formPopupWidth,
    formPopupReadOnlyMode,
    currentFormPopupAction,
    formPopupSubTableBindings,
    formPopupLinkedSubTableBindings,
    formPopupNativeSubTableBindingIds,
    formPopupFormConfig,
    formPopupViewContext,
  })

  const fallbackApproveCommentRequired = ref(false)
  const approveCommentRequired = options.approveCommentRequired ?? fallbackApproveCommentRequired

  async function confirmIfNeeded(config: Record<string, unknown>): Promise<boolean> {
    const msg = actionConfirmMessage(config)
    if (!msg) return true
    try {
      await ElMessageBox.confirm(msg, t('common.confirm'), { type: 'warning' })
      return true
    } catch {
      return false
    }
  }

  async function openApproveDialog(action: TaskActionInfo, completeAction: 'APPROVE' | 'REJECT') {
    const config = parseActionConfigJson(action.configJson)
    if (!(await confirmIfNeeded(config))) return
    if (completeAction === 'APPROVE' && !options.validateSubTableAssigneesForComplete()) return
    approveCommentRequired.value = actionRequiresComment(config)
    options.currentApproveAction.value = completeAction
    options.approveDialogTitle.value = action.actionName
    options.approveForm.comment = ''
    options.approveDialogVisible.value = true
  }

  function handleCustomAction(action: TaskActionInfo) {
    const actionType = (action.actionType || '').trim().toUpperCase()
    switch (actionType) {
      case 'SAVE':
        options.saveCurrentTaskForm()
        break
      case 'APPROVE':
      case 'PROCESS_SUBMIT':
        void openApproveDialog(action, 'APPROVE')
        break
      case 'REJECT':
      case 'PROCESS_REJECT':
        void openApproveDialog(action, 'REJECT')
        break
      case 'FORM_POPUP':
        try {
          const config = action.configJson ? JSON.parse(action.configJson) : {}
          openFormPopup(action, config)
        } catch {
          ElMessage.error(t('task.configParseFailed'))
        }
        break
      case 'ROLLBACK':
        handleRollbackAction(action)
        break
      case 'DRAFT':
        handleDraftAction(action)
        break
      case 'WITHDRAW':
        handleWithdrawAction(action)
        break
      case 'DELEGATE':
        if (options.onDelegate) {
          options.onDelegate(action)
        } else {
          ElMessage.warning(t('task.unknownActionType', { type: action.actionType }))
        }
        break
      case 'TRANSFER':
        if (options.onTransfer) {
          options.onTransfer(action)
        } else {
          ElMessage.warning(t('task.unknownActionType', { type: action.actionType }))
        }
        break
      case 'URGE':
        if (options.onUrge) {
          options.onUrge()
        } else {
          ElMessage.warning(t('task.unknownActionType', { type: action.actionType }))
        }
        break
      default:
        ElMessage.warning(t('task.unknownActionType', { type: action.actionType }))
    }
  }

  return {
    formPopupVisible,
    formPopupTitle,
    formPopupFields,
    formPopupTabs,
    formPopupData,
    formPopupReadOnly,
    formPopupWidth,
    formPopupLabelWidth,
    formPopupSubTableBindings,
    formPopupLinkedSubTableBindings,
    formPopupNativeSubTableBindingIds,
    formPopupFormConfig,
    formPopupViewContext,
    currentFormPopupAction,
    handleCustomAction,
    openFormPopup,
    submitFormPopup,
    handleFormPopupSubTableUpdate,
  }
}
