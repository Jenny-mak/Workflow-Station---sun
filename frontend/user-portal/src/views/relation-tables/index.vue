<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">Relation Tables</span>
    </div>

    <div class="data-layout">
      <!-- Left: Table list (collapsible) -->
      <div
        v-if="tableListCollapsed"
        class="table-list-panel collapsed"
      >
        <el-tooltip
          content="Expand"
          placement="right"
        >
          <el-button
            text
            class="collapse-toggle"
            @click="tableListCollapsed = false"
          >
            <el-icon><Expand /></el-icon>
          </el-button>
        </el-tooltip>
      </div>
      <div
        v-else
        class="table-list-panel"
      >
        <div class="panel-title">
          <span>Available Tables</span>
          <el-tooltip
            content="Collapse"
            placement="top"
          >
            <el-button
              text
              size="small"
              class="collapse-toggle"
              @click="tableListCollapsed = true"
            >
              <el-icon><Fold /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
        <div style="padding: 6px 8px;">
          <el-input
            v-model="tableSearchKeyword"
            placeholder="Search tables..."
            clearable
            size="small"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
        </div>
        <el-menu
          v-loading="tableListLoading"
          :default-active="selectedTableId ? String(selectedTableId) : ''"
          @select="handleSelectTable"
        >
          <el-menu-item
            v-for="t in filteredTables"
            :key="t.id"
            :index="String(t.id)"
          >
            <span>{{ t.displayName || t.tableName }}</span>
          </el-menu-item>
        </el-menu>
        <el-empty
          v-if="!tableListLoading && filteredTables.length === 0"
          description="No tables available"
          :image-size="60"
        />
      </div>

      <!-- Right: Data grid -->
      <div class="data-grid-panel">
        <template v-if="selectedTable">
          <div class="grid-toolbar">
            <el-input
              v-model="searchKeyword"
              placeholder="Search..."
              clearable
              style="width: 240px; margin-right: 12px;"
              @keyup.enter="handleSearch"
              @clear="handleSearch"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-button
              v-if="canWrite"
              type="primary"
              @click="openAddDialog"
            >
              <el-icon><Plus /></el-icon> Add
            </el-button>
            <el-button
              :loading="exporting"
              @click="handleExport"
            >
              <el-icon><Download /></el-icon> Export CSV
            </el-button>
            <el-dropdown
              v-if="canWrite"
              trigger="click"
              @command="handleDownloadTemplate"
            >
              <el-button :loading="exportingTemplate">
                <el-icon><Download /></el-icon> Export Template
                <el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="csv">
                    CSV (.csv)
                  </el-dropdown-item>
                  <el-dropdown-item command="xlsx">
                    Excel (.xlsx)
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button
              v-if="canWrite"
              @click="openImportDialog"
            >
              <el-icon><Upload /></el-icon> Import
            </el-button>
          </div>

          <div
            ref="gridScrollRef"
            class="list-data-grid-scroll"
          >
            <div
              class="list-data-grid-inner"
              :style="gridInnerStyle"
            >
          <el-table
            v-loading="dataLoading"
            :data="dataRows"
            stripe
            :fit="gridFits"
            :table-layout="gridFits ? 'auto' : 'fixed'"
            style="width: 100%;"
            class="list-data-grid"
            :class="{ 'list-data-grid--fit': gridFits }"
          >
            <el-table-column
              v-for="(col, colIndex) in displayColumns"
              :key="col.field"
              :prop="col.field"
              :width="gridFits ? undefined : widthOf(col.field)"
              :min-width="gridFits ? widthOf(col.field) : undefined"
              show-overflow-tooltip
            >
              <template #header>
                <ListColumnHeader
                  :column="col"
                  :sort="sort.field === col.field ? sort.direction : null"
                  :filtered="!!columnFilters[col.field]"
                  :width="widthOf(col.field)"
                  :show-move="displayColumns.length > 1"
                  :can-move-left="colIndex > 0"
                  :can-move-right="colIndex < displayColumns.length - 1"
                  @sort-change="(direction: 'ASC' | 'DESC') => applySort(col.field, direction)"
                  @clear-sort="clearSort"
                  @filter-open="openFilter(col.field)"
                  @clear-filter="clearFilter(col.field)"
                  @move="(direction: 'left' | 'right') => moveColumn(col.field, direction)"
                  @width-change="(width: number) => setWidth(col.field, width)"
                  @width-commit="persistWidths"
                />
              </template>
              <template #default="{ row }">
                {{ isTimestampColumn(col.field) ? formatHKT(row[col.field]) : formatRelationCellDisplay(row[col.field]) }}
              </template>
            </el-table-column>
            <el-table-column
              v-if="canWrite"
              label="Actions"
              width="200"
              fixed="right"
              align="center"
            >
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  size="small"
                  @click="openEditDialog(row)"
                >
                  Edit
                </el-button>
                <el-button
                  v-if="isRowInactive(row)"
                  link
                  type="success"
                  size="small"
                  @click="handleChangeStatus(row, 'ACTIVE')"
                >
                  Active
                </el-button>
                <el-button
                  v-else
                  link
                  type="warning"
                  size="small"
                  @click="handleChangeStatus(row, 'INACTIVE')"
                >
                  Inactive
                </el-button>
              </template>
            </el-table-column>
          </el-table>
            </div>
          </div>

          <ListPagination
            v-if="totalElements > 0"
            v-model:page="currentPage"
            v-model:size="pageSize"
            :total="totalElements"
            :loading="dataLoading"
            @change="fetchData"
          />
        </template>
        <el-empty
          v-else
          description="Select a table from the left panel"
        />
      </div>
    </div>

    <ListFilterDialog
      v-model:visible="filterDialog.visible"
      :column="activeFilterColumn"
      :filter="activeFilter"
      :remote-search="searchListFilterUsers"
      @apply="onFilterApply"
      @clear="onFilterClear"
    />

    <!-- Add/Edit Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'add' ? 'Add Record' : 'Edit Record'"
      width="600px"
      destroy-on-close
    >
      <el-form
        :model="formData"
        label-width="auto"
        label-position="left"
      >
        <el-form-item
          v-for="field in editableFields"
          :key="field.fieldName"
          :label="field.displayName || field.fieldName"
          :required="field.nullable === false || field.isPrimaryKey"
          :error="computedFieldErrorText[field.fieldName]"
        >
          <template v-if="field.dataType === 'LOOKUP'">
            <LookupField
              :model-value="formData[field.fieldName]"
              :table-id="field.lookupConfig?.refTableId || 0"
              :search-fields="field.lookupConfig?.searchFields || []"
              :display-field="field.lookupConfig?.displayFields?.[0] || ''"
              :display-fields="field.lookupConfig?.displayFields || []"
              :selected-display-field="field.lookupConfig?.selectedDisplayField"
              :filter-conditions="lookupFilterConditionsFor(field)"
              :view-fields="lookupViewFieldsFor(field)"
              :multiple="field.lookupConfig?.multiple"
              :lookup-config="JSON.stringify(field.lookupConfig || {})"
              @update:model-value="formData[field.fieldName] = $event"
              @select="onLookupSelect(field, $event)"
              @clear="onLookupClear(field)"
            />
            <LookupViewDisplay
              v-if="field.lookupConfig?.showBackfillView !== false && lookupSelectedData[field.fieldName]"
              :selected-data="lookupSelectedData[field.fieldName]"
              :view-fields="lookupViewFieldsFor(field)"
            />
          </template>
          <el-switch
            v-else-if="field.dataType === 'BOOLEAN'"
            v-model="formData[field.fieldName]"
            :disabled="isFieldDisabled(field)"
          />
          <el-input-number
            v-else-if="['INTEGER', 'BIGINT', 'DECIMAL'].includes(field.dataType)"
            v-model="formData[field.fieldName]"
            :precision="field.dataType === 'DECIMAL' ? (field.scale || 2) : 0"
            style="width: 100%;"
            :disabled="isFieldDisabled(field)"
          />
          <el-date-picker
            v-else-if="field.dataType === 'DATE'"
            v-model="formData[field.fieldName]"
            type="date"
            value-format="YYYY-MM-DD"
            style="width: 100%;"
            :disabled="isFieldDisabled(field)"
          />
          <el-date-picker
            v-else-if="field.dataType === 'TIMESTAMP'"
            v-model="formData[field.fieldName]"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%;"
            :disabled="isFieldDisabled(field)"
          />
          <el-input
            v-else
            v-model="formData[field.fieldName]"
            :maxlength="field.length || undefined"
            :disabled="isFieldDisabled(field)"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">
          Cancel
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          @click="handleSaveRecord"
        >
          Save
        </el-button>
      </template>
    </el-dialog>

    <!-- Import Dialog -->
    <el-dialog
      v-model="importDialogVisible"
      title="Import Data"
      width="640px"
    >
      <el-alert
        type="info"
        :closable="false"
        style="margin-bottom: 12px;"
      >
        Download a template first, fill it in, then upload (CSV or Excel). Rows are validated against the table structure; invalid rows are skipped. Auto-generated primary keys are filled by the system, so they are not included in the template. Up to 1000 rows per import.
      </el-alert>
      <el-upload
        drag
        :auto-upload="false"
        :show-file-list="false"
        accept=".csv,.xlsx"
        :on-change="onImportFileChange"
      >
        <el-icon class="el-icon--upload"><Upload /></el-icon>
        <div class="el-upload__text">
          Drop file here or <em>click to upload</em>
        </div>
      </el-upload>
      <div
        v-if="importing"
        style="margin-top: 12px;"
      >
        Validating...
      </div>
      <div
        v-if="importResult"
        style="margin-top: 12px;"
      >
        <el-alert
          v-if="importResult.dryRun"
          :type="importResult.failed > 0 ? 'warning' : 'success'"
          :closable="false"
          :title="`${importResult.validCount} valid row(s), ${importResult.failed} invalid${importResult.failed > 0 ? ' (will be skipped)' : ''}. Click “Confirm Import” to import.`"
          style="margin-bottom: 8px;"
        />
        <el-alert
          v-else
          :type="importResult.failed > 0 ? 'warning' : 'success'"
          :closable="false"
          :title="`Inserted ${importResult.inserted}, Failed ${importResult.failed}`"
          style="margin-bottom: 8px;"
        />
        <el-table
          v-if="importResult.errors.length"
          :data="importResult.errors"
          stripe
          max-height="240"
          size="small"
        >
          <el-table-column
            prop="row"
            label="Row"
            width="70"
          />
          <el-table-column
            prop="field"
            label="Field"
            width="160"
          />
          <el-table-column
            prop="message"
            label="Error"
          />
        </el-table>
      </div>
      <template #footer>
        <el-button @click="importDialogVisible = false">
          Close
        </el-button>
        <el-button
          v-if="importResult && importResult.dryRun && (importResult.validCount ?? 0) > 0"
          type="primary"
          :loading="importing"
          @click="handleConfirmImport"
        >
          Confirm Import ({{ importResult.validCount }})
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, reactive, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { Search, Download, Plus, Upload, ArrowDown, Expand, Fold } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter, ListColumnMeta } from '@platform-shared/list/columnMeta'
import { relationTableApi, type RelationTableDTO, type RelationFieldDef, type RelationImportResult, type LookupConfig, type RelationTableQueryRequest } from '@/api/relationTable'
import type { LookupFilterCondition } from '@/utils/lookupFilterConditions'
import LookupField from '@/components/lookup/LookupField.vue'
import LookupViewDisplay from '@/components/lookup/LookupViewDisplay.vue'
import { buildDerivedFilterConditions, resolveDerivedLookup, normalizeLookupValueForSave, formatRelationCellDisplay, type FieldLike } from '@/components/lookup/useLookupBehaviors'
import { collectComputedColumns, previewComputedRow } from '@/utils/computedFieldRuntime'
import { useListColumnLayout } from '@/composables/list/useListColumnLayout'
import { searchListFilterUsers } from '@/composables/list/searchListFilterUsers'

