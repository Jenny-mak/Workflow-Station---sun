<template>
  <div class="dashboard-page">
    <!-- 标题带：刷新属于页面级工具，放在更新时间旁而不是占据主操作位 -->
    <div class="page-heading">
      <div class="page-heading-copy">
        <h1 class="page-title">
          {{ greetingText }}
        </h1>
        <p class="page-intro">
          {{ introLine }}
        </p>
      </div>
      <div class="page-heading-tools">
        <button
          type="button"
          class="command"
          :disabled="loading"
          @click="refresh()"
        >
          <el-icon
            :size="16"
            :class="{ 'is-spinning': loading }"
          >
            <Refresh />
          </el-icon>
          <span>{{ t('dashboard.refresh') }}</span>
        </button>
        <span
          v-if="loadedAt"
          class="page-updated"
        >{{ t('dashboard.updatedAt', { time: timeOfDay(loadedAt) }) }}</span>
      </div>
    </div>

    <!-- 加载失败要出声，而不是退化成一屏 0 -->
    <div
      v-if="anyLoadFailed"
      class="load-error"
      role="alert"
    >
      <el-icon :size="16">
        <WarningFilled />
      </el-icon>
      <span class="load-error-text">{{ t('dashboard.loadFailed') }}</span>
      <button
        type="button"
        class="load-error-retry"
        @click="refresh()"
      >
        {{ t('dashboard.retry') }}
      </button>
    </div>

    <!-- ============ 任务与快捷操作：左右双卡 ============ -->
    <section class="home-shortcuts">
      <div class="task-overview-card">
        <header class="block-head">
          <h2 class="block-title">
            {{ t('dashboard.tasks') }}
          </h2>
          <router-link
            :to="DASHBOARD_ROUTES.todo"
            class="block-link"
          >
            {{ t('dashboard.viewAll') }} →
          </router-link>
        </header>

        <div class="task-figures">
          <router-link
            :to="DASHBOARD_ROUTES.todo"
            class="task-figure is-todo"
          >
            <span class="task-figure-label">{{ t('dashboard.todoTasks') }}</span>
            <span class="task-figure-value">{{ overviewLoading ? '–' : taskOverview.pendingCount }}</span>
          </router-link>
          <router-link
            :to="DASHBOARD_ROUTES.completedTasks"
            class="task-figure is-completed"
          >
            <span class="task-figure-label">{{ t('dashboard.completedToday') }}</span>
            <span class="task-figure-value">{{ overviewLoading ? '–' : taskOverview.completedTodayCount }}</span>
          </router-link>
        </div>
      </div>

      <div class="quick-actions-card">
        <header class="block-head">
          <h2 class="block-title">
            {{ t('dashboard.quickActions') }}
          </h2>
        </header>

        <nav
          class="quick-actions"
          :aria-label="t('dashboard.quickActions')"
        >
          <router-link
            :to="DASHBOARD_ROUTES.newRequest"
            class="quick-action is-new-request"
          >
            <el-icon :size="25">
              <Plus />
            </el-icon>
            <span>{{ t('dashboard.startRequest') }}</span>
          </router-link>
          <router-link
            :to="DASHBOARD_ROUTES.delegations"
            class="quick-action is-delegations"
          >
            <el-icon :size="25">
              <Share />
            </el-icon>
            <span>{{ t('menu.delegations') }}</span>
          </router-link>
          <router-link
            :to="DASHBOARD_ROUTES.profileSetup"
            class="quick-action is-profile-setup"
          >
            <el-icon :size="25">
              <User />
            </el-icon>
            <span>{{ t('menu.permissions') }}</span>
          </router-link>
        </nav>
      </div>
    </section>

    <!-- ============ 主视觉：个人 / 团队两本账 ============ -->
    <section class="ledger">
      <div class="ledger-half">
        <header class="ledger-head">
          <h2 class="ledger-title">
            {{ t('dashboard.myRequests') }}
          </h2>
          <router-link
            :to="DASHBOARD_ROUTES.myRequests"
            class="ledger-link"
          >
            {{ t('dashboard.viewAll') }} →
          </router-link>
        </header>

        <div class="figures">
          <router-link
            v-for="figure in myRequestFigures"
            :key="figure.key"
            :to="figure.to"
            class="figure figure-link"
          >
            <span class="figure-num">{{ loading ? '–' : figure.value }}</span>
            <span class="figure-label">{{ figure.label }}</span>
          </router-link>
        </div>

        <div class="ledger-foot">
          <span class="foot-label">{{ t('dashboard.approvalRate') }}</span>
          <span class="meter">
            <span
              class="meter-fill"
              :style="{ width: loading ? '0%' : `${approvalPercent}%` }"
            />
          </span>
          <span class="foot-value">{{ loading ? '–' : `${approvalPercent}%` }}</span>
        </div>
      </div>

      <div class="ledger-half">
        <header class="ledger-head">
          <h2 class="ledger-title">
            {{ t('dashboard.myTeam') }}
          </h2>
          <button
            type="button"
            class="ledger-link"
            @click="openTeamRequestsDialog"
          >
            {{ t('dashboard.viewAll') }} →
          </button>
        </header>

        <div class="figures">
          <div class="figure">
            <span class="figure-num">{{ loading ? '–' : teamSummary.overallCount }}</span>
            <span class="figure-label">{{ t('dashboard.overallRequests') }}</span>
          </div>
          <div class="figure">
            <span class="figure-num">{{ loading ? '–' : teamSummary.runningCount }}</span>
            <span class="figure-label">{{ t('dashboard.runningRequests') }}</span>
          </div>
          <div class="figure">
            <span class="figure-num">{{ loading ? '–' : teamSummary.completedCount }}</span>
            <span class="figure-label">{{ t('dashboard.completedRequests') }}</span>
          </div>
          <div class="figure">
            <span class="figure-num">{{ loading ? '–' : teamSummary.withdrawnCount }}</span>
            <span class="figure-label">{{ t('dashboard.withdrawnRequests') }}</span>
          </div>
        </div>

        <!-- 构成条：三段按真实占比，读作「在途 / 已结 / 已撤」 -->
        <div
          v-if="composition.length > 0"
          class="ledger-foot is-stacked"
        >
          <span
            class="composition"
            role="img"
            :aria-label="compositionLabel"
          >
            <span
              v-for="seg in composition"
              :key="seg.key"
              class="composition-seg"
              :class="`is-${seg.key}`"
              :style="{ width: `${seg.percent}%` }"
            />
          </span>
          <p class="composition-legend">
            <span
              v-for="seg in composition"
              :key="seg.key"
              class="legend-item"
            >
              <span
                class="legend-dot"
                :class="`is-${seg.key}`"
              />{{ seg.label }}
            </span>
          </p>
        </div>
      </div>
    </section>

    <!-- ============ 我最近的任务 ============ -->
    <section class="block">
      <header class="block-head">
        <h2 class="block-title">
          {{ t('dashboard.recentTasks') }}
        </h2>
        <router-link
          :to="DASHBOARD_ROUTES.todo"
          class="block-link"
        >
          {{ t('dashboard.viewAll') }} →
        </router-link>
      </header>

      <el-skeleton
        v-if="overviewLoading && recentTasks.length === 0"
        :rows="4"
        animated
      />

      <div
        v-else-if="recentTasks.length > 0"
        class="table-scroll"
      >
        <table class="data-table task-table">
          <thead>
            <tr>
              <th scope="col">
                {{ t('dashboard.colRequest') }}
              </th>
              <th scope="col">
                {{ t('task.taskName') }}
              </th>
              <th scope="col">
                {{ t('task.assignmentType') }}
              </th>
              <th scope="col">
                {{ t('task.functionUnit') }}
              </th>
              <th scope="col">
                {{ t('task.createTime') }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="task in recentTasks"
              :key="taskKey(task)"
              class="data-row"
              tabindex="0"
              role="link"
              @click="openTask(task)"
              @keyup.enter="openTask(task)"
            >
              <td class="cell-name">
                {{ taskRequestLabel(task) }}
              </td>
              <td>
                <span class="task-name">{{ task.taskName || task.name || '—' }}</span>
              </td>
              <td class="cell-muted">
                {{ t(`task.${assignmentDisplayKey(task)}`) }}
              </td>
              <td class="cell-muted">
                {{ task.functionUnitName || task.functionUnitCode || '—' }}
              </td>
              <td class="cell-muted">
                {{ formatDate(task.createTime || task.createdAt) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div
        v-else
        class="block-empty"
      >
        <p class="empty-text">
          {{ t('dashboard.noTasks') }}
        </p>
        <router-link
          :to="DASHBOARD_ROUTES.todo"
          class="empty-action"
        >
          {{ t('dashboard.openTodoTasks') }}
        </router-link>
      </div>
    </section>

    <!-- ============ 我的申请 ============ -->
    <section class="block">
      <header class="block-head">
        <h2 class="block-title">
          {{ t('dashboard.recentRequests') }}
        </h2>
        <router-link
          :to="DASHBOARD_ROUTES.myRequests"
          class="block-link"
        >
          {{ t('dashboard.viewAll') }} →
        </router-link>
      </header>

      <el-skeleton
        v-if="loading && myRequests.length === 0"
        :rows="4"
        animated
      />

      <div
        v-else-if="myRequests.length > 0"
        class="table-scroll"
      >
        <table
          class="data-table"
        >
          <thead>
            <tr>
              <th scope="col">
                {{ t('dashboard.colRequest') }}
              </th>
              <th scope="col">
                {{ t('application.currentStep') }}
              </th>
              <th scope="col">
                {{ t('application.currentAssignee') }}
              </th>
              <th scope="col">
                {{ t('application.status') }}
              </th>
              <th scope="col">
                {{ t('application.startTime') }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in myRequests"
              :key="row.id"
              class="data-row"
              tabindex="0"
              role="link"
              @click="openRequest(row.id)"
              @keyup.enter="openRequest(row.id)"
            >
              <td class="cell-name">
                {{ requestLabel(row) }}
              </td>
              <td class="cell-muted">
                {{ row.currentStepName || row.currentNode || '—' }}
              </td>
              <td class="cell-muted">
                {{ row.currentAssignee || '—' }}
              </td>
              <td>
                <span
                  class="status"
                  :class="statusClass(row.status)"
                >
                  <span class="status-dot" />
                  {{ getTeamStatusLabel(row.status) }}
                </span>
              </td>
              <td class="cell-muted">
                {{ formatDate(row.startTime) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div
        v-else
        class="block-empty"
      >
        <p class="empty-text">
          {{ t('dashboard.noRequests') }}
        </p>
        <router-link
          to="/processes"
          class="empty-action"
        >
          {{ t('dashboard.startRequest') }}
        </router-link>
      </div>
    </section>

    <!-- Team Requests Dialog -->
    <el-dialog
      v-model="teamDialogVisible"
      :title="t('dashboard.teamRequestsTitle')"
      width="900px"
      destroy-on-close
    >
      <div class="team-summary">
        <div
          class="team-summary-item"
          @click="switchTeamTab('all')"
        >
          <div class="team-summary-value">
            {{ teamRequests.overallCount }}
          </div>
          <div class="team-summary-label">
            {{ t('dashboard.overallRequests') }}
          </div>
        </div>
        <div
          class="team-summary-item running"
          @click="switchTeamTab('RUNNING')"
        >
          <div class="team-summary-value">
            {{ teamRequests.runningCount }}
          </div>
          <div class="team-summary-label">
            {{ t('dashboard.runningRequests') }}
          </div>
        </div>
        <div
          class="team-summary-item completed"
          @click="switchTeamTab('COMPLETED')"
        >
          <div class="team-summary-value">
            {{ teamRequests.completedCount }}
          </div>
          <div class="team-summary-label">
            {{ t('dashboard.completedRequests') }}
          </div>
        </div>
        <div
          class="team-summary-item withdrawn"
          @click="switchTeamTab('WITHDRAWN')"
        >
          <div class="team-summary-value">
            {{ teamRequests.withdrawnCount }}
          </div>
          <div class="team-summary-label">
            {{ t('dashboard.withdrawnRequests') }}
          </div>
        </div>
      </div>

      <el-tabs
        v-model="teamActiveTab"
        @tab-change="handleTeamTabChange"
      >
        <el-tab-pane
          :label="t('dashboard.overallRequests')"
          name="all"
        />
        <el-tab-pane
          :label="t('dashboard.runningRequests')"
          name="RUNNING"
        />
        <el-tab-pane
          :label="t('dashboard.completedRequests')"
          name="COMPLETED"
        />
        <el-tab-pane
          :label="t('dashboard.withdrawnRequests')"
          name="WITHDRAWN"
        />
      </el-tabs>

      <el-table
        :data="teamRequests.content"
        :loading="teamLoading"
        stripe
        table-layout="fixed"
      >
        <el-table-column
          prop="businessKey"
          :label="t('application.processTitle')"
          min-width="180"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ teamRequestLabel(row) }}
          </template>
        </el-table-column>
        <el-table-column
          prop="startUserName"
          :label="t('dashboard.initiator')"
          min-width="100"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.startUserName || '-' }}
          </template>
        </el-table-column>
        <el-table-column
          prop="currentNode"
          :label="t('application.currentStep')"
          min-width="120"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.currentNode || '-' }}
          </template>
        </el-table-column>
        <el-table-column
          prop="currentAssignee"
          :label="t('application.currentAssignee')"
          min-width="100"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.currentAssignee || '-' }}
          </template>
        </el-table-column>
        <el-table-column
          prop="startTime"
          :label="t('application.startTime')"
          width="160"
        >
          <template #default="{ row }">
            {{ formatDate(row.startTime) }}
          </template>
        </el-table-column>
        <el-table-column
          prop="status"
          :label="t('application.status')"
          width="90"
          align="center"
        >
          <template #default="{ row }">
            <el-tag
              :type="getTeamStatusType(row.status)"
              size="small"
              effect="light"
            >
              {{ getTeamStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="teamPagination.page"
        v-model:page-size="teamPagination.size"
        :disabled="teamLoading"
        :total="teamRequests.totalElements"
        layout="total, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end;"
        @current-change="handleTeamPageChange"
      />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Plus, Refresh, Share, User, WarningFilled } from '@element-plus/icons-vue'
import { formatDate } from '@/utils/dateFormat'
import { getStoredUser } from '@/api/auth'
import type { RecentTask, TeamRequestItem } from '@/api/dashboard'
import type { ProcessInstance } from '@/api/process'
import { useDashboardOverview } from '@/composables/dashboard/useDashboardOverview'
import { useRequestsBoard } from '@/composables/dashboard/useRequestsBoard'
import { useTeamRequests } from '@/composables/dashboard/useTeamRequests'
import { assignmentDisplayKey } from '@/utils/taskAssignmentDisplay'
import {
  DASHBOARD_ROUTES,
  dashboardMyRequestsRoute,
  dashboardRequestDetailRoute,
  dashboardTaskDetailRoute
} from './dashboardNavigation'

const { t, locale } = useI18n()
const router = useRouter()

// 个人流程概览（发起 / 在途 / 本月完成 / 通过率）与待办计数（用于问候语下那句话）
const {
  loading: overviewLoading,
  loadFailed: overviewFailed,
  loadedAt,
  taskOverview,
  processOverview,
  recentTasks,
  loadDashboardData
} = useDashboardOverview()

// 首页两张申请预览表 + 团队汇总
const {
  loading: boardLoading,
  loadFailed: boardFailed,
  myRequests,
  teamSummary,
  loadRequestsBoard
} = useRequestsBoard()

// 团队请求弹窗（「View all」入口，自带分页与状态页签）
const {
  teamDialogVisible,
  teamLoading,
  teamActiveTab,
  teamPagination,
  teamRequests,
  openTeamRequestsDialog,
  switchTeamTab,
  handleTeamTabChange,
  handleTeamPageChange,
  getTeamStatusType,
  getTeamStatusLabel
} = useTeamRequests()

const loading = computed(() => overviewLoading.value || boardLoading.value)
const anyLoadFailed = computed(() => overviewFailed.value || boardFailed.value)

const refresh = () => {
  void loadDashboardData()
  void loadRequestsBoard()
}

// 报头问候：按当前时段选键，带用户显示名
const greetingText = computed(() => {
  const hour = new Date().getHours()
  const key = hour < 12 ? 'dashboard.greetingMorning'
    : hour < 18 ? 'dashboard.greetingAfternoon'
      : 'dashboard.greetingEvening'
  const user = getStoredUser()
  return t(key, { name: user?.displayName || user?.username || '' })
})

// 日期行随语言环境格式化（zh: 7月24日 星期五 / en: Friday, July 24）
const dateLine = computed(() => new Intl.DateTimeFormat(locale.value, {
  month: 'long',
  day: 'numeric',
  weekday: 'long'
}).format(new Date()))

const timeOfDay = (d: Date) => new Intl.DateTimeFormat(locale.value, {
  hour: '2-digit',
  minute: '2-digit'
}).format(d)

/**
 * H1 下面那句话：只报待办条数。逾期作为产品概念已淡出（To Do 列表的 Due Date 列、
 * 任务详情的逾期标签都已移除），这里再单独喊一句就会成为它最后一个突兀的出口。
 * 整句交给 i18n 拼（日期是参数），中英文的标点才各自对。
 */
const introLine = computed(() => {
  const date = dateLine.value
  if (overviewLoading.value || overviewFailed.value) return date
  const count = taskOverview.value.pendingCount
  // 第三个参数是复数计数：英文按 count 选单/复数形式，中文只有一种形式不受影响
  if (count > 0) return t('dashboard.introPending', { date, count }, count)
  return t('dashboard.introClear', { date })
})

const approvalPercent = computed(() => Math.round(processOverview.value.approvalRate * 100))

/**
 * The three MY REQUESTS figures are entry points into My Applications, each landing
 * on the tab that lists exactly what the number counts. `initiated` has no status
 * filter (it counts every request the user started); "completed this month" lands on
 * the Completed tab — the list has no month window, so it shows every completed one.
 */
const myRequestFigures = computed(() => [
  {
    key: 'initiated',
    value: processOverview.value.initiatedCount,
    label: t('dashboard.initiatedProcesses'),
    to: dashboardMyRequestsRoute()
  },
  {
    key: 'inProgress',
    value: processOverview.value.inProgressCount,
    label: t('dashboard.inProgressProcesses'),
    to: dashboardMyRequestsRoute('RUNNING')
  },
  {
    key: 'completedThisMonth',
    value: processOverview.value.completedThisMonthCount,
    label: t('dashboard.completedThisMonth'),
    to: dashboardMyRequestsRoute('COMPLETED')
  },
  {
    key: 'draft',
    value: processOverview.value.draftCount,
    label: t('dashboard.myDrafts'),
    to: dashboardMyRequestsRoute('DRAFT')
  }
])

/** 团队构成条：只画有量的那几段，占比按总数算；总数为 0 时整条不画。 */
const composition = computed(() => {
  const { overallCount, runningCount, completedCount, withdrawnCount } = teamSummary.value
  if (!overallCount) return []
  return [
    { key: 'running', count: runningCount, name: t('dashboard.runningRequests') },
    { key: 'completed', count: completedCount, name: t('dashboard.completedRequests') },
    { key: 'withdrawn', count: withdrawnCount, name: t('dashboard.withdrawnRequests') }
  ]
    .filter((part) => part.count > 0)
    .map((part) => ({
      key: part.key,
      label: `${part.count} ${part.name}`,
      percent: Math.round((part.count / overallCount) * 100)
    }))
})

const compositionLabel = computed(() => composition.value.map((seg) => seg.label).join(', '))

const statusClass = (status?: string) => `is-${(status || 'unknown').toLowerCase()}`

/** 申请的可读标识：优先主表配置的 Request ID，其次业务键，最后流程名。 */
const requestLabel = (row: ProcessInstance) =>
  row.requestId || row.businessKey || row.processDefinitionName

/** Team Requests 弹窗同理；两处必须用同一套回退顺序，否则同一条申请在两处叫法不同。 */
const teamRequestLabel = (row: TeamRequestItem) =>
  row.requestId || row.businessKey || row.processDefinitionName

const taskKey = (task: RecentTask) => task.taskId || task.id || `${task.taskName}-${task.createTime}`

const taskRequestLabel = (task: RecentTask) =>
  task.requestId || task.processDefinitionName || task.processName || '—'

const openTask = (task: RecentTask) => {
  const location = dashboardTaskDetailRoute(task)
  if (location) router.push(location)
}

const openRequest = (id: string) => {
  const location = dashboardRequestDetailRoute(id)
  if (location) router.push(location)
}

onMounted(() => {
  refresh()
})
</script>

<style lang="scss" scoped>
// 内容画布是主题灰，页面自己是一张白色卡面（沿用主题的卡片 token）
.dashboard-page {
  // 首屏改卡片编排：页面本身贴着主题画布，MY REQUESTS / MY TEAM / 最近申请各自是一张白卡
  min-height: 100%;
  padding: 4px 4px 8px;
  background: transparent;
}

// 三张卡共用的卡面：白底 + 细边 + 两层投影（近处一条压边，远处一团散开）
// 分带靠内部的通栏细线，所以卡面必须裁切到圆角
%surface-card {
  background: var(--ws-card-bg);
  border: 1px solid var(--ws-card-border);
  border-radius: var(--ws-radius-card);
  box-shadow:
    0 1px 2px rgba(22, 22, 22, 0.04),
    0 14px 30px -20px rgba(22, 22, 22, 0.22);
  overflow: hidden;
}

// 题头带：小型全大写标签 + 右侧入口，与卡身之间一条通栏细线
%card-head {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 15px 22px 13px;
  border-bottom: 1px solid var(--ws-line);
}

// 品牌红竖标：三张卡的题头共用同一枚记号，把这一组卡认成一套
%card-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.09em;
  text-transform: uppercase;
  color: var(--ws-text-secondary);

  &::before {
    content: '';
    flex: 0 0 auto;
    width: 3px;
    height: 13px;
    border-radius: 1.5px;
    background: var(--hsbc-red);
  }
}

