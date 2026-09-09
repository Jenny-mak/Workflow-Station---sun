import { computed, nextTick, type ComputedRef } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import {
  getTaskHistory,
  type TaskHistoryInfo,
} from '@/api/task'
import {
  getProcessFormData,
  submitProcessFormUpdate,
  getTaskFormData as fetchTaskFormData,
  getCompletedTaskFormData,
  type ProcessFormData,
  type TaskFormData as TaskFormDataDTO,
  type CompletedTaskFormData,
} from '@/api/processForm'
import type { FormField, FormTab } from '@/components/FormRenderer.vue'
import {
  findTabsRule,
  isTabPaneRule,
} from '@/components/formRendererHelpers'
import {
  resolveSubTablePrimaryKeyFields,
} from '@/composables/tasks/shared'
import {
  bindingIdsPreferStrictSubTableLookup,
  cloneSubTableRows,
  isWholeFormLockedByFieldPermissions,
} from './subTableRowUtils'
import type { TaskDetailCtx } from './context'
import { seedTaskFormFromProcessValues } from './seedTaskFormFromProcessValues'
import {
  emptyProcessFormRef,
  extractCompletedFormFromVariables,
  hasSnapshotFieldValues,
} from '@/utils/completedTaskSnapshot'

export type PrefetchedTaskForms = {
  pfData: ProcessFormData | null
  tfData: TaskFormDataDTO | null
  ctData: CompletedTaskFormData | null
}

export interface TaskDetailFormsLoaderFns {
  prefetchProcessAndTaskFormData: (taskData: any) => Promise<PrefetchedTaskForms>
  loadProcessAndTaskFormData: (taskData: any, prefetched?: PrefetchedTaskForms) => Promise<void>
  handleProcessFormSubmit: () => Promise<void>
  isCompletedTaskData: (taskData: any) => boolean
  hasCompletedSnapshotRoute: () => boolean
  completedHistorySnapshotTime: ComputedRef<string>
  completedHistoryTaskId: ComputedRef<string>
  loadTaskHistory: () => Promise<void>
  scheduleDetailUiPhases: (onPainted?: () => void) => void
}

