import type { ComputedRef } from 'vue'
import { ElMessage } from 'element-plus'
import { getTaskDetail } from '@/api/task'
import { processApi } from '@/api/process'
import {
  coerceSubTablesVariableToMap,
  enrichChildBindingRowsFromParentsNestedSubTables,
  isMiParticipantScopedSubTableBinding,
} from '@/composables/tasks/shared'
import { resolveUserFacingHttpMessage } from '@/utils/httpErrorMessage'
import {
  cloneSubTableRows as cloneSubTableRowsImpl,
  cloneAndFlattenSubTablesMap,
  yieldToMain,
} from './subTableRowUtils'
import { seedTaskFormFromProcessValues } from './seedTaskFormFromProcessValues'
import { settleHistoryThenRefreshOwnerOverlay } from '@/composables/owner/overlayOwnerFromCompletedSnapshot'
import type { TaskDetailCtx } from './context'

/**
 * Setup-only externals the loader needs that are not registered on `ctx`.
 * Mirrors the original detail.vue setup-scope closures.
 */
export interface TaskDetailLoaderDeps {
  /** Read-only route-query fallback used when task-level permission is denied. */
  fallbackProcessInstanceId: ComputedRef<string>
}

export interface TaskDetailLoaderFns {
  loadTaskDetail: () => Promise<void>
}

/**
 * Top-level task-detail load orchestrator — extracted verbatim from detail.vue
 * `loadTaskDetail`. Behavior is unchanged; cross-module calls resolve through
 * `ctx.*` at invocation time, exactly as in the original single-file version.
 */
