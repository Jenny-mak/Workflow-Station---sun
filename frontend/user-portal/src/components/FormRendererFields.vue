<script setup lang="ts">
import { inject, ref, defineAsyncComponent } from 'vue'
import { Search } from '@element-plus/icons-vue'
import FieldRenderer from './FieldRenderer.vue'
const SubTableField = defineAsyncComponent({
  loader: () => import('./SubTableField.vue'),
  delay: 0,
})
import SubTableInlineForm from './SubTableInlineForm.vue'
import RecordNoteField from './RecordNoteField.vue'
import LookupField from './lookup/LookupField.vue'
import LookupViewDisplay from './lookup/LookupViewDisplay.vue'
import type { FormField } from './formRendererHelpers'
import { FORM_RENDERER_FIELDS_CTX } from './formRendererFieldsContext'
import { isDisplayOnlyLayoutField } from './formRendererHelpers'

defineOptions({ name: 'FormRendererFields' })

const props = withDefaults(
  defineProps<{
    fields: FormField[]
    /** Inside fcCol — stack fields vertically without grid el-col wrappers. */
    inColumn?: boolean
    /** Inside fcRow — expect fcCol children rendered as el-col. */
    rowColumns?: boolean
  }>(),
  {
    inColumn: false,
    rowColumns: false,
  },
)

const ctx = inject(FORM_RENDERER_FIELDS_CTX)
if (!ctx) {
  throw new Error('FormRendererFields requires FORM_RENDERER_FIELDS_CTX from FormRenderer')
}

const collapseActiveByKey = ref<Record<string, string[]>>({})

function fieldShowsRequired(field: FormField): boolean {
  return ctx.isFieldRequired(field)
}

function collapseActiveNames(field: FormField): string[] {
  const key = field.key
  const existing = collapseActiveByKey.value[key]
  if (existing) return existing
  const first = field.collapsePanels?.[0]?.name
  const initial = first != null ? [String(first)] : []
  collapseActiveByKey.value[key] = initial
  return initial
}

// el-collapse 的 update:model-value 实际给的是 CollapseModelValue
// （Array<string | number>，折叠面板 name 可以是数字）。原签名只写 string | string[]，
// 函数体本来就用 String() 归一化了两种情况，这里只是把签名对齐真实入参。
function onCollapseActiveChange(fieldKey: string, names: string | number | Array<string | number>) {
  collapseActiveByKey.value[fieldKey] = Array.isArray(names) ? names.map(String) : [String(names)]
}
</script>

