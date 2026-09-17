<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="560px"
    class="task-action-dialog"
    @opened="onOpened"
  >
    <template #header>
      <div class="task-action-dialog-title">
        <span class="el-dialog__title">{{ title }}</span>
        <DesignerHelpLink
          v-if="currentAction === 'delegate'"
          path="/task-delegate"
          :ariaLabel="$t('task.guideLinkAria')"
          test-id="task-delegate-guide-link"
        />
      </div>
    </template>
    <el-form
      :model="formData"
      label-width="auto"
      label-position="left"
      class="task-action-form"
    >
      <el-form-item
        v-if="currentAction === 'delegate'"
        :label="$t('task.delegateTargetType')"
      >
        <el-radio-group
          v-model="formData.targetType"
          @change="onTargetTypeChange"
        >
          <el-radio value="USER">
            {{ $t('task.delegateSpecifyUser') }}
          </el-radio>
          <el-radio value="BU_ROLE">
            {{ $t('task.delegateSpecifyBuRole') }}
          </el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item
        v-if="showDelegateUserLookup"
        :label="$t('task.targetUser')"
      >
        <div
          class="task-action-user-lookup"
          data-testid="task-action-user-lookup"
        >
          <LookupField
            :model-value="formData.targetUserId || null"
            :table-id="SYSTEM_USER_LOOKUP_TABLE_ID"
            :search-fields="SYSTEM_USER_SEARCH_FIELDS"
            display-field="display_name"
            :display-fields="SYSTEM_USER_DISPLAY_FIELDS"
            :view-fields="systemUserViewFields"
            selected-display-field="display_name"
            :prefetch-limit="DELEGATE_USER_LOOKUP_PAGE_SIZE"
            :remote-filter="true"
            :exclude-primary-keys="excludeDelegateUserIds"
            :placeholder="$t('task.clickToSearchUser')"
            @update:model-value="onUserLookupModelUpdate"
            @select="onUserLookupSelect"
            @clear="onUserLookupClear"
          />
        </div>
      </el-form-item>
      <el-form-item
        v-if="showTransferUserSelect"
        :label="$t('task.targetUser')"
      >
        <el-select
          v-model="formData.targetUserId"
          :placeholder="$t('task.selectUser')"
          :teleported="false"
          style="width: 100%;"
        >
          <el-option
            v-for="user in userOptions"
            :key="user.id"
            :label="$t('task.userOptionFormat', { name: user.name, username: user.username })"
            :value="user.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item
        v-if="showBuRoleSelect"
        :label="$t('task.delegateBusinessUnit')"
        :error="buLoadError"
      >
        <el-cascader
          v-model="formData.delegatedBuId"
          :options="buTree"
          :props="buCascaderProps"
          :placeholder="$t('task.selectBusinessUnit')"
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
        v-if="showBuRoleSelect"
        :label="$t('task.delegateRole')"
        :error="roleLoadError"
      >
        <el-select
          v-model="formData.delegatedRoleCode"
          :placeholder="$t('task.selectRole')"
          :loading="roleLoading"
          :disabled="!formData.delegatedBuId"
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
      <el-form-item
        :label="currentAction === 'urge' ? $t('task.urgeMessage') : $t('task.reasonDescription')"
        class="task-action-reason-item"
      >
        <el-input
          v-model="formData.reason"
          type="textarea"
          :rows="5"
          :placeholder="currentAction === 'urge' ? $t('task.urgeMessagePlaceholder') : $t('task.reasonPlaceholder')"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">
        {{ $t('common.cancel') }}
      </el-button>
      <el-button
        type="primary"
        :loading="submitting"
        @click="$emit('confirm')"
      >
        {{ $t('common.confirm') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, type CascaderValue } from 'element-plus'
import { permissionApi, type BusinessUnit, type RoleInfo } from '@/api/permission'
import DesignerHelpLink from '@/components/DesignerHelpLink.vue'
import LookupField from '@/components/lookup/LookupField.vue'
import { extractLookupPrimaryKey } from '@/utils/mainTableViewLookupDisplay'
import { currentPortalUserId } from '@/composables/delegations/useDelegationTargetPickers'

/** Platform sys_users virtual relation table — same source as module-driven user lookup. */
const SYSTEM_USER_LOOKUP_TABLE_ID = -1_000_000_001
const SYSTEM_USER_SEARCH_FIELDS = [
  'id',
  'username',
  'display_name',
  'full_name',
  'email',
  'employee_id',
]
const SYSTEM_USER_DISPLAY_FIELDS = [
  'username',
  'display_name',
  'full_name',
  'email',
  'employee_id',
]
const DELEGATE_USER_LOOKUP_PAGE_SIZE = 200

interface UserOption {
  id: string | number
  name: string
  username: string
}

export interface TaskActionForm {
  targetUserId: string
  reason: string
  targetType?: 'USER' | 'BU_ROLE'
  delegatedBuId?: string
  delegatedBuCode?: string
  delegatedRoleCode?: string
}

const props = defineProps<{
  modelValue: boolean
  title: string
  currentAction: string
  formData: TaskActionForm
  userOptions: UserOption[]
  submitting: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'confirm'): void
  (e: 'opened'): void
}>()

