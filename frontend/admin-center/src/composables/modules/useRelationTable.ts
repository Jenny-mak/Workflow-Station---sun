/**
 * 关联表结构业务逻辑 composable
 *
 * 封装 relation-table/structure/index.vue 页面的所有 API 调用和业务逻辑。
 * 组件仅保留 template + 调用此 composable。
 *
 * 所有 notify* 调用均在此处处理。错误通过 AppErrorCode 标准化。
 */

import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { notifyConfirm, notifyError, notifySuccess } from '@/utils/notify'
import { AppErrorCode } from '@/types/errors'
import { errorTranslator } from '@/utils/errorTranslator'
import { useRelationTableStore } from '@/stores/relationTable'
import {
  relationTableStructureApi,
  type RelationTableFuGroup,
  type RelationTableResponse,
} from '@/api/relationTable'
import { useAdminListGrid } from '@/composables/list/useAdminListGrid'

const ACTIONS_COL_WIDTH = 240

export function useRelationTable() {
  const router = useRouter()
  const { t } = useI18n()
  const store = useRelationTableStore()

  const loading = ref(false)
  const enableLoadingMap = ref<Record<number, boolean>>({})
  const portalLoadingMap = ref<Record<number, boolean>>({})
  const currentTable = ref<RelationTableResponse | null>(null)
  const showVersionDialog = ref(false)
  const showAccessDialog = ref(false)
  const showCompareDialog = ref(false)

  /** Left-rail selection: a Function Unit code, COMMON_KEY, or '' for all tables. */
  const selectedGroupKey = ref('')
  const COMMON_KEY = '__common__'
  const functionUnitGroups = ref<RelationTableFuGroup[]>([])

  const grid = useAdminListGrid<RelationTableResponse>({
    storageKey: 'admin-list-layout:rt-structure',
    extraWidth: ACTIONS_COL_WIDTH,
  })

  const terr = (code: string) => t(errorTranslator(code))

  const fetchTableList = async () => {
    const seq = grid.beginQuery()
    loading.value = true
    try {
      const page = await relationTableStructureApi.query({
        ...grid.buildQuery(),
        functionUnitCode: selectedGroupKey.value || undefined,
      })
      if (!grid.isCurrentQuery(seq)) return
      grid.applyPage(page, 'relation-tables/structures/query response is missing its column declaration')
      functionUnitGroups.value = page.functionUnitGroups ?? []
    } catch {
      if (!grid.isCurrentQuery(seq)) return
      notifyError(t('relationTable.structure.queryFailed'))
    } finally {
      if (grid.isCurrentQuery(seq)) loading.value = false
    }
  }

  watch(selectedGroupKey, () => {
    grid.resetPage()
    void fetchTableList()
  })

  const handleToggleEnabled = async (row: RelationTableResponse, val: boolean) => {
    enableLoadingMap.value = { ...enableLoadingMap.value, [row.id]: true }
    try {
      await store.setEnabled(row.id, val)
      notifySuccess(t(val ? 'relationTable.enabled' : 'relationTable.disabled'))
    } catch {
      row.enabled = !val
      notifyError(terr(AppErrorCode.RELATION_TABLE_TOGGLE_FAILED))
    } finally {
      enableLoadingMap.value = { ...enableLoadingMap.value, [row.id]: false }
    }
  }

  const handleTogglePortalVisibility = async (row: RelationTableResponse, val: boolean) => {
    portalLoadingMap.value = { ...portalLoadingMap.value, [row.id]: true }
    try {
      await store.setPortalVisibility(row.id, val)
      notifySuccess(t(val ? 'relationTable.portalVisible' : 'relationTable.portalHidden'))
    } catch {
      row.portalVisible = !val
      notifyError(terr(AppErrorCode.RELATION_TABLE_TOGGLE_FAILED))
    } finally {
      portalLoadingMap.value = { ...portalLoadingMap.value, [row.id]: false }
    }
  }

  const handleAccess = (row: RelationTableResponse) => {
    currentTable.value = row
    showAccessDialog.value = true
  }

  const handleVersions = (row: RelationTableResponse) => {
    currentTable.value = row
    showVersionDialog.value = true
  }

  const handleCompare = (row: RelationTableResponse) => {
    currentTable.value = row
    showCompareDialog.value = true
  }

  const handleEdit = (row: RelationTableResponse) => {
    router.push(`/relation-tables/structure/${row.id}/edit`)
  }

  const handleDeploy = async (row: RelationTableResponse) => {
    try {
      await notifyConfirm(
        t('relationTable.confirmDeployMsg', { name: row.tableName }),
        t('relationTable.confirmDeploy'),
        { type: 'warning' },
      )
      await store.deployTable(row.id)
      notifySuccess(t('relationTable.deployedSuccessfully'))
      await fetchTableList()
    } catch (e) {
      if (e !== 'cancel') {
        notifyError(terr(AppErrorCode.RELATION_TABLE_DEPLOY_FAILED))
      }
    }
  }

  const handleDelete = async (row: RelationTableResponse) => {
    try {
      await notifyConfirm(
        t('relationTable.confirmDeleteMsg', { name: row.tableName }),
        t('relationTable.confirmDelete'),
        { type: 'warning' },
      )
      await store.deleteTable(row.id)
      notifySuccess(t('relationTable.deletedSuccessfully'))
      await fetchTableList()
    } catch (e) {
      if (e !== 'cancel') {
        notifyError(terr(AppErrorCode.RELATION_TABLE_DELETE_FAILED))
      }
    }
  }

  const handleRollback = (row: RelationTableResponse) => {
    currentTable.value = row
    showVersionDialog.value = true
  }

  return {
    loading,
    functionUnitGroups,
    selectedGroupKey,
    COMMON_KEY,
    enableLoadingMap,
    portalLoadingMap,
    currentTable,
    showVersionDialog,
    showAccessDialog,
    showCompareDialog,
    fetchTableList,
    handleToggleEnabled,
    handleTogglePortalVisibility,
    handleDeploy,
    handleDelete,
    handleAccess,
    handleVersions,
    handleEdit,
    handleRollback,
    handleCompare,
    ACTIONS_COL_WIDTH,
    ...grid,
  }
}