<template>
  <template
    v-for="field in fields"
    :key="field.key"
  >
    <!-- fcCol inside fcRow -->
    <el-col
      v-if="rowColumns && field.type === 'col' && ctx.isFieldVisible(field.key)"
      :span="field.span || 12"
    >
      <FormRendererFields
        :fields="field.children || []"
        in-column
      />
    </el-col>

    <!-- fcRow layout -->
    <template v-else-if="field.type === 'row' && ctx.isFieldVisible(field.key)">
      <el-col
        v-if="!inColumn"
        :span="24"
      >
        <el-row :gutter="field.gutter ?? 20">
          <FormRendererFields
            :fields="field.children || []"
            row-columns
          />
        </el-row>
      </el-col>
      <el-row
        v-else
        :gutter="field.gutter ?? 20"
        class="form-row-in-column"
      >
        <FormRendererFields
          :fields="field.children || []"
          row-columns
        />
      </el-row>
    </template>

    <!-- Card layout -->
    <template v-else-if="field.type === 'card' && ctx.isFieldVisible(field.key)">
      <el-col
        v-if="!inColumn"
        :span="field.span || 24"
      >
        <el-card
          shadow="never"
          class="form-layout-card"
        >
          <template
            v-if="ctx.fieldLabel(field)"
            #header
          >
            <span class="form-layout-card-title">{{ ctx.fieldLabel(field) }}</span>
          </template>
          <el-row :gutter="20">
            <FormRendererFields :fields="field.children || []" />
          </el-row>
        </el-card>
      </el-col>
      <el-card
        v-else
        shadow="never"
        class="form-layout-card form-layout-card--stacked"
      >
        <template
          v-if="ctx.fieldLabel(field)"
          #header
        >
          <span class="form-layout-card-title">{{ ctx.fieldLabel(field) }}</span>
        </template>
        <FormRendererFields
          :fields="field.children || []"
          in-column
        />
      </el-card>
    </template>

    <!-- Nested el-tabs (inside tab pane / card) -->
    <template v-else-if="field.type === 'tabs' && field.tabs?.length && ctx.isFieldVisible(field.key)">
      <el-col
        v-if="!inColumn"
        :span="24"
      >
        <el-tabs class="form-renderer-nested-tabs">
          <el-tab-pane
            v-for="(tab, tabIdx) in field.tabs"
            :key="`${field.key}-tab-${tabIdx}-${String(tab.name)}`"
            :label="tab.label"
            :name="tab.name"
          >
            <FormRendererFields :fields="tab.fields || []" />
          </el-tab-pane>
        </el-tabs>
      </el-col>
      <el-tabs
        v-else
        class="form-renderer-nested-tabs"
      >
        <el-tab-pane
          v-for="(tab, tabIdx) in field.tabs"
          :key="`${field.key}-tab-${tabIdx}-${String(tab.name)}`"
          :label="tab.label"
          :name="tab.name"
        >
          <FormRendererFields
            :fields="tab.fields || []"
            in-column
          />
        </el-tab-pane>
      </el-tabs>
    </template>

    <!-- Nested el-collapse -->
    <template v-else-if="field.type === 'collapse' && field.collapsePanels?.length && ctx.isFieldVisible(field.key)">
      <el-col
        v-if="!inColumn"
        :span="24"
      >
        <el-collapse
          :model-value="collapseActiveNames(field)"
          class="form-renderer-collapse"
          @update:model-value="(names) => onCollapseActiveChange(field.key, names)"
        >
          <el-collapse-item
            v-for="(panel, panelIdx) in field.collapsePanels"
            :key="`${field.key}-collapse-${panelIdx}-${String(panel.name)}`"
            :title="panel.label"
            :name="panel.name"
          >
            <FormRendererFields :fields="panel.fields || []" />
          </el-collapse-item>
        </el-collapse>
      </el-col>
      <el-collapse
        v-else
        :model-value="collapseActiveNames(field)"
        class="form-renderer-collapse"
        @update:model-value="(names) => onCollapseActiveChange(field.key, names)"
      >
        <el-collapse-item
          v-for="(panel, panelIdx) in field.collapsePanels"
          :key="`${field.key}-collapse-${panelIdx}-${String(panel.name)}`"
          :title="panel.label"
          :name="panel.name"
        >
          <FormRendererFields
            :fields="panel.fields || []"
            in-column
          />
        </el-collapse-item>
      </el-collapse>
    </template>

    <!-- Display-only auxiliary widgets (Title, Divider, …) -->
    <template v-else-if="isDisplayOnlyLayoutField(field)">
      <el-col
        v-if="!inColumn"
        :span="field.span || 24"
        class="form-display-only-wrap"
      >
        <FieldRenderer
          :field="field"
          :model-value="ctx.formData[field.key]"
          :form-data="ctx.formData"
          :readonly="true"
        />
      </el-col>
      <div
        v-else
        class="form-display-only-wrap"
      >
        <FieldRenderer
          :field="field"
          :model-value="ctx.formData[field.key]"
          :form-data="ctx.formData"
          :readonly="true"
        />
      </div>
    </template>

    <!-- Record Note: comments + attachments panel (TABLE / RECORD scope) -->
    <template v-else-if="field.type === 'recordNote'">
      <el-col
        v-if="!inColumn"
        :span="24"
        style="padding: 0;"
      >
        <RecordNoteField
          :config="field._recordNote"
          :table-id="(ctx.primaryTableId ?? null) as number | null"
          :record-id="(ctx.processInstanceId ?? null) as string | null"
          :process-instance-id="(ctx.processInstanceId ?? null) as string | null"
          :function-unit-id="(ctx.functionUnitId ?? null) as string | null"
          :readonly="false"
        />
      </el-col>
      <RecordNoteField
        v-else
        :config="field._recordNote"
        :table-id="(ctx.primaryTableId ?? null) as number | null"
        :record-id="(ctx.processInstanceId ?? null) as string | null"
        :process-instance-id="(ctx.processInstanceId ?? null) as string | null"
        :function-unit-id="(ctx.functionUnitId ?? null) as string | null"
        :readonly="false"
      />
    </template>

    <!-- Sub-table — honor api.hidden / display (parity with lookup and other fields) -->
    <template v-else-if="field.type === 'subTable'">
      <el-col
        v-if="!inColumn && ctx.isFieldVisible(field.key)"
        :span="24"
        style="padding: 0;"
      >
        <SubTableField
          v-if="ctx.resolveBinding(field._bindingId)"
          :title="String(ctx.resolveBinding(field._bindingId)?.tableName ?? '')"
          :columns="(ctx.resolveBinding(field._bindingId)?.columns as any[]) || []"
          :dialog-columns="(ctx.resolveBinding(field._bindingId)?.dialogColumns as any[]) || undefined"
          :form-fields="ctx.resolveBinding(field._bindingId)?.formFields"
          :form-options="ctx.resolveBinding(field._bindingId)?.formOptions"
          :assignment-config="ctx.resolveBinding(field._bindingId)?.assignmentConfig"
          :model-value="(ctx.resolveBinding(field._bindingId)?.data as any[]) || []"
          :mi-participant-row-id="ctx.resolveMiParticipantSeedForSubTableAdd?.(field._bindingId).rowId ?? null"
          :mi-parent-participant-row="ctx.resolveMiParticipantSeedForSubTableAdd?.(field._bindingId).parentRow ?? null"
          :mi-parent-table-id="ctx.resolveMiParticipantSeedForSubTableAdd?.(field._bindingId).parentTableId ?? null"
          :editable="ctx.isSubTableEditable(field._bindingId)"
          :allow-add="field.allowAdd"
          :allow-edit="field.allowEdit"
          :allow-delete="field.allowDelete"
          :row-formulas="ctx.getSubFormRowFormulas(field._bindingId)"
          :summary-columns="ctx.getSummaryColumns(field._bindingId)"
          :summary-aggregations="ctx.getSummaryAggregations(field._bindingId)"
          :validation-config="ctx.getSubTableValidation(field._bindingId)"
          :upload-url="ctx.uploadUrl"
          :task-id="ctx.taskId"
          :assignee-field="ctx.subTableAssigneeField(field._bindingId)"
          :show-assign-button="ctx.showSubTableAssignColumn(field._bindingId)"
          :can-assign="!ctx.readonly && ctx.showSubTableAssignColumn(field._bindingId)"
          :enable-polling="ctx.enableSubTablePolling"
          :polling-interval="ctx.subTablePollingInterval"
          :linked-sub-table-bindings="ctx.linkableSubTableBindings"
          :suppress-link-form-initial-data="ctx.suppressLinkFormInitialData"
          :show-link-form-dialog-footer="ctx.showLinkFormDialogFooter"
          :show-task-status="ctx.subTableShowTaskStatus(field)"
          :compact-lookup-cells="ctx.subTableCompactLookupCells(field)"
          :primary-key-fields="ctx.resolveBinding(field._bindingId)?.primaryKeyFields as string[] | undefined"
          :field-definitions="ctx.resolveBinding(field._bindingId)?.fieldDefinitions as any"
          :binding-link-mode="ctx.resolveBinding(field._bindingId)?.bindingLinkMode"
          :binding-foreign-key-field="ctx.resolveBinding(field._bindingId)?.foreignKeyField"
          :table-id="ctx.resolveBinding(field._bindingId)?.tableId"
          :binding-id="field._bindingId"
          :binding-type="ctx.resolveBinding(field._bindingId)?.bindingType"
          :field-permissions="ctx.fieldPermissions"
          :function-unit-id="ctx.functionUnitId"
          :primary-form-data="ctx.primaryFormData"
          :sub-table-bindings-for-context="ctx.subTableBindingsForContext"
          :primary-table-display-name="ctx.primaryTableDisplayName"
          :primary-table-id="ctx.primaryTableId"
          :parent-tables-by-id="ctx.parentTablesById"
          style="margin-bottom: 16px;"
          @update:model-value="(rows: any[]) => ctx.handleSubTableUpdate(field._bindingId!, rows)"
          @update:primary-form-data="ctx.handlePrimaryFormDataPatch?.($event)"
          @update:linked-sub-table-data="ctx.handleSubTableUpdate"
          @view-detail="(row: any) => ctx.emitViewSubtaskDetail(row, ctx.resolveBinding(field._bindingId)?.data as any[])"
        />
      </el-col>
      <div
        v-else-if="inColumn && ctx.isFieldVisible(field.key)"
        class="form-col-subtable"
      >
        <SubTableField
          v-if="ctx.resolveBinding(field._bindingId)"
          :title="String(ctx.resolveBinding(field._bindingId)?.tableName ?? '')"
          :columns="(ctx.resolveBinding(field._bindingId)?.columns as any[]) || []"
          :dialog-columns="(ctx.resolveBinding(field._bindingId)?.dialogColumns as any[]) || undefined"
          :form-fields="ctx.resolveBinding(field._bindingId)?.formFields"
          :form-options="ctx.resolveBinding(field._bindingId)?.formOptions"
          :assignment-config="ctx.resolveBinding(field._bindingId)?.assignmentConfig"
          :model-value="(ctx.resolveBinding(field._bindingId)?.data as any[]) || []"
          :mi-participant-row-id="ctx.resolveMiParticipantSeedForSubTableAdd?.(field._bindingId).rowId ?? null"
          :mi-parent-participant-row="ctx.resolveMiParticipantSeedForSubTableAdd?.(field._bindingId).parentRow ?? null"
          :mi-parent-table-id="ctx.resolveMiParticipantSeedForSubTableAdd?.(field._bindingId).parentTableId ?? null"
          :editable="ctx.isSubTableEditable(field._bindingId)"
          :allow-add="field.allowAdd"
          :allow-edit="field.allowEdit"
          :allow-delete="field.allowDelete"
          :row-formulas="ctx.getSubFormRowFormulas(field._bindingId)"
          :summary-columns="ctx.getSummaryColumns(field._bindingId)"
          :summary-aggregations="ctx.getSummaryAggregations(field._bindingId)"
          :validation-config="ctx.getSubTableValidation(field._bindingId)"
          :upload-url="ctx.uploadUrl"
          :task-id="ctx.taskId"
          :assignee-field="ctx.subTableAssigneeField(field._bindingId)"
          :show-assign-button="ctx.showSubTableAssignColumn(field._bindingId)"
          :can-assign="!ctx.readonly && ctx.showSubTableAssignColumn(field._bindingId)"
          :enable-polling="ctx.enableSubTablePolling"
          :polling-interval="ctx.subTablePollingInterval"
          :linked-sub-table-bindings="ctx.linkableSubTableBindings"
          :suppress-link-form-initial-data="ctx.suppressLinkFormInitialData"
          :show-link-form-dialog-footer="ctx.showLinkFormDialogFooter"
          :show-task-status="ctx.subTableShowTaskStatus(field)"
          :compact-lookup-cells="ctx.subTableCompactLookupCells(field)"
          :primary-key-fields="ctx.resolveBinding(field._bindingId)?.primaryKeyFields as string[] | undefined"
          :field-definitions="ctx.resolveBinding(field._bindingId)?.fieldDefinitions as any"
          :binding-link-mode="ctx.resolveBinding(field._bindingId)?.bindingLinkMode"
          :binding-foreign-key-field="ctx.resolveBinding(field._bindingId)?.foreignKeyField"
          :table-id="ctx.resolveBinding(field._bindingId)?.tableId"
          :binding-id="field._bindingId"
          :binding-type="ctx.resolveBinding(field._bindingId)?.bindingType"
          :field-permissions="ctx.fieldPermissions"
          :function-unit-id="ctx.functionUnitId"
          :primary-form-data="ctx.primaryFormData"
          :sub-table-bindings-for-context="ctx.subTableBindingsForContext"
          :primary-table-display-name="ctx.primaryTableDisplayName"
          :primary-table-id="ctx.primaryTableId"
          :parent-tables-by-id="ctx.parentTablesById"
          style="margin-bottom: 16px;"
          @update:model-value="(rows: any[]) => ctx.handleSubTableUpdate(field._bindingId!, rows)"
          @update:primary-form-data="ctx.handlePrimaryFormDataPatch?.($event)"
          @update:linked-sub-table-data="ctx.handleSubTableUpdate"
          @view-detail="(row: any) => ctx.emitViewSubtaskDetail(row, ctx.resolveBinding(field._bindingId)?.data as any[])"
        />
      </div>
    </template>

    <!--
      Inline Form (`inlineSubForm`): the bound sub-table's designed form laid out right here —
      no grid above it, no dialog, no Save button of its own (rows persist with the host form).
      Both arms render it: the el-col one for top-level placement, the plain-div one for
      placement inside a Col layout container.
    -->
    <template v-else-if="field.type === 'inlineSubForm'">
      <el-col
        v-if="!inColumn && ctx.isFieldVisible(field.key)"
        :span="24"
        style="padding: 0;"
      >
        <SubTableInlineForm
          v-if="ctx.resolveBinding(field._bindingId)"
          :title="ctx.resolveInlineSubFormTitle(field)"
          :fields="ctx.resolveInlineSubFormFields(field)"
          :current-row="ctx.resolveInlineSubFormRow(field)"
          :primary-key-fields="ctx.resolveBinding(field._bindingId)?.primaryKeyFields"
          :readonly="ctx.inlineSubFormReadonly(field)"
          :label-width="ctx.labelWidth"
          :label-position="ctx.labelPosition"
          hide-save-button
          framed
          :bordered="false"
          :sub-table-bindings="ctx.subTableBindings as any[]"
          :linked-sub-table-bindings="ctx.linkableSubTableBindings as any[]"
          :host-table-id="(ctx.resolveBinding(field._bindingId)?.tableId ?? null) as number | null"
          :host-field-definitions="ctx.resolveBinding(field._bindingId)?.fieldDefinitions as any"
          :host-function-unit-id="ctx.functionUnitId as string | undefined"
          :host-task-id="ctx.taskId as string | undefined"
          :host-primary-form-data="ctx.primaryFormData as Record<string, unknown> | undefined"
          :host-primary-table-id="(ctx.primaryTableId ?? null) as number | null"
          :visited-inline-sub-form-binding-ids="new Set([Number(field._bindingId)])"
          :field-permissions="ctx.fieldPermissions"
          :form-options="ctx.resolveBinding(field._bindingId)?.formOptions"
          :dialog-columns="(ctx.resolveBinding(field._bindingId)?.dialogColumns as any[]) || undefined"
          :assignment-config="ctx.resolveBinding(field._bindingId)?.assignmentConfig"
          @update:row="(row: Record<string, any>) => ctx.handleInlineSubFormUpdate(field, row)"
          @update:sub-table-data="(bid: number, rows: any[]) => ctx.handleSubTableUpdate(bid, rows)"
        />
      </el-col>
      <div
        v-else-if="inColumn && ctx.isFieldVisible(field.key)"
        class="form-col-inline-sub-form"
      >
        <SubTableInlineForm
          v-if="ctx.resolveBinding(field._bindingId)"
          :title="ctx.resolveInlineSubFormTitle(field)"
          :fields="ctx.resolveInlineSubFormFields(field)"
          :current-row="ctx.resolveInlineSubFormRow(field)"
          :primary-key-fields="ctx.resolveBinding(field._bindingId)?.primaryKeyFields"
          :readonly="ctx.inlineSubFormReadonly(field)"
          :label-width="ctx.labelWidth"
          :label-position="ctx.labelPosition"
          hide-save-button
          framed
          :bordered="false"
          :sub-table-bindings="ctx.subTableBindings as any[]"
          :linked-sub-table-bindings="ctx.linkableSubTableBindings as any[]"
          :host-table-id="(ctx.resolveBinding(field._bindingId)?.tableId ?? null) as number | null"
          :host-field-definitions="ctx.resolveBinding(field._bindingId)?.fieldDefinitions as any"
          :host-function-unit-id="ctx.functionUnitId as string | undefined"
          :host-task-id="ctx.taskId as string | undefined"
          :host-primary-form-data="ctx.primaryFormData as Record<string, unknown> | undefined"
          :host-primary-table-id="(ctx.primaryTableId ?? null) as number | null"
          :visited-inline-sub-form-binding-ids="new Set([Number(field._bindingId)])"
          :field-permissions="ctx.fieldPermissions"
          :form-options="ctx.resolveBinding(field._bindingId)?.formOptions"
          :dialog-columns="(ctx.resolveBinding(field._bindingId)?.dialogColumns as any[]) || undefined"
          :assignment-config="ctx.resolveBinding(field._bindingId)?.assignmentConfig"
          @update:row="(row: Record<string, any>) => ctx.handleInlineSubFormUpdate(field, row)"
          @update:sub-table-data="(bid: number, rows: any[]) => ctx.handleSubTableUpdate(bid, rows)"
        />
      </div>
    </template>

    <!-- Lookup -->
    <template v-else-if="field.type === 'lookup'">
      <el-col
        v-if="!inColumn && ctx.isFieldVisible(field.key)"
        :span="field.span || 24"
      >
        <el-form-item
          :data-field-key="field.key"
          :prop="field.key"
          class="lookup-form-item"
        >
          <template #label>
            <span class="lookup-label-text">
              <el-icon class="lookup-label-icon"><Search /></el-icon>
              {{ ctx.fieldLabel(field) }}
            </span>
          </template>
          <div class="lookup-field-wrapper">
            <LookupField
              v-model="ctx.formData[field.key]"
              :table-id="(field as any)._lookupTableId"
              :search-fields="(field as any)._lookupSearchFields || []"
              :display-field="(field as any)._lookupDisplayField || ''"
              :display-fields="(field as any)._lookupDisplayFields || []"
              :selected-display-field="(field as any)._lookupSelectedDisplayField || ''"
              :filter-conditions="ctx.lookupFilterConditionsFor(field)"
              :auto-clear-if-missing="ctx.hasScriptLookupFilter(field.key)"
              :reload-nonce="ctx.lookupRefreshNonce[field.key] ?? 0"
              :lookup-config="(field as any)._lookupConfig"
              :view-fields="(field as any)._lookupViewFields || []"
              :placeholder="field.placeholder"
              :readonly="ctx.isFieldDisabled(field)"
              :multiple="(field as any)._lookupMultiple === true"
              @update:model-value="(val: unknown) => ctx.handleLookupModelUpdate?.(field.key, val)"
              @select="(row: any) => ctx.handleLookupSelect(field.key, row)"
              @clear="() => ctx.handleLookupClear(field.key)"
              @view-fields-loaded="(loaded: any[]) => { ctx.lookupLoadedViewFields[field.key] = loaded }"
            />
            <LookupViewDisplay
              v-if="ctx.lookupSelectedData[field.key] && ctx.lookupShowBackfillView(field)"
              :selected-data="ctx.lookupSelectedData[field.key]"
              :view-fields="(field as any)._lookupViewFields?.length ? (field as any)._lookupViewFields : (ctx.lookupLoadedViewFields[field.key] || [])"
            />
          </div>
        </el-form-item>
      </el-col>
      <div
        v-else-if="inColumn && ctx.isFieldVisible(field.key)"
        class="form-col-field"
      >
        <el-form-item
          :data-field-key="field.key"
          :prop="field.key"
          class="lookup-form-item"
        >
          <template #label>
            <span class="lookup-label-text">
              <el-icon class="lookup-label-icon"><Search /></el-icon>
              {{ ctx.fieldLabel(field) }}
            </span>
          </template>
          <div class="lookup-field-wrapper">
            <LookupField
              v-model="ctx.formData[field.key]"
              :table-id="(field as any)._lookupTableId"
              :search-fields="(field as any)._lookupSearchFields || []"
              :display-field="(field as any)._lookupDisplayField || ''"
              :display-fields="(field as any)._lookupDisplayFields || []"
              :selected-display-field="(field as any)._lookupSelectedDisplayField || ''"
              :filter-conditions="ctx.lookupFilterConditionsFor(field)"
              :auto-clear-if-missing="ctx.hasScriptLookupFilter(field.key)"
              :reload-nonce="ctx.lookupRefreshNonce[field.key] ?? 0"
              :lookup-config="(field as any)._lookupConfig"
              :view-fields="(field as any)._lookupViewFields || []"
              :placeholder="field.placeholder"
              :readonly="ctx.isFieldDisabled(field)"
              :multiple="(field as any)._lookupMultiple === true"
              @update:model-value="(val: unknown) => ctx.handleLookupModelUpdate?.(field.key, val)"
              @select="(row: any) => ctx.handleLookupSelect(field.key, row)"
              @clear="() => ctx.handleLookupClear(field.key)"
              @view-fields-loaded="(loaded: any[]) => { ctx.lookupLoadedViewFields[field.key] = loaded }"
            />
            <LookupViewDisplay
              v-if="ctx.lookupSelectedData[field.key] && ctx.lookupShowBackfillView(field)"
              :selected-data="ctx.lookupSelectedData[field.key]"
              :view-fields="(field as any)._lookupViewFields?.length ? (field as any)._lookupViewFields : (ctx.lookupLoadedViewFields[field.key] || [])"
            />
          </div>
        </el-form-item>
      </div>
    </template>

    <!-- Regular field -->
    <template v-else>
      <el-col
        v-if="!inColumn && ctx.isFieldVisible(field.key)"
        :span="field.span || 24"
      >
        <el-form-item
          :data-field-key="field.key"
          :label="ctx.fieldLabel(field)"
          :prop="field.key"
          :required="fieldShowsRequired(field)"
          :class="{ 'is-error': !!ctx.scriptFieldErrors[field.key] }"
        >
          <FieldRenderer
            :field="field"
            :model-value="ctx.formData[field.key]"
            :form-data="ctx.formData"
            :readonly="ctx.isFieldReadonly(field)"
            :disabled="ctx.isFieldDisabled(field)"
            :visible="ctx.isFieldVisible(field.key)"
            :options="ctx.fieldOptions(field)"
            :upload-url="ctx.uploadUrl"
            :user-search-results="ctx.userSearchResults.get(field.key)"
            @update:model-value="(val: any) => ctx.handleFieldChange(field.key, val)"
            @field-blur="() => ctx.handleFieldBlur(field.key)"
            @upload:success="(res: any, file: any, key: string) => ctx.handleUploadSuccess(res, file, key)"
            @upload:remove="(file: any, key: string) => ctx.handleUploadRemove(file, key)"
            @search:users="ctx.handleUserSearch"
          />
          <div
            v-if="ctx.scriptFieldErrors[field.key]"
            class="el-form-item__error script-field-error"
          >
            {{ ctx.scriptFieldErrors[field.key] }}
          </div>
        </el-form-item>
      </el-col>
      <div
        v-else-if="ctx.isFieldVisible(field.key)"
        class="form-col-field"
      >
        <el-form-item
          :data-field-key="field.key"
          :label="ctx.fieldLabel(field)"
          :prop="field.key"
          :required="fieldShowsRequired(field)"
          :class="{ 'is-error': !!ctx.scriptFieldErrors[field.key] }"
        >
          <FieldRenderer
            :field="field"
            :model-value="ctx.formData[field.key]"
            :form-data="ctx.formData"
            :readonly="ctx.isFieldReadonly(field)"
            :disabled="ctx.isFieldDisabled(field)"
            :visible="ctx.isFieldVisible(field.key)"
            :options="ctx.fieldOptions(field)"
            :upload-url="ctx.uploadUrl"
            :user-search-results="ctx.userSearchResults.get(field.key)"
            @update:model-value="(val: any) => ctx.handleFieldChange(field.key, val)"
            @field-blur="() => ctx.handleFieldBlur(field.key)"
            @upload:success="(res: any, file: any, key: string) => ctx.handleUploadSuccess(res, file, key)"
            @upload:remove="(file: any, key: string) => ctx.handleUploadRemove(file, key)"
            @search:users="ctx.handleUserSearch"
          />
          <div
            v-if="ctx.scriptFieldErrors[field.key]"
            class="el-form-item__error script-field-error"
          >
            {{ ctx.scriptFieldErrors[field.key] }}
          </div>
        </el-form-item>
      </div>
    </template>
  </template>
</template>

<style scoped lang="scss">
/* Adjacent designer cards (elCard) need vertical gap — parent FormRenderer scoped
   styles do not pierce this fragment root, so margin lives here. */
.form-layout-card {
  width: 100%;
  margin-bottom: 10px;
}

.form-layout-card--stacked {
  margin-bottom: 10px;
}

.form-col-field {
  width: 100%;
}

.form-row-in-column {
  width: 100%;
}

.form-col-subtable {
  width: 100%;
  margin-bottom: 16px;
}

.form-display-only-wrap {
  width: 100%;
  margin-bottom: 8px;
}

.form-renderer-nested-tabs {
  width: 100%;
  margin-bottom: 8px;
}

.form-renderer-collapse {
  width: 100%;
  margin-bottom: 18px;
}
</style>
