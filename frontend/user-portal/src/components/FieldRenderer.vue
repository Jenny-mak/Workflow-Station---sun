<template>
  <!-- 占满表单项内容区，否则 el-select 等 width:100% 会相对收缩父级计算，出现仅显示箭头 -->
  <div
    v-show="visible"
    class="field-renderer-root form-readonly-surface"
  >
    <!-- text / input (sensitive mask is display-only; model stays plaintext) -->
    <template v-if="field.type === 'text' || field.type === 'input'">
      <el-input
        :model-value="textDisplayValue"
        :placeholder="field.placeholder"
        :maxlength="field.maxLength"
        :show-word-limit="!!field.maxLength"
        :disabled="isDisabled"
        :readonly="textInputReadonly && !isDisabled"
        :clearable="!showTextMasked"
        @update:model-value="onMaskedInput"
        @focus="onMaskedFocus"
        @blur="onMaskedBlur"
      />
    </template>

    <!-- password -->
    <template v-else-if="field.type === 'password'">
      <el-input
        :model-value="modelValue"
        type="password"
        show-password
        :placeholder="field.placeholder"
        :disabled="isDisabled"
        clearable
        @update:model-value="onUpdate"
        @blur="onBlur"
      />
    </template>

    <!-- textarea -->
    <template v-else-if="field.type === 'textarea'">
      <el-input
        :model-value="modelValue"
        type="textarea"
        :rows="field.rows || 3"
        :placeholder="field.placeholder"
        :maxlength="field.maxLength"
        :show-word-limit="!!field.maxLength"
        :disabled="isDisabled"
        @update:model-value="onUpdate"
        @blur="onBlur"
      />
    </template>

    <!-- number (readonly non-numeric PK/FK strings e.g. Test-000017 cannot bind el-input-number) -->
    <template v-else-if="field.type === 'number' && showNumberAsText">
      <el-input
        :model-value="numberAsTextDisplay"
        disabled
      />
    </template>

    <!-- number -->
    <template v-else-if="field.type === 'number'">
      <el-input-number
        :model-value="modelValue"
        :min="field.min"
        :max="field.max"
        :step="field.step || 1"
        :precision="field.precision"
        :disabled="isDisabled"
        style="width: 100%"
        @update:model-value="onUpdate"
        @blur="onBlur"
      />
    </template>

    <!-- select -->
    <template v-else-if="field.type === 'select'">
      <el-select
        :model-value="modelValue"
        :placeholder="field.placeholder"
        :multiple="field.multiple"
        :filterable="field.filterable"
        :disabled="isDisabled"
        clearable
        style="width: 100%"
        :teleported="true"
        popper-class="form-renderer-popper"
        @update:model-value="onUpdate"
        @blur="onBlur"
      >
        <el-option
          v-for="opt in resolvedOptions"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
    </template>

    <!-- radio -->
    <template v-else-if="field.type === 'radio'">
      <el-radio-group
        :model-value="modelValue"
        :disabled="isDisabled"
        @update:model-value="onUpdate"
      >
        <el-radio
          v-for="opt in resolvedOptions"
          :key="opt.value"
          :label="opt.value"
        >
          {{ opt.label }}
        </el-radio>
      </el-radio-group>
    </template>

    <!-- checkbox -->
    <template v-else-if="field.type === 'checkbox'">
      <el-checkbox-group
        :model-value="modelValue"
        :disabled="isDisabled"
        @update:model-value="onUpdate"
      >
        <el-checkbox
          v-for="opt in resolvedOptions"
          :key="opt.value"
          :label="opt.value"
        >
          {{ opt.label }}
        </el-checkbox>
      </el-checkbox-group>
    </template>

    <!-- switch -->
    <template v-else-if="field.type === 'switch'">
      <el-switch
        :model-value="modelValue"
        :active-text="field.activeText"
        :inactive-text="field.inactiveText"
        :disabled="isDisabled"
        @update:model-value="onUpdate"
      />
    </template>

    <!-- date -->
    <template v-else-if="field.type === 'date'">
      <el-date-picker
        :model-value="modelValue"
        type="date"
        :placeholder="field.placeholder"
        :disabled="isDisabled"
        value-format="YYYY-MM-DD"
        style="width: 100%"
        :teleported="true"
        popper-class="form-renderer-popper"
        @update:model-value="onUpdate"
      />
    </template>

    <!-- datetime -->
    <template v-else-if="field.type === 'datetime'">
      <el-date-picker
        :model-value="modelValue"
        type="datetime"
        :placeholder="field.placeholder"
        :disabled="isDisabled"
        value-format="YYYY-MM-DD HH:mm:ss"
        style="width: 100%"
        :teleported="true"
        popper-class="form-renderer-popper"
        @update:model-value="onUpdate"
      />
    </template>

    <!-- daterange -->
    <template v-else-if="field.type === 'daterange'">
      <el-date-picker
        :model-value="modelValue"
        type="daterange"
        :range-separator="t('common.to')"
        :start-placeholder="t('common.startDate')"
        :end-placeholder="t('common.endDate')"
        :disabled="isDisabled"
        value-format="YYYY-MM-DD"
        style="width: 100%"
        :teleported="true"
        popper-class="form-renderer-popper"
        @update:model-value="onUpdate"
      />
    </template>

    <!-- time -->
    <template v-else-if="field.type === 'time'">
      <el-time-picker
        :model-value="modelValue"
        :placeholder="field.placeholder"
        :disabled="isDisabled"
        value-format="HH:mm:ss"
        style="width: 100%"
        :teleported="true"
        popper-class="form-renderer-popper"
        @update:model-value="onUpdate"
      />
    </template>

    <!-- timerange -->
    <template v-else-if="field.type === 'timerange'">
      <el-time-picker
        :model-value="modelValue"
        is-range
        value-format="HH:mm:ss"
        :start-placeholder="(field as any).startPlaceholder || t('common.startDate')"
        :end-placeholder="(field as any).endPlaceholder || t('common.endDate')"
        :disabled="isDisabled"
        style="width: 100%"
        :teleported="true"
        popper-class="form-renderer-popper"
        @update:model-value="onUpdate"
      />
    </template>

    <!-- cascader -->
    <template v-else-if="field.type === 'cascader'">
      <el-cascader
        :model-value="modelValue"
        :options="field.options"
        :props="field.cascaderProps"
        :placeholder="field.placeholder"
        :disabled="isDisabled"
        clearable
        style="width: 100%"
        :teleported="true"
        popper-class="form-renderer-popper"
        @update:model-value="onUpdate"
        @blur="onBlur"
      />
    </template>

    <!-- user -->
    <template v-else-if="field.type === 'user'">
      <el-select
        :model-value="modelValue"
        :placeholder="field.placeholder"
        :multiple="field.multiple"
        :disabled="isDisabled"
        filterable
        remote
        :remote-method="(query: string) => searchUsers(query, field)"
        clearable
        style="width: 100%"
        :teleported="true"
        popper-class="form-renderer-popper"
        @update:model-value="onUpdate"
        @blur="onBlur"
      >
        <el-option
          v-for="user in (userSearchResults || field.userOptions || [])"
          :key="user.id"
          :label="user.name"
          :value="user.id"
        />
      </el-select>
    </template>

    <!-- businessUnit -->
    <template v-else-if="field.type === 'businessUnit'">
      <el-tree-select
        :model-value="modelValue"
        :data="field.buOptions || []"
        :props="({ label: 'name', value: 'id', children: 'children' } as any)"
        :placeholder="field.placeholder"
        :disabled="isDisabled"
        check-strictly
        clearable
        style="width: 100%"
        :teleported="true"
        popper-class="form-renderer-popper"
        @update:model-value="onUpdate"
        @blur="onBlur"
      />
    </template>

    <!-- treeselect -->
    <template v-else-if="field.type === 'treeselect'">
      <el-tree-select
        :model-value="modelValue"
        :data="(field as any).treeData || []"
        :multiple="field.multiple"
        :check-strictly="(field as any).checkStrictly !== false"
        :placeholder="field.placeholder"
        :disabled="isDisabled"
        clearable
        style="width: 100%"
        :teleported="true"
        popper-class="form-renderer-popper"
        @update:model-value="onUpdate"
        @blur="onBlur"
      />
    </template>

    <!-- money -->
    <template v-else-if="field.type === 'money'">
      <el-input
        :model-value="modelValue"
        :placeholder="field.placeholder"
        :disabled="isDisabled"
        clearable
        @update:model-value="onUpdate"
        @blur="onBlur"
      >
        <template #prepend>
          {{ field.currency || '¥' }}
        </template>
      </el-input>
    </template>

    <!-- rate -->
    <template v-else-if="field.type === 'rate'">
      <el-rate
        :model-value="modelValue"
        :max="field.max || 5"
        :disabled="isDisabled"
        @update:model-value="onUpdate"
      />
    </template>

    <!-- slider -->
    <template v-else-if="field.type === 'slider'">
      <el-slider
        :model-value="modelValue"
        :min="field.min || 0"
        :max="field.max || 100"
        :step="field.step || 1"
        :disabled="isDisabled"
        style="width: 100%"
        @update:model-value="onUpdate"
      />
    </template>

    <!-- colorPicker -->
    <template v-else-if="field.type === 'colorPicker'">
      <span
        v-if="readonly && modelValue"
        class="color-swatch"
        :style="{ backgroundColor: modelValue }"
        :title="modelValue"
      />
      <span v-else-if="readonly">-</span>
      <el-color-picker
        v-else
        :model-value="modelValue"
        :disabled="isDisabled"
        :teleported="true"
        popper-class="form-renderer-popper"
        @update:model-value="onUpdate"
      />
    </template>

    <!-- editor (Task 6.2) -->
    <template v-else-if="field.type === 'editor'">
      <div
        v-if="readonly && modelValue"
        class="editor-readonly"
        v-html="sanitize(modelValue)"
      />
      <span v-else-if="readonly">-</span>
      <div
        v-else
        class="editor-wrapper"
      >
        <Toolbar
          :editor="editorInstance"
          :default-config="editorToolbarConfig"
          mode="default"
          style="border-bottom: 1px solid #ccc"
        />
        <Editor
          :model-value="modelValue || ''"
          :default-config="editorConfig"
          mode="default"
          style="height: 300px; overflow-y: hidden"
          @on-created="onEditorCreated"
          @on-change="onEditorChange"
        />
      </div>
    </template>

    <!-- signature (Task 6.3) -->
    <template v-else-if="field.type === 'signature'">
      <img
        v-if="readonly && modelValue"
        :src="modelValue"
        class="signature-preview"
        :alt="t('fieldRenderer.signature')"
      >
      <span v-else-if="readonly">-</span>
      <div
        v-else
        class="signature-pad"
      >
        <canvas
          ref="signatureCanvasRef"
          class="signature-canvas"
          @mousedown="onSigDown"
          @mousemove="onSigMove"
          @mouseup="onSigUp"
          @mouseleave="onSigUp"
          @touchstart.prevent="onTouchStart"
          @touchmove.prevent="onTouchMove"
          @touchend.prevent="onSigUp"
        />
        <div class="signature-actions">
          <el-button
            size="small"
            :disabled="disabled || signatureHistory.length === 0"
            @click="undoSignature"
          >
            {{ t('fieldRenderer.undo') }}
          </el-button>
          <el-button
            size="small"
            :disabled="disabled"
            @click="clearSignature"
          >
            {{ t('fieldRenderer.clear') }}
          </el-button>
        </div>
      </div>
    </template>

    <!-- transfer -->
    <template v-else-if="field.type === 'transfer'">
      <span v-if="readonly">
        {{ Array.isArray(modelValue) ? modelValue.join(', ') : (modelValue || '-') }}
      </span>
      <el-transfer
        v-else
        :model-value="modelValue"
        :data="(field.options || []).map((o: any) => ({ key: o.value, label: o.label }))"
        :disabled="isDisabled"
        filterable
        @update:model-value="onUpdate"
      />
    </template>

    <!-- Advanced Upload (Extend) -->
    <template v-else-if="isUploadControl && isAdvancedUploadControl">
      <div class="upload-field-wrap">
        <FormUploadDropZone
          :action="resolvedUploadUrl"
          :accept="field.uploadAccept || ''"
          :limit="uploadLimit"
          :multiple="uploadMultiple"
          :disabled="readonly || isDisabled"
          :file-list="fileList"
          :http-request="httpRequest"
          :max-file-size-mb="uploadMaxFileSizeMb"
          :drag-text="t('upload.dragText')"
          :click-text="t('upload.clickText')"
          :tip="t('upload.tip', { types: field.uploadAccept || 'jpg/png/pdf/docx/xlsx', size: uploadMaxFileSizeMb })"
          :fail-label="t('upload.failed')"
          :remove-label="t('common.delete')"
          :handle-success="onUploadSuccess"
          :handle-change="onUploadChange"
          :handle-remove="onUploadRemove"
          :handle-exceed="onUploadExceed"
          :handle-error="onUploadError"
          :handle-size-exceed="onSizeExceed"
          :handle-open-details="openDetails"
        />
        <FormUploadDetailsDrawer
          v-model="detailsOpen"
          :title="t('upload.fileDetails')"
          :file="detailsFile"
          :readonly="readonly || isDisabled"
          :labels="uploadDetailLabels"
          :preview-file="previewCurrentFile"
        />
      </div>
    </template>

    <!-- Basic native Upload (form-create stock) -->
    <template v-else-if="isUploadControl">
      <el-upload
        :action="resolvedUploadUrl"
        :accept="field.uploadAccept || ''"
        :limit="field.uploadLimit"
        :multiple="(field.uploadLimit ?? 1) > 1"
        :disabled="readonly || isDisabled"
        :file-list="fileList"
        :http-request="httpRequest"
        :auto-upload="true"
        :on-success="onUploadSuccess"
        :on-change="onUploadChange"
        :on-remove="onUploadRemove"
        :on-exceed="onUploadExceed"
        :on-error="onUploadError"
        :on-preview="previewCurrentFile"
      >
        <el-button
          type="primary"
          :disabled="readonly || isDisabled"
        >
          {{ t('upload.clickText') }}
        </el-button>
      </el-upload>
    </template>

    <!-- readonly -->
    <template v-else-if="field.type === 'readonly'">
      <span class="readonly-text">{{ modelValue || '-' }}</span>
    </template>

    <!-- divider -->
    <template v-else-if="field.type === 'divider'">
      <el-divider />
    </template>

    <!-- title (fcTitle) -->
    <template v-else-if="field.type === 'title'">
      <div
        class="form-layout-title"
        :class="`form-layout-title--${field.titleSize || 'default'}`"
      >
        {{ field.label }}
      </div>
    </template>

    <!-- static text -->
    <template v-else-if="field.type === 'staticText'">
      <div class="form-layout-static-text">
        {{ field.label }}
      </div>
    </template>

    <!-- html block -->
    <template v-else-if="field.type === 'html'">
      <div
        class="form-layout-html"
        v-html="sanitize(String(field.htmlContent || ''))"
      />
    </template>

    <!-- tag -->
    <template v-else-if="field.type === 'tag'">
      <el-tag type="info">
        {{ field.label }}
      </el-tag>
    </template>

    <!-- button (read-only display) -->
    <template v-else-if="field.type === 'button'">
      <el-button disabled>
        {{ field.label }}
      </el-button>
    </template>

    <!-- vertical spacer -->
    <template v-else-if="field.type === 'space'">
      <div
        class="form-layout-space"
        :style="{ height: `${field.step ?? 16}px` }"
      />
    </template>

    <!-- image -->
    <template v-else-if="field.type === 'image'">
      <el-image
        v-if="field.defaultValue"
        :src="String(field.defaultValue)"
        fit="contain"
        style="max-width: 100%; max-height: 240px;"
      />
      <span
        v-else
        class="readonly-text"
      >-</span>
    </template>

    <!-- alert -->
    <template v-else-if="field.type === 'alert'">
      <el-alert
        :title="field.alertTitle"
        :type="field.alertType || 'info'"
        :closable="false"
        show-icon
      />
    </template>

    <!-- department (Task 6.4) -->
    <template v-else-if="field.type === 'department'">
      <span
        v-if="readonly"
        class="readonly-text"
      >
        {{ departmentDisplayName || modelValue || '-' }}
      </span>
      <el-tree-select
        v-else
        :model-value="modelValue"
        :data="departmentTreeData"
        :props="({ label: 'name', value: 'id', children: 'children' } as any)"
        :placeholder="field.placeholder || t('fieldRenderer.selectDepartment')"
        :disabled="isDisabled"
        :loading="departmentLoading"
        check-strictly
        clearable
        filterable
        style="width: 100%"
        :teleported="true"
        popper-class="form-renderer-popper"
        @update:model-value="onUpdate"
      />
    </template>

    <!--
      lookup — match FormRenderer's top-level branch so PortalFormFields / SubTableInlineForm
      / SubTaskForm don't fall through to the default <el-input>, which stringifies the
      assignee object as "[object Object]". Backfill view (LookupViewDisplay) is rendered
      below the field when `lookupConfig.showBackfillView !== false` (carried via
      `_lookupShowBackfillView` from extractFieldsRecursive); falls back to runtime-loaded
      view fields when designer did not configure them.
    -->
    <template v-else-if="field.type === 'lookup'">
      <div class="lookup-field-wrapper">
        <LookupField
          :model-value="modelValue"
          :table-id="Number((field as any)._lookupTableId || 0)"
          :search-fields="(field as any)._lookupSearchFields || []"
          :display-field="(field as any)._lookupDisplayField || ''"
          :display-fields="(field as any)._lookupDisplayFields || []"
          :selected-display-field="(field as any)._lookupSelectedDisplayField || ''"
          :filter-conditions="effectiveLookupFilterConditions"
          :lookup-config="(field as any)._lookupConfig"
          :view-fields="(field as any)._lookupViewFields || []"
          :placeholder="field.placeholder"
          :readonly="isDisabled"
          :multiple="(field as any)._lookupMultiple === true"
          @update:model-value="onUpdate"
          @select="(row: Record<string, any>) => handleLookupSelect(row)"
          @clear="handleLookupClear"
          @view-fields-loaded="(vfs: any[]) => onLookupViewFieldsLoaded(vfs)"
        />
        <LookupViewDisplay
          v-if="lookupShowBackfillView && lookupSelectedRow"
          :selected-data="lookupSelectedRow"
          :view-fields="effectiveLookupViewFields"
        />
      </div>
    </template>

    <!-- Owner field: readonly Lookup chrome; value is auto-filled from Creator or Current Assignee. -->
    <template v-else-if="field.type === 'owner'">
      <OwnerField
        :model-value="modelValue"
        :owner-config="(field as any)._ownerConfig"
        :display="ownerDisplayValue"
        readonly
      />
    </template>

    <!-- RecordNote is rendered by FormRendererFields (main form only); skip in nested contexts -->
    <template v-else-if="field.type === 'recordNote'" />

    <!-- default fallback -->
    <template v-else>
      <el-input
        :model-value="modelValue"
        :placeholder="field.placeholder"
        :disabled="isDisabled"
        clearable
        @update:model-value="onUpdate"
      />
    </template>
  </div>
