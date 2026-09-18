<template>
  <!-- Hermes Master：常驻 DW 每个页面的助手机器人。挂在 App.vue，路由切换不卸载，对话不丢。 -->
  <div class="hermes-master">
    <div
      ref="robotEl"
      class="hm-robot"
      :class="{ 'is-dragging': pose === 'dragged' }"
      :style="robotStyle"
      role="button"
      tabindex="0"
      :aria-label="t('hermesMaster.ariaLabel')"
      :aria-expanded="chatOpen"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
      @pointerenter="onPointerEnter"
      @pointerleave="onPointerLeave"
      @keydown.enter.prevent="toggleChat"
      @keydown.space.prevent="toggleChat"
      @dragstart.prevent
    >
      <HermesMasterFigure
        :pose="pose"
        :airborne="y > 0"
      />
    </div>

    <Transition name="hm-bubble">
      <HermesMasterChatBubble
        v-if="chatOpen"
        :style="bubbleStyle"
        :messages="messages"
        :loading="loading"
        :on-function-unit="functionUnitId !== undefined"
        :tail-left="bubble.tailLeft"
        @send="onSend"
        @stop="stop"
        @clear="clear"
        @rest="onRest"
        @close="toggleChat"
      />
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import HermesMasterFigure from './HermesMasterFigure.vue'
import HermesMasterChatBubble from './HermesMasterChatBubble.vue'
import { useHermesMasterBehavior } from '@/composables/hermesMaster/useHermesMasterBehavior'
import { useHermesMasterChat } from '@/composables/hermesMaster/useHermesMasterChat'
import { HM_HEIGHT, HM_WIDTH, placeBubble } from '@/utils/hermesMasterBehavior'

const BUBBLE_WIDTH = 348
const BUBBLE_HEIGHT = 450
/** 回复到达后张嘴说话的时长 */
const TALK_MS = 1800

const { t } = useI18n()
const route = useRoute()

const robotEl = ref<HTMLElement | null>(null)
const chatOpen = ref(false)
const viewport = ref({ width: window.innerWidth, height: window.innerHeight })

/** 功能单元编辑页与 AI Studio 的路由都带 :id */
const functionUnitId = computed(() => {
  const id = Number(route.params.id)
  return route.path.startsWith('/function-units/') && Number.isInteger(id) && id > 0 ? id : undefined
})

const { messages, loading, send, stop, clear } = useHermesMasterChat({
  context: () => ({
    functionUnitId: functionUnitId.value,
    page: typeof route.name === 'string' ? route.name : undefined
  }),
  fallbackError: () => t('hermesMaster.error')
})

const {
  pose, x, y,
  onPointerDown, onPointerMove, onPointerUp, onPointerEnter, onPointerLeave,
  settle, talk, rest
} = useHermesMasterBehavior(robotEl, {
  chatPose: () => (chatOpen.value ? (loading.value ? 'think' : 'idle') : null),
  onClick: toggleChat
})

function toggleChat() {
  chatOpen.value = !chatOpen.value
  settle()
}

async function onSend(text: string) {
  const pending = send(text)
  settle()
  const replied = await pending
  if (replied && chatOpen.value) talk(TALK_MS)
  else settle()
}

function onRest() {
  chatOpen.value = false
  rest()
}

const robotStyle = computed(() => ({
  width: `${HM_WIDTH}px`,
  height: `${HM_HEIGHT}px`,
  transform: `translate3d(${x.value}px, ${-y.value}px, 0)`
}))

const bubbleSize = computed(() => ({
  width: Math.min(BUBBLE_WIDTH, viewport.value.width - 16),
  height: Math.min(BUBBLE_HEIGHT, viewport.value.height - HM_HEIGHT - 28)
}))

const bubble = computed(() => placeBubble({ x: x.value, y: y.value }, bubbleSize.value, viewport.value))

const bubbleStyle = computed(() => ({
  left: `${bubble.value.left}px`,
  bottom: `${bubble.value.bottom}px`,
  width: `${bubbleSize.value.width}px`,
  height: `${bubbleSize.value.height}px`
}))

function onResize() {
  viewport.value = { width: window.innerWidth, height: window.innerHeight }
}

// 换了功能单元，上一段对话里的设计建议就不再对得上
watch(functionUnitId, (next, previous) => {
  if (next !== previous && messages.value.length) clear()
})

onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))
</script>

<style scoped lang="scss">
// 低于 Element Plus 弹层的起始 z-index（2000）：对话框 / 抽屉打开时不盖在它们上面
$hm-z: 1990;

.hm-robot {
  position: fixed;
  left: 0;
  bottom: 0;
  z-index: $hm-z;
  cursor: grab;
  touch-action: none;
  user-select: none;
  -webkit-tap-highlight-color: transparent;
  outline: none;
  will-change: transform;

  &.is-dragging {
    cursor: grabbing;
  }

  &:focus-visible {
    outline: 2px solid #db0011;
    outline-offset: 2px;
    border-radius: 12px;
  }
}

:deep(.hm-chat) {
  z-index: $hm-z + 1;
}

.hm-bubble-enter-active,
.hm-bubble-leave-active {
  transition: opacity 0.16s ease, transform 0.16s ease;
  transform-origin: bottom center;
}

.hm-bubble-enter-from,
.hm-bubble-leave-to {
  opacity: 0;
  transform: translateY(8px) scale(0.96);
}
</style>
