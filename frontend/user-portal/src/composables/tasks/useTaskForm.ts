import { ref, type Ref } from 'vue'
import { subTableStoreKey } from './subTableStore'
import { bindingDeclaresMiParticipantRow } from './miBindingKindFromConfig'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { submitTaskForm } from '@/api/processForm'
import type { FormField, FormTab } from '@/components/FormRenderer.vue'
import { collectLeafFormFieldKeys } from '@/components/formRendererHelpers'
import {
  cloneSubTableRows,
  mergeSubTableRowsByRowId,
  getSavedSubTableRows,
  flattenNestedSubTableRowsIntoPayload,
  scrubMiCorruptLinkChildRowsForParent,
  buildMiCollectionSliceKeySet,
  collapseMiLinkChildRowsToOnePerParticipant,
  isMiParticipantScopedSubTableBinding,
  isMiDashboardSubTableBinding,
  shouldSyncStaleSiblingSubTableSlice,
  syncMiLinkChildEditedRowsIntoSiblingSlices,
  sameSubTableRow,
} from './shared'
import { mergeSubTableRowsForMiSave } from './miSubTableSaveMerge'

function subTableSliceUnchanged(
  snapshot: Record<string, any>,
  bindingId: number,
  out: unknown[],
): boolean {
  const prev = snapshot[bindingId] ?? snapshot[String(bindingId)]
  try {
    return JSON.stringify(prev) === JSON.stringify(out)
  } catch {
    return false
  }
}

function stampBindingTableNameAliases(
  subTables: Record<string, any>,
  subTableData: Record<string, Array<Record<string, unknown>>>,
  binding: { tableName?: string; designerTableName?: string },
  rows: unknown[],
) {
  // 规范 key：一张表一个 key。subTableData 是提交时的另一个字段（controller 会并进
  // __subTables__），用同一个 key 规则，避免两边再度分叉。
  const key = subTableStoreKey(binding)
  if (key) {
    subTables[key] = rows
    subTableData[key] = rows as Array<Record<string, unknown>>
  }
}

