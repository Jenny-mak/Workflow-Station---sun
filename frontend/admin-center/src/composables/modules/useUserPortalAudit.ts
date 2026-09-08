/**
 * User Portal 审计日志业务逻辑 composable
 *
 * 封装 audit/user-portal/index.vue 页面的所有 API 调用、状态管理和业务逻辑。
 * 参照 useAudit.ts 的模式。
 */

import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { storeToRefs } from 'pinia'
import { notifyError, notifySuccess } from '@/utils/notify'
import type { TableInstance } from 'element-plus'
import { queryAuditLogList, type UserPortalAuditRecord } from '@/api/user-portal-audit'
import { useUserPortalAuditStore } from '@/stores/user-portal-audit'
import { useAdminListGrid } from '@/composables/list/useAdminListGrid'
import * as XLSX from 'xlsx'

// ==================== 纯函数：操作类型 / 时间映射 ====================

const CHANGE_TYPE_TAG_MAP: Record<string, string> = {
  FIELD_UPDATE: 'warning',
  SUB_TABLE_ROW_ADD: 'success',
  SUB_TABLE_ROW_UPDATE: 'warning',
  SUB_TABLE_ROW_DELETE: 'danger',
  PROCESS_INITIATION: 'primary',
  RECORD_NOTE_ADD: 'success',
  RECORD_NOTE_UPDATE: 'warning',
  RECORD_NOTE_DELETE: 'danger',
  CLAIM: 'success',
  UNCLAIM: 'warning',
  FORCE_UNCLAIM: 'danger',
  REASSIGN: 'warning',
}

export function changeTypeTag(changeType: string): string {
  return CHANGE_TYPE_TAG_MAP[changeType] || 'info'
}

export function changeTypeText(t: ReturnType<typeof useI18n>['t'], changeType: string): string {
  const map: Record<string, string> = {
    FIELD_UPDATE: t('upAudit.actionFIELD_UPDATE'),
    SUB_TABLE_ROW_ADD: t('upAudit.actionSUB_TABLE_ROW_ADD'),
    SUB_TABLE_ROW_UPDATE: t('upAudit.actionSUB_TABLE_ROW_UPDATE'),
    SUB_TABLE_ROW_DELETE: t('upAudit.actionSUB_TABLE_ROW_DELETE'),
    PROCESS_INITIATION: t('upAudit.actionPROCESS_INITIATION'),
    RECORD_NOTE_ADD: t('upAudit.actionRECORD_NOTE_ADD'),
    RECORD_NOTE_UPDATE: t('upAudit.actionRECORD_NOTE_UPDATE'),
    RECORD_NOTE_DELETE: t('upAudit.actionRECORD_NOTE_DELETE'),
    CLAIM: t('upAudit.actionCLAIM'),
    UNCLAIM: t('upAudit.actionUNCLAIM'),
    FORCE_UNCLAIM: t('upAudit.actionFORCE_UNCLAIM'),
    REASSIGN: t('upAudit.actionREASSIGN'),
  }
  return map[changeType] || changeType
}

export function formatTimestamp(isoStr: string): string {
  if (!isoStr) return '-'
  try {
    const d = new Date(isoStr)
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  } catch {
    return isoStr
  }
}

export function truncateValue(value: string | null | undefined, maxLen: number = 40): string {
  if (!value) return '-'
  return value.length > maxLen ? value.substring(0, maxLen) + '…' : value
}

// ==================== Composable ====================

