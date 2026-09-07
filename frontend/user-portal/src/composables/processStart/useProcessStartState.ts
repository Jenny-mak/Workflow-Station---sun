import { ref } from 'vue'
import type { ProcessNode, ProcessFlow } from '@/components/ProcessDiagram.vue'
import type { HistoryRecord } from '@/components/ProcessHistory.vue'
import type { FormField, FormTab } from '@/components/FormRenderer.vue'
import FormRenderer from '@/components/FormRenderer.vue'
import type { RelationFieldDef } from '@/components/subTableAddDialogHelpers'
import type { BindingFieldDefinition } from '@/utils/subTableRowRuntime'
import type { AssignmentConfig } from '@/utils/miAssignmentConfig'

/**
 * Sub-table binding shape for the start form (same data as the original inline ref).
 * Field types align with FormRenderer's `SubTableBinding` prop so the orchestrator can
 * pass `subTableBindings` straight through without a cast.
 */
export interface ProcessStartSubTableBinding {
  bindingId: number
  tableId?: number | null
  bindingType: string
  bindingMode: string
  tableName: string
  designerTableName?: string
  tableType: string
  tableDescription: string
  /** Designer PK columns from tableBindings (admin-center); avoids hardcoding id/rowId. */
  primaryKeyFields?: string[]
  columns: Array<{ field: string; label: string; type?: string }>
  /** Form-design canvas columns for Add/Edit dialog (excludes list-view-only fields). */
  dialogColumns?: Array<{ field: string; label: string; type?: string }>
  /** Parsed sub-form design fields — backs the form-below-table inline form (incl. nested sub-tables). */
  formFields?: FormField[]
  /** Designer Form events (onChange / onCreated / …) — same contract as task-detail subFormDesign. */
  formOptions?: Record<string, unknown>
  assignmentConfig?: AssignmentConfig
  data: any[]
  fieldDefinitions?: BindingFieldDefinition[]
  bindingLinkMode?: string
  foreignKeyField?: string | null
}

/**
 * Reactive state + mutable caches for processes/start.vue.
 * Pure container — no behavior — so composables and the SFC orchestrator share one source of truth.
 */
export function createProcessStartState() {
  // 状态
  const loading = ref(true)
  const loadError = ref('')
  const isDisabled = ref(false)
  const isAccessDenied = ref(false)
  const noProcessForm = ref(false)
  const submitting = ref(false)
  const savingDraft = ref(false)
  const currentAction = ref('')

  // 功能单元信息
  const functionUnitName = ref('')
  const functionUnitVersion = ref('')
  const functionUnitCode = ref('')

  // 流程图数据
  const processNodes = ref<ProcessNode[]>([])
  const processFlows = ref<ProcessFlow[]>([])
  const currentNodeId = ref('')
  const completedNodeIds = ref<string[]>([])
  const bpmnXml = ref('')

  // 表单数据
  const formFields = ref<FormField[]>([])
  const formTabs = ref<FormTab[]>([])
  const formFieldsAfterTabs = ref<FormField[]>([])
  const formData = ref<Record<string, any>>({})
  const currentFormName = ref('')
  const formLabelWidth = ref('160px')
  const formLabelPosition = ref<'left' | 'right' | 'top'>('left')
  const formFormOptions = ref<Record<string, unknown>>({})
  const formConfigJson = ref<Record<string, unknown> | null>(null)
  const formRendererRef = ref<InstanceType<typeof FormRenderer> | null>(null)

  // Sub-table bindings for the start form
  const subTableBindings = ref<ProcessStartSubTableBinding[]>([])

  const primaryTableBinding = ref<{
    tableId?: number | null
    tableName?: string
    fieldDefinitions?: BindingFieldDefinition[]
  } | null>(null)

  /** Mutable caches reassigned during loadFunctionUnitContent; held in one object so writes propagate. */
  const caches: {
    cachedContentForms: unknown[]
    cachedRelationTableFieldIndex: Map<number, RelationFieldDef[]>
  } = {
    cachedContentForms: [],
    cachedRelationTableFieldIndex: new Map<number, RelationFieldDef[]>(),
  }

  // Lookup config fallback map (from rt_lookup_configs)
  const lookupDbConfigs = ref<Record<string, { tableId: number; searchFields: string[]; displayField: string; viewFields: any[] }>>({})

  // Relation view configs from configJson (designed in developer-workstation)
  const relationViewConfigs = ref<Record<string, { viewFields: any[]; allFields: any[] }>>({})

  // 流转记录
  const historyRecords = ref<HistoryRecord[]>([])

  // 可用动作
  const availableActions = ref<Array<{
    id: string
    label: string
    type?: 'primary' | 'success' | 'warning' | 'danger' | 'info'
    /** 设计器配置的按钮颜色（`#RRGGBB`），有值时覆盖 type 的语义配色。 */
    buttonColor?: string
    action?: string
    actionType?: string
    configJson?: string
  }>>([])

  return {
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
  }
}

export type ProcessStartState = ReturnType<typeof createProcessStartState>
