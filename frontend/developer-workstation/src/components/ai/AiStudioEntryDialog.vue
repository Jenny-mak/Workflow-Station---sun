<template>
  <el-dialog
    :model-value="visible"
    class="ai-studio-entry-dialog"
    width="min(1000px, 94vw)"
    align-center
    @update:model-value="emit('update:visible', $event)"
  >
    <template #header>
      <div class="entry-header">
        <h2 class="entry-header__title">
          {{ t('ai.studio.title') }}
          <DesignerHelpLink
            path="/ai-studio"
            :aria-label="t('ai.studio.guideLinkAria')"
            test-id="ai-studio-guide-link"
          />
        </h2>
        <p class="entry-header__subtitle">
          {{ t('ai.studio.subtitle') }}
        </p>
      </div>
    </template>

    <div
      class="mode-cards"
      role="radiogroup"
      :aria-label="t('ai.studio.title')"
    >
      <div
        class="mode-card"
        :class="{ 'is-selected': mode === 'new' }"
        role="radio"
        :aria-checked="mode === 'new'"
        tabindex="0"
        @click="mode = 'new'"
        @keydown.enter.prevent="mode = 'new'"
        @keydown.space.prevent="mode = 'new'"
      >
        <span
          class="mode-card__radio"
          :class="{ 'is-checked': mode === 'new' }"
        />
        <span class="mode-card__badge">{{ t('ai.studio.recommended') }}</span>
        <div class="mode-card__body">
          <span class="mode-card__icon-tile mode-card__icon-tile--new">
            <el-icon :size="24">
              <MagicStick />
            </el-icon>
          </span>
          <div>
            <div class="mode-card__title">
              {{ t('ai.studio.newDesign') }}
            </div>
            <div class="mode-card__desc">
              {{ t('ai.studio.newDesignDesc') }}
            </div>
          </div>
        </div>
      </div>

      <div
        class="mode-card"
        :class="{ 'is-selected': mode === 'continue', 'is-disabled': !draft }"
        role="radio"
        :aria-checked="mode === 'continue'"
        :aria-disabled="!draft"
        :tabindex="draft ? 0 : -1"
        @click="draft && (mode = 'continue')"
        @keydown.enter.prevent="draft && (mode = 'continue')"
        @keydown.space.prevent="draft && (mode = 'continue')"
      >
        <span
          class="mode-card__radio"
          :class="{ 'is-checked': mode === 'continue' }"
        />
        <div class="mode-card__body">
          <span class="mode-card__icon-tile">
            <el-icon :size="24">
              <RefreshLeft />
            </el-icon>
          </span>
          <div>
            <div class="mode-card__title">
              {{ t('ai.studio.continueDraft') }}
            </div>
            <div class="mode-card__desc">
              {{
                draft
                  ? t('ai.studio.continueDraftDesc', {
                    name: draft.name,
                    phase: aiStudioPhaseLabel(t, draft.phase)
                  })
                  : t('ai.studio.noDraft')
              }}
            </div>
          </div>
        </div>
      </div>
      <div
        class="mode-card"
        :class="{ 'is-selected': mode === 'generate', 'is-disabled': !canGenerate }"
        role="radio"
        :aria-checked="mode === 'generate'"
        :aria-disabled="!canGenerate"
        :tabindex="canGenerate ? 0 : -1"
        data-testid="ai-studio-mode-generate"
        @click="canGenerate && (mode = 'generate')"
        @keydown.enter.prevent="canGenerate && (mode = 'generate')"
        @keydown.space.prevent="canGenerate && (mode = 'generate')"
      >
        <span
          class="mode-card__radio"
          :class="{ 'is-checked': mode === 'generate' }"
        />
        <div class="mode-card__body">
          <span class="mode-card__icon-tile mode-card__icon-tile--generate">
            <el-icon :size="24">
              <Lightning />
            </el-icon>
          </span>
          <div>
            <div class="mode-card__title">
              {{ t('ai.studio.oneClick.title') }}
            </div>
            <div class="mode-card__desc">
              {{ canGenerate ? t('ai.studio.oneClick.desc') : t('ai.studio.oneClick.readOnly') }}
            </div>
          </div>
        </div>
      </div>
    </div>

    <template v-if="mode === 'generate'">
      <div class="guide-title">
        {{ t('ai.studio.oneClick.inputTitle') }}
      </div>
      <el-input
        v-model="requirements"
        type="textarea"
        class="generate-input"
        :rows="7"
        :maxlength="ONE_CLICK_MAX_CHARS"
        show-word-limit
        resize="none"
        :placeholder="t('ai.studio.oneClick.placeholder')"
        data-testid="ai-studio-one-click-input"
      />
      <ul class="generate-notes">
        <li>
          {{ hasRequirementsDocument
            ? t('ai.studio.oneClick.noteDocsPresent')
            : t('ai.studio.oneClick.noteDocsAbsent') }}
        </li>
        <li>{{ t('ai.studio.oneClick.noteScope') }}</li>
        <li>{{ t('ai.studio.oneClick.noteDuration') }}</li>
      </ul>
    </template>

    <div
      v-if="mode !== 'generate'"
      class="guide-title"
    >
      {{ t('ai.studio.guideTitle') }}
    </div>
    <!-- 引导条画成迷你流程链：编号节点 + 连接线，首节点细环、Review 粗环，
         呼应 BPMN 开始/结束事件——步骤即流程，这是工作流平台自己的语言 -->
    <div
      v-if="mode !== 'generate'"
      class="guide-steps"
    >
      <div
        v-for="(phase, idx) in AI_STUDIO_PHASES"
        :key="phase"
        class="guide-step"
        :style="{ animationDelay: `${idx * 45}ms` }"
      >
        <span class="guide-step__node">{{ idx + 1 }}</span>
        <span class="guide-step__label">{{ aiStudioPhaseLabel(t, phase) }}</span>
      </div>
    </div>

    <template #footer>
      <div class="entry-footer">
        <div class="entry-footer__note">
          <el-icon><Lock /></el-icon>
          <span>{{ t('ai.studio.overwriteNote') }}</span>
        </div>
        <div class="entry-footer__actions">
          <el-button @click="emit('update:visible', false)">
            {{ t('common.cancel') }}
          </el-button>
          <el-button
            type="primary"
            :loading="submitting"
            :disabled="mode === 'generate' && !canSubmitGenerate"
            data-testid="ai-studio-entry-confirm"
            @click="handleConfirm"
          >
            {{ mode === 'generate' ? t('ai.studio.oneClick.button') : t('ai.studio.openButton') }}
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MagicStick, RefreshLeft, Lock, Lightning } from '@element-plus/icons-vue'
import {
  AI_STUDIO_ONE_CLICK_PHASE,
  AI_STUDIO_PHASES,
  aiStudioPhaseLabel,
  loadAiStudioDraft,
  saveAiStudioPendingProposal,
  type AiStudioDraft,
  type AiStudioEntryMode,
  type AiStudioOpenPayload,
  type AiStudioPhase
} from '@/utils/aiStudioDraft'
import { aiStudioThreadApi } from '@/api/aiStudioThread'
import DesignerHelpLink from '@/components/designer/DesignerHelpLink.vue'
import { aiGenerationApi } from '@/api/aiGeneration'
import { functionUnitDocumentApi } from '@/api/functionUnitDocument'
import { resolveUserFacingHttpMessage } from '@/utils/httpErrorMessage'

