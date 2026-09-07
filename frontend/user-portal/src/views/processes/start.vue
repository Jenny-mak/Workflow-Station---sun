<template>
  <div class="process-start-page">
    <!-- 页面头部 -->
    <div class="page-header">
      <el-button
        :icon="ArrowLeft"
        @click="$router.back()"
      >
        {{ t('processStart.back') }}
      </el-button>
      <h1>{{ functionUnitName || t('processStart.startProcess') }}</h1>
      <el-tag
        v-if="functionUnitVersion"
        type="info"
        size="small"
      >
        v{{ functionUnitVersion }}
      </el-tag>
    </div>

    <!-- 加载状态：与待办一致 — 轻量提示，非整页骨架 -->
    <div
      v-if="loading"
      class="process-start-loading"
    >
      <el-icon
        class="is-loading"
        :size="32"
      >
        <Loading />
      </el-icon>
      <span>{{ t('common.loading') }}</span>
    </div>
    
    <!-- 功能单元已禁用状态 -->
    <div
      v-else-if="isDisabled"
      class="disabled-state"
    >
      <el-result
        icon="warning"
        :title="t('processStart.disabledTitle')"
        :sub-title="t('processStart.disabledSubtitle')"
      >
        <template #extra>
          <el-button
            type="primary"
            @click="$router.back()"
          >
            {{ t('processStart.back') }}
          </el-button>
          <el-button @click="$router.push('/processes')">
            {{ t('processStart.viewOtherProcesses') }}
          </el-button>
        </template>
      </el-result>
    </div>
    
    <!-- 访问被拒绝状态 -->
    <div
      v-else-if="isAccessDenied"
      class="access-denied-state"
    >
      <el-result
        icon="error"
        :title="t('processStart.accessDeniedTitle')"
        :sub-title="t('processStart.accessDeniedSubtitle')"
      >
        <template #extra>
          <el-button
            type="primary"
            @click="$router.back()"
          >
            {{ t('processStart.back') }}
          </el-button>
          <el-button @click="$router.push('/processes')">
            {{ t('processStart.viewOtherProcesses') }}
          </el-button>
        </template>
      </el-result>
    </div>
    
    <!-- 加载错误状态 -->
    <div
      v-else-if="loadError"
      class="error-state"
    >
      <el-result
        icon="error"
        :title="t('processStart.loadFailedTitle')"
        :sub-title="loadError"
      >
        <template #extra>
          <el-button
            type="primary"
            @click="loadFunctionUnitContent"
          >
            {{ t('processStart.reload') }}
          </el-button>
          <el-button @click="$router.back()">
            {{ t('processStart.back') }}
          </el-button>
        </template>
      </el-result>
    </div>

    <!-- 无 PROCESS form 警告状态 -->
    <div
      v-else-if="noProcessForm"
      class="no-process-form-state"
    >
      <el-result
        icon="warning"
        :title="t('process.noProcessFormTitle')"
        :sub-title="t('process.noProcessForm')"
      >
        <template #extra>
          <el-button
            type="primary"
            @click="$router.back()"
          >
            {{ t('processStart.back') }}
          </el-button>
          <el-button @click="$router.push('/processes')">
            {{ t('processStart.viewOtherProcesses') }}
          </el-button>
        </template>
      </el-result>
    </div>
    
    <!-- 正常内容 -->
    <div
      v-else
      class="content-sections"
    >
      <el-alert
        v-if="workspaceStartBlocked"
        type="warning"
        show-icon
        :closable="false"
        class="workspace-guard-alert"
      >
        <template #title>
          {{ t('processStart.workspaceGuardTitle') }}
        </template>
        {{ t('processStart.workspaceGuardHint') }}
      </el-alert>
      <!-- 第一部分：实时工作流程图（可折叠；默认展开） -->
      <WorkflowDiagramCollapsibleSection
        :title="t('processStart.workflowDiagram')"
      >
        <template #badge>
          <el-tag
            type="success"
            size="small"
          >
            {{ t('processStart.startNodeTag') }}
          </el-tag>
        </template>
        <ProcessDiagram
          v-if="bpmnXml || processNodes.length > 0"
          :nodes="processNodes"
          :flows="processFlows"
          :bpmn-xml="bpmnXml"
          :current-node-id="currentNodeId"
          :completed-node-ids="completedNodeIds"
          :show-toolbar="true"
          :show-legend="true"
        />
        <el-empty
          v-else
          :description="t('processStart.noProcessDefinition')"
        />
      </WorkflowDiagramCollapsibleSection>

      <!-- 第二部分：表单 -->
      <div class="section form-section">
        <div class="section-header">
          <el-icon><Document /></el-icon>
          <span>{{ currentFormName || t('processStart.applicationForm') }}</span>
        </div>
        <div class="section-content">
          <div
            v-if="formFields.length > 0 || formTabs.length > 0 || formFieldsAfterTabs.length > 0"
            class="form-container"
          >
            <FormRenderer
              ref="formRendererRef"
              v-model="formData"
              :fields="formFields"
              :tabs="formTabs"
              :fields-after-tabs="formFieldsAfterTabs"
              :label-width="formLabelWidth"
              :label-position="formLabelPosition"
              :form-options="formFormOptions"
              :form-config="formConfigJson"
              :sub-table-bindings="subTableBindings"
              :function-unit-id="functionUnitId"
              :process-instance-id="recordNoteDraftId"
              :primary-table-binding="primaryTableBinding ?? undefined"
              :request-id-config="requestIdConfig"
              @update:sub-table-data="(id: number, rows: any[]) => { const b = subTableBindings.find(x => x.bindingId === id); if (b) b.data = rows }"
            />
          </div>
          <el-empty
            v-else
            :description="t('processStart.noFormConfig')"
          />
        </div>
      </div>

      <!-- 第三部分：流转记录 -->
      <div class="section history-section">
        <div class="section-header">
          <el-icon><Clock /></el-icon>
          <span>{{ t('processStart.flowHistory') }}</span>
        </div>
        <div class="section-content">
          <ProcessHistory
            :records="historyRecords"
            :show-header="false"
            :show-refresh="false"
          />
        </div>
      </div>

      <!-- 第四部分：动作按钮 -->
      <div class="section action-section">
        <div class="action-buttons">
          <div class="left-actions">
            <el-button
              :loading="savingDraft"
              @click="handleSaveDraft"
            >
              <el-icon><FolderOpened /></el-icon> {{ t('processStart.saveDraft') }}
            </el-button>
            <el-button @click="$router.back()">
              {{ t('processStart.cancel') }}
            </el-button>
          </div>
          <div class="right-actions">
            <el-button 
              v-for="action in availableActions" 
              :key="action.id"
              :type="action.type || 'default'"
              :style="actionButtonStyle(action.buttonColor)"
              :disabled="workspaceStartBlocked && isSubmitLikeAction(action)"
              :loading="submitting && currentAction === action.id"
              @click="handleAction(action)"
            >
              {{ action.label }}
            </el-button>
            <el-button 
              v-if="availableActions.length === 0"
              type="primary" 
              :disabled="workspaceStartBlocked"
              :loading="submitting"
              @click="handleSubmit"
            >
              <el-icon><Promotion /></el-icon> {{ t('processStart.submit') }}
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { warnIfUploadsBlocking } from '@platform-shared/upload/uploadSubmitGate'
import { ArrowLeft, Document, Clock, FolderOpened, Promotion, Loading } from '@element-plus/icons-vue'
import { processApi } from '@/api/process'
import { adoptRecordNoteDrafts } from '@/api/recordNote'
import { isAutoGeneratedPrimaryKey } from '@/utils/formFieldMeta'
import WorkflowDiagramCollapsibleSection from '@/components/WorkflowDiagramCollapsibleSection.vue'
import ProcessDiagram from '@/components/ProcessDiagram.vue'
import ProcessHistory from '@/components/ProcessHistory.vue'
import FormRenderer from '@/components/FormRenderer.vue'
import { collectPlacedSubTableBindingIds, computeNeededSubTableBindingIds } from '@/components/formRendererHelpers'
import { relationTableApi } from '@/api/relationTable'
import { isDisabledMessage } from '@/utils/statusMatcher'
import { getUser } from '@/api/auth'
import { isProcessStartBlockedByWorkspace } from '@/utils/workspaceProcessGuard'
import { actionButtonStyle, isCustomButtonColor } from '@/utils/actionButtonColor'
import {
  resolveSubTablePrimaryKeyFields,
  flattenNestedSubTableRowsIntoPayload,
  normalizeSubTableRowsForBinding,
} from '@/composables/tasks/shared'
import {
  buildRelationTableFieldIndexFromDataTables,
  resolveBindingFieldDefinitions,
} from '@/components/subTableAddDialogHelpers'
import type { BindingFieldDefinition } from '@/utils/subTableRowRuntime'
import { applyFieldDefinitionsToFormFields } from '@/utils/subTableRowRuntime'
import {
  attachAssignmentConfigsToBindings,
  stampAssignmentConfigsOnForms,
} from '@/utils/miAssignmentConfig'
import { createProcessStartState } from '@/composables/processStart/useProcessStartState'
import { pickSubFormOptionsFromDesign } from '@/composables/processStart/pickSubFormOptionsFromDesign'
import { uploadSceneFlagsFromForms } from '@/utils/applyUploadPropsFromRule'
import { createProcessStartFormParsing } from '@/composables/processStart/useProcessStartFormParsing'
import { createProcessStartSubTables } from '@/composables/processStart/useProcessStartSubTables'
import {
  parseBpmnXmlAndGetStartFormId,
  createBpmnDiagramParser,
} from '@/composables/processStart/useProcessStartBpmn'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

