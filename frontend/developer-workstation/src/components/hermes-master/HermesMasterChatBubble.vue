<template>
  <section
    class="hm-chat"
    role="dialog"
    :aria-label="t('hermesMaster.name')"
    @keydown.esc.stop="emit('close')"
  >
    <header class="hm-chat__header">
      <span class="hm-chat__badge">{{ t('hermesMaster.shortName') }}</span>
      <div class="hm-chat__title">
        <strong>{{ t('hermesMaster.name') }}</strong>
        <span>{{ onFunctionUnit ? t('hermesMaster.contextFunctionUnit') : t('hermesMaster.contextGeneral') }}</span>
      </div>
      <button
        v-if="messages.length"
        type="button"
        class="hm-chat__icon"
        :title="t('hermesMaster.clear')"
        :aria-label="t('hermesMaster.clear')"
        @click="emit('clear')"
      >
        <el-icon><RefreshLeft /></el-icon>
      </button>
      <button
        type="button"
        class="hm-chat__icon"
        :title="t('hermesMaster.rest')"
        :aria-label="t('hermesMaster.rest')"
        @click="emit('rest')"
      >
        <el-icon><MoonNight /></el-icon>
      </button>
      <button
        type="button"
        class="hm-chat__icon"
        :title="t('common.close')"
        :aria-label="t('common.close')"
        @click="emit('close')"
      >
        <el-icon><Close /></el-icon>
      </button>
    </header>

    <div
      ref="scroller"
      class="hm-chat__messages"
    >
      <div class="hm-chat__msg is-assistant">
        {{ t('hermesMaster.greeting') }}
      </div>
      <div
        v-if="!messages.length"
        class="hm-chat__suggestions"
      >
        <button
          v-for="key in suggestions"
          :key="key"
          type="button"
          @click="emit('send', t(`hermesMaster.suggestions.${key}`))"
        >
          {{ t(`hermesMaster.suggestions.${key}`) }}
        </button>
      </div>
      <div
        v-for="m in messages"
        :key="m.id"
        class="hm-chat__msg"
        :class="[m.role === 'USER' ? 'is-user' : 'is-assistant', { 'is-error': m.error }]"
      >
        <MarkdownRenderer
          v-if="m.role === 'ASSISTANT' && !m.error"
          :content="m.content"
        />
        <template v-else>
          {{ m.content }}
        </template>
      </div>
      <div
        v-if="loading"
        class="hm-chat__msg is-assistant is-thinking"
      >
        <span>{{ t('hermesMaster.thinking') }}</span>
        <i /><i /><i />
      </div>
    </div>

    <form
      class="hm-chat__composer"
      @submit.prevent="submit"
    >
      <textarea
        ref="input"
        v-model="draft"
        rows="1"
        :maxlength="HM_MAX_MESSAGE_LENGTH"
        :placeholder="t('hermesMaster.placeholder')"
        :aria-label="t('hermesMaster.placeholder')"
        @keydown.enter.exact.prevent="submit"
      />
      <button
        v-if="loading"
        type="button"
        class="hm-chat__send is-stop"
        :title="t('hermesMaster.stop')"
        :aria-label="t('hermesMaster.stop')"
        @click="emit('stop')"
      >
        <el-icon><VideoPause /></el-icon>
      </button>
      <button
        v-else
        type="submit"
        class="hm-chat__send"
        :disabled="!draft.trim()"
        :title="t('hermesMaster.send')"
        :aria-label="t('hermesMaster.send')"
      >
        <el-icon><Promotion /></el-icon>
      </button>
    </form>

    <span
      class="hm-chat__tail"
      :style="{ left: `${tailLeft}px` }"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Close, MoonNight, Promotion, RefreshLeft, VideoPause } from '@element-plus/icons-vue'
import MarkdownRenderer from '@/components/ai/MarkdownRenderer.vue'
import { HM_MAX_MESSAGE_LENGTH } from '@/composables/hermesMaster/useHermesMasterChat'
import type { HermesMasterMessage } from '@/types/hermesMaster'

const props = defineProps<{
  messages: HermesMasterMessage[]
  loading: boolean
  /** 当前页面属于某个功能单元：HM 能看到它的设计摘要 */
  onFunctionUnit: boolean
  tailLeft: number
}>()

const emit = defineEmits<{
  send: [text: string]
  stop: []
  clear: []
  rest: []
  close: []
}>()

const { t } = useI18n()

const draft = ref('')
const scroller = ref<HTMLElement | null>(null)
const input = ref<HTMLTextAreaElement | null>(null)

