<template>
  <div
    class="portal-layout"
    :class="{ 'nav-open': navOpen }"
  >
    <!-- 顶栏与 admin-center 同构：品牌块留在侧栏那一列的白底上，红条只压内容区 -->
    <div class="portal-top">
      <div
        class="brand"
        :class="{ 'is-collapsed': panelHidden }"
      >
        <img
          class="brand-mark"
          :src="brandMarkUrl"
          alt=""
        >
        <router-link
          to="/dashboard"
          class="brand-name"
        >
          {{ t('app.name') }}
        </router-link>
      </div>

      <header class="portal-header">
        <button
          type="button"
          class="nav-toggle"
          :aria-label="t('menu.toggleNav')"
          @click="navOpen = !navOpen"
        >
          <el-icon :size="18">
            <Fold />
          </el-icon>
        </button>
        <nav class="breadcrumb">
          <router-link
            :to="rootCrumbTo"
            class="crumb-home"
          >
            {{ rootCrumbLabel }}
          </router-link>
          <template v-if="currentTitle && route.path !== '/dashboard'">
            <span class="crumb-sep">/</span>
            <span class="crumb-current">{{ currentTitle }}</span>
          </template>
        </nav>
        <div class="header-right">
          <NotificationBadge />
          <WorkspaceContextBar />
          <UserProfileDropdown />
        </div>
      </header>
    </div>

    <div class="portal-main">
      <!-- L1 图标导轨：一直可见，点击直达该区第一项 -->
      <nav
        class="rail"
        :aria-label="t('menu.sections')"
      >
        <button
          v-for="section in sections"
          :key="section.key"
          type="button"
          class="rail-item"
          :class="{ 'is-active': section.key === activeSection?.key }"
          :title="railTitle(section)"
          :aria-current="section.key === activeSection?.key ? 'page' : undefined"
          :aria-expanded="isPanelToggle(section) ? !panelHidden : undefined"
          :aria-controls="isPanelToggle(section) ? 'portal-nav-panel' : undefined"
          @click="goToSection(section)"
        >
          <el-icon :size="20">
            <component :is="section.icon" />
          </el-icon>
          <span class="rail-label">{{ section.label }}</span>
          <span
            v-if="section.count"
            class="rail-dot"
          />
        </button>
      </nav>

      <!-- L2 上下文导航面板：标题 = 当前区，条目带各自的计数。
           只有一条的区（如 Home）不铺面板，直接进内容。 -->
      <div
        id="portal-nav-panel"
        class="nav-panel"
        :class="{ 'is-collapsed': panelHidden }"
      >
        <div class="nav-head">
          <h2 class="nav-title">
            {{ activeSection?.label }}
          </h2>
        </div>
        <el-scrollbar class="nav-scroll">
          <template
            v-for="(group, gi) in activeSection?.groups || []"
            :key="gi"
          >
            <p
              v-if="group.label"
              class="nav-group"
            >
              {{ group.label }}
            </p>
            <router-link
              v-for="item in group.items"
              :key="item.to"
              :to="item.to"
              class="nav-item"
              :class="{ 'is-active': item.to === activeItemPath }"
              :aria-current="item.to === activeItemPath ? 'page' : undefined"
            >
              <FunctionUnitMenuIcon
                v-if="item.iconSvg"
                :icon-svg="item.iconSvg"
                class="nav-item-icon"
              />
              <el-icon
                v-else
                class="nav-item-icon"
                :size="16"
              >
                <component :is="item.icon || Document" />
              </el-icon>
              <span class="nav-item-label">{{ item.label }}</span>
              <span
                v-if="item.count"
                class="nav-item-count"
              >{{ item.count > 99 ? '99+' : item.count }}</span>
            </router-link>
            <p
              v-if="group.items.length === 0 && group.emptyText"
              class="nav-empty"
            >
              {{ group.emptyText }}
            </p>
          </template>
        </el-scrollbar>
      </div>
      <div
        class="nav-scrim"
        @click="navOpen = false"
      />

      <div class="portal-body">
        <SelfServiceBanner
          :portal-access-mode="portalAccessMode"
          :workspace-context-count="workspaceContextCount"
        />

        <main class="portal-content">
          <router-view v-slot="{ Component }">
            <transition
              name="fade"
              mode="out-in"
            >
              <keep-alive :include="cachedViews">
                <component :is="Component" />
              </keep-alive>
            </transition>
          </router-view>
        </main>
      </div>
    </div>
    <FilePreviewDialog />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, type Component as VueComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  HomeFilled, List, Document, Share, Setting,
  Fold, DataAnalysis, Grid, Finished, Tickets
} from '@element-plus/icons-vue'
import SelfServiceBanner from '@/components/SelfServiceBanner.vue'
import WorkspaceContextBar from '@/components/WorkspaceContextBar.vue'
import {
  applyWorkspaceAwarePortalAccess,
  getCurrentUser,
  getStoredUser,
  listWorkspaceContexts,
  reconcilePortalWorkspaceSession,
  saveUser,
  USER_ID_KEY
} from '@/api/auth'
import UserProfileDropdown from '@/components/UserProfileDropdown.vue'
import NotificationBadge from '@/components/NotificationBadge.vue'
import { biDashboardApi } from '@/api/biDashboard'
import { usePendingApprovalStore } from '@/stores/pendingApproval'
import { usePendingTaskStore } from '@/stores/pendingTask'
import { mainTableViewApi, type FunctionUnitViewMenuItem } from '@/api/mainTableView'
import { processApi, type AuditFunctionUnit } from '@/api/process'
import { relationTableApi } from '@/api/relationTable'
import FunctionUnitMenuIcon from '@/components/mainTableView/FunctionUnitMenuIcon.vue'
import FilePreviewDialog from '@/components/FilePreviewDialog.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const pendingApprovalStore = usePendingApprovalStore()
const { count: pendingApprovalCount } = storeToRefs(pendingApprovalStore)
const pendingTaskStore = usePendingTaskStore()
const { count: pendingTaskCount } = storeToRefs(pendingTaskStore)

