<template>
  <span class="owner-chip" :class="[`is-${kind}`, { 'is-empty': empty }]">
    <span class="owner-chip-head" aria-hidden="true">
      <el-icon v-if="empty" :size="iconSize">
        <UserFilled />
      </el-icon>
      <span v-else class="owner-chip-initial">{{ initial }}</span>
    </span>
    <span v-if="label" class="owner-chip-name">{{ label }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  kind?: 'user'
  label?: string
  empty?: boolean
  size?: number
}>(), {
  kind: 'user',
  label: '',
  empty: false,
  size: 22,
})

const iconSize = computed(() => Math.max(12, Math.round(props.size * 0.55)))
const headSize = computed(() => `${props.size}px`)
const initial = computed(() => {
  const t = (props.label || '').trim()
  return t ? t.charAt(0).toUpperCase() : ''
})
</script>

<style scoped>
.owner-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  max-width: 100%;
}

.owner-chip-head {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: v-bind(headSize);
  height: v-bind(headSize);
  border-radius: 50%;
  background: var(--el-color-primary, #db0011);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  overflow: hidden;
}

.is-empty .owner-chip-head {
  background: var(--el-fill-color-light, #f5f7fa);
  color: var(--el-text-color-secondary, #909399);
  border: 1px solid var(--el-border-color, #dcdfe6);
}

.owner-chip-initial {
  user-select: none;
}

.owner-chip-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: #909399;
  font-weight: 400;
}
</style>
