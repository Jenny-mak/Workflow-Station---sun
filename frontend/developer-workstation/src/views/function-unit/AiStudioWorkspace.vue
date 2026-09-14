<template>
  <div class="ai-studio">
    <!-- 左：阶段轨道 -->
    <aside class="rail">
      <div class="rail__header">
        <el-button
          text
          class="rail__exit"
          @click="exitStudio"
        >
          <el-icon><ArrowLeft /></el-icon>
          {{ t('ai.studio.workspace.exit') }}
        </el-button>
        <div class="rail__title">
          <el-icon
            class="rail__title-icon"
            :size="18"
          >
            <MagicStick />
          </el-icon>
          {{ t('ai.studio.entryButton') }}
        </div>
        <div class="rail__fu-name">
          {{ store.current?.name }}
        </div>
      </div>

      <div class="rail__phases">
        <button
          v-for="(phase, idx) in AI_STUDIO_PHASES"
          :key="phase"
          class="rail-phase"
          :class="{
            'is-current': phase === currentPhase,
            'is-done': completedPhases.includes(phase),
            'is-locked': !canEnterPhase(idx)
          }"
          :disabled="!canEnterPhase(idx)"
          @click="goToPhase(phase)"
        >
          <span class="rail-phase__node">
            <el-icon v-if="completedPhases.includes(phase)">
              <Check />
            </el-icon>
            <template v-else>{{ idx + 1 }}</template>
          </span>
          <span class="rail-phase__text">
            <span class="rail-phase__label">{{ aiStudioPhaseLabel(t, phase) }}</span>
            <span class="rail-phase__status">{{ phaseStatusText(phase) }}</span>
          </span>
        </button>
      </div>

      <div class="rail__footer">
        <el-icon><UploadFilled /></el-icon>
        <span>{{ t('ai.studio.workspace.draftSavedAt', { time: lastSavedAt || '—' }) }}</span>
      </div>
    </aside>

    <!-- 中：当前阶段设计区（复用既有设计器组件） -->
    <main class="stage">
      <div class="stage__header">
        <h2 class="stage__title">
          {{ aiStudioPhaseLabel(t, currentPhase) }}
        </h2>
        <p class="stage__desc">
          {{ phaseDesc(currentPhase) }}
        </p>
      </div>

      <div class="stage__body">
        <!-- Validation：收尾门禁。英雄区是放大的 BPMN 结束事件（粗环），与入口弹窗流程链同语言 -->
        <div
          v-if="currentPhase === 'VALIDATION'"
          class="validation"
        >
          <div
            class="validation__hero"
            :class="{
              'is-pass': validationResult?.valid,
              'is-fail': validationResult && !validationResult.valid
            }"
          >
            <span class="validation__ring">
              <el-icon
                v-if="validating"
                class="is-loading"
                :size="26"
              >
                <Loading />
              </el-icon>
              <el-icon
                v-else-if="validationResult?.valid"
                :size="28"
              >
                <Check />
              </el-icon>
              <el-icon
                v-else-if="validationResult"
                :size="26"
              >
                <Close />
              </el-icon>
              <el-icon
                v-else
                :size="24"
              >
                <MagicStick />
              </el-icon>
            </span>
            <h3 class="validation__title">
              {{ heroTitle }}
            </h3>
            <p class="validation__desc">
              {{ heroDesc }}
            </p>
            <el-button
              :loading="validating"
              @click="runValidation"
            >
              {{ t('ai.studio.workspace.rerunValidation') }}
            </el-button>
          </div>

          <div class="validation__grid">
            <section class="validation__panel">
              <header class="validation__panel-head">
                <span>{{ t('ai.studio.workspace.phasesChecklist') }}</span>
                <span class="validation__panel-count">
                  {{ t('ai.studio.workspace.phasesConfirmedCount', {
                    done: confirmedDesignPhaseCount,
                    total: designPhases.length
                  }) }}
                </span>
              </header>
              <button
                v-for="phase in designPhases"
                :key="phase"
                class="check-row"
                :class="{ 'is-done': completedPhases.includes(phase) }"
                @click="goToPhase(phase)"
              >
                <span class="check-row__node">
                  <el-icon
                    v-if="completedPhases.includes(phase)"
                    :size="12"
                  >
                    <Check />
                  </el-icon>
                </span>
                <span class="check-row__label">{{ aiStudioPhaseLabel(t, phase) }}</span>
                <span class="check-row__status">{{ phaseStatusText(phase) }}</span>
              </button>
            </section>

            <section class="validation__panel">
              <header class="validation__panel-head">
                <span>{{ t('ai.studio.workspace.findings') }}</span>
              </header>
              <div class="validation__findings">
                <el-alert
                  v-if="validationResult?.errors?.length"
                  type="error"
                  :closable="false"
                  class="validation__alert"
                >
                  <template #title>
                    {{ t('functionUnit.validationErrors') }} ({{ validationResult.errors.length }})
                  </template>
                  <ul class="validation__list">
                    <li
                      v-for="(err, i) in validationResult.errors"
                      :key="i"
                    >
                      {{ err }}
                    </li>
                  </ul>
                </el-alert>
                <el-alert
                  v-if="validationResult?.warnings?.length"
                  type="warning"
                  :closable="false"
                  class="validation__alert"
                >
                  <template #title>
                    {{ t('functionUnit.validationWarnings') }} ({{ validationResult.warnings.length }})
                  </template>
                  <ul class="validation__list">
                    <li
                      v-for="(warn, i) in validationResult.warnings"
                      :key="i"
                    >
                      {{ warn }}
                    </li>
                  </ul>
                </el-alert>
                <div
                  v-if="validationResult && !validationResult.errors?.length && !validationResult.warnings?.length"
                  class="validation__empty"
                >
                  <el-icon :size="16">
                    <Check />
                  </el-icon>
                  {{ t('ai.studio.workspace.noFindings') }}
                </div>
              </div>
            </section>
          </div>
        </div>
        <!-- Automation 阶段不再内嵌流程编排器（FR-B01/B2）：流程在独立的
             Automation 页设计，Service Task 面板只填业务键 -->
        <el-empty
          v-else-if="currentPhase === 'AUTOMATION'"
          class="automation-moved"
          :description="t('ai.studio.workspace.automationMoved')"
        >
          <el-button
            type="primary"
            @click="openAutomationPage"
          >
            {{ t('ai.studio.workspace.openAutomation') }}
          </el-button>
        </el-empty>
        <component
          :is="PHASE_COMPONENT[currentPhase]"
          v-else
          :key="`${currentPhase}-${stageReloadKey}`"
          :function-unit-id="fuId"
        />
      </div>

      <div class="stage__footer">
        <el-button @click="saveDraftNow">
          {{ t('ai.studio.workspace.saveDraft') }}
        </el-button>
        <el-button
          type="primary"
          @click="confirmPhase"
        >
          {{
            currentPhase === 'VALIDATION'
              ? t('ai.studio.workspace.finish')
              : t('ai.studio.workspace.confirmPhase')
          }}
        </el-button>
      </div>
    </main>

    <!-- 右：AI Copilot（本增量为 UI 壳，后端未接入时给显式回复） -->
    <aside
      v-if="copilotOpen"
      class="copilot"
    >
      <div class="copilot__header">
        <span class="copilot__title">
          <el-icon
            class="copilot__spark"
            :size="18"
          >
            <MagicStick />
          </el-icon>
          {{ t('ai.studio.workspace.copilotTitle') }}
        </span>
        <el-button
          text
          circle
          :aria-label="t('common.close')"
          @click="copilotOpen = false"
        >
          <el-icon><Close /></el-icon>
        </el-button>
      </div>
      <div
        ref="copilotBodyRef"
        class="copilot__body"
      >
        <div
          v-for="(msg, i) in copilotMessages"
          :key="i"
          class="copilot-msg"
          :class="[`copilot-msg--${msg.role}`, { 'copilot-msg--error': msg.isError }]"
        >
          <div
            v-if="msg.role === 'assistant'"
            class="copilot-msg__who"
          >
            <el-icon :size="14">
              <MagicStick />
            </el-icon>
            {{ t('ai.studio.workspace.copilotTitle') }}
          </div>
          <div class="copilot-msg__bubble">
            <!-- 模型回复按 markdown 渲染（MarkdownRenderer 内置 DOMPurify 消毒）；
                 用户消息与错误气泡保持纯文本 -->
            <MarkdownRenderer
              v-if="msg.role === 'assistant' && !msg.isError"
              :content="msg.text"
            />
            <template v-else>
              {{ msg.text }}
            </template>
          </div>
          <!-- 结构化改动提案卡：摘要 + Apply -->
          <div
            v-if="msg.proposal"
            class="proposal-card"
          >
            <div class="proposal-card__title">
              {{ t('ai.studio.workspace.proposalTitle') }}
            </div>
            <div
              v-for="item in proposalItems(msg.proposal.data)"
              :key="item.label"
              class="proposal-card__item"
            >
              <span class="proposal-card__plus">+</span>
              {{ item.label }}<template v-if="item.count !== null">
                × {{ item.count }}
              </template>
            </div>
            <div class="proposal-card__footer">
              <span
                v-if="msg.proposal.applied"
                class="proposal-card__applied"
              >
                <el-icon><Check /></el-icon>
                {{ t('ai.studio.workspace.proposalApplied') }}
              </span>
              <el-button
                v-else
                type="primary"
                size="small"
                :loading="applyingProposalMsg === msg"
                @click="applyProposal(msg)"
              >
                {{ t('ai.studio.workspace.proposalApply') }}
              </el-button>
            </div>
          </div>
        </div>
        <div
          v-if="copilotReplying && copilotReplyingPhase === currentPhase"
          class="copilot-msg copilot-msg--assistant"
        >
          <div class="copilot-msg__who">
            <el-icon :size="14">
              <MagicStick />
            </el-icon>
            {{ t('ai.studio.workspace.copilotTitle') }}
          </div>
          <div class="copilot-msg__bubble copilot-msg__bubble--typing">
            <span /><span /><span />
          </div>
          <div
            v-if="copilotProposalJobId"
            class="copilot-msg__working"
          >
            {{ t('ai.studio.workspace.proposalWorking') }}
          </div>
        </div>
      </div>
      <div
        v-if="proposalSupported"
        class="copilot__propose"
      >
        <el-button
          size="small"
          type="primary"
          plain
          :disabled="!copilotInput.trim() || copilotReplying"
          @click="sendCopilotMessage(true)"
        >
          <el-icon><MagicStick /></el-icon>
          {{ t('ai.studio.workspace.proposeButton') }}
        </el-button>
        <span class="copilot__propose-hint">{{ t('ai.studio.workspace.proposeHint') }}</span>
      </div>
      <div class="copilot__input">
        <el-input
          v-model="copilotInput"
          :placeholder="t('ai.studio.workspace.copilotPlaceholder')"
          :disabled="copilotReplying"
          @keydown.enter.prevent="sendCopilotMessage()"
        >
          <template #suffix>
            <el-button
              text
              circle
              :disabled="!copilotInput.trim() || copilotReplying"
              :aria-label="t('ai.studio.workspace.copilotSend')"
              @click="sendCopilotMessage()"
            >
              <el-icon><Promotion /></el-icon>
            </el-button>
          </template>
        </el-input>
      </div>
    </aside>
    <button
      v-else
      class="copilot-fab"
      :aria-label="t('ai.studio.workspace.copilotTitle')"
      @click="copilotOpen = true"
    >
      <el-icon :size="20">
        <MagicStick />
      </el-icon>
    </button>

    <!-- 底部状态条 -->
    <footer class="statusbar">
      <span class="statusbar__phase">
        <span class="statusbar__num">{{ currentPhaseIndex + 1 }}</span>
        {{ t('ai.studio.workspace.phaseOf', { current: currentPhaseIndex + 1, total: AI_STUDIO_PHASES.length }) }}
        · {{ aiStudioPhaseLabel(t, currentPhase) }}
      </span>
      <span class="statusbar__saved">{{ t('ai.studio.workspace.draftSavedAt', { time: lastSavedAt || '—' }) }}</span>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick, markRaw, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, MagicStick, Check, Close, Promotion, UploadFilled, Loading } from '@element-plus/icons-vue'
