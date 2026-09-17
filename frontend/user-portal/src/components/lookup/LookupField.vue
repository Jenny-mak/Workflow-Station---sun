<template>
  <div
    ref="wrapperRef"
    class="lookup-field"
    :class="{ readonly }"
  >
    <!-- Multi-select: multiple removable tags + an input row that opens the dropdown. -->
    <div
      v-if="multiple"
      class="lookup-selected-wrapper lookup-multi-wrapper"
      :class="{ 'is-readonly': readonly }"
      @click="!readonly && handleFocus()"
    >
      <span
        v-for="(row, i) in selectedRows"
        :key="i"
        class="lookup-selected-tag"
      >
        <span class="lookup-selected-text">{{ tagTextFor(row) }}</span>
        <el-icon
          v-if="!readonly"
          class="lookup-selected-close"
          @click.stop="removeSelectedAt(i)"
        ><Close /></el-icon>
      </span>
      <input
        v-if="!readonly"
        v-model="searchKeyword"
        class="lookup-multi-input"
        :placeholder="selectedRows.length ? '' : (placeholder || 'Click to search')"
        @focus="handleFocus"
      >
      <span
        v-if="readonly && !selectedRows.length"
        class="lookup-readonly-empty"
      >-</span>
    </div>
    <!-- Single-select: input container with inner tag -->
    <div
      v-else-if="selectedRow"
      class="lookup-selected-wrapper"
      :class="{ 'is-readonly': readonly }"
    >
      <span class="lookup-selected-tag">
        <span class="lookup-selected-text">{{ searchKeyword }}</span>
        <el-icon
          v-if="!readonly"
          class="lookup-selected-close"
          @click.stop="handleClear"
        ><Close /></el-icon>
      </span>
    </div>
    <!-- Search input (hidden when a value is selected or in readonly mode) -->
    <el-input
      v-else-if="!readonly"
      v-model="searchKeyword"
      :placeholder="placeholder || 'Click to search'"
      @focus="handleFocus"
    />
    <!-- Readonly empty: keep disabled input chrome (border) like other fields -->
    <el-input
      v-else
      model-value=""
      placeholder="-"
      class="lookup-input"
      disabled
    />

    <!-- Teleport to body so the dropdown is never clipped/occluded by following form cards
         (the field's own .form-layout-card creates a stacking context that z-index can't escape). -->
    <Teleport to="body">
      <div
        v-if="dropdownVisible"
        ref="dropdownRef"
        class="lookup-dropdown lookup-dropdown--floating"
        :style="dropdownStyle"
      >
        <div
          class="lookup-dropdown-table-wrap"
          :style="{ minWidth: `${tableContentWidth}px` }"
        >
          <el-table
            v-loading="loading"
            :data="filteredResults"
            size="small"
            highlight-current-row
            max-height="260"
            @row-click="handleSelect"
          >
          <el-table-column
            v-if="multiple"
            width="40"
            align="center"
          >
            <template #default="{ row }">
              <el-icon
                v-if="isRowSelected(row)"
                class="lookup-check"
              ><Check /></el-icon>
            </template>
          </el-table-column>
          <el-table-column
            v-for="col in visibleColumns"
            :key="col.prop"
            :prop="col.prop"
            :label="col.label"
            :min-width="col.width || 120"
            show-overflow-tooltip
          />
          </el-table>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { debounce } from 'lodash-es'
import { useZIndex } from 'element-plus'
import { Close, Check } from '@element-plus/icons-vue'
import { relationTableApi } from '@/api/relationTable'
import { fetchLookupRowByPrimaryKey } from './fetchLookupRowByPrimaryKey'
import { useLookupFieldRows } from './useLookupFieldRows'
import {
  buildVisibleColumns,
  lookupTableContentWidth,
  type LookupViewField,
} from './lookupFieldColumns'
import {
  getLookupSelectedDisplayFieldFromProps,
  resolveLookupCellTagText,
  unwrapSingleLookupModelValue,
} from '../subTableAddDialogHelpers'
import type { LookupFilterCondition } from '@/utils/lookupFilterConditions'

export type { LookupViewField }