const SYSTEM_FIELDS = new Set(['created_at', 'created_by', 'updated_at', 'updated_by', 'status'])
const TIMESTAMP_COLUMNS = new Set(['created_at', 'updated_at'])

const { t } = useI18n()
const route = useRoute()

const tableListLoading = ref(false)
const dataLoading = ref(false)
const exporting = ref(false)
const tables = ref<RelationTableDTO[]>([])
const selectedTableId = ref<number | null>(null)

const searchKeyword = ref('')
const tableSearchKeyword = ref('')
const tableListCollapsed = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const totalElements = ref(0)
const dataRows = ref<Record<string, any>[]>([])
// 列（含表头标签与可筛选/可排序能力）由查询响应声明，前端不自行推导
const columns = ref<ListColumnMeta[]>([])
/** Client-only display order; never sent to the query API. */
const columnOrder = ref<string[]>([])

const displayColumns = computed(() => {
  if (columnOrder.value.length === 0) return columns.value
  const byField = new Map(columns.value.map(c => [c.field, c]))
  const ordered: ListColumnMeta[] = []
  for (const field of columnOrder.value) {
    const col = byField.get(field)
    if (col) ordered.push(col)
  }
  for (const col of columns.value) {
    if (!columnOrder.value.includes(col.field)) ordered.push(col)
  }
  return ordered
})

