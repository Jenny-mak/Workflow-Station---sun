<template>
  <div
    v-loading="loading"
    class="document-editor"
  >
    <div class="document-editor__toolbar">
      <span
        v-if="saved"
        class="document-editor__meta"
      >
        {{ documentVersionLabel(saved) }} · {{ saved.createdBy }} · {{ formatTime(saved.createdAt) }}
        <template v-if="saved.summary"> · {{ formatDocumentSource(t, saved.summary) }}</template>
      </span>
      <span
        v-else
        class="document-editor__meta"
      >{{ t('functionUnit.documents.empty') }}</span>
      <DesignerHelpLink
        path="/fu-documents"
        :aria-label="t('functionUnit.documents.guideLinkAria')"
        test-id="fu-documents-guide-link"
      />
      <el-tag
        v-if="isDirty"
        size="small"
        type="warning"
      >
        {{ t('functionUnit.documents.unsaved') }}
      </el-tag>
      <div class="document-editor__buttons">
        <el-radio-group
          v-if="!readonly"
          v-model="mode"
          size="small"
        >
          <el-radio-button value="edit">
            {{ t('functionUnit.documents.edit') }}
          </el-radio-button>
          <el-radio-button value="preview">
            {{ t('functionUnit.documents.preview') }}
          </el-radio-button>
        </el-radio-group>
        <el-button
          v-if="!readonly"
          size="small"
          :icon="Upload"
          @click="fileInput?.click()"
        >
          {{ t('functionUnit.documents.import') }}
        </el-button>
        <el-button
          size="small"
          :icon="Download"
          :disabled="!draft"
          @click="download"
        >
          {{ t('functionUnit.documents.download') }}
        </el-button>
        <el-button
          size="small"
          :disabled="!saved"
          @click="historyVisible = true"
        >
          {{ t('functionUnit.documents.history') }}
        </el-button>
        <el-button
          v-if="!readonly"
          size="small"
          type="primary"
          :disabled="!isDirty"
          :loading="saving"
          @click="save"
        >
          {{ t('common.save') }}
        </el-button>
      </div>
    </div>

    <input
      ref="fileInput"
      type="file"
      class="document-editor__file"
      :accept="DOCUMENT_FILE_ACCEPT"
      @change="onFileSelected"
    >

    <div
      class="document-editor__body"
      :class="{ 'is-split': showEditor }"
    >
      <el-input
        v-if="showEditor"
        v-model="draft"
        type="textarea"
        class="document-editor__input"
        resize="none"
        :placeholder="t('functionUnit.documents.placeholder')"
      />
      <div class="document-editor__preview">
        <MarkdownRenderer
          v-if="draft"
          :content="draft"
        />
        <el-empty
          v-else
          :description="t('functionUnit.documents.empty')"
          :image-size="60"
        />
      </div>
    </div>

    <FunctionUnitDocumentHistory
      v-model="historyVisible"
      :function-unit-id="functionUnitId"
      :type="type"
      :current-version="saved?.version ?? 0"
      :readonly="readonly"
      @restored="applySaved"
      @stale="load"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Upload } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import MarkdownRenderer from '@/components/ai/MarkdownRenderer.vue'
import DesignerHelpLink from '@/components/designer/DesignerHelpLink.vue'
import FunctionUnitDocumentHistory from './FunctionUnitDocumentHistory.vue'
import {
  functionUnitDocumentApi,
  isDocumentConflict,
  type FunctionUnitDocument,
  type FunctionUnitDocumentType
} from '@/api/functionUnitDocument'
import { pickHttpErrorCode, resolveUserFacingHttpMessage } from '@/utils/httpErrorMessage'
import { documentVersionLabel, formatDocumentSource } from '@/utils/functionUnitDocumentSource'
import {
  DOCUMENT_FILE_ACCEPT,
  DocumentFileRejected,
  documentFileName,
  downloadDocumentFile,
  readDocumentFile
} from '@/utils/functionUnitDocumentFile'

const props = defineProps<{
  functionUnitId: number
  /** 只用于下载文件名 */
  functionUnitName?: string
  type: FunctionUnitDocumentType
  readonly: boolean
}>()

const { t } = useI18n()

const loading = ref(false)
const saving = ref(false)
const saved = ref<FunctionUnitDocument | null>(null)
const draft = ref('')
const mode = ref<'edit' | 'preview'>('edit')
const historyVisible = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

