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
        <div class="stage__heading">
          <h2 class="stage__title">
            {{ aiStudioPhaseLabel(t, currentPhase) }}
          </h2>
          <p class="stage__desc">
            {{ phaseDesc(currentPhase) }}
          </p>
        </div>
        <el-button
          v-if="sharedThreads"
          class="stage__docs"
          @click="docsDrawerOpen = true"
        >
          <el-icon :class="{ 'is-loading': docSyncRunning }">
            <component :is="docSyncRunning ? Loading : Document" />
          </el-icon>
          {{ docSyncRunning ? t('ai.studio.docSync.syncing') : t('ai.studio.docSync.documentsButton') }}
        </el-button>
      </div>

      <el-alert
        v-if="isStageReadOnly"
        type="info"
        :closable="false"
        show-icon
        :title="t('functionUnit.readOnlyHint')"
        class="stage__readonly"
      />

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
        <!-- Automation 阶段不内嵌流程编排器（FR-B01/B2）：流程在独立的 Automation 页设计；
             这里只读展示 BPMN 里各 service task 的 flow 绑定，Apply 提案后随 stageReloadKey 重载 -->
        <ServiceTaskBindingsPanel
          v-else-if="currentPhase === 'AUTOMATION'"
          :key="`automation-${stageReloadKey}`"
          :function-unit-id="fuId"
          @open-automation="openAutomationPage"
        />
        <!-- 只读成员：与 FunctionUnitEdit 同一套捕获阶段拦截，查看类控件（data-read-only-allowed）仍可用 -->
        <div
          v-else
          class="stage__designer"
          @click.capture="onReadOnlyInteraction"
          @pointerdown.capture="onReadOnlyInteraction"
          @keydown.capture="onReadOnlyInteraction"
          @dragstart.capture="onReadOnlyInteraction"
          @drop.capture="onReadOnlyInteraction"
        >
          <component
            :is="PHASE_COMPONENT[currentPhase]"
            :key="`${currentPhase}-${stageReloadKey}`"
            :function-unit-id="fuId"
          />
        </div>
      </div>

      <div class="stage__footer">
        <el-button
          :disabled="isStageReadOnly"
          @click="saveDraftNow"
        >
          {{ t('ai.studio.workspace.saveDraft') }}
        </el-button>
        <el-button
          type="primary"
          :disabled="isStageReadOnly"
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
          :class="[`copilot-msg--${msg.role}`, {
            'copilot-msg--error': msg.isError,
            'copilot-msg--teammate': msg.role === 'user' && msg.mine === false
          }]"
        >
          <!-- 共享线程：队友的提问标出是谁问的；自己的提问不标 -->
          <div
            v-if="msg.role === 'user' && msg.mine === false"
            class="copilot-msg__who copilot-msg__who--teammate"
          >
            {{ t('ai.studio.workspace.askedBy', { name: msg.authorName || '?' }) }}
          </div>
          <div
            v-if="msg.role === 'assistant'"
            class="copilot-msg__who"
          >
            <el-icon :size="14">
              <MagicStick />
            </el-icon>
            {{ t('ai.studio.workspace.copilotTitle') }}
          </div>
          <div
            v-if="!msg.docSync"
            class="copilot-msg__bubble"
          >
            <!-- 模型回复按 markdown 渲染（MarkdownRenderer 内置 DOMPurify 消毒）；
                 用户消息与错误气泡保持纯文本 -->
            <MarkdownRenderer
              v-if="msg.role === 'assistant' && !msg.isError"
              :content="msg.text || (msg.proposal ? t('ai.studio.workspace.proposalReady') : '')"
            />
            <template v-else>
              {{ msg.text }}
            </template>
          </div>
          <!-- DW 重启打断的提案：用原消息一键重新发起 -->
          <el-button
            v-if="msg.retryMessage"
            size="small"
            plain
            class="copilot-msg__retry"
            :disabled="copilotReplying || !canModifyThread"
            @click="retryInterruptedProposal(msg)"
          >
            <el-icon><MagicStick /></el-icon>
            {{ t('ai.studio.workspace.proposalRetry') }}
          </el-button>
          <!-- 文档同步结果：确认阶段 / 立即检查后由后端写入 -->
          <AiStudioDocSyncCard
            v-if="msg.docSync"
            :doc-sync="msg.docSync"
            :function-unit-id="fuId"
            :can-modify="canModifyThread"
            :syncing="docSyncRunning"
            @retry="checkDocumentsNow"
            @open-documents="docsDrawerOpen = true"
            @restored="docsRefreshKey++"
          />
          <!-- 结构化改动提案卡：摘要 + Apply -->
          <div
            v-if="msg.proposal"
            class="proposal-card"
          >
            <div class="proposal-card__title">
              {{ t('ai.studio.workspace.proposalTitle') }}
            </div>
            <div
              v-for="group in proposalGroups(msg.proposal)"
              :key="group.slice"
              class="proposal-card__group"
            >
              <div class="proposal-card__group-head">
                <span class="proposal-card__plus">+</span>
                <span class="proposal-card__group-label">{{ group.label }}</span>
                <span
                  v-if="group.newCount"
                  class="proposal-card__badge proposal-card__badge--new"
                >{{ t('ai.studio.workspace.proposalNew', { n: group.newCount }) }}</span>
                <span
                  v-if="group.updateCount"
                  class="proposal-card__badge proposal-card__badge--update"
                >{{ t('ai.studio.workspace.proposalUpdate', { n: group.updateCount }) }}</span>
                <span
                  v-if="group.bindCount"
                  class="proposal-card__badge proposal-card__badge--new"
                >{{ t('ai.studio.workspace.proposalBind', { n: group.bindCount }) }}</span>
                <span
                  v-if="group.rebindCount"
                  class="proposal-card__badge proposal-card__badge--update"
                >{{ t('ai.studio.workspace.proposalRebind', { n: group.rebindCount }) }}</span>
                <span
                  v-if="group.replaceCount && group.replacesExisting === null"
                  class="proposal-card__badge"
                >× {{ group.replaceCount }}</span>
                <span
                  v-if="group.replacesExisting !== null && group.replacesExisting > 0"
                  class="proposal-card__badge proposal-card__badge--replace"
                >{{ t('ai.studio.workspace.proposalReplacesExisting', { n: group.replacesExisting }) }}</span>
                <span
                  v-else-if="group.replacesExisting === 0"
                  class="proposal-card__badge proposal-card__badge--new"
                >{{ t('ai.studio.workspace.proposalNew', { n: group.replaceCount }) }}</span>
              </div>
              <ul
                v-if="group.items.length"
                class="proposal-card__list"
              >
                <li
                  v-for="item in visibleItems(msg, group)"
                  :key="`${item.action}-${item.name}`"
                  class="proposal-card__list-item"
                >
                  <span
                    class="proposal-card__dot"
                    :class="`proposal-card__dot--${item.action.toLowerCase()}`"
                  />
                  {{ item.name }}
                </li>
                <li
                  v-if="group.items.length > PROPOSAL_CARD_COLLAPSE_AT"
                  class="proposal-card__more"
                >
                  <el-button
                    link
                    size="small"
                    @click="toggleExpanded(msg, group.slice)"
                  >
                    {{ isExpanded(msg, group.slice)
                      ? t('ai.studio.workspace.proposalCollapse')
                      : t('ai.studio.workspace.proposalExpandAll', { n: group.items.length }) }}
                  </el-button>
                </li>
              </ul>
            </div>
            <div
              v-if="msg.proposal.preview?.issues?.length"
              class="proposal-card__issues"
            >
              <div class="proposal-card__issues-title">
                {{ t('ai.studio.workspace.proposalIssues') }}
              </div>
              <div
                v-for="(issue, issueIdx) in msg.proposal.preview.issues"
                :key="issueIdx"
                class="proposal-card__issue"
                :class="`proposal-card__issue--${issue.severity.toLowerCase()}`"
              >
                <el-icon :size="13">
                  <CircleClose v-if="issue.severity === 'ERROR'" />
                  <Warning v-else />
                </el-icon>
                <span><code v-if="issue.fieldPath">{{ issue.fieldPath }}</code>{{ issue.fieldPath ? ': ' : '' }}{{ issue.description }}</span>
              </div>
            </div>
            <div
              v-else-if="msg.proposal.preview && !msg.proposal.preview.checked"
              class="proposal-card__unchecked"
            >
              {{ t('ai.studio.workspace.proposalUnchecked') }}
            </div>
            <div
              v-if="!msg.proposal.applied && hasBlockingIssues(msg.proposal.preview)"
              class="proposal-card__blocked"
            >
              {{ t('ai.studio.workspace.proposalBlocked') }}
            </div>
            <div class="proposal-card__footer">
              <template v-if="msg.proposal.applied">
                <span class="proposal-card__applied">
                  <el-icon><Check /></el-icon>
                  {{ msg.proposal.appliedByName && !msg.proposal.appliedByMe
                    ? t('ai.studio.workspace.appliedBy', { name: msg.proposal.appliedByName })
                    : t('ai.studio.workspace.proposalApplied') }}
                </span>
                <el-button
                  v-if="msg.proposal.undo"
                  link
                  size="small"
                  class="proposal-card__undo"
                  :loading="undoingProposalMsg === msg"
                  @click="undoProposal(msg)"
                >
                  {{ t('ai.studio.workspace.undoButton') }}
                </el-button>
              </template>
              <template v-else>
                <el-button
                  v-if="hasBlockingIssues(msg.proposal.preview)"
                  size="small"
                  plain
                  class="proposal-card__fix"
                  :disabled="copilotReplying || !canModifyThread"
                  :title="t('ai.studio.workspace.proposalFixHint')"
                  @click="requestAiFix(msg)"
                >
                  <el-icon><MagicStick /></el-icon>
                  {{ t('ai.studio.workspace.proposalFixWithAi') }}
                </el-button>
                <el-button
                  type="primary"
                  size="small"
                  :disabled="hasBlockingIssues(msg.proposal.preview) || !canModifyThread"
                  :loading="applyingProposalMsg === msg"
                  @click="applyProposal(msg)"
                >
                  {{ t('ai.studio.workspace.proposalApply') }}
                </el-button>
              </template>
            </div>
          </div>
        </div>
        <!-- 队友正在本阶段生成提案（推送得知，结束即消失） -->
        <div
          v-for="job in teammateJobsInPhase"
          :key="job.jobId"
          class="copilot-msg copilot-msg--assistant copilot-msg--teammate-job"
        >
          <div class="copilot-msg__working">
            <el-icon class="is-loading">
              <Loading />
            </el-icon>
            {{ t('ai.studio.workspace.teammateGenerating', { name: job.authorName }) }}
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
        v-if="proposalSupported || copilotReplying"
        class="copilot__propose"
      >
        <el-button
          v-if="copilotReplying"
          size="small"
          type="danger"
          plain
          :loading="copilotStopping"
          @click="stopCopilot"
        >
          <el-icon><Close /></el-icon>
          {{ t('ai.studio.workspace.stopButton') }}
        </el-button>
        <el-button
          v-else
          size="small"
          type="primary"
          plain
          :disabled="!copilotInput.trim() || !canModifyThread"
          @click="sendCopilotMessage(true)"
        >
          <el-icon><MagicStick /></el-icon>
          {{ t('ai.studio.workspace.proposeButton') }}
        </el-button>
        <span class="copilot__propose-hint">
          {{ copilotReplying ? t('ai.studio.workspace.stopHint') : t('ai.studio.workspace.proposeHint') }}
        </span>
      </div>
      <div
        v-if="sharedThreads && !canModifyThread"
        class="copilot__readonly"
      >
        {{ t('ai.studio.workspace.threadReadOnly') }}
      </div>
      <div class="copilot__input">
        <el-input
          v-model="copilotInput"
          :placeholder="t('ai.studio.workspace.copilotPlaceholder')"
          :disabled="copilotReplying || !canModifyThread"
          @keydown.enter.prevent="sendCopilotMessage()"
        >
          <template #suffix>
            <el-button
              text
              circle
              :disabled="!copilotInput.trim() || copilotReplying || !canModifyThread"
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

    <AiStudioDocumentsDrawer
      v-model="docsDrawerOpen"
      :function-unit-id="fuId"
      :can-modify="canModifyThread"
      :syncing="docSyncRunning"
      :refresh-key="docsRefreshKey"
      @check="checkDocumentsNow"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick, markRaw, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, MagicStick, Check, Close, Promotion, UploadFilled, Loading, CircleClose, Warning, Document } from '@element-plus/icons-vue'