const columnFilters = ref<Record<string, ListColumnFilter>>({})
const sort = reactive<{ field: string | null; direction: 'ASC' | 'DESC' | null }>({
  field: null,
  direction: null
})
const filterDialog = reactive({ visible: false, field: '' })

const activeFilterColumn = computed<ListColumnMeta | null>(
  () => columns.value.find(c => c.field === filterDialog.field) ?? null
)
const activeFilter = computed<ListColumnFilter | null>(
  () => columnFilters.value[filterDialog.field] ?? null
)

const selectedTable = computed(() =>
  tables.value.find(t => t.id === selectedTableId.value) ?? null
)

const canWrite = computed(() => selectedTable.value?.permissionLevel === 'READ_WRITE')

const layoutFields = computed(() => displayColumns.value.map(c => c.field))
const layoutStorageKey = computed(() =>
  selectedTableId.value != null ? `portal-list-layout:relation-table:${selectedTableId.value}` : '',
)
const columnOrderStorageKey = computed(() =>
  selectedTableId.value != null ? `portal-list-column-order:relation-table:${selectedTableId.value}` : '',
)
const { gridScrollRef, gridFits, gridInnerStyle, widthOf, setWidth, persistWidths,
} = useListColumnLayout({
  storageKey: layoutStorageKey,
  fields: layoutFields,
  extraWidth: computed(() => (canWrite.value ? 200 : 0)),
  defaultWidthOf: (field) => (TIMESTAMP_COLUMNS.has(field) ? 180 : 120),
})

