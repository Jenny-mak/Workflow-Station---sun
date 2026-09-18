<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('functionUnit.documents.diffTitle', { doc: documentLabel, from: fromLabelText, to: toLabelText })"
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
import { documentVersionLabel } from '@/utils/functionUnitDocumentSource'
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
const fromLabelText = ref('')
const toLabelText = ref('')

const documentLabel = computed(() => t(`functionUnit.documents.type.${props.type}`))

/** 取某一版的正文与显示用版本号；版本 0 表示"还没有文档"。 */
async function versionOf(version: number): Promise<{ content: string; label: string }> {
  if (version === 0) return { content: '', label: '—' }
  const res = await functionUnitDocumentApi.version(props.functionUnitId, props.type, version)
  return { content: res.data.content ?? '', label: documentVersionLabel(res.data) }
}

async function load() {
  loading.value = true
  loaded.value = false
  try {
    const [from, to] = await Promise.all([versionOf(props.fromVersion), versionOf(props.toVersion)])
    oldText.value = from.content
    newText.value = to.content
    fromLabelText.value = from.label
    toLabelText.value = to.label
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