const brandMarkUrl = `${import.meta.env.BASE_URL}hermes-mark.svg`

/** 折叠只作用于 L2 面板；L1 导轨永远在，所以收起后仍然能换区。 */
const isCollapsed = ref(false)
/** 窄屏下 L2 面板改为浮层，由顶栏按钮开合。 */
const navOpen = ref(false)
/** 与 CSS 的 900px 断点保持同一个真源：导轨点击要知道自己开合的是面板还是浮层。 */
const NARROW_QUERY = '(max-width: 900px)'
const isNarrow = ref(false)
let narrowMedia: MediaQueryList | null = null
const syncNarrow = () => { isNarrow.value = narrowMedia?.matches ?? false }
const cachedViews = ref(['Dashboard', 'Tasks', 'MyApplications'])
const hasBiDashboards = ref(false)
const viewFunctionUnits = ref<FunctionUnitViewMenuItem[]>([])
const viewFuLoading = ref(false)
const auditFunctionUnits = ref<AuditFunctionUnit[]>([])
const relationTableFunctionUnits = ref<{ functionUnitCode: string; functionUnitName: string }[]>([])

interface NavItem {
  label: string
  to: string
  icon?: VueComponent
  iconSvg?: string | null
  /** 右对齐计数：把首页那一排大数字挪到各自的目的地旁边 */
  count?: number
}
interface NavGroup {
  label?: string
  items: NavItem[]
  emptyText?: string
}
interface NavSection {
  key: string
  label: string
  icon: VueComponent
  groups: NavGroup[]
  /** 导轨上的小红点：该区下有需要处理的东西 */
  count?: number
}