/** 本地用户信息随页面加载读取；切换工作台后整页刷新会更新 */
const workspaceStartBlocked = computed(() => isProcessStartBlockedByWorkspace(getUser()))

function isSubmitLikeAction(action: { action?: string; actionType?: string }) {
  return action.actionType === 'PROCESS_SUBMIT' || action.action === 'submit'
}

// 路由参数：key 是功能单元的 ID
const functionUnitId = computed(() => route.params.key as string)

// RecordNote on New Request: notes written before submit anchor on a per-form
// draft id (persisted per FU so a reload keeps the stream); after a successful
// start they are re-anchored onto the real process instance (adopt).
const recordNoteDraftId = computed(() => {
  const storageKey = `rnDraft:${functionUnitId.value}`
  let draft = sessionStorage.getItem(storageKey)
  if (!draft) {
    draft = `draft-${crypto.randomUUID()}`
    sessionStorage.setItem(storageKey, draft)
  }
  return draft
})

function clearRecordNoteDraftId() {
  sessionStorage.removeItem(`rnDraft:${functionUnitId.value}`)
}
const isDraftMode = computed(() => route.query.draft === 'true')

// Main-table Request ID config — enables live recompute of the readonly __request_id field.
const requestIdConfig = ref<{ fieldNames: string[]; separator?: string } | null>(null)

