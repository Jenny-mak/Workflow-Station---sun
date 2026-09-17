<template>
  <div class="delegations-page">
    <div class="page-header">
      <h1 class="page-title-row">
        <span>{{ t('delegation.title') }}</span>
        <PortalHelpLink
          path="/up-delegations"
          :ariaLabel="t('delegation.guideLinkAria')"
          test-id="delegation-guide-link"
        />
      </h1>
      <el-button
        type="primary"
        @click="createDialogVisible = true"
      >
        {{ t('delegation.create') }}
      </el-button>
    </div>

    <el-tabs
      v-model="activeTab"
      @tab-change="onTabChange"
    >
      <el-tab-pane
        :label="t('delegation.myDelegations')"
        name="my"
      >
        <DelegationRulesList ref="rulesListRef" />
      </el-tab-pane>

      <el-tab-pane
        :label="t('delegation.proxyTasks')"
        name="proxy"
      >
        <DelegationProxyTasksList ref="proxyListRef" />
      </el-tab-pane>

      <el-tab-pane
        :label="t('delegation.auditRecords')"
        name="audit"
      >
        <DelegationAuditList ref="auditListRef" />
      </el-tab-pane>
    </el-tabs>

    <DelegationCreateDialog
      v-model:visible="createDialogVisible"
      @created="onCreated"
    />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import DelegationRulesList from '@/components/delegations/DelegationRulesList.vue'
import DelegationProxyTasksList from '@/components/delegations/DelegationProxyTasksList.vue'
import DelegationAuditList from '@/components/delegations/DelegationAuditList.vue'
import DelegationCreateDialog from '@/components/delegations/DelegationCreateDialog.vue'
import PortalHelpLink from '@/components/PortalHelpLink.vue'

const { t } = useI18n()
const activeTab = ref('my')
const createDialogVisible = ref(false)
const rulesListRef = ref<{ reload: () => Promise<void> } | null>(null)
const proxyListRef = ref<{ ensureLoaded: () => void } | null>(null)
const auditListRef = ref<{ ensureLoaded: () => void } | null>(null)

function onTabChange(name: string | number) {
  if (name === 'proxy') {
    proxyListRef.value?.ensureLoaded()
  }
  if (name === 'audit') {
    auditListRef.value?.ensureLoaded()
  }
}

function onCreated() {
  void rulesListRef.value?.reload()
}
</script>

<style lang="scss">
@import '@/styles/listDataGrid.scss';
</style>

<style lang="scss" scoped>
.delegations-page {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;

  .page-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 16px;
    flex-shrink: 0;
  }

  .page-title-row {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    margin: 0;
    font-size: 20px;
    font-weight: 600;
  }

  :deep(.el-tabs) {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  :deep(.el-tabs__content) {
    flex: 1;
    min-height: 0;
    overflow: hidden;
  }

  :deep(.el-tab-pane) {
    height: 100%;
  }
}
</style>