export function createTaskDetailLoader(
  ctx: TaskDetailCtx,
  deps: TaskDetailLoaderDeps,
): TaskDetailLoaderFns {
  const { t, route, taskId } = ctx
  const { fallbackProcessInstanceId } = deps
  const {
    loading,
    formRenderReady,
    fuFormSubTableFields,
    detailUiPhase,
    diagramInViewport,
    nodeFormMap,
    previousForms,
    subTableBindings,
    taskError,
    taskInfo,
    isCompletedTask,
    isMiSubTaskMode,
    miFullSubTablesSnapshotRef,
    miFilled,
    functionUnitIdRef,
  } = ctx

  const { formData, formReadOnly, getCurrentFormFieldKeys } = ctx.taskForm
  const { currentNodeId, bpmnXml, parseBpmnXml } = ctx.bpmn

  /** Local alias preserves the original setup-scope shadowing of the shared import. */
  const cloneSubTableRows = cloneSubTableRowsImpl

  const loadTaskDetail = async () => {
    loading.value = true
    formRenderReady.value = false
    fuFormSubTableFields.value = []
    detailUiPhase.value = 0
    diagramInViewport.value = false
    ctx.disconnectDiagramViewportObserver()
    ctx.deferredNodeFormMapContent = null
    nodeFormMap.value = new Map()
    // #1446: in-place reload (e.g. after MI save) must start from the same blank slate as a fresh
    // mount — stale previous-step rows / pre-save binding rows would otherwise re-enter the MI
    // merge candidates (mergePriorStepSubTablesAfterMiIsolate seeds from current binding data)
    // and win over the refetched values.
    previousForms.value = []
    subTableBindings.value = []
    taskError.value = null
    // Re-derived below from completion, BPMN readOnly and claim state; without the reset an
    // in-place reload after claiming would keep the read-only flag the previous pass set.
    formReadOnly.value = false
    try {
      const res = await getTaskDetail(taskId)
      const data = res.data || res
      if (data) {
        taskInfo.value = data
        isCompletedTask.value = ctx.isCompletedTaskData(data) || ctx.hasCompletedSnapshotRoute()
        if (isCompletedTask.value) {
          formReadOnly.value = true
          currentNodeId.value = ''
        }
        // A BU Role request the signed-in user does not hold stays viewable but not editable:
        // only the member who claimed it may change the form.
        if (data.claimPoolTask && !data.claimedByCurrentUser) {
          formReadOnly.value = true
        }
        // Set before any hydration runs (loadFunctionUnitContent / loadProcessAndTaskFormData /
        // rehydrateSharedProcessSubTableBindings below): those calls gate cross-binding sibling-slice
        // merging on this flag for MI dashboard bindings, and observing it still `false` here let an
        // early pass merge in an unrelated sibling MI node's stale row data (#1524-class: reload
        // showing another participant's old value instead of this task's own saved edit).
        isMiSubTaskMode.value = ctx.isMiSubTask(data)
        if (data.variables) formData.value = data.variables
        const processSubTablesSnapshot =
          data.variables?.__subTables__ && typeof data.variables.__subTables__ === 'object'
            ? (JSON.parse(JSON.stringify(data.variables.__subTables__)) as Record<string, unknown>)
            : null
        const st0 = coerceSubTablesVariableToMap(formData.value.__subTables__)
        if (st0) {
          formData.value = { ...formData.value, __subTables__: st0 }
        }

        // Parallel fetch: history, FU content, process/task forms — do not block FU/form CPU on history.
        const historyPromise = settleHistoryThenRefreshOwnerOverlay(ctx.loadTaskHistory(), () => {
          if (bpmnXml.value) parseBpmnXml(bpmnXml.value)
          ctx.refreshNodeFormMapFromFormData()
        })
        const fuFetchPromise = data.processDefinitionKey
          ? processApi
              .getFunctionUnitContent(data.processDefinitionKey, taskId)
              .then(r => (r as { data?: unknown }).data ?? r)
              .catch((err: unknown) => {
                console.error('Failed to prefetch function unit content:', err)
                return null
              })
          : Promise.resolve(null)
        const formPrefetchPromise = ctx.prefetchProcessAndTaskFormData(data)

        const [prefetchedFu, prefetchedForms] = await Promise.all([
          fuFetchPromise,
          formPrefetchPromise,
        ])
        // Pre-compute flattened sub-tables once — shared by FU load, rehydrate, and MI resync.
        const preFlattenedSubTables = processSubTablesSnapshot
          ? cloneAndFlattenSubTablesMap(processSubTablesSnapshot)
          : undefined
        if (data.processDefinitionKey) {
          functionUnitIdRef.value = String(data.processDefinitionKey)
          await ctx.loadFunctionUnitContent(
            data.processDefinitionKey,
            prefetchedFu ?? undefined,
            preFlattenedSubTables,
          )
        }

        await ctx.loadProcessAndTaskFormData(data, prefetchedForms)
        ctx.rehydrateSharedProcessSubTableBindings(processSubTablesSnapshot ?? undefined, preFlattenedSubTables)

        const miIsolatePromise = ctx.isMiSubTask(data)
          ? (async () => {
              isMiSubTaskMode.value = true
              const preIsolateTopLevelForDiagram: Record<string, unknown> = { ...formData.value }
              const miFullSubTablesSnapshot =
                processSubTablesSnapshot ??
                (formData.value.__subTables__ && typeof formData.value.__subTables__ === 'object'
                  ? (JSON.parse(JSON.stringify(formData.value.__subTables__)) as Record<string, unknown>)
                  : null)
              // Persist-side guard source: flatten so nested participant rows are reachable per binding.
              miFullSubTablesSnapshotRef.value = miFullSubTablesSnapshot
                ? (preFlattenedSubTables ?? cloneAndFlattenSubTablesMap(miFullSubTablesSnapshot))
                : null
              await ctx.isolateMiSubTaskData(data)
              await yieldToMain()
              enrichChildBindingRowsFromParentsNestedSubTables(subTableBindings.value)
              await yieldToMain()
              const miRowIdAfterEnrich = ctx.resolveCurrentMiParticipantRowIdFromTaskVars(data?.variables ?? {})
              if (miRowIdAfterEnrich != null) {
                ctx.applyMiParticipantFilterToCurrentSubTableBindings(miRowIdAfterEnrich)
              }
              // Enrich re-aggregates nested rows across peer parents — scope again to this MI element (one task ↔ one participant row).
              const miVarsRef = data?.variables ?? {}
              const miRowIdPostEnrich = ctx.resolveCurrentMiParticipantRowIdFromTaskVars(miVarsRef)
              if (miRowIdPostEnrich != null) {
                await ctx.resyncMiParticipantSubTablesFromVariables(
                  miRowIdPostEnrich,
                  miFullSubTablesSnapshot,
                  preFlattenedSubTables,
                )
              }
              await yieldToMain()
              ctx.rehydrateSharedAttachmentBindings(
                subTableBindings.value,
                preIsolateTopLevelForDiagram,
                miFullSubTablesSnapshotRef.value ?? preFlattenedSubTables ?? miFullSubTablesSnapshot,
              )
              ctx.mergePriorStepSubTablesAfterMiIsolate(miRowIdPostEnrich ?? null)
              if (miRowIdPostEnrich != null) {
                ctx.hydrateMiLinkChildBindingsFromFullSnapshot(miRowIdPostEnrich)
              }
              if (miRowIdPostEnrich != null) {
                ctx.scopeMiSubTaskBindingsToCurrentParticipant(subTableBindings.value, miRowIdPostEnrich)
                for (const pf of previousForms.value) {
                  ctx.scopeMiSubTaskBindingsToCurrentParticipant(
                    pf.subTableBindings as typeof subTableBindings.value,
                    miRowIdPostEnrich,
                  )
                }
              } else {
                ctx.sanitizeMiCollectionBindingsData(subTableBindings.value)
                for (const pf of previousForms.value) {
                  ctx.sanitizeMiCollectionBindingsData(pf.subTableBindings as typeof subTableBindings.value)
                }
              }
              ctx.patchFormDataSubTablesFromCurrentBindings()
              // MI isolate may clear Start Process LOOKUP slots that exist as empty keys on
              // the participant row; re-seed from processFormValues before FormRenderer mounts.
              {
                const { next, patched } = seedTaskFormFromProcessValues(
                  formData.value as Record<string, unknown>,
                  ctx.processFormValues.value as Record<string, unknown>,
                  getCurrentFormFieldKeys(),
                )
                if (patched) {
                  formData.value = next as Record<string, any>
                }
              }
              // nodeFormMap refresh deferred until diagram panel (buildNodeFormMapIfNeeded)
              const formKeys = getCurrentFormFieldKeys()
              miFilled.value = formKeys.some(key => {
                const val = formData.value[key]
                return val != null && val !== '' && val !== false
              })
            })().catch((err: unknown) => {
              console.error('[detail] MI isolate failed:', err)
              throw err
            })
          : Promise.resolve()

        if (isCompletedTask.value) {
          ctx.applyTaskAssigneeNameToMatchingSubTableRows(data)
        }

        // History feeds diagram node status — load in background so MI form hydrate does not block the shell.
        await miIsolatePromise
        // Safe only after MI nested slices are merged — stripping earlier breaks link-form / participant isolation.
        ctx.stripNestedFromAllTaskBindings()
        if (isMiSubTaskMode.value && ctx.currentMiRowId.value != null) {
          ctx.hydrateMiLinkChildBindingsFromFullSnapshot(ctx.currentMiRowId.value)
          for (const b of subTableBindings.value) {
            if (!isMiParticipantScopedSubTableBinding(b)) continue
            ctx.syncMiLinkChildRowsIntoParentNested(
              { bindingId: b.bindingId, tableName: b.tableName ?? '' },
              cloneSubTableRows(Array.isArray(b.data) ? b.data : []),
            )
          }
          ctx.scopeMiSubTaskBindingsToCurrentParticipant(subTableBindings.value, ctx.currentMiRowId.value)
          for (const pf of previousForms.value) {
            ctx.scopeMiSubTaskBindingsToCurrentParticipant(
              pf.subTableBindings as typeof subTableBindings.value,
              ctx.currentMiRowId.value,
            )
          }
          ctx.patchFormDataSubTablesFromCurrentBindings()
        }
        ctx.markBindingRowsNonReactive()
        ctx.syncFormLayoutWithSubTableBindings()
        ctx.forceSeedMiCollectionBindingForCurrentParticipant()
        await yieldToMain()
        void historyPromise.catch((err: unknown) => {
          console.warn('[detail] Background history load failed:', err)
        })
      }
    } catch (error: any) {
      console.error('Failed to load task detail:', error)
      const status = error.response?.status
      const msg = resolveUserFacingHttpMessage(error, t)
      const notFound = status === 404 || /task not found/i.test(msg)
      const forbidden =
        status === 403 ||
        /permission|denied|do not have permission|无权|無權/i.test(msg)
      if (notFound) {
        taskError.value = t('task.notFound')
      } else if (forbidden) {
        // Completed tasks should still be able to render workflow diagram for process participants.
        // Fallback to process detail (read-only) when task-level permission is denied.
        if (fallbackProcessInstanceId.value) {
          try {
            const pr = await processApi.getProcessDetail(fallbackProcessInstanceId.value)
            const p = (pr as any).data || pr
            if (p) {
              taskInfo.value = {
                taskId,
                id: taskId,
                taskName: String(route.query.snapshotTaskName || ''),
                processInstanceId: p.id,
                processDefinitionKey: p.processDefinitionKey || (route.query.processDefinitionKey as any),
                variables: p.variables || {}
              } as any
              isCompletedTask.value = true
              formReadOnly.value = true
              currentNodeId.value = ''
              if (p.variables) formData.value = p.variables
              const stP = coerceSubTablesVariableToMap(formData.value.__subTables__)
              if (stP) {
                formData.value = { ...formData.value, __subTables__: stP }
              }
              const historyPromise = settleHistoryThenRefreshOwnerOverlay(ctx.loadTaskHistory(), () => {
                if (bpmnXml.value) parseBpmnXml(bpmnXml.value)
                ctx.refreshNodeFormMapFromFormData()
              })
              const key = (taskInfo.value as any).processDefinitionKey
              const fuFetchPromise = key
                ? processApi
                    .getFunctionUnitContent(String(key), taskId)
                    .then(r => (r as { data?: unknown }).data ?? r)
                    .catch(() => null)
                : Promise.resolve(null)
              const fallbackTask = { ...(taskInfo.value as any), processInstanceId: p.id, id: taskId }
              const formPrefetchPromise = ctx.prefetchProcessAndTaskFormData(fallbackTask)
              const prefetchedFu = await fuFetchPromise
              if (key) {
                await ctx.loadFunctionUnitContent(String(key), prefetchedFu ?? undefined)
              }
              await ctx.loadProcessAndTaskFormData(fallbackTask, await formPrefetchPromise)
              await historyPromise
              loading.value = false
              ctx.scheduleDetailUiPhases()
              return
            }
          } catch (e) {
            console.warn('[detail] Fallback process detail failed:', e)
          }
        }
        taskError.value = t('task.noPermission')
      } else {
        taskError.value = msg || t('task.serverError')
      }
      ElMessage.error(taskError.value)
    } finally {
      loading.value = false
      if (!taskError.value) {
        formRenderReady.value = true
        ctx.scheduleDetailUiPhases()
      }
    }
  }

  return { loadTaskDetail }
}