// 页脚带：比卡面低半档的浅灰，把度量条压在卡底
%card-foot {
  margin-top: auto;
  padding: 13px 22px;
  border-top: 1px solid var(--ws-line);
  background: #f6f7f9;
}

// ==================== 标题与页面工具 ====================
.page-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
}

.page-heading-copy { min-width: 0; }

.page-heading-tools {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
  min-height: 34px;
}

.command {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 12px;
  border: none;
  border-radius: 2px;
  background: transparent;
  color: var(--ws-text);
  font-size: 13.5px;
  cursor: pointer;

  &:hover:not(:disabled) { background: var(--background-light); }

  &:focus-visible {
    outline: 2px solid var(--hsbc-red);
    outline-offset: -2px;
  }

  &:disabled {
    color: var(--ws-text-muted);
    cursor: default;
  }
}

.page-updated {
  font-size: 12px;
  color: var(--ws-text-muted);
  white-space: nowrap;
}

.is-spinning { animation: dash-spin 0.8s linear infinite; }

@keyframes dash-spin {
  to { transform: rotate(360deg); }
}

// ==================== 标题带 ====================
.page-title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ws-text);
}

.page-intro {
  margin: 6px 0 0;
  max-width: 720px;
  font-size: 13.5px;
  line-height: 1.55;
  color: var(--ws-text-secondary);
}