const props = defineProps<{
  modelValue?: any
  tableId: number
  searchFields: string[]
  displayField: string
  displayFields?: string[]
  selectedDisplayField?: string
  lookupConfig?: string | Record<string, unknown>
  filterConditions?: LookupFilterCondition[]
  viewFields?: LookupViewField[]
  placeholder?: string
  readonly?: boolean
  /** Multi-select: value is an array of PKs; multiple tags; dropdown toggles rows. */
  multiple?: boolean
  /** Cap first-open / remote-search rows. Unset = page through the full table (form lookup). */
  prefetchLimit?: number
  /** When true, keyword hits the server instead of filtering already-loaded rows. */
  remoteFilter?: boolean
  /** After fetch, clear the selection when the current PK is not in the filtered rows. */
  autoClearIfMissing?: boolean
  /** Bump to force a reload (script `api.refresh`). */
  reloadNonce?: number
  /** Row PKs omitted from the dropdown (e.g. current user on Delegate). */
  excludePrimaryKeys?: string[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: any): void
  (e: 'select', row: Record<string, any>): void
  (e: 'clear'): void
  (e: 'viewFieldsLoaded', fields: LookupViewField[]): void
}>()

const wrapperRef = ref<HTMLElement>()
const dropdownRef = ref<HTMLElement>()
const dropdownVisible = ref(false)
// Floating dropdown position (teleported to body, position: fixed) — recomputed from the
// field's viewport rect on open and on scroll/resize so it tracks the input.
const dropdownStyle = ref<Record<string, string>>({})
const searchKeyword = ref('')
const selectedRow = ref<Record<string, any> | null>(null)
// Multi-select: the picked rows (order preserved). modelValue is an array of their PKs.
const selectedRows = ref<Record<string, any>[]>([])
const loadedViewFields = ref<LookupViewField[]>([])
const { allRows, loading, loadInitial, searchRemote, resetRows } = useLookupFieldRows({
  tableId: () => props.tableId,
  searchFields: () => props.searchFields || [],
  displayField: () => props.displayField || '',
  filterConditions: () => props.filterConditions || [],
  prefetchLimit: () => props.prefetchLimit,
  remoteFilter: () => !!props.remoteFilter,
})

const scheduleRemoteSearch = debounce((keyword: string) => {
  void searchRemote(keyword)
}, 300)

// Use prop viewFields if provided, otherwise use loaded ones
const effectiveViewFields = computed(() =>
  props.viewFields?.length ? props.viewFields : loadedViewFields.value
)

const visibleColumns = computed(() =>
  buildVisibleColumns({
    displayFields: props.displayFields,
    searchFields: props.searchFields || [],
    displayField: props.displayField || '',
    viewFields: effectiveViewFields.value,
  }),
)

const tableContentWidth = computed(() =>
  lookupTableContentWidth(visibleColumns.value, !!props.multiple),
)

const excludedPrimaryKeySet = computed(() => new Set(
  (props.excludePrimaryKeys || [])
    .map((key) => String(key).trim())
    .filter(Boolean),
))

function isExcludedRow(row: Record<string, any>): boolean {
  const excluded = excludedPrimaryKeySet.value
  if (excluded.size === 0) return false
  const pk = rowPk(row)
  return pk != null && excluded.has(String(pk).trim())
}

// Client-side filtering on the loaded data (form lookup). Delegate uses remoteFilter.
const filteredResults = computed(() => {
  const source = (() => {
    if (props.remoteFilter) return allRows.value
    const kw = searchKeyword.value?.trim().toLowerCase()
    if (!kw) return allRows.value
    const fields = props.searchFields?.length ? props.searchFields : null
    return allRows.value.filter((row) => {
      const values = fields ? fields.map((f) => row[f]) : Object.values(row)
      return values.some((v) => v != null && String(v).toLowerCase().includes(kw))
    })
  })()
  return source.filter((row) => !isExcludedRow(row))
})

async function loadAllData() {
  if (!props.tableId) return
  const vfPromise = !effectiveViewFields.value.length
    ? relationTableApi.getViewFields(props.tableId)
    : Promise.resolve({ data: [] as LookupViewField[] })
  const [, vfRes] = await Promise.all([
    loadInitial(),
    // FALLBACK(ux): column headers optional; lookup rows still load if view-fields fail
    vfPromise.catch(() => ({ data: [] as LookupViewField[] })),
  ])
  if (vfRes.data?.length) {
    loadedViewFields.value = vfRes.data as LookupViewField[]
    emit('viewFieldsLoaded', loadedViewFields.value)
  }
  maybeClearMissingSelection(allRows.value)
}

