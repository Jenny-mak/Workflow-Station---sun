<template>
  <div class="page-container">
    <div class="card">
      <div
        class="flex-between"
        style="margin-bottom: 16px;"
      >
        <div
          class="flex"
          style="align-items: center; gap: 16px;"
        >
          <el-button @click="goBack">
            <el-icon><ArrowLeft /></el-icon>
            {{ t('common.back') }}
          </el-button>
          <el-tooltip
            :content="store.current?.description || t('functionUnit.noDescription')"
            placement="bottom"
          >
            <IconPreview 
              :icon-id="store.current?.icon?.id" 
              size="large" 
            />
          </el-tooltip>
          <h3>{{ store.current?.name }}</h3>
          <el-tag :type="statusTagType(store.current?.status)">
            {{ statusLabel(store.current?.status) }}
          </el-tag>
          <span
            v-if="store.current?.currentVersion"
            class="version-badge"
          >
            v{{ store.current.currentVersion }}
          </span>
        </div>
        <div>
          <el-button
            v-if="AI_STUDIO_ENABLED && !isReadOnly"
            type="primary"
            plain
            @click="showAiStudioDialog = true"
          >
            <el-icon><Guide /></el-icon>
            {{ t('ai.studio.entryButton') }}
          </el-button>
          <!-- AI Generate 入口：功能已停用，见 utils/featureFlags.ts -->
          <el-button
            v-if="AI_GENERATION_ENABLED && !isReadOnly"
            type="primary"
            @click="showAiPanel = true"
          >
            <el-icon><MagicStick /></el-icon>
            {{ t('ai.panel.generateButton') }}
          </el-button>
          <el-button @click="openEditDialog">
            <el-icon><Setting /></el-icon>
            {{ t('functionUnit.settings') }}
          </el-button>
          <el-button
            :loading="exporting"
            @click="handleExport"
          >
            <el-icon><Download /></el-icon>
            {{ t('common.export') }}
          </el-button>
          <el-button
            :loading="validating"
            @click="handleValidate"
          >
            {{ t('functionUnit.validate') }}
          </el-button>
          <el-button
            v-if="!isReadOnly"
            type="warning"
            @click="showDeployDialog = true"
          >
            <el-icon><Upload /></el-icon>
            {{ t('functionUnit.deploy') }}
          </el-button>
        </div>
      </div>

      <el-alert
        v-if="isReadOnly"
        type="info"
        :closable="false"
        show-icon
        :title="t('functionUnit.readOnlyHint')"
        style="margin-bottom: 12px;"
      />

      <div
        class="designer-workspace"
        @click.capture="onReadOnlyInteraction"
        @pointerdown.capture="onReadOnlyInteraction"
        @keydown.capture="onReadOnlyInteraction"
        @dragstart.capture="onReadOnlyInteraction"
        @drop.capture="onReadOnlyInteraction"
      >
      <el-tabs
        v-model="activeTab"
        type="border-card"
      >
        <el-tab-pane
          :label="t('functionUnit.process')"
          name="process"
        >
          <ProcessDesigner
            v-if="activeTab === 'process'"
            :key="processDesignerReloadKey"
            :function-unit-id="functionUnitId"
          />
        </el-tab-pane>
        <el-tab-pane
          :label="t('functionUnit.tables')"
          name="tables"
        >
          <TableDesigner
            v-if="activeTab === 'tables'"
            :function-unit-id="functionUnitId"
          />
        </el-tab-pane>
        <el-tab-pane
          :label="t('functionUnit.forms')"
          name="forms"
        >
          <FormDesigner
            v-if="activeTab === 'forms'"
            :function-unit-id="functionUnitId"
          />
        </el-tab-pane>
        <el-tab-pane
          :label="t('functionUnit.viewDesign')"
          name="view-design"
        >
          <MainTableViewDesignTab
            v-if="activeTab === 'view-design'"
            :function-unit-id="functionUnitId"
          />
        </el-tab-pane>
        <el-tab-pane
          :label="t('functionUnit.actionDesign')"
          name="actions"
        >
          <ActionDesigner
            v-if="activeTab === 'actions'"
            :function-unit-id="functionUnitId"
          />
        </el-tab-pane>
        <el-tab-pane
          :label="t('functionUnit.automation')"
          name="service-task"
        >
          <ServiceTaskDesigner
            v-if="activeTab === 'service-task'"
            :function-unit-id="functionUnitId"
          />
        </el-tab-pane>
        <el-tab-pane
          :label="t('connection.title')"
          name="connections"
        >
          <ConnectionDesigner
            v-if="activeTab === 'connections'"
            :function-unit-id="functionUnitId"
          />
        </el-tab-pane>
        <el-tab-pane
          :label="t('emailTemplate.title')"
          name="email-templates"
        >
          <EmailTemplateDesigner
            v-if="activeTab === 'email-templates'"
            :function-unit-id="functionUnitId"
          />
        </el-tab-pane>
        <el-tab-pane
          :label="t('emailMonitor.title')"
          name="email-monitors"
        >
          <EmailMonitorDesigner
            v-if="activeTab === 'email-monitors'"
            :function-unit-id="functionUnitId"
          />
        </el-tab-pane>
        <el-tab-pane
          :label="t('functionUnit.decisions')"
          name="decisions"
        >
          <DecisionList
            v-if="activeTab === 'decisions'"
            :function-unit-id="functionUnitId"
          />
        </el-tab-pane>
        <el-tab-pane
          :label="t('version.title')"
          name="versions"
        >
          <VersionManager
            v-if="activeTab === 'versions'"
            :function-unit-id="functionUnitId"
          />
        </el-tab-pane>
      </el-tabs>
      </div>
    </div>

    <!-- Function Unit Settings: basic info + Requirements / Design documents -->
    <el-dialog
      :model-value="showEditDialog"
      :title="t('functionUnit.settings')"
      width="960px"
      :before-close="closeSettings"
    >
      <el-tabs v-model="settingsTab">
        <el-tab-pane
          :label="t('functionUnit.documents.tabBasic')"
          name="basic"
        >
          <el-form
            :model="editForm"
            :disabled="isReadOnly"
            label-width="auto"
            label-position="left"
          >
            <el-form-item :label="t('functionUnit.icon')">
              <IconUploadField
                v-model="editForm.iconId"
                size="large"
              />
            </el-form-item>
            <el-form-item
              :label="t('functionUnit.name')"
              required
            >
              <el-input
                v-model="editForm.name"
                :placeholder="t('functionUnit.namePlaceholder')"
              />
            </el-form-item>
            <el-form-item :label="t('functionUnit.description')">
              <el-input
                v-model="editForm.description"
                type="textarea"
                :rows="3"
                :placeholder="t('functionUnit.descriptionPlaceholder')"
              />
            </el-form-item>
            <el-form-item :label="t('functionUnit.tags')">
              <el-select
                v-model="editForm.tags"
                multiple
                filterable
                allow-create
                default-first-option
                :placeholder="t('functionUnit.selectTags')"
                style="width: 100%;"
              >
                <el-option
                  v-for="tag in availableTags"
                  :key="tag"
                  :label="tag"
                  :value="tag"
                />
              </el-select>
            </el-form-item>
          </el-form>
        </el-tab-pane>
        <el-tab-pane
          v-for="docType in FUNCTION_UNIT_DOCUMENT_TYPES"
          :key="docType"
          :label="t(`functionUnit.documents.type.${docType}`)"
          :name="docType"
          lazy
        >
          <FunctionUnitDocumentEditor
            v-if="showEditDialog"
            :ref="(el) => setDocumentEditor(docType, el)"
            :function-unit-id="functionUnitId"
            :function-unit-name="store.current?.name"
            :type="docType"
            :readonly="isReadOnly"
          />
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="closeSettings()">
          {{ isReadOnly ? t('common.close') : t('common.cancel') }}
        </el-button>
        <el-button
          v-if="settingsTab === 'basic' && !isReadOnly"
          type="primary"
          :loading="saving"
          @click="saveBasicInfo"
        >
          {{ t('common.save') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Validation Result Dialog -->
    <el-dialog
      v-model="showValidationDialog"
      :title="t('functionUnit.validationResult')"
      width="500px"
    >
      <div v-if="validationResult">
        <el-result
          v-if="validationResult.valid"
          icon="success"
          :title="t('functionUnit.validationPassed')"
          :sub-title="t('functionUnit.validationPassedDesc')"
        />
        <div v-else>
          <el-alert
            v-if="validationResult.errors?.length"
            type="error"
            :closable="false"
            style="margin-bottom: 12px;"
          >
            <template #title>
              {{ t('functionUnit.validationErrors') }} ({{ validationResult.errors.length }})
            </template>
            <ul style="margin: 8px 0 0 0; padding-left: 20px;">
              <li
                v-for="(err, i) in validationResult.errors"
                :key="i"
              >
                {{ err }}
              </li>
            </ul>
          </el-alert>
          <el-alert
            v-if="validationResult.warnings?.length"
            type="warning"
            :closable="false"
          >
            <template #title>
              {{ t('functionUnit.validationWarnings') }} ({{ validationResult.warnings.length }})
            </template>
            <ul style="margin: 8px 0 0 0; padding-left: 20px;">
              <li
                v-for="(warn, i) in validationResult.warnings"
                :key="i"
              >
                {{ warn }}
              </li>
            </ul>
          </el-alert>
        </div>
      </div>
      <template #footer>
        <el-button @click="showValidationDialog = false">
          {{ t('common.close') }}
        </el-button>
      </template>
    </el-dialog>

    <AiPanel
      v-if="AI_GENERATION_ENABLED"
      :function-unit-id="functionUnitId"
      :visible="showAiPanel"
      @update:visible="showAiPanel = $event"
      @data-applied="handleAiDataApplied"
    />

    <AiStudioEntryDialog
      v-if="AI_STUDIO_ENABLED"
      :function-unit-id="functionUnitId"
      :function-unit-name="store.current?.name"
      :can-generate="!isReadOnly"
      :visible="showAiStudioDialog"
      @update:visible="showAiStudioDialog = $event"
      @open="handleOpenAiStudio"
    />

    <!-- Deploy Dialog -->
    <el-dialog
      v-model="showDeployDialog"
      :title="t('functionUnit.deploy')"
      width="500px"
      @closed="cleanupDeployDialogState"
    >
      <el-form
        :model="deployForm"
        label-width="auto"
        label-position="left"
      >
        <el-form-item :label="t('functionUnit.autoEnable')">
          <el-switch v-model="deployForm.autoEnable" />
          <span style="margin-left: 12px; color: #909399; font-size: 12px;">{{ t('functionUnit.autoEnableHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('functionUnit.changeLog')">
          <el-input
            v-model="deployForm.changeLog"
            type="textarea"
            :placeholder="t('functionUnit.changeLogPlaceholder')"
            :rows="3"
          />
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        style="margin-bottom: 16px;"
      >
        {{ t('functionUnit.deployInfo') }}
      </el-alert>
      
      <!-- Deploy Status -->
      <div
        v-if="deployStatus"
        class="deploy-status"
      >
        <el-divider>{{ t('functionUnit.deployStatusTitle') }}</el-divider>
        <div class="status-header">
          <el-tag :type="getDeployStatusType(deployStatus.status)">
            {{ getDeployStatusLabel(deployStatus.status) }}
          </el-tag>
          <span v-if="deployStatus.progress !== undefined">{{ deployStatus.progress }}%</span>
        </div>
        <el-progress 
          v-if="deployStatus.status === 'DEPLOYING'" 
          :percentage="deployStatus.progress || 0" 
        />
        <el-progress 
          v-else-if="deployStatus.status === 'SUCCESS'" 
          :percentage="100" 
          status="success"
        />
        <el-progress 
          v-else-if="deployStatus.status === 'FAILED'" 
          :percentage="deployStatus.progress || 0" 
          status="exception"
        />
        <el-alert
          v-if="deployStatus.status === 'FAILED' && deployStatus.versionNumber"
          type="warning"
          :closable="false"
          style="margin-top: 12px;"
        >
          {{ t('functionUnit.versionCreatedButDeployFailed', { version: deployStatus.versionNumber }) }}
        </el-alert>
        <div
          v-if="deployStatus.status === 'SUCCESS' && deployStatus.versionNumber"
          class="version-info"
        >
          {{ t('functionUnit.newVersion', { version: deployStatus.versionNumber }) }}
        </div>
        <div
          v-if="deployStatus.steps?.length"
          class="deploy-steps"
        >
          <div
            v-for="step in deployStatus.steps"
            :key="step.name"
            class="step-item"
          >
            <el-icon
              v-if="step.status === 'SUCCESS'"
              color="#67c23a"
            >
              <CircleCheck />
            </el-icon>
            <el-icon
              v-else-if="step.status === 'FAILED'"
              color="#f56c6c"
            >
              <CircleClose />
            </el-icon>
            <el-icon
              v-else-if="step.status === 'RUNNING'"
              color="#409eff"
            >
              <Loading />
            </el-icon>
            <el-icon
              v-else
              color="#909399"
            >
              <Clock />
            </el-icon>
            <span>{{ translateStep(step.name) }}</span>
            <span
              v-if="step.message"
              class="step-message"
            >{{ translateStep(step.message) }}</span>
          </div>
        </div>
        <div
          v-if="deployStatus.message && deployStatus.status === 'FAILED'"
          class="error-message"
        >
          {{ deployStatus.message }}
        </div>
      </div>
      
      <template #footer>
        <el-button @click="closeDeployDialog">
          {{ t('common.close') }}
        </el-button>
        <el-button 
          v-if="!deployStatus || (deployStatus.status !== 'SUCCESS' && deployStatus.status !== 'FAILED')"
          type="primary" 
          :loading="deploying" 
          :disabled="deployStatus?.status === 'DEPLOYING'"
          @click="handleDeploy"
        >
          {{ deploying ? t('functionUnit.deploying') : t('functionUnit.startDeploy') }}
        </el-button>
        <el-button 
          v-if="deployStatus?.status === 'FAILED'"
          type="primary" 
          :loading="deploying" 
          @click="handleDeploy"
        >
          {{ t('functionUnit.redeploy') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessageBox } from 'element-plus'
import { ArrowLeft, Setting, Download, Upload, CircleCheck, CircleClose, Loading, Clock, MagicStick, Guide } from '@element-plus/icons-vue'
import { useFunctionUnitStore } from '@/stores/functionUnit'
import ProcessDesigner from '@/components/designer/ProcessDesigner.vue'
import ServiceTaskDesigner from '@/components/serviceTask/ServiceTaskDesigner.vue'
import TableDesigner from '@/components/designer/TableDesigner.vue'
import FormDesigner from '@/components/designer/FormDesigner.vue'
import MainTableViewDesignTab from '@/components/designer/MainTableViewDesignTab.vue'
import ActionDesigner from '@/components/designer/ActionDesigner.vue'
import ConnectionDesigner from '@/components/designer/ConnectionDesigner.vue'
import EmailTemplateDesigner from '@/components/designer/EmailTemplateDesigner.vue'
import EmailMonitorDesigner from '@/components/designer/EmailMonitorDesigner.vue'
import DecisionList from '@/components/designer/DecisionList.vue'
import VersionManager from '@/components/version/VersionManager.vue'
import IconPreview from '@/components/icon/IconPreview.vue'
import IconUploadField from '@/components/icon/IconUploadField.vue'
import AiPanel from '@/components/ai/AiPanel.vue'
import FunctionUnitDocumentEditor from '@/components/function-unit/FunctionUnitDocumentEditor.vue'
import { FUNCTION_UNIT_DOCUMENT_TYPES, type FunctionUnitDocumentType } from '@/api/functionUnitDocument'
import AiStudioEntryDialog from '@/components/ai/AiStudioEntryDialog.vue'
import { AI_GENERATION_ENABLED, AI_STUDIO_ENABLED } from '@/utils/featureFlags'
import { blockReadOnlyDesignerInteraction } from '@/utils/readOnlyDesignerInteraction'
import { isFunctionUnitReadOnly } from '@/utils/permission'
import type { AiStudioOpenPayload } from '@/utils/aiStudioDraft'
import { useFunctionUnitStatus } from '@/composables/functionUnitEdit/useFunctionUnitStatus'
import { useFunctionUnitSettings } from '@/composables/functionUnitEdit/useFunctionUnitSettings'
import { useFunctionUnitActions } from '@/composables/functionUnitEdit/useFunctionUnitActions'
import { useFunctionUnitDeploy } from '@/composables/functionUnitEdit/useFunctionUnitDeploy'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const store = useFunctionUnitStore()

const functionUnitId = computed(() => Number(route.params.id))
const isReadOnly = computed(() => store.current != null && isFunctionUnitReadOnly(store.current))

function onReadOnlyInteraction(event: Event): void {
  if (!isReadOnly.value) return
  blockReadOnlyDesignerInteraction(event)
}

// Back always means "up to the Function Unit list", never "the previous page": jumping
// between function units from the sidebar's Recent list would otherwise make Back walk
// backwards through those function units instead of leaving the designer.
const goBack = () => {
  router.push('/function-units')
}
const activeTab = ref('process')

watch(activeTab, (tab) => {
  if (tab === 'forms' && functionUnitId.value) {
    void store.fetchTables(functionUnitId.value)
  }
})

const showAiPanel = ref(false)
const showAiStudioDialog = ref(false)

function handleOpenAiStudio(payload: AiStudioOpenPayload) {
  router.push({
    name: 'AiStudioWorkspace',
    params: { id: functionUnitId.value },
    query: { mode: payload.mode }
  })
}
/** 改这个值会重挂载流程设计器——见 handleAiDataApplied。 */
const processDesignerReloadKey = ref(0)

const { statusTagType, statusLabel } = useFunctionUnitStatus()

const {
  saving,
  showEditDialog,
  settingsTab,
  editForm,
  availableTags,
  openEditDialog,
  handleSaveEdit
} = useFunctionUnitSettings({ functionUnitId, store })

type DocumentEditorHandle = InstanceType<typeof FunctionUnitDocumentEditor>
const documentEditors = new Map<FunctionUnitDocumentType, DocumentEditorHandle>()

function setDocumentEditor(type: FunctionUnitDocumentType, el: unknown) {
  if (el) documentEditors.set(type, el as DocumentEditorHandle)
  else documentEditors.delete(type)
}

/** Closing the settings dialog discards unsaved document edits only after the user agrees. */
async function closeSettings() {
  const dirty = [...documentEditors.values()].some(editor => editor.isDirty)
  if (dirty) {
    try {
      await ElMessageBox.confirm(
        t('functionUnit.documents.unsavedConfirm'),
        t('functionUnit.documents.unsavedTitle'),
        { type: 'warning', confirmButtonText: t('functionUnit.documents.discard') }
      )
    } catch {
      return // keep editing
    }
  }
  showEditDialog.value = false
}

async function saveBasicInfo() {
  if (await handleSaveEdit()) await closeSettings()
}

const {
  validating,
  exporting,
  showValidationDialog,
  validationResult,
  handleValidate,
  handleExport
} = useFunctionUnitActions({ functionUnitId, store })

const {
  deploying,
  showDeployDialog,
  deployStatus,
  deployForm,
  handleDeploy,
  stopDeployPolling,
  cleanupDeployDialogState,
  closeDeployDialog,
  getDeployStatusType,
  getDeployStatusLabel,
  translateStep
} = useFunctionUnitDeploy({ functionUnitId, store })

/**
 * AI 生成结果写入后刷新设计器。
 *
 * 表 / 表单 / 动作三个设计器在模板里直接绑 store（`:rows="() => store.tables"` 之类），
 * refreshAll 更新完 store 它们自己就跟着变了。流程设计器不行：它在 onMounted 里把 BPMN XML
 * 一次性 importXML 进 bpmn-js 实例，之后再没有任何地方重新导入——store.process 换了新流程，
 * 画布上还是 apply 之前那张图，用户只能手动刷新整页。改 key 强制重挂载，让 initModeler 重跑。
 *
 * 画布上未保存的改动会被丢弃，这是对的：AI apply 已经在服务端替换了流程定义，画布上那份是旧的。
 * 只有当前 tab 的设计器是挂载着的（各个 tab 都带 v-if），所以这次重挂载不会波及其他 tab。
 */
async function handleAiDataApplied() {
  await store.refreshAll(functionUnitId.value)
  processDesignerReloadKey.value++
}

/** AI Studio's "Edit in Settings" lands here with ?settings=REQUIREMENTS|DESIGN. */
function openSettingsFromQuery() {
  const tab = route.query.settings
  if (!FUNCTION_UNIT_DOCUMENT_TYPES.includes(tab as FunctionUnitDocumentType)) return
  openEditDialog()
  settingsTab.value = tab as FunctionUnitDocumentType
  void router.replace({ query: { ...route.query, settings: undefined } })
}

onMounted(async () => {
  store.fetchAllTags()
  await store.fetchById(functionUnitId.value)
  openSettingsFromQuery()
})

onUnmounted(() => {
  stopDeployPolling()
})
</script>

<style lang="scss" scoped>
.version-badge {
  background-color: #f0f0f0;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  color: #666;
}

.deploy-status {
  margin-top: 16px;
  
  .status-header {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 12px;
  }
  
  .deploy-steps {
    margin-top: 12px;
    
    .step-item {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 8px 0;
      border-bottom: 1px solid #eee;
      
      &:last-child {
        border-bottom: none;
      }
      
      .step-message {
        color: #909399;
        font-size: 12px;
        margin-left: auto;
      }
    }
  }
  
  .version-info {
    margin-top: 12px;
    padding: 8px 12px;
    background-color: #f0f9eb;
    border-radius: 4px;
    color: #67c23a;
    font-size: 13px;
    font-weight: 500;
  }

  .error-message {
    margin-top: 12px;
    padding: 12px;
    background-color: #fef0f0;
    border-radius: 4px;
    color: #f56c6c;
    font-size: 13px;
  }
}
</style>