export function useTaskForm(options: {
  subTableBindings: Ref<any[]>
  isMiSubTaskMode: Ref<boolean>
  isCompletedTask: Ref<boolean>
  effectiveTaskId: Ref<string>
  taskFormDTO?: Ref<{ fieldValues?: Record<string, any> } | null>
  /** Binding-id → relation-table-id map; used to protect MI collection slices from id_idw scrub on save. */
  bindingRelationTableMap?: Ref<Map<number, number | null>>
  miSubProcessScopeName?: Ref<string | null | undefined>
  /**
   * 归属谓词的**延迟解析**入口：返回「这一行属不属于当前参与者」的判定函数，判不出时返回 null。
   *
   * <p>为什么是工厂而不是直接传函数：`useTaskForm` 在 detail.vue 里先于 `ctx` 构造，
   * 而归属判定（`rowBelongsToCurrentMiScope`）挂在 `ctx` 上 —— 直接传会拿到 undefined。
   * 工厂在**保存时**才调用，那时 `ctx` 已装配完毕。
   *
   * <p>不传 = participant-child 退回并集（保守侧），行为与修复前一致。
   */
  resolveMiRowOwnershipPredicate?: (
    binding: unknown,
  ) => ((row: unknown) => boolean) | null
  onFormReadOnlyChange?: (readonly: boolean) => void
}) {
  const { t } = useI18n()

  // Form state
  const formFields = ref<FormField[]>([])
  const formTabs = ref<FormTab[]>([])
  const formFieldsAfterTabs = ref<FormField[]>([])
  const formData = ref<Record<string, any>>({})
  const currentFormName = ref('')
  const formReadOnly = ref(false)
  const formLabelWidth = ref('160px')
  const formFormOptions = ref<Record<string, unknown>>({})
  const savingTaskForm = ref(false)
  const taskFormDTO = options.taskFormDTO ?? ref<{ fieldValues?: Record<string, any> } | null>(null)
  let subTableAutosaveTimer: ReturnType<typeof setTimeout> | null = null

  /** Assignment task: merge active binding rows into stale sibling slices for the same MI collection table only. */
  function syncStaleSiblingSubTableSlicesFromActiveBindings(
    subTables: Record<string, any>,
    bindings: Array<{
      bindingId: number
      primaryKeyFields?: string[] | null
      data?: unknown[]
      tableId?: number | null
      tableName?: string
      columns?: Array<{ field?: string }> | null
      formFields?: unknown[] | null
    }>,
    snapshot?: Record<string, any>,
  ) {
    const currentIds = new Set(bindings.map(b => Number(b.bindingId)))
    for (const binding of bindings) {
      const source =
        subTables[binding.bindingId] ??
        subTables[String(binding.bindingId)] ??
        binding.data
      if (!Array.isArray(source) || source.length === 0) continue
      const pk = Array.isArray(binding.primaryKeyFields) ? binding.primaryKeyFields : null
      const sourceHasForm = Array.isArray(binding.formFields) && binding.formFields.length > 0
      const sourceIsMiDashboard = isMiDashboardSubTableBinding(binding)
      const sourceChanged = snapshot ? !subTableSliceUnchanged(snapshot, binding.bindingId, source) : sourceHasForm
      for (const key of Object.keys(subTables)) {
        if (!/^\d+$/.test(key)) continue
        if (Number(key) === Number(binding.bindingId)) continue
        // Unchanged list-only rows still hold page-load N/Y; do not copy that onto the
        // live form slice. A binding whose data actually changed this Save is the source of truth.
        if (currentIds.has(Number(key)) && !sourceIsMiDashboard && !sourceChanged) continue
        const target = subTables[key]
        if (!Array.isArray(target) || target.length === 0) continue
        if (!shouldSyncStaleSiblingSubTableSlice(
          target,
          binding,
          bindings,
          key,
          options.bindingRelationTableMap?.value,
          source,
        )) continue
        subTables[key] = mergeSubTableRowsByRowId(target, source as any[], pk)
      }
    }
  }

  function buildSubTableSubmitPayload() {
    const snapshot = (formData.value.__subTables__ as Record<string, any>) || {}
    const subTables: Record<string, any> = { ...snapshot }
    /**
     * 这里曾有一段 `pruneDeletedRowsFromNestedCaches` —— 靠比较「顶层切片」和「嵌套副本」
     * 哪边行数更少来猜谁是权威，以此补偿「删除没进 payload」。已删除。
     *
     * <p><b>为什么删掉。</b>那是在错误前提上打的补丁：真正的缺陷是
     * `PortalFormFields` 没有 `update:sub-table-data` 这个 emit，inline 表格的编辑只写进宿主行的
     * `__subTables__`，`binding.data` 永远不同步，于是同一张表出现两份互相矛盾的数据。
     *
     * <p>而「取行数少的一方」这个启发式**方向是反的**：删除时少的一方新，
     * **新增时多的一方才新** —— 照此规则新增的行会被当成「已删除」剪掉，静默丢数据。
     *
     * <p>补上那个 emit 后两份数据由同一次事件同时更新，天然一致，不需要任何权威判定。
     */
    // 每个 slice key 对应那张表配置的主键列——「删到空」分支要靠它解析父行标识，
    // 否则主键不叫 row_id/id_idw/id 的表删不掉最后一行（刷新后复活）。
    const pkBySliceKey: Record<string, readonly string[] | undefined> = {}
    for (const b of options.subTableBindings.value) {
      const key = subTableStoreKey(b)
      if (key) pkBySliceKey[key] = b?.primaryKeyFields ?? undefined
    }
    flattenNestedSubTableRowsIntoPayload(subTables as Record<string, unknown>, 8, pkBySliceKey)
    let miParentIdIdw: string | number | null = null
    let miCollectionSliceKeys: Set<string> | null = null
    if (options.isMiSubTaskMode.value) {
      const ci = (formData.value._currentItem ?? formData.value.currentItem) as
        | { rowId?: string | number; rowKey?: { id?: string | number } }
        | undefined
      const parentIdIdw = ci?.rowId ?? ci?.rowKey?.id
      if (parentIdIdw != null && String(parentIdIdw).trim() !== '') {
        miParentIdIdw = parentIdIdw
        miCollectionSliceKeys = buildMiCollectionSliceKeySet(
          options.subTableBindings.value,
          options.bindingRelationTableMap?.value ?? new Map<number, number | null>(),
          options.miSubProcessScopeName?.value,
        )
        scrubMiCorruptLinkChildRowsForParent(subTables as Record<string, unknown>, parentIdIdw, {
          skipSliceKeys: miCollectionSliceKeys,
        })
      }
    }
    const subTableData: Record<string, Array<Record<string, unknown>>> = {}
    /**
     * 本次提交里**用户主动删空**的参与者切片（删掉了自己最后一行）。
     *
     * <p>后端光看「空数组」区分不了「用户删空了」和「这个 binding 根本没渲染」，
     * 所以由前端显式声明意图 —— 只有出现在这里的 key，后端才允许清掉该参与者的基线行。
     * 见 `MiSubTaskSubTableRowMerger` 的 empty-slice 分支。
     */
    const emptiedSubTableKeys: string[] = []

    for (const binding of options.subTableBindings.value) {
      const rows = cloneSubTableRows(Array.isArray(binding.data) ? binding.data : [])
      const existing = getSavedSubTableRows(subTables, binding)
      /**
       * MI 模式下这里曾是**无差别并集**，是「删了行、刷新又回来」的第一现场：并集按主键取,
       * 「在 existing 里、不在 incoming 里」的行永远保留，结构上表达不了删除，被删的行在
       * 请求发出**之前**就被从已存切片填回去了（非 MI 走 `: rows` 直接替换，所以删得掉 ——
       * 这也解释了为什么只有 MI 子任务复现）。
       *
       * <p>f14599671 / 57715d8d8 只给 **shared** 表加了替换豁免，改的是
       * `useTaskDetailMiPersist` / `useTaskDetailSubTableSync` 两处，**漏了这里**，
       * 而 People 是 participant-child，那些豁免对它一条都不生效。
       *
       * <p>合并规则统一收敛到 {@link mergeSubTableRowsForMiSave}（三类各走各的路，
       * 判不出时保守并集）。
       */
      const ownRowPredicate = options.resolveMiRowOwnershipPredicate?.(binding) ?? null
      const merged = options.isMiSubTaskMode.value
        ? mergeSubTableRowsForMiSave(binding as never, {
            existing,
            uiRows: rows,
            primaryKeyFields: Array.isArray((binding as { primaryKeyFields?: string[] }).primaryKeyFields)
              ? (binding as { primaryKeyFields?: string[] }).primaryKeyFields
              : null,
            isOwnRow: ownRowPredicate,
          })
        : rows
      let out = cloneSubTableRows(
        options.isMiSubTaskMode.value && isMiParticipantScopedSubTableBinding(binding)
          ? collapseMiLinkChildRowsToOnePerParticipant(merged)
          : merged,
      )
      // #1446: live binding rows may still carry the #1435 corrupt id_idw mirror (rows created
      // before the seed-side guard, or hydrated from corrupt persisted slices) and would reinfect
      // the payload after the snapshot scrub above. Scrub the merged binding output too — link
      // bindings only, never collection slices.
      if (
        miParentIdIdw != null
        && miCollectionSliceKeys != null
        && !miCollectionSliceKeys.has(String(binding.bindingId))
      ) {
        const wrap: Record<string, unknown> = { rows: out }
        scrubMiCorruptLinkChildRowsForParent(wrap, miParentIdIdw, { skipSliceKeys: null })
        out = wrap.rows as typeof out
      }
      /**
       * 声明「用户把**自己名下**这张表的行删光了」。
       *
       * <p>条件必须同时满足，缺一不可：
       * <ul>
       *   <li>MI 模式、且这是**参与者私有**的切片（shared 表由后端直接透传，不走这条路）；</li>
       *   <li>**归属谓词可用**（`isOwnRow != null`）。判不出归属就不能声明，
       *       否则后端会按 FK 清行，而我们其实无法确认清掉的是不是自己的；</li>
       *   <li>界面上**属于我的**行为 0 —— 注意判据是「我的行数」而不是「整个切片为空」：
       *       提交的切片里始终还带着**其他参与者**的行（实测 submitted=3 peers / baseline=4），
       *       所以「切片为空」这个条件在真机上**永远不成立**，delete-last 因此一直没生效。</li>
       * </ul>
       * 三条都满足才发这个 key，后端也只对声明过的 key 清「我的」行 —— 其余情况一律保持基线。
       */
      if (
        options.isMiSubTaskMode.value
        && ownRowPredicate != null
        && isMiParticipantScopedSubTableBinding(binding)
        && !(out as unknown[]).some(row => ownRowPredicate(row))
        // 只有「原本有我的行、现在没了」才算删除。基线里本来就没有我的行时不发声明 ——
        // 那不是删除，是这张表我还没有行；发出去只是让后端对零行做一次空转。
        && (Array.isArray(existing) ? existing.some(row => ownRowPredicate(row)) : false)
      ) {
        const emptiedKey = subTableStoreKey(binding)
        if (emptiedKey) emptiedSubTableKeys.push(emptiedKey)
      }
      // One canonical key per designer table. The previous code also wrote the bindingId key and
      // then arbitrated, via `nameMissing` / `subTableSliceUnchanged`, which binding got to be the
      // last writer of the shared table-name alias — an arbitration only needed because several
      // bindings each held their own copy. With a single key there is no second copy to lose to,
      // so an unchanged list-only binding writing the same rows is a no-op rather than a clobber.
      stampBindingTableNameAliases(subTables, subTableData, binding, out)
    }

    if (!options.isMiSubTaskMode.value) {
      syncStaleSiblingSubTableSlicesFromActiveBindings(
        subTables,
        options.subTableBindings.value,
        snapshot,
      )
      for (const binding of options.subTableBindings.value) {
        if (!Array.isArray(binding.formFields) || binding.formFields.length === 0) continue
        const live = subTables[String(binding.bindingId)]
        if (!Array.isArray(live) || live.length === 0) continue
        stampBindingTableNameAliases(subTables, subTableData, binding, live)
      }
    } else {
      // #1446: link-form (People) edits must also reach the same relation table's stale sibling
      // slices (other nodes' binding ids), or reload hydrates the old value. Update-only by row PK;
      // MI collection slices stay excluded (09be69f8 / #1442 leak guards).
      const collectionSliceKeys = buildMiCollectionSliceKeySet(
        options.subTableBindings.value,
        options.bindingRelationTableMap?.value ?? new Map<number, number | null>(),
        options.miSubProcessScopeName?.value,
      )
      // MI collection 的设计器主键 —— 用来识别「主键就是参与者键」的 binding，
      // 那种 binding 不能驱动兄弟切片同步（见被调函数说明）。读配置，不猜列名。
      const miCollectionPk = options.subTableBindings.value
        .filter(b => bindingDeclaresMiParticipantRow(b as never))
        .flatMap(b => (b as { primaryKeyFields?: string[] | null }).primaryKeyFields ?? [])
      for (const binding of options.subTableBindings.value) {
        syncMiLinkChildEditedRowsIntoSiblingSlices(
          subTables,
          binding,
          subTables[String(binding.bindingId)],
          collectionSliceKeys,
          miCollectionPk,
        )
      }
    }

    return {
      formData: { __subTables__: subTables },
      subTableData,
      emptiedSubTableKeys,
    }
  }

  function buildCurrentTaskFormSubmitPayload() {
    const subTablePayload = buildSubTableSubmitPayload()
    return {
      formData: {
        ...formData.value,
        ...subTablePayload.formData
      },
      subTableData: subTablePayload.subTableData,
      baselineValues: taskFormDTO.value?.fieldValues || {},
      // 传输元数据，**刻意放在 formData 之外**：approve/complete 链路会把 formData 整体
      // 灌进流程变量（Object.assign(variables, formData)），放进去就会被当成业务变量持久化。
      emptiedSubTableKeys: subTablePayload.emptiedSubTableKeys,
    }
  }

  async function saveCurrentTaskForm() {
    if (formReadOnly.value || !options.effectiveTaskId.value) return
    savingTaskForm.value = true
    try {
      const payload = buildCurrentTaskFormSubmitPayload()
      await submitTaskForm(options.effectiveTaskId.value, payload)
      // #1446: align local slices with what was just persisted; otherwise post-save
      // re-hydration (variables resync / polling) reverts the link form to the
      // page-load snapshot until a full refresh.
      formData.value = { ...formData.value, __subTables__: payload.formData.__subTables__ }
      ElMessage.success(t('task.operationSuccess'))
    } catch (error) {
      console.error('[TaskForm] save failed:', error)
      ElMessage.error(t('task.operationFailed'))
    } finally {
      savingTaskForm.value = false
    }
  }

  function scheduleSubTableAutosave() {
    if (formReadOnly.value || options.isCompletedTask.value || options.isMiSubTaskMode.value) return
    if (!options.effectiveTaskId.value) return
    if (subTableAutosaveTimer) clearTimeout(subTableAutosaveTimer)

    subTableAutosaveTimer = setTimeout(async () => {
      subTableAutosaveTimer = null
      try {
        await submitTaskForm(options.effectiveTaskId.value, {
          ...buildSubTableSubmitPayload(),
          baselineValues: {}
        })
      } catch (error) {
        console.error('[SubTable] autosave failed:', error)
      }
    }, 400)
  }

  function getCurrentFormFieldKeys(): string[] {
    const keys = new Set(collectLeafFormFieldKeys(formFields.value, formTabs.value))
    for (const key of collectLeafFormFieldKeys(formFieldsAfterTabs.value)) {
      keys.add(key)
    }
    return Array.from(keys)
  }

  function clearAutosaveTimer() {
    if (subTableAutosaveTimer) {
      clearTimeout(subTableAutosaveTimer)
      subTableAutosaveTimer = null
    }
  }

  return {
    formFields,
    formTabs,
    formFieldsAfterTabs,
    formData,
    currentFormName,
    formReadOnly,
    formLabelWidth,
    formFormOptions,
    savingTaskForm,
    taskFormDTO,
    saveCurrentTaskForm,
    buildCurrentTaskFormSubmitPayload,
    buildSubTableSubmitPayload,
    scheduleSubTableAutosave,
    getCurrentFormFieldKeys,
    clearAutosaveTimer
  }
}
