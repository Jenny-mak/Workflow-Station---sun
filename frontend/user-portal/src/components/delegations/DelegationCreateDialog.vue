<template>
  <el-dialog
    :model-value="visible"
    :title="t('delegation.create')"
    width="560px"
    class="delegation-create-dialog"
    @update:model-value="emit('update:visible', $event)"
    @opened="onOpened"
  >
    <el-form
      :model="form"
      label-width="auto"
      label-position="left"
      class="delegation-create-form"
    >
      <el-form-item :label="t('delegation.targetType')">
        <el-radio-group
          v-model="form.targetType"
          @change="onTargetTypeChange"
        >
          <el-radio value="USER">
            {{ t('delegation.specifyUser') }}
          </el-radio>
          <el-radio value="BU_ROLE">
            {{ t('delegation.specifyBuRole') }}
          </el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item
        v-if="!isBuRole"
        :label="t('delegation.delegateTo')"
      >
        <div
          class="delegation-user-lookup"
          data-testid="delegation-user-lookup"
        >
          <LookupField
            :model-value="form.delegateId || null"
            :table-id="SYSTEM_USER_LOOKUP_TABLE_ID"
            :search-fields="SYSTEM_USER_SEARCH_FIELDS"
            display-field="display_name"
            :display-fields="SYSTEM_USER_DISPLAY_FIELDS"
            :view-fields="systemUserViewFields"
            selected-display-field="display_name"
            :prefetch-limit="DELEGATE_USER_LOOKUP_PAGE_SIZE"
            :remote-filter="true"
            :exclude-primary-keys="excludeDelegateUserIds"
            :placeholder="t('delegation.selectDelegate')"
            @update:model-value="applyUserLookupValue"
            @select="applyUserLookupValue"
            @clear="form.delegateId = ''"
          />
        </div>
      </el-form-item>
      <el-form-item
        v-if="isBuRole"
        :label="t('task.delegateBusinessUnit')"
        :error="buLoadError"
      >
        <el-cascader
          v-model="form.delegatedBuId"
          :options="buTree"
          :props="buCascaderProps"
          :placeholder="t('task.selectBusinessUnit')"
          :loading="buLoading"
          filterable
          clearable
          :teleported="true"
          style="width: 100%"
          @change="onBuChange"
          @visible-change="onBuVisibleChange"
        />
      </el-form-item>
      <el-form-item
        v-if="isBuRole"
        :label="t('task.delegateRole')"
        :error="roleLoadError"
      >
        <el-select
          v-model="form.delegatedRoleCode"
          :placeholder="t('task.selectRole')"
          :loading="roleLoading"
          :disabled="!form.delegatedBuId"
          filterable
          clearable
          :teleported="true"
          style="width: 100%"
        >
          <el-option
            v-for="role in roleOptions"
            :key="role.value"
            :label="role.label"
            :value="role.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('delegation.delegationType')">
        <el-select
          v-model="form.delegationType"
          data-testid="delegation-type-select"
          style="width: 100%;"
        >
          <el-option
            value="ALL"
            :label="t('delegation.all')"
          />
          <el-option
            value="PARTIAL"
            :label="t('delegation.partial')"
          />
          <el-option
            value="TEMPORARY"
            :label="t('delegation.temporary')"
          />
        </el-select>
      </el-form-item>
      <el-form-item
        v-if="form.delegationType === 'PARTIAL'"
        :label="t('delegation.processTypes')"
        :error="fuLoadError"
      >
        <el-select
          v-model="form.processTypes"
          multiple
          filterable
          :placeholder="t('delegation.processTypesPlaceholder')"
          :loading="fuLoading"
          data-testid="delegation-process-types"
          style="width: 100%;"
        >
          <el-option
            v-for="unit in fuOptions"
            :key="unit.key"
            :label="unit.name"
            :value="unit.key"
          />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('delegation.startTime')">
        <el-date-picker
          v-model="form.startTime"
          type="datetime"
          style="width: 100%;"
          :disabled-date="disablePastDate"
        />
      </el-form-item>
      <el-form-item :label="t('delegation.endTime')">
        <el-date-picker
          v-model="form.endTime"
          type="datetime"
          style="width: 100%;"
          :disabled-date="disablePastDate"
        />
      </el-form-item>
      <el-form-item :label="t('delegation.reason')">
        <el-input
          v-model="form.reason"
          type="textarea"
          :rows="3"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:visible', false)">
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
import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { createDelegationRule } from '@/api/delegation'
import { processApi, type ProcessDefinition } from '@/api/process'
import LookupField from '@/components/lookup/LookupField.vue'
import {
  DELEGATE_USER_LOOKUP_PAGE_SIZE,
  SYSTEM_USER_DISPLAY_FIELDS,
  SYSTEM_USER_LOOKUP_TABLE_ID,
  SYSTEM_USER_SEARCH_FIELDS,
  useDelegationTargetPickers,
} from '@/composables/delegations/useDelegationTargetPickers'

const props = defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  created: []
}>()

