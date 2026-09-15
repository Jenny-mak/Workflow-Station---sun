import { get, post, put, del } from './request'
import type { AdminListPage } from '@/types/common'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'

// ==================== Enums / Type Aliases ====================

export type DashboardStatus = 'ACTIVE' | 'AUTO_INACTIVE' | 'MANUAL_INACTIVE'
export type AssignmentTargetType = 'USER' | 'ROLE' | 'BUSINESS_UNIT'
export type LayoutMode = 'SINGLE' | 'MULTI' | 'WIDGET'
export type SupersetRoleStatus = 'ACTIVE' | 'INACTIVE'

// ==================== Response Types ====================

export interface DashboardRegistryResponse {
  id: string
  dashboardTitle: string
  description: string
  embedId: string
  supersetDashboardUuid: string
  supersetDashboardId: number
  tags: string
  isDefaultLanding: boolean
  status: DashboardStatus
  lastSyncedAt: string
  createdAt: string
  updatedAt: string
}

export interface DashboardAssignmentResponse {
  id: string
  dashboardId: string
  dashboardTitle: string
  targetType: AssignmentTargetType
  targetId: string
  targetName: string
  layoutMode: LayoutMode
  displayOrder: number
  isDefault: boolean
  createdAt: string
  updatedAt: string
}

export interface DataViewAssignmentResponse {
  id: string
  dashboardId: string
  dashboardTitle: string
  functionUnitId: number
  functionUnitCode: string
  functionUnitName: string
  tableId: number
  tableName: string
  tableDisplayName: string
  tableType: 'MAIN' | 'SUB'
  createdAt: string
  updatedAt: string
}

export interface DataViewFunctionUnitOption {
  id: number
  code: string
  name: string
}

export interface DataViewTableOption {
  id: number
  functionUnitId: number
  tableName: string
  tableDisplayName: string
  tableType: 'MAIN' | 'SUB'
}

export interface SyncResultResponse {
  created: number
  updated: number
  autoInactivated: number
  syncedAt: string
}

export interface RbacMappingResponse {
  sysRoleId: string
  sysRoleName: string
  sysRoleCode: string
  sysRoleType: string
  supersetRoles: SupersetRoleResponse[]
  lastUpdatedAt: string
}

export interface SupersetRoleResponse {
  id: number
  supersetRoleId: number
  name: string
  status: SupersetRoleStatus
  lastSyncedAt: string
}

export interface UserDashboardResponse {
  dashboardId: string
  dashboardTitle: string
  description: string
  embedId: string
  layoutMode: LayoutMode
  displayOrder: number
  isDefault: boolean
}

export interface GuestTokenResponse {
  token: string
  dashboardEmbedId: string
  supersetDomain?: string
}

// ==================== Request Types ====================

export interface DashboardRegistryUpdateRequest {
  tags?: string
  isDefaultLanding?: boolean
}

export interface DashboardStatusUpdateRequest {
  status: DashboardStatus
}

export interface DashboardAssignmentCreateRequest {
  dashboardId: string
  targetType: AssignmentTargetType
  targetId: string
  layoutMode?: LayoutMode
  displayOrder?: number
  isDefault?: boolean
}

export interface DataViewAssignmentRequest {
  dashboardId: string
  functionUnitId: number
  tableId: number
}

export interface DataViewAssignmentBatchRequest {
  dashboardIds: string[]
  functionUnitId: number
  tableId: number
}

export interface RbacMappingUpdateRequest {
  supersetRoleIds: number[]
}

export interface RbacMappingCreateRequest {
  sysRoleId: string
  supersetRoleIds: number[]
}

export interface RoleOptionResponse {
  id: string
  name: string
  code: string
  type: string
}

export interface GuestTokenRequest {
  dashboardId: string
  dataViewId?: number
}

// ==================== Paginated Response ====================

export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  size: number
  number: number
}

// ==================== Query Params ====================

export interface DashboardListParams {
  page?: number
  size?: number
  title?: string
  tags?: string
  status?: DashboardStatus
}

export interface DashboardListQuery {
  page: number
  size: number
  title?: string
  tags?: string
  status?: string
  filters?: Array<ListColumnFilter & { field: string }>
  sortField?: string
  sortDirection?: 'ASC' | 'DESC'
}

export interface AssignmentListParams {
  page?: number
  size?: number
  targetType?: AssignmentTargetType
  dashboardTitle?: string
}

export interface AssignmentListQuery {
  page: number
  size: number
  targetType?: string
  dashboardTitle?: string
  filters?: Array<ListColumnFilter & { field: string }>
  sortField?: string
  sortDirection?: 'ASC' | 'DESC'
}

export interface RbacMappingListParams {
  roleName?: string
  roleType?: string
}

export interface RbacMappingListQuery {
  page: number
  size: number
  roleName?: string
  roleType?: string
  filters?: Array<ListColumnFilter & { field: string }>
  sortField?: string
  sortDirection?: 'ASC' | 'DESC'
}

// ==================== API ====================

const DASHBOARD_BASE = '/bi/dashboards'
const ASSIGNMENT_BASE = '/bi/assignments'
const DATA_VIEW_ASSIGNMENT_BASE = '/bi/data-view-assignments'
const RBAC_BASE = '/bi/rbac'
const GUEST_TOKEN_BASE = '/bi/guest-token'