import { useFunctionUnitStore } from '@/stores/functionUnit'
import { functionUnitApi, type ValidationResult } from '@/api/functionUnit'
import { aiGenerationApi, type AiStudioProposalJob } from '@/api/aiGeneration'
import { aiStudioThreadApi, type AiStudioThreadEventData, type AiStudioThreadEventType } from '@/api/aiStudioThread'
import { functionUnitDocumentApi } from '@/api/functionUnitDocument'
import {
  mergeThread, pushLocal, toImportMessages, isStudioStageReadOnly, toHistoryEntries,
  appendServerMessages, latestServerId, SERVER_MESSAGES_PER_PHASE
} from '@/utils/aiStudioSharedThread'
import { useAiStudioThreadEvents } from '@/composables/useAiStudioThreadEvents'
import { blockReadOnlyDesignerInteraction } from '@/utils/readOnlyDesignerInteraction'
import { pickHttpErrorBodyMessage } from '@/utils/httpErrorMessage'
import {
  groupPreview, fallbackGroups, hasBlockingIssues, buildFixRequest, needsReplaceConfirm, formatUndoNote,
  PROPOSAL_CARD_COLLAPSE_AT,
  type ProposalCardGroup, type ProposalCardItem
} from '@/utils/aiStudioProposalCard'
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
import ServiceTaskBindingsPanel from '@/components/ai/ServiceTaskBindingsPanel.vue'
import AiStudioDocSyncCard from '@/components/ai/AiStudioDocSyncCard.vue'
import AiStudioDocumentsDrawer from '@/components/ai/AiStudioDocumentsDrawer.vue'
import {
  AI_STUDIO_ONE_CLICK_PHASE,
  AI_STUDIO_ONE_CLICK_SCOPE,
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
  if (isStageReadOnly.value) return
  persistDraft()
  ElMessage.success(t('ai.studio.workspace.draftSaved'))
}