/** 导航模型。L1 每一项对应一个 L2 面板，与 Power Platform admin center 的分级一致。 */
const sections = computed<NavSection[]>(() => {
  const setup: NavSection = {
    key: 'setup',
    label: t('menu.sectionSetup'),
    icon: Setting,
    count: pendingApprovalCount.value,
    groups: [{
      items: [
        ...(showFullPortal.value
          ? [{ label: t('menu.delegations'), to: '/delegations', icon: Share }]
          : []),
        {
          label: t('menu.permissions'),
          to: '/permissions',
          icon: Share,
          count: pendingApprovalCount.value
        }
      ]
    }]
  }

  if (!showFullPortal.value) return [setup]

  return [
    {
      key: 'home',
      label: t('menu.dashboard'),
      icon: HomeFilled,
      groups: [{
        items: [
          { label: t('menu.overview'), to: '/dashboard', icon: HomeFilled },
          ...(hasBiDashboards.value
            ? [{ label: t('menu.biDashboard'), to: '/bi-dashboard', icon: DataAnalysis }]
            : [])
        ]
      }]
    },
    {
      key: 'task',
      label: t('menu.sectionTask'),
      icon: List,
      count: pendingTaskCount.value,
      groups: [{
        items: [
          { label: t('menu.tasks'), to: '/tasks', icon: List, count: pendingTaskCount.value },
          { label: t('menu.completedTasks'), to: '/tasks/completed', icon: Finished }
        ]
      }]
    },
    {
      key: 'request',
      label: t('menu.sectionRequest'),
      icon: Document,
      groups: [
        {
          items: [
            { label: t('menu.processes'), to: '/processes', icon: Document },
            { label: t('menu.myApplications'), to: '/my-applications', icon: Tickets }
          ]
        },
        ...(auditFunctionUnits.value.length > 0
          ? [{
              label: t('menu.sectionAudit'),
              items: auditFunctionUnits.value.map((fu) => ({
                label: fu.functionUnitName,
                to: `/audit/${fu.functionUnitCode}`,
                icon: Document
              }))
            }]
          : [])
      ]
    },
    {
      key: 'data',
      label: t('menu.sectionData'),
      icon: Grid,
      groups: [
        {
          label: t('menu.views'),
          items: viewFunctionUnits.value.map((fu) => ({
            label: fu.functionUnitName,
            to: `/views/${fu.functionUnitCode}`,
            iconSvg: fu.iconSvg,
            icon: Grid
          })),
          emptyText: viewFuLoading.value ? undefined : t('mainTableView.noPublishedFu')
        },
        {
          label: t('menu.relationTables'),
          items: [
            { label: t('menu.allFunctionUnits'), to: '/relation-tables', icon: Grid },
            ...relationTableFunctionUnits.value.map((fu) => ({
              label: fu.functionUnitName,
              to: `/relation-tables/${fu.functionUnitCode}`,
              icon: Grid
            }))
          ]
        }
      ]
    },
    setup
  ]
})

/**
 * 详情页把来源列表写在 `fromList` 里：`/tasks/123` 这种路径本身说不出
 * 「从 To Do 进来」还是「从 Completed Tasks 进来」，只按前缀匹配会一律点亮 To Do。
 */
const FROM_LIST_ITEM_PATH: Record<string, string> = {
  completed: '/tasks/completed',
}

/**
 * 当前条目 = 与 route.path 匹配得最深的那一条：精确命中优先，其次前缀命中。
 * 这样 /tasks/123 落在「To Do」上，而 /tasks/completed 仍然落在自己那条。
 * 带 `fromList` 的详情页优先认来源列表（该条目当前不可见时退回前缀匹配）。
 */
const activeItemPath = computed(() => {
  const fromList = route.query.fromList
  const fromPath = typeof fromList === 'string' ? FROM_LIST_ITEM_PATH[fromList] : undefined
  let exact = ''
  let best = ''
  for (const section of sections.value) {
    for (const group of section.groups) {
      for (const item of group.items) {
        if (fromPath && item.to === fromPath) return item.to
        if (route.path === item.to) exact = item.to
        if (route.path.startsWith(`${item.to}/`) && item.to.length > best.length) best = item.to
      }
    }
  }
  return exact || best
})

const activeSection = computed(() => {
  const path = activeItemPath.value
  return sections.value.find(
    (section) => section.groups.some((group) => group.items.some((item) => item.to === path))
  ) || sections.value[0]
})

