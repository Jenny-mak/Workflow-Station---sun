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
          <el-form-item :label="t('emailMonitor.wizard.sampleAttachments')">
            <el-input
              v-model="sample.attachmentNames"
              :placeholder="t('emailMonitor.wizard.sampleAttachmentsPlaceholder')"
            />
            <div class="wizard-hint">{{ t('emailMonitor.wizard.sampleAttachmentsHint') }}</div>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- Main field mapping -->
      <el-tab-pane :label="t('emailMonitor.wizard.fieldMapping')" name="fields">
        <EmailFieldMappingTable
          :fields="fields"
          :main-field-options="mainFieldOptions"
          :last-selection="lastSelection"
          :attachment-preview="sample.attachmentNames"
          :preview-field="previewField"
          @add-field="addFieldRule"
          @bind-selection="addRuleFromSelection"
        />
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
import EmailFieldMappingTable from '@/components/designer/email/EmailFieldMappingTable.vue'
import { useProcessFormSubBindings, type SubTableFieldOption } from '@/composables/email/useProcessFormSubBindings'
import { invalidAttachmentTargets } from '@/composables/email/emailExtractionFieldMapping'
import { computePreviewRows, previewField as previewMappedField } from '@/composables/email/emailExtractionPreview'
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
  attachmentNames: '',
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
  sample.attachmentNames = s?.attachmentNames ?? ''
}
seedFromModel(props.modelValue)

function addFieldRule() {
  fields.push({ target: '', source: 'TEXT_AND_HTML', type: 'LABEL', required: false })
}

function previewField(rule: ExtractionFieldRule): string {
  return previewMappedField(sample, rule)
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
    || sample.attachmentNames?.trim()
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
      attachmentNames: sample.attachmentNames.trim() || undefined,
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

function attachmentTargetErrors(): string[] {
  return invalidAttachmentTargets(fields, mainFieldOptions.value)
}

defineExpose({ buildRules, attachmentTargetErrors })
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
