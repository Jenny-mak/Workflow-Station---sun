<template>
  <SubTableNestedModalShell
    :visible="visibleModel"
    :title="title || (mode === 'edit' ? 'Edit Record' : 'Add Record')"
    width="min(600px, calc(100vw - 48px))"
    @update:visible="visibleModel = $event"
    @closed="onShellClosed"
  >
    <el-form
      ref="formRef"
      class="form-readonly-surface"
      :model="formData"
      :rules="formRules"
      label-width="auto"
      label-position="left"
      :validate-on-rule-change="false"
    >
      <template
        v-for="col in columns"
        :key="col.field"
      >
      <el-form-item
        v-if="isDialogFieldVisible(col.field)"
        :label="col.label"
        :prop="col.field"
      >
        <!-- text -->
        <el-input
          v-if="!col.type || col.type === 'text'"
          v-model="formData[col.field]"
          :placeholder="col.placeholder || col.label"
          :maxlength="col.props?.maxlength"
          :disabled="isColReadonly(col)"
          :clearable="!isColReadonly(col)"
          @change="() => onDialogFieldChange(col.field)"
          @blur="() => onDialogFieldBlur(col.field)"
        />

        <!-- textarea -->
        <el-input
          v-else-if="col.type === 'textarea'"
          v-model="formData[col.field]"
          type="textarea"
          :rows="col.props?.rows || 3"
          :placeholder="col.placeholder || col.label"
          :maxlength="col.props?.maxlength"
          :disabled="isColReadonly(col)"
          @change="() => onDialogFieldChange(col.field)"
          @blur="() => onDialogFieldBlur(col.field)"
        />

        <!-- number -->
        <el-input-number
          v-else-if="col.type === 'number'"
          v-model="formData[col.field]"
          :precision="col.props?.precision"
          :min="col.props?.min"
          :max="col.props?.max"
          :placeholder="col.placeholder || col.label"
          :disabled="isColReadonly(col)"
          style="width: 100%"
          @change="(v: number | undefined) => onDialogFieldChange(col.field, v)"
        />

        <!-- select -->
        <el-select
          v-else-if="col.type === 'select'"
          v-model="formData[col.field]"
          :placeholder="col.placeholder || col.label"
          :multiple="col.props?.multiple"
          :clearable="!isColReadonly(col)"
          :disabled="isColReadonly(col)"
          style="width: 100%"
          @change="(v: unknown) => onDialogFieldChange(col.field, v)"
        >
          <el-option
            v-for="opt in (col.props?.options ?? col.options ?? [])"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>

        <!-- radio -->
        <el-radio-group
          v-else-if="col.type === 'radio'"
          v-model="formData[col.field]"
          :disabled="isColReadonly(col)"
          @change="(v: unknown) => onDialogFieldChange(col.field, v)"
        >
          <el-radio
            v-for="opt in (col.props?.options ?? col.options ?? [])"
            :key="opt.value"
            :value="opt.value"
          >
            {{ opt.label }}
          </el-radio>
        </el-radio-group>

        <!-- checkbox -->
        <el-checkbox-group
          v-else-if="col.type === 'checkbox'"
          v-model="formData[col.field]"
          :disabled="isColReadonly(col)"
          @change="(v: unknown) => onDialogFieldChange(col.field, v)"
        >
          <el-checkbox
            v-for="opt in (col.props?.options ?? col.options ?? [])"
            :key="opt.value"
            :value="opt.value"
          >
            {{ opt.label }}
          </el-checkbox>
        </el-checkbox-group>

        <!-- password -->
        <el-input
          v-else-if="col.type === 'password'"
          v-model="formData[col.field]"
          type="password"
          show-password
          :placeholder="col.placeholder || col.label"
          :disabled="isColReadonly(col)"
          :clearable="!isColReadonly(col)"
          @change="() => onDialogFieldChange(col.field)"
          @blur="() => onDialogFieldBlur(col.field)"
        />

        <!-- timerange -->
        <el-time-picker
          v-else-if="col.type === 'timerange'"
          v-model="formData[col.field]"
          is-range
          value-format="HH:mm:ss"
          :start-placeholder="col.props?.startPlaceholder || 'Start time'"
          :end-placeholder="col.props?.endPlaceholder || 'End time'"
          :disabled="isColReadonly(col)"
          style="width: 100%"
          @change="(v: unknown) => onDialogFieldChange(col.field, v)"
        />

        <!-- treeselect -->
        <el-tree-select
          v-else-if="col.type === 'treeselect'"
          v-model="formData[col.field]"
          :data="col.props?.treeData || []"
          :multiple="col.props?.multiple"
          :check-strictly="col.props?.checkStrictly !== false"
          :placeholder="col.placeholder || col.label"
          :clearable="!isColReadonly(col)"
          :disabled="isColReadonly(col)"
          style="width: 100%"
          @change="(v: unknown) => onDialogFieldChange(col.field, v)"
        />

        <!-- switch -->
        <el-switch
          v-else-if="col.type === 'switch'"
          v-model="formData[col.field]"
          :disabled="isColReadonly(col)"
          @change="(v: unknown) => onDialogFieldChange(col.field, v)"
        />

        <!-- date -->
        <el-date-picker
          v-else-if="col.type === 'date'"
          v-model="formData[col.field]"
          type="date"
          value-format="YYYY-MM-DD"
          :placeholder="col.placeholder || col.label"
          :disabled="isColReadonly(col)"
          style="width: 100%"
          @change="(v: unknown) => onDialogFieldChange(col.field, v)"
        />

        <!-- datetime -->
        <el-date-picker
          v-else-if="col.type === 'datetime'"
          v-model="formData[col.field]"
          type="datetime"
          value-format="YYYY-MM-DD HH:mm:ss"
          :placeholder="col.placeholder || col.label"
          :disabled="isColReadonly(col)"
          style="width: 100%"
          @change="(v: unknown) => onDialogFieldChange(col.field, v)"
        />

        <!-- upload -->
        <div
          v-else-if="col.type === 'upload' || col.type === 'advancedUpload'"
          style="display: flex; flex-direction: column; gap: 4px;"
        >
          <FormUploadDropZone
            compact
            :action="col.props?.action && col.props.action !== '/' ? col.props.action : '/api/v1/upload'"
            :accept="col.props?.accept || ''"
            :limit="maxFilesOf(col)"
            :multiple="maxFilesOf(col) > 1"
            :file-list="uploadFileLists[col.field] || []"
            :http-request="httpRequest"
            :max-file-size-mb="maxFileSizeMbOf(col)"
            :disabled="isColReadonly(col)"
            :drag-text="t('form.uploadDragText')"
            :click-text="t('form.uploadClickText')"
            :fail-label="t('form.uploadFailed')"
            :remove-label="t('common.delete')"
            :success-status-label="t('form.uploadStatusUploaded')"
            :uploading-status-label="t('form.uploadStatusUploading')"
            :handle-success="(res: unknown, file: { name?: string; url?: string }, list: Array<{ url?: string; name?: string; status?: string; response?: unknown }>) => handleUploadSuccess(res, file, col, list)"
            :handle-change="(_file: unknown, list: Array<{ url?: string; name?: string; status?: string; response?: unknown }>) => handleUploadChange(col, list)"
            :handle-remove="(_file: unknown, list: Array<{ url?: string; name?: string; status?: string; response?: unknown }>) => handleUploadRemove(col, list)"
            :handle-exceed="() => handleUploadExceed(col)"
            :handle-error="(error: unknown) => handleUploadError(col, error)"
            :handle-size-exceed="() => handleSizeExceed(col)"
            :handle-duplicate="(name: string) => handleDuplicate(col, name)"
            :handle-open-details="(file) => openDetails(col, file)"
          />
        </div>

        <!-- tree (el-tree with checkbox, uses id/label node format) -->
        <el-tree
          v-else-if="col.type === 'tree'"
          :data="col.props?.treeData || []"
          :props="col.props?.labelProps || { label: 'label', children: 'children' }"
          :node-key="col.props?.nodeKey || 'id'"
          :show-checkbox="col.props?.showCheckbox !== false"
          @check="(_node: any, state: any) => {
            formData[col.field] = state.checkedKeys
            onDialogFieldChange(col.field, state.checkedKeys)
          }"
        />

        <!-- colorPicker -->
        <el-color-picker
          v-else-if="col.type === 'colorPicker'"
          v-model="formData[col.field]"
          :show-alpha="col.props?.showAlpha || false"
          :disabled="isColReadonly(col)"
          popper-class="sub-table-color-popper"
          @change="(v: unknown) => onDialogFieldChange(col.field, v)"
        />

        <!-- rate -->
        <el-rate
          v-else-if="col.type === 'rate'"
          v-model="formData[col.field]"
          :max="col.props?.max || 5"
          :allow-half="col.props?.allowHalf || false"
          :disabled="isColReadonly(col)"
          @change="(v: unknown) => onDialogFieldChange(col.field, v)"
        />

        <!-- slider -->
        <el-slider
          v-else-if="col.type === 'slider'"
          v-model="formData[col.field]"
          :min="col.props?.min ?? 0"
          :max="col.props?.max ?? 100"
          :step="col.props?.step || 1"
          :disabled="isColReadonly(col)"
          style="width: 100%"
          @change="(v: unknown) => onDialogFieldChange(col.field, v)"
        />

        <!-- editor (rich text) -->
        <el-input
          v-else-if="col.type === 'editor'"
          v-model="formData[col.field]"
          type="textarea"
          :rows="col.props?.rows || 5"
          :placeholder="col.placeholder || col.label"
          :maxlength="col.props?.maxlength"
          @change="() => onDialogFieldChange(col.field)"
          @blur="() => onDialogFieldBlur(col.field)"
        />

        <!-- signature (base64 image URL input) -->
        <div
          v-else-if="col.type === 'signature'"
          style="width: 100%;"
        >
          <canvas
            :ref="(el: any) => { if (el) signatureCanvasRefs[col.field] = el }"
            class="signature-canvas"
            @mousedown="startSign($event, col.field)"
            @mousemove="drawSign($event, col.field)"
            @mouseup="endSign(col.field)"
            @mouseleave="endSign(col.field)"
          />
          <div style="margin-top: 4px;">
            <el-button
              size="small"
              @click="clearSignature(col.field)"
            >
              Clear
            </el-button>
          </div>
        </div>

        <!-- transfer -->
        <el-transfer
          v-else-if="col.type === 'transfer'"
          v-model="formData[col.field]"
          :data="(col.props?.options ?? col.options ?? []).map((o: any) => ({ key: o.value, label: o.label }))"
          :titles="[col.props?.leftTitle || 'Source', col.props?.rightTitle || 'Target']"
          :filterable="!isColReadonly(col)"
          :disabled="isColReadonly(col)"
          @change="(v: unknown) => onDialogFieldChange(col.field, v)"
        />

        <!-- cascader -->
        <el-cascader
          v-else-if="col.type === 'cascader'"
          v-model="formData[col.field]"
          :options="col.props?.options ?? col.options ?? []"
          :props="col.props?.cascaderProps"
          :placeholder="col.placeholder || col.label"
          :clearable="!isColReadonly(col)"
          :disabled="isColReadonly(col)"
          style="width: 100%"
          @change="(v: unknown) => onDialogFieldChange(col.field, v)"
        />

        <div v-else-if="col.type === 'owner'" class="owner-preview-readonly">
          <span class="owner-preview-tag">{{ t('form.ownerPlaceholder') }}</span>
        </div>

        <!-- user / department -->
        <el-input
          v-else-if="col.type === 'user' || col.type === 'department'"
          v-model="formData[col.field]"
          :placeholder="col.placeholder || (col.type === 'user' ? 'Select user' : 'Select department')"
          :disabled="isColReadonly(col)"
          :clearable="!isColReadonly(col)"
          @change="() => onDialogFieldChange(col.field)"
          @blur="() => onDialogFieldBlur(col.field)"
        />

        <!-- fallback -->
        <el-input
          v-else
          v-model="formData[col.field]"
          :placeholder="col.placeholder || col.label"
          :disabled="isColReadonly(col)"
          :clearable="!isColReadonly(col)"
          @change="() => onDialogFieldChange(col.field)"
          @blur="() => onDialogFieldBlur(col.field)"
        />
      </el-form-item>
      </template>
    </el-form>

    <template #footer>
      <el-button @click="requestClose">
        Cancel
      </el-button>
      <el-button
        type="primary"
        @click="saveRow"
      >
        Save
      </el-button>
    </template>
  </SubTableNestedModalShell>
  <FormUploadDetailsDrawer
    v-model="detailsOpen"
    :title="t('form.fileNet.detailsTitle')"
    :file="detailsFile"
    :readonly="detailsReadonly"
    :labels="uploadDetailLabels"
  />