const { nextZIndex } = useZIndex()
const dropdownZIndex = ref(3000)

// ---- z-index hardening (admin-center LookupField carries a copy — keep the two in sync). ----
// Absolute floor: keep the dropdown above the base app chrome even if no overlay is present.
const LOOKUP_DROPDOWN_Z_FLOOR = 3000
// Safety margin above the current top overlay so the dropdown clears not just the overlay
// but its own popper children (el-scrollbar, tooltips) that Element Plus stacks a few above it.
const LOOKUP_DROPDOWN_Z_OFFSET = 10

// Highest z-index among the currently active dialogs / overlays / poppers on the page.
// The lookup is teleported to <body>, so it competes directly with these for stacking; a single
// nextZIndex() is not enough because Element Plus can hand later overlays (or their poppers) a
// higher counter value, re-covering the dropdown after it opened.
function currentTopLayerZIndex(): number {
  const SELECTOR = '.el-overlay, .el-overlay-dialog, .el-dialog, .el-popper, .el-picker__popper, .el-select__popper'
  let top = 0
  document.querySelectorAll<HTMLElement>(SELECTOR).forEach(node => {
    // Skip our own dropdown so re-computation doesn't chase its own z-index upward.
    if (node === dropdownRef.value) return
    const z = parseInt(window.getComputedStyle(node).zIndex, 10)
    if (Number.isFinite(z) && z > top) top = z
  })
  return top
}

// Resolve the dropdown's z-index against the live stacking context: above the current top overlay
// + margin, never below the absolute floor. `base` seeds the lower bound — at open we pass a fresh
// nextZIndex() (bumps EP's shared counter once); on scroll/resize we pass the current value so the
// dropdown never drops below where it already sits and we don't burn the counter per scroll tick.
function resolveDropdownZIndex(base: number): number {
  return Math.max(base, currentTopLayerZIndex() + LOOKUP_DROPDOWN_Z_OFFSET, LOOKUP_DROPDOWN_Z_FLOOR)
}

// Position the teleported dropdown under the field using its viewport rect.
function updateDropdownPosition() {
  const el = wrapperRef.value
  if (!el) return
  const r = el.getBoundingClientRect()
  dropdownStyle.value = {
    position: 'fixed',
    top: `${r.bottom + 4}px`,
    left: `${r.left}px`,
    width: `${r.width}px`,
    zIndex: String(dropdownZIndex.value),
  }
}

function handleFocus() {
  if (props.readonly) return
  // Compute the z-index against the current top overlay so the dropdown sits above it — critical
  // when the lookup is rendered inside an el-dialog (e.g. Add ATM Transaction), whose overlay
  // z-index would otherwise cover a fixed-z dropdown.
  dropdownZIndex.value = resolveDropdownZIndex(nextZIndex())
  dropdownVisible.value = true
  updateDropdownPosition()
  loadAllData()
}

function pkField(): string {
  return String(props.searchFields?.[0] || 'id').trim() || 'id'
}

function rowPk(row: Record<string, any>): any {
  const pk = pkField()
  return row?.[pk] ?? row?.id
}

function isRowSelected(row: Record<string, any>): boolean {
  const pk = rowPk(row)
  return selectedRows.value.some(r => String(rowPk(r)) === String(pk))
}

function tagTextFor(row: Record<string, any>): string {
  return String(getDisplayValue(row) ?? '')
}

function emitMultiModel() {
  // Portal forms store full row objects (same as single-select) so tags can use
  // designer selectedDisplayField without a racey hydrate-by-PK round-trip.
  emit('update:modelValue', selectedRows.value.map(r => ({ ...r })))
}

function handleSelect(row: Record<string, any>) {
  if (isExcludedRow(row)) return
  if (props.multiple) {
    const pk = rowPk(row)
    const idx = selectedRows.value.findIndex(r => String(rowPk(r)) === String(pk))
    if (idx >= 0) {
      selectedRows.value.splice(idx, 1)
    } else {
      selectedRows.value.push(row)
    }
    searchKeyword.value = ''
    emitMultiModel()
    emit('select', row)
    // Keep the dropdown open for further picks.
    return
  }
  const displayVal = getDisplayValue(row)
  searchKeyword.value = String(displayVal ?? '')
  selectedRow.value = row
  emit('update:modelValue', row)
  emit('select', row)
  dropdownVisible.value = false
}