// 响应式状态 + 可变缓存（容器，无行为）
const state = createProcessStartState()
const {
  loading,
  loadError,
  isDisabled,
  isAccessDenied,
  noProcessForm,
  submitting,
  savingDraft,
  currentAction,
  functionUnitName,
  functionUnitVersion,
  functionUnitCode,
  processNodes,
  processFlows,
  currentNodeId,
  completedNodeIds,
  bpmnXml,
  formFields,
  formTabs,
  formFieldsAfterTabs,
  formData,
  currentFormName,
  formLabelWidth,
  formLabelPosition,
  formFormOptions,
  formConfigJson,
  formRendererRef,
  subTableBindings,
  primaryTableBinding,
  caches,
  lookupDbConfigs,
  relationViewConfigs,
  historyRecords,
  availableActions,
} = state

// 表单解析（form-create 规则 → 字段、子表列推导）
const { parseFormConfig, deriveColumnsFromBinding, deriveDialogColumnsFromBinding, extractFieldsRecursive } = createProcessStartFormParsing({
  lookupDbConfigs,
  relationViewConfigs,
  cannotDownloadFieldKeys: () => uploadSceneFlagsFromForms(
    caches.cachedContentForms as Array<{ data?: unknown; configJson?: unknown }>,
  ),
  formConfigJson,
  formLabelPosition,
  formFormOptions,
  formTabs,
  formFields,
  formFieldsAfterTabs,
})

// 子表列解析 + 草稿/提交载荷
const {
  resolveSubTableBindingColumnsForStart,
  buildStartFormSubTablesPayload,
} = createProcessStartSubTables({
  caches,
  subTableBindings,
  deriveColumnsFromBinding,
})

// BPMN 流程图解析
const { parseBpmnXml } = createBpmnDiagramParser({
  t,
  processNodes,
  processFlows,
  currentNodeId,
  completedNodeIds,
})

const placedBindingIds = computed((): Set<number> => {
  return collectPlacedSubTableBindingIds(formFields.value, formTabs.value, formFieldsAfterTabs.value)
})

/**
 * 功能单元内容里的表单项（admin-center FormContentDTO）真实形状：在 API 的 `{ id; name; data; type }`
 * 之外还携带 `sourceId` / `tableBindings` 等字段。仅放宽类型注解，运行时取值不变。
 */