import { useFunctionUnitStore } from '@/stores/functionUnit'
import { functionUnitApi, type ValidationResult } from '@/api/functionUnit'
import { aiGenerationApi, type AiStudioProposalJob } from '@/api/aiGeneration'
import ProcessDesigner from '@/components/designer/ProcessDesigner.vue'
import TableDesigner from '@/components/designer/TableDesigner.vue'
import FormDesigner from '@/components/designer/FormDesigner.vue'
import MainTableViewDesignTab from '@/components/designer/MainTableViewDesignTab.vue'
import ActionDesigner from '@/components/designer/ActionDesigner.vue'
import ConnectionDesigner from '@/components/designer/ConnectionDesigner.vue'
import EmailTemplateDesigner from '@/components/designer/EmailTemplateDesigner.vue'
import EmailMonitorDesigner from '@/components/designer/EmailMonitorDesigner.vue'
import DecisionList from '@/components/designer/DecisionList.vue'
import MarkdownRenderer from '@/components/ai/MarkdownRenderer.vue'
import {
  AI_STUDIO_PHASES,
  aiStudioPhaseLabel,
  loadAiStudioDraft,
  saveAiStudioDraft,
  loadAiStudioChatThreads,
  saveAiStudioChatThreads,
  clearAiStudioChatThreads,
  type AiStudioPhase,
  type AiStudioChatMessage,
  type AiStudioChatThreads,
  loadAiStudioPendingProposal,
  saveAiStudioPendingProposal,
  clearAiStudioPendingProposal,
  type AiStudioPendingProposal
} from '@/utils/aiStudioDraft'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const store = useFunctionUnitStore()

