import axios from 'axios'
import { getUser } from './auth'
import i18n from '@/i18n'
import { getActiveGroupHeaderValue } from '@/utils/devGroupContext'

// Create a separate axios instance for function unit API
export const functionUnitAxios = axios.create({
  baseURL: '',
  timeout: 30000,
  withCredentials: true,
  // Serialize array params as repeated keys (tags=A&tags=B) for Spring MVC compatibility
  paramsSerializer: {
    serialize: (params: Record<string, unknown>): string => {
      const parts: string[] = []
      for (const [key, value] of Object.entries(params)) {
        if (value === undefined || value === null) continue
        if (Array.isArray(value)) {
          for (const item of value) {
            parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(item)))
          }
        } else {
          parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(value)))
        }
      }
      return parts.join('&')
    }
  }
})

functionUnitAxios.interceptors.request.use(config => {
  // Tell the backend which language this UI speaks. Without it its locale resolver falls back to
  // the browser's own Accept-Language, so a Chinese browser got Chinese server strings (deploy
  // step labels, error text) inside this English-only designer.
  config.headers['Accept-Language'] = i18n.global.locale.value

  // Add X-User-Id request header for backend permission check
  const user = getUser()
  if (user && user.userId) {
    config.headers['X-User-Id'] = user.userId
  }

  // Active dev group (team) — visibility filter only; backend re-validates membership.
  const activeGroupId = getActiveGroupHeaderValue()
  if (activeGroupId) {
    config.headers['X-Dev-Group-Id'] = activeGroupId
  }

  return config
})

functionUnitAxios.interceptors.response.use(
  response => {
    // Check the success field in the response body to ensure it's a successful response
    if (response.data && typeof response.data === 'object' && 'success' in response.data) {
      if (response.data.success === false) {
        // If success is false, even with HTTP status 200, it should be treated as an error
        const error = new Error(response.data.error?.message || i18n.global.t('api.requestFailed'))
        ;(error as any).response = {
          status: response.data.error?.code === '403' ? 403 : 500,
          data: response.data
        }
        return Promise.reject(error)
      }
    }
    return response.data
  },
  async error => {
    const { response } = error
    
    // Handle 401 Unauthorized
    if (response?.status === 401) {
      const { clearAuth } = await import('./auth')
      const { redirectToUnifiedLogin } = await import('@/utils/sso')
      clearAuth()
      redirectToUnifiedLogin('developer-workstation', { autoSso: true })
      return Promise.reject(error)
    }
    
    // Handle 403 Forbidden
    if (response?.status === 403) {
      const { clearAuth } = await import('./auth')
      const { getStoredUser } = await import('./auth')
      const user = getStoredUser()
      if (!user) {
        clearAuth()
        const { redirectToUnifiedLogin } = await import('@/utils/sso')
        redirectToUnifiedLogin('developer-workstation', { autoSso: true })
      }
      return Promise.reject(error)
    }
    
    return Promise.reject(error)
  }
)

export interface FunctionUnit {
  id: number
  name: string
  description?: string
  tags?: string[]
  icon?: { id: number; name: string; url: string }
  status: string
  currentVersion?: string
  createdBy: string
  createdAt: string
  updatedBy?: string
  updatedAt?: string
  tableDefinitions?: TableDefinition[]
  formDefinitions?: FormDefinition[]
  actionDefinitions?: ActionDefinition[]
  processDefinition?: ProcessDefinition
  /** Backend resource-level write flag. Missing means not writable. */
  canModify?: boolean
}

export interface FunctionUnitResponse {
  id: number
  name: string
  description?: string
  tags?: string[]
  iconId?: number
  iconUrl?: string
  status: string
  currentVersion?: string
  createdAt: string
  updatedAt?: string
  tableCount: number
  formCount: number
  actionCount: number
  hasProcess: boolean
  /** Backend resource-level write flag. Missing means not writable. */
  canModify?: boolean
}

export interface FunctionUnitRequest {
  name: string
  description?: string
  iconId?: number
  tags?: string[]
  /** Team (virtual group) ids that own/see this FU. Only honoured on create. */
  virtualGroupIds?: string[]
}

/** Main-table Request ID config: ordered fields + separator joined into a human-readable
 *  request identifier (e.g. HR-2026-001). Meaningful for MAIN tables only. */
