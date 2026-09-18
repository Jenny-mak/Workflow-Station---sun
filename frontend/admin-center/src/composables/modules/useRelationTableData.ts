/**
 * Relation Table Data 业务逻辑 composable
 *
 * 封装 relation-table/data/index.vue 所有状态、API、CRUD。
 * 组件仅保留 template + 调用此 composable。
 */

import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { isFkHidden, isFkReadonly, type FieldFkMeta } from '@platform-shared/tableFkRuntime'
import { notifySuccess, notifyError, notifyConfirm } from '@/utils/notify'
import { relationTableDataApi, type RelationTableResponse, type RelationTableDataRow, type FieldDefinitionResponse, type RelationImportResult, type LookupConfig, type LookupFilterCondition } from '@/api/relationTable'
import { buildDerivedFilterConditions, resolveDerivedLookup, normalizeLookupValueForSave, type FieldLike } from '@/components/lookup/useLookupBehaviors'
import { useAdminListGrid } from '@/composables/list/useAdminListGrid'
import { exportTableCsv } from '@platform-shared/list/tableExport'

export const RT_DATA_STATUS_COL_WIDTH = 100
export const RT_DATA_ACTIONS_COL_WIDTH = 240

export function useRelationTableData() {
  const route = useRoute()
  const tableListLoading = ref(false)
  const dataLoading = ref(false)
  const exporting = ref(false)
  const saving = ref(false)
  const tables = ref<RelationTableResponse[]>([])
  const selectedTableId = ref<number | null>(null)

  const searchKeyword = ref('')
  const tableSearchKeyword = ref('')
  const fetchDataError = ref<string | null>(null)

  const dialogVisible = ref(false)
  const dialogMode = ref<'add' | 'edit'>('add')
  const editingRowId = ref<string | null>(null)
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- dynamic column keys from relation table schema
  const formData = ref<Record<string, any>>({})

  const localStatusMap = ref<Record<string, string>>({})

  // Selected lookup rows keyed by field name — feeds the backfill panel and derived cascade.
  const lookupSelectedData = ref<Record<string, Record<string, any> | null>>({})

  const SYSTEM_COLUMNS = new Set(['created_at', 'created_by', 'updated_at', 'updated_by', 'status'])

  const exportingTemplate = ref(false)
  const importDialogVisible = ref(false)
  const importing = ref(false)
  const importResult = ref<RelationImportResult | null>(null)
  const pendingImportFile = ref<File | null>(null)

  // ---- Computed ----
  const selectedTable = computed(() => tables.value.find(t => t.id === selectedTableId.value) ?? null)

  /** Whether the current admin may write (add/edit/inactive/import) on the selected table. */
  const canWrite = computed(() => selectedTable.value?.permissionLevel !== 'READONLY')

  const fieldColumns = computed<FieldDefinitionResponse[]>(() => {
    if (!selectedTable.value?.fieldDefinitions) return []
    return selectedTable.value.fieldDefinitions
      .filter(f => !SYSTEM_COLUMNS.has(f.fieldName))
      .sort((a, b) => {
        if (a.isPrimaryKey && !b.isPrimaryKey) return -1
        if (!a.isPrimaryKey && b.isPrimaryKey) return 1
        return a.sortOrder - b.sortOrder
      })
  })

  /**
   * Deployed Tables panel list: scoped to the Function Unit selected via the nav sidebar
   * (route.params.functionUnitCode), then narrowed further by the search box. FU grouping
   * itself is now the nav sidebar's job (Relation Tables > Table Data > <Function Unit>) —
   * this panel just lists that FU's tables (or all tables when no FU is selected).
   */
  const filteredTables = computed(() => {
    const fuCode = route.params.functionUnitCode as string | undefined
    const scoped = fuCode
      ? tables.value.filter(t => (t.functionUnits || []).some(fu => fu.code === fuCode))
      : tables.value
    const kw = tableSearchKeyword.value.trim().toLowerCase()
    if (!kw) return scoped
    return scoped.filter(t => (t.displayName || '').toLowerCase().includes(kw) || (t.tableName || '').toLowerCase().includes(kw))
  })

  const isNumericType = (dt: string) => ['INTEGER', 'BIGINT', 'DECIMAL'].includes(dt)

  const isRowDisabled = (row: RelationTableDataRow): boolean => {
    const local = localStatusMap.value[row.rowId]
    if (local) return local === 'INACTIVE'
    const status = (row.data as unknown as Record<string, unknown>)?.status ?? (row as unknown as Record<string, unknown>).status ?? ''
    return String(status).toUpperCase() === 'DISABLED' || String(status).toUpperCase() === 'INACTIVE'
  }

  const toFkMeta = (f: { fieldName: string; isForeignKey?: boolean; fkDisplayMode?: string | null }): FieldFkMeta => ({
    fieldName: f.fieldName,
    isForeignKey: f.isForeignKey,
    fkDisplayMode: (f.fkDisplayMode ?? undefined) as FieldFkMeta['fkDisplayMode'],
  })

  const grid = useAdminListGrid<RelationTableDataRow>({
    storageKey: 'admin-list-layout:rt-data',
    extraWidth: () => RT_DATA_STATUS_COL_WIDTH + (canWrite.value ? RT_DATA_ACTIONS_COL_WIDTH : 0),
  })

  const hiddenFkFields = (): Set<string> => new Set(
    (selectedTable.value?.fieldDefinitions ?? [])
      .filter(f => isFkHidden(toFkMeta(f)))
      .map(f => f.fieldName),
  )

  const resetGridQuery = () => {
    grid.columnFilters.value = {}
    grid.sort.field = null
    grid.sort.direction = null
    grid.resetPage()
  }

  // ---- Fetch ----
  const fetchTables = async () => {
    tableListLoading.value = true
    try {
      tables.value = await relationTableDataApi.getDeployedTables()
      if (!selectedTableId.value && tables.value.length > 0) {
        // Nav sidebar "Table Data > <Function Unit>" links here with the FU code as a route
        // param — land on that FU's first table instead of the global first table.
        selectedTableId.value = (filteredTables.value[0] ?? tables.value[0]).id
        await fetchData()
      }
    } catch { tables.value = [] }
    finally { tableListLoading.value = false }
  }

  const fetchData = async () => {
    if (!selectedTableId.value) return
    const seq = grid.beginQuery()
    dataLoading.value = true
    fetchDataError.value = null
    try {
      const page = await relationTableDataApi.query(selectedTableId.value, {
        ...grid.buildQuery(),
        search: searchKeyword.value || undefined,
      })
      if (!grid.isCurrentQuery(seq)) return
      const hidden = hiddenFkFields()
      grid.applyPage({
        ...page,
        columns: Array.isArray(page.columns)
          ? page.columns.filter(c => !hidden.has(c.field))
          : page.columns,
      }, 'relation-tables/data/query response is missing its column declaration')
    } catch (e: unknown) {
      if (!grid.isCurrentQuery(seq)) return
      const err = e as { response?: { data?: { error?: { message?: string; traceId?: string }; message?: string; traceId?: string }; status?: number }; message?: string }
      const apiMsg = err?.response?.data?.error?.message || err?.response?.data?.message
      const traceId = err?.response?.data?.error?.traceId || err?.response?.data?.traceId
      fetchDataError.value = traceId ? `Data load failed: ${apiMsg || err?.message} (traceId: ${traceId})` : `Data load failed: ${apiMsg || err?.message || 'Failed to load data'}`
    } finally {
      if (grid.isCurrentQuery(seq)) dataLoading.value = false
    }
  }

  let searchTimer: ReturnType<typeof setTimeout> | undefined
  watch(searchKeyword, () => {
    clearTimeout(searchTimer)
    searchTimer = setTimeout(() => {
      grid.resetPage()
      void fetchData()
    }, 300)
  })
  onBeforeUnmount(() => clearTimeout(searchTimer))

  // ---- Navigation ----
  const handleSelectTable = (index: string) => {
    selectedTableId.value = Number(index)
    searchKeyword.value = ''
    localStatusMap.value = {}
    fetchDataError.value = null
    resetGridQuery()
    void fetchData()
  }

  const visibleFieldColumns = computed(() =>
    fieldColumns.value.filter(f => !isFkHidden(toFkMeta(f))),
  )

  const isFkFieldDisabled = (field: FieldDefinitionResponse) => isFkReadonly(toFkMeta(field))

  const pkStrategy = (field: FieldDefinitionResponse): string =>
    (field.pkGeneration as { strategy?: string } | undefined)?.strategy ?? 'uuid'

  /**
   * Primary key fields are non-editable by default. The only exception is the `manual`
   * strategy in add mode, where the user must type the key themselves. On edit the PK is
   * always locked; on add with any auto strategy (uuid/sequence/…) it is auto-generated + locked.
   */
  const isPkFieldDisabled = (field: FieldDefinitionResponse): boolean => {
    if (!field.isPrimaryKey) return false
    if (dialogMode.value === 'edit') return true
    return pkStrategy(field) !== 'manual'
  }

  const openAddDialog = async () => {
    if (!selectedTableId.value) return
    dialogMode.value = 'add'
    editingRowId.value = null
    formData.value = {}
    lookupSelectedData.value = {}
    for (const f of fieldColumns.value) {
      if (f.isForeignKey && f.fkDisplayMode === 'hidden') continue
      ;(formData.value as Record<string, unknown>)[f.fieldName] =
        f.defaultValue ?? (f.dataType === 'BOOLEAN' ? false : (f.dataType === 'LOOKUP' && f.lookupConfig?.multiple ? [] : null))
    }
    try {
      for (const f of fieldColumns.value) {
        if (!f.isPrimaryKey) continue
        const strategy = (f.pkGeneration as { strategy?: string } | undefined)?.strategy ?? 'uuid'
        if (strategy === 'manual') continue
        const res = await relationTableDataApi.allocatePrimaryKeys(selectedTableId.value, {
          fieldName: f.fieldName,
        })
        const values = (res as { values?: string[] })?.values ?? (res as { data?: { values?: string[] } })?.data?.values
        if (values?.[0] != null) {
          ;(formData.value as Record<string, unknown>)[f.fieldName] = values[0]
        }
      }
      dialogVisible.value = true
    } catch (e: unknown) {
      notifyError((e as Error)?.message || 'Failed to prepare new record')
    }
  }

  const openEditDialog = (row: RelationTableDataRow) => {
    dialogMode.value = 'edit'; editingRowId.value = row.rowId; formData.value = {}
    lookupSelectedData.value = {}
    fieldColumns.value.forEach(f => { (formData.value as Record<string, unknown>)[f.fieldName] = row.data?.[f.fieldName] ?? null })
    dialogVisible.value = true
  }

  // ---- LOOKUP fields ----
  const fieldsAsLike = (): FieldLike[] =>
    fieldColumns.value.map(f => ({ fieldName: f.fieldName, dataType: f.dataType, lookupConfig: f.lookupConfig }))

  const isLookupField = (f: FieldDefinitionResponse) => f.dataType === 'LOOKUP'

  /** Effective filter conditions for a lookup field, incl. cascade from its parent's selected row. */
  const lookupFilterConditionsFor = (field: FieldDefinitionResponse): LookupFilterCondition[] => {
    const cfg = field.lookupConfig
    const base = cfg?.filterConditions || []
    const parent = cfg?.derivedFrom?.parentField
    if (!parent) return base
    return buildDerivedFilterConditions(base, cfg, lookupSelectedData.value[parent])
  }

  /** When a lookup value is picked: store the row (for backfill) and drive any dependent lookups. */
  const onLookupSelect = async (field: FieldDefinitionResponse, row: Record<string, any> | null) => {
    lookupSelectedData.value = { ...lookupSelectedData.value, [field.fieldName]: row }
    // Resolve every dependent lookup whose parent is this field (autofill mode).
    for (const dep of fieldColumns.value) {
      if (dep.dataType !== 'LOOKUP') continue
      if (dep.lookupConfig?.derivedFrom?.parentField !== field.fieldName) continue
      const res = await resolveDerivedLookup(
        { fieldName: dep.fieldName, dataType: dep.dataType, lookupConfig: dep.lookupConfig },
        row,
        fieldsAsLike(),
      )
      if (!res.skip) {
        ;(formData.value as Record<string, unknown>)[dep.fieldName] = res.value
      }
    }
  }

  const onLookupClear = (field: FieldDefinitionResponse) => {
    lookupSelectedData.value = { ...lookupSelectedData.value, [field.fieldName]: null }
    // Clear dependents' derived values.
    for (const dep of fieldColumns.value) {
      if (dep.dataType === 'LOOKUP' && dep.lookupConfig?.derivedFrom?.parentField === field.fieldName
          && dep.lookupConfig?.derivedFrom?.derivedMode === 'autofill') {
        ;(formData.value as Record<string, unknown>)[dep.fieldName] = dep.lookupConfig?.multiple ? [] : null
      }
    }
  }

  const lookupViewFieldsFor = (field: FieldDefinitionResponse) => {
    const cfg = field.lookupConfig
    return (cfg?.displayFields || []).map((fn, i) => ({ fieldName: fn, displayLabel: fn, sortOrder: i, visible: true }))
  }

  const handleSaveRecord = async () => {
    if (!selectedTableId.value) return
    saving.value = true
    try {
      // Map lookup field name → its config so we can normalize row objects → stored PKs.
      const lookupCfgByField = new Map<string, LookupConfig>()
      for (const f of fieldColumns.value) {
        if (f.dataType === 'LOOKUP' && f.lookupConfig) lookupCfgByField.set(f.fieldName, f.lookupConfig)
      }
      const cleanData: Record<string, unknown> = {}
      for (const [key, val] of Object.entries(formData.value)) {
        if (lookupCfgByField.has(key)) {
          const pk = normalizeLookupValueForSave(val, lookupCfgByField.get(key))
          if (pk !== null && pk !== undefined && pk !== '' && !(Array.isArray(pk) && pk.length === 0)) {
            cleanData[key] = pk
          }
          continue
        }
        if (val !== null && val !== undefined && val !== '') cleanData[key] = val
      }
      if (dialogMode.value === 'add') {
        await relationTableDataApi.addData(selectedTableId.value, cleanData)
        notifySuccess('Record added')
      } else if (editingRowId.value) {
        await relationTableDataApi.updateData(selectedTableId.value, editingRowId.value, cleanData)
        notifySuccess('Record updated')
      }
      dialogVisible.value = false; await fetchData()
    } catch (e: unknown) {
      const err = e as { response?: { data?: { error?: { message?: string }; message?: string } }; message?: string }
      notifyError(err?.response?.data?.error?.message || err?.response?.data?.message || err?.message || 'Save failed')
    } finally { saving.value = false }
  }

  const handleDisable = async (row: RelationTableDataRow) => {
    if (!selectedTableId.value) return
    try {
      await relationTableDataApi.changeStatus(selectedTableId.value, row.rowId, 'INACTIVE')
      localStatusMap.value = { ...localStatusMap.value, [row.rowId]: 'INACTIVE' }
      notifySuccess('Record set to inactive'); await fetchData()
    } catch (e: unknown) { notifyError((e as Error)?.message || 'Failed') }
  }

  const handleEnable = async (row: RelationTableDataRow) => {
    if (!selectedTableId.value) return
    try {
      await relationTableDataApi.changeStatus(selectedTableId.value, row.rowId, 'ACTIVE')
      localStatusMap.value = { ...localStatusMap.value, [row.rowId]: 'ACTIVE' }
      notifySuccess('Record set to active'); await fetchData()
    } catch (e: unknown) { notifyError((e as Error)?.message || 'Failed') }
  }

  const handleDelete = async (row: RelationTableDataRow) => {
    if (!selectedTableId.value) return
    try { await notifyConfirm('Are you sure to delete this record?', 'Confirm', { type: 'warning' }) }
    catch { return }
    try { await relationTableDataApi.deleteData(selectedTableId.value, row.rowId); notifySuccess('Record deleted'); await fetchData() }
    catch (e: unknown) { notifyError((e as Error)?.message || 'Delete failed') }
  }

  const formatHKT = (value: unknown): string => {
    if (value == null || value === '') return ''
    try { const d = new Date(value as string); return isNaN(d.getTime()) ? String(value) : d.toLocaleString('en-HK', { timeZone: 'Asia/Hong_Kong', hour12: false }) }
    catch { return String(value) }
  }

  const handleExport = async () => {
    if (!selectedTableId.value) return
    if (grid.gridSelectedRows.value.length > 0) {
      exportTableCsv({
        rows: grid.gridSelectedRows.value,
        columns: grid.displayColumns.value,
        filename: selectedTable.value?.displayName || selectedTable.value?.tableName || 'data',
      })
      notifySuccess('Export completed')
      return
    }
    exporting.value = true
    try {
      const blob = await relationTableDataApi.exportCsv(selectedTableId.value)
      const url = window.URL.createObjectURL(blob as Blob)
      const link = document.createElement('a'); link.href = url
      link.setAttribute('download', `${selectedTable.value?.displayName || selectedTable.value?.tableName || 'data'}.csv`)
      document.body.appendChild(link); link.click(); link.remove(); window.URL.revokeObjectURL(url)
      notifySuccess('Export completed')
    } catch { notifyError('Export failed') }
    finally { exporting.value = false }
  }

  const handleDownloadTemplate = async (format: 'csv' | 'xlsx') => {
    if (!selectedTableId.value) return
    exportingTemplate.value = true
    try {
      const blob = await relationTableDataApi.downloadTemplate(selectedTableId.value, format)
      const url = window.URL.createObjectURL(blob as Blob)
      const link = document.createElement('a'); link.href = url
      const name = selectedTable.value?.displayName || selectedTable.value?.tableName || 'template'
      link.setAttribute('download', `${name}-template.${format}`)
      document.body.appendChild(link); link.click(); link.remove(); window.URL.revokeObjectURL(url)
    } catch { notifyError('Template download failed') }
    finally { exportingTemplate.value = false }
  }

  const openImportDialog = () => {
    importResult.value = null
    pendingImportFile.value = null
    importDialogVisible.value = true
  }

  const importErrorMessage = (e: unknown, fallback: string): string => {
    const err = e as { response?: { data?: { error?: { message?: string }; message?: string } }; message?: string }
    return err?.response?.data?.error?.message || err?.response?.data?.message || err?.message || fallback
  }

  // Step 1: selecting a file validates only (dryRun) and shows a preview; nothing is inserted yet.
  const handleImportFile = async (file: File) => {
    if (!selectedTableId.value) return
    pendingImportFile.value = file
    importing.value = true
    importResult.value = null
    try {
      const format = file.name.toLowerCase().endsWith('.xlsx') ? 'xlsx' : 'csv'
      importResult.value = await relationTableDataApi.importData(selectedTableId.value, file, format, true)
    } catch (e: unknown) {
      pendingImportFile.value = null
      notifyError(importErrorMessage(e, 'Validation failed'))
    } finally { importing.value = false }
  }

  // Step 2: user confirms -> re-validate + insert server-side (dryRun=false).
  const handleConfirmImport = async () => {
    if (!selectedTableId.value || !pendingImportFile.value) return
    importing.value = true
    try {
      const file = pendingImportFile.value
      const format = file.name.toLowerCase().endsWith('.xlsx') ? 'xlsx' : 'csv'
      const res = await relationTableDataApi.importData(selectedTableId.value, file, format, false)
      importResult.value = res
      pendingImportFile.value = null
      if ((res.inserted ?? 0) > 0) {
        notifySuccess(`Imported ${res.inserted} row(s)${res.failed ? `, ${res.failed} failed` : ''}`)
        await fetchData()
      } else if (res.failed > 0) {
        notifyError(`All ${res.failed} row(s) failed validation`)
      }
    } catch (e: unknown) {
      notifyError(importErrorMessage(e, 'Import failed'))
    } finally { importing.value = false }
  }

  // ---- Lifecycle ----
  const init = () => fetchTables()
  const refresh = () => { fetchTables(); if (selectedTableId.value) fetchData() }

  return {
    tableListLoading, dataLoading, exporting, saving,
    exportingTemplate, importDialogVisible, importing, importResult,
    tables, selectedTableId, searchKeyword, tableSearchKeyword,
    fetchDataError, dialogVisible, dialogMode, editingRowId, formData,
    selectedTable, canWrite, fieldColumns, visibleFieldColumns, filteredTables,
    isNumericType, isRowDisabled, isFkFieldDisabled, isPkFieldDisabled,
    lookupSelectedData, isLookupField, lookupFilterConditionsFor, onLookupSelect, onLookupClear, lookupViewFieldsFor,
    fetchTables, fetchData, handleSelectTable,
    openAddDialog, openEditDialog, handleSaveRecord, handleDisable, handleEnable, handleDelete,
    formatHKT, handleExport, handleDownloadTemplate, openImportDialog, handleImportFile, handleConfirmImport, init, refresh,
    grid,
    RT_DATA_STATUS_COL_WIDTH,
    RT_DATA_ACTIONS_COL_WIDTH,
  }
}