export function createTaskDetailFormsLoader(ctx: TaskDetailCtx): TaskDetailFormsLoaderFns {
  const {
    t,
    route,
    taskId,
    taskInfo,
    submitting,
    detailUiPhase,
    historyRecords,
    historyError,
    processFormData,
    processFormCollapse,
    processFormEditable,
    processFormFields,
    processFormTabs,
    processFormValues,
    processFormSubTableBindings,
    processFormFormConfig,
    processFormNativeSubTableBindingIds,
    taskFormDTO,
    completedFormData,
    isCompletedTask,
    isReturnToRequester,
    subTableBindings,
  } = ctx
  const { formReadOnly, currentFormName } = ctx.taskForm
  const { getHistoryStatus, getHistoryAction } = ctx.display

  function isCompletedTaskData(taskData: any): boolean {
    return taskData?.endTime != null ||
      taskData?.completedTime != null ||
      taskData?.completed === true ||
      String(taskData?.status || '').toUpperCase() === 'COMPLETED'
  }

  function hasCompletedSnapshotRoute(): boolean {
    return typeof route.query.snapshotTime === 'string' ||
      typeof route.query.snapshotTaskId === 'string'
  }

  function assignCompletedSnapshot(
    ctData: CompletedTaskFormData | null,
    taskData: Record<string, unknown>,
    pfData: ProcessFormData | null,
  ): void {
    const currentTaskId = String(taskData.id || taskId)
    const recovered = ctData ?? extractCompletedFormFromVariables(
      taskData.variables,
      currentTaskId,
      pfData ?? emptyProcessFormRef(String(taskData.processInstanceId ?? '')),
    )
    if (!recovered) return
    completedFormData.value = recovered
    if (ctData || hasSnapshotFieldValues(recovered.snapshot.fieldValues)) {
      ctx.applyCompletedSnapshotToForm(recovered)
    }
  }

  const completedHistorySnapshotTime = computed(() => (
    isCompletedTask.value && typeof route.query.snapshotTime === 'string'
      ? route.query.snapshotTime
      : ''
  ))

  const completedHistoryTaskId = computed(() => (
    isCompletedTask.value && typeof route.query.snapshotTaskId === 'string'
      ? route.query.snapshotTaskId
      : taskId
  ))

  function isWithinCompletedSnapshot(itemTime?: string | null): boolean {
    if (!isCompletedTask.value || !completedHistorySnapshotTime.value) return true
    if (!itemTime) return true
    const item = dayjs(itemTime)
    const cutoff = dayjs(completedHistorySnapshotTime.value)
    if (!item.isValid() || !cutoff.isValid()) return true
    return item.valueOf() <= cutoff.valueOf()
  }

  function shouldKeepCompletedHistoryItem(item: TaskHistoryInfo): boolean {
    if (!isCompletedTask.value || !hasCompletedSnapshotRoute()) return true
    if (completedHistoryTaskId.value && item.taskId === completedHistoryTaskId.value) return true
    return isWithinCompletedSnapshot(item.operationTime)
  }

  /** Mount heavy UI in frames so the shell paints before bpmn-js + FormRenderer block the main thread. */
  function scheduleDetailUiPhases(onPainted?: () => void) {
    detailUiPhase.value = 1
    void nextTick(() => {
      requestAnimationFrame(() => {
        detailUiPhase.value = 2
        requestAnimationFrame(() => {
          requestAnimationFrame(() => {
            detailUiPhase.value = 3
            void nextTick(() => {
              requestAnimationFrame(() => {
                requestAnimationFrame(() => {
                  onPainted?.()
                })
              })
            })
          })
        })
      })
    })
  }

  const loadTaskHistory = async () => {
    historyError.value = null
    try {
      const processInstanceId = taskInfo.value?.processInstanceId as string | undefined
      const res = await getTaskHistory(taskId, processInstanceId)
      const data = res.data || res
      if (data && Array.isArray(data)) {
        const visibleHistory = data.filter(shouldKeepCompletedHistoryItem)
        // Convert to HistoryRecord format (keep gateway records for diagram status determination)
        historyRecords.value = visibleHistory.map((item: TaskHistoryInfo, index: number) => ({
          id: `history_${index}`,
          // Completed diagram nodes read `_snapshot_{taskId}` for Owner (§6.6) — dropping
          // this made every lookup miss and silently blank the frozen Case Handler.
          taskId: item.taskId,
          nodeId: item.activityId || `node_${index}`,
          nodeName: item.activityName || t('task.unknownNode'),
          status: getHistoryStatus(item.operationType),
          action: getHistoryAction(item.operationType),
          assigneeName: item.operatorName || '-',
          comment: item.comment,
          createdTime: item.operationTime || '',
          completedTime: item.operationTime,
          activityType: item.activityType || ''
        }))
      }
    } catch (error) {
      console.error('Failed to load task history:', error)
      historyError.value = t('task.historyLoadFailed')
      historyRecords.value = []
    }
  }

  /** Start process + task form HTTP in parallel with history / function-unit fetch. */
  function prefetchProcessAndTaskFormData(taskData: any): Promise<PrefetchedTaskForms> {
    const processInstanceId = taskData.processInstanceId
    const currentTaskId = taskData.id || taskId
    const isCompleted = isCompletedTaskData(taskData) || hasCompletedSnapshotRoute()

    const pfPromise = processInstanceId
      ? getProcessFormData(processInstanceId)
          .then(r => ((r as { data?: ProcessFormData }).data ?? r) as ProcessFormData)
          .catch((e: unknown) => {
            console.warn('[detail] Failed to load process form data:', e)
            return null
          })
      : Promise.resolve(null)

    const taskFormPromise = currentTaskId
      ? (isCompleted ? getCompletedTaskFormData(currentTaskId) : fetchTaskFormData(currentTaskId))
          .then(r => {
            if (isCompleted) {
              return ((r as { data?: CompletedTaskFormData }).data ?? r) as CompletedTaskFormData
            }
            return ((r as { data?: TaskFormDataDTO }).data ?? r) as TaskFormDataDTO
          })
          .catch((e: unknown) => {
            console.warn(
              `[detail] Failed to load ${isCompleted ? 'completed' : 'task'} form data:`,
              e,
            )
            return null
          })
      : Promise.resolve(null)

    return Promise.all([pfPromise, taskFormPromise]).then(([pfData, taskFormRaw]) => ({
      pfData,
      tfData: !isCompleted ? (taskFormRaw as TaskFormDataDTO | null) : null,
      ctData: isCompleted ? (taskFormRaw as CompletedTaskFormData | null) : null,
    }))
  }

  // Task 17: Load Process Form and Task Form data
  const loadProcessAndTaskFormData = async (taskData: any, prefetched?: PrefetchedTaskForms) => {
    const currentTaskId = taskData.id || taskId
    const isCompleted = isCompletedTaskData(taskData) || hasCompletedSnapshotRoute()
    const { pfData, tfData, ctData } = prefetched ?? (await prefetchProcessAndTaskFormData(taskData))

    if (pfData) {
      processFormData.value = pfData
      processFormValues.value = pfData.fieldValues || {}

      if (pfData.processState === 'Return_To_Requester' && pfData.editable) {
        isReturnToRequester.value = true
        processFormEditable.value = true
        processFormCollapse.value = ['processForm']
      }

      if (pfData.configJson) {
        parseProcessFormConfig(pfData.configJson)
        buildProcessFormSubTableBindings(pfData)
      } else {
        processFormSubTableBindings.value = []
        processFormFormConfig.value = {}
        processFormNativeSubTableBindingIds.value = []
      }
    } else {
      processFormSubTableBindings.value = []
      processFormFormConfig.value = {}
      processFormNativeSubTableBindingIds.value = []
    }

    if (currentTaskId) {
      if (isCompleted) {
        isCompletedTask.value = true
        formReadOnly.value = true
        assignCompletedSnapshot(ctData, taskData as Record<string, unknown>, pfData)
      } else if (tfData) {
        taskFormDTO.value = tfData
        const taskFormNodeReadOnly = tfData.formReadOnly === true
        if (taskFormNodeReadOnly) {
          formReadOnly.value = true
        }
        if (tfData.formName) {
          currentFormName.value = tfData.formName
        }
        if (tfData.configJson) {
          ctx.parseFormConfig(tfData.configJson as any)
        }
        if (taskFormNodeReadOnly) {
          formReadOnly.value = true
        }
        if (tfData.configJson && tfData.fieldPermissions
            && isWholeFormLockedByFieldPermissions(tfData.fieldPermissions, (tfData.configJson as any)?.rule)) {
          formReadOnly.value = true
        }
        if (tfData.fieldValues) {
          ctx.mergeIncomingTaskFormFieldValues(tfData.fieldValues as Record<string, any>, taskData)
        }
      }
    }
    // Start Process LOOKUP cascade autofill lives on processFormRef.fieldValues; task form
    // fieldValues often omit those keys. Seed empty overlapping fields so To Do FormRenderer
    // can show multi/single tags (status_name etc.) via the same LookupField path.
    {
      const { formData, getCurrentFormFieldKeys } = ctx.taskForm
      const { next, patched } = seedTaskFormFromProcessValues(
        formData.value as Record<string, unknown>,
        processFormValues.value as Record<string, unknown>,
        getCurrentFormFieldKeys(),
      )
      if (patched) {
        formData.value = next as Record<string, any>
      }
    }
    ctx.syncFormLayoutWithSubTableBindings()
    ctx.refreshNodeFormMapFromFormData()
    ctx.rehydrateSharedProcessSubTableBindings()
  }

  // Parse Process Form config into FormRenderer fields
  const parseProcessFormConfig = (configJson: Record<string, unknown>) => {
    try {
      const config = configJson
      const rules = (config as any).rule && Array.isArray((config as any).rule)
        ? (config as any).rule
        : (Array.isArray(config) ? config : null)
      if (!rules) return

      const tabsRule = findTabsRule(rules)
      if (tabsRule?.children && Array.isArray(tabsRule.children)) {
        const tabs: FormTab[] = []
        for (const tabPane of tabsRule.children as Record<string, unknown>[]) {
          if (isTabPaneRule(tabPane) && tabPane.props) {
            const props = tabPane.props as Record<string, unknown>
            const tabFields: FormField[] = []
            if (tabPane.children) tabFields.push(...ctx.extractFieldsRecursive(tabPane.children as Record<string, unknown>[]))
            tabs.push({
              name: String(props.name || `tab_${tabs.length}`),
              label: String(props.label || `Tab ${tabs.length + 1}`),
              fields: tabFields,
            })
          }
        }
        processFormTabs.value = tabs
        processFormFields.value = []
      } else {
        processFormTabs.value = []
        processFormFields.value = ctx.extractFieldsRecursive(rules)
      }
    } catch (e) {
      console.error('[detail] Failed to parse process form config:', e)
    }
  }

  /**
   * Build FormRenderer-ready sub-table bindings for the Process Form panel.
   *
   * The backend ProcessFormData.subTableBindings DTO carries only the minimal
   * fields (bindingId / tableName / columns / data). Designer-side metadata
   * needed by FormRenderer (subForms row layout, portalViews, primaryKeyFields)
   * lives in configJson. Merge them here so the panel renders at parity with
   * the Designer Form Preview / main task form (portal-design-parity).
   *
   * Skips PRIMARY bindings (those drive flat fields, not sub-table widgets) and
   * coerces bindingId to Number for downstream binding lookup.
   */
  function buildProcessFormSubTableBindings(pfData: ProcessFormData) {
    const cfg = (pfData.configJson || {}) as Record<string, any>
    const subForms = (cfg.subForms || {}) as Record<string, any>
    const bindings: typeof subTableBindings.value = []
    const nativeIds: number[] = []
    for (const b of (pfData.subTableBindings || [])) {
      if (b.bindingType === 'PRIMARY') continue
      // resolveSubFormDesign signature: ({ bindingId }, subForms) → uses bindingId to look up cfg.subForms[bindingId].
      const subFormDesign = ctx.resolveSubFormDesign({ bindingId: b.bindingId } as any, subForms)
      bindings.push({
        bindingId: b.bindingId,
        tableId: null,
        bindingType: b.bindingType,
        bindingMode: b.bindingMode,
        foreignKeyField: null,
        tableName: (b as any).tableDisplayName || b.tableName,
        designerTableName: b.tableName,
        // 关联表必须落 rt: 命名空间；缺这两个字段会被当成设计器子表存进 dw:，
        // 进而被 MI 行隔离当成某个参与者的行，导致 Save 被拒。
        relationTableId: (b as any).relationTableId ?? null,
        relationTableName: (b as any).relationTableName ?? null,
        tableType: '',
        tableDescription: '',
        columns: Array.isArray(b.columns) ? (b.columns as any[]) : [],
        formFields: subFormDesign.formFields,
        formOptions: subFormDesign.formOptions,
        assignmentConfig: b.assignmentConfig,
        primaryKeyFields: resolveSubTablePrimaryKeyFields(null, b.bindingId, cfg),
        data: Array.isArray(b.data) ? (b.data as any[]) : [],
      } as any)
      const bid = Number(b.bindingId)
      if (Number.isFinite(bid)) nativeIds.push(bid)
    }
    // The DTO's binding.data is metadata-only (always empty). Hydrate rows from the live
    // process variables the same way the task-form path does — without this, any node that
    // falls back to the Process Form (e.g. no Task Form binding on the stage) renders every
    // sub-table as "No Data" even though __subTables__ carries the submitted rows.
    const savedSubTables = ((pfData.fieldValues || {}) as Record<string, any>).__subTables__
    if (savedSubTables && typeof savedSubTables === 'object' && !Array.isArray(savedSubTables)) {
      const ambiguous = bindingIdsPreferStrictSubTableLookup(bindings)
      for (const binding of bindings) {
        if (Array.isArray(binding.data) && binding.data.length > 0) continue
        const rows = ctx.getSavedSubTableRows(
          savedSubTables as Record<string, unknown>,
          binding,
          ambiguous.has(binding.bindingId),
        )
        if (rows?.length) binding.data = cloneSubTableRows(rows)
      }
    }
    processFormSubTableBindings.value = bindings
    processFormFormConfig.value = cfg
    processFormNativeSubTableBindingIds.value = nativeIds
  }

  // Task 17.4: Submit Process Form update (Return_To_Requester state)
  const handleProcessFormSubmit = async () => {
    if (!taskInfo.value.processInstanceId) return
    submitting.value = true
    try {
      await submitProcessFormUpdate(taskInfo.value.processInstanceId, processFormValues.value)
      ElMessage.success(t('task.operationSuccess'))
      // Refresh page data
      await ctx.loadTaskDetail()
    } catch (e: any) {
      if (e.response?.status === 403) {
        ElMessage.warning(t('process.notInReturnState'))
      } else {
        ElMessage.error(t('task.operationFailed'))
      }
    } finally {
      submitting.value = false
    }
  }

  return {
    prefetchProcessAndTaskFormData,
    loadProcessAndTaskFormData,
    handleProcessFormSubmit,
    isCompletedTaskData,
    hasCompletedSnapshotRoute,
    completedHistorySnapshotTime,
    completedHistoryTaskId,
    loadTaskHistory,
    scheduleDetailUiPhases,
  }
}
