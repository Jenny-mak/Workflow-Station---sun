<template>
  <el-dialog
    :model-value="modelValue"
    :title="mode === 'edit' ? t('bi.dataViewAssignment.editAssignment') : t('bi.dataViewAssignment.newAssignment')"
    width="600px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form
      ref="formRef"
      v-loading="initializing"
      :model="form"
      :rules="rules"
      label-position="left"
      label-width="auto"
    >
      <el-form-item
        v-if="mode === 'create'"
        :label="t('bi.dataViewAssignment.formDashboard')"
        prop="dashboardIds"
      >
        <el-select
          v-model="form.dashboardIds"
          multiple
          collapse-tags
          collapse-tags-tooltip
          :max-collapse-tags="2"
          filterable
          :loading="dashboardsLoading"
          :placeholder="t('bi.dataViewAssignment.placeholderDashboard')"
          style="width: 100%"
        >
          <el-option
            v-for="dashboard in dashboards"
            :key="dashboard.id"
            :label="dashboard.dashboardTitle"
            :value="dashboard.id"
          />
        </el-select>
        <div class="field-help">
          {{ t('bi.dataViewAssignment.multiDashboardHint') }}
        </div>
      </el-form-item>

      <el-form-item
        v-else
        :label="t('bi.dataViewAssignment.formDashboard')"
        prop="dashboardId"
      >
        <el-select
          v-model="form.dashboardId"
          filterable
          :loading="dashboardsLoading"
          :placeholder="t('bi.dataViewAssignment.placeholderDashboard')"
          style="width: 100%"
        >
          <el-option
            v-for="dashboard in dashboards"
            :key="dashboard.id"
            :label="dashboard.dashboardTitle"
            :value="dashboard.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item
        :label="t('bi.dataViewAssignment.formTarget')"
        prop="functionUnitId"
      >
        <el-select
          v-model="form.functionUnitId"
          filterable
          :loading="functionUnitsLoading"
          :placeholder="t('bi.dataViewAssignment.placeholderTarget')"
          style="width: 100%"
          @change="handleFunctionUnitChange"
        >
          <el-option
            v-for="unit in functionUnits"
            :key="unit.id"
            :label="`${unit.name} (${unit.code})`"
            :value="unit.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item
        :label="t('bi.dataViewAssignment.formTable')"
        prop="tableId"
      >
        <el-select
          v-model="form.tableId"
          filterable
          :disabled="!form.functionUnitId"
          :loading="tablesLoading"
          :placeholder="t('bi.dataViewAssignment.placeholderTable')"
          style="width: 100%"
        >
          <el-option
            v-for="table in tables"
            :key="table.id"
            :label="tableOptionLabel(table)"
            :value="table.id"
          />
        </el-select>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">
        {{ t('common.cancel') }}
      </el-button>
      <el-button
        type="primary"
        :loading="submitting"
        @click="submit"
      >
        {{ t('common.confirm') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FormInstance } from 'element-plus'
import { biManagementApi, type DashboardRegistryResponse, type DataViewAssignmentResponse, type DataViewFunctionUnitOption, type DataViewTableOption } from '@/api/biManagement'
import { notifyError, notifySuccess } from '@/utils/notify'

const props = defineProps<{
  modelValue: boolean
  mode: 'create' | 'edit'
  initialRow: DataViewAssignmentResponse | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

const { t } = useI18n()
const formRef = ref<FormInstance>()
const initializing = ref(false)
const submitting = ref(false)
const dashboardsLoading = ref(false)
const functionUnitsLoading = ref(false)
const tablesLoading = ref(false)
const dashboards = ref<DashboardRegistryResponse[]>([])
const functionUnits = ref<DataViewFunctionUnitOption[]>([])
const tables = ref<DataViewTableOption[]>([])

const form = reactive({
  dashboardId: '',
  dashboardIds: [] as string[],
  functionUnitId: null as number | null,
  tableId: null as number | null,
})

const rules = computed(() => ({
  dashboardId: [{ required: true, message: t('bi.dataViewAssignment.ruleDashboard'), trigger: 'change' }],
  dashboardIds: [{
    type: 'array' as const,
    required: true,
    min: 1,
    message: t('bi.dataViewAssignment.ruleDashboard'),
    trigger: 'change',
  }],
  functionUnitId: [{ required: true, message: t('bi.dataViewAssignment.ruleTarget'), trigger: 'change' }],
  tableId: [{ required: true, message: t('bi.dataViewAssignment.ruleTable'), trigger: 'change' }],
}))

function tableOptionLabel(table: DataViewTableOption) {
  const name = table.tableDisplayName || table.tableName
  return `${name} · ${t(`bi.dataViewAssignment.tableType${table.tableType}`)}`
}

async function loadTables(functionUnitId: number) {
  tablesLoading.value = true
  try {
    tables.value = await biManagementApi.dataViewAssignment.listTables(functionUnitId)
  } catch {
    tables.value = []
    notifyError(t('bi.dataViewAssignment.loadTablesFailed'))
  } finally {
    tablesLoading.value = false
  }
}

async function handleFunctionUnitChange(value: number) {
  form.tableId = null
  tables.value = []
  if (value) await loadTables(value)
}

async function initialize() {
  initializing.value = true
  dashboardsLoading.value = true
  functionUnitsLoading.value = true
  try {
    const [dashboardPage, units] = await Promise.all([
      biManagementApi.dashboard.list({ status: 'ACTIVE', size: 1000 }),
      biManagementApi.dataViewAssignment.listFunctionUnits(),
    ])
    dashboards.value = dashboardPage.content || []
    functionUnits.value = units || []

    form.dashboardId = props.initialRow?.dashboardId || ''
    form.dashboardIds = []
    form.functionUnitId = props.initialRow?.functionUnitId || null
    form.tableId = props.initialRow?.tableId || null
    if (form.functionUnitId) {
      const selectedTableId = form.tableId
      await loadTables(form.functionUnitId)
      form.tableId = selectedTableId
    } else {
      tables.value = []
    }
  } catch {
    notifyError(t('bi.dataViewAssignment.initializeFailed'))
  } finally {
    dashboardsLoading.value = false
    functionUnitsLoading.value = false
    initializing.value = false
  }
}

async function submit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  if (!form.functionUnitId || !form.tableId) return

  submitting.value = true
  try {
    const payload = {
      dashboardId: form.dashboardId,
      functionUnitId: form.functionUnitId,
      tableId: form.tableId,
    }
    if (props.mode === 'edit' && props.initialRow) {
      await biManagementApi.dataViewAssignment.update(props.initialRow.id, payload)
      notifySuccess(t('bi.dataViewAssignment.updateSuccess'))
    } else {
      const created = await biManagementApi.dataViewAssignment.createBatch({
        dashboardIds: form.dashboardIds,
        functionUnitId: form.functionUnitId,
        tableId: form.tableId,
      })
      notifySuccess(t('bi.dataViewAssignment.createSuccess', { count: created.length }))
    }
    emit('update:modelValue', false)
    emit('success')
  } catch {
    notifyError(t('bi.dataViewAssignment.submitFailed'))
  } finally {
    submitting.value = false
  }
}

watch(() => props.modelValue, (open) => {
  if (open) void initialize()
})
</script>

<style scoped>
.field-help {
  width: 100%;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}
</style>