const fuId = computed(() => Number(route.params.id))

/** 阶段 → 中间区设计器组件。VALIDATION 无设计器，模板里单独渲染。 */
const PHASE_COMPONENT: Partial<Record<AiStudioPhase, Component>> = {
  PROCESS_DESIGN: markRaw(ProcessDesigner),
  TABLE_DESIGN: markRaw(TableDesigner),
  FORM_DESIGN: markRaw(FormDesigner),
  VIEW_DESIGN: markRaw(MainTableViewDesignTab),
  ACTION_DESIGN: markRaw(ActionDesigner),
  // AUTOMATION 无内嵌设计器（流程移至独立 Automation 页），模板里单独渲染引导态
  CONNECTIONS: markRaw(ConnectionDesigner),
  EMAIL_TEMPLATES: markRaw(EmailTemplateDesigner),
  EMAIL_MONITORS: markRaw(EmailMonitorDesigner),
  DECISION_DESIGN: markRaw(DecisionList)
}

const currentPhase = ref<AiStudioPhase>('PROCESS_DESIGN')
const completedPhases = ref<AiStudioPhase[]>([])
const lastSavedAt = ref('')

const currentPhaseIndex = computed(() => AI_STUDIO_PHASES.indexOf(currentPhase.value))

/** Automation 阶段引导：新标签页打开独立 Automation 页，不打断工作台上下文 */
function openAutomationPage(): void {
  window.open(router.resolve('/automation').href, '_blank', 'noopener')
}

/** 可进入：已确认的阶段、当前阶段，以及最远进度的下一个阶段。 */
function canEnterPhase(idx: number): boolean {
  const maxDone = completedPhases.value.reduce(
    (max, p) => Math.max(max, AI_STUDIO_PHASES.indexOf(p)),
    -1
  )
  return idx <= Math.max(maxDone + 1, currentPhaseIndex.value)
}

function phaseStatusText(phase: AiStudioPhase): string {
  if (completedPhases.value.includes(phase)) return t('ai.studio.workspace.statusConfirmed')
  if (phase === currentPhase.value) return t('ai.studio.workspace.statusInProgress')
  return t('ai.studio.workspace.statusNotStarted')
}