interface StartFormContentItem {
  id: string
  name: string
  data: string
  type: string
  sourceId?: string | number
  tableBindings?: any[]
}

// 加载功能单元内容
const loadFunctionUnitContent = async () => {
  loading.value = true
  loadError.value = ''
  isDisabled.value = false
  isAccessDenied.value = false
  noProcessForm.value = false
  
  try {
    const response = await processApi.getFunctionUnitContent(functionUnitId.value)
    const content = response.data || response
    
    if (content.error) {
      loadError.value = content.error
      return
    }
    stampAssignmentConfigsOnForms(content.forms, content.miAssignments)

    caches.cachedContentForms = content.forms || []
    caches.cachedRelationTableFieldIndex = buildRelationTableFieldIndexFromDataTables(content.dataTables)
    
    // 设置基本信息
    functionUnitName.value = content.name || ''
    functionUnitVersion.value = content.version || ''
    functionUnitCode.value = content.code || ''
    requestIdConfig.value = (content as { requestIdConfig?: { fieldNames: string[]; separator?: string } | null }).requestIdConfig ?? null
    
    let startFormInfo: { formId: string | null, formName: string | null, actionIds: string[] | null } = { formId: null, formName: null, actionIds: null }
    
    // 解析流程定义
    if (content.processes && content.processes.length > 0) {
      const processData = content.processes[0]
      bpmnXml.value = processData.data
      // 先获取开始节点后第一个用户任务的 formId 和 formName
      startFormInfo = parseBpmnXmlAndGetStartFormId(processData.data)
      parseBpmnXml(processData.data)
    }
    
    // 解析表单定义 - 根据开始节点的 formId 选择正确的表单
    if (content.forms && content.forms.length > 0) {
      // 功能单元内容中的表单来自 admin-center FormContentDTO：字段为 type（如 "FORM"），无 formType
      const hasProcessForm = content.forms.some(
        (f: any) => f.formType === 'PROCESS' || f.type === 'FORM' || f.type === 'PROCESS'
      )
      if (!hasProcessForm) {
        noProcessForm.value = true
        return
      }

      // 功能单元内容项实际含 sourceId / tableBindings / fieldDefinitions（admin-center FormContentDTO）；
      // 此处把 API 的窄声明放宽到运行时真实形状，仅为类型注解，不改任何取值逻辑。
      const contentForms = content.forms as StartFormContentItem[]
      let selectedForm = contentForms[0] // 默认第一个

      // 优先使用 formId 匹配 sourceId（原始表单ID）
      if (startFormInfo.formId) {
        const matchedForm = contentForms.find((f: any) =>
          String(f.sourceId) === startFormInfo.formId
        )
        if (matchedForm) {
          selectedForm = matchedForm
          console.log('Matched form by sourceId:', startFormInfo.formId, '->', selectedForm.name)
        } else {
          // 如果 sourceId 匹配失败，尝试用 formName 匹配
          if (startFormInfo.formName) {
            const matchedByName = content.forms.find((f: any) => f.name === startFormInfo.formName)
            if (matchedByName) {
              selectedForm = matchedByName
              console.log('Matched form by name:', startFormInfo.formName)
            }
          }
        }
      } else if (startFormInfo.formName) {
        // 如果没有 formId，尝试用 formName 匹配
        const matchedForm = content.forms.find((f: any) => f.name === startFormInfo.formName)
        if (matchedForm) {
          selectedForm = matchedForm
        }
      }
      
      currentFormName.value = selectedForm.name

      // Load lookup configs from rt_lookup_configs before parsing form
      lookupDbConfigs.value = {}
      if (selectedForm.sourceId) {
        try {
          const lcRes = await relationTableApi.getLookupConfigs(Number(selectedForm.sourceId))
          for (const lc of (lcRes.data || [])) {
            let sf: string[] = []
            try { sf = typeof lc.searchFields === 'string' ? JSON.parse(lc.searchFields || '[]') : (lc.searchFields || []) } catch { sf = [] }
            lookupDbConfigs.value[lc.componentId] = { tableId: lc.tableId, searchFields: sf, displayField: lc.displayField || '', viewFields: lc.viewFields || [] }
          }
        } catch (e) { console.warn('[start] Failed to load lookup configs:', e) }
      }

      // Parse relationViews from configJson BEFORE parseFormConfig so lookup view fields are available
      try {
        const cfg = typeof selectedForm.data === 'string' ? JSON.parse(selectedForm.data) : (selectedForm.data || {})
        relationViewConfigs.value = cfg.relationViews || {}
      } catch { relationViewConfigs.value = {} }

      parseFormConfig(selectedForm.data)
      
      // Parse subForms from configJson
      let subForms: Record<string, any> = {}
      let formConfigForPk: Record<string, any> = {}
      try {
        const cfg = typeof selectedForm.data === 'string' ? JSON.parse(selectedForm.data) : (selectedForm.data || {})
        subForms = cfg.subForms || {}
        formConfigForPk = cfg || {}
      } catch {}

      console.log('[start] tableBindings:', selectedForm.tableBindings?.length, 'subForms keys:', Object.keys(subForms))

      // Load sub-table bindings (SUB / RELATED, skip PRIMARY)
      const bindings: typeof subTableBindings.value = []
      let primaryBindingMeta: typeof primaryTableBinding.value = null
      for (const b of (selectedForm.tableBindings || [])) {
        if (b.bindingType === 'PRIMARY') {
          primaryBindingMeta = {
            tableId: (b as { tableId?: number | null }).tableId ?? null,
            tableName: b.tableDisplayName || b.tableName,
            fieldDefinitions: resolveBindingFieldDefinitions(
              { tableId: (b as { tableId?: number | null }).tableId, fieldDefinitions: (b as { fieldDefinitions?: Array<Record<string, unknown>> }).fieldDefinitions },
              caches.cachedRelationTableFieldIndex,
            ) as unknown as BindingFieldDefinition[],
          }
          continue
        }
        const tid = (b as { tableId?: number | null }).tableId ?? null
        const columns = resolveSubTableBindingColumnsForStart(b, subForms, formConfigForPk)
        const dialogColumns = deriveDialogColumnsFromBinding(b, subForms)
        const bindingFieldDefinitions = resolveBindingFieldDefinitions(
          { tableId: tid, fieldDefinitions: (b as { fieldDefinitions?: Array<Record<string, unknown>> }).fieldDefinitions },
          caches.cachedRelationTableFieldIndex,
        ) as unknown as BindingFieldDefinition[]
        // Sub-form design fields back the sub-table's Add/Edit dialog form (same contract
        // as task detail's resolveSubFormDesign) — without them the dialog renders an
        // empty "no form fields configured" card on the start page.
        // formOptions (Form onChange / onCreated / …) must also travel: without them
        // New Request subform dialog/bootstrap skips Form events that Todo already runs.
        const subFormDesign = subForms[b.bindingId] ?? subForms[String(b.bindingId)] ?? {}
        const subFormFields = Array.isArray(subFormDesign.rule) && subFormDesign.rule.length > 0
          ? applyFieldDefinitionsToFormFields(extractFieldsRecursive(subFormDesign.rule), bindingFieldDefinitions)
          : []
        const subFormOptions = pickSubFormOptionsFromDesign(subFormDesign)
        bindings.push({
          bindingId: b.bindingId,
          tableId: tid != null ? Number(tid) : null,
          bindingType: b.bindingType,
          bindingMode: b.bindingMode,
          tableName: b.tableDisplayName || b.tableName,
          designerTableName: b.tableName,
          tableType: b.tableType,
          tableDescription: b.tableDescription,
          primaryKeyFields: resolveSubTablePrimaryKeyFields(
            (b as { primaryKeyFields?: string[] }).primaryKeyFields,
            b.bindingId,
            formConfigForPk
          ),
          columns,
          ...(dialogColumns.length > 0 ? { dialogColumns } : {}),
          ...(subFormFields.length > 0 ? { formFields: subFormFields } : {}),
          ...(subFormOptions ? { formOptions: subFormOptions } : {}),
          fieldDefinitions: bindingFieldDefinitions,
          bindingLinkMode: (b as { bindingLinkMode?: string }).bindingLinkMode,
          foreignKeyField: (b as { foreignKeyField?: string | null }).foreignKeyField ?? null,
          data: []
        })
      }
      primaryTableBinding.value = primaryBindingMeta

      // Fallback: tableBindings 为空但 subForms 有数据时，直接从 subForms 构建
      if (bindings.length === 0 && Object.keys(subForms).length > 0) {
        console.log('[start] tableBindings empty, building from subForms fallback')
        for (const [bindingIdStr, subForm] of Object.entries(subForms)) {
          const bindingId = Number(bindingIdStr)
          if (!subForm || !Array.isArray((subForm as any).rule)) continue
          const fakeBinding = { bindingId, subFormConfig: subForm }
          const listCols = resolveSubTableBindingColumnsForStart(fakeBinding, subForms, formConfigForPk)
          const dialogCols = deriveDialogColumnsFromBinding(fakeBinding, subForms)
          const fallbackOptions = pickSubFormOptionsFromDesign(subForm as { options?: unknown })
          bindings.push({
            bindingId,
            tableId: null,
            bindingType: 'SUB',
            bindingMode: 'EDITABLE',
            tableName: 'Request Items',
            tableType: 'SUB',
            tableDescription: '',
            primaryKeyFields: undefined,
            columns: listCols,
            ...(dialogCols.length > 0 ? { dialogColumns: dialogCols } : {}),
            ...(fallbackOptions ? { formOptions: fallbackOptions } : {}),
            fieldDefinitions: [],
            data: []
          })
        }
      }

      const neededBindingIds = computeNeededSubTableBindingIds(placedBindingIds.value, bindings)
      subTableBindings.value = attachAssignmentConfigsToBindings(
        bindings.filter(b => neededBindingIds.has(b.bindingId)),
        content.miAssignments,
      )
      console.log('[start] subTableBindings built (designer-placed + linkForm closure):', subTableBindings.value.map(b => ({ id: b.bindingId, cols: b.columns.length })))
    }
    
    // 初始化流转记录（新流程，只有开始节点）
    initHistoryRecords()
    
    // 初始化动作按钮（使用 BPMN 中提取的 actionIds）
    await initActionButtons(startFormInfo.actionIds)
    
    // 如果是草稿模式，加载草稿数据
    if (isDraftMode.value) {
      await loadDraftData()
    }
    
  } catch (error: any) {
    console.error('Failed to load function unit content:', error)
    
    // 检查是否是 403 错误（禁用或无权限）
    if (error.response?.status === 403) {
      const message = error.response?.data?.message || ''
      if (isDisabledMessage(message)) {
        isDisabled.value = true
      } else {
        isAccessDenied.value = true
      }
    } else {
      loadError.value = error.message || t('processStart.loadFailed')
    }
  } finally {
    loading.value = false
  }
}

