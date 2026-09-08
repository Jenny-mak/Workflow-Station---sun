<template>
  <div class="task-detail-page">
    <!-- Page header -->
    <div class="page-header">
      <el-button
        :icon="ArrowLeft"
        @click="$router.back()"
      >
        {{ t('common.back') }}
      </el-button>
      <h1>{{ taskInfo.taskName || t('task.detail') }}</h1>
      <el-tag
        :type="getPriorityType(taskInfo.priority)"
        size="small"
      >
        {{ getPriorityLabel(taskInfo.priority) }}
      </el-tag>
    </div>

    <!-- Loading state -->
    <div
      v-if="loading"
      class="skeleton-content"
    >
      <el-skeleton
        animated
        :count="3"
      >
        <template #template>
          <el-skeleton-item
            variant="rect"
            style="height: 120px; margin-bottom: 20px;"
          />
          <el-skeleton-item
            variant="rect"
            style="height: 300px; margin-bottom: 20px;"
          />
          <el-skeleton-item
            variant="rect"
            style="height: 200px;"
          />
        </template>
      </el-skeleton>
    </div>

    <!-- Task loading error -->
    <div
      v-else-if="taskError"
      class="error-content"
    >
      <el-result
        icon="warning"
        :title="taskError"
      >
        <template #extra>
          <el-button
            type="primary"
            @click="$router.back()"
          >
            {{ t('common.back') }}
          </el-button>
          <el-button @click="loadTaskDetail">
            {{ t('common.reset') }}
          </el-button>
        </template>
      </el-result>
    </div>

    <!-- Main content -->
    <div
      v-else
      class="content-sections"
    >
      <!-- Section 1: Basic info -->
      <TaskBasicInfo
        :task-info="taskInfo"
        :format-date="formatDate"
        :get-current-assignee-display="getCurrentAssigneeDisplay"
        :get-delegation-status-display="getDelegationStatusDisplay"
        :is-completed="isCompletedTask"
      />

      <!-- Section 1b: BU Role claim state (claim / unclaim / held by a colleague) -->
      <TaskClaimBanner
        v-if="!isCompletedTask"
        :task="taskInfo"
        :submitting="claimSubmitting"
        @claim="handleClaimTask"
        @unclaim="handleUnclaimTask"
        @force-unclaim="handleForceUnclaimTask"
        @reassign="handleReassignTask"
      />

      <div
        v-if="detailUiPhase === 1"
        class="section form-section"
      >
        <el-skeleton
          animated
          :rows="6"
        />
      </div>

      <!-- Section 2: Process diagram (collapsible default on; async + viewport gate) -->
      <div
        v-if="detailUiPhase >= 3"
        ref="workflowSectionRef"
      >
        <WorkflowDiagramCollapsibleSection
          :title="t('task.workflowDiagram')"
        >
          <template #badge>
            <el-tag
              v-if="!isCompletedTask"
              type="warning"
              size="small"
            >
              {{ taskInfo.taskName || t('task.pending') }}
            </el-tag>
          </template>
          <el-alert
            v-if="processError && !bpmnXml && processNodes.length === 0"
            :title="processError"
            type="warning"
            show-icon
            :closable="false"
          />
          <template v-if="bpmnXml || processNodes.length > 0">
            <el-skeleton
              v-if="!diagramInViewport"
              animated
              :rows="4"
            />
            <Suspense v-else>
              <ProcessDiagramAsync
                :nodes="processNodes"
                :flows="processFlows"
                :bpmn-xml="bpmnXml"
                :current-node-id="currentNodeId"
                :completed-node-ids="completedNodeIds"
                :selected-node-id="selectedNodeId ?? ''"
                :show-toolbar="true"
                :show-legend="true"
                :show-current-step="!isCompletedTask"
                @node-click="handleNodeClick"
              />
              <template #fallback>
                <el-skeleton
                  animated
                  :rows="4"
                />
              </template>
            </Suspense>
          </template>
          <el-empty
            v-else
            :description="t('task.noProcessDefinition')"
          />
        </WorkflowDiagramCollapsibleSection>
      </div>

      <el-tabs
        v-if="detailUiPhase >= 2"
        v-model="activeDetailTab"
        class="detail-navigation"
      >
        <el-tab-pane
          :label="t('process.general')"
          name="general"
          lazy
        >
      <!-- Selected node form (click a node in the diagram to show its form) -->
      <div
        v-if="detailUiPhase >= 2 && selectedNodeId && selectedNodeForm"
        class="section form-section"
      >
        <div class="section-header">
          <el-icon><Document /></el-icon>
          <span>{{ selectedNodeForm.formName }}</span>
          <el-tag
            v-if="selectedNodeForm.isCurrentTask && !isCompletedTask"
            type="warning"
            size="small"
          >
            {{ t('task.currentStep') }}
          </el-tag>
          <el-tag
            v-else
            type="info"
            size="small"
          >
            {{ t('task.readonly') }}
          </el-tag>
          <el-button
            size="small"
            style="margin-left: auto;"
            @click="clearNodeSelection"
          >
            {{ t('common.back') }}
          </el-button>
        </div>
        <div class="section-content">
          <div
            v-if="selectedNodeForm.fields.length > 0 || selectedNodeForm.tabs.length > 0"
            class="form-container"
          >
            <FormRenderer
              :fields="selectedNodeForm.isCurrentTask ? formFields : selectedNodeForm.fields"
              :tabs="selectedNodeForm.isCurrentTask ? formTabs : selectedNodeForm.tabs"
              :process-instance-id="taskInfo.processInstanceId"
              :model-value="selectedNodeForm.isCurrentTask ? formData : selectedNodeForm.values"
              :label-width="formLabelWidth"
              :readonly="selectedNodeForm.isCurrentTask ? formReadOnly : true"
              :primary-read-only="selectedNodeForm.isCurrentTask ? primaryReadOnly : false"
              :sub-table-bindings="selectedNodeForm.isCurrentTask ? subTableBindings : selectedNodeForm.subTableBindings"
              :linked-sub-table-bindings="selectedNodeForm.isCurrentTask ? linkableSubTableBindings : selectedNodeForm.subTableBindings"
              :native-sub-table-binding-ids="selectedNodeForm.isCurrentTask ? mainFormNativeSubTableBindingIds : selectedNodeForm.nativeSubTableBindingIds"
              :form-config="selectedNodeForm.isCurrentTask ? mainFormConfig : selectedNodeForm.formConfig"
              :form-options="selectedNodeForm.isCurrentTask ? formFormOptions : undefined"
              :preview-sub-tables="selectedNodeForm.isCurrentTask ? isMiSubTaskMode : true"
              :task-id="selectedNodeForm.isCurrentTask ? effectiveTaskId : undefined"
              :allow-sub-table-assign="selectedNodeForm.isCurrentTask ? allowSubTableAssignForCurrentTask : false"
              :suppress-link-form-initial-data="selectedNodeForm.isCurrentTask ? (isMiSubTaskMode && !isCompletedTask) : false"
              :show-link-form-dialog-footer="selectedNodeForm.isCurrentTask ? (!isCompletedTask && !formReadOnly) : false"
              view-context="assigneeTodo"
              :auto-open-file-preview="selectedNodeForm.isCurrentTask"
              :current-mi-row-id="currentMiRowId"
              :function-unit-id="functionUnitIdRef"
              :primary-table-binding="primaryTableBinding ?? undefined"
              :request-id-config="requestIdConfig"
              :field-permissions="selectedNodeForm.isCurrentTask ? taskFieldPermissions : undefined"
              @update:model-value="val => { if (selectedNodeForm.isCurrentTask) formData = { ...formData, ...val } }"
              @update:sub-table-data="(bindingId, rows) => { if (selectedNodeForm.isCurrentTask) syncMainSubTableRows(bindingId, rows) }"
            />
          </div>
          <el-empty
            v-else
            :description="t('task.noFormData')"
          />
        </div>
      </div>
      <!-- Node selected but no form bound -->
      <div
        v-else-if="detailUiPhase >= 2 && selectedNodeId && !selectedNodeForm"
        class="section form-section"
      >
        <div class="section-header">
          <el-icon><Document /></el-icon>
          <span>{{ selectedNodeId }}</span>
        </div>
        <div class="section-content">
          <el-empty :description="t('task.noFormBound')" />
          <div style="text-align: center; margin-top: 8px;">
            <el-button
              size="small"
              @click="clearNodeSelection"
            >
              {{ t('common.back') }}
            </el-button>
          </div>
        </div>
      </div>