</template>

<script setup lang="ts">
// ---------------------------------------------------------------------------
// FieldRenderer — orchestrator. Single field renderer (render hot-path).
// Behaviour is unchanged: logic lives in src/composables/fieldRenderer/*,
// invoked here in the original registration order so watcher / lifecycle-hook
// ordering is preserved (lookup watch → upload watch; editor onBeforeUnmount →
// signature onBeforeUnmount; combined onMounted for signature + department).
// ---------------------------------------------------------------------------
import { computed, inject, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import FormUploadDropZone from '@platform-shared/upload/FormUploadDropZone.vue'
import FormUploadDetailsDrawer from '@platform-shared/upload/FormUploadDetailsDrawer.vue'
import '@wangeditor/editor/dist/css/style.css'
import type { FormField } from './formRendererHelpers'
import LookupField from './lookup/LookupField.vue'
import LookupViewDisplay from './lookup/LookupViewDisplay.vue'
import OwnerField from './owner/OwnerField.vue'
import { useFieldCore } from '@/composables/fieldRenderer/useFieldCore'
import { useFieldSanitize } from '@/composables/fieldRenderer/useFieldSanitize'
import { useFieldLookup } from '@/composables/fieldRenderer/useFieldLookup'
import { useFieldUpload } from '@/composables/fieldRenderer/useFieldUpload'
import { useFieldEditor } from '@/composables/fieldRenderer/useFieldEditor'
import { useFieldSignature } from '@/composables/fieldRenderer/useFieldSignature'
import { useFieldDepartment } from '@/composables/fieldRenderer/useFieldDepartment'
import { useFieldSensitiveMask } from '@/composables/fieldRenderer/useFieldSensitiveMask'
import { FORM_RENDERER_FIELDS_CTX } from './formRendererFieldsContext'
import { INLINE_LOOKUP_CASCADE_CTX } from '@/composables/formRenderer/inlineFormLookupCascadeContext'
import { isAdvancedUploadType, isAnyUploadType } from '@platform-shared/upload/uploadRuleType'

// ---------------------------------------------------------------------------
// i18n
// ---------------------------------------------------------------------------
const { t } = useI18n()

// ---------------------------------------------------------------------------
// Props & Emits
// ---------------------------------------------------------------------------
interface Props {
  field: FormField
  modelValue: any
  formData?: Record<string, any>
  readonly?: boolean
  disabled?: boolean
  visible?: boolean
  options?: Array<{ label: string; value: any }>
  uploadUrl?: string
  userSearchResults?: Array<{ id: string; name: string }>
}

const props = withDefaults(defineProps<Props>(), {
  readonly: false,
  disabled: false,
  visible: true,
})

const isUploadControl = computed(() => isAnyUploadType(props.field.type))
const isAdvancedUploadControl = computed(() =>
  props.field.advancedUpload === true || isAdvancedUploadType(props.field.type),
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: any): void
  (e: 'field-blur', fieldKey: string): void
  (e: 'upload:success', response: any, file: any, fieldKey: string): void
  (e: 'upload:remove', file: any, fieldKey: string): void
  (e: 'search:users', query: string, fieldKey: string): void
}>()

