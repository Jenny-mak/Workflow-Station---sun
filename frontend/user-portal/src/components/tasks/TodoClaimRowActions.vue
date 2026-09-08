<template>
  <span class="todo-claim-row-actions">
    <template v-if="task.claimable">
      <el-button
        type="primary"
        size="small"
        :loading="loading"
        data-test="todo-claim-btn"
        @click="$emit('claim', task)"
      >
        {{ t('task.claim') }}
      </el-button>
    </template>
    <el-button
      v-else-if="task.claimedByCurrentUser"
      size="small"
      :loading="loading"
      data-test="todo-unclaim-btn"
      @click="$emit('unclaim', task)"
    >
      {{ t('task.unclaim') }}
    </el-button>
    <el-button
      v-else-if="task.canForceUnclaim"
      type="warning"
      size="small"
      :loading="loading"
      data-test="todo-force-unclaim-btn"
      @click="$emit('force-unclaim', task)"
    >
      {{ t('task.forceUnclaim') }}
    </el-button>
    <span
      v-else-if="task.claimPoolTask && task.assignee"
      class="todo-held"
      data-test="todo-held"
    >{{ t('task.heldByOther') }}</span>
    <el-button
      v-if="task.canReassign"
      type="primary"
      plain
      size="small"
      :loading="loading"
      data-test="todo-reassign-btn"
      @click="$emit('reassign', task)"
    >
      {{ t('task.reassign') }}
    </el-button>
  </span>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { TaskInfo } from '@/api/task'

defineProps<{
  task: TaskInfo
  loading: boolean
}>()

defineEmits<{
  claim: [task: TaskInfo]
  unclaim: [task: TaskInfo]
  'force-unclaim': [task: TaskInfo]
  reassign: [task: TaskInfo]
}>()

const { t } = useI18n()
</script>

<style scoped>
.todo-claim-row-actions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.todo-held {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
