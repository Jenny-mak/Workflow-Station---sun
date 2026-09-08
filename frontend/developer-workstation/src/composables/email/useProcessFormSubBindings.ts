import { ref, watch, type Ref } from 'vue'
import { functionUnitApi, type FormDefinition, type TableBinding, type TableDefinition } from '@/api/functionUnit'
import { isTableAuditField } from '@/utils/tableAuditFields'

/** SUB binding option — same shape as {@link SubTableBindingSelect}. */
export interface ProcessSubBindingOption {
  id: number
  tableName: string
  tableDisplayName?: string
  tableDescription: string
  bindingType: string
}

export interface SubTableFieldOption {
  fieldName: string
  displayName: string
  dataType?: string
}

/** MI / runtime fields — not typically populated from inbound email extraction. */
const RUNTIME_MI_FIELD_NAMES = new Set([
  'assignee_id',
  'sub_task_status',
  'sub_task_current_node',
])

/**
 * Build selectable field options for email extraction mapping.
 *
 * Sub-table bindings exclude the primary key (auto-allocated on start, never mapped from email).
 * The main table keeps its primary key selectable: business keys such as {@code case_number}
 * are exactly what inbound email extraction needs to populate, so {@code includePrimaryKey}
 * must be set when resolving main-form fields.
 * Computed and platform audit columns are filled by Portal start, not by email extraction.
 * Owner columns (form {@code type:"owner"}) are also platform-filled at start (Creator ≈ initiator).
 */
export function extractMappableFields(
  table?: TableDefinition,
  includePrimaryKey = false,
): SubTableFieldOption[] {
  if (!table?.fieldDefinitions?.length) {
    return []
  }
  return table.fieldDefinitions
    .filter((f) => mappableFromEmail(f, includePrimaryKey))
    .map((f) => ({
      fieldName: f.fieldName,
      displayName: f.displayName?.trim() || f.fieldName,
      dataType: f.dataType,
    }))
    .sort((a, b) => a.displayName.localeCompare(b.displayName))
}

function mappableFromEmail(
  field: TableDefinition['fieldDefinitions'][number],
  includePrimaryKey: boolean,
): boolean {
  if (!includePrimaryKey && field.isPrimaryKey) {
    return false
  }
  if (RUNTIME_MI_FIELD_NAMES.has(field.fieldName)) {
    return false
  }
  if (field.isComputed) {
    return false
  }
  return !isTableAuditField(field.fieldName)
}

function toSubBindingOption(binding: TableBinding, table?: TableDefinition): ProcessSubBindingOption | null {
  if (binding.bindingType !== 'SUB' || binding.id == null) {
    return null
  }
  return {
    id: binding.id,
    tableName: table?.tableName ?? binding.tableName ?? '',
    tableDisplayName: table?.tableDisplayName,
    tableDescription: table?.description ?? '',
    bindingType: binding.bindingType,
  }
}

interface ProcessSubBindingLoadResult {
  options: ProcessSubBindingOption[]
  fieldsByBindingId: Record<number, SubTableFieldOption[]>
  mainFieldOptions: SubTableFieldOption[]
}

export function loadMainFieldOptions(
  bindings: TableBinding[],
  tableById: Map<number, TableDefinition>,
): SubTableFieldOption[] {
  const primary = bindings.find((b) => b.bindingType === 'PRIMARY')
  if (primary) {
    return extractMappableFields(tableById.get(primary.tableId), true)
  }
  const merged = new Map<string, SubTableFieldOption>()
  for (const table of tableById.values()) {
    if (table.tableType !== 'MAIN') {
      continue
    }
    for (const field of extractMappableFields(table, true)) {
      merged.set(field.fieldName, field)
    }
  }
  return [...merged.values()].sort((a, b) => a.displayName.localeCompare(b.displayName))
}

async function loadProcessFormBindings(functionUnitId: number): Promise<ProcessSubBindingLoadResult> {
  const empty: ProcessSubBindingLoadResult = { options: [], fieldsByBindingId: {}, mainFieldOptions: [] }
  const [formsRes, tablesRes] = await Promise.all([
    functionUnitApi.getForms(functionUnitId),
    functionUnitApi.getTables(functionUnitId),
  ])
  const processForm = formsRes.data.find((f: FormDefinition) => f.formType === 'PROCESS')
  if (!processForm?.id) {
    return empty
  }

  let bindings = processForm.tableBindings ?? []
  if (bindings.length === 0) {
    const bindRes = await functionUnitApi.getFormBindings(functionUnitId, processForm.id)
    bindings = bindRes.data ?? []
  }

  const tableById = new Map<number, TableDefinition>(
    (tablesRes.data ?? []).map((t: TableDefinition) => [t.id, t]),
  )

  const fieldsByBindingId: Record<number, SubTableFieldOption[]> = {}
  const options = bindings
    .map((b: TableBinding) => {
      const table = tableById.get(b.tableId)
      const option = toSubBindingOption(b, table)
      if (option) {
        fieldsByBindingId[option.id] = extractMappableFields(table)
      }
      return option
    })
    .filter((b): b is ProcessSubBindingOption => b != null)
    .sort((a, b) => {
      const la = a.tableDisplayName || a.tableName
      const lb = b.tableDisplayName || b.tableName
      return la.localeCompare(lb)
    })

  return {
    options,
    fieldsByBindingId,
    mainFieldOptions: loadMainFieldOptions(bindings, tableById),
  }
}

/** Loads SUB bindings from the Function Unit PROCESS form (parity with Form Designer). */
export function useProcessFormSubBindings(functionUnitId: Ref<number | undefined>) {
  const loading = ref(false)
  const options = ref<ProcessSubBindingOption[]>([])
  const fieldsByBindingId = ref<Record<number, SubTableFieldOption[]>>({})
  const mainFieldOptions = ref<SubTableFieldOption[]>([])

  async function reload() {
    const fuId = functionUnitId.value
    if (!fuId) {
      options.value = []
      fieldsByBindingId.value = {}
      mainFieldOptions.value = []
      return
    }
    loading.value = true
    try {
      const loaded = await loadProcessFormBindings(fuId)
      options.value = loaded.options
      fieldsByBindingId.value = loaded.fieldsByBindingId
      mainFieldOptions.value = loaded.mainFieldOptions
    } catch {
      options.value = []
      fieldsByBindingId.value = {}
      mainFieldOptions.value = []
    } finally {
      loading.value = false
    }
  }

  watch(functionUnitId, () => { void reload() }, { immediate: true })

  return { loading, options, fieldsByBindingId, mainFieldOptions, reload }
}
