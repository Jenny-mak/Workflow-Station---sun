<template>
  <div class="email-monitor-designer">
    <div class="designer-toolbar">
      <el-button type="primary" @click="openCreateDialog">{{ t('emailMonitor.create') }}</el-button>
      <el-button @click="loadRules" :loading="loading">
        <el-icon><Refresh /></el-icon> {{ t('common.refresh') }}
      </el-button>
      <DesignerHelpLink
        path="/email-monitor"
        :aria-label="t('emailMonitor.guideLinkAria')"
        test-id="email-monitor-guide-link"
      />
    </div>

    <el-alert
      v-if="inboundConnections.length === 0"
      type="warning"
      :closable="false"
      :title="t('emailMonitor.noInboundConnection')"
      style="margin-bottom: 12px;"
    />

    <DesignerListTable
      :loading="loading"
      :storage-key="`${functionUnitId}:email-monitors`"
      :columns="listColumns"
      :rows="rules"
      :actions-width="160"
      table-class="monitor-rules-table"
      @row-click="handleRowClick"
    >
      <template #cell-connectionUid="{ row }">
        {{ connectionName(row.connectionUid) }}
      </template>
      <template #cell-enabled="{ row }">
        <el-tag
          :type="row.enabled ? 'success' : 'info'"
          size="small"
        >
          {{ row.enabled ? t('common.yes') : t('common.no') }}
        </el-tag>
      </template>
      <template #actions="{ row }">
        <div
          class="row-actions"
          @click.stop
        >
          <el-button
            link
            type="primary"
            @click="openEditDialog(row as EmailMonitorRule)"
          >
            {{ t('common.edit') }}
          </el-button>
          <el-button
            link
            type="danger"
            @click="handleDelete(row as EmailMonitorRule)"
          >
            {{ t('common.delete') }}
          </el-button>
        </div>
      </template>
    </DesignerListTable>

    <el-dialog
      v-model="showFormDialog"
      width="900px"
      destroy-on-close
      top="5vh"
    >
      <template #header>
        <div class="designer-help-dialog-title">
          <span class="el-dialog__title">{{ editingId ? t('emailMonitor.edit') : t('emailMonitor.create') }}</span>
          <DesignerHelpLink
            path="/email-monitor"
            :aria-label="t('emailMonitor.guideLinkAria')"
            test-id="email-monitor-dialog-guide-link"
          />
        </div>
      </template>
      <el-form :model="form" label-position="top" class="monitor-form">
        <div class="form-grid">
          <el-form-item :label="t('emailMonitor.name')" required>
            <el-input v-model="form.name" :placeholder="t('emailMonitor.namePlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('emailMonitor.connection')" required>
            <el-select v-model="form.connectionUid" style="width: 100%;" :placeholder="t('emailMonitor.connectionPlaceholder')">
              <el-option
                v-for="c in inboundConnections"
                :key="c.connectionUid"
                :label="`${c.name} (${c.connectionType})`"
                :value="c.connectionUid"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('emailMonitor.systemInitiator')">
            <SystemInitiatorSelect v-model="form.systemInitiatorUserId" />
          </el-form-item>
          <el-form-item :label="t('emailMonitor.folderLabel')">
            <el-input v-model="form.folderLabel" placeholder="INBOX" />
          </el-form-item>
          <el-form-item :label="t('emailMonitor.pollInterval')">
            <el-input-number v-model="form.pollIntervalSeconds" :min="30" :step="30" controls-position="right" />
          </el-form-item>
        </div>
        <el-form-item>
          <el-checkbox v-model="form.reviewOnMissing">{{ t('emailMonitor.reviewOnMissing') }}</el-checkbox>
        </el-form-item>

        <el-divider>{{ t('emailMonitor.wizard.title') }}</el-divider>
        <div class="form-tip" style="margin-bottom: 8px;">{{ t('emailMonitor.templateFiltersHint') }}</div>
        <EmailExtractionWizard
          ref="wizardRef"
          v-model="form.extractionRules"
          :function-unit-id="functionUnitId"
        />
      </el-form>
      <template #footer>
        <el-button @click="showFormDialog = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="handleSave" :loading="saving">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import EmailExtractionWizard from '@/components/designer/email/EmailExtractionWizard.vue'
import SystemInitiatorSelect from '@/components/designer/email/SystemInitiatorSelect.vue'
import {
  emailMonitorApi,
  type EmailMonitorRule,
  type EmailMonitorRuleRequest
} from '@/api/emailMonitor'
import { connectionApi, type EmailConnection } from '@/api/connection'
import { resolveUserFacingHttpMessage } from '@/utils/httpErrorMessage'
import DesignerListTable from '@/components/designer-list/DesignerListTable.vue'
import DesignerHelpLink from '@/components/designer/DesignerHelpLink.vue'
import type { DesignerListTableColumn } from '@/composables/useDesignerListGrid'

const props = defineProps<{ functionUnitId: number }>()
const { t } = useI18n()

const rules = ref<EmailMonitorRule[]>([])
const connections = ref<EmailConnection[]>([])
const loading = ref(false)
const saving = ref(false)
const showFormDialog = ref(false)
const editingId = ref<number | null>(null)