<!-- Task 17.1 / 17.4: Collapsible Process Form panel -->
      <div
        v-if="detailUiPhase >= 2 && showProcessFormPanel && processFormData"
        class="section process-form-section"
      >
        <el-collapse v-model="processFormCollapse">
          <el-collapse-item
            :title="isReturnToRequester ? t('process.processForm') : t('process.processFormReadonly')"
            name="processForm"
          >
            <div class="section-content">
              <FormRenderer
                v-if="processFormFields.length > 0 || processFormTabs.length > 0 || processFormSubTableBindings.length > 0"
                :fields="processFormFields"
                :tabs="processFormTabs"
                :model-value="processFormValues"
                :process-instance-id="taskInfo.processInstanceId"
                :label-width="formLabelWidth"
                :readonly="!processFormEditable"
                :primary-read-only="primaryReadOnly"
                :sub-table-bindings="processFormSubTableBindings"
                :linked-sub-table-bindings="processFormSubTableBindings"
                :native-sub-table-binding-ids="processFormNativeSubTableBindingIds"
                :form-config="processFormFormConfig"
                view-context="initiatorRequest"
                :show-link-form-dialog-footer="processFormEditable"
                :function-unit-id="functionUnitIdRef"
                :primary-table-binding="primaryTableBinding ?? undefined"
                :request-id-config="requestIdConfig"
                @update:model-value="val => processFormValues = { ...processFormValues, ...val }"
                @update:sub-table-data="(bindingId: number, rows: any[]) => {
                  const target = processFormSubTableBindings.find((b: any) => Number(b?.bindingId) === Number(bindingId))
                  if (target) target.data = rows
                }"
              />
              <el-empty
                v-else
                :description="t('task.noFormData')"
              />
              <div
                v-if="processFormEditable"
                class="process-form-actions"
                style="margin-top: 16px; text-align: right;"
              >
                <el-button
                  type="primary"
                  :loading="submitting"
                  @click="handleProcessFormSubmit"
                >
                  {{ t('common.submit') }}
                </el-button>
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>

      <!-- Section 3: Form data (hide normal task form card when previewing a selected node) -->
      <div
        v-if="detailUiPhase >= 2 && !selectedNodeId && (!isMiSubTaskMode || formFields.length > 0 || formTabs.length > 0 || formFieldsAfterTabs.length > 0 || subTableBindings.length > 0)"
        class="section form-section"
      >
        <div class="section-header">
          <el-icon><Document /></el-icon>
          <span>{{ currentFormName || t('task.taskForm') }}</span>
        </div>
        <div class="section-content">
          <div
            v-if="formFields.length > 0 || formTabs.length > 0 || formFieldsAfterTabs.length > 0 || subTableBindings.length > 0"
            class="form-container"
          >
            <FormRenderer
              v-if="formRenderReady"
              :fields="formFields"
              :tabs="formTabs"
              :fields-after-tabs="formFieldsAfterTabs"
              :process-instance-id="taskInfo.processInstanceId"
              :model-value="formData"
              :label-width="formLabelWidth"
              :readonly="formReadOnly"
              :primary-read-only="primaryReadOnly"
              :sub-table-bindings="subTableBindings"
              :linked-sub-table-bindings="linkableSubTableBindings"
              :native-sub-table-binding-ids="mainFormNativeSubTableBindingIds"
              :form-config="mainFormConfig"
              :form-options="formFormOptions"
              :preview-sub-tables="isMiSubTaskMode"
              :task-id="effectiveTaskId"
              :allow-sub-table-assign="allowSubTableAssignForCurrentTask"
              :suppress-link-form-initial-data="isMiSubTaskMode && !isCompletedTask"
              :show-link-form-dialog-footer="!isCompletedTask && !formReadOnly"
              view-context="assigneeTodo"
              :auto-open-file-preview="true"
              :current-mi-row-id="currentMiRowId"
              :function-unit-id="functionUnitIdRef"
              :primary-table-binding="primaryTableBinding ?? undefined"
              :request-id-config="requestIdConfig"
              :field-permissions="taskFieldPermissions"
              @update:model-value="val => formData = { ...formData, ...val }"
              @update:sub-table-data="syncMainSubTableRows"
              @save="saveCurrentTaskFormWithMiPersist"
            />
          </div>
          <el-empty :description="t('task.noFormData')" />
        </div>
      </div>

        </el-tab-pane>

      <!-- Task 19.2: Change history panel (title and collapse handled internally by ChangeHistoryPanel) -->
        <el-tab-pane
          :label="t('changeHistory.title')"
          name="change-history"
          lazy
        >
          <div
            v-if="detailUiPhase >= 3 && taskInfo.processInstanceId"
            class="section change-history-section"
          >
            <ChangeHistoryPanel
              :process-instance-id="taskInfo.processInstanceId"
              :snapshot-time="completedHistorySnapshotTime"
              :task-id="taskId"
              :task-instance-id="completedHistoryTaskId"
              :show-header="false"
              :sensitive-mask-lookup="sensitiveMaskLookup"
            />
          </div>
        </el-tab-pane>

      <!-- Section 4: Flow history -->
        <el-tab-pane
          :label="t('task.flowHistory')"
          name="flow-history"
          lazy
        >
          <TaskHistorySection
            v-if="detailUiPhase >= 3"
            :history-records="historyRecords"
            :history-error="historyError"
            :show-header="false"
          />
        </el-tab-pane>

        <el-tab-pane
          v-if="isCompletedTask"
          :label="t('task.completedSnapshot')"
          name="completed-snapshot"
          lazy
        >
          <TaskSnapshotSection
            v-if="detailUiPhase >= 3"
            :is-completed-task="isCompletedTask"
            :completed-form-data="completedFormData"
            :form-fields="formFields"
            :form-tabs="formTabs"
            :form-fields-after-tabs="formFieldsAfterTabs"
            :sub-table-bindings="linkableSubTableBindings"
          />
        </el-tab-pane>
      </el-tabs>

      <!-- Section 5: Action buttons (hidden for completed tasks) -->
      <TaskActionBar
        :is-completed-task="isCompletedTask"
        :claim-locked="claimLocked"
        :show-implicit-save-action="showImplicitSaveAction"
        :saving-task-form="savingTaskForm"
        :actions="taskInfo.actions"
        :can-delegate="!!String(taskInfo.assignee || '').trim()"
        :get-button-type="getButtonType"
        :get-icon-component="getIconComponent"
        :get-action-label="getActionLabel"
        @save="saveCurrentTaskFormWithMiPersist"
        @custom-action="handleCustomAction"
        @approve="handleApprove"
        @reject="handleReject"
      />
    </div>

    <!-- Approval dialog -->
    <ApproveDialog
      v-model="approveDialogVisible"
      :title="approveDialogTitle"
      :form-data="approveForm"
      :submitting="submitting"
      @confirm="submitApprove"
    />

    <!-- Delegate/Transfer dialog -->
    <ActionDialog
      v-model="actionDialogVisible"
      :title="actionDialogTitle"
      :current-action="currentAction"
      :form-data="actionForm"
      :user-options="userOptions"
      :submitting="submitting"
      @confirm="submitAction"
      @opened="onActionDialogOpened"
    />

    <!-- Form popup dialog -->
    <FormPopupDialog
      v-model="formPopupVisible"
      :title="formPopupTitle"
      :width="formPopupWidth"
      :fields="formPopupFields"
      :tabs="formPopupTabs"
      :form-data="formPopupData"
      :label-width="formPopupLabelWidth"
      :readonly="formPopupReadOnly"
      :submitting="submitting"
      :sub-table-bindings="formPopupSubTableBindings"
      :linked-sub-table-bindings="formPopupLinkedSubTableBindings ?? formPopupSubTableBindings"
      :native-sub-table-binding-ids="formPopupNativeSubTableBindingIds"
      :form-config="formPopupFormConfig"
      :view-context="formPopupViewContext"
      @update:form-data="val => formPopupData = { ...formPopupData, ...val }"
      @update:sub-table-data="handleFormPopupSubTableUpdate"
      @submit="submitFormPopup"
    />