const PHASE_DESC_KEY: Record<AiStudioPhase, string> = {
  PROCESS_DESIGN: 'ai.studio.workspace.phaseDesc.processDesign',
  TABLE_DESIGN: 'ai.studio.workspace.phaseDesc.tableDesign',
  FORM_DESIGN: 'ai.studio.workspace.phaseDesc.formDesign',
  VIEW_DESIGN: 'ai.studio.workspace.phaseDesc.viewDesign',
  ACTION_DESIGN: 'ai.studio.workspace.phaseDesc.actionDesign',
  AUTOMATION: 'ai.studio.workspace.phaseDesc.automation',
  CONNECTIONS: 'ai.studio.workspace.phaseDesc.connections',
  EMAIL_TEMPLATES: 'ai.studio.workspace.phaseDesc.emailTemplates',
  EMAIL_MONITORS: 'ai.studio.workspace.phaseDesc.emailMonitors',
  DECISION_DESIGN: 'ai.studio.workspace.phaseDesc.decisionDesign',
  VALIDATION: 'ai.studio.workspace.phaseDesc.validation'
}

function phaseDesc(phase: AiStudioPhase): string {
  return t(PHASE_DESC_KEY[phase])
}

function persistDraft() {
  saveAiStudioDraft(fuId.value, {
    name: store.current?.name ?? `#${fuId.value}`,
    phase: currentPhase.value,
    completedPhases: completedPhases.value,
    updatedAt: new Date().toISOString()
  })
  lastSavedAt.value = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function goToPhase(phase: AiStudioPhase) {
  currentPhase.value = phase
}

function saveDraftNow() {
  persistDraft()
  ElMessage.success(t('ai.studio.workspace.draftSaved'))
}

function confirmPhase() {
  if (!completedPhases.value.includes(currentPhase.value)) {
    completedPhases.value.push(currentPhase.value)
  }
  const idx = currentPhaseIndex.value
  if (idx < AI_STUDIO_PHASES.length - 1) {
    const next = AI_STUDIO_PHASES[idx + 1]
    ElMessage.success(
      t('ai.studio.workspace.phaseConfirmed', { phase: aiStudioPhaseLabel(t, currentPhase.value) })
    )
    currentPhase.value = next
  } else {
    ElMessage.success(t('ai.studio.workspace.allPhasesDone'))
    persistDraft()
  }
}

/** Validation 阶段左侧清单 = 除自身外的 10 个设计阶段 */
const designPhases = AI_STUDIO_PHASES.filter(p => p !== 'VALIDATION')
const confirmedDesignPhaseCount = computed(
  () => designPhases.filter(p => completedPhases.value.includes(p)).length
)

function exitStudio() {
  persistDraft()
  router.push(`/function-units/${fuId.value}`)
}

// ---- Validation 阶段：整体校验 ----
const validating = ref(false)
const validationResult = ref<ValidationResult | null>(null)

const heroTitle = computed(() => {
  if (validating.value || !validationResult.value) return t('ai.studio.workspace.validationRunning')
  if (validationResult.value.valid) return t('ai.studio.workspace.validationReady')
  return t('ai.studio.workspace.validationIssues', {
    count: validationResult.value.errors?.length ?? 0
  })
})

const heroDesc = computed(() => {
  if (validating.value || !validationResult.value) return t('ai.studio.workspace.phaseDesc.validation')
  if (validationResult.value.valid) return t('ai.studio.workspace.validationReadyDesc')
  return t('ai.studio.workspace.validationIssuesDesc')
})

async function runValidation() {
  validating.value = true
  validationResult.value = null
  try {
    const res = await functionUnitApi.validate(fuId.value)
    validationResult.value = res.data
  } catch (e) {
    ElMessage.error(t('functionUnit.validationFailed'))
    console.error('[ai-studio] validation request failed', e)
  } finally {
    validating.value = false
  }
}

// ---- AI Copilot：与 AI Generate 同源的模型链路（集团 gateway + AMToken 透传） ----
// 会话按阶段隔离：每个阶段一个独立线程，切阶段即切线程，互不可见也互不进对方历史。
// 线程随草稿持久化到 localStorage（契约见 utils/aiStudioDraft.ts），Continue AI draft 时恢复。
type CopilotMessage = AiStudioChatMessage

const copilotOpen = ref(true)
const copilotInput = ref('')
const copilotReplying = ref(false)
/** 正在等回复的线程；打字指示只出现在这个阶段的线程里 */
const copilotReplyingPhase = ref<AiStudioPhase | null>(null)
/** 正在轮询的提案作业 id；非空时打字指示下方补一行"要等几分钟"的说明 */
const copilotProposalJobId = ref<string | null>(null)
const copilotBodyRef = ref<HTMLElement>()
const copilotThreads = ref<AiStudioChatThreads>({})

// 任何线程变化（新线程、发消息、收回复）即落盘；进入时的整体恢复也会触发一次原样回写，无害
watch(copilotThreads, (threads) => {
  saveAiStudioChatThreads(fuId.value, threads)
}, { deep: true })

/**
 * 取（必要时新建）某阶段的线程。引导语只随线程**首次创建**（=第一次进入该阶段）追加一次，
 * 之后作为线程历史永久保留；revisit 只是重新展示既有线程，绝不会再追加第二条引导语。
 */
function copilotThread(phase: AiStudioPhase): CopilotMessage[] {
  let thread = copilotThreads.value[phase]
  if (!thread) {
    thread = [{
      role: 'assistant',
      text: t('ai.studio.workspace.copilotPhaseNote', {
        phase: aiStudioPhaseLabel(t, phase),
        desc: phaseDesc(phase)
      }),
      isPhaseNote: true
    }]
    copilotThreads.value[phase] = thread
  }
  return thread
}

// 纯读取：线程的按需创建在 watch(currentPhase) / onMounted / 发送时做，不在 computed 里带副作用
const copilotMessages = computed(() => copilotThreads.value[currentPhase.value] ?? [])

function scrollCopilotToBottom() {
  void nextTick(() => {
    copilotBodyRef.value?.scrollTo({ top: copilotBodyRef.value.scrollHeight })
  })
}

/** 送给模型的历史窗口：本阶段线程里最近 10 条真实对话（引导语与错误气泡不算）。 */
function copilotHistory(phase: AiStudioPhase) {
  return copilotThread(phase)
    .filter(m => !m.isError && !m.isPhaseNote)
    .slice(-10)
    .map(m => ({
      role: m.role === 'user' ? ('USER' as const) : ('ASSISTANT' as const),
      content: m.text
    }))
}

/** 结构化提案仅在有 generatedData 切片的阶段可用（与后端 PROPOSAL_SCOPE_BY_PHASE 一致）。 */
const PROPOSAL_PHASES: readonly AiStudioPhase[] =
  ['PROCESS_DESIGN', 'TABLE_DESIGN', 'FORM_DESIGN', 'ACTION_DESIGN', 'DECISION_DESIGN']
const proposalSupported = computed(() => PROPOSAL_PHASES.includes(currentPhase.value))

async function sendCopilotMessage(propose = false) {
  const text = copilotInput.value.trim()
  if (!text || copilotReplying.value) return
  // 锁定发送时所在的阶段线程：等待期间切走，回复也落回这个线程
  const phase = currentPhase.value
  const thread = copilotThread(phase)
  const history = copilotHistory(phase)
  thread.push({ role: 'user', text })
  copilotInput.value = ''
  beginCopilotWait(phase)
  try {
    if (propose) {
      const { data: job } = await aiGenerationApi.studioStartProposal({
        functionUnitId: fuId.value,
        phase,
        message: text,
        history
      })
      // 先落盘再轮询：刷新/离开页面后 onMounted 能凭这条记录接着等同一个作业
      saveAiStudioPendingProposal(fuId.value, { jobId: job.jobId, phase, submittedAt: Date.now() })
      copilotProposalJobId.value = job.jobId
      const done = await pollProposal(job.jobId)
      if (done) pushProposalResult(thread, done)
    } else {
      const res = await aiGenerationApi.studioChat({
        functionUnitId: fuId.value,
        phase,
        message: text,
        history
      })
      thread.push({ role: 'assistant', text: res.data.reply ?? '' })
    }
  } catch (e: unknown) {
    pushCopilotError(thread, e)
  } finally {
    endCopilotWait(phase)
  }
}

function beginCopilotWait(phase: AiStudioPhase) {
  copilotReplying.value = true
  copilotReplyingPhase.value = phase
  scrollCopilotToBottom()
}

function endCopilotWait(phase: AiStudioPhase) {
  copilotReplying.value = false
  copilotReplyingPhase.value = null
  copilotProposalJobId.value = null
  if (currentPhase.value === phase) scrollCopilotToBottom()
}

/** axios 错误的可读原因：后端 ApiResponse.error.message → 顶层 message → Error.message → 原样字符串 */
function errorReason(e: unknown): string {
  const err = e as {
    response?: { data?: { error?: { message?: string }; message?: string } }
    message?: string
  } | null
  return err?.response?.data?.error?.message
    ?? err?.response?.data?.message
    ?? err?.message
    ?? String(e)
}

function pushCopilotError(thread: CopilotMessage[], e: unknown) {
  const reason = errorReason(e)
  thread.push({
    role: 'assistant',
    text: t('ai.studio.workspace.copilotError', { reason }),
    isError: true
  })
}

// ---- 提案作业轮询 ----

const PROPOSAL_POLL_INTERVAL_MS = 4000
/** 连续这么多次轮询失败（网络/5xx）才放弃；单次抖动不该让用户丢掉一轮 7 分钟的生成 */
const PROPOSAL_POLL_MAX_CONSECUTIVE_FAILURES = 8

/** 组件卸载后置 true：轮询循环看到就退出，不再往已销毁的线程里推消息 */
let proposalPollingCancelled = false
onBeforeUnmount(() => {
  proposalPollingCancelled = true
})

function sleep(ms: number) {
  return new Promise<void>(resolve => setTimeout(resolve, ms))
}

/**
 * 轮询到终态返回作业；组件卸载中途退出返回 null（待办记录保留给下次进入接着等）。
 * 作业不存在（404）或连续失败超限时抛错，并清掉待办记录——再等也不会有结果。
 */
async function pollProposal(jobId: string): Promise<AiStudioProposalJob | null> {
  let failures = 0
  for (;;) {
    await sleep(PROPOSAL_POLL_INTERVAL_MS)
    if (proposalPollingCancelled) return null
    try {
      const { data: job } = await aiGenerationApi.studioGetProposal(jobId)
      failures = 0
      if (job.status === 'SUCCEEDED' || job.status === 'FAILED') {
        clearAiStudioPendingProposal(fuId.value)
        return job
      }
    } catch (e: unknown) {
      if (proposalPollingCancelled) return null
      if ((e as { response?: { status?: number } } | null)?.response?.status === 404) {
        clearAiStudioPendingProposal(fuId.value)
        throw new Error(t('ai.studio.workspace.proposalLost'))
      }
      failures++
      if (failures >= PROPOSAL_POLL_MAX_CONSECUTIVE_FAILURES) {
        clearAiStudioPendingProposal(fuId.value)
        throw new Error(t('ai.studio.workspace.proposalPollFailed', { reason: errorReason(e) }))
      }
    }
  }
}

function pushProposalResult(thread: CopilotMessage[], job: AiStudioProposalJob) {
  if (job.status === 'FAILED') {
    thread.push({
      role: 'assistant',
      text: t('ai.studio.workspace.copilotError', { reason: job.errorMessage ?? job.errorCode ?? 'FAILED' }),
      isError: true
    })
    return
  }
  const { reply, proposal, proposalScope } = job
  const message: CopilotMessage = {
    role: 'assistant',
    text: reply ?? (proposal ? t('ai.studio.workspace.proposalReady') : '')
  }
  if (proposal && proposalScope) {
    message.proposal = { scope: proposalScope, data: proposal }
  } else {
    // 要求了提案但模型没产出数据块：显式说明，别让用户以为按钮坏了
    message.text = `${message.text}\n\n${t('ai.studio.workspace.proposalNone')}`.trim()
  }
  thread.push(message)
}

/** 进入页面时发现上次的提案作业还没等完：接着轮询同一个 jobId，结果落回发起时的阶段线程。 */
async function resumePendingProposal(pending: AiStudioPendingProposal) {
  const thread = copilotThread(pending.phase)
  copilotProposalJobId.value = pending.jobId
  beginCopilotWait(pending.phase)
  try {
    const done = await pollProposal(pending.jobId)
    if (done) pushProposalResult(thread, done)
  } catch (e: unknown) {
    pushCopilotError(thread, e)
  } finally {
    endCopilotWait(pending.phase)
  }
}

// ---- 提案卡片：摘要与 Apply ----

/** 提案内容摘要：各切片的条数（+ 流程是否更新），供卡片按 "+ 条目" 列出。 */
function proposalItems(data: Record<string, unknown>): { label: string; count: number | null }[] {
  const items: { label: string; count: number | null }[] = []
  const push = (key: string, label: string) => {
    const n = Array.isArray(data[key]) ? (data[key] as unknown[]).length : 0
    if (n > 0) items.push({ label, count: n })
  }
  push('tableDefinitions', t('functionUnit.tables'))
  push('tableRelations', t('ai.studio.workspace.proposalItemRelations'))
  push('formDefinitions', t('functionUnit.forms'))
  push('actionDefinitions', t('functionUnit.actionDesign'))
  push('decisionDefinitions', t('functionUnit.decisions'))
  if (data.processDefinition) items.push({ label: t('functionUnit.process'), count: null })
  return items
}

/** 正在 Apply 的那条消息（同一时刻只允许一个 Apply 在跑） */
const applyingProposalMsg = ref<CopilotMessage | null>(null)
/** 改这个值会重挂载中间区设计器——Apply 写库后画布/列表必须重新加载 */
const stageReloadKey = ref(0)

async function applyProposal(msg: CopilotMessage) {
  if (!msg.proposal || msg.proposal.applied || applyingProposalMsg.value) return
  applyingProposalMsg.value = msg
  try {
    await aiGenerationApi.studioApplyProposal({
      functionUnitId: fuId.value,
      scope: msg.proposal.scope,
      generatedData: msg.proposal.data
    })
    msg.proposal.applied = true
    ElMessage.success(t('ai.studio.workspace.proposalApplySuccess'))
    await store.refreshAll(fuId.value)
    stageReloadKey.value++
  } catch (e: any) {
    const reason = e?.response?.data?.error?.message
      ?? e?.response?.data?.message
      ?? e?.message
      ?? String(e)
    ElMessage.error(t('ai.studio.workspace.proposalApplyFailed', { reason }))
  } finally {
    applyingProposalMsg.value = null
  }
}

// ---- 阶段切换副作用 ----
watch(currentPhase, (phase) => {
  persistDraft()
  // FormDesigner 依赖 store.tables 预取（与 FunctionUnitEdit 的 forms tab watch 一致）
  if (phase === 'FORM_DESIGN') void store.fetchTables(fuId.value)
  if (phase === 'VALIDATION') void runValidation()
  // 确保该阶段的 Copilot 线程存在（新线程带引导语开场）并滚到底
  copilotThread(phase)
  scrollCopilotToBottom()
})

onMounted(async () => {
  await store.fetchById(fuId.value)

  const draft = loadAiStudioDraft(fuId.value)
  const mode = route.query.mode
  // 先恢复聊天线程再定阶段：阶段 watch 里的 copilotThread() 看到已恢复的线程
  // 就不会重建（引导语只属于真正的首次进入）
  copilotThreads.value = loadAiStudioChatThreads(fuId.value)

  if (draft && mode === 'continue') {
    currentPhase.value = draft.phase
    completedPhases.value = draft.completedPhases ?? []
  } else if (draft && mode === 'new' && (draft.completedPhases?.length || draft.phase !== AI_STUDIO_PHASES[0])) {
    // 入口承诺"未经确认不覆盖"：重新开始会丢弃草稿进度与聊天记录，必须先问
    try {
      await ElMessageBox.confirm(
        t('ai.studio.workspace.restartConfirmMsg'),
        t('ai.studio.workspace.restartConfirmTitle'),
        { type: 'warning', confirmButtonText: t('ai.studio.workspace.restartConfirmOk') }
      )
      completedPhases.value = []
      currentPhase.value = AI_STUDIO_PHASES[0]
      clearAiStudioChatThreads(fuId.value)
      clearAiStudioPendingProposal(fuId.value)
      copilotThreads.value = {}
    } catch {
      currentPhase.value = draft.phase
      completedPhases.value = draft.completedPhases ?? []
    }
  } else if (draft && mode !== 'new') {
    // 直接进入（刷新/收藏链接）：默认续用草稿进度
    currentPhase.value = draft.phase
    completedPhases.value = draft.completedPhases ?? []
  }

  persistDraft()
  copilotThread(currentPhase.value)
  if (currentPhase.value === 'FORM_DESIGN') void store.fetchTables(fuId.value)
  if (currentPhase.value === 'VALIDATION') void runValidation()

  // 上次离开时还有提案作业在跑（异步、服务端继续算）：接着等，不重新发起
  const pending = loadAiStudioPendingProposal(fuId.value)
  if (pending) void resumePendingProposal(pending)
})
</script>

<style lang="scss" scoped>
.ai-studio {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  grid-template-columns: 264px minmax(0, 1fr) auto;
  grid-template-rows: minmax(0, 1fr) 40px;
  gap: 12px;
  padding: 12px 12px 0;
  background-color: var(--el-bg-color-page, #f5f6f8);
}

// ---- 左：阶段轨道 ----
.rail {
  display: flex;
  flex-direction: column;
  background-color: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  overflow: hidden;

  &__header {
    padding: 14px 16px 10px;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  &__exit {
    padding: 0;
    margin-bottom: 10px;
    color: var(--el-text-color-secondary);
  }

  &__title {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 17px;
    font-weight: 650;
    letter-spacing: -0.2px;
    color: var(--el-text-color-primary);
  }

  &__title-icon {
    color: #e6a23c;
  }

  &__fu-name {
    margin-top: 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__phases {
    flex: 1;
    overflow-y: auto;
    padding: 12px 10px;
  }

  &__footer {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 10px 16px;
    border-top: 1px solid var(--el-border-color-lighter);
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}

.rail-phase {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  width: 100%;
  padding: 9px 10px;
  border: none;
  border-radius: 8px;
  background: none;
  text-align: left;
  cursor: pointer;
  font: inherit;

  // 竖向连接线：与入口弹窗的流程链同语言
  &:not(:last-child)::after {
    content: '';
    position: absolute;
    left: 24px;
    top: 40px;
    bottom: -10px;
    width: 2px;
    background-color: var(--el-border-color-lighter);
  }

  &.is-done:not(:last-child)::after {
    background-color: var(--el-color-success-light-5, #b3e19d);
  }

  &:hover:not(:disabled):not(.is-current) {
    background-color: var(--el-fill-color-light);
  }

  &.is-current {
    background-color: var(--el-color-primary-light-9);
  }

  &.is-locked {
    cursor: not-allowed;
    opacity: 0.55;
  }

  &__node {
    z-index: 1;
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    border-radius: 50%;
    border: 2px solid var(--el-border-color);
    background-color: #fff;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }

  &.is-current &__node {
    border-color: var(--el-color-primary);
    background-color: var(--el-color-primary);
    color: #fff;
  }

  &.is-done &__node {
    border-color: var(--el-color-success);
    background-color: var(--el-color-success);
    color: #fff;
  }

  &__text {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  &__label {
    font-size: 13.5px;
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  &.is-current &__label {
    color: var(--el-color-primary);
  }

  &__status {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  &.is-current &__status {
    color: var(--el-color-primary);
  }

  &.is-done &__status {
    color: var(--el-color-success);
  }
}

// ---- 中：设计区 ----
.stage {
  display: flex;
  flex-direction: column;
  min-width: 0;
  background-color: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  overflow: hidden;

  &__header {
    padding: 16px 20px 12px;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  &__title {
    margin: 0;
    font-size: 20px;
    font-weight: 650;
    letter-spacing: -0.3px;
    color: var(--el-text-color-primary);
  }

  &__desc {
    margin: 4px 0 0;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  &__body {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 16px 20px;
  }

  &__footer {
    display: flex;
    justify-content: flex-end;
    gap: 4px;
    padding: 12px 20px;
    border-top: 1px solid var(--el-border-color-lighter);
    background-color: #fff;
  }
}

.validation {
  max-width: 880px;
  margin: 0 auto;

  &__hero {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 28px 20px 24px;
    text-align: center;
  }

  // 放大的 BPMN 结束事件：粗环 + 外圈光晕，与入口弹窗流程链的 Review 节点同源
  &__ring {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 64px;
    height: 64px;
    border-radius: 50%;
    border: 4px solid var(--el-color-primary);
    background-color: #fff;
    color: var(--el-color-primary);
    box-shadow: 0 0 0 6px var(--el-color-primary-light-9);
    transition: border-color 0.3s, color 0.3s, box-shadow 0.3s;
  }

  &__hero.is-pass &__ring {
    border-color: var(--el-color-success);
    color: var(--el-color-success);
    box-shadow: 0 0 0 6px var(--el-color-success-light-9, #f0f9eb);
  }

  &__hero.is-fail &__ring {
    border-color: var(--el-color-danger);
    color: var(--el-color-danger);
    box-shadow: 0 0 0 6px var(--el-color-danger-light-9, #fef0f0);
  }

  &__title {
    margin: 16px 0 0;
    font-size: 20px;
    font-weight: 650;
    letter-spacing: -0.3px;
    color: var(--el-text-color-primary);
  }

  &__desc {
    margin: 6px 0 16px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  &__grid {
    display: grid;
    grid-template-columns: minmax(0, 5fr) minmax(0, 7fr);
    gap: 16px;
    margin-top: 8px;
  }

  &__panel {
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 12px;
    padding: 14px 16px;
    background-color: #fff;
  }

  &__panel-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 10px;
    font-size: 13px;
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  &__panel-count {
    font-weight: 500;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  &__findings {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  &__alert {
    margin: 0;
  }

  &__list {
    margin: 8px 0 0;
    padding-left: 20px;
  }

  &__empty {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 14px 4px;
    font-size: 13px;
    color: var(--el-color-success);
  }
}

.check-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 7px 8px;
  border: none;
  border-radius: 8px;
  background: none;
  text-align: left;
  cursor: pointer;
  font: inherit;

  &:hover {
    background-color: var(--el-fill-color-light);
  }

  &__node {
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    border-radius: 50%;
    border: 2px solid var(--el-border-color);
    background-color: #fff;
    color: #fff;
  }

  &.is-done &__node {
    border-color: var(--el-color-success);
    background-color: var(--el-color-success);
  }

  &__label {
    flex: 1;
    min-width: 0;
    font-size: 13px;
    color: var(--el-text-color-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__status {
    flex-shrink: 0;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  &.is-done &__status {
    color: var(--el-color-success);
  }
}

@media (max-width: 900px) {
  .validation__grid {
    grid-template-columns: 1fr;
  }
}

// ---- 右：AI Copilot ----
.copilot {
  display: flex;
  flex-direction: column;
  width: 336px;
  background-color: #fff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  overflow: hidden;

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 14px;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  &__title {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 15px;
    font-weight: 650;
    color: var(--el-text-color-primary);
  }

  &__spark {
    color: #e6a23c;
  }

  &__body {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 14px;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  &__propose {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 14px 0;
    border-top: 1px solid var(--el-border-color-lighter);
  }

  &__propose-hint {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  &__input {
    padding: 12px 14px;
  }

  // propose 行不存在的阶段（无结构化切片），输入区自己补分隔线
  &__propose + &__input {
    border-top: none;
  }

  &__body + &__input {
    border-top: 1px solid var(--el-border-color-lighter);
  }
}

.proposal-card {
  margin-top: 8px;
  padding: 12px 14px;
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 10px;
  background-color: var(--el-color-primary-light-9);

  &__title {
    font-size: 13px;
    font-weight: 650;
    color: var(--el-text-color-primary);
    margin-bottom: 8px;
  }

  &__item {
    display: flex;
    align-items: baseline;
    gap: 6px;
    font-size: 13px;
    line-height: 1.7;
    color: var(--el-text-color-regular);
  }

  &__plus {
    color: var(--el-color-primary);
    font-weight: 700;
  }

  &__footer {
    display: flex;
    justify-content: flex-end;
    margin-top: 10px;
  }

  &__applied {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 13px;
    font-weight: 600;
    color: var(--el-color-success);
  }
}

.copilot-msg {
  &__who {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 4px;
    font-size: 12px;
    font-weight: 600;
    color: var(--el-text-color-secondary);

    .el-icon {
      color: #e6a23c;
    }
  }

  &__bubble {
    padding: 10px 12px;
    border-radius: 10px;
    font-size: 13px;
    line-height: 1.55;
    color: var(--el-text-color-primary);
    word-break: break-word;

    // markdown 首末元素的外边距不撑大气泡
    :deep(.markdown-renderer > :first-child) {
      margin-top: 0;
    }

    :deep(.markdown-renderer > :last-child) {
      margin-bottom: 0;
    }
  }

  &--assistant &__bubble {
    background-color: var(--el-fill-color-light);
  }

  &--error &__bubble {
    background-color: var(--el-color-danger-light-9, #fef0f0);
    color: var(--el-color-danger);
  }

  &--user {
    align-self: flex-end;
    max-width: 88%;

    .copilot-msg__bubble {
      background-color: var(--el-color-primary-light-9);
    }
  }

  &__working {
    margin-top: 6px;
    font-size: 12px;
    line-height: 1.5;
    color: var(--el-text-color-secondary);
  }

  &__bubble--typing {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 12px 14px;

    span {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background-color: var(--el-text-color-placeholder);
      animation: copilot-typing 1.2s ease-in-out infinite;

      &:nth-child(2) {
        animation-delay: 0.15s;
      }

      &:nth-child(3) {
        animation-delay: 0.3s;
      }
    }
  }
}

@keyframes copilot-typing {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.5;
  }
  30% {
    transform: translateY(-4px);
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .copilot-msg__bubble--typing span {
    animation: none;
  }
}

.copilot-fab {
  position: fixed;
  right: 20px;
  bottom: 60px;
  z-index: 101;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border: none;
  border-radius: 50%;
  background-color: var(--el-color-primary);
  color: #fff;
  cursor: pointer;
  box-shadow: 0 4px 14px rgba(219, 0, 17, 0.3);
}

// ---- 底部状态条 ----
.statusbar {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  font-size: 12.5px;
  color: var(--el-text-color-regular);

  &__phase {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  &__num {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    border-radius: 50%;
    background-color: var(--el-color-primary-light-9);
    color: var(--el-color-primary);
    font-weight: 600;
    font-size: 11px;
  }

  &__saved {
    color: var(--el-text-color-secondary);
  }
}

@media (max-width: 1200px) {
  .ai-studio {
    grid-template-columns: 232px minmax(0, 1fr);
  }

  .copilot {
    display: none;
  }
}
</style>
