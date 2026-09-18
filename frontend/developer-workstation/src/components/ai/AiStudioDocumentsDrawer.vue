<template>
  <el-drawer
    :model-value="modelValue"
    :title="t('ai.studio.docSync.drawerTitle')"
    size="640px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="docs-drawer__toolbar">
      <span
        v-if="syncing"
        class="docs-drawer__syncing"
      >
        <el-icon class="is-loading"><Loading /></el-icon>
        {{ t('ai.studio.docSync.syncing') }}
      </span>
      <el-button
        v-if="canModify"
        size="small"
        :disabled="syncing"
        @click="emit('check')"
      >
        {{ t('ai.studio.docSync.checkNow') }}
      </el-button>
      <el-button
        v-if="canModify"
        size="small"
        type="primary"
        plain
        @click="editInSettings"
      >
        {{ t('ai.studio.docSync.editInSettings') }}
      </el-button>
    </div>

    <el-tabs
      v-model="activeType"
      v-loading="loading"
    >
      <el-tab-pane
        v-for="type in FUNCTION_UNIT_DOCUMENT_TYPES"
        :key="type"
        :label="t(`functionUnit.documents.type.${type}`)"
        :name="type"
      >
        <template v-if="documents[type]">
          <div class="docs-drawer__meta">
            {{ documentVersionLabel(documents[type]) }} · {{ documents[type]!.createdBy }}
            · {{ formatTime(documents[type]!.createdAt) }}
            <template v-if="documents[type]!.summary">
              · {{ formatDocumentSource(t, documents[type]!.summary) }}
            </template>
          </div>
          <MarkdownRenderer :content="documents[type]!.content ?? ''" />
        </template>
        <el-empty
          v-else
          :description="t('functionUnit.documents.empty')"
          :image-size="60"
        />
      </el-tab-pane>
    </el-tabs>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { Loading } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import MarkdownRenderer from './MarkdownRenderer.vue'
import { documentVersionLabel, formatDocumentSource } from '@/utils/functionUnitDocumentSource'
import {
  functionUnitDocumentApi,
  FUNCTION_UNIT_DOCUMENT_TYPES,
  type FunctionUnitDocument,
  type FunctionUnitDocumentType
} from '@/api/functionUnitDocument'

const props = defineProps<{
  modelValue: boolean
  functionUnitId: number
  canModify: boolean
  syncing: boolean
  /** 变化时（同步结束 / 恢复版本后）重新拉取 */
  refreshKey: number
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  check: []
}>()

const { t } = useI18n()
const router = useRouter()

const loading = ref(false)
const activeType = ref<FunctionUnitDocumentType>('REQUIREMENTS')
const documents = ref<Partial<Record<FunctionUnitDocumentType, FunctionUnitDocument | null>>>({})

function formatTime(value: string) {
  return dayjs(value).format('YYYY-MM-DD HH:mm')
}

async function load() {
  loading.value = true
  try {
    documents.value = (await functionUnitDocumentApi.current(props.functionUnitId)).data
  } finally {
    loading.value = false
  }
}

function editInSettings() {
  router.push({
    name: 'FunctionUnitEdit',
    params: { id: props.functionUnitId },
    query: { settings: activeType.value }
  })
}

watch(() => [props.modelValue, props.refreshKey], () => {
  if (props.modelValue) void load()
}, { immediate: true })
</script>

<style lang="scss" scoped>
.docs-drawer__toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 8px;
}

.docs-drawer__syncing {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-right: auto;
  color: #409eff;
  font-size: 12px;
}

.docs-drawer__meta {
  margin-bottom: 8px;
  color: #909399;
  font-size: 12px;
}
</style>
