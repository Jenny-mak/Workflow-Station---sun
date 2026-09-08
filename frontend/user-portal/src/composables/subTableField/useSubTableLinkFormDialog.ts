import { computed, ref, unref, type Ref } from 'vue'
import { PLATFORM_ROW_UUID_FIELD } from '@/utils/subTableRowIdentity'
import { mergeSubTableRowsByRowId, stripLinkFormDesignerTableLabel } from '@/composables/tasks/shared'
import type { Column, SubTableBinding, SubTableFieldEmit, SubTableFieldProps, SubTableFieldT } from './subTableFieldTypes'
import {
  isLinkFormBoundToHostGrid,
  isMiStyleParentRowForLinkForm,
  linkFormTableMatchKey,
  mergeSelfBoundLinkFormIntoParentRow,
  normalizeFkIdForMatch,
  subTableBindingMatches
} from './subTableLinkFormRowMatch'
import {
  collectLinkTargetFormFieldKeys,
  linkFormRowsLackFormPayload,
  maxFormFieldOverlapScore,
  peerSubTableDataByFormFieldOverlap,
  seedLinkedFormDataFromFields
} from './subTableLinkFormFields'

/** Link Form detail modal: dialog state, binding resolution and save/close flow. */
export function useSubTableLinkFormDialog(
  props: SubTableFieldProps,
  rows: Ref<any[]>,
  emit: SubTableFieldEmit,
  t: SubTableFieldT,
  deps: {
    resolveSubTableRowPk: (row: Record<string, unknown> | null | undefined) => string | number | null
    filterRowsByMiLinkFormParent: (parentRow: Record<string, any>, rows: any[], binding?: SubTableBinding) => any[]
  },
) {
  const { resolveSubTableRowPk, filterRowsByMiLinkFormParent } = deps

  const linkFormDialogVisible = ref(false)
  const linkFormModalPanelRef = ref<HTMLElement | null>(null)
  const activeLinkColumn = ref<Column | null>(null)
  const activeLinkRowIndex = ref<number | null>(null)
  const linkedSubTableRows = ref<any[]>([])
  const linkedFormData = ref<Record<string, any>>({})
  /** When footer is shown (To Do), restore on Cancel/X without persisting to parent row. */
  const linkFormDialogSnapshot = ref<{ linkedFormData: Record<string, any>; linkedSubTableRows: any[] } | null>(null)

  /**
   * Multiple bindings can share the same bindingId (prev vs current). `.find` always picked the first;
   * for read-only / snapshot UI (`suppressLinkFormInitialData` false), prefer the first match that
   * already has row data so Details is not blank. MI todo (`suppress` true) keeps the first match
   * so isolated empty binding wins → blank Details for a new sub-task.
   */
  function resolveLinkBindingForColumn(col: Column | null | undefined): SubTableBinding | undefined {
    if (!col) return undefined
    const list = props.linkedSubTableBindings ?? []
    const boundId = col.props?.boundSubTableBindingId
    const boundNameRaw = col.props?.boundSubTableName ? String(col.props.boundSubTableName).trim() : ''
    const boundNameStripped = stripLinkFormDesignerTableLabel(boundNameRaw)
    const boundKey = linkFormTableMatchKey(boundNameRaw)

    const matches = list.filter(item => {
      if (boundId != null && Number(item.bindingId) === Number(boundId)) return true
      if (boundNameRaw && item.tableName === boundNameRaw) return true
      if (boundKey && linkFormTableMatchKey(item.tableName) === boundKey) return true
      if (boundNameStripped || boundId != null) {
        const pid = boundId != null ? Number(boundId) : -2147483648
        return subTableBindingMatches(
          {
            bindingId: pid,
            tableName: boundNameStripped || boundNameRaw,
            tableId: null,
            designerTableName: undefined
          },
          {
            bindingId: item.bindingId,
            tableName: item.tableName,
            tableId: item.tableId ?? null,
            designerTableName: item.designerTableName
          }
        )
      }
      return false
    })
    if (matches.length === 0) return undefined
    if (props.suppressLinkFormInitialData) return matches[0]
    const withData = matches.find(m => Array.isArray(m.data) && m.data.length > 0)
    return withData ?? matches[0]
  }

  const selectedLinkBinding = computed(() => {
    const col = activeLinkColumn.value
    return resolveLinkBindingForColumn(col) ?? null
  })

  /** Modal title: bound sub-table name + i18n (do not use list column label — avoids stale "ADD + …" text). */
  const linkFormModalTitle = computed(() => {
    const col = activeLinkColumn.value
    const fromProp = col?.props?.boundSubTableName ? String(col.props.boundSubTableName).trim() : ''
    const fromBinding = selectedLinkBinding.value?.tableName ? String(selectedLinkBinding.value.tableName).trim() : ''
    const tableName = fromProp || fromBinding
    if (tableName) return t('linkForm.dialogTitleAddTable', { tableName })
    return t('linkForm.linkedForm')
  })

  const linkedFormFields = computed(() => selectedLinkBinding.value?.formFields || [])
  // 弹窗内强制 auto（与 DW SubTableFormDialog 一致）：设计器配置的固定 labelWidth
  // 会让超长 label 折行/错位，EP auto 取最长 label 整列严格对齐
  const linkedFormLabelWidth = computed(() => 'auto')
  const canEditSelectedLinkBinding = computed(() => !!(props.editable && selectedLinkBinding.value?.bindingMode === 'EDITABLE'))

  /** Field-layout link form only; grid fallback has no footer. */
  const showLinkFormDetailActionFooter = computed(
    () =>
      !!props.showLinkFormDialogFooter &&
      canEditSelectedLinkBinding.value &&
      !!selectedLinkBinding.value &&
      linkedFormFields.value.length > 0
  )

  function resolveLinkedFallbackRows(binding?: SubTableBinding): any[] {
    if (!binding) return []
    let pool: any[] = Array.isArray(binding.data) ? [...binding.data] : []
    const mergeAllMatchingPeers = () => {
      for (const item of props.linkedSubTableBindings ?? []) {
        if (item === binding || !subTableBindingMatches(item, binding)) continue
        if (!Array.isArray(item.data) || item.data.length === 0) continue
        pool = mergeSubTableRowsByRowId(pool, item.data, binding.primaryKeyFields ?? null)
      }
    }
    if (pool.length === 0) {
      const sameTableBinding = props.linkedSubTableBindings?.find(item =>
        item !== binding &&
        Array.isArray(item.data) &&
        item.data.length > 0 &&
        subTableBindingMatches(item, binding)
      )
      pool = Array.isArray(sameTableBinding?.data) ? [...sameTableBinding.data] : []
    }
    if (
      pool.length === 0
      || (binding.formFields?.length && linkFormRowsLackFormPayload(pool, binding.formFields))
    ) {
      mergeAllMatchingPeers()
      if (binding.formFields?.length && linkFormRowsLackFormPayload(pool, binding.formFields)) {
        const overlap = peerSubTableDataByFormFieldOverlap(binding, props.linkedSubTableBindings ?? [])
        if (overlap.length > 0) {
          pool = mergeSubTableRowsByRowId(pool, overlap, binding.primaryKeyFields ?? null)
        }
      }
    }
    return pool
  }

  function buildLinkFormPeerMap(): Map<number, number | null> {
    const peerMap = new Map<number, number | null>()
    for (const b of props.linkedSubTableBindings ?? []) {
      const tid = b.tableId != null ? Number(b.tableId) : null
      if (tid != null && Number.isFinite(tid)) peerMap.set(Number(b.bindingId), tid)
    }
    return peerMap
  }

  function collectLinkFormRowsFromProcessVariables(
    binding: SubTableBinding,
    parentRow: Record<string, any> | null | undefined,
  ): any[] {
    const pd = unref(props.primaryFormData) as Record<string, unknown> | undefined
    const raw = pd?.__subTables__
    if (!raw || typeof raw !== 'object') return []
    const fieldKeys = collectLinkTargetFormFieldKeys(binding)
    if (fieldKeys.size === 0) return []
    const threshold =
      fieldKeys.size <= 2 ? 1 : Math.min(fieldKeys.size, Math.max(2, Math.ceil(fieldKeys.size * 0.25)))
    let merged: any[] = []
    for (const v of Object.values(raw as Record<string, unknown>)) {
      if (!Array.isArray(v) || v.length === 0) continue
      if (maxFormFieldOverlapScore(v, fieldKeys) < threshold) continue
      merged = mergeSubTableRowsByRowId(merged, v as any[], binding.primaryKeyFields ?? null)
    }
    if (parentRow && merged.length > 0 && isMiStyleParentRowForLinkForm(parentRow as Record<string, unknown>)) {
      merged = filterRowsByMiLinkFormParent(parentRow, merged, binding)
    }
    return merged
  }

  /**
   * 行标识：**先配置主键**，其次平台生成的行标识。取不到返回 null（= 判不出身份）。
   *
   * <p>原来是 `['row_id', 'id_idw', ...pk, 'id']` —— 把猜的名字排在配置主键前面。这三个字面量
   * 都已删除：`id` 在 dev 是 13 张表的业务列且无一是主键，`row_id` 在有些表是配置主键、
   * 另一些表是普通列，`id_idw` 在当前库里 0 行存在。判不出身份就返回 null，不猜。
   */
  function linkFormRowIdentity(row: Record<string, any> | null | undefined): string | null {
    if (!row || typeof row !== 'object') return null
    const pk = selectedLinkBinding.value?.primaryKeyFields ?? []
    for (const k of [...pk, PLATFORM_ROW_UUID_FIELD]) {
      const name = String(k ?? '').trim()
      if (!name) continue
      const v = normalizeFkIdForMatch(row[name])
      if (v != null) return v
    }
    return null
  }

  function buildLinkedFormData(
    binding?: SubTableBinding,
    opts?: { readonly?: boolean },
    /** 正在打开的那一行；用来判断 `binding.data[0]` 是不是它本人。 */
    parentRowForSeed?: Record<string, any> | null,
  ): Record<string, any> {
    /**
     * 播种用的行：**只按身份挑正在打开的那一行；挑不到就用空行，绝不退回 `data[0]`。**
     *
     * <p>`data[0]` 是池子第一行 —— 新建的父行还没有自己的数据时，它就是**上一个父行**。
     * 拿它播种等于「猜这一行是谁」：会把别人的字段值和 `__subTables__` 整份继承过来，
     * 并被持久化。实测每新建一个 transaction 就多继承一份
     * （`TRANS-000017 = TRANS-000016 的全部 + 自己的 50`）。
     *
     * <p>身份判不出时**宁可空**：空表单最多让用户重填，而猜错会把别人的数据写到这一行名下
     * —— 后者是静默的数据污染，且会顺着保存一路扩散。
     */
    const rows = Array.isArray(binding?.data) ? (binding!.data as Record<string, any>[]) : []
    const wantKey = linkFormRowIdentity(parentRowForSeed)
    const matched = wantKey == null
      ? undefined
      : rows.find(r => r && typeof r === 'object' && linkFormRowIdentity(r) === wantKey)
    const raw: Record<string, any> = matched ?? {}
    const next: Record<string, any> = {}
    const modalOpts = { readonly: opts?.readonly ?? !props.editable }
    if (binding?.formFields?.length) {
      // Descends through layout containers so a block's nested fields are seeded too
      // (an Assignment Mode block owns its pickers as children) — see the helper.
      Object.assign(next, seedLinkedFormDataFromFields(binding.formFields, raw, modalOpts))
      /**
       * 带上嵌套切片，让 `PortalFormFields` 能读到孙行、`saveLinkedFormData` 能写回去。
       *
       * <p><b>但只有当 `raw` 就是正在打开的那一行时才可以带。</b>`raw` 取自
       * `binding.data[0]`：新行还没有自己的数据时，`data[0]` 是**上一个父行** ——
       * 无条件复制会把它的 `__subTables__` 整份继承过来。嵌套切片属于**某一行**，
       * 不是这张表的公共属性。
       *
       * <p>实测（task a736e30f）每新建一个 transaction 就多继承一份：
       * `TRANS-000017 = TRANS-000016 的全部 + 自己的 50`，且**被持久化进库**，
       * 表现为「新增一条后其它的不见了、保存后又变 3 条」。
       *
       * <p>判据是行标识相等（`row_id` / 设计器主键）。判不出身份时**不带**——
       * 宁可让新行从空开始（用户自己加的行不会丢），也不能继承别人的数据。
       */
      /**
       * 只有当 `raw` **确实是**正在打开的那一行时才带上它的嵌套切片。
       *
       * <p>`raw` 只可能是「按身份挑中的本人」或空对象（见上），所以这里带上的一定是
       * 这一行自己的切片。嵌套切片属于某一行，不是这张表的公共属性。
       *
       * <p>身份判不出（新行还没分配标识）时 `raw` 为空 → 不带：宁可从空开始，
       * 用户自己加的行会通过 `onNestedRowsUpdate` 正常写进来。
       */
      if (raw.__subTables__ && typeof raw.__subTables__ === 'object') {
        next.__subTables__ = raw.__subTables__
      }
      return next
    }
    return { ...raw }
  }

  function updateLinkedFormField(key: string, value: any) {
    linkedFormData.value = { ...linkedFormData.value, [key]: value }
  }

  /** Header close / Cancel: with To Do footer, discard edits; otherwise auto-save field link form when editable. */
  function closeLinkFormDetailDialog() {
    if (showLinkFormDetailActionFooter.value) {
      const snap = linkFormDialogSnapshot.value
      if (snap) {
        linkedFormData.value = JSON.parse(JSON.stringify(snap.linkedFormData)) as Record<string, any>
        linkedSubTableRows.value = JSON.parse(JSON.stringify(snap.linkedSubTableRows)) as any[]
      }
      linkFormDialogVisible.value = false
      linkFormDialogSnapshot.value = null
      return
    }
    if (
      canEditSelectedLinkBinding.value &&
      selectedLinkBinding.value &&
      linkedFormFields.value.length > 0
    ) {
      saveLinkedFormData()
      return
    }
    linkFormDialogVisible.value = false
  }

  function saveLinkedFormData() {
    const linkRowIndex = activeLinkRowIndex.value
    const col = activeLinkColumn.value
    if (linkRowIndex == null || !col) {
      linkFormDialogSnapshot.value = null
      linkFormDialogVisible.value = false
      return
    }

    const boundId = col.props?.boundSubTableBindingId
    const binding = selectedLinkBinding.value
    const boundName = col.props?.boundSubTableName || binding?.tableName

    const currentRows = linkedSubTableRows.value.length > 0 ? [...linkedSubTableRows.value] : [{}]
    currentRows[0] = { ...currentRows[0], ...linkedFormData.value }
    const parentRow = linkRowIndex != null ? rows.value[linkRowIndex] : null
    if (parentRow && isMiStyleParentRowForLinkForm(parentRow as Record<string, unknown>)) {
      const parentKey =
        normalizeFkIdForMatch(parentRow.id_idw)
        ?? normalizeFkIdForMatch(resolveSubTableRowPk(parentRow as Record<string, unknown>))
      if (parentKey != null) {
        const curId = normalizeFkIdForMatch(currentRows[0]?.id)
        if (curId == null || curId !== parentKey) {
          const n = Number(parentKey)
          currentRows[0] = {
            ...currentRows[0],
            id: Number.isFinite(n) && String(n) === parentKey ? n : parentKey
          }
        }
      }
    }
    linkedSubTableRows.value = [...currentRows]

    const selfBound = isLinkFormBoundToHostGrid(col, props, binding)
    const nextMainRows = rows.value.map((r, idx) => {
      if (idx !== linkRowIndex) return r
      if (selfBound && r && typeof r === 'object') {
        return mergeSelfBoundLinkFormIntoParentRow(r, currentRows[0] || {})
      }
      const base = (r && typeof r === 'object') ? { ...r } : {}
      const sub = { ...((base.__subTables__ && typeof base.__subTables__ === 'object') ? base.__subTables__ : {}) } as Record<string, any>
      if (boundId != null) {
        sub[boundId] = currentRows
        sub[String(boundId)] = currentRows
      }
      if (boundName) {
        sub[boundName] = currentRows
        sub[String(boundName)] = currentRows
      }
      /**
       * 弹窗里**嵌套子表**的增删，跟着 `linkedFormData.__subTables__` 一起回到父行。
       *
       * <p>上面只把 link form 自己的行写进 `boundId` / `boundName` 两个 key。嵌套那张表
       * （ATM Transaction 的 Details 里那个 ATM Correspondence）用的是**规范 key**
       * `dw:<设计器表名>`，读取端也只认这个 key —— 不带上它，弹窗里的删除/新增
       * 在保存时被整个丢掉（实测 task a736e30f：删 Corr-000039 无效）。
       *
       * <p>逐 key 覆盖而不是整体替换：`base.__subTables__` 里可能还有本次没编辑的其它切片。
       */
      const lfdSto = (linkedFormData.value as Record<string, any>)?.__subTables__
      if (lfdSto && typeof lfdSto === 'object' && !Array.isArray(lfdSto)) {
        for (const [k, v] of Object.entries(lfdSto as Record<string, unknown>)) {
          if (Array.isArray(v)) sub[k] = v
        }
      }
      base.__subTables__ = sub
      return base
    })

    emit('update:modelValue', nextMainRows)
    linkFormDialogSnapshot.value = null
    linkFormDialogVisible.value = false
  }

  function handleLinkedSubTableUpdate(rows: any[]) {
    linkedSubTableRows.value = [...rows]
    const bindingId = selectedLinkBinding.value?.bindingId
    if (bindingId != null) {
      emit('update:linkedSubTableData', bindingId, rows)
    }
  }

  return {
    linkFormDialogVisible,
    linkFormModalPanelRef,
    activeLinkColumn,
    activeLinkRowIndex,
    linkedSubTableRows,
    linkedFormData,
    linkFormDialogSnapshot,
    resolveLinkBindingForColumn,
    selectedLinkBinding,
    linkFormModalTitle,
    linkedFormFields,
    linkedFormLabelWidth,
    canEditSelectedLinkBinding,
    showLinkFormDetailActionFooter,
    resolveLinkedFallbackRows,
    buildLinkFormPeerMap,
    collectLinkFormRowsFromProcessVariables,
    buildLinkedFormData,
    updateLinkedFormField,
    closeLinkFormDetailDialog,
    saveLinkedFormData,
    handleLinkedSubTableUpdate
  }
}
