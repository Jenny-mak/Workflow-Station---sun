<template>
  <div class="data-view-dashboard-panel">
    <el-tabs
      v-if="dashboards.length > 1"
      v-model="activeDashboardId"
      class="dashboard-tabs"
      @tab-change="renderActiveDashboard"
    >
      <el-tab-pane
        v-for="dashboard in dashboards"
        :key="dashboard.dashboardId"
        :label="dashboard.dashboardTitle"
        :name="dashboard.dashboardId"
      >
        <div
          :id="containerId(dashboard.dashboardId)"
          class="superset-container"
        />
      </el-tab-pane>
    </el-tabs>

    <div
      v-else-if="dashboards.length === 1"
      :id="containerId(dashboards[0].dashboardId)"
      class="superset-container"
    />

    <el-empty
      v-else
      :description="t('mainTableView.noAssignedDashboard')"
    />
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { biDashboardApi, type DataViewDashboardResponse, type GuestTokenResponse } from '@/api/biDashboard'

const props = defineProps<{
  viewId: number
  dashboards: DataViewDashboardResponse[]
}>()

const { t } = useI18n()
const activeDashboardId = ref('')
const embeddedInstances = new Map<string, { unmount?: () => void }>()
let embedGeneration = 0

function containerId(dashboardId: string) {
  return `data-view-superset-${props.viewId}-${dashboardId}`
}

function cleanup() {
  for (const [, instance] of embeddedInstances) instance?.unmount?.()
  embeddedInstances.clear()
}

async function guestToken(dashboardId: string, viewId: number): Promise<GuestTokenResponse> {
  const response = await biDashboardApi.getGuestToken({
    dashboardId,
    dataViewId: viewId,
  })
  return response
}

async function embed(dashboard: DataViewDashboardResponse, generation = embedGeneration) {
  if (embeddedInstances.has(dashboard.dashboardId)) return
  await nextTick()
  if (generation !== embedGeneration) return
  const mountPoint = document.getElementById(containerId(dashboard.dashboardId))
  if (!mountPoint) return

  try {
    const viewId = props.viewId
    const { embedDashboard } = await import('@superset-ui/embedded-sdk')
    const initialToken = await guestToken(dashboard.dashboardId, viewId)
    if (generation !== embedGeneration) return
    let useInitialToken = true
    const instance = await embedDashboard({
      id: dashboard.embedId,
      supersetDomain: initialToken.supersetDomain || 'http://localhost:8089',
      mountPoint,
      fetchGuestToken: async () => {
        if (useInitialToken) {
          useInitialToken = false
          return initialToken.token
        }
        return (await guestToken(dashboard.dashboardId, viewId)).token
      },
      dashboardUiConfig: {
        hideTitle: true,
        hideChartControls: false,
        hideTab: false,
      },
    })
    if (generation !== embedGeneration) {
      instance?.unmount?.()
      return
    }
    embeddedInstances.set(dashboard.dashboardId, instance || {})
    const iframe = mountPoint.querySelector('iframe')
    if (iframe) {
      iframe.style.width = '100%'
      iframe.style.height = '100%'
      iframe.style.border = 'none'
    }
  } catch (error) {
    if (generation !== embedGeneration) return
    console.error(`Failed to embed Data View dashboard ${dashboard.dashboardId}`, error)
    mountPoint.textContent = t('mainTableView.dashboardLoadFailed')
    mountPoint.classList.add('superset-container--error')
  }
}

async function renderActiveDashboard() {
  const active = props.dashboards.find(d => d.dashboardId === activeDashboardId.value)
  if (active) await embed(active)
}

async function initialize() {
  embedGeneration += 1
  cleanup()
  activeDashboardId.value = props.dashboards[0]?.dashboardId || ''
  await renderActiveDashboard()
}

watch(
  () => [props.viewId, props.dashboards.map(d => d.dashboardId).join('|')],
  () => void initialize(),
)

onMounted(() => void initialize())
onBeforeUnmount(cleanup)
</script>

<style scoped lang="scss">
.data-view-dashboard-panel {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.dashboard-tabs {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;

  :deep(.el-tabs__content),
  :deep(.el-tab-pane) {
    flex: 1 1 auto;
    min-height: 0;
    height: 100%;
  }

  :deep(.el-tabs__content) {
    display: flex;
    flex-direction: column;
  }
}

.superset-container {
  flex: 1 1 auto;
  width: 100%;
  min-height: 520px;
  height: 100%;
  overflow: hidden;
  border-radius: 4px;
  background: var(--el-fill-color-light);
}

.superset-container--error {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
}
</style>
