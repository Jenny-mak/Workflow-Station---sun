<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('functionUnit.documents.diffTitle', { doc: documentLabel, from: fromVersion, to: toVersion })"
    width="820px"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div
      v-loading="loading"
      class="document-version-diff"
    >
      <DocumentDiffView
        v-if="loaded"
        :old-text="oldText"
        :new-text="newText"
      />
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import DocumentDiffView from './DocumentDiffView.vue'
import { functionUnitDocumentApi, type FunctionUnitDocumentType } from '@/api/functionUnitDocument'
import { resolveUserFacingHttpMessage } from '@/utils/httpErrorMessage'

/** 两个版本之间的逐行对比；版本 0 表示"还没有文档"（空文本）。 */
const props = defineProps<{
  modelValue: boolean
  functionUnitId: number
  type: FunctionUnitDocumentType
  fromVersion: number
  toVersion: number
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const { t } = useI18n()

const loading = ref(false)
const loaded = ref(false)
const oldText = ref('')
const newText = ref('')

const documentLabel = computed(() => t(`functionUnit.documents.type.${props.type}`))

async function contentOf(version: number): Promise<string> {
  if (version === 0) return ''
  const res = await functionUnitDocumentApi.version(props.functionUnitId, props.type, version)
  return res.data.content ?? ''
}

async function load() {
  loading.value = true
  loaded.value = false
  try {
    const [from, to] = await Promise.all([contentOf(props.fromVersion), contentOf(props.toVersion)])
    oldText.value = from
    newText.value = to
    loaded.value = true
  } catch (e) {
    ElMessage.error(resolveUserFacingHttpMessage(e, t))
  } finally {
    loading.value = false
  }
}

watch(() => [props.modelValue, props.fromVersion, props.toVersion, props.type], () => {
  if (props.modelValue) void load()
}, { immediate: true })
</script>

<style lang="scss" scoped>
.document-version-diff {
  min-height: 120px;
  max-height: 60vh;
  overflow-y: auto;
}
</style>