<!-- MI subtask fill-form dialog -->
    <MiFillDialog
      v-model="miFillDialogVisible"
      :title="currentFormName || t('task.taskForm')"
      :fields="formFields"
      :tabs="formTabs"
      :form-data="miFillDialogData"
      label-width="auto"
      :form-read-only="formReadOnly"
      :dialog-read-only="miFillDialogReadOnly"
      :sub-table-bindings="miFillSubTableBindings"
      :is-mi-sub-task-mode="isMiSubTaskMode"
:is-completed-task="isCompletedTask" @update:form-data="val => miFillDialogData = { ...miFillDialogData, ...val }" @update:sub-table-data="syncMiFillSubTableRows" @confirm="saveMiFillDialog"
    />
    <TaskReassignDialog
      v-model="reassignDialogVisible"
      :candidate-user-ids="taskInfo?.candidateUserIds ?? []"
      :current-holder-id="taskInfo?.assignee"
      :submitting="claimSubmitting"
      @confirm="confirmReassignTask"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount, computed, nextTick, ref, watch, defineAsyncComponent } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowLeft } from '@element-plus/icons-vue'
import type { ProcessNode } from '@/components/ProcessDiagram.vue'
import WorkflowDiagramCollapsibleSection from '@/components/WorkflowDiagramCollapsibleSection.vue'