// ==================== 加载失败 ====================
.load-error {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
  padding: 10px 14px;
  border: 1px solid rgba(219, 0, 17, 0.35);
  border-left: 3px solid var(--hsbc-red);
  background: rgba(219, 0, 17, 0.04);
  color: var(--hsbc-red);
  font-size: 13.5px;
}

.load-error-text { flex: 1; }

.load-error-retry {
  border: none;
  background: transparent;
  color: var(--hsbc-red);
  font: inherit;
  font-weight: 600;
  text-decoration: underline;
  cursor: pointer;
}

// ==================== 任务概览 + 快捷操作 ====================
.home-shortcuts {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
  margin-top: 22px;
}

.task-overview-card,
.quick-actions-card {
  @extend %surface-card;
}

.task-figures {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1px;
  background: var(--ws-line);
}

.task-figure {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  grid-template-areas: 'label value';
  align-items: center;
  min-height: 112px;
  padding: 22px 24px 20px 28px;
  background: var(--ws-card-bg);
  color: var(--ws-text);
  text-decoration: none;

  &::before {
    content: '';
    position: absolute;
    inset: 20px auto 20px 0;
    width: 4px;
    background: var(--hsbc-red);
  }

  &.is-completed::before { background: var(--success-green); }

  &:hover {
    background: var(--background-light);

    .task-figure-value { color: var(--hsbc-red); }
  }

  &:focus-visible {
    z-index: 1;
    outline: 2px solid var(--hsbc-red);
    outline-offset: -2px;
  }
}

