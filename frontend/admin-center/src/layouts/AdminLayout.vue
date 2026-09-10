<template>
  <div class="admin-layout">
    <!-- Left dark sidebar: brand on top, nav, collapse toggle at bottom -->
    <aside
      class="admin-aside"
      :class="{ 'is-collapsed': isCollapse }"
    >
      <div class="brand">
        <img
          class="brand-mark"
          :src="brandMarkUrl"
          alt=""
        >
        <span class="brand-name">{{ t('app.name') }}</span>
      </div>

      <el-scrollbar class="aside-scroll">
        <el-menu
          :default-active="activeMenu"
          :collapse="isCollapse"
          :collapse-transition="false"
          class="admin-menu"
          router
        >
          <!-- Dashboard - everyone -->
          <el-menu-item index="/dashboard">
            <el-icon class="nav-anim nav-anim--wobble"><Odometer /></el-icon>
            <template #title>
              {{ t('menu.dashboard') }}
            </template>
          </el-menu-item>

          <!-- User Management - requires user:read -->
          <el-menu-item
            v-if="canReadUser"
            index="/user/list"
          >
            <el-icon class="nav-anim nav-anim--bounce"><User /></el-icon>
            <template #title>
              {{ t('menu.userManagement') }}
            </template>
          </el-menu-item>

          <!-- Entitlement Management - sub-menu -->
          <el-sub-menu
            v-if="canReadUser || canReadRole"
            index="entitlement"
          >
            <template #title>
              <el-icon class="nav-anim nav-anim--wobble"><Lock /></el-icon>
              <span>{{ t('menu.entitlementManagement') }}</span>
            </template>
            <el-menu-item
              v-if="canReadUser"
              index="/organization"
            >
              {{ t('menu.organization') }}
            </el-menu-item>
            <el-menu-item
              v-if="canReadUser"
              index="/virtual-group"
            >
              {{ t('menu.virtualGroup') }}
            </el-menu-item>
            <el-menu-item
              v-if="canReadRole"
              index="/role"
            >
              {{ t('menu.roleManagement') }}
            </el-menu-item>
          </el-sub-menu>

          <!-- Function Unit - requires system:admin -->
          <el-menu-item
            v-if="isSystemAdmin"
            index="/function-unit"
          >
            <el-icon class="nav-anim nav-anim--pop"><Box /></el-icon>
            <template #title>
              {{ t('menu.functionUnit') }}
            </template>
          </el-menu-item>

          <!-- BI Management - requires system:admin -->
          <el-sub-menu
            v-if="isSystemAdmin"
            index="bi-management"
          >
            <template #title>
              <el-icon class="nav-anim nav-anim--rise"><DataAnalysis /></el-icon>
              <span>{{ t('menu.biManagement') }}</span>
            </template>
            <el-menu-item index="/bi-management/dashboard-registry">
              {{ t('menu.biDashboardRegistry') }}
            </el-menu-item>
            <el-menu-item index="/bi-management/dashboard-assignment">
              {{ t('menu.biDashboardAssignment') }}
            </el-menu-item>
            <el-menu-item index="/bi-management/rbac-mapping">
              {{ t('menu.biRbacMapping') }}
            </el-menu-item>
          </el-sub-menu>

          <!-- Audit Log - requires audit:read or log:read -->
          <el-sub-menu
            v-if="canReadAudit"
            index="audit"
          >
            <template #title>
              <el-icon class="nav-anim nav-anim--wobble"><Document /></el-icon>
              <span>{{ t('menu.audit') }}</span>
            </template>
            <el-menu-item index="/audit/admin-center">
              {{ t('menu.auditAdminCenter') }}
            </el-menu-item>
            <el-menu-item index="/audit/user-portal">
              {{ t('menu.auditUserPortal') }}
            </el-menu-item>
          </el-sub-menu>

          <!-- Relation Tables - requires system:admin -->
          <el-sub-menu
            v-if="isSystemAdmin"
            index="relation-tables"
          >
            <template #title>
              <el-icon class="nav-anim nav-anim--pop"><Grid /></el-icon>
              <span>{{ t('menu.relationTables') }}</span>
            </template>
            <el-menu-item index="/relation-tables/structure">
              {{ t('menu.tableStructure') }}
            </el-menu-item>
            <el-sub-menu index="relation-tables-data">
              <template #title>
                <span>{{ t('menu.tableData') }}</span>
              </template>
              <el-menu-item index="/relation-tables/data">
                {{ t('menu.allFunctionUnits') }}
              </el-menu-item>
              <el-menu-item
                v-for="fu in tableDataFunctionUnits"
                :key="fu.functionUnitCode"
                :index="`/relation-tables/data/${fu.functionUnitCode}`"
              >
                {{ fu.functionUnitName || fu.functionUnitCode }}
              </el-menu-item>
            </el-sub-menu>
          </el-sub-menu>

          <!-- Automation Pieces - piece catalog + import/export/enable/delete, requires system:admin.
               Lives here (not in the Developer Workstation): DW is dev-only and is not part of the
               K8S deployment set, while piece rollout is a production-environment operation. -->
          <el-menu-item
            v-if="isSystemAdmin"
            index="/automation-pieces"
          >
            <el-icon class="nav-anim nav-anim--pop"><Cpu /></el-icon>
            <template #title>
              {{ t('menu.automationPieces') }}
            </template>
          </el-menu-item>

          <!-- Automation Flow Migration - flow migration page: export from uat, import into prod; requires system:admin -->
          <el-menu-item
            v-if="isSystemAdmin"
            index="/automation-flows"
          >
            <el-icon class="nav-anim nav-anim--wobble"><Share /></el-icon>
            <template #title>
              {{ t('menu.automationFlows') }}
            </template>
          </el-menu-item>

          <!-- Automation Runs - flow execution history, moved here from the Developer Workstation
               (dev-only) because triaging a failed automation is a production operation. Read-only. -->
          <el-menu-item
            v-if="isSystemAdmin"
            index="/automation-runs"
          >
            <el-icon class="nav-anim nav-anim--pop"><Tickets /></el-icon>
            <template #title>
              {{ t('menu.automationRuns') }}
            </template>
          </el-menu-item>
        </el-menu>
      </el-scrollbar>

      <div
        class="collapse-btn"
        role="button"
        @click="toggleCollapse"
      >
        <el-icon :size="18">
          <Fold v-if="!isCollapse" />
          <Expand v-else />
        </el-icon>
      </div>
    </aside>

    <!-- Right column: white top bar + canvas -->
    <div class="admin-body">
      <header class="admin-header">
        <nav class="breadcrumb">
          <router-link
            to="/dashboard"
            class="crumb-home"
          >
            {{ t('menu.home') }}
          </router-link>
          <template v-if="currentTitle && route.path !== '/dashboard'">
            <span class="crumb-sep">/</span>
            <span class="crumb-current">{{ currentTitle }}</span>
          </template>
        </nav>
        <div class="header-right">
          <UserProfileDropdown />
        </div>
      </header>

      <main class="admin-main">
        <router-view v-slot="{ Component, route }">
          <transition
            name="fade"
            mode="out-in"
          >
            <keep-alive>
              <component
                :is="Component"
                :key="route.path"
              />
            </keep-alive>
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  Fold, Expand,
  Odometer, Box, User, Lock, Document, DataAnalysis, Grid, Cpu, Share, Tickets
} from '@element-plus/icons-vue'
import UserProfileDropdown from '@/components/UserProfileDropdown.vue'
import { hasPermission, PERMISSIONS } from '@/utils/permission'
import { relationTableDataApi, type FunctionUnitTableGroup } from '@/api/relationTable'