/**
 * 只有一条目的区（Home 没配 BI 时、自助用户的 Setup）不值得占一整列面板：
 * 面板标题会和唯一一条几乎重复，还把内容推远 280px。这类区直接进内容。
 */
const isPanelWorthShowing = computed(
  () => (activeSection.value?.groups || []).reduce((n, group) => n + group.items.length, 0) > 1
)

/** 面板不铺的两种情形：结构上不值得铺，或用户自己收起了。 */
const panelHidden = computed(() => !isPanelWorthShowing.value || isCollapsed.value)

/** 当前区且有面板可收 —— 这时导轨图标兼任开合按钮。 */
const isPanelToggle = (section: NavSection) =>
  section.key === activeSection.value?.key && isPanelWorthShowing.value

/** 图标的悬浮提示：不在当前区就是区名，在当前区则说明再点一次会怎样。 */
const railTitle = (section: NavSection) => {
  if (!isPanelToggle(section)) return section.label
  return `${section.label} — ${panelHidden.value ? t('menu.expandNav') : t('menu.collapseNav')}`
}

/**
 * 点导轨：换区就去这一区的第一项；再点一次当前区则开合面板。
 * 窄屏的面板是浮层，开合的是 navOpen 而不是 isCollapsed。
 */
const goToSection = (section: NavSection) => {
  if (section.key === activeSection.value?.key) {
    if (!isPanelToggle(section)) return
    if (isNarrow.value) navOpen.value = !navOpen.value
    else isCollapsed.value = !isCollapsed.value
    return
  }
  // 换区就是「让我看看这一区」，收起态要跟着打开，否则这一下点击没有可见反馈
  isCollapsed.value = false
  const first = section.groups.flatMap((group) => group.items)[0]
  if (first && first.to !== route.path) void router.push(first.to)
}

/**
 * 根面包屑说的是「当前在哪一区」——和左侧导轨高亮的那一格一致（Task / Request / Data / Setup），
 * 于是顶栏读成「区 / 页」。首页本身就是应用根，没有上一级可指，所以用应用名。
 */
const rootCrumbLabel = computed(() =>
  route.path === '/dashboard' ? t('app.name') : activeSection.value?.label ?? t('app.name')
)

/** 标签指哪就跳哪：点「Task」进 Task 区第一项，而不是回首页。 */
const rootCrumbTo = computed(() => {
  if (route.path === '/dashboard') return '/dashboard'
  return activeSection.value?.groups.flatMap((group) => group.items)[0]?.to ?? '/dashboard'
})

// 顶栏当前页标题取路由 meta.titleKey（与 admin-center 同构）；
// ApplicationDetail 被 My Requests 和 All Requests(Audit) 共用同一路由，
// 靠 ?from=audit 区分入口，否则永远显示「My Requests」。
const currentTitle = computed(() => {
  // Views 主表行也复用这个路由，打开的同样是「别人的申请」这一视角，
  // 所以和 audit 入口一样显示 All Requests——不加区分会说成「My Requests」。
  if (route.name === 'ApplicationDetail'
      && (route.query.from === 'audit' || route.query.from === 'views')) {
    return t('menu.audit')
  }
  const key = route.meta.titleKey as string | undefined
  return key ? t(key) : ''
})

/** 与 localStorage 解耦：路由守卫 saveUser 后 computed(getStoredUser) 不会重算，需 ref + /me 显式同步 */
const portalAccessMode = ref<string | undefined>(getStoredUser()?.portalAccessMode)
/** null=未拉取；与 /workspace-contexts 一致，用于横幅：有 UBR 时不应仅因 portalAccessMode 滞后仍显示「无工作台」 */
const workspaceContextCount = ref<number | null>(null)
const showFullPortal = computed(
  () => portalAccessMode.value !== 'PERMISSION_SELF_SERVICE_ONLY'
)