.task-figure-label {
  grid-area: label;
  font-size: 20px;
  font-weight: 650;
  line-height: 1.25;
}

.task-figure-value {
  grid-area: value;
  margin-left: 24px;
  font-size: 40px;
  font-weight: 600;
  line-height: 1;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  transition: color 0.15s ease;
}

.quick-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  min-height: 112px;
}

.quick-action {
  display: flex;
  min-width: 0;
  padding: 20px 12px 18px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 10px;
  border-left: 1px solid var(--ws-line);
  color: var(--ws-text);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.25;
  text-align: center;
  text-decoration: none;

  &:first-child { border-left: none; }

  &:hover { background: var(--background-light); }

  &:focus-visible {
    z-index: 1;
    outline: 2px solid var(--hsbc-red);
    outline-offset: -2px;
  }

  .el-icon { color: var(--hsbc-red); }

  &.is-delegations .el-icon { color: var(--warning-orange); }
  &.is-profile-setup .el-icon { color: var(--success-green); }
}

// ==================== 主视觉：两本账 ====================
.ledger {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
  margin-top: 22px;
}

.ledger-half {
  @extend %surface-card;

  display: flex;
  flex-direction: column;
}

.ledger-head { @extend %card-head; }

.ledger-title { @extend %card-eyebrow; }

