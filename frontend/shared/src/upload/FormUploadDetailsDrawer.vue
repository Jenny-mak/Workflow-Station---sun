<template>
  <el-drawer
    :model-value="modelValue"
    direction="rtl"
    size="420px"
    :title="title"
    destroy-on-close
    append-to-body
    :z-index="drawerZ"
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
import { ref, watch } from 'vue'
import { useZIndex } from 'element-plus'
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