// 加载草稿数据
const loadDraftData = async () => {
  try {
    const response = await processApi.getDraft(functionUnitCode.value || functionUnitId.value)
    const draft = response.data || response
    if (draft && draft.formData) {
      const { __subTables__, ...mainFormData } = draft.formData
      formData.value = mainFormData
      // 恢复子表数据
      // 注意：JSON 序列化后 key 变为 string，需同时用 number 和 string 查找
      if (__subTables__ && typeof __subTables__ === 'object') {
        const st = JSON.parse(JSON.stringify(__subTables__)) as Record<string, unknown>
        flattenNestedSubTableRowsIntoPayload(st)
        subTableBindings.value.forEach(binding => {
          const saved = st[binding.bindingId] ?? st[String(binding.bindingId)]
          if (Array.isArray(saved)) {
            binding.data = normalizeSubTableRowsForBinding(saved)
          }
        })
      }
      ElMessage.success(t('processStart.draftLoaded'))
    }
  } catch (error) {
    console.error('Failed to load draft:', error)
  }
}

// 初始化流转记录
const initHistoryRecords = () => {
  historyRecords.value = [
    {
      id: 'init',
      nodeId: 'start',
      nodeName: t('processStart.initiateApplication'),
      status: 'current',
      createdTime: new Date().toISOString()
    }
  ]
}

