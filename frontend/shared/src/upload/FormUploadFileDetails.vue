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
      <div class="upload-file-details__name">{{ row.name }}</div>
      <div class="upload-file-details__field">
        <label>{{ labels.description }}</label>
        <el-input
          v-if="!readonly"
          v-model="row.description"
          maxlength="500"
          show-word-limit
          @blur="saveDescription(row)"
        />
        <span
          v-else
          class="upload-file-details__value"
        >{{ row.description || '—' }}</span>
      </div>
      <div class="upload-file-details__field">
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
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { extractStoredFileName, extractStoredFileNames } from './storedFileName'
import { queryFileTransfers, updateFileDescription } from './fileTransferApi'

export interface UploadDetailFile {
  url: string
  name: string
}

export interface UploadDetailLabels {
  description: string
  callbackUrl: string
  status: string
  completed: string
  saveFailed: string
}

interface DetailRow extends UploadDetailFile {
  storedName: string
  description: string
}

const props = defineProps<{
  files: UploadDetailFile[]
  readonly?: boolean
  labels: UploadDetailLabels
  previewFile?: (file: UploadDetailFile) => void
}>()

function onCallbackClick(event: MouseEvent, row: DetailRow): void {
  if (!props.previewFile) return
  if (event.defaultPrevented) return
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
    return
  }
  event.preventDefault()
  props.previewFile({ url: row.url, name: row.name })
}

const rows = ref<DetailRow[]>([])
let loadSeq = 0

function toRows(files: UploadDetailFile[]): DetailRow[] {
  return files
    .filter((file) => file.url)
    .map((file) => ({
      url: file.url,
      name: file.name || file.url,
      storedName: extractStoredFileName(file.url),
      description: '',
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
  rows.value = next.map((row) => ({
    ...row,
    description: byName.get(row.storedName) ?? '',
  }))
}

async function saveDescription(row: DetailRow): Promise<void> {
  if (!row.storedName || props.readonly) return
  try {
    const saved = await updateFileDescription(row.storedName, row.description)
    row.description = saved.fileDescription ?? ''
  } catch {
    ElMessage.error(props.labels.saveFailed)
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

.upload-file-details__name {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  word-break: break-all;
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

.upload-file-details__value,
.upload-file-details__link {
  font-size: 13px;
  word-break: break-all;
}

.upload-file-details__link {
  color: var(--el-color-primary);
}
</style>
