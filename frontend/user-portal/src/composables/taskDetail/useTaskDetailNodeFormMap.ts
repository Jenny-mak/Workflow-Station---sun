import type { FormField, FormTab } from '@/components/FormRenderer.vue'
import {
  findTabsRule,
  isTabPaneRule,
} from '@/components/formRendererHelpers'
import {
  resolveSubTablePrimaryKeyFields,
  hydrateChildSubTablesFromParentsNestedRows,
  hydrateBindingsRowsFromVariablesBySharedRelationTableId,
  enrichChildBindingRowsFromParentsNestedSubTables,
  applySharedAttachmentFinalizeAndMaterialize,
} from '@/composables/tasks/shared'
import { getCachedBpmnDocument } from '@/utils/bpmnParseCache'
import {
  cloneSubTableRows,
  cloneAndFlattenSubTablesMap,
  bindingIdsPreferStrictSubTableLookup,
  yieldToMain,
  applyUnionFindMergedRowSnapshots,
  type SubTableBindingAlignable,
} from './subTableRowUtils'
import type { NodeFormInfo, PreviousFormEntry } from './useTaskDetailState'
import type { TaskDetailCtx } from './context'
import { stampMiCollectionFromBpmn } from './miCollectionStamp'
import { attachAssignmentConfigsToBindings } from '@/utils/miAssignmentConfig'
import { overlayCompletedNodeOwner } from '@/composables/owner/overlayOwnerFromCompletedSnapshot'

export interface TaskDetailNodeFormMapFns {
  buildNodeFormMapIfNeeded: () => Promise<void>
  refreshNodeFormMapFromFormData: (opts?: {
    subTablesSource?: Record<string, unknown> | null
    topLevelValuesSource?: Record<string, unknown> | null
  }) => void
  alignNodeFormMapSubTableBindingsOnly: () => void
  alignProcessSubTableBindingsBySharedTable: () => void
}