/** Lazy-load bpmn-js (~190kB gzip) only when the diagram section enters the viewport. */
const ProcessDiagramAsync = defineAsyncComponent(
  () => import('@/components/ProcessDiagram.vue'),
)
import FormRenderer from '@/components/FormRenderer.vue'
import ChangeHistoryPanel from '@/components/ChangeHistoryPanel.vue'
import TaskBasicInfo from '@/components/tasks/TaskBasicInfo.vue'
import ApproveDialog from '@/components/tasks/ApproveDialog.vue'
import ActionDialog from '@/components/tasks/ActionDialog.vue'
import FormPopupDialog from '@/components/tasks/FormPopupDialog.vue'
import MiFillDialog from '@/components/tasks/MiFillDialog.vue'
import TaskSnapshotSection from '@/components/tasks/TaskSnapshotSection.vue'
import TaskHistorySection from '@/components/tasks/TaskHistorySection.vue'
import TaskActionBar from '@/components/tasks/TaskActionBar.vue'
import TaskClaimBanner from '@/components/tasks/TaskClaimBanner.vue'
import TaskReassignDialog from '@/components/tasks/TaskReassignDialog.vue'
import { useTaskForm } from '@/composables/tasks/useTaskForm'
import { useBpmnParser } from '@/composables/tasks/useBpmnParser'
import { useTaskDisplay } from '@/composables/tasks/useTaskDisplay'
import { useTaskActions } from '@/composables/tasks/useTaskActions'
import { useTaskClaimActions } from '@/composables/tasks/useTaskClaimActions'
import { useCustomActions } from '@/composables/tasks/useCustomActions'
import { clearBpmnParseCache } from '@/utils/bpmnParseCache'
import { reconcilePortalWorkspaceSession } from '@/api/auth'
import {
  buildSensitiveMaskLookup,
  parseFormConfigJsonsProcessFirst,
} from '@/utils/sensitiveMaskLookup'
import type { TaskDetailCtx } from '@/composables/taskDetail/context'
import { createTaskDetailState } from '@/composables/taskDetail/useTaskDetailState'
import { createTaskDetailFormSchema } from '@/composables/taskDetail/useTaskDetailFormSchema'
import { createTaskDetailFieldExtraction } from '@/composables/taskDetail/useTaskDetailFieldExtraction'
import { createTaskDetailLinkTargets } from '@/composables/taskDetail/useTaskDetailLinkTargets'
import { createTaskDetailMiScope } from '@/composables/taskDetail/useTaskDetailMiScope'
import { createTaskDetailSubTableHydration } from '@/composables/taskDetail/useTaskDetailSubTableHydration'
import { createTaskDetailSubTableSync } from '@/composables/taskDetail/useTaskDetailSubTableSync'
import { createTaskDetailMiLinkChild } from '@/composables/taskDetail/useTaskDetailMiLinkChild'
import { createTaskDetailMiBackfill } from '@/composables/taskDetail/useTaskDetailMiBackfill'
import { createTaskDetailMiIsolation } from '@/composables/taskDetail/useTaskDetailMiIsolation'
import { createTaskDetailMiResync } from '@/composables/taskDetail/useTaskDetailMiResync'
import { createTaskDetailMiPersist } from '@/composables/taskDetail/useTaskDetailMiPersist'
import { createTaskDetailLayoutSync } from '@/composables/taskDetail/useTaskDetailLayoutSync'
import { createTaskDetailNodeFormMap } from '@/composables/taskDetail/useTaskDetailNodeFormMap'
import { createTaskDetailDiagram } from '@/composables/taskDetail/useTaskDetailDiagram'
import { createTaskDetailFuLoader } from '@/composables/taskDetail/useTaskDetailFuLoader'
import { createTaskDetailPrevForms } from '@/composables/taskDetail/useTaskDetailPrevForms'
import { createTaskDetailFormsLoader } from '@/composables/taskDetail/useTaskDetailFormsLoader'
import { createTaskDetailPopupHelpers } from '@/composables/taskDetail/useTaskDetailPopup'
import { createTaskDetailLoader } from '@/composables/taskDetail/useTaskDetailLoader'