function readStoredColumnOrder(key: string): string[] {
  if (!key) return []
  try {
    const raw = sessionStorage.getItem(key)
    if (!raw) return []
    const parsed = JSON.parse(raw) as { columnOrder?: string[] }
    return Array.isArray(parsed.columnOrder) ? parsed.columnOrder.filter(f => typeof f === 'string') : []
  } catch {
    // FALLBACK(ux): unreadable layout costs remembered order only; the row data is unaffected.
    return []
  }
}

function persistColumnOrder() {
  const key = columnOrderStorageKey.value
  if (!key) return
  try {
    sessionStorage.setItem(key, JSON.stringify({ columnOrder: [...columnOrder.value] }))
  } catch {
    // FALLBACK(ux): quota errors must not interrupt the move the user just performed.
  }
}

function syncColumnOrderFromServer(declared: ListColumnMeta[]) {
  const declaredFields = declared.map(c => c.field)
  const stored = readStoredColumnOrder(columnOrderStorageKey.value)
  const next: string[] = []
  for (const field of stored) {
    if (declaredFields.includes(field) && !next.includes(field)) next.push(field)
  }
  for (const field of declaredFields) {
    if (!next.includes(field)) next.push(field)
  }
  columnOrder.value = next
}

function moveColumn(field: string, direction: 'left' | 'right') {
  const order = [...columnOrder.value]
  const index = order.indexOf(field)
  if (index < 0) return
  const swapWith = direction === 'left' ? index - 1 : index + 1
  if (swapWith < 0 || swapWith >= order.length) return
  ;[order[index], order[swapWith]] = [order[swapWith], order[index]]
  columnOrder.value = order
  persistColumnOrder()
}

// ---- Editing state ----
const fieldDefs = ref<RelationFieldDef[]>([])
const editableFields = computed(() => fieldDefs.value.filter(f => !SYSTEM_FIELDS.has(f.fieldName)))
const pkField = computed(() => fieldDefs.value.find(f => f.isPrimaryKey)?.fieldName ?? null)

const dialogVisible = ref(false)
const dialogMode = ref<'add' | 'edit'>('add')
const saving = ref(false)
const editingRowId = ref<string | null>(null)
const formData = ref<Record<string, any>>({})
// Selected lookup rows keyed by field name — feeds the backfill panel + derived cascade.
const lookupSelectedData = ref<Record<string, Record<string, any> | null>>({})

const exportingTemplate = ref(false)
const importDialogVisible = ref(false)
const importing = ref(false)
const importResult = ref<RelationImportResult | null>(null)
const pendingImportFile = ref<File | null>(null)

const isRowInactive = (row: Record<string, any>): boolean =>
  String(row.status ?? '').toUpperCase() === 'INACTIVE'