function confirmPhase() {
  if (isStageReadOnly.value) return
  if (!completedPhases.value.includes(currentPhase.value)) {
    completedPhases.value.push(currentPhase.value)
    void saveSharedCompletedPhases()
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
// 线程按功能单元共享（同组成员看到同一份讨论）：对话消息由后端落库，这里只读取并与本地临时消息合并
// （规则见 utils/aiStudioSharedThread.ts）；后端不可用时整体回落到 localStorage（契约见 utils/aiStudioDraft.ts）。
type CopilotMessage = AiStudioChatMessage

/** 后端共享线程可用；false 时一切照 localStorage 时代的行为走 */
const sharedThreads = ref(false)
/** 当前用户能否在共享线程里发言（MODIFY）；只读成员只能看 */
const canModifyThread = ref(true)

// ---- 文档（Requirements / Function Unit Design）：确认阶段后后台同步 ----
const docsDrawerOpen = ref(false)
/** 该功能单元有文档同步作业在跑（共享状态 + 推送） */
const docSyncRunning = ref(false)
/** 同步结束 / 恢复版本后让文档抽屉重新拉取 */
const docsRefreshKey = ref(0)

async function checkDocumentsNow() {
  docSyncRunning.value = true
  try {
    await aiStudioThreadApi.checkDocuments(fuId.value, currentPhase.value)
  } catch (e) {
    docSyncRunning.value = false
    console.warn('[ai-studio] document check could not be started', e)
  }
}
/** 中间设计区与阶段页脚是否只读（线程 canModify，线程不可用时回落到功能单元 canModify） */
const isStageReadOnly = computed(() =>
  isStudioStageReadOnly(sharedThreads.value, canModifyThread.value, store.current)
)

function onReadOnlyInteraction(event: Event): void {
  if (!isStageReadOnly.value) return
  blockReadOnlyDesignerInteraction(event)
}
/** 每阶段的刷新序号：乱序返回的旧结果直接丢弃 */
const threadRefreshSeq: Partial<Record<AiStudioPhase, number>> = {}

/**
 * 用后端线程刷新某阶段。成功返回 true；失败只 warn 并返回 false（本地线程原样保留）。
 * @param drop 已被后端版本取代的本地消息
 */
async function refreshThread(phase: AiStudioPhase, drop: CopilotMessage[] = []): Promise<boolean> {
  if (!sharedThreads.value) return false
  drop.forEach(m => replacedLocal.add(m))
  const seq = (threadRefreshSeq[phase] ?? 0) + 1
  threadRefreshSeq[phase] = seq
  try {
    const { data } = await aiStudioThreadApi.getMessages(fuId.value, phase)
    if (threadRefreshSeq[phase] !== seq) return true
    const atBottom = isCopilotScrolledToBottom()
    const thread = copilotThread(phase)
    copilotThreads.value[phase] = mergeThread(thread, data ?? [], thread.filter(m => replacedLocal.has(m)))
    if (phase === currentPhase.value && atBottom) scrollCopilotToBottom()
    return true
  } catch (e) {
    console.warn(`[ai-studio] shared thread refresh failed for ${phase}`, e)
    return false
  }
}

/**
 * 已被后端版本取代的本地乐观消息。任何一次刷新（不论是谁触发的）合并时都把它们去掉——
 * 否则推送触发的刷新先到、带 drop 的那次因乱序被丢弃时，同一句提问会显示两遍。
 */
const replacedLocal = new WeakSet<CopilotMessage>()

/**
 * 增量刷新：只拉本地最新 serverId 之后的消息。本地还没有后端消息，或一次拉满上限（中间可能有缺口）时退回整段。
 */
async function refreshThreadIncremental(phase: AiStudioPhase, drop: CopilotMessage[] = []): Promise<boolean> {
  if (!sharedThreads.value) return false
  const afterId = latestServerId(copilotThread(phase))
  if (!afterId) return refreshThread(phase, drop)
  drop.forEach(m => replacedLocal.add(m))
  const seq = (threadRefreshSeq[phase] ?? 0) + 1
  threadRefreshSeq[phase] = seq
  try {
    const { data } = await aiStudioThreadApi.getMessages(fuId.value, phase, afterId)
    if (threadRefreshSeq[phase] !== seq) return true
    if ((data?.length ?? 0) >= SERVER_MESSAGES_PER_PHASE) return refreshThread(phase)
    const atBottom = isCopilotScrolledToBottom()
    const thread = copilotThread(phase)
    copilotThreads.value[phase] = appendServerMessages(thread, data ?? [], thread.filter(m => replacedLocal.has(m)))
    if (phase === currentPhase.value && atBottom) scrollCopilotToBottom()
    return true
  } catch (e) {
    console.warn(`[ai-studio] incremental thread refresh failed for ${phase}`, e)
    return false
  }
}

/** 按 id 刷新一条已在本地的消息（已应用状态变化）；本地没有的不追加，免得打乱顺序。 */
async function refreshThreadMessage(phase: AiStudioPhase, messageId: number): Promise<boolean> {
  const thread = copilotThreads.value[phase]
  if (!thread?.some(m => m.serverId === messageId)) return true
  try {
    const { data } = await aiStudioThreadApi.getMessage(fuId.value, messageId)
    appendServerMessages(copilotThread(phase), [data])
    return true
  } catch (e) {
    console.warn(`[ai-studio] message ${messageId} refresh failed`, e)
    return false
  }
}

async function saveSharedCompletedPhases() {
  if (!sharedThreads.value || !canModifyThread.value) return
  try {
    await aiStudioThreadApi.saveCompletedPhases(fuId.value, completedPhases.value)
  } catch (e) {
    console.warn('[ai-studio] failed to save shared progress', e)
  }
}

/**
 * 进入工作台时接上共享线程：拿共享进度，首次把本地旧线程/进度迁上去（只填后端还空着的阶段）。
 * 任何失败都回落到纯本地模式。
 */
async function initSharedThreads(localCompleted: AiStudioPhase[]): Promise<AiStudioPhase[] | null> {
  try {
    const { data: state } = await aiStudioThreadApi.getState(fuId.value)
    sharedThreads.value = true
    canModifyThread.value = state.canModify
    docSyncRunning.value = state.documentSyncRunning
    const counts = state.messageCounts ?? {}
    const toImport: Record<string, ReturnType<typeof toImportMessages>> = {}
    for (const phase of AI_STUDIO_PHASES) {
      if (counts[phase]) continue
      const msgs = toImportMessages(copilotThreads.value[phase])
      if (msgs.length) toImport[phase] = msgs
    }
    const seedProgress = state.completedPhases === null && localCompleted.length > 0
    if (state.canModify && (Object.keys(toImport).length || seedProgress)) {
      try {
        // 导入后本地旧消息先留着：后端该阶段有内容时合并会自动隐藏它们；
        // 若此处删掉，而之后共享接口又失败（如限流），没刷新过的阶段在本地回落时会是空的
        await aiStudioThreadApi.importThreads(fuId.value, toImport, localCompleted)
        return seedProgress ? localCompleted : (state.completedPhases as AiStudioPhase[] | null)
      } catch (e) {
        console.warn('[ai-studio] importing local threads failed; they stay local for now', e)
      }
    }
    return (state.completedPhases as AiStudioPhase[] | null)
  } catch (e) {
    sharedThreads.value = false
    console.warn('[ai-studio] shared threads unavailable, using browser storage only', e)
    return null
  }
}

/**
 * 窗口重新拿到焦点：补拉一次当前阶段与共享进度。推送在线时这是冗余的兜底，
 * 推送断开期间（重连中）靠它保底。
 */
async function onWorkspaceFocus() {
  if (!sharedThreads.value || document.visibilityState === 'hidden') return
  void refreshThreadIncremental(currentPhase.value)
  await refreshSharedProgress()
}

/**
 * 首次接共享线程失败（限流、DW 重启中）时，稍后再试，别让整个会话都停在纯本地模式。
 */
const SHARED_RETRY_DELAYS_MS = [10000, 20000, 40000, 60000, 120000]
let sharedRetryTimer: ReturnType<typeof setTimeout> | null = null
function scheduleSharedRetry(attempt = 0) {
  if (attempt >= SHARED_RETRY_DELAYS_MS.length || proposalPollingCancelled) return
  sharedRetryTimer = setTimeout(async () => {
    sharedRetryTimer = null
    if (proposalPollingCancelled || sharedThreads.value) return
    const shared = await initSharedThreads(completedPhases.value)
    if (!sharedThreads.value) {
      scheduleSharedRetry(attempt + 1)
      return
    }
    if (shared) completedPhases.value = shared
    void refreshThread(currentPhase.value)
    threadEvents.start()
  }, SHARED_RETRY_DELAYS_MS[attempt])
}

async function refreshSharedProgress(): Promise<boolean> {
  try {
    const { data: state } = await aiStudioThreadApi.getState(fuId.value)
    canModifyThread.value = state.canModify
    docSyncRunning.value = state.documentSyncRunning
    if (state.completedPhases) completedPhases.value = state.completedPhases as AiStudioPhase[]
    return true
  } catch (e) {
    console.warn('[ai-studio] shared progress refresh failed', e)
    return false
  }
}

/**
 * 推送触发的刷新失败（最常见是网关限流）时隔几秒再试，别让这次变化一直不显示到下次推送/获得焦点。
 */
const PUSH_REFRESH_RETRY_MS = [3000, 10000]
function refreshWithRetry(refresh: () => Promise<boolean>, attempt = 0) {
  void refresh().then(ok => {
    if (ok || attempt >= PUSH_REFRESH_RETRY_MS.length || proposalPollingCancelled) return
    setTimeout(() => refreshWithRetry(refresh, attempt + 1), PUSH_REFRESH_RETRY_MS[attempt])
  })
}

// ---- 实时推送：线程变化、进度变化、谁正在生成提案 ----

/** 队友正在跑的提案作业（jobId → 阶段与作者）；自己的作业不在这里，由打字指示表示 */
const teammateJobs = ref<Record<string, { phase: string; authorName: string }>>({})
const teammateJobsInPhase = computed(() =>
  Object.entries(teammateJobs.value)
    .filter(([, j]) => j.phase === currentPhase.value)
    .map(([jobId, j]) => ({ jobId, authorName: j.authorName })))

function onThreadEvent(type: AiStudioThreadEventType, data: AiStudioThreadEventData) {
  switch (type) {
    case 'READY':
      // 新连上（含断线重连）：补拉断线期间的变化；进行中的作业随后逐条重放
      teammateJobs.value = {}
      refreshWithRetry(() => refreshThreadIncremental(currentPhase.value))
      refreshWithRetry(refreshSharedProgress)
      break
    case 'MESSAGE_ADDED':
      // 其他阶段不必现在拉：切过去时会整段刷新
      if (data.phase !== currentPhase.value) break
      if (data.messageId) refreshWithRetry(() => refreshThreadIncremental(currentPhase.value))
      else refreshWithRetry(() => refreshThread(currentPhase.value))
      break
    case 'MESSAGE_UPDATED':
      if (data.phase === currentPhase.value && data.messageId) {
        const phase = currentPhase.value
        const id = data.messageId
        refreshWithRetry(() => refreshThreadMessage(phase, id))
      }
      break
    case 'PROGRESS_UPDATED':
      refreshWithRetry(refreshSharedProgress)
      break
    case 'PROPOSAL_STARTED':
      if (!data.jobId || !data.phase) break
      if (!data.mine) {
        teammateJobs.value = {
          ...teammateJobs.value,
          [data.jobId]: { phase: data.phase, authorName: data.authorName ?? '?' }
        }
      } else if (!copilotReplying.value && data.jobId !== copilotProposalJobId.value
        && (AI_STUDIO_PHASES as readonly string[]).includes(data.phase)) {
        // 自己在别的浏览器/标签页发起的作业：这里也接着等结果
        void resumePendingProposal({ jobId: data.jobId, phase: data.phase as AiStudioPhase, submittedAt: Date.now() })
      }
      break
    case 'DOC_SYNC_STARTED':
      docSyncRunning.value = true
      break
    case 'DOC_SYNC_FINISHED':
      docSyncRunning.value = false
      docsRefreshKey.value++
      // 结果卡写在触发同步的阶段里；确认后通常已经切到下一阶段，提示一下去哪儿看
      if (data.mine && data.phase && data.phase !== currentPhase.value) {
        ElMessage.info(t('ai.studio.docSync.finishedElsewhere', {
          phase: aiStudioPhaseLabel(t, data.phase as AiStudioPhase)
        }))
      }
      break
    case 'PROPOSAL_FINISHED':
      if (data.jobId && teammateJobs.value[data.jobId]) {
        const rest = { ...teammateJobs.value }
        delete rest[data.jobId]
        teammateJobs.value = rest
      }
      break
  }
}

const threadEvents = useAiStudioThreadEvents(() => fuId.value, onThreadEvent)

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
      isPhaseNote: true,
      anchorId: 0
    }]
    copilotThreads.value[phase] = thread
  }
  return thread
}