const { t } = useI18n()
const route = useRoute()

const taskId = route.params.id as string
const activeDetailTab = ref('general')
const fallbackProcessInstanceId = computed(() => {
  const v = route.query.processInstanceId
  return typeof v === 'string' && v.trim() ? v.trim() : ''
})

// Page state (refs / computeds) — declarations live in composables/taskDetail/useTaskDetailState.ts
const state = createTaskDetailState({ taskId })
const {
  loading,
  detailUiPhase,
  workflowSectionRef,
  diagramInViewport,
  submitting,
  taskInfo,
  effectiveTaskId,
  taskError,
  processError,
  historyError,
  selectedNodeId,
  formRenderReady,
  lastBindingRelationTableMap,
  selectedNodeForm,
  subTableBindings,
  linkableSubTableBindings,
  isMiSubTaskMode,
  miFillDialogVisible,
  miFillDialogData,
  miFillSubTableBindings,
  miFillDialogReadOnly,
  historyRecords,
  approveDialogVisible,
  approveDialogTitle,
  currentApproveAction,
  approveForm,
  actionDialogVisible,
  actionDialogTitle,
  currentAction,
  actionForm,
  userOptions,
  userSearchLoading,
  processFormData,
  processFormCollapse,
  processFormEditable,
  processFormFields,
  processFormTabs,
  processFormValues,
  processFormSubTableBindings,
  processFormFormConfig,
  processFormNativeSubTableBindingIds,
  taskFormDTO,
  hasConfiguredSaveAction,
  completedFormData,
  isCompletedTask,
  miSubProcessScopeName,
  isReturnToRequester,
  showProcessFormPanel,
  primaryReadOnly,
  primaryTableBinding,
  functionUnitIdRef,
  mainFormConfig,
  mainFormNativeSubTableBindingIds,
  allowSubTableAssignForCurrentTask,
} = state
// Main-table Request ID config — enables live recompute of the readonly __request_id field
// on the To-Do form; completed/process forms reuse it for display parity.
const requestIdConfig = computed<{ fieldNames: string[]; separator?: string } | null>(() => {
  const fromTask = (taskFormDTO.value as { requestIdConfig?: { fieldNames: string[]; separator?: string } | null } | null)?.requestIdConfig
  if (fromTask) return fromTask
  const fromProcessRef = (taskFormDTO.value as { processFormRef?: { requestIdConfig?: { fieldNames: string[]; separator?: string } | null } } | null)?.processFormRef?.requestIdConfig
  if (fromProcessRef) return fromProcessRef
  const fromCompleted = (completedFormData.value as { processFormRef?: { requestIdConfig?: { fieldNames: string[]; separator?: string } | null } } | null)?.processFormRef?.requestIdConfig
  return fromCompleted ?? null
})