const rowId = (row: Record<string, any>): string | null =>
  pkField.value ? String(row[pkField.value]) : null

const filteredTables = computed(() => {
  const kw = tableSearchKeyword.value.trim().toLowerCase()
  if (!kw) return tables.value
  return tables.value.filter(t =>
    (t.displayName || '').toLowerCase().includes(kw) ||
    (t.tableName || '').toLowerCase().includes(kw)
  )
})

const fetchTables = async () => {
  tableListLoading.value = true
  try {
    const res = await relationTableApi.getVisibleTables()
    tables.value = res.data
    // Honor a drill-down from Views: ?tableId=<id>&search=<value> preselects that table + filter.
    const queryTableId = route.query.tableId != null ? Number(route.query.tableId) : null
    const target = queryTableId != null && tables.value.some(t => t.id === queryTableId)
      ? queryTableId
      : (tables.value.length > 0 ? tables.value[0].id : null)
    if (!selectedTableId.value && target != null) {
      selectedTableId.value = target
      if (queryTableId === target && typeof route.query.search === 'string') {
        searchKeyword.value = route.query.search
      }
      fetchFieldDefs()
      fetchData()
    }
  } finally {
    tableListLoading.value = false
  }
}

const fetchFieldDefs = async () => {
  if (!selectedTableId.value) { fieldDefs.value = []; return }
  const res = await relationTableApi.getFieldDefinitions(selectedTableId.value)
  fieldDefs.value = res.data
}

const buildQuery = (): RelationTableQueryRequest => {
  const body: RelationTableQueryRequest = {
    page: currentPage.value - 1,
    size: pageSize.value
  }
  if (searchKeyword.value) {
    body.search = searchKeyword.value
  }
  const filters = Object.entries(columnFilters.value).map(([field, filter]) => ({ field, ...filter }))
  if (filters.length > 0) {
    body.filters = filters
  }
  if (sort.field && sort.direction) {
    body.sortField = sort.field
    body.sortDirection = sort.direction
  }
  return body
}

// Switching tables while a query is in flight must not let the old table's rows land in the
// new table's grid — a superseded response is dropped rather than rendered.
let latestQuery = 0

const fetchData = async () => {
  if (!selectedTableId.value) return
  const queryId = ++latestQuery
  dataLoading.value = true
  try {
    const res = await relationTableApi.queryTableData(selectedTableId.value, buildQuery())
    if (queryId !== latestQuery) return
    const page = res.data
    if (!Array.isArray(page.columns) || page.columns.length === 0) {
      throw new Error('relation-table query response is missing its column declaration')
    }
    columns.value = page.columns
    syncColumnOrderFromServer(page.columns)
    dataRows.value = page.content
    totalElements.value = page.totalElements
  } catch (e: unknown) {
    if (queryId !== latestQuery) return
    const err = e as { response?: { data?: { message?: string; error?: { message?: string } } } }
    ElMessage.error(
      err?.response?.data?.error?.message
        || err?.response?.data?.message
        || (e instanceof Error ? e.message : 'Failed to load table data'),
    )
    columns.value = []
    dataRows.value = []
    totalElements.value = 0
  } finally {
    if (queryId === latestQuery) {
      dataLoading.value = false
    }
  }
}

/** Column state belongs to one table; carrying it across a switch would query B with A's columns. */
const resetTableState = () => {
  searchKeyword.value = ''
  currentPage.value = 1
  columns.value = []
  columnOrder.value = []
  columnFilters.value = {}
  sort.field = null
  sort.direction = null
  filterDialog.visible = false
  filterDialog.field = ''
}

const handleSelectTable = (index: string) => {
  selectedTableId.value = Number(index)
  resetTableState()
  fetchFieldDefs()
  fetchData()
}

/** A new keyword changes the result set, so the old page number no longer addresses anything. */
const handleSearch = () => {
  currentPage.value = 1
  fetchData()
}

const openFilter = (field: string) => {
  filterDialog.field = field
  filterDialog.visible = true
}

const clearFilter = (field: string) => {
  const { [field]: _removed, ...rest } = columnFilters.value
  columnFilters.value = rest
  currentPage.value = 1
  fetchData()
}

const onFilterApply = (filter: ListColumnFilter) => {
  columnFilters.value = { ...columnFilters.value, [filterDialog.field]: filter }
  filterDialog.visible = false
  currentPage.value = 1
  fetchData()
}

const onFilterClear = () => {
  filterDialog.visible = false
  clearFilter(filterDialog.field)
}