// 初始化动作按钮 - 从 BPMN actionIds 获取自定义动作
const initActionButtons = async (actionIds: string[] | null) => {
  if (actionIds && actionIds.length > 0) {
    try {
      const response = await processApi.getActionsByIds(actionIds)
      const actions = response.data || response
      if (Array.isArray(actions) && actions.length > 0) {
        availableActions.value = actions.map((action: any) => {
          // 设计器配了颜色就用设计器的（内联 CSS 变量），否则按 actionType 回落到语义配色
          let btnType: 'primary' | 'success' | 'warning' | 'danger' | 'info' | undefined
          switch (action.actionType) {
            case 'PROCESS_SUBMIT': btnType = 'primary'; break
            case 'APPROVE': btnType = 'success'; break
            case 'REJECT': btnType = 'danger'; break
            default: btnType = isCustomButtonColor(action.buttonColor) ? 'primary' : (action.buttonColor || undefined)
          }
          return {
            id: action.id,
            label: action.actionName,
            type: btnType,
            buttonColor: action.buttonColor || undefined,
            action: action.actionType,
            actionType: action.actionType,
            configJson: action.configJson
          }
        })
        console.log('Loaded custom action buttons:', availableActions.value)
        return
      }
    } catch (error) {
      console.error('Failed to load action definitions, falling back to default:', error)
    }
  }
  
  // 回退：默认提交按钮
  availableActions.value = [
    {
      id: 'submit',
      label: t('processStart.submitApplication'),
      type: 'primary',
      action: 'submit',
      actionType: 'PROCESS_SUBMIT'
    }
  ]
}

