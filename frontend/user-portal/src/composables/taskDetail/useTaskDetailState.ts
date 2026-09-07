import { ref, reactive, computed } from 'vue'
import type { TaskInfo } from '@/api/task'
import type { FormField, FormTab } from '@/components/FormRenderer.vue'
import type { HistoryRecord } from '@/types/historyRecord'
import type {
  ProcessFormData,
  TaskFormData as TaskFormDataDTO,
  CompletedTaskFormData,
} from '@/api/processForm'
import type { RelationFieldDef } from '@/components/subTableAddDialogHelpers'
import type { BindingFieldDefinition } from '@/utils/subTableRowRuntime/types'
import type { MiSubProcessScopeConfig } from '@/composables/tasks/miSubProcessScope'
import { isMiDashboardSubTableBinding } from '@/composables/tasks/shared'
import type { AssignmentConfig } from '@/utils/miAssignmentConfig'

export interface NodeFormInfo {
  formName: string
  /**
   * BPMN activity name of this node — matches flow-history `nodeName`, which the
   * form name often does not (node "assignment" vs form "Assign Task"). Kept so
   * the completed-node Owner snapshot lookup (§6.6) can fall back on the name
   * when activityId is missing.
   */
  bpmnNodeName: string
  isCurrentTask: boolean
  fields: FormField[]
  tabs: FormTab[]
  values: Record<string, any>
  subTableBindings: PreviousFormEntry['subTableBindings']
  /**
   * Designer configJson for this node's form — required so FormRenderer can resolve
   * Link Form targets (subListViews) and apply the per-binding portalViews merge
   * (mergeSubTablePortalViewsForRuntime). Without it the snapshot view silently
   * drops Link Form widgets and falls back to non-portalized columns
   * (#1395 / portal-design-parity).
   */
  formConfig: Record<string, any>
  /**
   * Binding ids declared directly on this form's tableBindings (non-PRIMARY,
   * pre-Link-Form-merge). FormRenderer uses this to distinguish "native"
   * sub-tables (placed widgets) from Link Form targets pulled in via
   * mergeLinkFormTargetBindingsInto.
   */
  nativeSubTableBindingIds: number[]
}

// Previous node forms (read-only display, ordered)
export interface PreviousFormEntry {
  formId: string
  formName: string
  labelWidth: string
  fields: FormField[]
  tabs: FormTab[]
  subTableBindings: Array<{
    bindingId: number
    tableId?: number | null
    bindingType: string
    bindingMode: string
    foreignKeyField: string | null
    tableName: string
    designerTableName?: string
    /** 关联表标识：决定 __subTables__ 走 rt: 还是 dw: 命名空间 */
    relationTableId?: number | null
    relationTableName?: string | null
    tableType: string
    tableDescription: string
    columns: Array<{ field: string; label: string; type?: string; props?: Record<string, any> }>
    formFields?: FormField[]
    formOptions?: Record<string, any>
    assignmentConfig?: AssignmentConfig
    portalViews?: Record<string, any> | null
    primaryKeyFields?: string[]
    /** 见 subTableBindings 上的同名字段：BPMN 给出的 MI 归属事实，覆盖列名启发式。 */
    miCollection?: boolean | null
    data: any[]
  }>
}

