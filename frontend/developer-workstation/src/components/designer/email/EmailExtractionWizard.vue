<template>
  <div class="email-extraction-wizard">
    <el-tabs v-model="activeTab">
      <!-- Sample email -->
      <el-tab-pane :label="t('emailMonitor.wizard.sample')" name="sample">
        <el-form label-position="top" size="small">
          <el-form-item :label="t('emailMonitor.wizard.sampleSubject')">
            <el-input v-model="sample.subject" :placeholder="t('emailMonitor.wizard.sampleSubjectPlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('emailMonitor.wizard.sampleFrom')">
            <el-input v-model="sample.from" placeholder="sender@example.com" />
          </el-form-item>
          <el-form-item :label="t('emailMonitor.wizard.sampleTo')">
            <el-input v-model="sample.to" placeholder="recipient@example.com" />
          </el-form-item>
          <el-form-item :label="t('emailMonitor.wizard.sampleCc')">
            <el-input v-model="sample.cc" placeholder="cc@example.com" />
          </el-form-item>
          <el-form-item :label="t('emailMonitor.wizard.sampleReplyTo')">
            <el-input v-model="sample.replyTo" placeholder="reply@example.com" />
          </el-form-item>
          <el-form-item :label="t('emailMonitor.wizard.sampleDate')">
            <el-input v-model="sample.date" placeholder="2026-09-08T08:30:00Z" />
          </el-form-item>
          <el-form-item :label="t('emailMonitor.wizard.sampleMessageId')">
            <el-input v-model="sample.messageId" placeholder="&lt;message-id@example.com&gt;" />
          </el-form-item>
          <el-form-item :label="t('emailMonitor.wizard.sampleText')">
            <el-input
              v-model="sample.text"
              type="textarea"
              :rows="8"
              :placeholder="t('emailMonitor.wizard.sampleTextPlaceholder')"
              @mouseup="captureSelection"
            />
            <div class="wizard-hint">{{ t('emailMonitor.wizard.selectionHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('emailMonitor.wizard.sampleHtml')">
            <el-input
              v-model="sample.html"
              type="textarea"
              :rows="5"
              placeholder="<table>...</table>"
            />
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- Main field mapping -->
      <el-tab-pane :label="t('emailMonitor.wizard.fieldMapping')" name="fields">
        <div class="wizard-toolbar">
          <el-button size="small" type="primary" @click="addFieldRule">
            {{ t('emailMonitor.wizard.addField') }}
          </el-button>
          <span v-if="lastSelection" class="wizard-selection">
            {{ t('emailMonitor.wizard.selected') }}: "{{ lastSelection }}"
            <el-button size="small" link type="primary" @click="addRuleFromSelection">
              {{ t('emailMonitor.wizard.bindSelection') }}
            </el-button>
          </span>
        </div>
        <el-table :data="fields" size="small" border>
          <el-table-column :label="t('emailMonitor.wizard.targetField')" min-width="180">
            <template #default="{ row }">
              <el-select
                v-model="row.target"
                size="small"
                filterable
                clearable
                :placeholder="t('emailMonitor.wizard.targetFieldPlaceholder')"
                class="target-field-select"
              >
                <el-option
                  v-for="f in mainFieldOptionsForRow(row.target)"
                  :key="f.fieldName"
                  :label="fieldOptionLabel(f)"
                  :value="f.fieldName"
                />
                <template v-if="mainFieldOptions.length === 0" #empty>
                  <span class="el-select-dropdown__empty">
                    {{ t('emailMonitor.wizard.mainTargetFieldEmpty') }}
                  </span>
                </template>
              </el-select>
            </template>
          </el-table-column>
          <el-table-column :label="t('emailMonitor.wizard.source')" width="170">
            <template #default="{ row }">
              <el-select v-model="row.source" size="small" @change="onSourceChange(row)">
                <el-option-group :label="t('emailMonitor.wizard.sourceGroupAttributes')">
                  <el-option
                    v-for="s in ATTRIBUTE_SOURCES"
                    :key="s"
                    :label="sourceLabel(s)"
                    :value="s"
                  />
                </el-option-group>
                <el-option-group :label="t('emailMonitor.wizard.sourceGroupBody')">
                  <el-option
                    v-for="s in BODY_SOURCES"
                    :key="s"
                    :label="sourceLabel(s)"
                    :value="s"
                  />
                </el-option-group>
              </el-select>
            </template>
          </el-table-column>
          <el-table-column :label="t('emailMonitor.wizard.type')" width="130">
            <template #default="{ row }">
              <el-select
                v-if="!isLockedAttributeSource(row.source)"
                v-model="row.type"
                size="small"
              >
                <el-option
                  v-for="ty in typesForSource(row.source)"
                  :key="ty"
                  :label="typeLabel(ty)"
                  :value="ty"
                />
              </el-select>
              <span v-else class="wizard-direct-label">{{ typeLabel('DIRECT') }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('emailMonitor.wizard.config')" min-width="220">
            <template #default="{ row }">
              <span v-if="row.type === 'DIRECT'" class="wizard-hint">—</span>
              <el-input v-else-if="row.type === 'LABEL'" v-model="row.label" size="small" placeholder="Case No: " />
              <template v-else-if="row.type === 'BETWEEN'">
                <el-input v-model="row.before" size="small" :placeholder="t('emailMonitor.wizard.before')" />
                <el-input v-model="row.after" size="small" :placeholder="t('emailMonitor.wizard.after')" />
              </template>
              <el-input v-else-if="row.type === 'REGEX'" v-model="row.pattern" size="small" placeholder="(\\d+)" />
              <el-input v-else-if="row.type === 'CONST'" v-model="row.value" size="small" placeholder="EMAIL" />
              <el-input v-else-if="row.type === 'HEADER'" v-model="row.header" size="small" placeholder="From" />
            </template>
          </el-table-column>
          <el-table-column :label="t('emailMonitor.wizard.required')" width="70">
            <template #default="{ row }">
              <el-switch v-model="row.required" size="small" />
            </template>
          </el-table-column>
          <el-table-column :label="t('emailMonitor.wizard.preview')" min-width="120">
            <template #default="{ row }">
              <span class="wizard-preview-val">{{ previewField(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column width="60">
            <template #default="{ $index }">
              <el-button size="small" link type="danger" @click="fields.splice($index, 1)">
                {{ t('common.delete') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- Sub-table (HTML) mapping -->
      <el-tab-pane :label="t('emailMonitor.wizard.subTableMapping')" name="subtable">
        <div class="wizard-hint">{{ t('emailMonitor.wizard.subTableHint') }}</div>
        <div class="wizard-toolbar" style="margin-top: 8px;">
          <el-button
            size="small"
            type="primary"
            :disabled="subBindingOptions.length === 0"
            @click="addSubTable"
          >
            {{ t('emailMonitor.wizard.addSubTable') }}
          </el-button>
          <span v-if="!subBindingLoading && subBindingOptions.length === 0" class="wizard-hint">
            {{ t('emailMonitor.wizard.subTableBindingEmpty') }}
          </span>
        </div>

        <el-empty
          v-if="subTables.length === 0"
          :description="t('emailMonitor.wizard.subTableNone')"
          :image-size="60"
        />

        <div v-for="(st, sIdx) in subTables" :key="sIdx" class="subtable-block">
          <div class="subtable-block-header">
            <span class="subtable-block-title">
              {{ t('emailMonitor.wizard.subTableBlock', { n: sIdx + 1 }) }}
            </span>
            <el-button size="small" link type="danger" @click="subTables.splice(sIdx, 1)">
              {{ t('common.delete') }}
            </el-button>
          </div>

          <el-form label-position="top" size="small">
            <div class="subtable-config-row">
              <el-form-item :label="t('emailMonitor.wizard.subTableBinding')" class="subtable-binding-item">
                <SubTableBindingSelect
                  :model-value="bindingIdOf(st)"
                  :sub-bindings="subBindingOptions"
                  class="binding-select"
                  @update:model-value="(id: number | null) => onBindingChange(st, id)"
                />
              </el-form-item>
              <el-form-item :label="t('emailMonitor.wizard.tableIndex')" class="subtable-index-item">
                <el-input-number v-model="st.tableIndex" :min="0" size="small" controls-position="right" />
                <div class="wizard-hint">{{ t('emailMonitor.wizard.tableIndexHint') }}</div>
              </el-form-item>
              <el-form-item :label="t('emailMonitor.wizard.tableSelector')" class="subtable-selector-item">
                <el-input v-model="st.tableSelector" size="small" placeholder="table" />
                <div class="wizard-hint">{{ t('emailMonitor.wizard.tableSelectorHint') }}</div>
              </el-form-item>
              <el-form-item label=" " class="subtable-header-item">
                <el-checkbox v-model="st.headerRow">{{ t('emailMonitor.wizard.headerRow') }}</el-checkbox>
              </el-form-item>
            </div>
          </el-form>

          <el-button size="small" type="primary" :disabled="!st.bindingId" @click="addColumn(st)">
            {{ t('emailMonitor.wizard.addColumn') }}
          </el-button>
          <el-table :data="st.columns" size="small" border style="margin-top: 8px;">
            <el-table-column :label="t('emailMonitor.wizard.columnIndex')" width="120">
              <template #default="{ row }">
                <el-input-number v-model="row.columnIndex" size="small" :min="0" controls-position="right" />
              </template>
            </el-table-column>
            <el-table-column :label="t('emailMonitor.wizard.targetField')" min-width="200">
              <template #default="{ row }">
                <el-select
                  v-model="row.field"
                  size="small"
                  filterable
                  clearable
                  :disabled="!st.bindingId"
                  :placeholder="t('emailMonitor.wizard.targetFieldPlaceholder')"
                  class="target-field-select"
                >
                  <el-option
                    v-for="f in fieldOptionsForBlockRow(st, row.field)"
                    :key="f.fieldName"
                    :label="fieldOptionLabel(f)"
                    :value="f.fieldName"
                  />
                  <template v-if="fieldOptionsOf(st).length === 0" #empty>
                    <span class="el-select-dropdown__empty">
                      {{ t('emailMonitor.wizard.targetFieldEmpty') }}
                    </span>
                  </template>
                </el-select>
              </template>
            </el-table-column>
            <el-table-column width="60">
              <template #default="{ $index }">
                <el-button size="small" link type="danger" @click="st.columns.splice($index, 1)">
                  {{ t('common.delete') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="subTablePreviews[sIdx]?.length" class="wizard-subpreview">
            <div class="wizard-hint">
              {{ t('emailMonitor.wizard.preview') }} ({{ subTablePreviews[sIdx].length }})
            </div>
            <pre>{{ JSON.stringify(subTablePreviews[sIdx], null, 2) }}</pre>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, computed, watch, nextTick, toRef } from 'vue'
import { useI18n } from 'vue-i18n'
import SubTableBindingSelect from '@/components/designer/SubTableBindingSelect.vue'
import { useProcessFormSubBindings, type SubTableFieldOption } from '@/composables/email/useProcessFormSubBindings'
import { normalizeBindingId } from '@/utils/bindingDisplayHelpers'
import type {
  ExtractionRules,
  ExtractionFieldRule,
  ExtractionSubTableRule
} from '@/api/emailMonitor'

const props = defineProps<{
  modelValue?: ExtractionRules
  functionUnitId?: number
}>()
const emit = defineEmits<{ (e: 'update:modelValue', value: ExtractionRules): void }>()
const { t } = useI18n()

const ATTRIBUTE_SOURCES = [
  'SUBJECT', 'FROM', 'TO', 'CC', 'REPLY_TO', 'DATE', 'MESSAGE_ID',
] as const
const BODY_SOURCES = ['TEXT_AND_HTML', 'TEXT', 'HTML', 'HEADER', 'CONST'] as const
const LOCKED_ATTRIBUTE_SOURCES = ['FROM', 'TO', 'CC', 'REPLY_TO', 'DATE', 'MESSAGE_ID'] as const
const BODY_TYPES = ['LABEL', 'BETWEEN', 'REGEX', 'CONST', 'HEADER'] as const
const SUBJECT_TYPES = ['DIRECT', 'LABEL', 'BETWEEN', 'REGEX'] as const

function sourceLabel(source: typeof ATTRIBUTE_SOURCES[number] | typeof BODY_SOURCES[number]): string {
  return t(`emailMonitor.wizard.source_${source}`)
}

function typeLabel(type: string): string {
  if (type === 'DIRECT') {
    return t('emailMonitor.wizard.type_DIRECT')
  }
  return type
}

function isLockedAttributeSource(source?: string): boolean {
  return LOCKED_ATTRIBUTE_SOURCES.includes(source as typeof LOCKED_ATTRIBUTE_SOURCES[number])
}

function typesForSource(source?: string): string[] {
  if (source === 'SUBJECT') {
    return [...SUBJECT_TYPES]
  }
  return [...BODY_TYPES]
}

function onSourceChange(row: ExtractionFieldRule) {
  if (isLockedAttributeSource(row.source)) {
    row.type = 'DIRECT'
    return
  }
  if (row.source === 'SUBJECT' && !SUBJECT_TYPES.includes(row.type as typeof SUBJECT_TYPES[number])) {
    row.type = 'DIRECT'
    return
  }
  if (row.type === 'DIRECT') {
    row.type = 'LABEL'
  }
}

const activeTab = ref('sample')
const sample = reactive({
  subject: '',
  from: '',
  to: '',
  cc: '',
  replyTo: '',
  date: '',
  messageId: '',
  text: '',
  html: '',
})
const lastSelection = ref('')
const lastSelectionPrefix = ref('')

const fields = reactive<ExtractionFieldRule[]>([])
const subTables = reactive<ExtractionSubTableRule[]>([])

const { loading: subBindingLoading, options: subBindingOptions, fieldsByBindingId, mainFieldOptions } = useProcessFormSubBindings(
  toRef(() => props.functionUnitId),
)

function fieldOptionLabel(f: SubTableFieldOption): string {
  return f.displayName !== f.fieldName ? `${f.displayName} (${f.fieldName})` : f.fieldName
}

/** Keep legacy saved field names selectable even if metadata changed. */
function mainFieldOptionsForRow(currentTarget?: string): SubTableFieldOption[] {
  const base = mainFieldOptions.value
  const trimmed = currentTarget?.trim()
  if (trimmed && !base.some((f) => f.fieldName === trimmed)) {
    return [{ fieldName: trimmed, displayName: trimmed }, ...base]
  }
  return base
}

function bindingIdOf(st: ExtractionSubTableRule): number | null {
  return normalizeBindingId(st.bindingId)
}

function fieldOptionsOf(st: ExtractionSubTableRule): SubTableFieldOption[] {
  const id = bindingIdOf(st)
  if (id == null) {
    return []
  }
  return fieldsByBindingId.value[id] ?? []
}

function fieldOptionsForBlockRow(st: ExtractionSubTableRule, currentField?: string): SubTableFieldOption[] {
  const base = fieldOptionsOf(st)
  const trimmed = currentField?.trim()
  if (trimmed && !base.some((f) => f.fieldName === trimmed)) {
    return [{ fieldName: trimmed, displayName: trimmed }, ...base]
  }
  return base
}

/** When a block's binding changes, drop column mappings no longer valid for the new table. */
function onBindingChange(st: ExtractionSubTableRule, id: number | null) {
  st.bindingId = id != null ? String(id) : ''
  const allowed = new Set(fieldOptionsOf(st).map((f) => f.fieldName))
  for (const col of st.columns) {
    if (col.field?.trim() && !allowed.has(col.field.trim())) {
      col.field = ''
    }
  }
}

function seedFromModel(model?: ExtractionRules) {
  fields.splice(0, fields.length)
  ;(model?.fields ?? []).forEach(f => fields.push({ ...f }))
  subTables.splice(0, subTables.length)
  ;(model?.subTables ?? []).forEach(st => subTables.push({
    bindingId: st.bindingId ?? '',
    tableSelector: st.tableSelector ?? '',
    tableIndex: st.tableIndex ?? 0,
    headerRow: st.headerRow ?? true,
    columns: (st.columns ?? []).map(c => ({ ...c }))
  }))
  const s = model?.sampleEmail
  sample.subject = s?.subject ?? ''
  sample.from = s?.from ?? ''
  sample.to = s?.to ?? ''
  sample.cc = s?.cc ?? ''
  sample.replyTo = s?.replyTo ?? ''
  sample.date = s?.date ?? ''
  sample.messageId = s?.messageId ?? ''
  sample.text = s?.text ?? ''
  sample.html = s?.html ?? ''
}
seedFromModel(props.modelValue)

function addFieldRule() {
  fields.push({ target: '', source: 'TEXT_AND_HTML', type: 'LABEL', required: false })
}

function attributePreviewValue(source?: string): string | null {
  if (source === 'SUBJECT') return sample.subject?.trim() || null
  if (source === 'FROM') return sample.from?.trim() || null
  if (source === 'TO') return sample.to?.trim() || null
  if (source === 'CC') return sample.cc?.trim() || null
  if (source === 'REPLY_TO') return sample.replyTo?.trim() || null
  if (source === 'DATE') return sample.date?.trim() || null
  if (source === 'MESSAGE_ID') return sample.messageId?.trim() || null
  return null
}

/** New block defaults its tableIndex to the next HTML table (0,1,2…) — the common multi-table case. */
function addSubTable() {
  subTables.push({ bindingId: '', tableSelector: '', tableIndex: subTables.length, headerRow: true, columns: [] })
}

function addColumn(st: ExtractionSubTableRule) {
  st.columns.push({ field: '', columnIndex: st.columns.length })
}

/** Captures the current selection in the text sample and infers a same-line prefix anchor. */
function captureSelection(event: MouseEvent) {
  const el = event.target as HTMLTextAreaElement
  if (!el || el.selectionStart === el.selectionEnd) {
    return
  }
  const value = el.value
  const start = el.selectionStart
  lastSelection.value = value.substring(start, el.selectionEnd).trim()
  const lineStart = value.lastIndexOf('\n', start - 1) + 1
  lastSelectionPrefix.value = value.substring(lineStart, start)
}

function addRuleFromSelection() {
  if (!lastSelection.value) {
    return
  }
  fields.push({
    target: '',
    source: 'TEXT_AND_HTML',
    type: lastSelectionPrefix.value.trim() ? 'BETWEEN' : 'LABEL',
    before: lastSelectionPrefix.value.trim() || undefined,
    after: undefined,
    label: lastSelectionPrefix.value.trim() ? undefined : lastSelectionPrefix.value,
    required: false,
    postProcess: ['TRIM']
  })
  activeTab.value = 'fields'
}

function combinedSampleTextAndHtml(): string {
  const plain = sample.text?.trim() ?? ''
  const html = stripHtml(sample.html)?.trim() ?? ''
  if (!plain) return html
  if (!html) return plain
  if (plain.includes(html) || html.includes(plain)) {
    return plain.length >= html.length ? plain : html
  }
  return `${plain}\n${html}`
}

/** Client-side mirror of the backend interpreter for live preview. */
function sourceText(source?: string): string {
  if (source === 'SUBJECT') return sample.subject
  if (source === 'HTML') return stripHtml(sample.html)
  if (source === 'TEXT' || source === 'TEXT_AND_HTML') return combinedSampleTextAndHtml()
  return sample.text
}

function stripHtml(html: string): string {
  if (!html) return ''
  const doc = new DOMParser().parseFromString(html, 'text/html')
  return doc.body?.textContent ?? ''
}

function previewField(rule: ExtractionFieldRule): string {
  try {
    let raw: string | null = null
    if (rule.type === 'DIRECT') {
      raw = attributePreviewValue(rule.source) ?? sourceText(rule.source)
    } else if (rule.type === 'CONST') raw = rule.value ?? null
    else if (rule.type === 'HEADER') {
      const header = rule.header?.toLowerCase()
      if (header === 'from') raw = sample.from || null
      else if (header === 'to') raw = sample.to || null
      else if (header === 'cc') raw = sample.cc || null
      else if (header === 'reply-to') raw = sample.replyTo || null
      else if (header === 'date') raw = sample.date || null
      else if (header === 'message-id') raw = sample.messageId || null
      else raw = null
    } else if (rule.type === 'LABEL') raw = byLabel(sourceText(rule.source), rule.label)
    else if (rule.type === 'BETWEEN') raw = between(sourceText(rule.source), rule.before, rule.after)
    else if (rule.type === 'REGEX') raw = byRegex(sourceText(rule.source), rule.pattern, rule.group ?? 1)
    return applyPost(raw, rule.postProcess) ?? '—'
  } catch {
    return '—'
  }
}

function byLabel(text: string, label?: string): string | null {
  if (!text || !label) return null
  const idx = text.indexOf(label)
  if (idx < 0) return null
  const after = text.substring(idx + label.length)
  const eol = after.search(/[\r\n]/)
  return (eol < 0 ? after : after.substring(0, eol)).trim() || null
}

function between(text: string, before?: string, after?: string): string | null {
  if (!text || !before) return null
  const idx = text.indexOf(before)
  if (idx < 0) return null
  const start = idx + before.length
  const end = after ? text.indexOf(after, start) : -1
  return text.substring(start, end < 0 ? text.length : end).trim() || null
}

function byRegex(text: string, pattern?: string, group = 1): string | null {
  if (!text || !pattern) return null
  const m = new RegExp(pattern).exec(text)
  return m && m[group] != null ? m[group].trim() : null
}

function applyPost(value: string | null, steps?: string[]): string | null {
  if (value == null || !steps?.length) return value
  return steps.reduce((acc, step) => {
    if (acc == null) return acc
    if (step === 'TRIM') return acc.trim()
    if (step === 'DIGITS_ONLY') return acc.replace(/[^0-9]/g, '')
    if (step === 'STRIP_CURRENCY') return acc.replace(/[^0-9.,-]/g, '').trim()
    if (step === 'UPPER') return acc.toUpperCase()
    if (step === 'LOWER') return acc.toLowerCase()
    return acc
  }, value as string | null)
}

/** Mirrors backend table selection: tableSelector (default "table") + tableIndex picks one table. */
function computePreviewRows(st: ExtractionSubTableRule, doc: Document): Record<string, string>[] {
  if (!st.columns.length) return []
  const selector = st.tableSelector?.trim() || 'table'
  let tables: Element[]
  try {
    tables = Array.from(doc.querySelectorAll(selector))
  } catch {
    return []
  }
  const table = tables[st.tableIndex ?? 0]
  if (!table) return []
  const trs = Array.from(table.querySelectorAll('tr'))
  const rows = st.headerRow ? trs.slice(1) : trs
  return rows.map(tr => {
    const cells = Array.from(tr.querySelectorAll('td,th'))
    const obj: Record<string, string> = {}
    st.columns.forEach(col => {
      if (col.field && col.columnIndex != null && cells[col.columnIndex]) {
        obj[col.field] = (cells[col.columnIndex].textContent ?? '').trim()
      }
    })
    return obj
  }).filter(o => Object.keys(o).length)
}

const subTablePreviews = computed((): Record<string, string>[][] => {
  if (!sample.html) return subTables.map(() => [])
  let doc: Document
  try {
    doc = new DOMParser().parseFromString(sample.html, 'text/html')
  } catch {
    return subTables.map(() => [])
  }
  return subTables.map(st => computePreviewRows(st, doc))
})

function buildRules(): ExtractionRules {
  const result: ExtractionRules = {}
  const cleanFields = fields.filter(f => f.target?.trim())
  if (cleanFields.length) result.fields = cleanFields.map(f => ({ ...f }))
  const cleanSubTables = subTables
    .filter(st => st.bindingId?.trim() && st.columns.some(c => c.field?.trim()))
    .map(st => {
      const rule: ExtractionSubTableRule = {
        bindingId: st.bindingId!.trim(),
        tableIndex: st.tableIndex ?? 0,
        headerRow: st.headerRow,
        columns: st.columns.filter(c => c.field?.trim()).map(c => ({ ...c }))
      }
      if (st.tableSelector?.trim()) rule.tableSelector = st.tableSelector.trim()
      return rule
    })
  if (cleanSubTables.length) result.subTables = cleanSubTables
  if (
    sample.subject?.trim()
    || sample.from?.trim()
    || sample.to?.trim()
    || sample.cc?.trim()
    || sample.replyTo?.trim()
    || sample.date?.trim()
    || sample.messageId?.trim()
    || sample.text?.trim()
    || sample.html?.trim()
  ) {
    result.sampleEmail = {
      subject: sample.subject.trim() || undefined,
      from: sample.from.trim() || undefined,
      to: sample.to.trim() || undefined,
      cc: sample.cc.trim() || undefined,
      replyTo: sample.replyTo.trim() || undefined,
      date: sample.date.trim() || undefined,
      messageId: sample.messageId.trim() || undefined,
      text: sample.text.trim() || undefined,
      html: sample.html.trim() || undefined,
    }
  }
  return result
}

function rulesEqual(a?: ExtractionRules, b?: ExtractionRules): boolean {
  return JSON.stringify(a ?? {}) === JSON.stringify(b ?? {})
}

/** Prevent emit ↔ seed circular updates (was freezing the UI). */
let suppressEmit = false

watch([fields, subTables, sample], () => {
  if (suppressEmit) return
  const next = buildRules()
  if (rulesEqual(props.modelValue, next)) return
  emit('update:modelValue', next)
}, { deep: true })

watch(() => props.modelValue, (model) => {
  if (rulesEqual(model, buildRules())) return
  suppressEmit = true
  seedFromModel(model)
  nextTick(() => { suppressEmit = false })
}, { deep: true })

defineExpose({ buildRules })
</script>

<style scoped lang="scss">
.email-extraction-wizard {
  .wizard-hint {
    font-size: 12px;
    color: #909399;
    margin-top: 4px;
  }
  .wizard-toolbar {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 8px;
  }
  .wizard-selection {
    font-size: 12px;
    color: #606266;
  }
  .wizard-preview-val {
    font-family: monospace;
    color: #409eff;
  }
  .wizard-direct-label {
    font-size: 12px;
    color: #606266;
  }
  .wizard-subpreview {
    margin-top: 12px;
    pre {
      background: #f5f7fa;
      padding: 8px;
      border-radius: 4px;
      max-height: 180px;
      overflow: auto;
      font-size: 12px;
    }
  }
  .binding-select {
    width: 100%;
  }
  .binding-select :deep(.el-select) {
    width: 100%;
  }
  .target-field-select {
    width: 100%;
  }
  .subtable-block {
    border: 1px solid #ebeef5;
    border-radius: 6px;
    padding: 12px;
    margin-top: 12px;
    background: #fafafa;
  }
  .subtable-block-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 8px;
  }
  .subtable-block-title {
    font-size: 13px;
    font-weight: 600;
    color: #303133;
  }
  .subtable-config-row {
    display: flex;
    gap: 16px;
    flex-wrap: wrap;
    align-items: flex-start;
  }
  .subtable-binding-item {
    flex: 1 1 240px;
    min-width: 240px;
  }
  .subtable-index-item {
    flex: 0 0 140px;
  }
  .subtable-selector-item {
    flex: 1 1 180px;
    min-width: 160px;
  }
  .subtable-header-item {
    flex: 0 0 auto;
  }
  .subtable-config-row :deep(.el-form-item) {
    margin-bottom: 8px;
  }
}
</style>