const { t } = useI18n()
const systemUserViewFields = computed(() =>
  SYSTEM_USER_DISPLAY_FIELDS.map((fieldName, sortOrder) => ({
    fieldName,
    displayLabel: t(`task.userLookupCol.${fieldName}`),
    sortOrder,
    visible: true,
  })),
)
const visible = ref(props.modelValue)
const buTree = ref<BusinessUnit[]>([])
const buIdToCode = ref<Record<string, string>>({})
const roleOptions = ref<Array<{ label: string; value: string }>>([])
const buLoading = ref(false)
const roleLoading = ref(false)
const buLoadError = ref('')
const roleLoadError = ref('')
const skipGlobalError = { skipGlobalErrorHandler: true } as const
const buCascaderProps = {
  value: 'id',
  label: 'name',
  children: 'children',
  checkStrictly: true,
  emitPath: false,
}

const isDelegateBuRole = computed(() =>
  props.currentAction === 'delegate' && (props.formData.targetType || 'USER') === 'BU_ROLE')
const showDelegateUserLookup = computed(() =>
  props.currentAction === 'delegate' && !isDelegateBuRole.value)
const showTransferUserSelect = computed(() => props.currentAction === 'transfer')
const showBuRoleSelect = computed(() => isDelegateBuRole.value)
const excludeDelegateUserIds = computed(() => {
  const id = currentPortalUserId()
  return id ? [id] : []
})

watch(() => props.modelValue, (val) => {
  visible.value = val
})

watch(visible, (val) => {
  emit('update:modelValue', val)
})

function indexBuTree(list: BusinessUnit[]) {
  for (const bu of list || []) {
    const id = String(bu.id)
    buIdToCode.value[id] = (bu.code && bu.code.trim()) ? bu.code.trim() : id
    if (bu.children && bu.children.length) indexBuTree(bu.children)
  }
}

async function loadBusinessUnits() {
  if (buTree.value.length > 0) return
  buLoading.value = true
  buLoadError.value = ''
  try {
    const resp = await permissionApi.getBusinessUnitsTree(skipGlobalError) as { data?: BusinessUnit[] } | BusinessUnit[]
    const tree = Array.isArray(resp) ? resp : (resp?.data ?? [])
    buTree.value = Array.isArray(tree) ? tree : []
    buIdToCode.value = {}
    indexBuTree(buTree.value)
  } catch {
    buTree.value = []
    buIdToCode.value = {}
    buLoadError.value = t('task.delegateBuLoadFailed')
    ElMessage.error(t('task.delegateBuLoadFailed'))
  } finally {
    buLoading.value = false
  }
}

async function loadRolesForBu(buId: string) {
  roleOptions.value = []
  roleLoadError.value = ''
  if (!buId) return
  roleLoading.value = true
  try {
    const roleResp = await permissionApi.getBusinessUnitRoles(buId, skipGlobalError) as { data?: RoleInfo[] } | RoleInfo[]
    const roles = Array.isArray(roleResp) ? roleResp : (roleResp?.data ?? [])
    roleOptions.value = (Array.isArray(roles) ? roles : [])
      .filter(r => r && r.code)
      .map(r => ({ label: r.name || r.code, value: r.code }))
  } catch {
    roleOptions.value = []
    roleLoadError.value = t('task.delegateRoleLoadFailed')
    ElMessage.error(t('task.delegateRoleLoadFailed'))
  } finally {
    roleLoading.value = false
  }
}

function onBuVisibleChange(open: boolean) {
  if (open && buTree.value.length === 0) {
    void loadBusinessUnits()
  }
}

function onTargetTypeChange() {
  props.formData.targetUserId = ''
  props.formData.delegatedBuId = ''
  props.formData.delegatedBuCode = ''
  props.formData.delegatedRoleCode = ''
  roleOptions.value = []
  roleLoadError.value = ''
  if (isDelegateBuRole.value) {
    void loadBusinessUnits()
  }
}

async function onBuChange(value: CascaderValue | null | undefined) {
  const raw = Array.isArray(value) ? value[value.length - 1] : value
  const id = raw == null || raw === '' ? '' : String(raw)
  props.formData.delegatedBuId = id
  props.formData.delegatedBuCode = id ? (buIdToCode.value[id] || id) : ''
  props.formData.delegatedRoleCode = ''
  await loadRolesForBu(id)
}

function applyUserLookupValue(val: unknown) {
  const id = extractLookupPrimaryKey(val) ?? ''
  if (id && excludeDelegateUserIds.value.includes(id)) {
    ElMessage.warning(t('delegation.cannotDelegateSelf'))
    return
  }
  props.formData.targetUserId = id
}

function onUserLookupSelect(row: Record<string, unknown>) {
  applyUserLookupValue(row)
}

function onUserLookupModelUpdate(val: unknown) {
  applyUserLookupValue(val)
}

function onUserLookupClear() {
  props.formData.targetUserId = ''
}

function onOpened() {
  emit('opened')
  if (isDelegateBuRole.value) {
    void loadBusinessUnits()
  }
}
</script>

<style lang="scss" scoped>
.task-action-dialog-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding-right: 28px;
}

.task-action-user-lookup {
  width: 100%;
}

:deep(.task-action-form) {
  .el-form-item__label {
    white-space: nowrap;
    text-align: left;
  }

  .task-action-reason-item .el-textarea__inner {
    min-height: 100px;
    font-size: 14px;
  }
}
</style>
