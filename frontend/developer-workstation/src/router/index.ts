import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { hasAnyRole, isAuditorBlockedFromWorkstation } from '@/utils/permission'
import i18n from '@/i18n'
import { redirectToUnifiedLogin, setSsoReturnPath } from '@/utils/sso'
import { COMPUTED_FIELD_GUIDE_ROUTE_NAME } from '@/utils/computedFieldGuide'

declare module 'vue-router' {
  interface RouteMeta {
    titleKey?: string
    title?: string
    icon?: string
    hidden?: boolean
    requiresAuth?: boolean
    requiredRoles?: string[]
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/sso/callback',
    name: 'SsoCallback',
    component: () => import('@/views/SsoCallback.vue'),
    meta: { titleKey: 'login.title' }
  },
  {
    // AI Studio 工作台：全屏三栏布局，自带左侧阶段轨道，因此不挂在 MainLayout 下
    path: '/function-units/:id/ai-studio',
    name: 'AiStudioWorkspace',
    component: () => import('@/views/function-unit/AiStudioWorkspace.vue'),
    meta: { titleKey: 'ai.studio.entryButton', requiresAuth: true, requiredRoles: ['SYS_ADMIN', 'TECH_LEAD', 'TEAM_LEAD', 'DEVELOPER'] }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/function-units',
    meta: { requiresAuth: true },
    children: [
      {
        path: 'function-units',
        name: 'FunctionUnits',
        component: () => import('@/views/function-unit/FunctionUnitList.vue'),
        meta: { titleKey: 'functionUnit.title', requiredRoles: ['SYS_ADMIN', 'TECH_LEAD', 'TEAM_LEAD', 'DEVELOPER'] }
      },
      {
        path: 'function-units/:id',
        name: 'FunctionUnitEdit',
        component: () => import('@/views/function-unit/FunctionUnitEdit.vue'),
        meta: { titleKey: 'functionUnit.edit', requiredRoles: ['SYS_ADMIN', 'TECH_LEAD', 'TEAM_LEAD', 'DEVELOPER'] }
      },
      {
        path: 'automation',
        name: 'Automation',
        component: () => import('@/views/automation/AutomationPage.vue'),
        meta: { titleKey: 'automation.title', requiredRoles: ['SYS_ADMIN', 'TECH_LEAD', 'TEAM_LEAD', 'DEVELOPER'] }
      },
      {
        path: 'automation/:flowId',
        name: 'AutomationFlowEdit',
        component: () => import('@/views/automation/AutomationFlowEdit.vue'),
        meta: { titleKey: 'automation.title', hidden: true, requiredRoles: ['SYS_ADMIN', 'TECH_LEAD', 'TEAM_LEAD', 'DEVELOPER'] }
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/profile/index.vue'),
        meta: { titleKey: 'profile.title', hidden: true }
      },
      {
        path: 'help/computed-fields',
        name: COMPUTED_FIELD_GUIDE_ROUTE_NAME,
        component: () => import('@/views/help/ComputedFieldGuide.vue'),
        meta: {
          hidden: true
        }
      },
      {
        path: '403',
        name: 'Forbidden',
        component: () => import('@/views/error/403.vue'),
        meta: { titleKey: 'error.forbidden', hidden: true }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

// Cache the backend workspace-access decision for the session to avoid an extra request
// on every navigation. Only consulted for users without a DW capability role.
let workspaceAccessCache: boolean | null = null
async function resolveWorkspaceAccess(): Promise<boolean> {
  if (workspaceAccessCache !== null) {
    return workspaceAccessCache
  }
  try {
    const { functionUnitApi } = await import('@/api/functionUnit')
    const res = await functionUnitApi.getWorkspaceAccess()
    workspaceAccessCache = res?.data?.canView === true
  } catch {
    workspaceAccessCache = false
  }
  return workspaceAccessCache
}

// 路由守卫
router.beforeEach(async (to, _from, next) => {
  const t = i18n.global.t
  const pageTitle = (to.meta as any)?.titleKey ? t((to.meta as any).titleKey) : ((to.meta as any)?.title || t('app.name'))
  document.title = `${pageTitle} - ${t('app.title')}`

  if (to.path === '/login') {
    const r = to.query.redirect
    setSsoReturnPath(typeof r === 'string' ? r : '/')
    redirectToUnifiedLogin('developer-workstation', { autoSso: true })
    return next(false)
  }

  if (to.path === '/sso/callback') {
    // Auth checked via httpOnly cookie — getUser reads from localStorage user profile
    const { getUser } = await import('@/api/auth')
    const user = getUser()
    if (user) {
      next('/')
      return
    }
    next()
    return
  }
  
  // 检查需要认证的路由
  const requiresAuth = to.matched.some(record => record.meta.requiresAuth)
  if (requiresAuth) {
    // Auth checked via httpOnly cookie — if no valid cookie, API calls will 401 and redirect to login
    const { getUser } = await import('@/api/auth')
    const user = getUser()
    if (!user) {
      const { clearAuth } = await import('@/api/auth')
      clearAuth()
      setSsoReturnPath(to.fullPath)
      redirectToUnifiedLogin('developer-workstation', { autoSso: true })
      return next(false)
    }
  }

  // Pure Auditor: do not enter DW (including the workspace-access fallback).
  // Overlay users with a capability role still enter; keep AUDITOR out of requiredRoles
  // so this gate can be removed later without touching backend read-only logic.
  if (isAuditorBlockedFromWorkstation() && to.name !== 'Forbidden') {
    next('/403')
    return
  }

  const requiredRoles = to.meta.requiredRoles as string[] | undefined
  if (requiredRoles && requiredRoles.length > 0) {
    if (!hasAnyRole(requiredRoles)) {
      // Members of a team (virtual group) that owns function units have no DW capability
      // role but may still enter the workspace read-only. The backend is the source of
      // truth (it knows team→FU ownership); ask it before denying access.
      // FR-B23 (supersedes FR-B15): Automation now joins the fallback. FR-B15 kept it out
      // because flows were platform-wide — a team member would have seen every team's
      // flows. Flows are workspace-scoped now, and the bridge signs a Viewer session for
      // members without a capability role, so entering read-only shows their own team's
      // flows only and AP itself refuses every write.
      // AI Studio joins too (FU_VIEWER was removed); its copilot is read-only when the thread
      // state reports canModify=false.
      const workspaceFallbackApplies =
        to.name === 'FunctionUnits' || to.name === 'FunctionUnitEdit' || to.name === 'AiStudioWorkspace'
        || to.name === 'Automation' || to.name === 'AutomationFlowEdit'
      const canView = workspaceFallbackApplies && (await resolveWorkspaceAccess())
      if (!canView) {
        next('/403')
        return
      }
    }
  }
  
  next()
})

export default router