.ledger-link {
  margin-left: auto;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--hsbc-red);
  font: inherit;
  font-size: 13px;
  text-decoration: none;
  cursor: pointer;

  &:hover { text-decoration: underline; }
}

// 大数字是这一屏的主角：字号拉开，标签退到底下当注脚
.figures {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  flex: 1;
  padding: 18px 22px 20px;
}

// 格间细竖线：四个数字因此读作一排量表，而不是四段浮在留白里的文字
.figure {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
  padding: 2px 11px;
  border-left: 1px solid var(--ws-line);

  &:first-child {
    padding-left: 0;
    border-left: none;
  }

  &:last-child { padding-right: 0; }
}

// 三个数字是进 My Applications 对应分页的入口
.figure-link {
  position: relative;
  color: inherit;
  text-decoration: none;
  cursor: pointer;

  // 下划线是这四个数字「能点」的唯一线索——团队那侧的数字没有链接，也就没有下划线
  .figure-num {
    text-decoration: underline;
    text-decoration-thickness: 2px;
    text-underline-offset: 6px;
    text-decoration-color: var(--ws-text-muted);
  }

  &:hover .figure-num {
    color: var(--hsbc-red);
    text-decoration-color: var(--hsbc-red);
  }

  &:hover .figure-label { color: var(--ws-text); }

  &:focus-visible {
    outline: 2px solid var(--hsbc-red);
    outline-offset: 2px;
    border-radius: 6px;
  }
}