// 保存草稿
const handleSaveDraft = async () => {
  savingDraft.value = true
  try {
    const liveFormData = (formRendererRef.value as { getFormData?: () => Record<string, unknown> } | null)
      ?.getFormData?.() ?? formData.value
    formData.value = { ...liveFormData }
    // Include sub-table data in draft
    const draftData = {
      ...liveFormData,
      __subTables__: buildStartFormSubTablesPayload()
    }
    await processApi.saveDraft(functionUnitCode.value || functionUnitId.value, draftData)
    ElMessage.success(t('processStart.draftSaved'))
  } catch (error: any) {
    ElMessage.error(error.message || t('processStart.draftSaveFailed'))
  } finally {
    savingDraft.value = false
  }
}

// 处理动作按钮点击
const handleAction = async (action: { id: string; label: string; action?: string; actionType?: string; configJson?: string }) => {
  if (workspaceStartBlocked.value && isSubmitLikeAction(action)) {
    ElMessage.warning(t('processStart.workspaceGuardToast'))
    return
  }
  switch (action.actionType) {
    case 'PROCESS_SUBMIT':
      await handleSubmit()
      break
    default:
      // 对于未知类型，尝试作为提交处理
      if (action.action === 'submit') {
        await handleSubmit()
      } else {
        ElMessage.warning(t('process.unknownActionType', { type: action.actionType || action.action }))
      }
  }
}

/**
 * On submit, generate the MAIN table's auto-generated primary key if it has no value yet.
 * Already-filled PKs are left untouched (idempotent across draft reload / re-submit).
 */
const ensureMainPrimaryKey = async () => {
  const binding = primaryTableBinding.value
  const tableId = binding?.tableId
  if (tableId == null || !Array.isArray(binding?.fieldDefinitions)) return
  const pkField = binding.fieldDefinitions.find(
    (f) => isAutoGeneratedPrimaryKey(f as Parameters<typeof isAutoGeneratedPrimaryKey>[0]),
  )
  if (!pkField?.fieldName) return
  // FormRenderer owns the model that handleSubmit actually submits (getFormData()); the parent
  // v-model is a throttled copy. Reading or writing the parent here would silently drop the PK
  // and leave the Request ID it feeds incomplete, so a missing renderer must fail the submit
  // rather than fall back to the stale model.
  const renderer = formRendererRef.value as {
    getFormData?: () => Record<string, unknown>
    handlePrimaryFormDataPatch?: (patch: Record<string, unknown>) => void
  } | null
  if (!renderer?.getFormData || !renderer.handlePrimaryFormDataPatch) {
    throw new Error('FormRenderer is not ready to allocate the main primary key')
  }
  const live = renderer.getFormData()
  const current = live[pkField.fieldName]
  if (current !== undefined && current !== null && String(current).trim() !== '') return
  // Allocate via backend so UUID / sequence strategies are honored consistently.
  const res = await processApi.allocatePrimaryKeys(
    functionUnitCode.value || functionUnitId.value,
    { tableId: Number(tableId), fieldName: pkField.fieldName },
  )
  const value = (res as { data?: { values?: string[] } })?.data?.values?.[0]
    ?? (res as { values?: string[] })?.values?.[0]
  if (value == null) {
    throw new Error(`Primary key allocation returned no value for ${pkField.fieldName}`)
  }
  // Patches FormRenderer's own model and recomputes __request_id, then emits back to the parent.
  renderer.handlePrimaryFormDataPatch({ [pkField.fieldName]: value })
}