const applySort = (field: string, direction: 'ASC' | 'DESC') => {
  sort.field = field
  sort.direction = direction
  currentPage.value = 1
  fetchData()
}

const clearSort = () => {
  sort.field = null
  sort.direction = null
  currentPage.value = 1
  fetchData()
}

const isTimestampColumn = (col: string): boolean => TIMESTAMP_COLUMNS.has(col)

const formatHKT = (value: any): string => {
  if (value == null || value === '') return ''
  try {
    const date = new Date(value)
    if (isNaN(date.getTime())) return String(value)
    return date.toLocaleString('en-HK', { timeZone: 'Asia/Hong_Kong', hour12: false })
  } catch {
    return String(value)
  }
}

const handleExport = async () => {
  if (!selectedTableId.value) return
  exporting.value = true
  try {
    const blob = await relationTableApi.exportCsv(selectedTableId.value)
    const url = window.URL.createObjectURL(new Blob([blob as any]))
    const link = document.createElement('a')
    link.href = url
    const name = selectedTable.value?.displayName || selectedTable.value?.tableName || 'data'
    link.setAttribute('download', `${name}.csv`)
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.URL.revokeObjectURL(url)
    ElMessage.success('Export completed')
  } catch {
    ElMessage.error('Export failed')
  } finally {
    exporting.value = false
  }
}

// ---- Add / Edit ----
const pkStrategy = (f: RelationFieldDef): string => (f.pkGeneration?.strategy as string) ?? 'uuid'

/**
 * Primary key is non-editable by default. Only the `manual` strategy in add mode lets the
 * user type it; on edit it is always locked, and on add with any auto strategy it is
 * generated server-side and shown read-only.
 */
const isPkDisabled = (f: RelationFieldDef): boolean => {
  if (!f.isPrimaryKey) return false
  if (dialogMode.value === 'edit') return true
  return pkStrategy(f) !== 'manual'
}

/** Computed columns join primary keys as fields the user may see but not type into. */
const isFieldDisabled = (f: RelationFieldDef): boolean => isPkDisabled(f) || f.isComputed === true

// ---- Computed (formula) columns ----
// The server recomputes these on every write; previewing keeps the dialog in step with the save.
// Relation tables have no sub-tables, so every formula here is row scope.
const computedColumns = computed(() =>
  collectComputedColumns(
    fieldDefs.value.map(f => ({
      fieldName: f.fieldName,
      isComputed: f.isComputed,
      computedField: f.computedField,
    })),
  ),
)

/** fieldName → error code for formulas the server would refuse to save. */
const computedFieldErrors = ref<Record<string, string>>({})

const recomputeComputedColumns = (): void => {
  if (!computedColumns.value.length) return
  const preview = previewComputedRow(computedColumns.value, formData.value, {})
  for (const [fieldName, value] of Object.entries(preview.values)) {
    formData.value[fieldName] = value
  }
  for (const fieldName of Object.keys(preview.errors)) {
    formData.value[fieldName] = null
  }
  computedFieldErrors.value = preview.errors
}

const computedFieldErrorText = computed<Record<string, string>>(() => {
  const messages: Record<string, string> = {}
  for (const [field, code] of Object.entries(computedFieldErrors.value)) {
    messages[field] = t('computedField.evaluationFailed', { code })
  }
  return messages
})

// Stable primitive getter: joining the dependency values avoids a deep traversal of the whole row.
watch(
  () => {
    if (!computedColumns.value.length) return ''
    const parts: string[] = []
    for (const column of computedColumns.value) {
      for (const dep of column.definition?.dependsOn ?? []) {
        if (dep.includes('.')) continue
        parts.push(`${dep}=${String(formData.value[dep] ?? '')}`)
      }
    }
    return parts.join('|')
  },
  () => { recomputeComputedColumns() },
)

// ---- LOOKUP fields ----
const fieldsAsLike = (): FieldLike[] =>
  editableFields.value.map(f => ({ fieldName: f.fieldName, dataType: f.dataType, lookupConfig: f.lookupConfig }))

/** Effective filter conditions for a lookup field, incl. cascade from its parent's selected row. */
const lookupFilterConditionsFor = (field: RelationFieldDef): LookupFilterCondition[] => {
  const cfg = field.lookupConfig
  const base = cfg?.filterConditions || []
  const parent = cfg?.derivedFrom?.parentField
  if (!parent) return base
  return buildDerivedFilterConditions(base, cfg, lookupSelectedData.value[parent])
}

