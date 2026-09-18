<template>
  <div class="page-container">
    <PageHeader :title="t('menu.functionUnit')">
      <template #actions>
        <el-button
          type="primary"
          @click="openImportDialog"
        >
          <el-icon><Upload /></el-icon>{{ t('common.import') }}
        </el-button>
      </template>
    </PageHeader>

    <el-tabs v-model="activeTab">
      <el-tab-pane
        :label="t('functionUnit.list')"
        name="list"
      >
        <FunctionUnitListTab
          v-model:search-keyword="searchKeyword"
          :grid="listGrid"
          :loading="listLoading"
          :selected-units="selectedUnits"
          :selection-width="LIST_SELECTION_WIDTH"
          :actions-width="LIST_ACTIONS_WIDTH"
          :validate-loading-id="validateLoadingId"
          :deploy-loading-id="deployLoadingId"
          @fetch="fetchFunctionUnits"
          @selection-change="handleSelectionChange"
          @enabled-change="handleEnabledChange"
          @show-access="showAccessDialog"
          @validate="handleValidate"
          @deploy="handleDeploy"
          @show-versions="showVersions"
          @rollback="handleRollback"
          @delete-click="handleDeleteClick"
          @batch-enable="handleBatchEnable"
          @batch-disable="handleBatchDisable"
          @batch-delete="handleBatchDelete"
        />
      </el-tab-pane>

      <el-tab-pane
        :label="t('functionUnit.archiveList')"
        name="archive"
      >
        <FunctionUnitArchiveTab
          v-model:search-keyword="archiveSearchKeyword"
          :grid="archiveGrid"
          :loading="archivedLoading"
          :actions-width="ARCHIVE_ACTIONS_WIDTH"
          :restore-loading-id="restoreLoadingId"
          @fetch="fetchArchivedFunctionUnits"
          @restore="handleRestore"
        />
      </el-tab-pane>

      <el-tab-pane
        :label="t('functionUnit.deploymentRecords')"
        name="deployments"
      >
        <FunctionUnitDeploymentsTab
          v-model:search-keyword="deploymentSearchKeyword"
          :grid="deployGrid"
          :loading="deploymentsLoading"
          @fetch="fetchDeployments"
        />
      </el-tab-pane>
    </el-tabs>

    <FunctionUnitImportDialog
      v-model="showImportDialog"
      :import-loading="importLoading"
      :import-file="importFile"
      @file-change="handleImportFileChange"
      @start-import="handleStartImport"
    />

    <AccessConfigDialog
      v-model="showAccessDialogVisible"
      :function-unit-id="currentUnit?.id"
      :function-unit-name="currentUnit?.name"
    />

    <el-dialog
      v-model="showVersionsDialogVisible"
      :title="t('functionUnit.versions') + ' - ' + (currentUnit?.name || '')"
      width="800px"
    >
      <el-table
        v-loading="versionsLoading"
        :data="versionList"
        stripe
      >
        <el-table-column
          prop="name"
          :label="t('common.name')"
        />
        <el-table-column
          prop="version"
          :label="t('functionUnit.version')"
          width="120"
        />
        <el-table-column
          prop="status"
          :label="t('common.status')"
          width="120"
        >
          <template #default="{ row }">
            <el-tag :type="functionUnitStatusType(row.status)">
              {{ t(functionUnitStatusKey(row.status)) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          :label="t('common.enable')"
          width="80"
        >
          <template #default="{ row }">
            <el-tag
              :type="row.enabled ? 'success' : 'info'"
              size="small"
            >
              {{ row.enabled ? t('common.yes') : t('common.no') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="updatedAt"
          :label="t('common.updateTime')"
        />
        <el-table-column
          :label="t('common.actions')"
          width="100"
        >
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="handleCompareVersion(row)"
            >
              {{ t('version.compare') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="showVersionsDialogVisible = false">
          {{ t('common.close') }}
        </el-button>
      </template>
    </el-dialog>

    <DeleteConfirmDialog
      v-model="showDeleteDialogVisible"
      :function-unit="deleteTargetUnit"
      :preview="deletePreview"
      @confirm="handleDeleteConfirm"
    />

    <el-dialog
      v-model="showCompareDialogVisible"
      :title="t('version.compare')"
      width="700px"
    >
      <div v-if="compareVersion">
        <el-descriptions
          :column="2"
          border
        >
          <el-descriptions-item :label="t('functionUnit.version')">
            {{ compareVersion.version }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('common.status')">
            <el-tag :type="functionUnitStatusType(compareVersion.status)">
              {{ t(functionUnitStatusKey(compareVersion.status)) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('common.updateTime')">
            {{ compareVersion.updatedAt }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('common.enable')">
            {{ compareVersion.enabled ? t('common.yes') : t('common.no') }}
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="showCompareDialogVisible = false">
          {{ t('common.close') }}
        </el-button>
      </template>
    </el-dialog>

    <ValidateResultDialog
      v-model="showValidateResultDialog"
      :result="validateResult"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, onActivated } from 'vue'
import { useI18n } from 'vue-i18n'
import { Upload } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import DeleteConfirmDialog from './components/DeleteConfirmDialog.vue'
import AccessConfigDialog from './components/AccessConfigDialog.vue'
import FunctionUnitImportDialog from './components/FunctionUnitImportDialog.vue'
import ValidateResultDialog from './components/ValidateResultDialog.vue'
import FunctionUnitListTab from './components/FunctionUnitListTab.vue'
import FunctionUnitArchiveTab from './components/FunctionUnitArchiveTab.vue'
import FunctionUnitDeploymentsTab from './components/FunctionUnitDeploymentsTab.vue'
import { functionUnitStatusType, functionUnitStatusKey } from '@/utils/format'
import { useFunctionUnit } from '@/composables/modules/useFunctionUnit'

const { t } = useI18n()

const {
  activeTab,
  listLoading,
  archivedLoading,
  deploymentsLoading,
  versionsLoading,
  importLoading,
  deployLoadingId,
  validateLoadingId,
  restoreLoadingId,
  versionList,
  searchKeyword,
  archiveSearchKeyword,
  deploymentSearchKeyword,
  selectedUnits,
  listGrid,
  archiveGrid,
  deployGrid,
  handleSelectionChange,
  LIST_ACTIONS_WIDTH,
  LIST_SELECTION_WIDTH,
  ARCHIVE_ACTIONS_WIDTH,
  showImportDialog,
  showAccessDialogVisible,
  showDeleteDialogVisible,
  showVersionsDialogVisible,
  showCompareDialogVisible,
  showValidateResultDialog,
  validateResult,
  currentUnit,
  deleteTargetUnit,
  deletePreview,
  compareVersion,
  importFile,
  fetchFunctionUnits,
  showAccessDialog,
  showVersions,
  handleValidate,
  handleDeploy,
  handleRestore,
  handleRollback,
  handleEnabledChange,
  handleDeleteClick,
  handleDeleteConfirm,
  handleBatchEnable,
  handleBatchDisable,
  handleBatchDelete,
  handleCompareVersion,
  openImportDialog,
  handleImportFileChange,
  handleStartImport,
  fetchArchivedFunctionUnits,
  fetchDeployments,
} = useFunctionUnit()

onMounted(() => {
  void fetchFunctionUnits()
})

onActivated(() => {
  void fetchFunctionUnits()
})
</script>