const suggestions = computed(() =>
  props.onFunctionUnit
    ? (['reviewDesign', 'nextStep', 'buildOrder'] as const)
    : (['whatCanDwDo', 'createFunctionUnit', 'buildOrder'] as const)
)

function submit() {
  const text = draft.value.trim()
  if (!text || props.loading) return
  draft.value = ''
  emit('send', text)
}

watch(
  () => [props.messages.length, props.loading],
  async () => {
    await nextTick()
    scroller.value?.scrollTo({ top: scroller.value.scrollHeight, behavior: 'smooth' })
  }
)

onMounted(() => input.value?.focus())
</script>

<style scoped lang="scss">
$hm-red: #db0011;
$hm-ink: #23282e;
$hm-hairline: #e7e9ed;

.hm-chat {
  position: fixed;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid $hm-hairline;
  border-radius: 14px;
  box-shadow: 0 14px 40px rgba(17, 24, 39, 0.18), 0 2px 6px rgba(17, 24, 39, 0.08);
  color: $hm-ink;
  font-size: 13px;
}

.hm-chat__header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 10px 10px 12px;
  border-bottom: 1px solid $hm-hairline;
}

.hm-chat__badge {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: $hm-red;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.hm-chat__title {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.25;

  span {
    color: #8a93a0;
    font-size: 11px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}

.hm-chat__icon {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #5b6470;
  cursor: pointer;

  &:hover {
    background: #eff1f4;
    color: $hm-ink;
  }
}

.hm-chat__messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: #f6f7f9;
}

.hm-chat__msg {
  max-width: 88%;
  padding: 8px 11px;
  border-radius: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;

  &.is-assistant {
    align-self: flex-start;
    background: #fff;
    border: 1px solid $hm-hairline;
    border-bottom-left-radius: 4px;
    // Markdown 渲染器自带段落间距，这里不再保留换行
    white-space: normal;

    :deep(.markdown-renderer > :first-child) {
      margin-top: 0;
    }

    :deep(.markdown-renderer > :last-child) {
      margin-bottom: 0;
    }

    :deep(ol),
    :deep(ul) {
      padding-left: 18px;
    }
  }

  &.is-user {
    align-self: flex-end;
    background: #2e343c;
    color: #fff;
    border-bottom-right-radius: 4px;
  }

  &.is-error {
    border-color: #f3c2c6;
    background: #fbebec;
    color: #b00010;
  }

  &.is-thinking {
    display: flex;
    align-items: center;
    gap: 4px;
    color: #5b6470;

    span {
      margin-right: 2px;
    }

    i {
      width: 4px;
      height: 4px;
      border-radius: 50%;
      background: currentColor;
      animation: hm-typing 1s ease-in-out infinite;

      &:nth-of-type(2) {
        animation-delay: 0.15s;
      }

      &:nth-of-type(3) {
        animation-delay: 0.3s;
      }
    }
  }
}

.hm-chat__suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;

  button {
    padding: 5px 10px;
    border: 1px solid $hm-hairline;
    border-radius: 999px;
    background: #fff;
    color: $hm-ink;
    font-size: 12px;
    cursor: pointer;
    text-align: left;

    &:hover {
      border-color: $hm-red;
      color: $hm-red;
    }
  }
}

.hm-chat__composer {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 10px;
  border-top: 1px solid $hm-hairline;

  textarea {
    flex: 1;
    min-height: 34px;
    max-height: 96px;
    padding: 7px 10px;
    border: 1px solid $hm-hairline;
    border-radius: 9px;
    font: inherit;
    color: inherit;
    resize: none;
    outline: none;
    field-sizing: content;

    &:focus {
      border-color: $hm-red;
    }
  }
}

.hm-chat__send {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 9px;
  background: $hm-red;
  color: #fff;
  font-size: 16px;
  cursor: pointer;

  &:hover:not(:disabled) {
    background: #b00010;
  }

  &:disabled {
    background: #d5d9df;
    cursor: not-allowed;
  }

  &.is-stop {
    background: #2e343c;
  }
}

.hm-chat__tail {
  position: absolute;
  bottom: -7px;
  width: 12px;
  height: 12px;
  margin-left: -6px;
  background: #fff;
  border-right: 1px solid $hm-hairline;
  border-bottom: 1px solid $hm-hairline;
  transform: rotate(45deg);
}

@keyframes hm-typing {
  0%, 100% { opacity: 0.25; transform: translateY(0); }
  50% { opacity: 1; transform: translateY(-2px); }
}
</style>
