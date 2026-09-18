/**
 * 功能单元业务逻辑 composable
 *
 * 封装 function-unit 页面的所有 API 调用和业务逻辑。
 * 组件仅保留 template + 调用此 composable。
 */

import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { logger } from '@/utils/logger'
import { notifyConfirm, notifyError, notifySuccess, notifyWarning } from '@/utils/notify'
import { functionUnitApi, type FunctionUnit, type DeletePreviewResponse, type FunctionUnitValidationResult } from '@/api/functionUnit'
import { useFunctionUnitStore } from '@/stores/functionUnit'
import { ApiError } from '@/types/errors'
import { pickHttpErrorBodyMessage } from '@/utils/httpErrorMessage'
import { useFunctionUnitLists, type FunctionUnitRow } from '@/composables/modules/useFunctionUnitLists'

export function useFunctionUnit() {
  const { t } = useI18n()
  const store = useFunctionUnitStore()
  const lists = useFunctionUnitLists()
  const {
    listLoading,
    archivedLoading,
    deploymentsLoading,
    searchKeyword,
    archiveSearchKeyword,
    deploymentSearchKeyword,
    selectedUnits,
    listGrid,
    archiveGrid,
    deployGrid,
    fetchFunctionUnits,
    fetchArchivedFunctionUnits,
    fetchDeployments,
    handleSelectionChange,
    LIST_ACTIONS_WIDTH,
    LIST_SELECTION_WIDTH,
    ARCHIVE_ACTIONS_WIDTH,
  } = lists

  // ==================== State ====================

  const activeTab = ref('list')
  const versionsLoading = ref(false)
  const importLoading = ref(false)
  const deployLoadingId = ref<string | null>(null)
  const validateLoadingId = ref<string | null>(null)
  const restoreLoadingId = ref<string | null>(null)

  const versionList = ref<FunctionUnit[]>([])

  // Dialog visibility
  const showImportDialog = ref(false)
  const showAccessDialogVisible = ref(false)
  const showDeleteDialogVisible = ref(false)
  const showVersionsDialogVisible = ref(false)
  const showCompareDialogVisible = ref(false)

  // Current selections
  const currentUnit = ref<FunctionUnit | null>(null)
  const deleteTargetUnit = ref<FunctionUnit | null>(null)
  const deletePreview = ref<DeletePreviewResponse | null>(null)
  const compareVersion = ref<FunctionUnit | null>(null)

  const importFile = ref<File | null>(null)
  const importUploadRef = ref<any>(null)

  const showValidateResultDialog = ref(false)
  const validateResult = ref<FunctionUnitValidationResult | null>(null)

  watch(activeTab, (tab) => {
    if (tab === 'deployments') void fetchDeployments()
    if (tab === 'archive') void fetchArchivedFunctionUnits()
  })

  // ==================== Dialog Actions ====================

  const showAccessDialog = (unit: FunctionUnit) => {
    currentUnit.value = unit
    showAccessDialogVisible.value = true
  }

  const showVersions = async (unit: FunctionUnit) => {
    currentUnit.value = unit
    showVersionsDialogVisible.value = true
    versionsLoading.value = true
    try {
      versionList.value = await functionUnitApi.getAllVersions(unit.code)
    } catch (e) {
      logger.error('functionUnit', 'Failed to load versions:', e)
      notifyError(t('functionUnit.loadFailed'))
    } finally {
      versionsLoading.value = false
    }
  }

  // ==================== Validate ====================

  const handleValidate = async (unit: FunctionUnit) => {
    validateLoadingId.value = unit.id
    try {
      const result = await functionUnitApi.validateUnit(unit.id)
      validateResult.value = result
      showValidateResultDialog.value = true
      if (result.valid) {
        notifySuccess(t('functionUnit.validateSuccess'))
        await fetchFunctionUnits()
      }
    } catch (e: unknown) {
      logger.error('functionUnit', 'Failed to validate:', e)
      // HTTP 错误已由 request 拦截器 toast；此处仅补充 ApiError 文案（拦截器已展示则不再重复）
      if (!(e instanceof ApiError)) {
        const msg = pickHttpErrorBodyMessage((e as { response?: { data?: unknown } })?.response?.data)
        notifyError(msg || t('functionUnit.validateFailed'))
      }
    } finally {
      validateLoadingId.value = null
    }
  }

  // ==================== Deploy ====================

  const handleDeploy = async (unit: FunctionUnit) => {
    try {
      await notifyConfirm(
        t('functionUnit.deployConfirmMessage', { name: unit.name }),
        t('functionUnit.confirmDeploy'),
        { type: 'warning' }
      )
    } catch {
      return
    }

    deployLoadingId.value = unit.id
    try {
      await functionUnitApi.deploy(unit.id)
      notifySuccess(t('functionUnit.deploySuccess'))
      await fetchFunctionUnits()
      await fetchDeployments()
    } catch (e: unknown) {
      logger.error('functionUnit', 'Failed to deploy:', e)
      const msg = pickHttpErrorBodyMessage((e as { response?: { data?: unknown } })?.response?.data)
      notifyError(msg || t('functionUnit.deployFailed'))
    } finally {
      deployLoadingId.value = null
    }
  }

  // ==================== Restore ====================

  const handleRestore = async (unit: FunctionUnit) => {
    try {
      await notifyConfirm(
        t('functionUnit.restoreConfirmMessage', { name: unit.name }),
        t('functionUnit.restore'),
        { type: 'warning' }
      )
    } catch {
      return
    }

    restoreLoadingId.value = unit.id
    try {
      await functionUnitApi.restore(unit.id)
      notifySuccess(t('functionUnit.restoreSuccess'))
      await fetchArchivedFunctionUnits()
      await fetchFunctionUnits()
    } catch (e: unknown) {
      logger.error('functionUnit', 'Failed to restore:', e)
      const msg = pickHttpErrorBodyMessage((e as { response?: { data?: unknown } })?.response?.data)
      notifyError(msg || t('functionUnit.restoreFailed'))
    } finally {
      restoreLoadingId.value = null
    }
  }

  // ==================== Rollback ====================

  const handleRollback = async (unit: FunctionUnit) => {
    await notifyConfirm(
      t('functionUnit.rollbackConfirm', { name: unit.name }),
      t('common.confirm'),
      { type: 'warning' }
    )
    try {
      const deploymentHistory = await functionUnitApi.getDeploymentHistory(unit.id)
      const lastDeployment = deploymentHistory.find(d => d.status === 'COMPLETED')
      if (lastDeployment) {
        await functionUnitApi.rollbackDeployment(lastDeployment.id, t('functionUnit.manualRollback'))
        notifySuccess(t('functionUnit.rollbackSuccess'))
        fetchFunctionUnits()
      } else {
        notifyWarning(t('functionUnit.noRollbackRecord'))
      }
    } catch (e) {
      logger.error('functionUnit', 'Failed to rollback:', e)
      notifyError(t('functionUnit.rollbackFailed'))
    }
  }

  // ==================== Enable/Disable ====================

  const handleEnabledChange = async (unit: FunctionUnitRow, enabled: boolean) => {
    if (!enabled) {
      try {
        await notifyConfirm(
          t('functionUnit.disableConfirmMessage', { name: unit.name }),
          t('functionUnit.confirmDisable'),
          { type: 'warning' }
        )
      } catch {
        unit.enabled = true
        return
      }
    }
    unit._enabledLoading = true
    try {
      await store.setEnabled(unit.id, enabled)
      notifySuccess(enabled ? t('functionUnit.enabledSuccess') : t('functionUnit.disabledSuccess'))
    } catch (e: unknown) {
      const msg = pickHttpErrorBodyMessage((e as { response?: { data?: unknown } })?.response?.data)
      notifyError(msg || t('common.failed'))
      unit.enabled = !enabled
    } finally {
      unit._enabledLoading = false
    }
  }

  // ==================== Delete (Archive) ====================

  const handleDeleteClick = async (unit: FunctionUnit) => {
    deleteTargetUnit.value = unit
    try {
      deletePreview.value = await functionUnitApi.getDeletePreview(unit.id)
      showDeleteDialogVisible.value = true
    } catch {
      notifyError(t('functionUnit.getDeletePreviewFailed'))
    }
  }

  const handleDeleteConfirm = async () => {
    if (!deleteTargetUnit.value) return
    try {
      await store.deleteFunctionUnit(deleteTargetUnit.value.id)
      notifySuccess(t('functionUnit.archiveSuccess'))
      showDeleteDialogVisible.value = false
      fetchFunctionUnits()
    } catch (e: unknown) {
      logger.error('functionUnit', 'Failed to archive:', e)
      const msg = pickHttpErrorBodyMessage((e as { response?: { data?: unknown } })?.response?.data)
      notifyError(msg || t('functionUnit.deleteFailed'))
    }
  }

  // ==================== Batch Operations (current page only) ====================

  const handleBatchEnable = async () => {
    try {
      await store.batchSetEnabled(selectedUnits.value.map(u => u.id), true)
      notifySuccess(t('functionUnit.enabledSuccess'))
      selectedUnits.value = []
      fetchFunctionUnits()
    } catch (e: unknown) {
      const msg = pickHttpErrorBodyMessage((e as { response?: { data?: unknown } })?.response?.data)
      notifyError(msg || t('common.failed'))
    }
  }

  const handleBatchDisable = async () => {
    try {
      await notifyConfirm(t('functionUnit.batchDisableConfirm'), t('common.confirm'), { type: 'warning' })
      await store.batchSetEnabled(selectedUnits.value.map(u => u.id), false)
      notifySuccess(t('functionUnit.disabledSuccess'))
      selectedUnits.value = []
      fetchFunctionUnits()
    } catch (e) {
      if ((e as string) !== 'cancel') notifyError(t('common.failed'))
    }
  }

  const handleBatchDelete = async () => {
    const ids = selectedUnits.value.map(u => u.id)
    try {
      await notifyConfirm(t('functionUnit.batchArchiveConfirm', { count: ids.length }), t('common.confirm'), { type: 'warning' })
      await store.batchDelete(ids)
      notifySuccess(t('functionUnit.archiveSuccess'))
      selectedUnits.value = []
      fetchFunctionUnits()
    } catch (e) {
      if ((e as string) !== 'cancel') notifyError(t('common.failed'))
    }
  }

  // ==================== Version Compare ====================

  const handleCompareVersion = (version: FunctionUnit) => {
    compareVersion.value = version
    showCompareDialogVisible.value = true
  }

  // ==================== Import ====================

  const resetImportDialog = () => {
    importFile.value = null
  }

  const openImportDialog = () => {
    resetImportDialog()
    showImportDialog.value = true
  }

  watch(showImportDialog, (open) => {
    if (!open) resetImportDialog()
  })

  const handleImportFileChange = (file: any) => {
    importFile.value = file?.raw || null
  }

  const handleStartImport = async () => {
    if (!importFile.value) return
    importLoading.value = true
    try {
      const reader = new FileReader()
      reader.onload = async () => {
        const base64 = (reader.result as string).split(',')[1]
        try {
          const result = await functionUnitApi.import({
            fileName: importFile.value!.name,
            fileContent: base64,
          })
          if (result.success) {
            notifySuccess(result.versioned
              ? t('functionUnit.importVersioned')
              : t('functionUnit.importSuccess'))
            showImportDialog.value = false
            resetImportDialog()
            fetchFunctionUnits()
          } else {
            notifyError(result.errorMessage || t('functionUnit.importFailed'))
          }
        } catch (e: unknown) {
          const msg = pickHttpErrorBodyMessage((e as { response?: { data?: unknown } })?.response?.data)
          notifyError(msg || t('functionUnit.importFailed'))
        } finally {
          importLoading.value = false
        }
      }
      reader.readAsDataURL(importFile.value)
    } catch {
      notifyError(t('functionUnit.importFailed'))
      importLoading.value = false
    }
  }

  return {
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
    currentUnit,
    deleteTargetUnit,
    deletePreview,
    compareVersion,
    importFile,
    importUploadRef,
    showValidateResultDialog,
    validateResult,
    fetchFunctionUnits,
    fetchArchivedFunctionUnits,
    fetchDeployments,
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
  }
}