/** Current task-node field permissions (bare main-table key, `${bindingId}:${fieldName}` sub-table key). */
const taskFieldPermissions = computed<Record<string, string> | null>(() => {
  return (taskFormDTO.value as { fieldPermissions?: Record<string, string> | null } | null)?.fieldPermissions ?? null
})

watch(
  () => detailUiPhase.value >= 3,
  (active) => {
    if (!active) {
      diagramInViewport.value = false
      disconnectDiagramViewportObserver()
      return
    }
    void buildNodeFormMapIfNeeded()
    void nextTick(() => {
      connectDiagramViewportObserver()
      // Fallback: if observer misses (already in viewport), still mount diagram after phase 3.
      setTimeout(() => {
        if (detailUiPhase.value >= 3 && !diagramInViewport.value && (bpmnXml.value || processNodes.value.length > 0)) {
          diagramInViewport.value = true
        }
      }, 400)
    })
  },
)

// Display helpers
const taskDisplay = useTaskDisplay(taskInfo as any)
const {
  formatDate,
  getCurrentAssigneeDisplay,
  getDelegationStatusDisplay,
  getPriorityLabel,
  getPriorityType,
  getButtonType,
  getActionLabel,
  getIconComponent
} = taskDisplay

const bpmnParser = useBpmnParser({ taskInfo: taskInfo as any, historyRecords, isCompletedTask })
const { processNodes, processFlows, completedNodeIds, currentNodeId, bpmnXml } = bpmnParser

/**
 * 保存时判定「这一行是不是当前参与者的」——复用 `rowBelongsToCurrentMiScope`，不另造判据。
 * 延迟到调用时才读 `ctx`：`useTaskForm` 构造早于 `ctx` 装配。
 * 解析不出当前参与者（`currentMiRowId == null`）时返回 null，让合并退回保守并集。
 */
const resolveMiRowOwnershipPredicate = (binding: unknown) => {
  const myRowId = (ctx as any)?.currentMiRowId?.value
  if (myRowId == null) return null
  const belongs = (ctx as any)?.rowBelongsToCurrentMiScope
  if (typeof belongs !== 'function') return null
  return (row: unknown) => belongs(row, myRowId, binding as any)
}

const taskForm = useTaskForm({ subTableBindings, isMiSubTaskMode, isCompletedTask, effectiveTaskId, taskFormDTO: taskFormDTO as any, bindingRelationTableMap: lastBindingRelationTableMap, miSubProcessScopeName, resolveMiRowOwnershipPredicate })
const { formFields, formTabs, formFieldsAfterTabs, formData, currentFormName, formReadOnly, formLabelWidth, formFormOptions, savingTaskForm, buildCurrentTaskFormSubmitPayload, clearAutosaveTimer: clearFormAutosaveTimer } = taskForm

const showImplicitSaveAction = computed(() => !formReadOnly.value && !hasConfiguredSaveAction.value)

/** Mask configs for Change History only (form stages use each form's own sensitiveMask). */
const sensitiveMaskLookup = computed(() => buildSensitiveMaskLookup({
  formConfigJsons: [
    ...parseFormConfigJsonsProcessFirst(state.cachedContentForms),
    processFormFormConfig.value,
    mainFormConfig.value,
    selectedNodeForm.value?.formConfig,
  ],
  formFields: formFields.value,
  formTabs: [
    ...(formTabs.value || []),
    ...(processFormTabs.value || []),
    ...(selectedNodeForm.value?.tabs || []),
  ],
  formFieldsAfterTabs: formFieldsAfterTabs.value,
  extraFieldLists: [
    processFormFields.value,
    selectedNodeForm.value?.fields,
  ],
  subTableBindings: [
    ...(subTableBindings.value || []),
    ...(processFormSubTableBindings.value || []),
    ...(selectedNodeForm.value?.subTableBindings || []),
  ],
}))

/**
 * Shared mutable context — the function clusters extracted to
 * composables/taskDetail/* register themselves here. Cross-module calls
 * resolve through ctx at invocation time (post-setup), mirroring the
 * original single-file function hoisting semantics.
 */
