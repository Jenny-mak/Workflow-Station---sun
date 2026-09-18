<template>
  <el-drawer
    :model-value="modelValue"
    :title="`${documentLabel} · ${t('functionUnit.documents.history')}`"
    size="560px"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading">
      <el-empty
        v-if="!loading && versions.length === 0"
        :description="t('functionUnit.documents.historyEmpty')"
        :image-size="60"
      />
      <div
        v-for="item in versions"
        :key="item.version"
        class="document-history__item"
        :class="{ 'is-selected': viewing?.version === item.version }"
      >
        <div class="document-history__head">
          <strong>{{ documentVersionLabel(item) }}</strong>
          <el-tag
            v-if="item.version === currentVersion"
            size="small"
            type="success"
          >
            {{ t('functionUnit.documents.current') }}
          </el-tag>
          <span class="document-history__meta">{{ item.createdBy }} · {{ formatTime(item.createdAt) }}</span>
        </div>
        <div class="document-history__summary">
          {{ formatDocumentSource(t, item.summary) }}
        </div>
        <div class="document-history__actions">
          <el-button
            link
            type="primary"
            @click="view(item.version)"
          >
            {{ t('functionUnit.documents.view') }}
          </el-button>
          <template v-if="item.version !== currentVersion">
            <el-button
              link
              type="primary"
              @click="compare(item.version)"
            >
              {{ t('functionUnit.documents.compare') }}
            </el-button>
            <el-button
              v-if="!readonly"
              link
              type="warning"
              :loading="restoring === item.version"
              @click="restore(item)"
            >
              {{ t('functionUnit.documents.restore') }}
            </el-button>
          </template>
        </div>
      </div>

      <div
        v-if="viewing"
        class="document-history__preview"
      >
        <MarkdownRenderer :content="viewing.content ?? ''" />
      </div>
    </div>

    <DocumentVersionDiffDialog
      v-if="comparing !== null"
      v-model="diffVisible"
      :function-unit-id="functionUnitId"
      :type="type"
      :from-version="comparing"
      :to-version="currentVersion"
    />
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import MarkdownRenderer from '@/components/ai/MarkdownRenderer.vue'
import DocumentVersionDiffDialog from './DocumentVersionDiffDialog.vue'
import {
  functionUnitDocumentApi,
  isDocumentConflict,
  type FunctionUnitDocument,
  type FunctionUnitDocumentType
} from '@/api/functionUnitDocument'
import { resolveUserFacingHttpMessage } from '@/utils/httpErrorMessage'
import { documentVersionLabel, formatDocumentSource } from '@/utils/functionUnitDocumentSource'

const props = defineProps<{
  modelValue: boolean
  functionUnitId: number
  type: FunctionUnitDocumentType
  /** 编辑器里当前的版本（0 = 还没有文档） */
  currentVersion: number
  readonly: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  restored: [document: FunctionUnitDocument]
  /** 编辑器的版本已过时（别人刚保存过），需要重新载入 */
  stale: []
}>()

const { t } = useI18n()

const loading = ref(false)
const versions = ref<FunctionUnitDocument[]>([])
const viewing = ref<FunctionUnitDocument | null>(null)
const restoring = ref<number | null>(null)
const comparing = ref<number | null>(null)
const diffVisible = ref(false)

const documentLabel = computed(() => t(`functionUnit.documents.type.${props.type}`))

function formatTime(value: string) {
  return dayjs(value).format('YYYY-MM-DD HH:mm')
}

async function load() {
  loading.value = true
  viewing.value = null
  try {
    versions.value = (await functionUnitDocumentApi.history(props.functionUnitId, props.type)).data
  } finally {
    loading.value = false
  }
}

async function view(version: number) {
  viewing.value = (await functionUnitDocumentApi.version(props.functionUnitId, props.type, version)).data
}

function compare(version: number) {
  comparing.value = version
  diffVisible.value = true
}

async function restore(item: FunctionUnitDocument) {
  const version = item.version
  try {
    await ElMessageBox.confirm(
      t('functionUnit.documents.restoreConfirm', { version: documentVersionLabel(item) }),
      documentLabel.value,
      { type: 'warning', confirmButtonText: t('functionUnit.documents.restore') }
    )
  } catch {
    return // 用户取消
  }
  restoring.value = version
  try {
    const res = await functionUnitDocumentApi.restore(props.functionUnitId, props.type, version, props.currentVersion)
    ElMessage.success(t('functionUnit.documents.restored',
      { from: documentVersionLabel(item), to: documentVersionLabel(res.data) }))
    emit('restored', res.data)
    await load()
  } catch (e) {
    if (!isDocumentConflict(e)) {
      ElMessage.error(resolveUserFacingHttpMessage(e, t))
      return
    }
    ElMessage.warning(t('functionUnit.documents.conflictReload'))
    emit('stale')
    await load()
  } finally {
    restoring.value = null
  }
}

watch(() => [props.modelValue, props.type], () => {
  if (props.modelValue) void load()
}, { immediate: true })
</script>

<style lang="scss" scoped>
.document-history__item {
  padding: 10px 0;
  border-bottom: 1px solid #ebeef5;

  &.is-selected {
    background: #f5f7fa;
  }
}

.document-history__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.document-history__meta {
  margin-left: auto;
  color: #909399;
  font-size: 12px;
}

.document-history__summary {
  margin-top: 4px;
  color: #606266;
  font-size: 12px;
}

.document-history__preview {
  margin-top: 12px;
  padding: 12px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
</style>
