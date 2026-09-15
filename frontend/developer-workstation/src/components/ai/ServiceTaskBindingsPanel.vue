<template>
  <div class="bindings">
    <div class="bindings__head">
      <div>
        <h3 class="bindings__title">{{ t('ai.studio.workspace.bindings.title') }}</h3>
        <p class="bindings__hint">{{ t('ai.studio.workspace.bindings.hint') }}</p>
      </div>
      <el-button
        type="primary"
        plain
        @click="emit('open-automation')"
      >
        {{ t('ai.studio.workspace.openAutomation') }}
      </el-button>
    </div>

    <el-skeleton
      v-if="loading"
      :rows="3"
      animated
    />
    <el-alert
      v-else-if="error"
      type="error"
      :closable="false"
      :title="error"
    />
    <el-empty
      v-else-if="!hasProcess"
      :description="t('ai.studio.workspace.bindings.noProcess')"
    />
    <el-empty
      v-else-if="bindings.length === 0"
      :description="t('ai.studio.workspace.bindings.empty')"
    />
    <el-table
      v-else
      :data="bindings"
      class="bindings__table"
      size="default"
    >
      <el-table-column
        prop="id"
        :label="t('ai.studio.workspace.bindings.task')"
        min-width="160"
      />
      <el-table-column
        :label="t('ai.studio.workspace.bindings.name')"
        min-width="160"
      >
        <template #default="{ row }">
          {{ row.name ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('ai.studio.workspace.bindings.type')"
        width="120"
      >
        <template #default="{ row }">
          {{ row.serviceType ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('ai.studio.workspace.bindings.flowKey')"
        min-width="220"
      >
        <template #default="{ row }">
          <el-tag
            v-if="row.flowKey"
            type="success"
            effect="plain"
            class="bindings__key"
          >
            {{ row.flowKey }}
          </el-tag>
          <el-tag
            v-else-if="row.legacyFlowId"
            type="warning"
            effect="plain"
            class="bindings__key"
          >
            {{ row.legacyFlowId }} · {{ t('ai.studio.workspace.bindings.legacy') }}
          </el-tag>
          <span
            v-else
            class="bindings__unbound"
          >{{ t('ai.studio.workspace.bindings.unbound') }}</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useFunctionUnitStore } from '@/stores/functionUnit'
import { parseServiceTaskBindings, type ServiceTaskBinding } from '@/utils/serviceTaskBindings'

const props = defineProps<{ functionUnitId: number }>()
const emit = defineEmits<{ (e: 'open-automation'): void }>()

const { t } = useI18n()
const store = useFunctionUnitStore()

const loading = ref(true)
const error = ref('')
const hasProcess = ref(false)
const bindings = ref<ServiceTaskBinding[]>([])

/** 每次挂载都重新拉流程定义：AI Studio 用 stageReloadKey 重挂载本组件来反映 Apply 结果 */
onMounted(async () => {
  loading.value = true
  error.value = ''
  try {
    const process = await store.fetchProcess(props.functionUnitId)
    const xml = process?.bpmnXml ?? ''
    hasProcess.value = !!xml.trim()
    bindings.value = hasProcess.value ? parseServiceTaskBindings(xml) : []
  } catch (e) {
    // 流程定义拉取失败或 XML 解析失败都显式提示，不留空表让人误以为"没有服务任务"
    error.value = `${t('ai.studio.workspace.bindings.loadFailed')} ${e instanceof Error ? e.message : ''}`.trim()
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.bindings {
  padding: 24px 28px;
}
.bindings__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.bindings__title {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 600;
}
.bindings__hint {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.bindings__key {
  font-family: var(--el-font-family-mono, ui-monospace, monospace);
}
.bindings__unbound {
  color: var(--el-text-color-placeholder);
}
</style>