const showEditor = computed(() => !props.readonly && mode.value === 'edit')
const isDirty = computed(() => draft.value !== (saved.value?.content ?? ''))

function formatTime(value: string) {
  return dayjs(value).format('YYYY-MM-DD HH:mm')
}

function applySaved(document: FunctionUnitDocument | null) {
  saved.value = document
  draft.value = document?.content ?? ''
}

async function fetchLatest(): Promise<FunctionUnitDocument | null> {
  return (await functionUnitDocumentApi.current(props.functionUnitId)).data[props.type]
}

async function load() {
  loading.value = true
  try {
    applySaved(await fetchLatest())
  } finally {
    loading.value = false
  }
}

async function submit(baseVersion: number) {
  const res = await functionUnitDocumentApi.save(props.functionUnitId, props.type, draft.value, baseVersion)
  applySaved(res.data)
  ElMessage.success(t('functionUnit.documents.saved', { version: documentVersionLabel(res.data) }))
}

/** 别人先保存了：载入最新（丢弃本地修改），或以最新版本为基准仍然保存。 */
async function resolveConflict() {
  let action: 'confirm' | 'cancel' | 'close'
  try {
    await ElMessageBox.confirm(
      t('functionUnit.documents.conflictMsg'),
      t('functionUnit.documents.conflictTitle'),
      {
        type: 'warning',
        distinguishCancelAndClose: true,
        confirmButtonText: t('functionUnit.documents.loadLatest'),
        cancelButtonText: t('functionUnit.documents.saveAnyway')
      }
    )
    action = 'confirm'
  } catch (reason) {
    action = reason === 'cancel' ? 'cancel' : 'close'
  }
  if (action === 'confirm') {
    await load()
  } else if (action === 'cancel') {
    const latest = await fetchLatest()
    await submit(latest?.version ?? 0)
  }
}

async function save() {
  if (!isDirty.value) return
  saving.value = true
  try {
    try {
      await submit(saved.value?.version ?? 0)
    } catch (e) {
      if (!isDocumentConflict(e)) throw e
      await resolveConflict()
    }
  } catch (e) {
    const code = pickHttpErrorCode((e as { response?: { data?: unknown } })?.response?.data)
    ElMessage.error(code === 'DOCUMENT_TOO_LARGE'
      ? t('functionUnit.documents.tooLarge')
      : resolveUserFacingHttpMessage(e, t))
  } finally {
    saving.value = false
  }
}

/** 下载的是编辑器里看到的内容：有未保存修改时就是草稿，文件名仍带它所基于的版本号。 */
function download() {
  downloadDocumentFile(documentFileName(props.functionUnitName, props.type, saved.value), draft.value)
}

/**
 * 导入只替换编辑器草稿，不直接落库：用户在预览里看过再点保存，版本比对与冲突处理走同一条链路。
 * 已保存的内容在版本历史里，覆盖无损；只有未保存的修改会丢，所以只在这种情况下确认。
 */
async function onFileSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 清空 value：否则再次选择同一个文件不会触发 change
  input.value = ''
  if (!file) return
  let content: string
  try {
    content = await readDocumentFile(file)
  } catch (e) {
    if (!(e instanceof DocumentFileRejected)) throw e
    ElMessage.error(t(`functionUnit.documents.importRejected.${e.reason}`))
    return
  }
  if (isDirty.value) {
    try {
      await ElMessageBox.confirm(
        t('functionUnit.documents.importReplaceConfirm', { file: file.name }),
        t('functionUnit.documents.unsavedTitle'),
        { type: 'warning', confirmButtonText: t('functionUnit.documents.importReplace') }
      )
    } catch {
      return
    }
  }
  draft.value = content
  mode.value = 'edit'
  ElMessage.success(t('functionUnit.documents.imported', { file: file.name }))
}

onMounted(load)

defineExpose({ isDirty, reload: load })
</script>

<style lang="scss" scoped>
.document-editor__toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.document-editor__meta {
  color: #909399;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.document-editor__buttons {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  flex-shrink: 0;
}

.document-editor__file {
  display: none;
}

.document-editor__body {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
  height: 55vh;

  &.is-split {
    grid-template-columns: 1fr 1fr;
  }
}

.document-editor__input {
  height: 100%;

  :deep(.el-textarea__inner) {
    height: 100%;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
  }
}

.document-editor__preview {
  overflow-y: auto;
  padding: 8px 12px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
</style>