/** 与后端 AiStudioOneClickRequest.requirements 的 @Size 一致 */
const ONE_CLICK_MAX_CHARS = 4000

const props = defineProps<{
  visible: boolean
  functionUnitId: number
  /** 本浏览器没有草稿、但团队已在 AI Studio 里推进过时，用它当草稿名 */
  functionUnitName?: string
  /** 只读成员不能一键生成（会改写整个设计）；引导式工作台本身对只读成员仍可打开 */
  canGenerate?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'open', payload: AiStudioOpenPayload): void
}>()

const { t } = useI18n()

const mode = ref<AiStudioEntryMode>('new')
const draft = ref<AiStudioDraft | null>(null)
const requirements = ref('')
const submitting = ref(false)
/** 已有 Requirements 文档时描述可以留空——文档本身就是输入 */
const hasRequirementsDocument = ref(false)
const canSubmitGenerate = computed(() => !!requirements.value.trim() || hasRequirementsDocument.value)

// 每次打开重新读草稿：工作台可能在两次打开之间更新过 localStorage。
// 没有草稿时选中态强制回到 new，避免 continue 卡片禁用后仍处于选中。
watch(
  () => props.visible,
  (visible) => {
    if (!visible) return
    draft.value = loadAiStudioDraft(props.functionUnitId)
    if (!draft.value && mode.value === 'continue') mode.value = 'new'
    if (!props.canGenerate && mode.value === 'generate') mode.value = 'new'
    void loadSharedDraft()
    void loadRequirementsDocument()
  },
  { immediate: true }
)