// Check if user has BI dashboards assigned
const checkBiDashboards = async () => {
  try {
    const storedUser = getStoredUser()
    const userId = storedUser?.userId || localStorage.getItem(USER_ID_KEY)
    if (userId) {
      const dashboards = await biDashboardApi.getUserDashboards(
        userId,
        storedUser?.activeBusinessUnitId
      )
      hasBiDashboards.value = Array.isArray(dashboards) && dashboards.length > 0
    }
  } catch (e) {
    console.error('Failed to check BI dashboards:', e)
    hasBiDashboards.value = false
  }
}

async function syncPortalAccessFromServer() {
  await reconcilePortalWorkspaceSession()
  try {
    const contexts = await listWorkspaceContexts()
    workspaceContextCount.value = Array.isArray(contexts) ? contexts.length : 0
  } catch {
    workspaceContextCount.value = 0
  }
  void pendingApprovalStore.fetchPendingCount()
  // 待办列表、首页概览会各自用一次 query/overview 更新角标，避免 layout 再打满 queryTasks。
  if (route.path !== '/tasks' && route.path !== '/dashboard') {
    void pendingTaskStore.fetchPendingCount()
  }
  try {
    const u = await getCurrentUser()
    const hasCtx = (workspaceContextCount.value ?? 0) > 0
    const merged = applyWorkspaceAwarePortalAccess(u, hasCtx)
    saveUser(merged)
    localStorage.setItem(USER_ID_KEY, merged.userId)
    portalAccessMode.value = merged.portalAccessMode
  } catch {
    portalAccessMode.value = getStoredUser()?.portalAccessMode
  }
}

async function loadViewFunctionUnits() {
  viewFuLoading.value = true
  try {
    const res = await mainTableViewApi.listFunctionUnits()
    viewFunctionUnits.value = res.data || []
    if (route.path === '/views' && viewFunctionUnits.value.length) {
      await router.replace(`/views/${viewFunctionUnits.value[0].functionUnitCode}`)
    }
  } catch {
    viewFunctionUnits.value = []
  } finally {
    viewFuLoading.value = false
  }
}

async function loadAuditFunctionUnits() {
  try {
    const res = await processApi.getAuditFunctionUnits()
    auditFunctionUnits.value = res.data || []
  } catch {
    // A failure here silently removes the audit entry, which looks identical to
    // having no grant — say so instead of leaving reviewers guessing.
    auditFunctionUnits.value = []
    ElMessage.error(t('menu.auditLoadFailed'))
  }
}

// Built-in system tables (e.g. the read-only User table) have no Function Unit of their own;
// the sidebar files them under a fixed "Common" entry using this synthetic code — kept in sync
// with the same constant in relation-tables/index.vue (COMMON_FU_CODE) and its table-name set.
const COMMON_FU_CODE = '__common__'
const COMMON_TABLE_NAMES = new Set(['sys_users'])

/** Distinct Function Units among the user's visible Relation Tables, for the nav sub-menu. */
async function loadRelationTableFunctionUnits() {
  try {
    const res = await relationTableApi.getVisibleTables()
    const tables = res.data || []
    const byCode = new Map<string, string>()
    let hasCommon = false
    for (const table of tables) {
      if (COMMON_TABLE_NAMES.has(table.tableName)) {
        hasCommon = true
      } else if (table.functionUnitCode) {
        byCode.set(table.functionUnitCode, table.functionUnitName || table.functionUnitCode)
      }
    }
    const groups = [...byCode.entries()]
      .map(([functionUnitCode, functionUnitName]) => ({ functionUnitCode, functionUnitName }))
      .sort((a, b) => a.functionUnitName.localeCompare(b.functionUnitName))
    relationTableFunctionUnits.value = hasCommon
      ? [{ functionUnitCode: COMMON_FU_CODE, functionUnitName: t('menu.commonTables') }, ...groups]
      : groups
  } catch {
    relationTableFunctionUnits.value = []
  }
}

onMounted(() => {
  narrowMedia = window.matchMedia(NARROW_QUERY)
  syncNarrow()
  narrowMedia.addEventListener('change', syncNarrow)

  void (async () => {
    await syncPortalAccessFromServer()
    if (showFullPortal.value) {
      // Parallel: a third serial round-trip would delay first paint of the menu.
      await Promise.all([checkBiDashboards(), loadViewFunctionUnits(), loadAuditFunctionUnits(), loadRelationTableFunctionUnits()])
    }
  })()
})

