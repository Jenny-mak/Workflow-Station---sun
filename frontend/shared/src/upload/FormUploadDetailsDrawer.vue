<template>
  <el-drawer
    :model-value="modelValue"
    direction="rtl"
    size="420px"
    destroy-on-close
    append-to-body
    :z-index="drawerZ"
    data-testid="upload-file-details-drawer"
    @close="emit('update:modelValue', false)"
  >
    <template #header="{ titleId, titleClass }">
      <div class="upload-file-details-drawer__header">
        <span
          :id="titleId"
          :class="titleClass"
        >{{ title }}</span>
        <a
          v-if="helpHref"
          class="upload-file-details-drawer__help"
          data-testid="upload-file-details-help"
          :href="helpHref"
          target="_blank"
          rel="noopener noreferrer"
          :aria-label="helpAriaLabel"
        >
          <el-icon><QuestionFilled /></el-icon>
        </a>
      </div>
    </template>
    <FormUploadFileDetails
      v-if="file"
      :files="[file]"
      :readonly="readonly"
      :labels="labels"
      :preview-file="previewFile"
      :cannot-download="cannotDownload"
    />
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useZIndex } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import FormUploadFileDetails, {
  type UploadDetailFile,
  type UploadDetailLabels,
} from './FormUploadFileDetails.vue'
import { resolveUploadDrawerZIndex } from './uploadOverlayZIndex'

const props = defineProps<{
  modelValue: boolean
  title: string
  file: UploadDetailFile | null
  readonly?: boolean
  labels: UploadDetailLabels
  previewFile?: (file: UploadDetailFile) => void
  cannotDownload?: boolean
  helpHref?: string
  helpAriaLabel?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const { nextZIndex } = useZIndex()
const drawerZ = ref(2000)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    drawerZ.value = resolveUploadDrawerZIndex(nextZIndex)
  },
  { immediate: true },
)
</script>

<style scoped>
.upload-file-details-drawer__header {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.upload-file-details-drawer__help {
  display: inline-flex;
  align-items: center;
  color: var(--el-color-primary);
  font-size: 16px;
  line-height: 1;
  flex-shrink: 0;
}

.upload-file-details-drawer__help:hover,
.upload-file-details-drawer__help:focus-visible {
  color: var(--el-color-primary-light-3);
}
</style>