</template>

<script setup lang="ts">
import { ref, watch, computed, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import FormUploadDropZone from '@platform-shared/upload/FormUploadDropZone.vue'
import FormUploadDetailsDrawer from '@platform-shared/upload/FormUploadDetailsDrawer.vue'
import type { UploadDetailFile } from '@platform-shared/upload/FormUploadFileDetails.vue'
import type { FormInstance } from 'element-plus'
import { ElMessage } from 'element-plus'
import { buildInitialRow, buildRules, isColReadonly as designerColReadonly, mergeFormRowWithSeed } from './subTableAddDialogHelpers'
import type { DialogColumn } from './subTableAddDialogHelpers'
import { overlayEventRequiredOnFormRules } from '@/utils/formCreateEventRuntime'
import { isTableAuditField } from '@/utils/tableAuditFields'
import SubTableNestedModalShell from './SubTableNestedModalShell.vue'
import { normalizeUploadFieldsInRow } from './uploadFieldUtils'
import { extractFileLinks } from '@platform-shared/list/fileNames'
import {
  joinTargetFileNames,
  resolveUploadMaxFileSizeMb,
  resolveUploadMaxFiles,
  splitUploadFileList,
  toElUploadFileList,
} from '@platform-shared/upload/uploadFieldValue'
import { queuedUploadRequest } from '@platform-shared/upload/queuedUploadRequest'
import { isUploadUnauthorizedError } from '@platform-shared/upload/uploadAuthRefresh'
import { clearUploadWidgetState, setUploadWidgetState, warnIfUploadsBlocking } from '@platform-shared/upload/uploadSubmitGate'
import { useSubTableDialogComponentEvents } from '@/composables/designerSubTableField/useSubTableDialogComponentEvents'

const { t } = useI18n()

const props = defineProps<{
  visible: boolean
  columns: DialogColumn[]
  title?: string
  mode: 'add' | 'edit'
  initialData?: Record<string, any>
}>()

const emit = defineEmits<{
  (e: 'update:visible', val: boolean): void
  (e: 'save', rowData: Record<string, any>): void
}>()

const visibleModel = computed({
  get: () => props.visible,
  set: (val: boolean) => emit('update:visible', val),
})

const formRef = ref<FormInstance>()
const formData = ref<Record<string, any>>({})
const {
  onDialogFieldChange,
  onDialogFieldBlur,
  isDialogFieldVisible,
  eventRequiredState,
  eventRequiredTick,
  resetDialogEventVisibility,
  scriptFieldErrors,
  isDialogFieldDisabled,
} = useSubTableDialogComponentEvents(
  formData,
  () => props.columns,
)
const uploadFileLists = ref<Record<string, Array<{ name: string; url: string; status?: string }>>>({})
const httpRequest = queuedUploadRequest

function maxFilesOf(col: DialogColumn): number {
  return resolveUploadMaxFiles(col.props)
}

function maxFileSizeMbOf(col: DialogColumn): number {
  return resolveUploadMaxFileSizeMb(col.props)
}

watch(
  uploadFileLists,
  (lists) => {
    for (const [field, list] of Object.entries(lists)) {
      setUploadWidgetState(`dw-dialog-upload:${field}`, list)
    }
  },
  { deep: true },
)
onBeforeUnmount(() => {
  for (const field of Object.keys(uploadFileLists.value)) {
    clearUploadWidgetState(`dw-dialog-upload:${field}`)
  }
})

const detailsOpen = ref(false)
const detailsFile = ref<UploadDetailFile | null>(null)
const detailsCol = ref<DialogColumn | null>(null)

function isColReadonly(col: DialogColumn): boolean {
  return isDialogFieldDisabled(col.field, designerColReadonly(col) || isTableAuditField(col.field))
}

const detailsReadonly = computed(() => !detailsCol.value || isColReadonly(detailsCol.value))

function openDetails(col: DialogColumn, file: UploadDetailFile) {
  detailsCol.value = col
  detailsFile.value = file
  detailsOpen.value = true
}

function writeUploadColumn(
  col: DialogColumn,
  list: Array<{ url?: string; name?: string; status?: string; response?: unknown }>,
) {
  const { stored, display } = splitUploadFileList(list, maxFilesOf(col))
  formData.value[col.field] = stored
  uploadFileLists.value = { ...uploadFileLists.value, [col.field]: display }
  const target = col.props?.fileNameTargetField
  if (target && props.columns.some((c) => c.field === target)) {
    formData.value[target] = joinTargetFileNames(extractFileLinks(stored))
  }
}

const uploadDetailLabels = computed(() => ({
  description: t('form.fileNet.description'),
  callbackUrl: t('form.fileNet.callbackUrl'),
  status: t('form.fileNet.status'),
  completed: t('form.fileNet.statusCompleted'),
  saveFailed: t('form.fileNet.saveFailed'),
}))


const signatureCanvasRefs = ref<Record<string, HTMLCanvasElement>>({})
const signingField = ref<string | null>(null)

function startSign(e: MouseEvent, field: string) {
  signingField.value = field
  const canvas = signatureCanvasRefs.value[field]
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  const rect = canvas.getBoundingClientRect()
  ctx.beginPath()
  ctx.moveTo(e.clientX - rect.left, e.clientY - rect.top)
}

function drawSign(e: MouseEvent, field: string) {
  if (signingField.value !== field) return
  const canvas = signatureCanvasRefs.value[field]
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  const rect = canvas.getBoundingClientRect()
  ctx.lineWidth = 2
  ctx.lineCap = 'round'
  ctx.strokeStyle = '#000'
  ctx.lineTo(e.clientX - rect.left, e.clientY - rect.top)
  ctx.stroke()
}

function endSign(field: string) {
  if (signingField.value !== field) return
  signingField.value = null
  const canvas = signatureCanvasRefs.value[field]
  if (!canvas) return
  formData.value[field] = canvas.toDataURL('image/png')
}

function clearSignature(field: string) {
  const canvas = signatureCanvasRefs.value[field]
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  formData.value[field] = ''
}

// buildRules 把 Form Design 的 required / validate[] 转成 Element Plus 规则。此前它被算出来
// 却没有绑到 <el-form :rules>，而 handleConfirm 又调 formRef.validate() —— 没有规则的校验
// 恒真，于是子表新增/编辑行对必填与校验规则完全不设防。属接线遗漏：buildRules 是导出的、
// 有专门属性测试，用途只有这一个表单。
const formRules = computed(() => {
  void eventRequiredTick.value
  return overlayEventRequiredOnFormRules(
    buildRules(props.columns) as Record<string, unknown>,
    props.columns.filter((c) => !c.readonly && !isTableAuditField(c.field)).map((c) => c.field),
    eventRequiredState.flags,
    (key) => {
      const col = props.columns.find((c) => c.field === key)
      if (!col) return []
      return [{ required: true, message: `${col.label} is required`, trigger: 'blur' }]
    },
  )
})

watch(
  () => props.visible,
  (open) => {
    if (!open) return
    uploadFileLists.value = {}
    resetDialogEventVisibility()
    const seed = props.initialData ? JSON.parse(JSON.stringify(props.initialData)) : {}
    formData.value = { ...buildInitialRow(props.columns), ...seed }
    if (props.mode === 'edit' && props.initialData) {
      const next: Record<string, Array<{ name: string; url: string; status?: string }>> = {}
      for (const col of props.columns) {
        if (col.type === 'upload' || col.type === 'advancedUpload') next[col.field] = toElUploadFileList(formData.value[col.field])
      }
      uploadFileLists.value = next
    }
  },
  { immediate: false },
)

function onShellClosed() {
  formRef.value?.resetFields()
  uploadFileLists.value = {}
  formData.value = buildInitialRow(props.columns)
}

function requestClose() {
  formRef.value?.resetFields()
  visibleModel.value = false
}

function saveRow() {
  if (!warnIfUploadsBlocking({
    inflight: t('form.uploadWaitUntilComplete'),
    failed: t('form.uploadFixFailedBeforeSubmit'),
  })) return
  return handleSave()
}

async function handleSave() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (Object.keys(scriptFieldErrors.value).length > 0) return
  const row = mergeFormRowWithSeed(props.initialData, formData.value)
  normalizeUploadFieldsInRow(row, props.columns)
  emit('save', row)
  visibleModel.value = false
}