export const biManagementApi = {
  /** Dashboard Registry Management */
  dashboard: {
    /** Manually sync dashboards */
    sync: () =>
      post<SyncResultResponse>(`${DASHBOARD_BASE}/sync`),

    /** Paginated dashboard list */
    list: (params?: DashboardListParams) =>
      get<PageResponse<DashboardRegistryResponse>>(DASHBOARD_BASE, { params }),

    query: (body: DashboardListQuery) =>
      post<AdminListPage<DashboardRegistryResponse>>(`${DASHBOARD_BASE}/query`, body),

    /** Get dashboard details */
    getById: (id: string) =>
      get<DashboardRegistryResponse>(`${DASHBOARD_BASE}/${id}`),

    /** Update local extension fields (Tags, Is_Default_Landing) */
    update: (id: string, data: DashboardRegistryUpdateRequest) =>
      put<DashboardRegistryResponse>(`${DASHBOARD_BASE}/${id}`, data),

    /** Toggle dashboard status (enable/disable) */
    updateStatus: (id: string, data: DashboardStatusUpdateRequest) =>
      put<DashboardRegistryResponse>(`${DASHBOARD_BASE}/${id}/status`, data),

    /** Delete dashboard */
    delete: (id: string) =>
      del<void>(`${DASHBOARD_BASE}/${id}`),
  },

  /** Audience Assignment Management (legacy user/role/business-unit assignment). */
  assignment: {
    /** Create assignment record */
    create: (data: DashboardAssignmentCreateRequest) =>
      post<DashboardAssignmentResponse>(ASSIGNMENT_BASE, data),

    /** Paginated assignment list */
    list: (params?: AssignmentListParams) =>
      get<PageResponse<DashboardAssignmentResponse>>(ASSIGNMENT_BASE, { params }),

    query: (body: AssignmentListQuery) =>
      post<AdminListPage<DashboardAssignmentResponse>>(`${ASSIGNMENT_BASE}/query`, body),

    /** Update assignment record */
    update: (id: string, data: DashboardAssignmentCreateRequest) =>
      put<DashboardAssignmentResponse>(`${ASSIGNMENT_BASE}/${id}`, data),

    /** Delete assignment record */
    delete: (id: string) =>
      del<void>(`${ASSIGNMENT_BASE}/${id}`),

    /** Get user's effective dashboard list */
    getUserDashboards: (userId: string) =>
      get<UserDashboardResponse[]>(`${ASSIGNMENT_BASE}/user/${userId}`),
  },

  /** Dashboard-to-table bindings used by User Portal Data -> Views. */
  dataViewAssignment: {
    create: (data: DataViewAssignmentRequest) =>
      post<DataViewAssignmentResponse>(DATA_VIEW_ASSIGNMENT_BASE, data),

    createBatch: (data: DataViewAssignmentBatchRequest) =>
      post<DataViewAssignmentResponse[]>(`${DATA_VIEW_ASSIGNMENT_BASE}/batch`, data),

    list: (params?: {
      page?: number
      size?: number
      dashboardTitle?: string
      functionUnitId?: number
    }) => get<PageResponse<DataViewAssignmentResponse>>(DATA_VIEW_ASSIGNMENT_BASE, { params }),

    update: (id: string, data: DataViewAssignmentRequest) =>
      put<DataViewAssignmentResponse>(`${DATA_VIEW_ASSIGNMENT_BASE}/${id}`, data),

    delete: (id: string) =>
      del<void>(`${DATA_VIEW_ASSIGNMENT_BASE}/${id}`),

    listFunctionUnits: () =>
      get<DataViewFunctionUnitOption[]>(`${DATA_VIEW_ASSIGNMENT_BASE}/function-units`),

    listTables: (functionUnitId: number) =>
      get<DataViewTableOption[]>(
        `${DATA_VIEW_ASSIGNMENT_BASE}/function-units/${functionUnitId}/tables`,
      ),
  },

  /** RBAC Mapping Management */
  rbac: {
    /** Manually sync Superset roles */
    syncSupersetRoles: () =>
      post<SyncResultResponse>(`${RBAC_BASE}/superset-roles/sync`),

    /** Get all synced Superset role list */
    listSupersetRoles: () =>
      get<SupersetRoleResponse[]>(`${RBAC_BASE}/superset-roles`),

    /** Get RBAC mapping list */
    listMappings: (params?: RbacMappingListParams) =>
      get<RbacMappingResponse[]>(`${RBAC_BASE}/mappings`, { params }),

    queryMappings: (body: RbacMappingListQuery) =>
      post<AdminListPage<RbacMappingResponse>>(`${RBAC_BASE}/mappings/query`, body),

    /** Update Sys_Role to Superset_Role mapping (full replace) */
    updateMapping: (sysRoleId: string, data: RbacMappingUpdateRequest) =>
      put<void>(`${RBAC_BASE}/mappings/${sysRoleId}`, data),

    /** Create RBAC mapping for a system role */
    createMapping: (data: RbacMappingCreateRequest) =>
      post<void>(`${RBAC_BASE}/mappings`, data),

    /** Delete all RBAC mappings for a system role */
    deleteMapping: (sysRoleId: string) =>
      del<void>(`${RBAC_BASE}/mappings/${sysRoleId}`),

    /** Get unmapped active system roles (for create mapping dropdown) */
    listUnmappedRoles: () =>
      get<RoleOptionResponse[]>(`${RBAC_BASE}/unmapped-roles`),
  },

  /** Guest Token */
  guestToken: {
    /** Get Guest Token */
    getToken: (data: GuestTokenRequest) =>
      post<GuestTokenResponse>(GUEST_TOKEN_BASE, data),
  },
}