function removeSelectedAt(i: number) {
  selectedRows.value.splice(i, 1)
  emitMultiModel()
}

function handleClear() {
  searchKeyword.value = ''
  selectedRow.value = null
  selectedRows.value = []
  if (props.remoteFilter) resetRows()
  emit('update:modelValue', props.multiple ? [] : null)
  emit('clear')
}

function currentSelectionPks(): string[] {
  if (props.multiple) {
    const arr = Array.isArray(props.modelValue) ? props.modelValue : selectedRows.value
    return arr.map((item: unknown) => {
      if (item && typeof item === 'object') return String(rowPk(item as Record<string, unknown>) ?? '')
      return item == null || item === '' ? '' : String(item)
    }).filter(Boolean)
  }
  const val = props.modelValue
  if (val == null || val === '') return []
  if (typeof val === 'object') {
    const pk = rowPk(val as Record<string, unknown>)
    return pk == null || pk === '' ? [] : [String(pk)]
  }
  return [String(val)]
}

function maybeClearMissingSelection(rows: Record<string, unknown>[]) {
  if (!props.autoClearIfMissing) return
  const pks = currentSelectionPks()
  if (!pks.length) return
  const present = new Set(rows.map((row) => String(rowPk(row))))
  if (pks.every((pk) => present.has(pk))) return
  handleClear()
}

// Sync empty parent modelValue into local UI only — never emit('clear').
// emit('clear') is reserved for user-initiated handleClear (FormRenderer cascade wipe).
function clearLookupSelectionFromModel() {
  selectedRow.value = null
  searchKeyword.value = ''
}

function initFromModelValue(val: any) {
  if (props.multiple) {
    initMultiFromModelValue(val)
    return
  }
  // Start Process may persist multi LOOKUP as an array while a later node form
  // (e.g. Assign Task) still has multiple:false — unwrap so we don't treat the
  // Array itself as a row (Object.keys → "0","1"…; getDisplayValue → "-").
  if (Array.isArray(val)) {
    initFromModelValue(unwrapSingleLookupModelValue(val))
    return
  }
  // Sync from model only — never emit('clear') here. FormRenderer runs initFormData in onMounted,
  // so LookupField can mount with a briefly-empty internal formData; emitting clear would cascade
  // wipe parent process/task variables (stage → null, multi test_status → []) before init copies them in.
  if (val == null || val === '') {
    clearLookupSelectionFromModel()
    return
  }
  // Process variables often persist lookup as a scalar id/string — readonly inline rows otherwise render "-" forever.
  // Do NOT emit('select') here: FormRenderer handleLookupSelect would overwrite formData / re-enter cascade
  // (autofill PK → synthetic object → hydrate aborted → blank Test_status tag).
  if (typeof val === 'number' || typeof val === 'bigint' || typeof val === 'boolean') {
    const scalarRow = buildSyntheticLookupRow(val)
    selectedRow.value = scalarRow
    searchKeyword.value = String(getDisplayValue(scalarRow) || '')
    void hydrateScalarFromRelationTable(val)
    return
  }
  if (typeof val === 'string') {
    const trimmed = val.trim()
    if (trimmed === '') {
      clearLookupSelectionFromModel()
      return
    }
    const scalarRow = buildSyntheticLookupRow(trimmed)
    selectedRow.value = scalarRow
    searchKeyword.value = String(getDisplayValue(scalarRow) || '')
    void hydrateScalarFromRelationTable(trimmed)
    return
  }
  if (typeof val === 'object' && Object.keys(val).length > 0) {
    selectedRow.value = val
    const displayVal = getDisplayValue(val)
    searchKeyword.value = String(displayVal ?? '')
  }
}

/** Multi-select init: modelValue is row objects, PK scalars, or a JSON-array string. */
function initMultiFromModelValue(val: any) {
  let items: any[] = []
  if (Array.isArray(val)) {
    items = val
  } else if (typeof val === 'string' && val.trim() !== '') {
    try {
      const parsed = JSON.parse(val)
      items = Array.isArray(parsed) ? parsed : [val]
    } catch {
      items = [val]
    }
  } else if (val != null && val !== '') {
    items = [val]
  }
  items = items.filter(p => p != null && !(typeof p === 'string' && p.trim() === ''))
  if (!items.length) {
    selectedRows.value = []
    searchKeyword.value = ''
    return
  }
  // Full row objects from cascade autofill / prior selects — use as-is for tag text.
  if (items.every(p => typeof p === 'object' && !Array.isArray(p))) {
    selectedRows.value = items as Record<string, any>[]
    searchKeyword.value = ''
    return
  }
  // Legacy / engine PK arrays — seed synthetics then hydrate for display fields.
  const pks = items.map(p => (typeof p === 'object' && p != null ? rowPk(p) : p))
    .filter(p => p != null && String(p).trim() !== '')
  selectedRows.value = pks.map(p => buildSyntheticLookupRow(p))
  pks.forEach((p, idx) => void hydrateMultiRowAt(idx, p))
}