const lookupViewFieldsFor = (field: RelationFieldDef) =>
  (field.lookupConfig?.displayFields || []).map((fn, i) => ({ fieldName: fn, displayLabel: fn, sortOrder: i, visible: true }))

/** On lookup pick: store the row (backfill) + drive dependent lookups (derived auto-fill). */
const onLookupSelect = async (field: RelationFieldDef, row: Record<string, any> | null) => {
  lookupSelectedData.value = { ...lookupSelectedData.value, [field.fieldName]: row }
  for (const dep of editableFields.value) {
    if (dep.dataType !== 'LOOKUP') continue
    if (dep.lookupConfig?.derivedFrom?.parentField !== field.fieldName) continue
    const res = await resolveDerivedLookup(
      { fieldName: dep.fieldName, dataType: dep.dataType, lookupConfig: dep.lookupConfig },
      row,
      fieldsAsLike(),
    )
    if (!res.skip) formData.value[dep.fieldName] = res.value
  }
}

const onLookupClear = (field: RelationFieldDef) => {
  lookupSelectedData.value = { ...lookupSelectedData.value, [field.fieldName]: null }
  for (const dep of editableFields.value) {
    if (dep.dataType === 'LOOKUP' && dep.lookupConfig?.derivedFrom?.parentField === field.fieldName
        && dep.lookupConfig?.derivedFrom?.derivedMode === 'autofill') {
      formData.value[dep.fieldName] = dep.lookupConfig?.multiple ? [] : null
    }
  }
}

const openAddDialog = async () => {
  if (!selectedTableId.value) return
  dialogMode.value = 'add'
  editingRowId.value = null
  lookupSelectedData.value = {}
  const fd: Record<string, any> = {}
  for (const f of editableFields.value) {
    fd[f.fieldName] = f.dataType === 'BOOLEAN' ? false : (f.dataType === 'LOOKUP' && f.lookupConfig?.multiple ? [] : null)
  }
  formData.value = fd
  computedFieldErrors.value = {}
  recomputeComputedColumns()
  dialogVisible.value = true
  // Auto-generate PK values per strategy (skip manual, which the user types).
  try {
    for (const f of editableFields.value) {
      if (!f.isPrimaryKey || pkStrategy(f) === 'manual') continue
      const res: any = await relationTableApi.allocatePrimaryKeys(selectedTableId.value, f.fieldName)
      const values = res?.data?.values ?? res?.values
      if (values?.[0] != null) formData.value[f.fieldName] = values[0]
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.error?.message || e?.response?.data?.message || 'Failed to allocate primary key')
  }
}

const openEditDialog = (row: Record<string, any>) => {
  dialogMode.value = 'edit'
  editingRowId.value = rowId(row)
  lookupSelectedData.value = {}
  const fd: Record<string, any> = {}
  for (const f of editableFields.value) {
    fd[f.fieldName] = row[f.fieldName] ?? null
  }
  formData.value = fd
  computedFieldErrors.value = {}
  recomputeComputedColumns()
  dialogVisible.value = true
}

const handleSaveRecord = async () => {
  if (!selectedTableId.value) return
  // A formula the server refuses to evaluate would make the whole write fail; the inline error is
  // already on the offending field, so stop here instead of round-tripping to a rejection.
  if (Object.keys(computedFieldErrors.value).length > 0) return
  saving.value = true
  try {
    const lookupCfgByField = new Map<string, LookupConfig>()
    for (const f of editableFields.value) {
      if (f.dataType === 'LOOKUP' && f.lookupConfig) lookupCfgByField.set(f.fieldName, f.lookupConfig)
    }
    const clean: Record<string, any> = {}
    for (const [k, v] of Object.entries(formData.value)) {
      if (lookupCfgByField.has(k)) {
        const pk = normalizeLookupValueForSave(v, lookupCfgByField.get(k))
        if (pk !== null && pk !== undefined && pk !== '' && !(Array.isArray(pk) && pk.length === 0)) clean[k] = pk
        continue
      }
      if (v !== null && v !== undefined && v !== '') clean[k] = v
    }
    if (dialogMode.value === 'add') {
      await relationTableApi.addData(selectedTableId.value, clean)
      ElMessage.success('Record added')
    } else if (editingRowId.value) {
      await relationTableApi.updateData(selectedTableId.value, editingRowId.value, clean)
      ElMessage.success('Record updated')
    }
    dialogVisible.value = false
    await fetchData()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.error?.message || e?.response?.data?.message || 'Save failed')
  } finally {
    saving.value = false
  }
}