// 提交流程
const handleSubmit = async () => {
  if (workspaceStartBlocked.value) {
    ElMessage.warning(t('processStart.workspaceGuardToast'))
    return
  }
  // Generate the auto-generated main PK BEFORE validation so its (required, readonly)
  // field is populated and doesn't fail required-validation. No-op if already set.
  try {
    await ensureMainPrimaryKey()
  } catch (e) {
    console.error('[ensureMainPrimaryKey] failed:', e)
    ElMessage.error(t('processStart.pkGenerateFailed'))
    return
  }
  // 验证表单
  if (formRendererRef.value) {
    const valid = await formRendererRef.value.validate()
    if (!valid) {
      ElMessage.warning(t('processStart.pleaseCompleteForm'))
      return
    }
  }
  if (!warnIfUploadsBlocking({
    inflight: t('upload.waitUntilComplete'),
    failed: t('upload.fixFailedBeforeSubmit'),
  })) return

  submitting.value = true
  currentAction.value = 'submit'

  try {
    const procKey = functionUnitCode.value || functionUnitId.value
    // Live FormRenderer data — do not submit stale parent formData (multi LOOKUP race).
    const liveFormData = (formRendererRef.value as { getFormData?: () => Record<string, unknown> } | null)
      ?.getFormData?.() ?? formData.value
    formData.value = { ...liveFormData }
    const startResponse: any = await processApi.startProcess(procKey, {
      processDefinitionKey: procKey,
      formData: {
        ...liveFormData,
        __subTables__: buildStartFormSubTablesPayload()
      },
      priority: 'NORMAL'
    })

    // Re-anchor draft notes onto the new instance (best-effort; notes must not
    // block the submission flow).
    const newInstanceId = startResponse?.data?.id
      || startResponse?.data?.processInstanceId
      || startResponse?.id
      || startResponse?.processInstanceId
    if (newInstanceId) {
      try {
        await adoptRecordNoteDrafts(recordNoteDraftId.value, String(newInstanceId))
      } catch (e) {
        console.warn('[recordNote] draft adopt failed:', e)
      }
    }
    clearRecordNoteDraftId()

    // 提交成功后删除草稿
    try {
      await processApi.deleteDraft(functionUnitCode.value || functionUnitId.value)
    } catch (e) {
      // 忽略删除草稿失败
    }
    
    // The instance exists either way, but a first step that did not complete is not a successful
    // submission — saying "submitted successfully" here is how a failed automation used to look fine.
    const firstStepError = startResponse?.data?.firstStepError || startResponse?.firstStepError
    if (firstStepError) {
      // Marker only — the reason is server-side (it names the AP webhook URL, which is a credential).
      console.warn('[processStart] first step did not complete; see user-portal logs')
      ElMessage({
        type: 'warning',
        message: t('processStart.firstStepIncomplete'),
        duration: 0,
        showClose: true
      })
    } else {
      ElMessage.success(t('processStart.processSubmitSuccess'))
    }

    // Task 16.2: 提交成功后清除 FormRenderer 自动保存数据
    if (formRendererRef.value) {
      formRendererRef.value.clearAutoSave()
    }
    
    router.push('/my-applications')
    
  } catch (error: any) {
    // HTTP 错误已由 api/request 拦截器展示（含后端 error.message）
    if (!error?.response) {
      ElMessage.error(error?.message || t('processStart.submitFailed'))
    }
  } finally {
    submitting.value = false
    currentAction.value = ''
  }
}

onMounted(() => {
  loadFunctionUnitContent()
})
</script>

<style lang="scss" scoped>
.process-start-page {
  width: 100%;
  max-width: 100%;
  margin: 0;
  box-sizing: border-box;

  .workspace-guard-alert {
    margin-bottom: 16px;
  }
  
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
  
  .process-start-loading {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 12px;
    min-height: 280px;
    color: var(--text-secondary);
    font-size: 14px;
    background: var(--background-white, #fff);
    border: 1px solid var(--border-color);
    border-radius: 8px;
  }
  
  .error-state {
    padding: 40px 0;
  }
  
  .disabled-state,
  .access-denied-state,
  .no-process-form-state {
    padding: 60px 0;
    background: white;
    border-radius: 8px;
    border: 1px solid var(--border-color);
  }
  
  .content-sections {
    display: flex;
    flex-direction: column;
    gap: 20px;
  }
  
  .section {
    background: white;
    border-radius: 8px;
    border: 1px solid var(--border-color);
    
    .section-header {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 16px 20px;
      background: #fafafa;
      border-bottom: 1px solid var(--border-color);
      font-size: 16px;
      font-weight: 500;
      color: var(--text-primary);
      
      .el-icon {
        color: var(--hsbc-red);
      }
    }
    
    .section-content {
      padding: 20px;
    }
  }
  
  .form-section {
    .form-container {
      width: 100%;
    }
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