export interface RequestIdConfig {
  /** Ordered field names; array order = join order (stores fieldName). */
  fieldNames: string[]
  /** Separator between fields, e.g. '-' / '/' / '_' / '.' / ' ' / '' (none). */
  separator: string
}

export interface TableDefinition {
  id: number
  tableName: string
  tableDisplayName?: string
  tableType: string
  description?: string
  requestIdConfig?: RequestIdConfig | null
  fieldDefinitions: FieldDefinition[]
}

export interface FieldDefinition {
  id?: number
  fieldName: string
  dataType: string
  length?: number
  precision?: number
  scale?: number
  nullable: boolean
  isPrimaryKey: boolean
  defaultValue?: string
  displayName?: string
  isForeignKey?: boolean
  refTableId?: number
  refPrimaryKeyFields?: string[]
  pkGeneration?: Record<string, unknown>
  pkGenerationJson?: Record<string, unknown>
  fkDisplayMode?: 'readonly' | 'hidden'
  relationCardinality?: string
  isComputed?: boolean
  computedField?: Record<string, unknown>
  computedFieldJson?: Record<string, unknown>
}

export type FormType = 'PROCESS' | 'TASK' | 'ACTION' | 'DETAIL'

/**
 * Which portal scene a form design serves. A node can hold one design per scene,
 * so To Do and My Requests are laid out independently instead of sharing one
 * form trimmed by sub-table config.
 */
export type FormScene = 'TASK' | 'REQUEST'

export interface StageBinding {
  id?: number
  stageId: string
  stageName?: string
  /** Omitted means TASK — the To Do design. */
  scene?: FormScene
}

export interface FormDefinition {
  id: number
  formName: string
  formType: FormType
  /** Omitted means TASK — the To Do design. */
  scene?: FormScene
  description?: string
  configJson: Record<string, any>
  boundTableId?: number
  boundTableName?: string
  formSchema?: string // deprecated, use configJson instead
  tableBindings?: TableBinding[]
  fieldPermissions?: Record<string, string>
  showLiveValues?: boolean
  stageBindings?: StageBinding[]
  /**
   * Create-only flag: build the To Do and My Requests designs of one step in a single request.
   * Honoured for PROCESS and TASK forms; the My Requests row takes a name suffix because form
   * names are unique per function unit, and the two are linked by BPMN node id, not by name.
   */
  createBothScenes?: boolean
}

// Table binding type
export type BindingType = 'PRIMARY' | 'SUB' | 'RELATED' | 'ACTION'

// Sub binding mode
export type SubBindingMode = 'FULL' | 'FORM_ONLY'

// Binding mode
export type BindingMode = 'EDITABLE' | 'READONLY'

// Table binding interface
export type BindingLinkMode = 'structuralFk' | 'miParticipantRow'

export interface TableBinding {
  id?: number
  tableId: number
  tableName?: string
  tableType?: string
  bindingType: BindingType
  bindingMode: BindingMode
  foreignKeyField?: string
  bindingLinkMode?: BindingLinkMode
  sortOrder: number
  subListViewId?: number
  subMode?: SubBindingMode
}

// Table binding request
export interface TableBindingRequest {
  tableId?: number
  relationTableId?: number
  bindingType: BindingType
  bindingMode?: BindingMode
  foreignKeyField?: string
  bindingLinkMode?: BindingLinkMode
  sortOrder?: number
  subMode?: SubBindingMode
}

export interface ActionDefinition {
  id: string | number // Support both String IDs (new) and numeric IDs (legacy)
  actionName: string
  actionType: string
  description?: string
  /** User Portal button colour, stored as a `#RRGGBB` hex string (legacy rows may hold an Element Plus type name). */
  buttonColor?: string
  configJson: Record<string, any>
  actionConfig?: string // deprecated, use configJson instead
}

export interface ProcessDefinition {
  id: number
  processKey: string
  processName: string
  bpmnXml?: string
  description?: string
}

export interface TableRelationDTO {
  id?: number
  sourceTableId: number
  sourceFieldName: string
  relationType: string
  targetTableId: number
  targetFieldName: string
}