const route = useRoute()
const { t } = useI18n()

const brandMarkUrl = `${import.meta.env.BASE_URL}hermes-mark.svg`

const isCollapse = ref(false)
const activeMenu = computed(() => route.path)

// Header breadcrumb: the current page title comes from the route's meta.titleKey
const currentTitle = computed(() => {
  const key = route.meta.titleKey
  return key ? t(key) : ''
})

// Permission checks
const isSystemAdmin = computed(() => hasPermission(PERMISSIONS.SYSTEM_ADMIN))
const canReadUser = computed(() => hasPermission(PERMISSIONS.USER_READ))
const canReadRole = computed(() => hasPermission(PERMISSIONS.ROLE_READ))
const canReadAudit = computed(() => hasPermission(PERMISSIONS.AUDIT_READ) || hasPermission(PERMISSIONS.LOG_READ))

const toggleCollapse = () => {
  isCollapse.value = !isCollapse.value
}

// Table Data nav sub-menu: one entry per Function Unit that has deployed relation tables.
const tableDataFunctionUnits = ref<FunctionUnitTableGroup[]>([])
const loadTableDataFunctionUnits = async () => {
  try {
    tableDataFunctionUnits.value = await relationTableDataApi.getFunctionUnitGroups()
  } catch {
    tableDataFunctionUnits.value = []
  }
}

