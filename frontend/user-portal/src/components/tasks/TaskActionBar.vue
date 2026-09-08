<template>
  <div
    v-if="!isCompletedTask && !claimLocked"
    class="section action-section"
  >
    <div class="action-buttons">
      <div class="left-actions">
        <el-button @click="$router.back()">
          {{ $t('task.backToList') }}
        </el-button>
      </div>
      <div class="right-actions">
        <el-button
          v-if="showImplicitSaveAction"
          type="primary"
          :loading="savingTaskForm"
          @click="$emit('save')"
        >
          {{ $t('common.save') }}
        </el-button>
        <!-- Show custom buttons when custom Actions are configured -->
        <template v-if="visibleActions.length > 0">
          <el-button
            v-for="action in visibleActions"
            :key="action.actionId"
            :type="resolveButtonType(action)"
            :style="resolveButtonStyle(action)"
            @click="$emit('customAction', action)"
          >
            <el-icon v-if="resolveIconName(action)">
              <component :is="getIconComponent(resolveIconName(action))" />
            </el-icon>
            {{ getActionLabel(action) }}
          </el-button>
        </template>
        <!-- Show default approval buttons when no custom Actions are configured -->
        <template v-else-if="actions === undefined || actions === null">
          <el-button
            type="success"
            @click="$emit('approve')"
          >
            <el-icon><Check /></el-icon> {{ $t('task.approve') }}
          </el-button>
          <el-button
            type="danger"
            @click="$emit('reject')"
          >
            <el-icon><Close /></el-icon> {{ $t('task.reject') }}
          </el-button>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Check, Close } from '@element-plus/icons-vue'
import type { Component } from 'vue'
import type { TaskActionInfo } from '@/api/task'
import { actionButtonStyle, isCustomButtonColor } from '@/utils/actionButtonColor'

/**
 * Delegate / Transfer / Urge are no longer hardcoded here: they are Action
 * definitions configured in the Developer Workstation Action Designer and bound
 * to the user task node in Process Design, exactly like every other Action.
 * These maps only supply the look the built-in buttons used to have, for Actions
 * whose designer-configured colour is unset (the Action Designer does not expose
 * an icon picker yet).
 */
const BUILT_IN_ICONS: Record<string, string> = {
  DELEGATE: 'user',
  TRANSFER: 'switch',
  URGE: 'bell'
}
type ElButtonType = '' | 'default' | 'text' | 'primary' | 'success' | 'warning' | 'info' | 'danger'

const BUILT_IN_BUTTON_TYPES: Record<string, ElButtonType> = {
  DELEGATE: '',
  TRANSFER: '',
  URGE: 'warning'
}

const props = defineProps<{
  isCompletedTask: boolean
  /** BU Role pool task the signed-in user does not hold: view only until they claim it. */
  claimLocked?: boolean
  showImplicitSaveAction: boolean
  savingTaskForm: boolean
  actions: TaskActionInfo[] | undefined | null
  canDelegate: boolean
  getButtonType: (color?: string) => ElButtonType
  getIconComponent: (iconName?: string) => Component
  getActionLabel: (action: TaskActionInfo) => string
}>()

defineEmits<{
  (e: 'save'): void
  (e: 'customAction', action: TaskActionInfo): void
  (e: 'approve'): void
  (e: 'reject'): void
}>()

function actionTypeOf(action: TaskActionInfo): string {
  return (action.actionType || '').trim().toUpperCase()
}

/** A DELEGATE action stays hidden while the task has no assignee to delegate from. */
const visibleActions = computed<TaskActionInfo[]>(() =>
  (props.actions || []).filter(
    action => actionTypeOf(action) !== 'DELEGATE' || props.canDelegate
  )
)

function resolveIconName(action: TaskActionInfo): string | undefined {
  return action.icon || BUILT_IN_ICONS[actionTypeOf(action)]
}

function resolveButtonType(action: TaskActionInfo): ElButtonType {
  // A designer-picked hex colour is applied as inline CSS variables instead of an
  // Element Plus semantic type, so the button renders exactly that colour.
  if (isCustomButtonColor(action.buttonColor)) return 'primary'
  if (action.buttonColor) return props.getButtonType(action.buttonColor)
  const builtIn = BUILT_IN_BUTTON_TYPES[actionTypeOf(action)]
  return builtIn === undefined ? props.getButtonType(undefined) : builtIn
}

function resolveButtonStyle(action: TaskActionInfo): Record<string, string> | undefined {
  return actionButtonStyle(action.buttonColor)
}
</script>

<style lang="scss" scoped>
.section {
  background: white;
  border-radius: 8px;
  border: 1px solid var(--border-color, #e4e7ed);
}

.action-section {
  position: sticky;
  bottom: 0;
  z-index: 10;
  padding: 16px 20px;
  border-top: 1px solid var(--border-color, #e4e7ed);
}

.action-buttons {
  display: flex;
  justify-content: space-between;
  align-items: center;

  .left-actions, .right-actions {
    display: flex;
    gap: 12px;
  }
}
</style>