export interface ForeignKeyDTO {
  id: number
  sourceTableId: number
  sourceTableName: string
  sourceFieldId: number
  sourceFieldName: string
  targetTableId: number
  targetTableName: string
  targetFieldId: number
  targetFieldName: string
  onDelete?: string
  onUpdate?: string
}

export interface ValidationResult {
  valid: boolean
  errors: string[]
  warnings: string[]
}

export interface GatewayEvaluationItem {
  flowId: string
  condition?: string
  result: boolean
  reason?: string
}

export interface GatewayEvaluation {
  gatewayId: string
  gatewayType?: string
  defaultFlowId?: string
  selectedFlowId?: string
  evaluations: GatewayEvaluationItem[]
}

export interface DebugLookupProbeRequest {
  formId: number
  bindingId: number
  lookupConfig: Record<string, any>
  keyword?: string
  runtimeVariables?: Record<string, any>
  page?: number
  size?: number
  sort?: string[]
  searchMode?: 'contains' | 'startsWith' | 'exact'
}

export interface DebugLookupProbeResult {
  columns: Array<{ fieldName: string; label?: string }>
  rows: Array<Record<string, any>>
  appliedFilters?: Array<{ fieldName: string; value: any }>
  page: number
  size: number
  total: number
}

export interface DebugActionRunRequest {
  nodeId: string
  actionId: string | number
  runtimeVariables?: Record<string, any>
  formData?: Record<string, any>
  dryRun?: boolean
}

export interface DebugActionRunResult {
  success: boolean
  actionResult?: Record<string, any>
  variablePatches?: Record<string, any>
  logs?: string[]
  durationMs?: number
}

export interface Version {
  id: number
  versionNumber: string
  changeLog?: string
  createdBy: string
  createdAt: string
  current?: boolean
  snapshotData?: string
}

export interface DevGroupOption {
  id: string
  name: string
  /** ACTIVE selectable; INACTIVE listed but disabled in the switcher */
  status?: string
}

export interface MyDevGroups {
  groups: DevGroupOption[]
  canSeeAllGroups: boolean
  publicGroupId: string
}