const ctx = {
  ...state,
  t,
  route,
  taskId,
  taskForm,
  bpmn: bpmnParser,
  display: taskDisplay,
} as unknown as TaskDetailCtx
Object.assign(ctx, createTaskDetailMiScope(ctx))
Object.assign(ctx, createTaskDetailFormSchema(ctx))
Object.assign(ctx, createTaskDetailFieldExtraction(ctx))
Object.assign(ctx, createTaskDetailLinkTargets(ctx))
Object.assign(ctx, createTaskDetailSubTableHydration(ctx))
Object.assign(ctx, createTaskDetailSubTableSync(ctx))
Object.assign(ctx, createTaskDetailMiLinkChild(ctx))
Object.assign(ctx, createTaskDetailMiBackfill(ctx))
Object.assign(ctx, createTaskDetailMiIsolation(ctx))
Object.assign(ctx, createTaskDetailMiResync(ctx))
Object.assign(ctx, createTaskDetailMiPersist(ctx))
Object.assign(ctx, createTaskDetailLayoutSync(ctx))
Object.assign(ctx, createTaskDetailNodeFormMap(ctx))
Object.assign(ctx, createTaskDetailDiagram(ctx))
Object.assign(ctx, createTaskDetailPrevForms(ctx))
Object.assign(ctx, createTaskDetailFuLoader(ctx))
Object.assign(ctx, createTaskDetailFormsLoader(ctx))

// Cross-module fns still referenced directly by the SFC (template bindings,
// the phase watcher, lifecycle, and the taskActions/customActions wiring).
// The remaining clusters are invoked through `ctx.*` inside the extracted
// composables (e.g. useTaskDetailLoader), so they are not destructured here.
const {
  currentMiRowId,
  patchFormDataSubTablesFromCurrentBindings,
  syncMainSubTableRows,
  mergeMiParticipantScalarsFromForm,
  protectMainRecordScalarsInSubmitPayload,
  saveCurrentTaskFormWithMiPersist,
  syncMiFillSubTableRows,
  saveMiFillDialog,
  buildNodeFormMapIfNeeded,
  disconnectDiagramViewportObserver,
  connectDiagramViewportObserver,
  handleProcessFormSubmit,
  completedHistorySnapshotTime,
  completedHistoryTaskId,
} = ctx

// ── Node click handlers for diagram ──────────────────────────────────────

const handleNodeClick = (node: ProcessNode) => {
  activeDetailTab.value = 'general'
  if (selectedNodeId.value === node.id) {
    clearNodeSelection()
    return
  }
  void buildNodeFormMapIfNeeded().then(() => {
    selectedNodeId.value = node.id
  })
}

const clearNodeSelection = () => {
  selectedNodeId.value = null
}

/** Top-level load orchestrator — extracted to composables/taskDetail/useTaskDetailLoader.ts (behavior unchanged). */
const { loadTaskDetail } = createTaskDetailLoader(ctx, { fallbackProcessInstanceId })
ctx.loadTaskDetail = loadTaskDetail
const taskActions = useTaskActions({
  taskId: effectiveTaskId,
  taskInfo: taskInfo as any,
  subTableBindings,
  formData,
  formReadOnly,
  submitting,
  approveDialogVisible,
  approveDialogTitle,
  currentApproveAction,
  approveForm,
  actionDialogVisible,
  actionDialogTitle,
  currentAction,
  actionForm,
  userOptions,
  userSearchLoading,
  loadTaskDetail,
  prepareBeforeComplete: async () => {
    if (isMiSubTaskMode.value) {
      await mergeMiParticipantScalarsFromForm()
      return
    }
    if (!formReadOnly.value && allowSubTableAssignForCurrentTask.value) {
      patchFormDataSubTablesFromCurrentBindings()
    }
  },
  buildFormPayloadForComplete: () => {
    const payload = buildCurrentTaskFormSubmitPayload()
    if (isMiSubTaskMode.value) {
      protectMainRecordScalarsInSubmitPayload(payload)
    }
    return payload.formData
  },
})
const {
  validateSubTableAssigneesForComplete,
  onActionDialogOpened,
  handleApprove,
  handleReject,
  handleDelegate,
  handleTransfer,
  handleUrge,
  submitApprove,
  submitAction
} = taskActions

/** FORM_POPUP helper callbacks — extracted to composables/taskDetail/useTaskDetailPopup.ts (behavior unchanged). */
const popupHelpers = createTaskDetailPopupHelpers(ctx)

const customActions = useCustomActions({
  taskInfo: taskInfo as any,
  subTableBindings,
  formData,
  submitting,
  saveCurrentTaskForm: saveCurrentTaskFormWithMiPersist,
  validateSubTableAssigneesForComplete,
  approveDialogVisible,
  approveDialogTitle,
  currentApproveAction,
  approveForm,
  loadTaskDetail,
  resolveFormPopupContent: popupHelpers.resolveFormPopupContent,
  preparePopupContext: popupHelpers.preparePopupContext,
  onDelegate: handleDelegate,
  onTransfer: handleTransfer,
  onUrge: handleUrge,
})
const {
  formPopupVisible,
  formPopupTitle,
  formPopupFields,
  formPopupTabs,
  formPopupData,
  formPopupReadOnly,
  formPopupWidth,
  formPopupLabelWidth,
  formPopupSubTableBindings,
  formPopupLinkedSubTableBindings,
  formPopupNativeSubTableBindingIds,
  formPopupFormConfig,
  formPopupViewContext,
  handleCustomAction,
  submitFormPopup,
  handleFormPopupSubTableUpdate,
} = customActions