.figure-num {
  font-size: 40px;
  font-weight: 600;
  line-height: 1;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--ws-text);
}

.figure-label {
  font-size: 11px;
  line-height: 1.35;
  overflow-wrap: anywhere;
  color: var(--ws-text-secondary);
}

.ledger-foot {
  @extend %card-foot;

  display: flex;
  align-items: center;
  gap: 12px;

  // 团队卡的页脚是「构成条 + 图例」两行，共用同一条底带
  &.is-stacked {
    flex-direction: column;
    align-items: stretch;
    gap: 9px;
  }
}

.foot-label {
  flex: 0 0 auto;
  font-size: 13px;
  color: var(--ws-text-secondary);
}

.foot-value {
  flex: 0 0 auto;
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--ws-text);
}

.meter {
  flex: 1;
  min-width: 0;
  height: 3px;
  background: var(--ws-line-strong);
  overflow: hidden;
}

.meter-fill {
  display: block;
  height: 100%;
  background: var(--hsbc-red);
  transition: width 0.4s ease;
}

// 构成条：一段红 + 两档灰，红色只留给「还在跑」
.composition {
  display: flex;
  width: 100%;
  height: 8px;
  gap: 2px;
  overflow: hidden;
}

.composition-seg {
  display: block;
  height: 100%;

  // 三档要在白底上都分得出来：--ws-line-strong 做小圆点几乎看不见
  &.is-running { background: var(--hsbc-red); }
  &.is-completed { background: var(--ws-text-secondary); }
  &.is-withdrawn { background: var(--ws-text-muted); }
}