export const functionUnitApi = {
  /**
   * Whether the current user may enter the function unit workspace.
   * True for DW capability roles or members of a team (virtual group) that owns at least
   * one function unit (read-only baseline). Used by the router guard to admit role-less
   * team members who would otherwise fail the role-based check.
   */
  getWorkspaceAccess: () =>
    functionUnitAxios.get<any, { data: { canView: boolean } }>('/api/v1/function-units/workspace-access'),

  /**
   * Current user's selectable dev teams (for the entry dialog + header switcher), whether
   * they may view all function units (ADMIN), and the built-in Public group id.
   */
  getMyDevGroups: () =>
    functionUnitAxios.get<any, { data: MyDevGroups }>('/api/v1/function-units/my-dev-groups'),

  // Function Unit CRUD
  list: (params: { name?: string; status?: string; tags?: string[]; page?: number; size?: number; sort?: string }) =>
    functionUnitAxios.get<any, { data: { content: FunctionUnitResponse[]; totalElements: number } }>('/api/v1/function-units', { params }),

  /** Get all distinct tags across all enabled function units (for filter dropdown). */
  getAllTags: () =>
    functionUnitAxios.get<any, { data: string[] }>('/api/v1/function-units/tags'),
  
  getById: (id: number) => 
    functionUnitAxios.get<any, { data: FunctionUnit }>(`/api/v1/function-units/${id}`),
  
  create: (data: FunctionUnitRequest) => 
    functionUnitAxios.post<any, { data: FunctionUnit }>('/api/v1/function-units', data),
  
  update: (id: number, data: FunctionUnitRequest) => 
    functionUnitAxios.put<any, { data: FunctionUnit }>(`/api/v1/function-units/${id}`, data),
  
  delete: (id: number) =>
    functionUnitAxios.delete(`/api/v1/function-units/${id}`),

  // Restore an ARCHIVED function unit back to DRAFT
  restore: (id: number) =>
    functionUnitAxios.post<any, { data: FunctionUnit }>(`/api/v1/function-units/${id}/restore`),

  // POST /{id}/publish still exists on the backend, but only the Deploy flow calls it internally;
  // the DW frontend no longer publishes directly.

  clone: (id: number, newName: string) => 
    functionUnitAxios.post<any, { data: FunctionUnit }>(`/api/v1/function-units/${id}/clone`, null, { params: { newName } }),
  
  validate: (id: number) => 
    functionUnitAxios.get<any, { data: ValidationResult }>(`/api/v1/function-units/${id}/validate`),

  // Team (virtual dev group) assignments — controls FU visibility scope
  getDevGroups: (id: number) =>
    functionUnitAxios.get<any, { data: string[] }>(`/api/v1/function-units/${id}/dev-groups`),

  replaceDevGroups: (id: number, virtualGroupIds: string[]) =>
    functionUnitAxios.put<any, { data: void }>(`/api/v1/function-units/${id}/dev-groups`, { virtualGroupIds }),

  // Table Definitions
  getTables: (functionUnitId: number) =>
    functionUnitAxios.get<any, { data: TableDefinition[] }>(`/api/v1/function-units/${functionUnitId}/tables`),
  
  createTable: (functionUnitId: number, data: Partial<TableDefinition>) =>
    functionUnitAxios.post<any, { data: TableDefinition }>(`/api/v1/function-units/${functionUnitId}/tables`, data),

  checkTableNameAvailable: (functionUnitId: number, tableName: string, excludeTableId?: number) =>
    functionUnitAxios.get<any, { data: { available: boolean; tableName: string } }>(
      `/api/v1/function-units/${functionUnitId}/tables/name-available`,
      { params: { tableName, excludeTableId } },
    ),
  
  updateTable: (functionUnitId: number, tableId: number, data: Partial<TableDefinition>) =>
    functionUnitAxios.put<any, { data: TableDefinition }>(`/api/v1/function-units/${functionUnitId}/tables/${tableId}`, data),
  
  deleteTable: (functionUnitId: number, tableId: number) =>
    functionUnitAxios.delete(`/api/v1/function-units/${functionUnitId}/tables/${tableId}`),

  // Form Definitions
  getForms: (functionUnitId: number) =>
    functionUnitAxios.get<any, { data: FormDefinition[] }>(`/api/v1/function-units/${functionUnitId}/forms`),
  
  createForm: (functionUnitId: number, data: Partial<FormDefinition>) =>
    functionUnitAxios.post<any, { data: FormDefinition }>(`/api/v1/function-units/${functionUnitId}/forms`, data),
  
  updateForm: (functionUnitId: number, formId: number, data: Partial<FormDefinition>) =>
    functionUnitAxios.put<any, { data: FormDefinition }>(`/api/v1/function-units/${functionUnitId}/forms/${formId}`, data),
  
  deleteForm: (functionUnitId: number, formId: number) =>
    functionUnitAxios.delete(`/api/v1/function-units/${functionUnitId}/forms/${formId}`),

  // Action Definitions
  getActions: (functionUnitId: number) =>
    functionUnitAxios.get<any, { data: ActionDefinition[] }>(`/api/v1/function-units/${functionUnitId}/actions`),
  
  createAction: (functionUnitId: number, data: Partial<ActionDefinition>) =>
    functionUnitAxios.post<any, { data: ActionDefinition }>(`/api/v1/function-units/${functionUnitId}/actions`, data),
  
  updateAction: (functionUnitId: number, actionId: string | number, data: Partial<ActionDefinition>) =>
    functionUnitAxios.put<any, { data: ActionDefinition }>(`/api/v1/function-units/${functionUnitId}/actions/${actionId}`, data),
  
  deleteAction: (functionUnitId: number, actionId: string | number) =>
    functionUnitAxios.delete(`/api/v1/function-units/${functionUnitId}/actions/${actionId}`),

  // Process Definition
  getProcess: (functionUnitId: number) =>
    functionUnitAxios.get<any, { data: ProcessDefinition }>(`/api/v1/function-units/${functionUnitId}/process`),
  
  // allowEmpty=true is required to overwrite a stored non-empty process with an empty diagram
  // (backend empty-diagram guard, see ProcessDesignComponentImpl#save).
  saveProcess: (
    functionUnitId: number,
    data: Partial<ProcessDefinition>,
    options?: { allowEmpty?: boolean }
  ) =>
    functionUnitAxios.post<any, { data: ProcessDefinition }>(
      `/api/v1/function-units/${functionUnitId}/process`,
      data,
      options?.allowEmpty ? { params: { allowEmpty: true } } : undefined
    ),

  // Versions
  getVersions: (functionUnitId: number) =>
    functionUnitAxios.get<any, { data: Version[] }>(`/api/v1/function-units/${functionUnitId}/versions`),
  
  rollback: (functionUnitId: number, versionId: number) =>
    functionUnitAxios.post<any, { data: FunctionUnit }>(`/api/v1/function-units/${functionUnitId}/versions/${versionId}/rollback`),

  compareVersions: (functionUnitId: number, versionId1: number, versionId2: number) =>
    functionUnitAxios.get<any, { data: any }>(`/api/v1/function-units/${functionUnitId}/versions/compare`, {
      params: { versionId1, versionId2 }
    }),

  // Table DDL
  generateDDL: (functionUnitId: number, tableId: number, dialect: string) =>
    functionUnitAxios.get<any, { data: string }>(`/api/v1/function-units/${functionUnitId}/tables/${tableId}/ddl`, {
      params: { dialect }
    }),

  validateTables: (functionUnitId: number) =>
    functionUnitAxios.get<any, { data: ValidationResult }>(`/api/v1/function-units/${functionUnitId}/tables/validate`),

  // Table Relations
  saveTableRelations: (functionUnitId: number, relations: TableRelationDTO[]) =>
    functionUnitAxios.post<any, { data: TableRelationDTO[] }>(`/api/v1/function-units/${functionUnitId}/table-relations`, relations),

  getTableRelations: (functionUnitId: number) =>
    functionUnitAxios.get<any, { data: TableRelationDTO[] }>(`/api/v1/function-units/${functionUnitId}/table-relations`),

  // Foreign Keys
  getForeignKeys: (functionUnitId: number) =>
    functionUnitAxios.get<any, { data: ForeignKeyDTO[] }>(`/api/v1/function-units/${functionUnitId}/tables/foreign-keys`),

  allocatePrimaryKeys: (functionUnitId: number, payload: {
    tableId: number
    fieldName: string
    count?: number
    scopeKey?: string
  }) =>
    functionUnitAxios.post<any, { data: { values: string[] } }>(
      `/api/v1/function-units/${functionUnitId}/tables/primary-keys/allocate`,
      payload,
    ),

  // Action test
  testAction: (functionUnitId: number, actionId: string | number, testData: any) =>
    functionUnitAxios.post<any, { data: any }>(`/api/v1/function-units/${functionUnitId}/actions/${actionId}/test`, testData),

  // Process validation
  validateProcess: (functionUnitId: number) =>
    functionUnitAxios.get<any, { data: ValidationResult }>(`/api/v1/function-units/${functionUnitId}/process/validate`),

  // Process simulation
  simulateProcess: (functionUnitId: number, variables: any) =>
    functionUnitAxios.post<any, { data: any }>(`/api/v1/function-units/${functionUnitId}/process/simulate`, variables),

  // Process debug lookup probe
  debugLookupProbe: (functionUnitId: number, payload: DebugLookupProbeRequest) =>
    functionUnitAxios.post<any, { data: DebugLookupProbeResult }>(
      `/api/v1/function-units/${functionUnitId}/process/debug/lookup/probe`,
      payload
    ),

  // Process debug action runner
  debugRunAction: (functionUnitId: number, payload: DebugActionRunRequest) =>
    functionUnitAxios.post<any, { data: DebugActionRunResult }>(
      `/api/v1/function-units/${functionUnitId}/process/debug/actions/run`,
      payload
    ),

  // Form Table Bindings
  getFormBindings: (functionUnitId: number, formId: number) =>
    functionUnitAxios.get<any, { data: TableBinding[] }>(`/api/v1/function-units/${functionUnitId}/forms/${formId}/bindings`),
  
  createFormBinding: (functionUnitId: number, formId: number, data: TableBindingRequest) =>
    functionUnitAxios.post<any, { data: TableBinding }>(`/api/v1/function-units/${functionUnitId}/forms/${formId}/bindings`, data),
  
  updateFormBinding: (functionUnitId: number, formId: number, bindingId: number, data: TableBindingRequest) =>
    functionUnitAxios.put<any, { data: TableBinding }>(`/api/v1/function-units/${functionUnitId}/forms/${formId}/bindings/${bindingId}`, data),
  
  deleteFormBinding: (functionUnitId: number, formId: number, bindingId: number) =>
    functionUnitAxios.delete(`/api/v1/function-units/${functionUnitId}/forms/${formId}/bindings/${bindingId}`),

  // Form Design Helpers
  getDataTableColumns: (functionUnitId: number) =>
    functionUnitAxios.get<any, { data: string[] }>(`/api/v1/function-units/${functionUnitId}/forms/data-table-columns`),

  copyTaskForm: (functionUnitId: number, formId: number) =>
    functionUnitAxios.post<any, { data: FormDefinition }>(`/api/v1/function-units/${functionUnitId}/forms/${formId}/copy`),

  copyProcessToTaskForm: (functionUnitId: number, formId: number) =>
    functionUnitAxios.post<any, { data: FormDefinition }>(`/api/v1/function-units/${functionUnitId}/forms/${formId}/copy-to-task`),

  /** Remap stale bindingId/tableId in a pasted form configJson against this form's bindings. */
  repairFormConfig: (
    functionUnitId: number,
    formId: number,
    data: { configJson: Record<string, unknown>; apply?: boolean; createMissingTables?: boolean },
  ) =>
    functionUnitAxios.post<any, {
      data: {
        configJson: Record<string, unknown>
        bindingIdMapping: Record<string, string>
        relationTableIdMapping: Record<string, string>
        warnings: string[]
        mixedSource: boolean
        applied: boolean
        createdTableNames?: string[]
      }
    }>(`/api/v1/function-units/${functionUnitId}/forms/${formId}/repair-config`, data),

  // Export, Import and Deploy
  exportFunctionUnit: (functionUnitId: number) =>
    functionUnitAxios.get(`/api/v1/function-units/${functionUnitId}/export`, { responseType: 'blob' }),

  // Name does not exist → new import; name exists → add a version. Optional changeLog is recorded on the version.
  importFunctionUnit: (file: File, changeLog?: string) => {
    const formData = new FormData()
    formData.append('file', file)
    return functionUnitAxios.post<any, { data: { status: string; message?: string; functionUnitId?: number; version?: string; versioned?: boolean; automationFlows?: AutomationFlowRestoreResult[] } }>(
      '/api/v1/export-import/import',
      formData,
      { params: changeLog ? { changeLog } : {} }
    )
  },
  
  deploy: (functionUnitId: number, request: DeployRequest) =>
    functionUnitAxios.post<any, { data: DeployResponse }>(`/api/v1/function-units/${functionUnitId}/deploy`, request),
  
  getDeploymentStatus: (deploymentId: string) =>
    functionUnitAxios.get<any, { data: DeployResponse }>(`/api/v1/function-units/deployments/${deploymentId}/status`),
  
  getDeploymentHistory: (functionUnitId: number) =>
    functionUnitAxios.get<any, { data: DeployResponse[] }>(`/api/v1/function-units/${functionUnitId}/deployments`)
}

/**
 * Restore result, in this environment, for an Automation flow carried by an import package.
 * PUBLISH_FAILED = the draft was created but not published (usually because this environment
 * is missing connection credentials); someone has to supply them and publish manually.
 */
export interface AutomationFlowRestoreResult {
  flowKey: string
  displayName: string
  flowId: string
  status: 'CREATED' | 'ALREADY_PRESENT' | 'PUBLISH_FAILED'
  detail?: string
}

// Deploy types
export interface DeployRequest {
  targetUrl?: string
  environment?: 'DEVELOPMENT' | 'TESTING' | 'PRODUCTION'
  conflictStrategy?: string
  autoEnable?: boolean
  changeLog?: string
}


export interface DeployResponse {
  deploymentId: string
  status: 'PENDING' | 'DEPLOYING' | 'SUCCESS' | 'FAILED' | 'ROLLED_BACK'
  message?: string
  progress?: number
  steps?: DeployStep[]
  deployedAt?: string
  versionNumber?: string
  changeLog?: string
}


export interface DeployStep {
  name: string
  status: string
  message?: string
  completedAt?: string
}