export function createTaskDetailState(options: { taskId: string }) {
  const { taskId } = options

  const loading = ref(true)
  /** Staged mount: 0=loading, 1=header+basic, 2=forms, 3=diagram+secondary panels (avoids one 20s+ main-thread block). */
  const detailUiPhase = ref(0)
  const workflowSectionRef = ref<HTMLElement | null>(null)
  /** True once the workflow section intersects the viewport — triggers async ProcessDiagram mount. */
  const diagramInViewport = ref(false)

  const submitting = ref(false)
  const taskInfo = ref<Partial<TaskInfo>>({})
  const effectiveTaskId = computed(() => {
    const currentTaskId = (taskInfo.value as Record<string, unknown>)?.taskId
    return typeof currentTaskId === 'string' && currentTaskId.trim().length > 0 ? currentTaskId : taskId
  })

  // Error state
  const taskError = ref<string | null>(null)
  const processError = ref<string | null>(null)
  const historyError = ref<string | null>(null)

  // Node-to-form mapping for diagram click interaction
  const selectedNodeId = ref<string | null>(null)
  const nodeFormMap = ref<Map<string, NodeFormInfo>>(new Map())
  /** Gate FormRenderer mount until MI hydrate yields — prevents RESULT_CODE_HUNG on heavy el-table trees. */
  const formRenderReady = ref(false)
  /** FU canvas subTable widgets — preserved when task-form configJson overwrites layout. */
  const fuFormSubTableFields = ref<FormField[]>([])
  /** Cached from last successful loadFunctionUnitContent — refreshes nodeFormMap after loadProcessAndTaskFormData merges variables. */
  const lastBindingRelationTableMap = ref<Map<number, number | null>>(new Map())

  const selectedNodeForm = computed<NodeFormInfo | null>(() => {
    if (!selectedNodeId.value) return null
    return nodeFormMap.value.get(selectedNodeId.value) ?? null
  })

  const previousForms = ref<PreviousFormEntry[]>([])

  // Sub-table bindings for the current form
  const subTableBindings = ref<Array<{
    bindingId: number
    tableId?: number | null
    bindingType: string
    bindingMode: string
    foreignKeyField: string | null
    tableName: string
    designerTableName?: string
    /** 关联表标识：决定 __subTables__ 走 rt: 还是 dw: 命名空间 */
    relationTableId?: number | null
    relationTableName?: string | null
    tableType: string
    tableDescription: string
    columns: Array<{ field: string; label: string; type?: string; props?: Record<string, any> }>
    formFields?: FormField[]
    formOptions?: Record<string, any>
    assignmentConfig?: AssignmentConfig
    // Per-binding portalViews loaded from form configJson.subTablePortalViews[bindingId].
    // Used as fallback for SubTable rule nodes without portalViews on rule.props, and as the
    // primary source for unplaced bindings (e.g. accessed only via Link Form).
    portalViews?: Record<string, any> | null
    /** Designer PK columns (admin-center tableBindings); sub-table row merge / identity. */
    primaryKeyFields?: string[]
    /**
     * Authoritative MI-collection flag from the BPMN, overriding the column-name heuristic in
     * {@code isMiDashboardSubTableBinding}. `false` = this process has no multi-instance
     * sub-process, so no binding of it can be an MI dashboard. Undefined = unknown, keep guessing.
     */
    miCollection?: boolean | null
    data: any[]
  }>>([])

  const linkableSubTableBindings = computed(() => [
    ...subTableBindings.value,
    ...previousForms.value.flatMap(form => form.subTableBindings)
  ])

  const isMiSubTaskMode = ref(false)

  /**
   * Full (pre-isolation) {@code __subTables__} snapshot from the task variables, flattened.
   * MI isolation filters {@code binding.data} to the current participant for DISPLAY, but Save must NOT
   * persist that truncated slice or it wipes every other participant's MI-scoped rows (People, Sub Task …).
   * Kept so {@link patchFormDataSubTablesFromCurrentBindings} can re-merge the other participants' rows back.
   */
  const miFullSubTablesSnapshotRef = ref<Record<string, unknown> | null>(null)

  // MI subtask fill-form dialog state
  const miFillDialogVisible = ref(false)
  const miFillDialogData = ref<Record<string, any>>({})
  const miFillSubTableBindings = ref<typeof subTableBindings.value>([])
  const miFilled = ref(false)
  const miFillDialogReadOnly = ref(false)

  // Lookup config fallback map (from rt_lookup_configs)
  const lookupDbConfigs = ref<Record<string, { tableId: number; searchFields: string[]; displayField: string; viewFields: any[] }>>({})

  // Relation view configs from configJson (designed in developer-workstation)
  const relationViewConfigs = ref<Record<string, { viewFields: any[]; allFields: any[] }>>({})

  // Flow history records
  const historyRecords = ref<HistoryRecord[]>([])

  const approveDialogVisible = ref(false)
  const approveDialogTitle = ref('')
  const currentApproveAction = ref('')
  const approveForm = reactive({
    comment: ''
  })

  const actionDialogVisible = ref(false)
  const actionDialogTitle = ref('')
  const currentAction = ref('')
  const actionForm = reactive({
    targetUserId: '',
    reason: '',
    targetType: 'USER' as 'USER' | 'BU_ROLE',
    delegatedBuId: '',
    delegatedBuCode: '',
    delegatedRoleCode: ''
  })

  // User search
  const userOptions = ref<any[]>([])
  const userSearchLoading = ref(false)

  // Task 17: Process Form / Task Form separation state
  const processFormData = ref<ProcessFormData | null>(null)
  const processFormCollapse = ref<string[]>([])  // empty = collapsed
  const processFormEditable = ref(false)
  const processFormFields = ref<FormField[]>([])
  const processFormTabs = ref<FormTab[]>([])
  const processFormValues = ref<Record<string, any>>({})
  /**
   * Sub-table bindings parsed from `pfData.subTableBindings` enriched with
   * configJson (subForms / subTablePortalViews) — feeds FormRenderer so the
   * Process Form panel renders sub-table widgets at parity with the Designer
   * Form Preview (portal-design-parity).
   */
  const processFormSubTableBindings = ref<typeof subTableBindings.value>([])
  /** Designer configJson for the process form (powers Link Form / portalViews merge). */
  const processFormFormConfig = ref<Record<string, unknown>>({})
  /** Native (non-link-target) binding ids on the process form. */
  const processFormNativeSubTableBindingIds = ref<number[]>([])

  // Task 17.2: Task Form data
  const taskFormDTO = ref<TaskFormDataDTO | null>(null)
  const hasConfiguredSaveAction = computed(() =>
    (taskInfo.value.actions || []).some(action => (action.actionType || '').trim().toUpperCase() === 'SAVE')
  )

  // Task 17.3: Completed task snapshot
  const completedFormData = ref<CompletedTaskFormData | null>(null)
  const isCompletedTask = ref(false)

  const miSubProcessScope = ref<MiSubProcessScopeConfig | null>(null)
  const miMissingPrimaryKeyWarned = new Set<string>()
  const miSubProcessScopeName = computed(() => miSubProcessScope.value?.subTableName ?? null)

  // Task 17.4: Return_To_Requester state
  const isReturnToRequester = ref(false)

  /**
   * Show the Process Form panel iff we have process form data AND the user can
   * actually act on it (Return_To_Requester editable mode). Prior code kept this
   * as a never-flipped `ref(false)`, which silently dead-coded the entire panel.
   * Read-only completed-task snapshots are surfaced by TaskSnapshotSection,
   * so this panel is reserved for the editable Return_To_Requester scenario.
   */
  const showProcessFormPanel = computed(() =>
    !!processFormData.value && processFormEditable.value,
  )

  // Whether the PRIMARY table binding has bindingMode READONLY.
  // When true, main form fields are disabled but sub-tables retain their own editability.
  const primaryReadOnly = ref(false)
  const primaryTableBinding = ref<{
    tableId?: number | null
    tableName?: string
    fieldDefinitions?: BindingFieldDefinition[]
  } | null>(null)
  /** PRIMARY table field names — MI isolate/save must not treat collection-row keys as main-record scalars. */
  const primaryTableFieldNames = ref<Set<string>>(new Set())
  const functionUnitIdRef = ref<string>('')

  /**
   * Designer configJson for the currently selected task's main form. Required by
   * FormRenderer to resolve Link Form targets (subListViews / boundSubTableBindingId)
   * and to drive mergeSubTablePortalViewsForRuntime portalViews merges. Mirrors
   * applications/detail.vue's mainFormConfig (#1395 / portal-design-parity).
   */
  const mainFormConfig = ref<Record<string, any>>({})
  /**
   * Binding ids declared directly on the main form's tableBindings (non-PRIMARY,
   * pre-Link-Form-merge). Lets FormRenderer distinguish "native" sub-tables placed
   * on this form's canvas from Link Form targets pulled in from other FU forms.
   */
  const mainFormNativeSubTableBindingIds = ref<number[]>([])

  /** Show sub-table Assign on participant-assignment tasks and any assignee form with an editable MI collection grid. */
  const allowSubTableAssignForCurrentTask = computed(() => {
    const tdk = (taskInfo.value as { taskDefinitionKey?: string }).taskDefinitionKey || ''
    if (tdk === 'Task_AssignParticipants' || tdk === 'Activity_0hwtl8v') return true
    if (isMiSubTaskMode.value) return false
    return subTableBindings.value.some(
      b => isMiDashboardSubTableBinding(b) && b.bindingMode !== 'READONLY',
    )
  })

  return {
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
    nodeFormMap,
    formRenderReady,
    fuFormSubTableFields,
    lastBindingRelationTableMap,
    selectedNodeForm,
    previousForms,
    subTableBindings,
    linkableSubTableBindings,
    isMiSubTaskMode,
    miFullSubTablesSnapshotRef,
    miFillDialogVisible,
    miFillDialogData,
    miFillSubTableBindings,
    miFilled,
    miFillDialogReadOnly,
    lookupDbConfigs,
    relationViewConfigs,
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
    miSubProcessScope,
    miMissingPrimaryKeyWarned,
    miSubProcessScopeName,
    isReturnToRequester,
    showProcessFormPanel,
    primaryReadOnly,
    primaryTableBinding,
    primaryTableFieldNames,
    functionUnitIdRef,
    mainFormConfig,
    mainFormNativeSubTableBindingIds,
    allowSubTableAssignForCurrentTask,
    /** Deferred BPM node→form map build (diagram clicks only) — avoids blocking first paint on MI cases. */
    deferredNodeFormMapContent: null as { content: any; bindingRelationTableMap: Map<number, number | null> } | null,
    /** All forms in the current function unit — used to resolve sub-table schema by shared {@code tableId}. */
    cachedContentForms: [] as any[],
    cachedRelationTableFieldIndex: new Map<number, RelationFieldDef[]>(),
    diagramViewportObserver: null as IntersectionObserver | null,
  }
}

export type TaskDetailState = ReturnType<typeof createTaskDetailState>