// 纯读取：线程的按需创建在 watch(currentPhase) / onMounted / 发送时做，不在 computed 里带副作用
const copilotMessages = computed(() => copilotThreads.value[currentPhase.value] ?? [])

function isCopilotScrolledToBottom(): boolean {
  const el = copilotBodyRef.value
  return !el || el.scrollHeight - el.scrollTop - el.clientHeight < 40
}

function scrollCopilotToBottom() {
  void nextTick(() => {
    copilotBodyRef.value?.scrollTo({ top: copilotBodyRef.value.scrollHeight })
  })
}

/** 送给模型的历史窗口：本阶段线程里最近 10 条真实对话（引导语与错误气泡不算）。 */
function copilotHistory(phase: AiStudioPhase) {
  return toHistoryEntries(copilotThread(phase), t('ai.studio.workspace.proposalReady'))
}

/** 结构化提案仅在有 generatedData 切片的阶段可用：除 Validation 外全部（与后端 AiStudioPhase.proposalScope 一致）。 */
const PROPOSAL_PHASES: readonly AiStudioPhase[] = AI_STUDIO_PHASES.filter(p => p !== 'VALIDATION')
const proposalSupported = computed(() => PROPOSAL_PHASES.includes(currentPhase.value))

/**
 * @param overrideText 由按钮（如"让 AI 修正"）发起时用它当消息，输入框里的草稿原样保留
 */
