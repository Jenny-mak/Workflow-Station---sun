import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, type CascaderValue } from 'element-plus'
import { getStoredUser, USER_ID_KEY } from '@/api/auth'
import { permissionApi, type BusinessUnit, type RoleInfo } from '@/api/permission'
import { extractLookupPrimaryKey } from '@/utils/mainTableViewLookupDisplay'

/** Platform sys_users virtual relation table — same source as task Delegate lookup. */
export const SYSTEM_USER_LOOKUP_TABLE_ID = -1_000_000_001
export const SYSTEM_USER_SEARCH_FIELDS = [
  'id',
  'username',
  'display_name',
  'full_name',
  'email',
  'employee_id',
]
export const SYSTEM_USER_DISPLAY_FIELDS = [
  'username',
  'display_name',
  'full_name',
  'email',
  'employee_id',
]
export const DELEGATE_USER_LOOKUP_PAGE_SIZE = 200

export function currentPortalUserId(): string {
  const stored = getStoredUser()?.userId?.trim()
  const fallback = (typeof localStorage !== 'undefined' ? localStorage.getItem(USER_ID_KEY) : '') || ''
  return stored || fallback.trim()
}

export interface DelegationTargetForm {
  targetType: 'USER' | 'BU_ROLE'
  delegateId: string
  delegatedBuId: string
  delegatedBuCode: string
  delegatedRoleCode: string
}

export function useDelegationTargetPickers(form: DelegationTargetForm) {
  const { t } = useI18n()
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

  const systemUserViewFields = computed(() =>
    SYSTEM_USER_DISPLAY_FIELDS.map((fieldName, sortOrder) => ({
      fieldName,
      displayLabel: t(`task.userLookupCol.${fieldName}`),
      sortOrder,
      visible: true,
    })),
  )

  const isBuRole = computed(() => form.targetType === 'BU_ROLE')

  const excludeDelegateUserIds = computed(() => {
    const id = currentPortalUserId()
    return id ? [id] : []
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
    form.delegateId = ''
    form.delegatedBuId = ''
    form.delegatedBuCode = ''
    form.delegatedRoleCode = ''
    roleOptions.value = []
    roleLoadError.value = ''
    if (isBuRole.value) {
      void loadBusinessUnits()
    }
  }

  async function onBuChange(value: CascaderValue | null | undefined) {
    const raw = Array.isArray(value) ? value[value.length - 1] : value
    const id = raw == null || raw === '' ? '' : String(raw)
    form.delegatedBuId = id
    form.delegatedBuCode = id ? (buIdToCode.value[id] || id) : ''
    form.delegatedRoleCode = ''
    await loadRolesForBu(id)
  }

  function applyUserLookupValue(val: unknown) {
    const id = extractLookupPrimaryKey(val) ?? ''
    if (id && excludeDelegateUserIds.value.includes(id)) {
      ElMessage.warning(t('delegation.cannotDelegateSelf'))
      return
    }
    form.delegateId = id
  }

  function onOpened() {
    if (isBuRole.value) {
      void loadBusinessUnits()
    }
  }

  return {
    systemUserViewFields,
    isBuRole,
    buTree,
    roleOptions,
    buLoading,
    roleLoading,
    buLoadError,
    roleLoadError,
    buCascaderProps,
    loadBusinessUnits,
    onBuVisibleChange,
    onTargetTypeChange,
    onBuChange,
    applyUserLookupValue,
    onOpened,
    excludeDelegateUserIds,
  }
}