onUnmounted(() => {
  narrowMedia?.removeEventListener('change', syncNarrow)
})

watch(
  () => route.path,
  () => {
    void pendingApprovalStore.fetchPendingCount()
    // 窄屏浮层导航跳转后自动收起，否则内容被盖住。
    navOpen.value = false
  }
)
</script>

<style lang="scss" scoped>
$rail-width: 68px;
$panel-width: 280px;

.portal-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  background: var(--ws-canvas);
}

// ==================== 顶栏：白色品牌块 + 品牌红标题条 ====================
.portal-top {
  display: flex;
  flex-shrink: 0;
  height: var(--header-height);
}

// 品牌块盖住导轨 + 面板这一整列，红条从内容区才开始（与 admin-center 同构）
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  width: $rail-width + $panel-width;
  flex-shrink: 0;
  padding: 0 20px;
  background: var(--ws-card-bg);
  border-right: 1px solid var(--ws-card-border);
  border-bottom: 1px solid var(--ws-card-border);
  overflow: hidden;
  transition: width 0.2s ease;

  &.is-collapsed {
    width: $rail-width;
    justify-content: center;
    padding: 0;

    .brand-name { display: none; }
  }
}

.brand-mark {
  display: block;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
}

.brand-name {
  color: var(--ws-text);
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.5px;
  white-space: nowrap;
  text-decoration: none;

  &:hover { color: var(--ws-text); }
}