// ---------------------------------------------------------------------------
// Composables — invoked in original registration order (see header note).
// ---------------------------------------------------------------------------
// Core bindings: disabled state, number-as-text fallback, options, emitters.
const {
  isDisabled,
  showNumberAsText,
  numberAsTextDisplay,
  onUpdate,
  onBlur,
  resolvedOptions,
  searchUsers,
} = useFieldCore(props, emit)

const {
  displayValue: textDisplayValue,
  inputReadonly: textInputReadonly,
  showMasked: showTextMasked,
  onMaskedInput,
  onMaskedFocus,
  onMaskedBlur,
} = useFieldSensitiveMask(
  () => props.field,
  () => props.modelValue,
  () => isDisabled.value,
  onUpdate,
  onBlur,
)

// XSS sanitization (Task 6.5)
const { sanitize } = useFieldSanitize()

// Lookup backfill state — registers the modelValue/type watch first.
const {
  lookupSelectedRow,
  lookupShowBackfillView,
  effectiveLookupViewFields,
  onLookupSelect,
  onLookupClear,
  onLookupViewFieldsLoaded,
} = useFieldLookup(props)

const formRendererCtx = inject(FORM_RENDERER_FIELDS_CTX, null)
const inlineLookupCtx = inject(INLINE_LOOKUP_CASCADE_CTX, null)