.composition-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin: 0;
  font-size: 12.5px;
  color: var(--ws-text-secondary);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.legend-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;

  &.is-running { background: var(--hsbc-red); }
  &.is-completed { background: var(--ws-text-secondary); }
  &.is-withdrawn { background: var(--ws-text-muted); }
}

// ==================== 区块 ====================
.block {
  @extend %surface-card;

  margin-top: 16px;
}

.block-head { @extend %card-head; }

.block-title { @extend %card-eyebrow; }

.block-link {
  margin-left: auto;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--hsbc-red);
  font: inherit;
  font-size: 13px;
  text-decoration: none;
  cursor: pointer;

  &:hover { text-decoration: underline; }
}

// ==================== 数据表 ====================
// 窄屏让表格自己横滚，页面本身不横滚
.table-scroll {
  padding: 4px 22px 8px;
  overflow-x: auto;
  overscroll-behavior-x: contain;
}

.data-table {
  width: 100%;
  min-width: 660px;
  border-collapse: collapse;
  font-size: 13.5px;

  th {
    padding: 12px 12px 9px;
    border-bottom: 1px solid var(--ws-line);
    color: var(--ws-text-secondary);
    font-size: 12.5px;
    font-weight: 600;
    text-align: left;
    white-space: nowrap;
  }

  td {
    padding: 11px 12px;
    border-bottom: 1px solid var(--ws-line);
    color: var(--ws-text);
    vertical-align: middle;
  }

  th:first-child,
  td:first-child { padding-left: 0; }

  // 卡片自带边界，最后一行不再需要收尾灰线
  tbody tr:last-child td { border-bottom: none; }
}