.portal-header {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 24px;
  background: linear-gradient(135deg, var(--hsbc-red) 0%, #8B0000 100%);
  color: #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.nav-toggle {
  display: none; // 只在窄屏出现
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  padding: 0;
  border: none;
  border-radius: 2px;
  background: transparent;
  color: #fff;
  cursor: pointer;

  &:hover { background: rgba(255, 255, 255, 0.16); }
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-size: 15px;

  .crumb-home {
    color: #fff;
    font-weight: 700;
    text-decoration: none;
    white-space: nowrap;

    &:hover { opacity: 0.85; }
  }

  .crumb-sep { color: rgba(255, 255, 255, 0.65); }

  .crumb-current {
    color: #fff;
    font-weight: 700;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-left: auto;
  min-width: 0;
}

// 顶栏挤不下时，先截工作台上下文，用户菜单（含登出）永远完整
:deep(.workspace-context-bar) {
  min-width: 0;
  overflow: hidden;

  .ctx-text {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

:deep(.user-info) {
  flex-shrink: 0;

  .user-name { white-space: nowrap; }
}


// ==================== 主体三段 ====================
.portal-main {
  flex: 1;
  min-height: 0;
  display: flex;
  position: relative;
}

// ---- L1 图标导轨 ----
.rail {
  display: flex;
  flex-direction: column;
  width: $rail-width;
  flex-shrink: 0;
  padding: 6px 0;
  background: #fff;
  border-right: 1px solid var(--ws-card-border);
  overflow-y: auto;
  overflow-x: hidden;
}

.rail-item {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  width: 100%;
  padding: 9px 4px;
  border: none;
  background: transparent;
  color: var(--ws-text-secondary);
  cursor: pointer;

  &:hover {
    background: var(--background-light);
    color: var(--ws-text);
  }

  &:focus-visible {
    outline: 2px solid var(--hsbc-red);
    outline-offset: -2px;
  }

  &.is-active {
    color: var(--hsbc-red);

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 6px;
      bottom: 6px;
      width: 3px;
      background: var(--hsbc-red);
    }
  }
}

.rail-label {
  max-width: 100%;
  font-size: 10.5px;
  line-height: 1.3;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

// 导轨小红点：这一区里有东西等着你，不用展开面板也知道
.rail-dot {
  position: absolute;
  top: 8px;
  right: 16px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--hsbc-red);
}


// ---- L2 上下文导航面板 ----
.nav-panel {
  display: flex;
  flex-direction: column;
  width: $panel-width;
  flex-shrink: 0;
  background: #fff;
  border-right: 1px solid var(--ws-card-border);
  overflow: hidden;
  transition: width 0.2s ease;

  &.is-collapsed {
    width: 0;
    border-right: none;
    visibility: hidden; // 宽度 0 的链接仍能被 Tab 聚焦，必须一起藏掉
  }
}

.nav-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 18px 20px 12px;
  flex-shrink: 0;
}

.nav-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ws-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}


.nav-scroll {
  flex: 1;
  min-height: 0;
  padding-bottom: 16px;
}

// 分组标题：句首大写、灰色，上方一条发丝线 —— 与参考图的 Data / Products 同构
.nav-group {
  margin: 0;
  padding: 16px 20px 6px;
  border-top: 1px solid var(--ws-line);
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ws-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  height: 36px;
  font-size: 13.5px;
  color: var(--ws-text);
  text-decoration: none;

  &:hover { background: var(--background-light); }

  &:focus-visible {
    outline: 2px solid var(--hsbc-red);
    outline-offset: -2px;
  }

  // 当前位置：只靠颜色 + 字重，不加底色块（参考图即如此）
  &.is-active {
    color: var(--hsbc-red);
    font-weight: 600;
  }
}

.nav-item-icon {
  flex-shrink: 0;
  color: inherit;
}

.nav-item-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

// 计数贴在目的地旁边：首页那排大数字的去处
.nav-item-count {
  flex-shrink: 0;
  min-width: 20px;
  padding: 0 6px;
  border-radius: 9px;
  background: var(--hsbc-red);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  text-align: center;
}

.nav-empty {
  margin: 0;
  padding: 6px 20px 10px;
  font-size: 12.5px;
  color: var(--ws-text-muted);
}

.nav-scrim { display: none; }

// ---- 内容区 ----
.portal-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.portal-content {
  flex: 1;
  min-height: 0;
  min-width: 0;
  background: var(--background-light);
  padding: 20px;
  overflow-y: auto;
}

// ==================== 窄屏：面板改浮层 ====================
@media (max-width: 900px) {
  .nav-toggle { display: flex; }

  // 品牌块收到只剩导轨宽，红条随之向左铺满其余空间
  .brand {
    width: $rail-width;
    justify-content: center;
    padding: 0;
  }

  // 导轨压在遮罩之上：抽屉开着时也能直接换区（换区会导航，抽屉随路由自动收起）
  .rail {
    position: relative;
    z-index: 30;
  }

  .nav-panel {
    position: absolute;
    top: 0;
    left: $rail-width;
    bottom: 0;
    z-index: 20;
    width: $panel-width;
    max-width: calc(100vw - #{$rail-width});
    // 位移要算上自身 left 偏移，否则右缘还留在导轨上，把图标标签盖掉一截
    transform: translateX(calc(-100% - #{$rail-width}));
    visibility: hidden;
    transition: transform 0.2s ease, visibility 0.2s;
    box-shadow: 2px 0 12px rgba(0, 0, 0, 0.12);

    &.is-collapsed { width: $panel-width; border-right: 1px solid var(--ws-card-border); }
  }

  .nav-open .nav-panel {
    transform: translateX(0);
    visibility: visible;
  }

  .nav-open .nav-scrim {
    display: block;
    position: absolute;
    inset: 0;
    z-index: 10;
    background: rgba(0, 0, 0, 0.28);
  }

  .portal-content { padding: 16px; }

  .brand-name { display: none; }
}

// 手机上顶栏只剩功能项：BU·Role 文本让位，但切换按钮要完整可点
@media (max-width: 600px) {
  // 面包屑让位：导轨高亮 + 页面 H1 已经说清在哪一页了
  .breadcrumb { display: none; }

  :deep(.workspace-context-bar) {
    .ctx-text { display: none; }
    .ctx-switch { flex-shrink: 0; }
  }

  // 头像本身就是下拉入口，名字在手机上纯属占宽度
  :deep(.user-info) .user-name { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  .nav-panel { transition: none; }
}
</style>