async function sendCopilotMessage(propose = false, overrideText?: string, oneClickFollowUp = false) {
  const text = (overrideText ?? copilotInput.value).trim()
  if (!text || copilotReplying.value || !canModifyThread.value) return
  // 锁定发送时所在的阶段线程：等待期间切走，回复也落回这个线程。
  // 一键生成的修正轮固定落在它自己的线程（后端也只往那里写）
  const phase = oneClickFollowUp ? AI_STUDIO_ONE_CLICK_PHASE : currentPhase.value
  const thread = copilotThread(phase)
  const history = copilotHistory(phase)
  // 共享模式下这条提问先作为本地乐观消息显示，后端落库后被后端版本取代；
  // 本地模式下它就是线程的正式内容（日后首次迁移时上传）
  const asked = pushLocal(thread, { role: 'user', text, localOnly: sharedThreads.value || undefined })
  if (!overrideText) copilotInput.value = ''
  beginCopilotWait(phase)
  try {
    if (propose) {
      const { data: job } = oneClickFollowUp
        ? await aiGenerationApi.studioStartOneClick({ functionUnitId: fuId.value, requirements: text, followUp: true })
        : await aiGenerationApi.studioStartProposal({
          functionUnitId: fuId.value,
          phase,
          message: text,
          history
        })
      // 先落盘再轮询：刷新/离开页面后 onMounted 能凭这条记录接着等同一个作业
      saveAiStudioPendingProposal(fuId.value, { jobId: job.jobId, phase, submittedAt: Date.now() })
      copilotProposalJobId.value = job.jobId
      // 提交成功时后端已把提问写进共享线程
      void refreshThreadIncremental(phase, [asked])
      const done = await pollProposal(job.jobId)
      if (done) await landProposalResult(phase, done)
    } else {
      const res = await aiGenerationApi.studioChat({
        functionUnitId: fuId.value,
        phase,
        message: text,
        history
      }, copilotAbort.signal)
      // 回复与提问都已由后端落库：刷新即可；刷新失败时退回本地追加，别让回复丢了
      if (!(await refreshThreadIncremental(phase, [asked]))) {
        pushLocal(copilotThread(phase), {
          role: 'assistant', text: res.data.reply ?? '', localOnly: sharedThreads.value || undefined
        })
      }
    }
  } catch (e: unknown) {
    // 用户主动 Stop：请求被中止，不算错误，线程里已由 stopCopilot 记过一笔
    if (!isAbortError(e)) pushCopilotError(thread, e)
  } finally {
    endCopilotWait(phase)
  }
}

/** 当前对话轮的中止句柄；每轮 begin 时换新，Stop 时 abort */
let copilotAbort = new AbortController()
const copilotStopping = ref(false)

function isAbortError(e: unknown): boolean {
  const err = e as { code?: string; name?: string } | null
  return err?.code === 'ERR_CANCELED' || err?.name === 'CanceledError' || err?.name === 'AbortError'
}

/**
 * 停止当前这一轮：提案轮调后端取消（中断后台线程、丢弃迟到结果）并停掉轮询；
 * 对话轮直接中止 HTTP。两种都立刻把输入区还给用户，并在线程里记一条"已停止"。
 */
async function stopCopilot() {
  if (!copilotReplying.value || copilotStopping.value) return
  const phase = copilotReplyingPhase.value ?? currentPhase.value
  const jobId = copilotProposalJobId.value
  copilotStopping.value = true
  try {
    if (jobId) {
      cancelledJobIds.add(jobId)
      clearAiStudioPendingProposal(fuId.value)
      try {
        await aiGenerationApi.studioCancelProposal(jobId)
      } catch (e: unknown) {
        // 作业已经不在（404）等同于已停；其它失败提示但仍释放 UI——轮询侧已按 cancelledJobIds 退出
        if ((e as { response?: { status?: number } } | null)?.response?.status !== 404) {
          ElMessage.warning(t('ai.studio.workspace.stopFailed', { reason: errorReason(e) }))
        }
      }
    } else {
      copilotAbort.abort()
    }
    pushLocal(copilotThread(phase), { role: 'assistant', text: t('ai.studio.workspace.stopped'), isPhaseNote: true })
  } finally {
    copilotStopping.value = false
    endCopilotWait(phase)
  }
}

function beginCopilotWait(phase: AiStudioPhase) {
  copilotAbort = new AbortController()
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
  pushLocal(thread, {
    role: 'assistant',
    text: t('ai.studio.workspace.copilotError', { reason }),
    isError: true
  })
}

// ---- 提案作业轮询 ----

const PROPOSAL_POLL_INTERVAL_MS = 4000
/**
 * 连续这么多次轮询失败（网络/5xx）才放弃：约 3 分钟，足够熬过一次 DW 重启——
 * 作业快照落库后，重启完再问到的是终态或"已中断"，而不是白等。
 */