const handleChangeStatus = async (row: Record<string, any>, status: string) => {
  if (!selectedTableId.value) return
  const id = rowId(row)
  if (!id) { ElMessage.error('No primary key on this table'); return }
  try {
    await relationTableApi.changeStatus(selectedTableId.value, id, status)
    ElMessage.success(status === 'INACTIVE' ? 'Record set to inactive' : 'Record set to active')
    await fetchData()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.error?.message || e?.response?.data?.message || 'Failed')
  }
}

// ---- Template / Import ----
const handleDownloadTemplate = async (format: 'csv' | 'xlsx') => {
  if (!selectedTableId.value) return
  exportingTemplate.value = true
  try {
    const blob = await relationTableApi.downloadTemplate(selectedTableId.value, format)
    const url = window.URL.createObjectURL(new Blob([blob as any]))
    const link = document.createElement('a')
    link.href = url
    const name = selectedTable.value?.displayName || selectedTable.value?.tableName || 'template'
    link.setAttribute('download', `${name}-template.${format}`)
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.URL.revokeObjectURL(url)
  } catch {
    ElMessage.error('Template download failed')
  } finally {
    exportingTemplate.value = false
  }
}

const openImportDialog = () => {
  importResult.value = null
  pendingImportFile.value = null
  importDialogVisible.value = true
}

// Step 1: selecting a file validates only (dryRun) and shows a preview; nothing is inserted yet.
const onImportFileChange = (file: { raw?: File }) => {
  if (file.raw) handleValidateImport(file.raw)
}

const handleValidateImport = async (file: File) => {
  if (!selectedTableId.value) return
  pendingImportFile.value = file
  importing.value = true
  importResult.value = null
  try {
    const format = file.name.toLowerCase().endsWith('.xlsx') ? 'xlsx' : 'csv'
    const res: any = await relationTableApi.importData(selectedTableId.value, file, format, true)
    importResult.value = res?.data ?? res
  } catch (e: any) {
    pendingImportFile.value = null
    ElMessage.error(e?.response?.data?.error?.message || e?.response?.data?.message || 'Validation failed')
  } finally {
    importing.value = false
  }
}

// Step 2: user confirms -> re-validate + insert server-side (dryRun=false).
const handleConfirmImport = async () => {
  if (!selectedTableId.value || !pendingImportFile.value) return
  importing.value = true
  try {
    const file = pendingImportFile.value
    const format = file.name.toLowerCase().endsWith('.xlsx') ? 'xlsx' : 'csv'
    const res: any = await relationTableApi.importData(selectedTableId.value, file, format, false)
    importResult.value = res?.data ?? res
    pendingImportFile.value = null
    const r = importResult.value
    if (r && (r.inserted ?? 0) > 0) {
      ElMessage.success(`Imported ${r.inserted} row(s)${r.failed ? `, ${r.failed} failed` : ''}`)
      await fetchData()
    } else if (r && r.failed > 0) {
      ElMessage.error(`All ${r.failed} row(s) failed validation`)
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.error?.message || e?.response?.data?.message || 'Import failed')
  } finally {
    importing.value = false
  }
}

onMounted(fetchTables)
</script>

<style lang="scss">
@import '@/styles/listDataGrid.scss';
</style>

<style scoped>
.page-container {
  padding: 20px;
}

.page-header {
  margin-bottom: 16px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
}

.data-layout {
  display: flex;
  gap: 16px;
  height: calc(100vh - 140px);
  min-height: 0;
}

.table-list-panel {
  width: 220px;
  flex-shrink: 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: 4px;
  overflow-y: auto;
  transition: width 0.2s ease;
}

.table-list-panel.collapsed {
  width: 40px;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding-top: 8px;
  overflow: hidden;
}

.collapse-toggle {
  padding: 4px;
}

.table-list-panel :deep(.el-menu-item.is-active) {
  background-color: var(--el-color-primary-light-9, #ecf5ff);
  color: var(--el-color-primary, #409eff);
}

.table-list-panel :deep(.el-menu-item.is-active)::before {
  display: none;
}

.panel-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 8px 8px 16px;
  font-weight: 600;
  font-size: 14px;
  border-bottom: 1px solid var(--el-border-color-light);
}

.data-grid-panel {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.grid-toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
  flex-shrink: 0;
}
</style>