.data-row {
  cursor: pointer;
  transition: background 0.12s ease;

  &:hover { background: var(--background-light); }

  &:focus-visible {
    outline: 2px solid var(--hsbc-red);
    outline-offset: -2px;
  }
}

// 第一列是入口，用品牌色标出来
.cell-name {
  color: var(--hsbc-red);
  font-weight: 500;
  white-space: nowrap;
}

// 团队那张表的行进不去详情，第一列就不能长得像链接
.cell-strong {
  font-weight: 500;
  white-space: nowrap;
}

.cell-muted { color: var(--ws-text-secondary); }

.task-name {
  display: block;
  font-weight: 500;
  white-space: nowrap;
}

.status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  white-space: nowrap;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--ws-text-muted);
}

.status.is-running .status-dot { background: var(--hsbc-red); }
.status.is-completed .status-dot { background: var(--ws-text-secondary); }
.status.is-withdrawn .status-dot { background: var(--ws-text-muted); }

.block-empty {
  padding: 22px 22px 26px;

  .empty-text {
    margin: 0 0 12px;
    font-size: 13.5px;
    color: var(--ws-text-secondary);
  }
}

.empty-action {
  display: inline-block;
  color: var(--hsbc-red);
  font-size: 13.5px;
  font-weight: 600;
  text-decoration: none;

  &:hover { text-decoration: underline; }
}

// ==================== 团队弹窗 ====================
.team-summary {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.team-summary-item {
  padding: 12px;
  border: 1px solid var(--ws-card-border);
  text-align: center;
  cursor: pointer;

  &:hover { border-color: var(--hsbc-red); }

  .team-summary-value {
    font-size: 22px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    color: var(--ws-text);
  }

  .team-summary-label {
    margin-top: 2px;
    font-size: 12px;
    color: var(--ws-text-secondary);
  }
}

@media (max-width: 900px) {
  .home-shortcuts { grid-template-columns: 1fr; }

  .ledger { grid-template-columns: 1fr; }

  .task-figures { grid-template-columns: 1fr; }

  .task-figure {
    min-height: 96px;
    padding-block: 20px;
  }

  .task-figure-label { font-size: 18px; }

  // 窄屏放不下四格量表，改两行两格；竖线只留在每行中间那道
  .figures {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    row-gap: 18px;
  }

  .figure:nth-child(odd) {
    padding-left: 0;
    border-left: none;
  }

  .figure:nth-child(even) { padding-right: 0; }

  .figures { gap: 24px; }

  .figure-num { font-size: 32px; }

  .page-heading {
    flex-direction: column;
    gap: 8px;
  }

  .page-heading-tools {
    width: 100%;
    justify-content: flex-end;
  }

  .team-summary { grid-template-columns: repeat(2, 1fr); }
}

@media (prefers-reduced-motion: reduce) {
  .is-spinning { animation: none; }
  .meter-fill { transition: none; }
}
</style>
