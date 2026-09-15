<template>
  <div
    v-if="files.length"
    class="upload-file-details"
    data-testid="upload-file-details"
  >
    <div
      v-for="row in rows"
      :key="row.url"
      class="upload-file-details__row"
    >
      <div class="upload-file-details__name-row">
        <div
          class="upload-file-details__name"
          :title="row.name"
        >
          {{ row.name }}
        </div>
        <div class="upload-file-details__actions">
          <el-button
            v-if="!cannotDownload"
            size="small"
            data-testid="upload-file-download"
            :loading="downloadingName === row.storedName"
            @click="downloadRow(row)"
          >
            {{ labels.download }}
          </el-button>
          <el-button
            size="small"
            data-testid="upload-file-preview"
            :loading="previewingName === row.storedName"
            @click="previewRow(row)"
          >
            {{ labels.preview }}
          </el-button>
        </div>
      </div>
      <div class="upload-file-details__field">
        <label>{{ labels.description }}</label>
        <el-input
          v-if="!readonly"
          v-model="row.description"
          maxlength="500"
          show-word-limit
        />
        <span
          v-else
          class="upload-file-details__value"
        >{{ row.description || '—' }}</span>
        <el-button
          v-if="!readonly"
          type="primary"
          size="small"
          class="upload-file-details__save"
          data-testid="upload-file-save"
          :disabled="!isDescriptionDirty(row)"
          :loading="savingName === row.storedName"
          @click="saveDescription(row)"
        >
          {{ labels.save }}
        </el-button>
      </div>
      <template v-if="SHOW_FILENET_DETAILS">
        <div
          v-if="!cannotDownload"
          class="upload-file-details__field"
        >
          <label>{{ labels.callbackUrl }}</label>
          <a
            class="upload-file-details__link"
            :href="row.url"
            target="_blank"
            rel="noopener noreferrer"
            @click="onCallbackClick($event, row)"
          >{{ row.url }}</a>
        </div>
        <div class="upload-file-details__field">
          <label>{{ labels.status }}</label>
          <el-tag
            type="success"
            size="small"
          >
            {{ labels.completed }}
          </el-tag>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, provide, ref, watch } from 'vue'
import { ElMessage, formContextKey } from 'element-plus'
import { extractStoredFileName, extractStoredFileNames } from './storedFileName'
import { queryFileTransfers, updateFileDescription } from './fileTransferApi'
import {
  fetchStoredFileBlob,
  openStoredFileInNewTab,
  triggerBlobDownload,
} from './storedFileBlob'

export interface UploadDetailFile {
  url: string
  name: string
}

export interface UploadDetailLabels {
  description: string
  callbackUrl: string
  status: string
  completed: string
  save: string
  saveSuccess: string
  saveFailed: string
  download: string
  preview: string
  downloadFailed: string
  fileNotFound: string
}

interface DetailRow extends UploadDetailFile {
  storedName: string
  description: string
  savedDescription: string
}

/** FileNet archive fields stay in the template but are hidden until that work ships. */
const SHOW_FILENET_DETAILS = false

// Download/Preview must stay clickable on My Request / completed views.
// el-drawer append-to-body moves the DOM, but Vue inject still sees the outer
// el-form :disabled (same pattern as RecordNoteField / SubTableField).
provide(formContextKey, undefined as never)

const props = defineProps<{
  files: UploadDetailFile[]
  readonly?: boolean
  labels: UploadDetailLabels
  previewFile?: (file: UploadDetailFile) => void
  /** Designer Advanced Upload "Can not download": hide Download and the raw file URL. */
  cannotDownload?: boolean
}>()

const downloadingName = ref('')
const previewingName = ref('')
const savingName = ref('')
const rows = ref<DetailRow[]>([])
let loadSeq = 0

function isDescriptionDirty(row: DetailRow): boolean {
  return row.description !== row.savedDescription
}

function fileErrorMessage(result: 'not-found' | 'failed' | 'blocked'): string {
  return result === 'not-found' ? props.labels.fileNotFound : props.labels.downloadFailed
}

function onCallbackClick(event: MouseEvent, row: DetailRow): void {
  if (!props.previewFile) return
  if (event.defaultPrevented) return
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
    return
  }
  event.preventDefault()
  props.previewFile({ url: row.url, name: row.name })
}

function toRows(files: UploadDetailFile[]): DetailRow[] {
  return files
    .filter((file) => file.url)
    .map((file) => ({
      url: file.url,
      name: file.name || file.url,
      storedName: extractStoredFileName(file.url),
      description: '',
      savedDescription: '',
    }))
}

async function loadDescriptions(files: UploadDetailFile[]): Promise<void> {
  const next = toRows(files)
  rows.value = next
  const names = extractStoredFileNames(next.map((row) => row.url))
  if (names.length === 0) return
  const seq = ++loadSeq
  const remote = await queryFileTransfers(names)
  if (seq !== loadSeq) return
  const byName = new Map(remote.map((item) => [item.storedName, item.fileDescription ?? '']))
  rows.value = next.map((row) => {
    const description = byName.get(row.storedName) ?? ''
    return { ...row, description, savedDescription: description }
  })
}

async function saveDescription(row: DetailRow): Promise<void> {
  if (!row.storedName || props.readonly || !isDescriptionDirty(row)) return
  savingName.value = row.storedName
  try {
    const saved = await updateFileDescription(row.storedName, row.description)
    row.description = saved.fileDescription ?? ''
    row.savedDescription = row.description
    ElMessage.success(props.labels.saveSuccess)
  } catch {
    ElMessage.error(props.labels.saveFailed)
  } finally {
    savingName.value = ''
  }
}

async function downloadRow(row: DetailRow): Promise<void> {
  if (props.cannotDownload || !row.url || downloadingName.value) return
  downloadingName.value = row.storedName
  try {
    const fetched = await fetchStoredFileBlob(row.url)
    if (!fetched.ok) {
      ElMessage.error(fileErrorMessage(fetched.result))
      return
    }
    triggerBlobDownload(fetched.blob, row.name)
  } finally {
    downloadingName.value = ''
  }
}

async function previewRow(row: DetailRow): Promise<void> {
  if (!row.url || previewingName.value) return
  if (props.previewFile) {
    props.previewFile({ url: row.url, name: row.name })
    return
  }
  previewingName.value = row.storedName
  try {
    const result = await openStoredFileInNewTab(row.url)
    if (result !== 'ok') ElMessage.error(fileErrorMessage(result))
  } finally {
    previewingName.value = ''
  }
}

watch(
  () => props.files.map((file) => file.url).join('\0'),
  () => {
    void loadDescriptions(props.files).catch(() => {
      // FALLBACK(ux): description is display-only metadata; a query miss must not hide the uploaded files
      rows.value = toRows(props.files)
    })
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  loadSeq += 1
})
</script>

<style scoped>
.upload-file-details {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 8px;
  width: 100%;
}

.upload-file-details__row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background: var(--el-fill-color-blank);
}

.upload-file-details__name-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.upload-file-details__name {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  word-break: break-all;
  min-width: 0;
}

.upload-file-details__actions {
  display: flex;
  flex-shrink: 0;
  gap: 6px;
}

.upload-file-details__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.upload-file-details__field label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.upload-file-details__save {
  align-self: flex-start;
  margin-top: 4px;
}

.upload-file-details__value,
.upload-file-details__link {
  font-size: 13px;
  word-break: break-all;
}

.upload-file-details__link {
  color: var(--el-color-primary);
}
</style>
