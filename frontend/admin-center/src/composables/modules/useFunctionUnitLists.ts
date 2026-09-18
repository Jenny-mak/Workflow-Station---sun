/**
 * Shared-list grids for the Function Unit page (list / archive / deployments).
 * Dialogs and write actions stay in useFunctionUnit.
 */

import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { notifyError } from '@/utils/notify'
import {
  functionUnitApi,
  type Deployment,
  type FunctionUnit,
} from '@/api/functionUnit'
import { useAdminListGrid } from '@/composables/list/useAdminListGrid'

export type FunctionUnitRow = FunctionUnit & { _enabledLoading?: boolean }

const LIST_ACTIONS_WIDTH = 420
const LIST_SELECTION_WIDTH = 50
const ARCHIVE_ACTIONS_WIDTH = 120

export function useFunctionUnitLists() {
  const { t } = useI18n()

  const listLoading = ref(false)
  const archivedLoading = ref(false)
  const deploymentsLoading = ref(false)
  const searchKeyword = ref('')
  const archiveSearchKeyword = ref('')
  const deploymentSearchKeyword = ref('')
  const selectedUnits = ref<FunctionUnitRow[]>([])

  const listGrid = useAdminListGrid<FunctionUnitRow>({
    storageKey: 'admin-list-layout:function-units',
    extraWidth: LIST_ACTIONS_WIDTH,
  })
  const archiveGrid = useAdminListGrid<FunctionUnitRow>({
    storageKey: 'admin-list-layout:function-units-archived',
    extraWidth: ARCHIVE_ACTIONS_WIDTH,
  })
  const deployGrid = useAdminListGrid<Deployment>({
    storageKey: 'admin-list-layout:function-unit-deployments',
    extraWidth: 0,
  })

  const fetchFunctionUnits = async () => {
    const seq = listGrid.beginQuery()
    listLoading.value = true
    try {
      const page = await functionUnitApi.query({
        ...listGrid.buildQuery(),
        keyword: searchKeyword.value || undefined,
      })
      if (!listGrid.isCurrentQuery(seq)) return
      listGrid.applyPage({
        ...page,
        content: page.content.map((unit) => ({
          ...unit,
          enabled: unit.enabled !== false,
          _enabledLoading: false,
        })),
      }, 'function-units/query response is missing its column declaration')
    } catch (error: unknown) {
      if (!listGrid.isCurrentQuery(seq)) return
      if (!(error as { response?: unknown })?.response) {
        notifyError(error instanceof Error ? error.message : t('functionUnit.loadFailed'))
      }
    } finally {
      if (listGrid.isCurrentQuery(seq)) listLoading.value = false
    }
  }

  const fetchArchivedFunctionUnits = async () => {
    const seq = archiveGrid.beginQuery()
    archivedLoading.value = true
    try {
      const page = await functionUnitApi.queryArchived({
        ...archiveGrid.buildQuery(),
        keyword: archiveSearchKeyword.value || undefined,
      })
      if (!archiveGrid.isCurrentQuery(seq)) return
      archiveGrid.applyPage(page, 'function-units/archived/query response is missing its column declaration')
    } catch (error: unknown) {
      if (!archiveGrid.isCurrentQuery(seq)) return
      if (!(error as { response?: unknown })?.response) {
        notifyError(error instanceof Error ? error.message : t('functionUnit.loadFailed'))
      }
    } finally {
      if (archiveGrid.isCurrentQuery(seq)) archivedLoading.value = false
    }
  }

  const fetchDeployments = async () => {
    const seq = deployGrid.beginQuery()
    deploymentsLoading.value = true
    try {
      const page = await functionUnitApi.queryDeployments({
        ...deployGrid.buildQuery(),
        keyword: deploymentSearchKeyword.value || undefined,
      })
      if (!deployGrid.isCurrentQuery(seq)) return
      deployGrid.applyPage(page, 'function-units/deployments/query response is missing its column declaration')
    } catch (error: unknown) {
      if (!deployGrid.isCurrentQuery(seq)) return
      if (!(error as { response?: unknown })?.response) {
        notifyError(error instanceof Error ? error.message : t('functionUnit.loadFailed'))
      }
    } finally {
      if (deployGrid.isCurrentQuery(seq)) deploymentsLoading.value = false
    }
  }

  let listSearchTimer: ReturnType<typeof setTimeout> | undefined
  let archiveSearchTimer: ReturnType<typeof setTimeout> | undefined
  watch(searchKeyword, () => {
    clearTimeout(listSearchTimer)
    listSearchTimer = setTimeout(() => {
      listGrid.resetPage()
      void fetchFunctionUnits()
    }, 300)
  })
  watch(archiveSearchKeyword, () => {
    clearTimeout(archiveSearchTimer)
    archiveSearchTimer = setTimeout(() => {
      archiveGrid.resetPage()
      void fetchArchivedFunctionUnits()
    }, 300)
  })
  onBeforeUnmount(() => {
    clearTimeout(listSearchTimer)
    clearTimeout(archiveSearchTimer)
  })

  const handleSelectionChange = (selection: FunctionUnitRow[]) => {
    selectedUnits.value = selection
  }

  return {
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
  }
}