/**
 * AI Studio 进度按功能单元共享：本浏览器没有草稿，但队友已经确认过阶段或在线程里讨论过时，
 * 同样点亮"继续"，停在第一个未确认的阶段。后端不可用时维持本地判断。
 */
async function loadSharedDraft() {
  if (draft.value) return
  const functionUnitId = props.functionUnitId
  try {
    const { data } = await aiStudioThreadApi.getState(functionUnitId)
    if (draft.value || !props.visible || functionUnitId !== props.functionUnitId) return
    const completed = (data.completedPhases ?? [])
      .filter((p): p is AiStudioPhase => (AI_STUDIO_PHASES as readonly string[]).includes(p))
    const hasMessages = Object.values(data.messageCounts ?? {}).some(n => n > 0)
    if (!completed.length && !hasMessages) return
    draft.value = {
      name: props.functionUnitName || `#${functionUnitId}`,
      phase: AI_STUDIO_PHASES.find(p => !completed.includes(p)) ?? AI_STUDIO_PHASES[AI_STUDIO_PHASES.length - 1],
      completedPhases: completed,
      updatedAt: data.updatedAt ?? undefined
    }
  } catch (e) {
    console.warn('[ai-studio] shared progress unavailable for the entry dialog', e)
  }
}

async function loadRequirementsDocument() {
  const functionUnitId = props.functionUnitId
  try {
    const { data } = await functionUnitDocumentApi.current(functionUnitId)
    if (functionUnitId === props.functionUnitId) {
      hasRequirementsDocument.value = !!data.REQUIREMENTS?.content?.trim()
    }
  } catch (e) {
    // 查不到就当没有：只影响"描述可否留空"，后端仍会按真实状态校验
    console.warn('[ai-studio] requirements document lookup failed for the entry dialog', e)
    hasRequirementsDocument.value = false
  }
}

/**
 * 一键生成：确认（整套设计会被替换）→ 提交作业 → 记下待办作业 → 进工作台接着等。
 * 作业在这里提交而不是进工作台后再提交：开关没开、缺令牌、无权限这类错误当场就能看到，不用白跳一次页面。
 */
async function startOneClick() {
  if (!canSubmitGenerate.value || submitting.value) return
  try {
    await ElMessageBox.confirm(
      t('ai.studio.oneClick.confirmMsg'),
      t('ai.studio.oneClick.confirmTitle'),
      { type: 'warning', confirmButtonText: t('ai.studio.oneClick.button') }
    )
  } catch {
    return
  }
  submitting.value = true
  try {
    const { data: job } = await aiGenerationApi.studioStartOneClick({
      functionUnitId: props.functionUnitId,
      requirements: requirements.value.trim()
    })
    saveAiStudioPendingProposal(props.functionUnitId, {
      jobId: job.jobId,
      phase: AI_STUDIO_ONE_CLICK_PHASE,
      submittedAt: Date.now()
    })
    requirements.value = ''
    emit('open', { mode: 'generate', draft: null })
    emit('update:visible', false)
  } catch (e) {
    ElMessage.error({ message: resolveUserFacingHttpMessage(e, t), duration: 8000, showClose: true })
  } finally {
    submitting.value = false
  }
}

function handleConfirm() {
  if (mode.value === 'generate') {
    void startOneClick()
    return
  }
  emit('open', {
    mode: mode.value,
    draft: mode.value === 'continue' ? draft.value : null
  })
  emit('update:visible', false)
}
</script>

<style lang="scss" scoped>
.entry-header {
  text-align: center;
  padding-top: 12px;

  &__title {
    margin: 0;
    font-size: 26px;
    font-weight: 650;
    letter-spacing: -0.4px;
    color: var(--el-text-color-primary);
  }

  &__subtitle {
    margin: 10px 0 0;
    font-size: 14px;
    color: var(--el-text-color-secondary);
  }
}

.mode-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-top: 10px;
}