const inboundConnections = computed(() =>
  connections.value.filter(c => c.direction === 'INBOUND')
)

const defaultForm = (): EmailMonitorRuleRequest => ({
  name: '',
  enabled: true,
  connectionUid: '',
  processDefinitionKey: '',
  systemInitiatorUserId: '',
  folderLabel: 'INBOX',
  actionType: 'START_PROCESS',
  pollIntervalSeconds: 60,
  reviewOnMissing: true,
  extractionRules: {}
})

const form = reactive<EmailMonitorRuleRequest>(defaultForm())
const wizardRef = ref<{ attachmentTargetErrors: () => string[] } | null>(null)

function connectionName(uid: string): string {
  return connections.value.find(c => c.connectionUid === uid)?.name ?? uid
}

const listColumns = computed<DesignerListTableColumn<EmailMonitorRule>[]>(() => [
  {
    key: 'name',
    prop: 'name',
    label: t('emailMonitor.name'),
    defaultWidth: 160,
    showOverflowTooltip: true,
  },
  {
    key: 'connectionUid',
    prop: 'connectionUid',
    label: t('emailMonitor.connection'),
    defaultWidth: 200,
    showOverflowTooltip: true,
    getValue: (row) => connectionName(row.connectionUid),
  },
  {
    key: 'pollIntervalSeconds',
    prop: 'pollIntervalSeconds',
    label: t('emailMonitor.pollInterval'),
    defaultWidth: 120,
  },
  {
    key: 'enabled',
    prop: 'enabled',
    label: t('emailMonitor.enabled'),
    defaultWidth: 100,
    getValue: (row) => (row.enabled ? t('common.yes') : t('common.no')),
  },
])

async function loadRules() {
  loading.value = true
  try {
    const [rulesRes, connRes] = await Promise.all([
      emailMonitorApi.listTemplates(props.functionUnitId),
      connectionApi.list(props.functionUnitId)
    ])
    rules.value = rulesRes.data || []
    connections.value = connRes.data || []
  } catch {
    ElMessage.error(t('emailMonitor.loadFailed'))
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  editingId.value = null
  Object.assign(form, defaultForm())
  showFormDialog.value = true
}

function handleRowClick(row: EmailMonitorRule) {
  void openEditDialog(row)
}

async function openEditDialog(row: EmailMonitorRule) {
  editingId.value = row.id
  try {
    const res = await emailMonitorApi.get(props.functionUnitId, row.id)
    const r = res.data
    Object.assign(form, {
      name: r.name,
      enabled: r.enabled,
      connectionUid: r.connectionUid,
      systemInitiatorUserId: r.systemInitiatorUserId || '',
      folderLabel: r.folderLabel || 'INBOX',
      actionType: r.actionType || 'START_PROCESS',
      pollIntervalSeconds: r.pollIntervalSeconds || 60,
      reviewOnMissing: r.reviewOnMissing ?? true,
      extractionRules: r.extractionRules || {}
    })
    showFormDialog.value = true
  } catch {
    ElMessage.error(t('emailMonitor.loadFailed'))
  }
}

async function handleSave() {
  if (!form.name.trim()) {
    ElMessage.warning(t('emailMonitor.nameRequired'))
    return
  }
  if (!form.connectionUid) {
    ElMessage.warning(t('emailMonitor.connectionRequired'))
    return
  }
  const attachmentErrors = wizardRef.value?.attachmentTargetErrors() ?? []
  if (attachmentErrors.length) {
    ElMessage.warning(t('emailMonitor.wizard.attachmentsTargetMustBeFile', { field: attachmentErrors[0] }))
    return
  }
  saving.value = true
  try {
    const payload: EmailMonitorRuleRequest = {
      ...form,
      name: form.name.trim(),
      systemInitiatorUserId: form.systemInitiatorUserId?.trim() || undefined
    }
    if (editingId.value) {
      await emailMonitorApi.update(props.functionUnitId, editingId.value, payload)
    } else {
      await emailMonitorApi.create(props.functionUnitId, payload)
    }
    ElMessage.success(t('common.saveSuccess'))
    showFormDialog.value = false
    await loadRules()
  } catch (e) {
    ElMessage.error(resolveUserFacingHttpMessage(e, t) || t('common.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function handleDelete(row: EmailMonitorRule) {
  try {
    await ElMessageBox.confirm(
      t('emailMonitor.deleteConfirm', { name: row.name }),
      t('common.confirm'),
      { type: 'warning' }
    )
    await emailMonitorApi.delete(props.functionUnitId, row.id)
    ElMessage.success(t('common.deleteSuccess'))
    await loadRules()
  } catch {
    // cancelled or failed
  }
}

onMounted(loadRules)
</script>

<style scoped lang="scss">
.email-monitor-designer {
  padding: 8px 0;
}
.designer-toolbar {
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.designer-help-dialog-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding-right: 28px;
}
.monitor-form {
  .form-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0 16px;
  }
  :deep(.el-form-item) {
    margin-bottom: 14px;
  }
}
.monitor-rules-table {
  :deep(.el-table__body tr) {
    cursor: pointer;
  }
  .row-actions {
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }
}
</style>
