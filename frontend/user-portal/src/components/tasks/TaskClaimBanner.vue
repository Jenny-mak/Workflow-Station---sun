<template>
  <div
    v-if="task.claimPoolTask"
    class="section claim-banner"
    data-test="task-claim-banner"
  >
    <el-alert
      :type="alertType"
      :closable="false"
      show-icon
    >
      <template #title>
        <span data-test="task-claim-banner-title">{{ title }}</span>
      </template>
      <template #default>
        <div class="claim-banner__body">
          <span class="claim-banner__hint">{{ hint }}</span>
          <el-button
            v-if="task.claimable"
            type="primary"
            size="small"
            :loading="submitting"
            data-test="task-claim-btn"
            @click="emit('claim')"
          >
            {{ t('task.claim') }}
          </el-button>
          <el-button
            v-else-if="task.claimedByCurrentUser"
            size="small"
            :loading="submitting"
            data-test="task-unclaim-btn"
            @click="emit('unclaim')"
          >
            {{ t('task.unclaim') }}
          </el-button>
          <el-button
            v-else-if="task.canForceUnclaim"
            type="warning"
            size="small"
            :loading="submitting"
            data-test="task-force-unclaim-btn"
            @click="emit('force-unclaim')"
          >
            {{ t('task.forceUnclaim') }}
          </el-button>
          <el-button
            v-if="task.canReassign"
            type="primary"
            plain
            size="small"
            :loading="submitting"
            data-test="task-reassign-btn"
            @click="emit('reassign')"
          >
            {{ t('task.reassign') }}
          </el-button>
        </div>
      </template>
    </el-alert>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

interface ClaimBannerTask {
  claimPoolTask?: boolean
  claimable?: boolean
  claimedByCurrentUser?: boolean
  canForceUnclaim?: boolean
  canReassign?: boolean
  assignee?: string
  assigneeName?: string
}

const props = defineProps<{
  task: ClaimBannerTask
  submitting: boolean
}>()

const emit = defineEmits<{
  (e: 'claim'): void
  (e: 'unclaim'): void
  (e: 'force-unclaim'): void
  (e: 'reassign'): void
}>()

const { t } = useI18n()

const alertType = computed(() => {
  if (props.task.claimedByCurrentUser) return 'success'
  return props.task.claimable ? 'warning' : 'info'
})

const title = computed(() => {
  if (props.task.claimedByCurrentUser) return t('task.claimHeldByYou')
  if (props.task.claimable) return t('task.claimAvailable')
  return t('task.claimHeldByOther', { user: props.task.assigneeName || props.task.assignee || '-' })
})

const hint = computed(() => {
  if (props.task.claimedByCurrentUser) return t('task.claimHeldByYouHint')
  if (props.task.claimable) return t('task.claimAvailableHint')
  if (props.task.canForceUnclaim) return t('task.forceUnclaimHint')
  if (props.task.canReassign) return t('task.reassignHint')
  return t('task.claimHeldByOtherHint')
})
</script>

<style lang="scss" scoped>
.claim-banner {
  background: transparent;
  border: none;

  &__body {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
  }

  &__hint {
    color: var(--text-secondary);
  }
}
</style>
