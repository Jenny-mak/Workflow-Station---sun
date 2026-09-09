<template>
  <div class="owner-field lookup-field readonly">
    <div v-if="configError" class="owner-config-error">{{ t('owner.invalidConfig') }}</div>
    <div
      v-else-if="chips.length"
      class="lookup-selected-wrapper is-readonly"
    >
      <span
        v-for="(chip, index) in chips"
        :key="`${chip.kind}-${index}-${chip.label}`"
        :class="chip.kind === 'step' ? 'owner-step-text' : 'lookup-selected-tag'"
      >
        <OwnerChip
          v-if="chip.kind !== 'step'"
          :label="chip.label"
          :size="22"
        />
        <span v-else>{{ chip.label }}</span>
      </span>
    </div>
    <el-input
      v-else
      model-value=""
      :placeholder="t('owner.empty')"
      class="lookup-input"
      disabled
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import OwnerChip from './OwnerChip.vue'
import { ownerChips, parseOwnerSource } from '@/composables/owner/useOwnerFieldModel'

const props = defineProps<{
  modelValue?: string | null
  ownerConfig?: string
  display?: string
  readonly?: boolean
  disabled?: boolean
}>()

void props.readonly
void props.disabled

const { t } = useI18n()
const configError = ref(false)
const source = computed(() => parseOwnerSource(props.ownerConfig, configError))
void source

const chips = computed(() => ownerChips(props.modelValue, props.display))
</script>

<style scoped>
.owner-field {
  width: 100%;
}

.lookup-selected-wrapper {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  min-height: 32px;
  padding: 4px 8px;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: var(--ws-radius-input, 8px);
  background: #fff;
}

.lookup-selected-wrapper.is-readonly {
  background: var(--el-disabled-bg-color, #f5f7fa);
  border-color: var(--el-disabled-border-color, #e4e7ed);
  cursor: not-allowed;
  pointer-events: none;
}

.lookup-selected-tag {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  height: 24px;
  padding: 0 8px 0 4px;
  border-radius: 4px;
  background: #f0f2f5;
  font-size: 13px;
  color: #909399;
  line-height: 24px;
}

.owner-step-text {
  font-size: 13px;
  line-height: 24px;
  color: var(--el-text-color-regular, #606266);
}

.owner-config-error {
  color: var(--el-color-danger);
  font-size: 12px;
}

.owner-field.readonly :deep(.el-input.is-disabled .el-input__wrapper) {
  background-color: var(--el-disabled-bg-color, #f5f7fa);
  box-shadow: 0 0 0 1px var(--el-disabled-border-color, #e4e7ed) inset;
}
</style>