// Owner display: the backend-written "<field>__display" companion (docs/design/
// owner-field-component.md §4.3), used to label the stored value without a lookup.
const ownerDisplayValue = computed(() => {
  const display = props.formData?.[`${props.field.key}__display`]
  return typeof display === 'string' ? display : ''
})

const effectiveLookupFilterConditions = computed(() => {
  if (formRendererCtx?.lookupFilterConditionsFor) {
    return formRendererCtx.lookupFilterConditionsFor(props.field)
  }
  if (inlineLookupCtx?.lookupFilterConditionsFor) {
    return inlineLookupCtx.lookupFilterConditionsFor(props.field)
  }
  return (props.field as { _lookupFilterConditions?: unknown[] })._lookupFilterConditions || []
})

function handleLookupSelect(row: Record<string, unknown>) {
  onLookupSelect(row as Record<string, any>)
  if (formRendererCtx?.handleLookupSelect) {
    void formRendererCtx.handleLookupSelect(props.field.key, row)
    return
  }
  if (inlineLookupCtx?.handleLookupSelect) {
    void inlineLookupCtx.handleLookupSelect(props.field.key, row)
  }
}

function handleLookupClear() {
  onLookupClear()
  if (formRendererCtx?.handleLookupClear) {
    formRendererCtx.handleLookupClear(props.field.key)
    return
  }
  if (inlineLookupCtx?.handleLookupClear) {
    inlineLookupCtx.handleLookupClear(props.field.key)
  }
}