.mode-card {
  position: relative;
  border: 1px solid var(--el-border-color);
  border-radius: 12px;
  padding: 46px 22px 24px;
  cursor: pointer;
  transition: border-color 0.2s, background-color 0.2s, box-shadow 0.2s, transform 0.2s;
  outline: none;

  &:hover:not(.is-disabled),
  &:focus-visible:not(.is-disabled) {
    border-color: var(--el-color-primary-light-5);
    transform: translateY(-1px);
  }

  &:focus-visible {
    box-shadow: 0 0 0 2px var(--el-color-primary-light-7);
  }

  &.is-selected {
    border-color: var(--el-color-primary);
    background: linear-gradient(180deg, var(--el-color-primary-light-9), rgba(255, 255, 255, 0) 90%);
    box-shadow: 0 6px 18px rgba(219, 0, 17, 0.07);
  }

  &.is-disabled {
    cursor: not-allowed;
    opacity: 0.55;
  }

  &__radio {
    position: absolute;
    top: 16px;
    left: 16px;
    width: 18px;
    height: 18px;
    border: 2px solid var(--el-border-color);
    border-radius: 50%;
    background-color: #fff;
    transition: border-color 0.2s;

    &.is-checked {
      border-color: var(--el-color-primary);

      &::after {
        content: '';
        position: absolute;
        inset: 3px;
        border-radius: 50%;
        background-color: var(--el-color-primary);
      }
    }
  }

  &__badge {
    position: absolute;
    top: 14px;
    right: 14px;
    padding: 2px 10px;
    border: 1px solid #f3d19e;
    border-radius: 999px;
    background-color: #fdf6ec;
    color: #d48806;
    font-size: 12px;
  }

  &__body {
    display: flex;
    align-items: flex-start;
    gap: 16px;
  }

  &__icon-tile {
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 46px;
    height: 46px;
    border-radius: 12px;
    background-color: var(--el-fill-color-light);
    color: var(--el-text-color-secondary);

    &--generate {
      background-color: var(--el-color-primary-light-9);
      color: var(--el-color-primary);
    }

    &--new {
      background-color: #fdf4e5;
      color: #e6a23c;

      .el-icon {
        animation: sparkle-pulse 2.8s ease-in-out infinite;
      }
    }
  }

  &__title {
    font-size: 17px;
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  &__desc {
    margin-top: 6px;
    font-size: 13px;
    line-height: 1.5;
    color: var(--el-text-color-secondary);
  }
}

@keyframes sparkle-pulse {
  0%,
  100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.15);
  }
}

// eyebrow：文字 + 向右延伸的细线，把引导区从卡片区里轻轻隔开
.guide-title {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 30px;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--el-text-color-regular);

  &::after {
    content: '';
    flex: 1;
    height: 1px;
    background-color: var(--el-border-color-lighter);
  }
}

.generate-input {
  margin-top: 16px;
}

.generate-notes {
  margin: 12px 0 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

// 迷你流程链：6 列网格，节点间以连接线相连；行首节点不画进线
.guide-steps {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  row-gap: 20px;
  margin-top: 18px;
  padding: 4px 0 2px;
}

.guide-step {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  text-align: center;
  animation: step-in 0.35s ease-out backwards;

  // 连接线：从上一节点圆心画到本节点圆心，两端各让出节点半径
  &::before {
    content: '';
    position: absolute;
    top: 17px;
    left: calc(-50% + 24px);
    right: calc(50% + 24px);
    height: 2px;
    background-color: var(--el-color-primary-light-8);
  }

  &:first-child::before,
  &:nth-child(6n + 1)::before {
    display: none;
  }

  &__node {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    border-radius: 50%;
    border: 2px solid var(--el-color-primary-light-5);
    background-color: #fff;
    color: var(--el-color-primary);
    font-size: 13px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }

  // BPMN 语义：首节点细环 = 开始事件
  &:first-child &__node {
    border-width: 1.5px;
  }

  // 末节点（Review）粗环 = 结束事件
  &:last-child &__node {
    border: 3px solid var(--el-color-primary);
  }

  &__label {
    max-width: 100%;
    padding: 0 6px;
    font-size: 12px;
    line-height: 1.35;
    color: var(--el-text-color-regular);
  }
}

@keyframes step-in {
  from {
    opacity: 0;
    transform: translateY(6px) scale(0.92);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.entry-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;

  &__note {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    text-align: left;
  }
}

@media (max-width: 900px) {
  .mode-cards {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {

  .guide-steps {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .guide-step {
    &::before {
      display: block;
    }

    &:first-child::before,
    &:nth-child(3n + 1)::before {
      display: none;
    }
  }
}

@media (prefers-reduced-motion: reduce) {
  .guide-step {
    animation: none;
  }

  .mode-card {
    transition: none;

    &:hover:not(.is-disabled),
    &:focus-visible:not(.is-disabled) {
      transform: none;
    }
  }

  .mode-card__icon-tile--new .el-icon {
    animation: none;
  }
}
</style>