onMounted(() => {
  if (isSystemAdmin.value) void loadTableDataFunctionUnits()
})
</script>

<style scoped lang="scss">
$header-height: 64px;
$aside-width: 280px; // 与 user-portal / developer-workstation 统一；280 起「Entitlement Management」文字才不压子菜单箭头
$aside-collapsed-width: 64px;
$primary-color: #db0011;
$primary-dark: #8b0000;

.admin-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background-color: var(--ws-canvas);
}

// ==================== White sidebar (back to the original color scheme) ====================
.admin-aside {
  display: flex;
  flex-direction: column;
  width: $aside-width;
  flex-shrink: 0;
  background: #ffffff;
  border-right: 1px solid #e6e8eb;
  transition: width 0.3s;
  overflow: hidden;

  &.is-collapsed {
    width: $aside-collapsed-width;

    .brand {
      justify-content: center;
      padding: 0;
    }

    .brand-name {
      display: none;
    }
  }
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  height: $header-height;
  padding: 0 20px;
  flex-shrink: 0;
  border-bottom: 1px solid #f0f0f0;

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
  }
}

.aside-scroll {
  flex: 1;
  min-height: 0;
}

.admin-menu {
  border-right: none;
  padding: 8px;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #4a4a4a;
  --el-menu-active-color: #{$primary-color};
  --el-menu-hover-bg-color: rgba(219, 0, 17, 0.06);
  --el-menu-hover-text-color: #{$primary-color};

  &:not(.el-menu--collapse) {
    width: 100%;
  }

  :deep(.el-menu-item),
  :deep(.el-sub-menu__title) {
    height: 44px;
    line-height: 44px;
    margin: 2px 0;
    border-radius: 10px;
  }

  :deep(.el-menu-item.is-active) {
    color: $primary-color;
    font-weight: 600;
    background-color: rgba(219, 0, 17, 0.1);

    &::before {
      content: '';
      position: absolute;
      left: -8px; // Flush with the sidebar's left edge (offsets the menu container padding)
      top: 50%;
      transform: translateY(-50%);
      width: 3px;
      height: 22px;
      background-color: $primary-color;
      border-radius: 0 3px 3px 0;
    }
  }

  :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
    color: $primary-color;
  }

  // Third-level items (e.g. Relation Tables > Table Data > <Function Unit>) default to a deeper
  // indent than their second-level siblings (Table Structure, Table Data) — force the same 40px
  // so nested Function Unit entries stay left-aligned with the rest of the second-level group.
  :deep(.el-sub-menu .el-sub-menu .el-menu-item) {
    padding-left: 40px !important;
  }

  :deep(.el-menu) {
    background: transparent;
  }

  // 收起态：菜单容器 8px padding 把条目压到 48px 宽，而 EP 仍按「64px 宽 + 20px 左 padding」
  // 摆图标（el-menu-item 的内容还包在绝对定位的 .el-menu-tooltip__trigger 里，自带同款 padding），
  // 图标中心整体右偏 8px —— 收起时一律 flex 居中。
  &.el-menu--collapse {
    :deep(.el-menu-item),
    :deep(.el-sub-menu__title),
    :deep(.el-menu-item .el-menu-tooltip__trigger) {
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 0 !important;
    }

    :deep(.el-icon) {
      margin-right: 0;
    }
  }

  // Nav icon micro-animations (Activepieces-style): hovering the menu ROW plays a
  // one-shot springy animation on the icon, each variant matching the icon's meaning.
  // Keyframes end at the identity transform so there is no snap on hover-out.
  :deep(.el-menu-item:hover .nav-anim--wobble svg),
  :deep(.el-sub-menu__title:hover .nav-anim--wobble svg) {
    animation: nav-icon-wobble 0.55s ease-in-out;
  }

  :deep(.el-menu-item:hover .nav-anim--pop svg),
  :deep(.el-sub-menu__title:hover .nav-anim--pop svg) {
    animation: nav-icon-pop 0.45s ease-out;
  }

  :deep(.el-menu-item:hover .nav-anim--rise svg),
  :deep(.el-sub-menu__title:hover .nav-anim--rise svg) {
    transform-origin: 50% 85%;
    animation: nav-icon-rise 0.5s ease-out;
  }

  :deep(.el-menu-item:hover .nav-anim--bounce svg),
  :deep(.el-sub-menu__title:hover .nav-anim--bounce svg) {
    animation: nav-icon-bounce 0.5s ease-out;
  }

  :deep(.el-menu-item:hover .nav-anim--nudge svg),
  :deep(.el-sub-menu__title:hover .nav-anim--nudge svg) {
    animation: nav-icon-nudge 0.5s ease-in-out;
  }

  @media (prefers-reduced-motion: reduce) {
    :deep(.el-menu-item:hover .nav-anim svg),
    :deep(.el-sub-menu__title:hover .nav-anim svg) {
      animation: none;
    }
  }
}