export function useUserPortalAudit() {
  const { t } = useI18n()
  const store = useUserPortalAuditStore()

  const { logs, total, loading: storeLoading, query, dateRange, functionUnitCodes } = storeToRefs(store)
  const { sort: storeSort } = storeToRefs(store)

  // ==================== Local State ====================

  const loading = storeLoading
  const ACTIONS_COL_WIDTH = 80
  const grid = useAdminListGrid<UserPortalAuditRecord>({
    storageKey: 'admin-list-layout:up-audit',
    extraWidth: ACTIONS_COL_WIDTH,
  })
  const page = computed({
    get: () => grid.pagination.page,
    set: (v) => { grid.pagination.page = v },
  })
  const size = computed({
    get: () => grid.pagination.size,
    set: (v) => { grid.pagination.size = v },
  })
  const sortField = computed({
    get: () => storeSort.value.field,
    set: (v) => { store.sort.field = v },
  })
  const sortOrder = computed({
    get: () => storeSort.value.order,
    set: (v) => { store.sort.order = v },
  })

  const exporting = ref(false)
  const detailDialogVisible = ref(false)
  const currentRecord = ref<UserPortalAuditRecord | null>(null)
  const tableRef = ref<TableInstance>()

  // ==================== Export Dialog ====================

  const exportDialogVisible = ref(false)
  const ALL_EXPORT_FIELDS = computed(() => [
    { key: 'timestamp', label: t('audit.time') },
    { key: 'userName', label: t('audit.operator') },
    { key: 'functionUnitCode', label: t('upAudit.functionUnit') },
    { key: 'subTableName', label: t('upAudit.subTableName') },
    { key: 'changeType', label: t('upAudit.changeType') },
    { key: 'processTitle', label: t('upAudit.processInstanceId') },
    { key: 'stageName', label: t('upAudit.stage') },
    { key: 'fieldName', label: t('upAudit.fieldName') },
    { key: 'oldValue', label: t('upAudit.oldValue') },
    { key: 'newValue', label: t('upAudit.newValue') },
  ])

  // ==================== Date Shortcuts ====================

  const dateShortcuts = [
    {
      text: t('audit.today'),
      value: () => { const d = new Date(); return [d, d] },
    },
    {
      text: t('audit.last7Days'),
      value: () => {
        const end = new Date()
        const start = new Date()
        start.setDate(start.getDate() - 6)
        return [start, end]
      },
    },
    {
      text: t('audit.last30Days'),
      value: () => {
        const end = new Date()
        const start = new Date()
        start.setDate(start.getDate() - 29)
        return [start, end]
      },
    },
    {
      text: t('audit.thisMonth'),
      value: () => {
        const now = new Date()
        return [new Date(now.getFullYear(), now.getMonth(), 1), now]
      },
    },
  ]

  // ==================== Actions ====================

  const handleSearch = async () => {
    const seq = grid.beginQuery()
    loading.value = true
    try {
      const pageResult = await queryAuditLogList({
        ...store.buildQueryRequest(),
        ...grid.buildQuery(),
      })
      if (!grid.isCurrentQuery(seq)) return
      grid.applyPage(pageResult, 'user-portal-audit-logs/list-query response is missing its column declaration')
      logs.value = pageResult.content
      total.value = pageResult.totalElements
      store.sort.field = grid.sort.field || 'timestamp'
      store.sort.order = grid.sort.direction === 'ASC' ? 'ascending' : 'descending'
    } catch (error: unknown) {
      if (!grid.isCurrentQuery(seq)) return
      if (!(error as { response?: unknown })?.response) {
        notifyError(error instanceof Error ? error.message : t('upAudit.emptyText'))
      }
    } finally {
      if (grid.isCurrentQuery(seq)) loading.value = false
    }
  }

  const handleReset = () => {
    store.resetQuery()
    grid.clearSort()
    for (const field of Object.keys(grid.columnFilters.value)) {
      grid.clearFilter(field)
    }
    handleSearch()
  }

  const showDetail = (record: UserPortalAuditRecord) => {
    currentRecord.value = record
    detailDialogVisible.value = true
  }

  // ==================== Export ====================

  const getRowValue = (row: UserPortalAuditRecord, key: string): string => {
    switch (key) {
      case 'timestamp': return formatTimestamp(row.timestamp)
      case 'userName': return row.userName || row.userId || '-'
      case 'functionUnitCode': return row.functionUnitName || row.functionUnitCode || '-'
      case 'changeType': return changeTypeText(t, row.changeType)
      case 'processTitle': return row.processTitle || row.processInstanceId || '-'
      case 'processInstanceId': return row.processTitle || row.processInstanceId || '-'
      case 'stageName': return row.stageName || row.stageId || '-'
      case 'fieldName': return row.fieldLabel || row.fieldName || '-'
      case 'subTableName': return row.subTableDisplayName || row.subTableName || '-'
      case 'oldValue': return truncateValue(row.oldValue, 100)
      case 'newValue': return truncateValue(row.newValue, 100)
      default: return '-'
    }
  }

  const buildExportRows = (data: UserPortalAuditRecord[], fieldKeys: { key: string; label: string }[]) => {
    const headers = fieldKeys.map(f => f.label)
    const rows = data.map(row => fieldKeys.map(f => getRowValue(row, f.key)))
    return { headers, rows }
  }

  const buildCsvBlob = (headers: string[], rows: string[][]) => {
    const csv = [headers, ...rows]
      .map(r => r.map(v => `"${String(v).replace(/"/g, '""')}"`).join(','))
      .join('\n')
    return new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' })
  }

  const downloadBlob = (blob: Blob, filename: string) => {
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  const openExportDialog = () => {
    exportDialogVisible.value = true
  }

  const doExport = async (format: 'csv' | 'excel', fields?: string[]) => {
    const exportFields = fields?.length ? fields : ALL_EXPORT_FIELDS.value.map(f => f.key)
    if (exportFields.length === 0) return
    const fieldLabels = ALL_EXPORT_FIELDS.value.filter(f => exportFields.includes(f.key))

    exporting.value = true
    try {
      const dataToExport = await store.fetchAllLogsForExport()
      const { headers, rows } = buildExportRows(dataToExport, fieldLabels)
      const dateSuffix = new Date().toISOString().slice(0, 10)

      if (format === 'csv') {
        downloadBlob(buildCsvBlob(headers, rows), `up-audit-logs-${dateSuffix}.csv`)
      } else {
        const ws = XLSX.utils.aoa_to_sheet([headers, ...rows])
        const wb = XLSX.utils.book_new()
        XLSX.utils.book_append_sheet(wb, ws, 'User Portal Audit')
        XLSX.writeFile(wb, `up-audit-logs-${dateSuffix}.xlsx`)
      }
      notifySuccess(t('common.success'))
      exportDialogVisible.value = false
    } catch (e) {
      console.error('Export failed:', e)
    } finally {
      exporting.value = false
    }
  }

  // ==================== Lifecycle ====================

  onMounted(async () => {
    if (!store.dateRange || store.dateRange.length === 0) {
      const end = new Date()
      const start = new Date()
      start.setDate(start.getDate() - 6)
      const fmt = (d: Date) =>
        `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
      store.dateRange = [fmt(start), fmt(end)]
    }
    if (store.functionUnitCodes.length === 0) {
      await store.fetchFunctionUnitCodes()
    }
    handleSearch()
  })

  onUnmounted(() => {
    // cleanup if needed
  })

  return {
    // State
    loading, exporting, logs, total, page, size,
    detailDialogVisible, currentRecord, dateRange,
    tableRef, sortField, sortOrder,
    query, functionUnitCodes,
    // Export
    exportDialogVisible,
    ALL_EXPORT_FIELDS,
    openExportDialog, doExport,
    // Date shortcuts
    dateShortcuts,
    // Mapping functions
    changeTypeTag, changeTypeText, formatTimestamp, truncateValue,
    // Actions
    handleSearch, handleReset,
    showDetail,
    ACTIONS_COL_WIDTH,
    ...grid,
  }
}