// Upload URL + file list — registers the modelValue watch second.
const {
  resolvedUploadUrl,
  uploadLimit,
  uploadMaxFileSizeMb,
  uploadMultiple,
  fileList,
  httpRequest,
  onUploadSuccess,
  onUploadChange,
  onUploadRemove,
  onUploadExceed,
  onUploadError,
  onSizeExceed,
  previewCurrentFile,
  detailsOpen,
  detailsFile,
  openDetails,
} = useFieldUpload(props, emit)

const uploadDetailLabels = computed(() => ({
  description: t('upload.fileDescription'),
  callbackUrl: t('upload.callbackUrl'),
  status: t('upload.autoSendToFileNet'),
  completed: t('upload.statusCompleted'),
  saveFailed: t('upload.descriptionSaveFailed'),
}))

// Editor — registers onBeforeUnmount first (matches original order).
const {
  editorInstance,
  editorToolbarConfig,
  editorConfig,
  onEditorCreated,
  onEditorChange,
} = useFieldEditor(props, emit)

// Signature canvas — registers onBeforeUnmount second; setup called in onMounted.
const {
  signatureCanvasRef,
  signatureHistory,
  onSigDown,
  onSigMove,
  onSigUp,
  onTouchStart,
  onTouchMove,
  undoSignature,
  clearSignature,
  setupSignatureCanvas,
} = useFieldSignature(props, emit)