export function createTaskDetailNodeFormMap(ctx: TaskDetailCtx): TaskDetailNodeFormMapFns {
  const {
    taskInfo,
    isCompletedTask,
    nodeFormMap,
    subTableBindings,
    previousForms,
    lastBindingRelationTableMap,
  } = ctx
  const { formData } = ctx.taskForm
  const { historyRecords } = ctx

  function withCompletedOwnerOverlay(
    nodeId: string,
    info: Pick<NodeFormInfo, 'isCurrentTask' | 'fields' | 'tabs' | 'bpmnNodeName'>,
    values: Record<string, unknown>,
    bindings: NodeFormInfo['subTableBindings'],
  ): Pick<NodeFormInfo, 'values' | 'subTableBindings'> {
    if (info.isCurrentTask) {
      return { values, subTableBindings: bindings }
    }
    const variables = (taskInfo.value as { variables?: Record<string, unknown> }).variables
    const overlaid = overlayCompletedNodeOwner({
      values,
      fields: info.fields,
      tabs: info.tabs,
      bindings,
      nodeId,
      nodeName: info.bpmnNodeName,
      variables,
      history: historyRecords.value,
    })
    return {
      values: overlaid.values,
      subTableBindings: overlaid.bindings as NodeFormInfo['subTableBindings'],
    }
  }

  async function buildNodeFormMapIfNeeded() {
    const pending = ctx.deferredNodeFormMapContent
    if (!pending || nodeFormMap.value.size > 0) return
    await yieldToMain()
    try {
      nodeFormMap.value = buildNodeFormMapFromContent(pending.content, pending.bindingRelationTableMap)
      // Same BPMN verdict the live bindings get — the read-only diagram view of a node must not
      // classify its sub-table as an MI dashboard when the process has no MI sub-process.
      // Later rebuilds spread each binding, so stamping once at build time carries through.
      nodeFormMap.value.forEach(info => stampMiCollectionFromBpmn(ctx, info.subTableBindings))
      alignNodeFormMapSubTableBindingsOnly()
      refreshNodeFormMapFromFormData({
        subTablesSource: formData.value.__subTables__ as Record<string, unknown> | undefined,
      })
    } catch (e) {
      console.warn('[NodeFormMap] Deferred build failed:', e)
    } finally {
      ctx.deferredNodeFormMapContent = null
    }
  }

  /** Build BPM node → form snapshot map (diagram clicks). Expensive — call only after first paint. */
  function buildNodeFormMapFromContent(
    content: any,
    bindingRelationTableMap: Map<number, number | null>,
  ): Map<string, NodeFormInfo> {
    const newMap = new Map<string, NodeFormInfo>()
    const bpmnData = content.processes?.[0]?.data
    if (!bpmnData) return newMap
    const doc = getCachedBpmnDocument(bpmnData)
    const allElements = doc?.getElementsByTagName('*')
    if (!allElements) return newMap
    for (let i = 0; i < allElements.length; i++) {
      const el = allElements[i]
      const localName = el.localName || el.nodeName.split(':').pop()
      if (localName !== 'userTask' && localName !== 'subProcess' && localName !== 'serviceTask') continue
      const nodeId = el.getAttribute('id') || ''
      if (!nodeId) continue
      let formId: string | null = null
      let formName: string | null = null
      const props = el.getElementsByTagName('*')
      for (let j = 0; j < props.length; j++) {
        const p = props[j]
        const ln = p.localName || p.nodeName.split(':').pop()
        if (ln === 'property' || ln === 'values') {
          const n = p.getAttribute('name'), v = p.getAttribute('value')
          if (n === 'formId' && v) formId = v
          if (n === 'formName' && v) formName = v
        }
      }
      if (localName === 'subProcess' && !formId && !formName) {
        const innerTasks = el.getElementsByTagName('userTask')
        for (let k = 0; k < innerTasks.length; k++) {
          let fid: string | null = null
          let fnm: string | null = null
          const inner = innerTasks[k]
          const iprops = inner.getElementsByTagName('*')
          for (let j = 0; j < iprops.length; j++) {
            const p = iprops[j]
            const ln = p.localName || p.nodeName.split(':').pop()
            if (ln === 'property' || ln === 'values') {
              const n = p.getAttribute('name'), v = p.getAttribute('value')
              if (n === 'formId' && v) fid = v
              if (n === 'formName' && v) fnm = v
            }
          }
          if (fid || fnm) {
            formId = fid
            formName = fnm
            break
          }
        }
      }
      // Only `formId` is read above, so the node's My Requests design (carried as
      // requestFormId) is already out of scope here. The name fallback still needs
      // a scene guard: both designs of a node commonly share a name.
      let matchedForm: any = null
      if (formId) matchedForm = content.forms.find((f: any) => String(f.sourceId) === formId)
      if (!matchedForm && formName) {
        matchedForm = content.forms.find((f: any) => f.name === formName && f.scene !== 'REQUEST')
      }
      if (!matchedForm) continue

      const nodeFields: FormField[] = []
      const nodeTabs: FormTab[] = []
      const nodeBindings: PreviousFormEntry['subTableBindings'] = []
      let nodeFormConfig: Record<string, any> = {}
      const nodeNativeIds: number[] = []
      try {
        const cfg = typeof matchedForm.data === 'string' ? JSON.parse(matchedForm.data) : (matchedForm.data || {})
        nodeFormConfig = cfg
        const rules = cfg.rule && Array.isArray(cfg.rule) ? cfg.rule : (Array.isArray(cfg) ? cfg : null)
        if (rules) {
          const tabsRule = findTabsRule(rules)
          if (tabsRule?.children) {
            for (const tabPane of tabsRule.children as Record<string, unknown>[]) {
              if (isTabPaneRule(tabPane) && tabPane.props) {
                const tabFields: FormField[] = []
                if (tabPane.children) tabFields.push(...ctx.extractFieldsRecursive(tabPane.children))
                nodeTabs.push({
                  name: tabPane.props.name || `tab_${nodeTabs.length}`,
                  label: tabPane.props.label || `Tab ${nodeTabs.length + 1}`,
                  fields: tabFields,
                })
              }
            }
          } else {
            nodeFields.push(...ctx.extractFieldsRecursive(rules))
          }
        }
        let subForms: Record<string, any> = {}
        let configForSubTables: Record<string, any> = {}
        configForSubTables = cfg
        subForms = cfg.subForms || {}
        for (const b of (matchedForm.tableBindings || [])) {
          if (b.bindingType === 'PRIMARY') continue
          const cols = ctx.deriveColumnsFromBinding(b, subForms, configForSubTables)
          const subFormDesign = ctx.resolveSubFormDesign(b, subForms)
          const binding = {
            bindingId: b.bindingId, tableId: b.tableId ?? null, bindingType: b.bindingType, bindingMode: b.bindingMode,
            foreignKeyField: b.foreignKeyField, tableName: b.tableDisplayName || b.tableName, designerTableName: b.tableName,
            relationTableId: (b as any).relationTableId ?? null,
            relationTableName: (b as any).relationTableName ?? null,
            // 分类判据（MI collection / child / shared）只读这两项 —— 漏传就判不出 MI。
            bindingLinkMode: b.bindingLinkMode ?? null,
            fieldDefinitions: b.fieldDefinitions ?? null,
            tableType: b.tableType, tableDescription: b.tableDescription, columns: cols,
            formFields: subFormDesign.formFields,
            formOptions: subFormDesign.formOptions,
            primaryKeyFields: resolveSubTablePrimaryKeyFields(b.primaryKeyFields, b.bindingId, configForSubTables),
            data: [] as any[],
          }
          nodeBindings.push(binding)
          const bid = Number(b.bindingId)
          if (Number.isFinite(bid)) nodeNativeIds.push(bid)
        }
        ctx.mergeLinkFormTargetBindingsInto(nodeBindings as any, content.forms, configForSubTables, subForms)
        attachAssignmentConfigsToBindings(nodeBindings, content.miAssignments)
        const ambiguousNodeDiagram = bindingIdsPreferStrictSubTableLookup(nodeBindings as any[])
        const _stForNested = formData.value.__subTables__
        if (_stForNested && typeof _stForNested === 'object') {
          nodeBindings.forEach(binding => {
            const saved = ctx.getSavedSubTableRows(_stForNested, binding, ambiguousNodeDiagram.has(binding.bindingId))
            if (saved) binding.data = cloneSubTableRows(saved)
          })
        }
        hydrateChildSubTablesFromParentsNestedRows(
          nodeBindings,
          _stForNested && typeof _stForNested === 'object' ? (_stForNested as Record<string, unknown>) : null,
          bindingRelationTableMap,
        )
        if (_stForNested && typeof _stForNested === 'object') {
          hydrateBindingsRowsFromVariablesBySharedRelationTableId(
            nodeBindings,
            _stForNested as Record<string, unknown>,
            bindingRelationTableMap,
          )
        }
        enrichChildBindingRowsFromParentsNestedSubTables(nodeBindings)
        ctx.rehydrateSharedAttachmentBindings(
          nodeBindings,
          formData.value as Record<string, unknown>,
          _stForNested && typeof _stForNested === 'object' ? (_stForNested as Record<string, unknown>) : null,
        )
      } catch { /* node snapshot best-effort */ }

      const nodeName = el.getAttribute('name') || nodeId
      const currentDefKey = (taskInfo.value as any).taskDefinitionKey || ''
      const isCurrentTask = !isCompletedTask.value && (nodeId === currentDefKey || nodeName === taskInfo.value.taskName)
      const ownerLayer = withCompletedOwnerOverlay(
        nodeId,
        { isCurrentTask, fields: nodeFields, tabs: nodeTabs, bpmnNodeName: nodeName },
        { ...formData.value },
        nodeBindings,
      )
      newMap.set(nodeId, {
        formName: matchedForm.name || nodeName,
        bpmnNodeName: nodeName,
        isCurrentTask,
        fields: nodeFields,
        tabs: nodeTabs,
        values: ownerLayer.values,
        subTableBindings: ownerLayer.subTableBindings,
        formConfig: nodeFormConfig,
        nativeSubTableBindingIds: nodeNativeIds,
      })
    }
    return newMap
  }

  /**
   * BPM diagram clicks render {@link nodeFormMap}. That map is built in {@link loadFunctionUnitContent}, but
   * {@link loadProcessAndTaskFormData} may merge additional {@code fieldValues}/{@code __subTables__} afterwards —
   * refresh snapshots so historical nodes (e.g. assignment/submit "sub form1") show the same rows as live variables.
   *
   * {@code topLevelValuesSource}: when MI isolation has cleared fields on {@link formData} that still belong on
   * earlier steps (diagram uses {@code selectedNodeForm.values} for read-only nodes), pass the pre-isolate snapshot here.
   */
  function refreshNodeFormMapFromFormData(opts?: {
    subTablesSource?: Record<string, unknown> | null
    topLevelValuesSource?: Record<string, unknown> | null
  }) {
    if (nodeFormMap.value.size === 0) return
    const valuesBase = (opts?.topLevelValuesSource ?? formData.value) as Record<string, any>
    const raw = opts?.subTablesSource ?? formData.value.__subTables__
    if (!raw || typeof raw !== 'object') {
      const nextEarly = new Map<string, NodeFormInfo>()
      for (const [nodeId, info] of nodeFormMap.value.entries()) {
        const bindings = info.subTableBindings.map(b => ({
          ...b,
          data: cloneSubTableRows(Array.isArray(b.data) ? b.data : []),
        }))
        applySharedAttachmentFinalizeAndMaterialize(bindings, valuesBase, {
          flattened: null,
          bindingTableById: lastBindingRelationTableMap.value,
        })
        const ownerLayer = withCompletedOwnerOverlay(nodeId, info, { ...valuesBase }, bindings)
        nextEarly.set(nodeId, { ...info, values: ownerLayer.values, subTableBindings: ownerLayer.subTableBindings })
      }
      nodeFormMap.value = nextEarly
      return
    }
    // Reuse pre-flattened copy if provided (avoids deep-clone per call)
    const flattened =
      (opts as any)?._preFlattened ??
      cloneAndFlattenSubTablesMap(raw as Record<string, unknown>)
    const rtMap = lastBindingRelationTableMap.value
    const next = new Map<string, NodeFormInfo>()
    for (const [nodeId, info] of nodeFormMap.value.entries()) {
      const bindings = info.subTableBindings.map(b => ({
        ...b,
        data: [] as PreviousFormEntry['subTableBindings'][0]['data'],
      }))
      const ambiguousNodeRefresh = bindingIdsPreferStrictSubTableLookup(bindings as any[])
      bindings.forEach(binding => {
        const saved = ctx.getSavedSubTableRows(flattened, binding, ambiguousNodeRefresh.has(binding.bindingId))
        if (saved) binding.data = cloneSubTableRows(saved)
      })
      hydrateChildSubTablesFromParentsNestedRows(bindings as any, flattened, rtMap.size > 0 ? rtMap : undefined)
      hydrateBindingsRowsFromVariablesBySharedRelationTableId(bindings as any, flattened, rtMap)
      enrichChildBindingRowsFromParentsNestedSubTables(bindings as any)
      applySharedAttachmentFinalizeAndMaterialize(bindings, valuesBase, {
        flattened,
        bindingTableById: lastBindingRelationTableMap.value,
      })
      const ownerLayer = withCompletedOwnerOverlay(nodeId, info, { ...valuesBase }, bindings)
      next.set(nodeId, {
        ...info,
        values: ownerLayer.values,
        subTableBindings: ownerLayer.subTableBindings,
      })
    }
    nodeFormMap.value = next
    alignNodeFormMapSubTableBindingsOnly()
  }

  /**
   * Diagram-only align: copied-binding id mismatches still merge inside {@link nodeFormMap}, without touching
   * live {@link subTableBindings} (MI isolation must not be widened by a post-isolate node refresh).
   */
  function alignNodeFormMapSubTableBindingsOnly() {
    if (nodeFormMap.value.size === 0) return
    nodeFormMap.value.forEach(info => {
      const chunk = info.subTableBindings as SubTableBindingAlignable[]
      if (chunk.length === 0) return
      applyUnionFindMergedRowSnapshots(chunk)
      enrichChildBindingRowsFromParentsNestedSubTables(chunk as any)
    })
  }

  /**
   * Copied Task Forms (e.g. subform_copy) get a new bindingId while runtime __subTables__ still keys by the
   * initiator binding id — union-find merge by shared tableId / normalized display name (same idea as My Request).
   * Includes diagram node bindings so clicking nodes stays consistent.
   */
  function alignProcessSubTableBindingsBySharedTable() {
    const partitions: SubTableBindingAlignable[][] = []
    if ((subTableBindings.value as SubTableBindingAlignable[]).length > 0) {
      partitions.push(subTableBindings.value as SubTableBindingAlignable[])
    }
    previousForms.value.forEach(f => {
      if ((f.subTableBindings as SubTableBindingAlignable[]).length > 0) {
        partitions.push(f.subTableBindings as SubTableBindingAlignable[])
      }
    })
    nodeFormMap.value.forEach(info => {
      if ((info.subTableBindings as SubTableBindingAlignable[]).length > 0) {
        partitions.push(info.subTableBindings as SubTableBindingAlignable[])
      }
    })

    if (partitions.length === 0) return

    partitions.forEach(p => {
      applyUnionFindMergedRowSnapshots(p)
      enrichChildBindingRowsFromParentsNestedSubTables(p as any)
    })

    ctx.backfillEmptySubTableBindingsFromVariables()
  }

  return {
    buildNodeFormMapIfNeeded,
    refreshNodeFormMapFromFormData,
    alignNodeFormMapSubTableBindingsOnly,
    alignProcessSubTableBindingsBySharedTable,
  }
}