@keyframes nav-icon-wobble {
  0% { transform: rotate(0deg); }
  30% { transform: rotate(-14deg); }
  60% { transform: rotate(10deg); }
  80% { transform: rotate(-4deg); }
  100% { transform: rotate(0deg); }
}

@keyframes nav-icon-pop {
  0% { transform: scale(1); }
  35% { transform: scale(0.8); }
  70% { transform: scale(1.15); }
  100% { transform: scale(1); }
}

@keyframes nav-icon-rise {
  0% { transform: scaleY(1); }
  35% { transform: scaleY(0.7); }
  70% { transform: scaleY(1.12); }
  100% { transform: scaleY(1); }
}

@keyframes nav-icon-bounce {
  0% { transform: translateY(0); }
  35% { transform: translateY(-3px); }
  65% { transform: translateY(1px); }
  100% { transform: translateY(0); }
}

@keyframes nav-icon-nudge {
  0% { transform: translateX(0); }
  25% { transform: translateX(-2px); }
  55% { transform: translateX(2.5px); }
  80% { transform: translateX(-1px); }
  100% { transform: translateX(0); }
}

.collapse-btn {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 48px;
  flex-shrink: 0;
  cursor: pointer;
  color: var(--ws-text-secondary);
  border-top: 1px solid #e6e8eb;

  &:hover {
    color: $primary-color;
    background-color: rgba(219, 0, 17, 0.06);
  }
}

// ==================== Main body (right side) ====================
.admin-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.admin-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  height: $header-height;
  flex-shrink: 0;
  padding: 0 24px;
  background: linear-gradient(135deg, $primary-color 0%, $primary-dark 100%);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;

  .crumb-home {
    color: #fff;
    font-weight: 700;
    text-decoration: none;

    &:hover {
      opacity: 0.85;
    }
  }

  .crumb-sep {
    color: rgba(255, 255, 255, 0.65);
  }

  .crumb-current {
    color: #fff;
    font-weight: 700;
  }
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.admin-main {
  flex: 1;
  min-height: 0;
  background-color: var(--ws-canvas);
  padding: 24px;
  overflow-y: auto;
}

// Page transition animation
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