// Department tree-select — fetch triggered in onMounted.
const {
  departmentTreeData,
  departmentLoading,
  departmentDisplayName,
  fetchDepartmentTree,
} = useFieldDepartment(props)

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------
onMounted(() => {
  // Signature canvas setup
  setupSignatureCanvas()

  // Department data fetch
  if (props.field.type === 'department') {
    fetchDepartmentTree()
  }
})
</script>

<style scoped lang="scss">
@use '@/styles/form-readonly.scss';

.field-renderer-root {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
}

.color-swatch {
  display: inline-block;
  width: 20px;
  height: 20px;
  border-radius: 3px;
  border: 1px solid #dcdfe6;
  vertical-align: middle;
}

.editor-readonly {
  padding: 8px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #f5f7fa;
  min-height: 40px;
  line-height: 1.5;
  word-break: break-word;
  width: 100%;
}

.editor-wrapper {
  border: 1px solid #ccc;
  border-radius: 4px;
  overflow: hidden;
  width: 100%;
}

.signature-preview {
  max-width: 200px;
  max-height: 80px;
  object-fit: contain;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
}

.signature-pad {
  width: 100%;
}

.signature-canvas {
  display: block;
  width: 100%;
  height: 120px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  cursor: crosshair;
  background: #fff;
}

.signature-actions {
  margin-top: 4px;
}

.readonly-text {
  color: #606266;
  line-height: 32px;
}

.file-preview-link {
  color: #165DFF;
  text-decoration: underline;
  cursor: pointer;
}

.upload-readonly-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.file-preview-link:hover {
  color: #0e44cc;
}

.form-layout-title {
  font-weight: 600;
  color: #303133;
  margin: 4px 0 12px;
  line-height: 1.4;
}

.form-layout-title--h1 {
  font-size: 20px;
}

.form-layout-title--h2,
.form-layout-title--default {
  font-size: 16px;
}

.form-layout-title--h3,
.form-layout-title--h4 {
  font-size: 14px;
}

.form-layout-static-text {
  color: #606266;
  line-height: 1.5;
  margin-bottom: 8px;
}

.form-layout-html {
  line-height: 1.5;
  margin-bottom: 8px;
}

.form-layout-space {
  width: 100%;
}

.lookup-field-wrapper {
  width: 100%;
  min-width: 0;
}
</style>