/** BU Role pool request held by somebody else (or by nobody yet): view only, no action bar. */
const claimLocked = computed(
  () => !!taskInfo.value?.claimPoolTask && !taskInfo.value?.claimedByCurrentUser,
)
const claimSubmitting = ref(false)
const { claim: claimHeld, unclaim: unclaimHeld, forceUnclaim: forceUnclaimHeld, reassign: reassignHeld } = useTaskClaimActions({
  reload: loadTaskDetail,
  submitting: claimSubmitting,
})

const reassignDialogVisible = ref(false)

function handleClaimTask() {
  return claimHeld(effectiveTaskId.value)
}

function handleUnclaimTask() {
  const task = taskInfo.value
  return unclaimHeld(effectiveTaskId.value, task?.assignmentType ?? '', task?.assignee ?? '')
}

function handleForceUnclaimTask() {
  const task = taskInfo.value
  return forceUnclaimHeld(
    effectiveTaskId.value,
    task?.assignmentType ?? '',
    task?.assignee ?? '',
    task?.assigneeName,
  )
}

function handleReassignTask() {
  reassignDialogVisible.value = true
}

function confirmReassignTask(targetUserId: string) {
  return reassignHeld(effectiveTaskId.value, targetUserId).then(() => {
    reassignDialogVisible.value = false
  })
}

// display helpers moved to useTaskDisplay composable
// action handlers moved to useTaskActions composable

// custom action handlers moved to useCustomActions composable

onMounted(async () => {
  await reconcilePortalWorkspaceSession()
  await loadTaskDetail()
})

onBeforeUnmount(() => {
  clearFormAutosaveTimer()
  clearBpmnParseCache()
  disconnectDiagramViewportObserver()
})
</script>


<style lang="scss" scoped>
@use '@/styles/detail-navigation' as detailNavigation;

.task-detail-page {
  width: 100%;
  max-width: 100%;
  margin: 0;
  box-sizing: border-box;
  
  .page-header {
    display: flex;
    align-items: center;
    gap: 16px;
    margin-bottom: 20px;
    
    h1 {
      font-size: 24px;
      font-weight: 500;
      color: var(--text-primary);
      margin: 0;
    }
  }
  
  .skeleton-content {
    display: flex;
    flex-direction: column;
  }
  
  .error-content {
    display: flex;
    justify-content: center;
    align-items: center;
    min-height: 400px;
    background: white;
    border-radius: 8px;
    border: 1px solid var(--border-color, #e4e7ed);
  }
  
  .content-sections {
    display: flex;
    flex-direction: column;
    gap: 20px;
  }
  
  .section {
    background: white;
    border-radius: 8px;
    border: 1px solid var(--border-color, #e4e7ed);
    
    .section-header {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 16px 20px;
      background: #fafafa;
      border-bottom: 1px solid var(--border-color, #e4e7ed);
      font-size: 16px;
      font-weight: 500;
      color: var(--text-primary);
      
      .el-icon {
        color: var(--hsbc-red, #db0011);
      }
    }
    
    .section-content {
      padding: 20px;
    }
  }

  .detail-navigation {
    @include detailNavigation.compact-detail-navigation;
  }
.form-section {
    .form-container {
      width: 100%;
    }

    .sub-table-section {
      margin-top: 16px;
    }
  }

  .process-form-section {
    :deep(.el-collapse-item__header) {
      font-size: 16px;
      font-weight: 500;
      padding: 0 20px;
      background: #fafafa;
    }
    :deep(.el-collapse-item__content) {
      padding: 20px;
    }
  }

  .snapshot-section,
  .change-history-section {
    .section-content {
      padding: 20px;
    }
  }
  
  .form-popup-container {
    width: 100%;
    max-height: 60vh;
    overflow-y: auto;
  }
  
  .history-section {
    .section-content {
      min-height: 100px;
    }
  }
  
  .action-section {
    position: sticky;
    bottom: 0;
    z-index: 10;
    
    .action-buttons {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 16px 20px;
      
      .left-actions,
      .right-actions {
        display: flex;
        gap: 12px;
      }
    }
  }
}
</style>

<style lang="scss">
/* Transfer/Delegate dialog: prevent label wrapping, left-align */
.task-action-form .el-form-item__label {
  white-space: nowrap;
  text-align: left;
}
/* Reason Description: slightly larger label and textarea */
.task-action-form .task-action-reason-item .el-form-item__label {
  font-size: 14px;
}
.task-action-form .task-action-reason-item .el-textarea__inner {
  min-height: 100px;
  width: 100%;
  font-size: 14px;
}
</style>