async function hydrateMultiRowAt(index: number, scalar: any) {
  if (!props.tableId) return
  try {
    // Omit derived filterConditions: cascade autofill already chose these PKs;
    // re-applying parent filters can leave tags stuck on "-".
    const row = await fetchLookupRowByPrimaryKey(props.tableId, scalar, {
      searchFields: props.searchFields || [],
      displayField: props.displayField || '',
      filterConditions: [],
      primaryKeyField: pkField(),
    })
    if (!row || !Object.keys(row).length) return
    // Only replace if the slot still holds the same PK (guard against races/edits).
    const cur = selectedRows.value[index]
    if (cur && String(rowPk(cur)) === String(scalar)) {
      selectedRows.value.splice(index, 1, row)
    }
  } catch {
    /* keep synthetic row */
  }
}

/**
 * 将仅存的主键标量解析为完整行，用于只读标签/回填视图；不写回 modelValue，避免改变流程持久化形态。
 */
async function hydrateScalarFromRelationTable(scalar: string | number) {
  if (!props.tableId) return
  const want = String(scalar).trim()
  try {
    // Omit derived filterConditions here: cascade autofill already chose this PK; re-applying
    // parent filters can race with parent clear/select and leave the tag blank.
    const row = await fetchLookupRowByPrimaryKey(props.tableId, scalar, {
      searchFields: props.searchFields || [],
      displayField: props.displayField || '',
      filterConditions: [],
      primaryKeyField: pkField(),
    })
    if (!row || !Object.keys(row).length) return
    const cur = props.modelValue
    if (cur == null || cur === '') return
    if (typeof cur === 'object' && !Array.isArray(cur)) {
      const curPk = String(rowPk(cur) ?? '').trim()
      if (curPk && curPk !== want) return
    } else if (String(cur).trim() !== want) {
      return
    }
    selectedRow.value = row
    searchKeyword.value = String(getDisplayValue(row) ?? '')
    // Display-only hydrate — do not emit select (avoids FormRenderer cascade / formData overwrite).
  } catch {
    /* 保持合成行回退 */
  }
}

function primaryDisplayField(): string {
  return getLookupSelectedDisplayFieldFromProps(props)
}

function refreshSearchKeywordFromSelectedRow() {
  if (!selectedRow.value) return
  searchKeyword.value = String(getDisplayValue(selectedRow.value) ?? '')
}

/** Minimal row shape for persisted scalar PK — do not copy PK into selectedDisplayField column. */
function buildSyntheticLookupRow(raw: number | bigint | boolean | string): Record<string, any> {
  const pk = String(props.searchFields?.[0] || 'id').trim() || 'id'
  const row: Record<string, any> = { [pk]: raw }
  if (pk !== 'id') row.id = raw
  return row
}

function getDisplayValue(row: Record<string, any>) {
  return resolveLookupCellTagText(props, row)
}

// Watch for external modelValue changes (e.g. form data loaded after mount).
// Lookup values change by wholesale replacement (select → new object, clear → null);
// initFromModelValue re-derives from the value as a whole, so a shallow (reference) watch suffices.
watch(
  () => props.modelValue,
  val => {
    initFromModelValue(val)
  },
  { immediate: true },
)

watch(
  () => [props.tableId, props.searchFields, props.displayField, props.filterConditions, props.reloadNonce],
  () => {
    resetRows()
    if (props.autoClearIfMissing && props.tableId) void loadAllData()
  },
  { deep: true }
)

watch(searchKeyword, (kw) => {
  if (!props.remoteFilter || !dropdownVisible.value || selectedRow.value) return
  scheduleRemoteSearch(kw)
})

watch(
  () => [
    props.selectedDisplayField,
    props.displayField,
    props.displayFields,
    props.lookupConfig,
  ],
  () => {
    refreshSearchKeywordFromSelectedRow()
  },
)