const PROPOSAL_POLL_MAX_CONSECUTIVE_FAILURES = 45

/** 组件卸载后置 true：轮询循环看到就退出，不再往已销毁的线程里推消息 */
let proposalPollingCancelled = false
/** 用户点 Stop 取消掉的作业：对应的轮询循环退出，迟到的终态也不再推进线程 */
const cancelledJobIds = new Set<string>()
onBeforeUnmount(() => {
  proposalPollingCancelled = true
  threadEvents.stop()
  if (sharedRetryTimer) clearTimeout(sharedRetryTimer)
  window.removeEventListener('focus', onWorkspaceFocus)
  document.removeEventListener('visibilitychange', onWorkspaceFocus)
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
    if (proposalPollingCancelled || cancelledJobIds.has(jobId)) return null
    try {
      const { data: job } = await aiGenerationApi.studioGetProposal(jobId)
      failures = 0
      if (cancelledJobIds.has(jobId)) return null
      if (job.status === 'SUCCEEDED' || job.status === 'FAILED' || job.status === 'CANCELLED') {
        clearAiStudioPendingProposal(fuId.value)
        return job
      }
    } catch (e: unknown) {
      if (proposalPollingCancelled || cancelledJobIds.has(jobId)) return null
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

/**
 * 作业终态落到线程：共享模式下成功的结果已由后端写进线程，刷新即可（刷新失败才本地追加）；
 * 失败/取消只是发起人自己的临时说明。
 */
async function landProposalResult(phase: AiStudioPhase, job: AiStudioProposalJob) {
  if (job.status === 'SUCCEEDED' && job.proposalScope === AI_STUDIO_ONE_CLICK_SCOPE) {
    // 一键生成：预校验通过时后端已经把整套设计写进去了，设计器必须重新加载
    // （没写入时重载也无害；卡片上的"已应用"状态以线程里的为准）
    await store.refreshAll(fuId.value)
    stageReloadKey.value++
    if (currentPhase.value === 'FORM_DESIGN') void store.fetchTables(fuId.value)
  }
  if (job.status === 'SUCCEEDED' && await refreshThreadIncremental(phase)) {
    if (!job.proposal) {
      pushLocal(copilotThread(phase), { role: 'assistant', text: t('ai.studio.workspace.proposalNone'), isPhaseNote: true })
    }
    return
  }
  pushProposalResult(copilotThread(phase), job)
}

function pushProposalResult(thread: CopilotMessage[], job: AiStudioProposalJob) {
  if (job.status === 'CANCELLED') {
    // 服务端侧取消（被新请求替换 / 别的标签页点了 Stop）：记一笔说明，不当错误
    pushLocal(thread, { role: 'assistant', text: t('ai.studio.workspace.stopped'), isPhaseNote: true })
    return
  }
  if (job.status === 'FAILED') {
    // DW 重启打断的作业：模型调用无法续跑（凭证不落库），给一键重新发起
    const interrupted = job.errorCode === PROPOSAL_INTERRUPTED
    pushLocal(thread, {
      role: 'assistant',
      text: interrupted
        ? t('ai.studio.workspace.proposalInterrupted')
        : t('ai.studio.workspace.copilotError', { reason: job.errorMessage ?? job.errorCode ?? 'FAILED' }),
      isError: true,
      retryMessage: interrupted && job.message ? job.message : undefined
    })
    return
  }
  const { reply, proposal, proposalScope, preview } = job
  const message: CopilotMessage = {
    role: 'assistant',
    text: reply ?? (proposal ? t('ai.studio.workspace.proposalReady') : ''),
    localOnly: sharedThreads.value || undefined
  }
  if (proposal && proposalScope) {
    message.proposal = { scope: proposalScope, data: proposal, preview: preview ?? null }
  } else {
    // 要求了提案但模型没产出数据块：显式说明，别让用户以为按钮坏了
    message.text = `${message.text}\n\n${t('ai.studio.workspace.proposalNone')}`.trim()
  }
  pushLocal(thread, message)
}

const PROPOSAL_INTERRUPTED = 'AI_STUDIO_PROPOSAL_INTERRUPTED'

/** 用原消息重新发起被中断的提案（按钮只出现一次，点过即收起） */
function retryInterruptedProposal(msg: CopilotMessage) {
  const text = msg.retryMessage
  if (!text || copilotReplying.value) return
  msg.retryMessage = undefined
  void sendCopilotMessage(true, text)
}

/** 进入页面时发现上次的提案作业还没等完：接着轮询同一个 jobId，结果落回发起时的阶段线程。 */
async function resumePendingProposal(pending: AiStudioPendingProposal) {
  if (copilotReplying.value) return
  copilotProposalJobId.value = pending.jobId
  saveAiStudioPendingProposal(fuId.value, pending)
  beginCopilotWait(pending.phase)
  try {
    const done = await pollProposal(pending.jobId)
    if (done) await landProposalResult(pending.phase, done)
  } catch (e: unknown) {
    pushCopilotError(copilotThread(pending.phase), e)
  } finally {
    endCopilotWait(pending.phase)
  }
}

// ---- 提案卡片：摘要与 Apply ----

/** 提案卡分组：有后端预览按 新增/更新/替换 分组列条目，老提案退化为按切片条数。 */
function proposalGroups(proposal: NonNullable<CopilotMessage['proposal']>): ProposalCardGroup[] {
  return proposal.preview
    ? groupPreview(proposal.preview, (k: string) => t(k))
    : fallbackGroups(proposal.data, (k: string) => t(k))
}

/** 展开状态按 消息 + 切片 记，不进持久化 */
const expandedSlices = ref(new WeakMap<CopilotMessage, Set<string>>())
function isExpanded(msg: CopilotMessage, slice: string): boolean {
  return expandedSlices.value.get(msg)?.has(slice) ?? false
}
function toggleExpanded(msg: CopilotMessage, slice: string) {
  const set = expandedSlices.value.get(msg) ?? new Set<string>()
  if (set.has(slice)) set.delete(slice); else set.add(slice)
  // ref 会把 WeakMap 包成响应式集合，set 即触发重算（Set 本身不是响应式的，所以要重新 set）
  expandedSlices.value.set(msg, set)
}
function visibleItems(msg: CopilotMessage, group: ProposalCardGroup): ProposalCardItem[] {
  return group.items.length > PROPOSAL_CARD_COLLAPSE_AT && !isExpanded(msg, group.slice)
    ? group.items.slice(0, PROPOSAL_CARD_COLLAPSE_AT)
    : group.items
}

/** 正在 Apply 的那条消息（同一时刻只允许一个 Apply 在跑） */
const applyingProposalMsg = ref<CopilotMessage | null>(null)
/** 正在撤销的那条消息 */
const undoingProposalMsg = ref<CopilotMessage | null>(null)
/** 改这个值会重挂载中间区设计器——Apply 写库后画布/列表必须重新加载 */
const stageReloadKey = ref(0)

/** 不可撤销的 Apply（清空重建类 scope，由后端预览的 undoable 标出）：先让用户确认一次。 */
async function confirmReplacingApply(msg: CopilotMessage): Promise<boolean> {
  const proposal = msg.proposal
  if (!proposal || !needsReplaceConfirm(proposal.preview)) return true
  const replaces = (proposal.preview?.replacements ?? []).reduce((n, r) => n + r.replacesExisting, 0)
  try {
    await ElMessageBox.confirm(
      t('ai.studio.workspace.applyReplaceConfirmMsg', { n: replaces }),
      t('ai.studio.workspace.applyReplaceConfirmTitle'),
      { type: 'warning', confirmButtonText: t('ai.studio.workspace.proposalApply') }
    )
    return true
  } catch {
    return false
  }
}

async function applyProposal(msg: CopilotMessage) {
  if (!msg.proposal || msg.proposal.applied || applyingProposalMsg.value) return
  if (!(await confirmReplacingApply(msg))) return
  applyingProposalMsg.value = msg
  try {
    const res = await aiGenerationApi.studioApplyProposal({
      functionUnitId: fuId.value,
      scope: msg.proposal.scope,
      generatedData: msg.proposal.data
    })
    msg.proposal.applied = true
    msg.proposal.appliedByMe = true
    msg.proposal.localIssues = undefined
    msg.proposal.undo = res.data?.undoToken
      ? { token: res.data.undoToken, until: res.data.undoableUntil }
      : null
    void syncApplied(msg, true)
    ElMessage.success(t('ai.studio.workspace.proposalApplySuccess'))
    await store.refreshAll(fuId.value)
    stageReloadKey.value++
  } catch (e: any) {
    // 校验失败时后端在 error.details.errors 里逐条给出字段路径与原因；pickHttpErrorBodyMessage 会把它们
    // 拼成 "message (path: reason; …)"，与全局拦截器的提示同一份文案，只是这里多了阶段语境
    const reason = pickHttpErrorBodyMessage(e?.response?.data) ?? e?.message ?? String(e)
    ElMessage.error({ message: t('ai.studio.workspace.proposalApplyFailed', { reason }), duration: 8000, showClose: true })
    // 失败原因写回卡片：预校验漏掉的问题（Apply 前状态又变了）也留在卡上，Apply 自动禁用并给出修正入口，
    // 与预校验的呈现走同一条路径，而不是只活在一条会被关掉的 toast 里
    const details: { errorType?: string; fieldPath?: string; description?: string }[] =
      Array.isArray(e?.response?.data?.error?.details?.errors) ? e.response.data.error.details.errors : []
    if (details.length && msg.proposal) {
      const issues = details.map(d => ({
        severity: 'ERROR' as const,
        errorType: d.errorType ?? 'APPLY_FAILED',
        fieldPath: d.fieldPath ?? '',
        description: d.description ?? reason
      }))
      msg.proposal.preview = msg.proposal.preview
        ? { ...msg.proposal.preview, issues }
        : { items: [], replacements: [], issues, checked: true, undoable: false }
      msg.proposal.localIssues = true
    }
  } finally {
    applyingProposalMsg.value = null
  }
}

/** 把卡片的已应用状态同步到共享线程（队友据此看到"已应用 · by 某某"）；失败只 warn */
async function syncApplied(msg: CopilotMessage, applied: boolean) {
  if (!sharedThreads.value || !msg.serverId) return
  try {
    await aiStudioThreadApi.markApplied(fuId.value, msg.serverId, applied)
  } catch (e) {
    console.warn('[ai-studio] failed to share the applied state', e)
  }
}

/** 把卡片上的问题交给 AI 修正：发起一轮新的提案，历史里带着上一轮提案，模型据此定向改。 */
function requestAiFix(msg: CopilotMessage) {
  const request = buildFixRequest(msg.proposal?.preview, (k: string) => t(k))
  if (!request) return
  // 整套设计的卡片要整套重来：按阶段的提案只会改一个切片，修不了跨切片的引用问题
  void sendCopilotMessage(true, request, msg.proposal?.scope === AI_STUDIO_ONE_CLICK_SCOPE)
}

/** 撤销一次 Apply：后端逐项逆操作，回来后重载设计器并把卡片还原成可 Apply。 */
async function undoProposal(msg: CopilotMessage) {
  const token = msg.proposal?.undo?.token
  if (!token || undoingProposalMsg.value) return
  undoingProposalMsg.value = msg
  try {
    const res = await aiGenerationApi.studioUndoApply(token)
    const notes = res.data?.undoNotes ?? []
    if (msg.proposal) {
      msg.proposal.applied = false
      msg.proposal.appliedByMe = false
      msg.proposal.appliedByName = null
      msg.proposal.undo = null
    }
    void syncApplied(msg, false)
    ElMessage.success({
      message: notes.length
        ? `${t('ai.studio.workspace.undoDone')} ${notes.slice(0, 3).map(n => formatUndoNote(n, (k, p) => t(k, p))).join('; ')}`
        : t('ai.studio.workspace.undoDone'),
      duration: 6000,
      showClose: true
    })
    await store.refreshAll(fuId.value)
    stageReloadKey.value++
  } catch (e: unknown) {
    const err = e as { response?: { data?: { error?: { code?: string } } }; message?: string } | null
    // 令牌过期是常态（窗口过了、后端重启过）：提示后把按钮撤掉，别让用户反复点
    const expired = err?.response?.data?.error?.code === 'AI_STUDIO_UNDO_EXPIRED'
    if (expired && msg.proposal) msg.proposal.undo = null
    ElMessage.error(expired
      ? t('ai.studio.workspace.undoExpired')
      : t('ai.studio.workspace.undoFailed', { reason: pickHttpErrorBodyMessage(err?.response?.data) ?? err?.message ?? String(e) }))
  } finally {
    undoingProposalMsg.value = null
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
  void refreshThread(phase)
})

onMounted(async () => {
  await store.fetchById(fuId.value)

  const draft = loadAiStudioDraft(fuId.value)
  const mode = route.query.mode
  // 先恢复聊天线程再定阶段：阶段 watch 里的 copilotThread() 看到已恢复的线程
  // 就不会重建（引导语只属于真正的首次进入）
  copilotThreads.value = loadAiStudioChatThreads(fuId.value)
  // 共享进度优先于本地草稿里的进度；当前停在哪个阶段仍是每个人自己的
  const sharedCompleted = await initSharedThreads(draft?.completedPhases ?? [])
  const draftCompleted = () => sharedCompleted ?? draft?.completedPhases ?? []

  if (draft && mode === 'continue') {
    currentPhase.value = draft.phase
    completedPhases.value = draftCompleted()
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
      // 共享模式：进度按功能单元共享，一并重置；队友的讨论不删，只清本浏览器的缓存
      void saveSharedCompletedPhases()
      // 新一轮设计：两份文档的下一次保存进入新的主版本（v2.1）
      void functionUnitDocumentApi.startNewRound(fuId.value)
        .catch(e => console.warn('[ai-studio] could not start a new document round', e))
    } catch {
      currentPhase.value = draft.phase
      completedPhases.value = draftCompleted()
    }
  } else if (mode === 'generate') {
    // 一键生成：入口弹窗已确认"整套设计会被替换"并提交了作业（待办作业记录在 localStorage，下面接着等）。
    // 旧进度对新设计不再成立，从第一个阶段开始逐项复核；讨论线程保留
    const hadProgress = draftCompleted().length > 0
    completedPhases.value = []
    currentPhase.value = AI_STUDIO_PHASES[0]
    if (hadProgress) {
      void saveSharedCompletedPhases()
      // 新一轮设计：两份文档的下一次保存进入新的主版本
      void functionUnitDocumentApi.startNewRound(fuId.value)
        .catch(e => console.warn('[ai-studio] could not start a new document round', e))
    }
    // 刷新页面不能再重置一次进度
    void router.replace({ query: { ...route.query, mode: 'continue' } })
  } else if (draft && mode !== 'new') {
    // 直接进入（刷新/收藏链接）：默认续用草稿进度
    currentPhase.value = draft.phase
    completedPhases.value = draftCompleted()
  } else if (sharedCompleted) {
    // 本浏览器没来过，但队友已经推进过：沿用共享进度；选了"继续"就停在第一个未确认的阶段
    completedPhases.value = sharedCompleted
    if (mode === 'continue') {
      currentPhase.value = AI_STUDIO_PHASES.find(p => !sharedCompleted.includes(p)) ?? AI_STUDIO_PHASES[0]
    }
  }

  persistDraft()
  copilotThread(currentPhase.value)
  void refreshThread(currentPhase.value)
  if (sharedThreads.value) threadEvents.start()
  else scheduleSharedRetry()
  window.addEventListener('focus', onWorkspaceFocus)
  document.addEventListener('visibilitychange', onWorkspaceFocus)
  if (currentPhase.value === 'FORM_DESIGN') void store.fetchTables(fuId.value)
  if (currentPhase.value === 'VALIDATION') void runValidation()

  // 上次离开时还有提案作业在跑（异步、服务端继续算）：接着等，不重新发起
  const pending = loadAiStudioPendingProposal(fuId.value)
  if (pending) {
    void resumePendingProposal(pending)
  } else if (sharedThreads.value) {
    // 本浏览器没有待办：看看自己是不是在别的浏览器里发起过、还没跑完的作业
    try {
      const { data: active } = await aiGenerationApi.studioActiveProposal(fuId.value)
      if (active && (AI_STUDIO_PHASES as readonly string[]).includes(active.phase)) {
        void resumePendingProposal({ jobId: active.jobId, phase: active.phase as AiStudioPhase, submittedAt: Date.now() })
      }
    } catch (e) {
      console.warn('[ai-studio] active proposal lookup failed', e)
    }
  }
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
    display: flex;
    align-items: flex-start;
    gap: 12px;
    padding: 16px 20px 12px;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  &__heading {
    flex: 1;
    min-width: 0;
  }

  &__docs {
    flex-shrink: 0;
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

  &__readonly {
    margin: 12px 20px 0;
    width: auto;
  }

  &__designer {
    height: 100%;
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

  &__readonly {
    padding: 8px 14px 0;
    font-size: 12px;
    line-height: 1.5;
    color: var(--el-color-warning-dark-2);
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

  &__group + &__group {
    margin-top: 6px;
  }

  &__group-head {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 6px;
    font-size: 13px;
    line-height: 1.7;
    color: var(--el-text-color-regular);
  }

  &__group-label {
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  &__badge {
    display: inline-flex;
    align-items: center;
    padding: 0 7px;
    border-radius: 999px;
    font-size: 11px;
    line-height: 18px;
    background-color: var(--el-fill-color);
    color: var(--el-text-color-secondary);

    &--new {
      background-color: var(--el-color-success-light-9);
      color: var(--el-color-success);
    }

    &--update {
      background-color: var(--el-color-primary-light-9);
      color: var(--el-color-primary);
    }

    &--replace {
      background-color: var(--el-color-warning-light-9);
      color: var(--el-color-warning-dark-2);
      font-weight: 600;
    }
  }

  &__list {
    list-style: none;
    margin: 2px 0 0;
    padding: 0 0 0 18px;
  }

  &__list-item {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12.5px;
    line-height: 1.7;
    color: var(--el-text-color-regular);
    font-family: var(--el-font-family-mono, ui-monospace, monospace);
  }

  &__dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background-color: var(--el-color-info);

    &--new, &--bind { background-color: var(--el-color-success); }
    &--update, &--rebind { background-color: var(--el-color-primary); }
    &--replace { background-color: var(--el-color-warning); }
  }

  &__more {
    padding-left: 12px;
  }

  &__issues {
    margin-top: 10px;
    padding-top: 8px;
    border-top: 1px dashed var(--el-border-color);
  }

  &__issues-title {
    font-size: 12px;
    font-weight: 600;
    color: var(--el-text-color-secondary);
    margin-bottom: 4px;
  }

  &__issue {
    display: flex;
    align-items: flex-start;
    gap: 6px;
    font-size: 12.5px;
    line-height: 1.6;

    code {
      font-size: 11.5px;
      padding: 0 3px;
      border-radius: 3px;
      background-color: var(--el-fill-color);
    }

    &--error { color: var(--el-color-danger); }
    &--warning { color: var(--el-color-warning-dark-2); }

    .el-icon { margin-top: 3px; flex: none; }
  }

  &__unchecked {
    margin-top: 8px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  &__undo {
    margin-left: 10px;
  }

  &__fix {
    margin-right: 8px;
  }

  &__blocked {
    margin-top: 8px;
    font-size: 12px;
    line-height: 1.6;
    color: var(--el-color-danger);
  }

  &__footer {
    display: flex;
    justify-content: flex-end;
    align-items: center;
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

  // 队友的提问靠左、换底色，与自己的提问区分开
  &--user#{&}--teammate {
    align-self: flex-start;

    .copilot-msg__bubble {
      background-color: var(--el-color-info-light-9);
    }
  }

  &__who--teammate {
    font-weight: 500;
  }

  &__working {
    margin-top: 6px;
    font-size: 12px;
    line-height: 1.5;
    color: var(--el-text-color-secondary);
  }

  &--teammate-job &__working {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    margin-top: 0;
  }

  &__retry {
    margin-top: 6px;
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
