<template>
  <div class="action-color-field">
    <el-input
      :model-value="hexDigits"
      class="hex-input"
      maxlength="6"
      :placeholder="t('action.buttonColorPlaceholder')"
      @update:model-value="handleHexInput"
      @blur="handleBlur"
    >
      <template #prepend>
        #
      </template>
    </el-input>
    <el-color-picker
      :model-value="pickerValue"
      color-format="hex"
      :predefine="PREDEFINED_COLORS"
      @update:model-value="handlePick"
    />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

/**
 * Action 按钮颜色输入：`#` 前缀的十六进制输入框 + 调色盘按钮（点击弹出面板）。
 * 值以 `#RRGGBB` 形式存入 dw_action_definitions.button_color（VARCHAR(20)），
 * User Portal 直接用它渲染按钮底色。
 */
const props = defineProps<{ modelValue?: string | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const { t } = useI18n()

const PREDEFINED_COLORS = [
  '#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#909399',
  '#1F5C4A', '#7B4EA8', '#0F5FA6', '#C0392B', '#2C3E50'
]

/** 完整合法值：`#RGB` / `#RRGGBB`，只有它才喂给调色盘并最终落库。 */
const FULL_HEX = /^#?([0-9a-fA-F]{6}|[0-9a-fA-F]{3})$/
/**
 * 输入过程中的中间值（如刚敲了 `7B`）。输入框是受控的，若只认完整值，
 * 每敲一个字符都会被回显清空——必须按「部分合法」回显。
 */
const PARTIAL_HEX = /^#?[0-9a-fA-F]{0,6}$/

function digitsOf(value?: string | null): string {
  return String(value || '').trim().replace(/^#/, '').toUpperCase()
}

/**
 * 输入框只显示十六进制数字部分。历史遗留的具名颜色（如 `primary`）不是十六进制，
 * 显示为空且挂载时不发事件——未编辑就不会被静默改写。
 */
const hexDigits = computed(() => {
  const raw = String(props.modelValue || '').trim()
  return PARTIAL_HEX.test(raw) ? digitsOf(raw) : ''
})

const pickerValue = computed(() => {
  const raw = String(props.modelValue || '').trim()
  return FULL_HEX.test(raw) ? `#${digitsOf(raw)}` : null
})

function handleHexInput(value: string) {
  const digits = String(value || '').replace(/[^0-9a-fA-F]/g, '').slice(0, 6).toUpperCase()
  emit('update:modelValue', digits ? `#${digits}` : '')
}

/** 离开输入框时把没敲完的半截值（如 `#7B4E`）清掉，避免存下 Portal 认不出的颜色。 */
function handleBlur() {
  const raw = String(props.modelValue || '').trim()
  if (raw && PARTIAL_HEX.test(raw) && !FULL_HEX.test(raw)) {
    emit('update:modelValue', '')
  }
}

function handlePick(value: string | null) {
  emit('update:modelValue', value ? value.toUpperCase() : '')
}
</script>

<style lang="scss" scoped>
.action-color-field {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;

  .hex-input {
    width: 160px;
  }
}
</style>