function onClickOutside(e: MouseEvent) {
  const target = e.target as Node
  // Dropdown is teleported to body, so a click on a result row is "outside" the wrapper —
  // treat clicks inside either the wrapper or the floating dropdown as inside.
  const insideWrapper = wrapperRef.value?.contains(target)
  const insideDropdown = dropdownRef.value?.contains(target)
  if (!insideWrapper && !insideDropdown) {
    dropdownVisible.value = false
  }
}

// Keep the floating dropdown aligned while it's open; close on far scroll is acceptable but we just reposition.
// Also re-resolve the z-index: scrolling/resizing the host dialog can raise a sibling overlay above us,
// so refresh stacking together with position to avoid the dropdown slipping back under the dialog.
function onViewportChange() {
  if (dropdownVisible.value) {
    dropdownZIndex.value = resolveDropdownZIndex(dropdownZIndex.value)
    updateDropdownPosition()
  }
}

onMounted(() => {
  document.addEventListener('mousedown', onClickOutside)
  window.addEventListener('scroll', onViewportChange, true)
  window.addEventListener('resize', onViewportChange)

  // Eagerly load view fields so LookupViewDisplay can show them after selection
  if (props.tableId && !effectiveViewFields.value.length) {
    relationTableApi.getViewFields(props.tableId).then(res => {
      if (res.data?.length) {
        loadedViewFields.value = res.data as LookupViewField[]
        emit('viewFieldsLoaded', loadedViewFields.value)
      }
    }).catch(() => {})
  } else if (effectiveViewFields.value.length) {
    // Props already have view fields — emit them so FormRenderer's lookupLoadedViewFields is populated
    emit('viewFieldsLoaded', effectiveViewFields.value as LookupViewField[])
  }
})
onBeforeUnmount(() => {
  scheduleRemoteSearch.cancel()
  document.removeEventListener('mousedown', onClickOutside)
  window.removeEventListener('scroll', onViewportChange, true)
  window.removeEventListener('resize', onViewportChange)
})

defineExpose({ effectiveViewFields, handleFocus, visibleColumns, filteredResults })
</script>

<style lang="scss" scoped>
.lookup-field {
  width: 100%;
  position: relative;

  &.readonly {
    cursor: not-allowed;

    .lookup-input :deep(.el-input__wrapper) {
      background-color: var(--el-disabled-bg-color, #f5f7fa);
      box-shadow: 0 0 0 1px var(--el-disabled-border-color, #e4e7ed) inset;
      cursor: not-allowed;
      pointer-events: none;
    }
  }

  .lookup-selected-wrapper {
    display: flex;
    align-items: center;
    min-height: 32px;
    padding: 4px 8px;
    border: 1px solid #dcdfe6;
    border-radius: 4px;
    background: #fff;

    &.is-readonly {
      background: var(--el-disabled-bg-color, #f5f7fa);
      border-color: var(--el-disabled-border-color, #e4e7ed);
      cursor: not-allowed;
      pointer-events: none;
    }
  }

  .lookup-selected-tag {
    display: inline-flex;
    align-items: center;
    max-width: 100%;
    height: 24px;
    padding: 0 8px;
    border-radius: 4px;
    background: #f0f2f5;
    font-size: 13px;
    color: #909399;
    line-height: 24px;

    .lookup-selected-text {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .lookup-selected-close {
      flex-shrink: 0;
      margin-left: 4px;
      font-size: 13px;
      color: #909399;
      cursor: pointer;

      &:hover {
        color: #606266;
      }
    }
  }

  .lookup-multi-wrapper {
    flex-wrap: wrap;
    gap: 4px;
    cursor: text;

    .lookup-selected-tag {
      background: var(--el-color-primary-light-9, #ecf5ff);
      color: var(--el-color-primary, #409eff);
    }
  }

  .lookup-multi-input {
    flex: 1;
    min-width: 60px;
    border: none;
    outline: none;
    background: transparent;
    font-size: 13px;
    line-height: 24px;
    color: #606266;
  }

  .lookup-readonly-empty {
    color: #606266;
    line-height: 32px;
  }

  .lookup-input {
    width: 100%;
  }
}
</style>

<!-- Floating dropdown is teleported to <body>, so its styles must be global. -->
<style lang="scss" src="./LookupField.dropdown.scss"></style>