function handleUploadSuccess(
  _res: unknown,
  _file: { name?: string; url?: string },
  col: DialogColumn,
  list?: Array<{ url?: string; name?: string; status?: string; response?: unknown }>,
) {
  if (!list) return
  writeUploadColumn(col, list)
}

function handleUploadChange(
  col: DialogColumn,
  list?: Array<{ url?: string; name?: string; status?: string; response?: unknown }>,
) {
  if (!list) return
  writeUploadColumn(col, list)
}

function handleUploadRemove(
  col: DialogColumn,
  list?: Array<{ url?: string; name?: string; status?: string; response?: unknown }>,
) {
  writeUploadColumn(col, list ?? [])
}

function handleUploadExceed(col: DialogColumn) {
  ElMessage.warning(t('form.uploadLimitExceed', { limit: maxFilesOf(col) }))
}

function handleUploadError(col: DialogColumn, error?: unknown) {
  if (isUploadUnauthorizedError(error)) {
    ElMessage.error(t('form.uploadSessionExpired'))
    return
  }
  ElMessage.error(t('form.uploadFailedForField', { field: col.label }))
}

function handleSizeExceed(col: DialogColumn) {
  ElMessage.warning(t('form.uploadSizeExceed', { size: maxFileSizeMbOf(col) }))
}

function handleDuplicate(_col: DialogColumn, name: string) {
  ElMessage.warning(t('form.uploadDuplicate', { name }))
}
</script>

<style scoped>
@import '@/styles/form-readonly.scss';

/* 不折行、不截断；label-width=auto 下自然宽度即最长文案，输入框整列对齐 */
:deep(.el-form-item__label) {
  white-space: nowrap;
  min-width: max-content;
}

.owner-preview-readonly {
  display: flex;
  align-items: center;
  min-height: 32px;
  padding: 4px 8px;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: var(--ws-radius-input, 8px);
  background: var(--el-disabled-bg-color, #f5f7fa);
}

.owner-preview-tag {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 8px;
  border-radius: 4px;
  background: #f0f2f5;
  font-size: 13px;
  color: #909399;
  line-height: 24px;
}
</style>

<style>
.sub-table-color-popper {
  z-index: var(--sub-table-nested-popper-z, 10050) !important;
}

.signature-canvas {
  width: 100%;
  height: 120px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  cursor: crosshair;
  background: #fff;
}
</style>
