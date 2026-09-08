import type { Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { processApi } from '@/api/process'
import { submitActionFormPopup } from '@/api/processForm'
import { warnIfUploadsBlocking } from '@platform-shared/upload/uploadSubmitGate'
import type { TaskActionInfo } from '@/api/task'
import type { FormField, FormTab, PortalViewContext } from '@/components/formRendererHelpers'
import type { PreparedFormPopupContext } from './customActionTypes'

/**
 * FORM_POPUP open / submit / sub-table-update handlers extracted verbatim from
 * useCustomActions. Built as a factory so the host composable injects its popup
 * state refs and host callbacks; behavior is unchanged. Form content is loaded
 * via the same processApi wrapper as before.
 */
export function createCustomActionFormPopup(deps: {
  t: (key: string, named?: Record<string, unknown>) => string
  taskInfo: Ref<Record<string, any>>
  submitting: Ref<boolean>
  loadTaskDetail: () => Promise<void>
  resolveFormPopupContent?: (action: TaskActionInfo, config: any) => any | null
  preparePopupContext?: (formContent: any, formConfig: Record<string, unknown>) => PreparedFormPopupContext | null
  formPopupVisible: Ref<boolean>
  formPopupTitle: Ref<string>
  formPopupFields: Ref<FormField[]>
  formPopupTabs: Ref<FormTab[]>
  formPopupData: Ref<Record<string, any>>
  formPopupWidth: Ref<string>
  formPopupReadOnlyMode: Ref<boolean>
  currentFormPopupAction: Ref<TaskActionInfo | null>
  formPopupSubTableBindings: Ref<any[]>
  formPopupLinkedSubTableBindings: Ref<any[] | null>
  formPopupNativeSubTableBindingIds: Ref<number[]>
  formPopupFormConfig: Ref<Record<string, unknown>>
  formPopupViewContext: Ref<PortalViewContext>
}) {
  const {
    t,
    taskInfo,
    submitting,
    loadTaskDetail,
    resolveFormPopupContent,
    preparePopupContext,
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
  } = deps

  /**
   * Open the FORM_POPUP dialog with parity to the Designer Form Preview:
   *  1) Resolve the target form content (with tableBindings) via the host-provided
   *     `resolveFormPopupContent` callback (typically reads from cachedContentForms);
   *     falls back to `processApi.getFunctionUnitContent` so legacy callers still work.
   *  2) Delegate parsing + sub-table binding assembly to the host via
   *     `preparePopupContext`, which uses the same helpers as the main form (so
   *     subTable / lookup / card / Link Form widgets render identically).
   * Designer must enforce subTable widgets only on PRIMARY-style ACTION forms;
   * popup rendering does not attempt to write back sub-table data without the
   * host's submission integration.
   */
  async function openFormPopup(action: TaskActionInfo, config: any) {
    try {
      currentFormPopupAction.value = action
      formPopupTitle.value = config.popupTitle || action.actionName
      formPopupWidth.value = config.popupWidth || '800px'
      formPopupReadOnlyMode.value = config.readOnly === true || config.readOnly === 'true'
      formPopupData.value = {}
      formPopupFields.value = []
      formPopupTabs.value = []
      formPopupSubTableBindings.value = []
      formPopupLinkedSubTableBindings.value = null
      formPopupNativeSubTableBindingIds.value = []
      formPopupFormConfig.value = {}
      formPopupViewContext.value = 'assigneeTodo'

      if (!config.formId) {
        ElMessage.error(t('task.formMissingId'))
        return
      }

      // Prefer host-supplied form content (already includes tableBindings + sourceId).
      let formContent: any = null
      if (resolveFormPopupContent) {
        formContent = resolveFormPopupContent(action, config)
      }

      // Fallback for hosts that don't supply a resolver — fetch full FU content directly
      // (legacy parity; still returns tableBindings on each form despite slim TS types).
      if (!formContent) {
        const functionUnitId = taskInfo.value.processDefinitionKey
        if (functionUnitId) {
          try {
            const popupTaskId = taskInfo.value.taskId || taskInfo.value.id
            const res = await processApi.getFunctionUnitContent(functionUnitId, popupTaskId)
            const content = ('data' in (res as any) ? (res as any).data : res) as any
            const forms = content?.forms || []
            formContent =
              forms.find((f: any) => String(f.sourceId) === String(config.formId)) ||
              (config.formName ? forms.find((f: any) => f.name === config.formName) : null) ||
              null
          } catch {
            ElMessage.error(t('task.formLoadFailed'))
            return
          }
        }
      }

      if (!formContent) {
        ElMessage.error(t('task.formNotFound', { name: config.formName || config.formId }))
        return
      }

      const rawData = formContent.data ?? formContent.contentData
      let formConfig: Record<string, unknown> = {}
      try {
        formConfig = typeof rawData === 'string' ? JSON.parse(rawData) : (rawData || {})
      } catch {
        ElMessage.error(t('task.formLoadFailed'))
        return
      }

      if (!preparePopupContext) {
        // Without a host-side preparer the composable cannot build sub-table
        // bindings safely (helpers live in tasks/detail.vue). Refuse to fall back
        // to the legacy simplified converter — that path silently dropped
        // subTable / card / lookup widgets and is the root cause of #1394.
        console.warn('[useCustomActions] preparePopupContext not provided — popup will not render')
        ElMessage.error(t('task.formOpenFailed'))
        return
      }

      const ctx = preparePopupContext(formContent, formConfig)
      if (!ctx) {
        ElMessage.error(t('task.formOpenFailed'))
        return
      }

      formPopupFields.value = ctx.fields
      formPopupTabs.value = ctx.tabs
      formPopupSubTableBindings.value = ctx.subTableBindings
      formPopupLinkedSubTableBindings.value = ctx.linkedSubTableBindings ?? null
      formPopupNativeSubTableBindingIds.value = ctx.nativeSubTableBindingIds
      formPopupFormConfig.value = ctx.formConfig
      formPopupViewContext.value = ctx.viewContext ?? 'assigneeTodo'
      formPopupVisible.value = true
    } catch (e) {
      console.warn('[useCustomActions] openFormPopup failed:', e)
      ElMessage.error(t('task.formOpenFailed'))
    }
  }

  function handleFormPopupSubTableUpdate(bindingId: number, rows: any[]) {
    const target = formPopupSubTableBindings.value.find(
      (b: any) => Number(b?.bindingId) === Number(bindingId),
    )
    if (target) {
      target.data = rows
    }
  }

  async function submitFormPopup() {
    const action = currentFormPopupAction.value
    const taskId = taskInfo.value.taskId || taskInfo.value.id
    if (!action?.actionId || !taskId) {
      ElMessage.error(t('task.formSubmitFailed'))
      return
    }
    if (!warnIfUploadsBlocking({
      inflight: t('upload.waitUntilComplete'),
      failed: t('upload.fixFailedBeforeSubmit'),
    })) return
    submitting.value = true
    try {
      await submitActionFormPopup(taskId, action.actionId, formPopupData.value)
      // Sub-table edits (formPopupSubTableBindings) are not part of this ACTION-table
      // submission — ACTION forms write directly to their own table, not process variables.
      ElMessage.success(t('task.formSubmitSuccess'))
      formPopupVisible.value = false
      loadTaskDetail()
    } catch {
      ElMessage.error(t('task.formSubmitFailed'))
    } finally {
      submitting.value = false
    }
  }

  return { openFormPopup, handleFormPopupSubTableUpdate, submitFormPopup }
}
