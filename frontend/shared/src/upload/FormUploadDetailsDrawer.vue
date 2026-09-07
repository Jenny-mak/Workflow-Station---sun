<template>
  <el-drawer
    :model-value="modelValue"
    direction="rtl"
    size="420px"
    :title="title"
    destroy-on-close
    :z-index="4100"
    data-testid="upload-file-details-drawer"
    @close="emit('update:modelValue', false)"
  >
    <FormUploadFileDetails
      v-if="file"
      :files="[file]"
      :readonly="readonly"
      :labels="labels"
      :preview-file="previewFile"
    />
  </el-drawer>
</template>

<script setup lang="ts">
import FormUploadFileDetails, {
  type UploadDetailFile,
  type UploadDetailLabels,
} from './FormUploadFileDetails.vue'

defineProps<{
  modelValue: boolean
  title: string
  file: UploadDetailFile | null
  readonly?: boolean
  labels: UploadDetailLabels
  previewFile?: (file: UploadDetailFile) => void
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()
</script>