const { t } = useI18n()
const submitting = ref(false)
const fuLoading = ref(false)
const fuLoadError = ref('')
const fuOptions = ref<Array<{ key: string; name: string }>>([])

const form = reactive({
  targetType: 'USER' as 'USER' | 'BU_ROLE',
  delegateId: '',
  delegatedBuId: '',
  delegatedBuCode: '',
  delegatedRoleCode: '',
  delegationType: 'ALL',
  processTypes: [] as string[],
  startTime: null as Date | null,
  endTime: null as Date | null,
  reason: '',
})

const {
  systemUserViewFields,
  isBuRole,
  buTree,
  roleOptions,
  buLoading,
  roleLoading,
  buLoadError,
  roleLoadError,
  buCascaderProps,
  onBuVisibleChange,
  onTargetTypeChange,
  onBuChange,
  applyUserLookupValue,
  onOpened,
  excludeDelegateUserIds,
} = useDelegationTargetPickers(form)

function resetForm() {
  form.targetType = 'USER'
  form.delegateId = ''
  form.delegatedBuId = ''
  form.delegatedBuCode = ''
  form.delegatedRoleCode = ''
  form.delegationType = 'ALL'
  form.processTypes = []
  form.startTime = null
  form.endTime = null
  form.reason = ''
}

function unwrapProcessDefinitions(payload: unknown): ProcessDefinition[] {
  if (Array.isArray(payload)) {
    return payload as ProcessDefinition[]
  }
  if (payload && typeof payload === 'object' && 'data' in payload) {
    const data = (payload as { data: unknown }).data
    if (Array.isArray(data)) {
      return data as ProcessDefinition[]
    }
  }
  throw new Error('Startable process list is missing')
}

async function loadStartableFunctionUnits(): Promise<void> {
  fuLoading.value = true
  fuLoadError.value = ''
  try {
    const listed = unwrapProcessDefinitions(await processApi.getDefinitions())
    fuOptions.value = listed
      .filter((unit) => unit.key && unit.key.trim())
      .map((unit) => ({
        key: unit.key.trim(),
        name: (unit.name && unit.name.trim()) ? unit.name.trim() : unit.key.trim(),
      }))
  } catch {
    // FALLBACK(ux): load failed — error shown; empty options block Partial save
    fuOptions.value = []
    fuLoadError.value = t('delegation.processTypesLoadFailed')
    ElMessage.error(t('delegation.processTypesLoadFailed'))
  } finally {
    fuLoading.value = false
  }
}

watch(
  () => props.visible,
  (open) => {
    if (open) {
      resetForm()
      void loadStartableFunctionUnits()
    }
  },
  { immediate: true },
)

function validate(): boolean {
  if (form.targetType === 'BU_ROLE') {
    if (!form.delegatedBuCode || !form.delegatedRoleCode) {
      ElMessage.warning(t('delegation.buRolePairRequired'))
      return false
    }
  } else if (!form.delegateId) {
    ElMessage.warning(t('delegation.selectDelegate'))
    return false
  }
  if (form.delegationType === 'PARTIAL'
      && form.processTypes.every((key) => !key || !String(key).trim())) {
    ElMessage.warning(t('delegation.partialProcessTypesRequired'))
    return false
  }
  if (form.delegationType === 'TEMPORARY' && (!form.startTime || !form.endTime)) {
    ElMessage.warning(t('delegation.temporaryWindowRequired'))
    return false
  }
  if (isPastDateTime(form.startTime) || isPastDateTime(form.endTime)) {
    ElMessage.warning(t('delegation.timeInPast'))
    return false
  }
  return true
}

function disablePastDate(date: Date): boolean {
  const startOfToday = new Date()
  startOfToday.setHours(0, 0, 0, 0)
  return date.getTime() < startOfToday.getTime()
}

function isPastDateTime(value: Date | null): boolean {
  return !!value && value.getTime() < Date.now()
}

async function submit() {
  if (!validate()) return
  submitting.value = true
  try {
    const processTypes = form.delegationType === 'PARTIAL'
      ? form.processTypes.map((key) => String(key).trim()).filter(Boolean)
      : undefined
    await createDelegationRule({
      delegateTargetType: form.targetType,
      delegateId: form.targetType === 'USER' ? form.delegateId : undefined,
      delegateBuCode: form.targetType === 'BU_ROLE' ? form.delegatedBuCode : undefined,
      delegateRoleCode: form.targetType === 'BU_ROLE' ? form.delegatedRoleCode : undefined,
      delegationType: form.delegationType,
      processTypes,
      startTime: form.startTime ? form.startTime.toISOString() : undefined,
      endTime: form.endTime ? form.endTime.toISOString() : undefined,
      reason: form.reason || undefined,
    })
    ElMessage.success(t('delegation.createSuccess'))
    emit('update:visible', false)
    emit('created')
  } catch {
    // request interceptor already surfaces API errors
  } finally {
    submitting.value = false
  }
}

defineExpose({ form, submit, fuOptions })
</script>

<style lang="scss" scoped>
.delegation-user-lookup {
  width: 100%;
}
</style>
