import type { useI18n } from 'vue-i18n'
import type { ChangeHistoryRecord } from '@/api/processForm'
import {
  applySensitiveMask,
  isSensitiveMaskActive,
} from '@/utils/sensitiveMask'
import {
  getSensitiveMask,
  type SensitiveMaskLookup,
} from '@/utils/sensitiveMaskLookup'
import { isStoredFileUrl } from '@/components/subTableAddDialogHelpers/fileColumns'
import { fileDisplayText } from '@/utils/mainTableViewCsvExport'

type TranslateFn = ReturnType<typeof useI18n>['t']

const ASSIGNMENT_CHANGE_TYPES = new Set(['CLAIM', 'UNCLAIM', 'FORCE_UNCLAIM', 'REASSIGN'])

function isAssignmentChangeType(changeType: string | undefined): boolean {
  return !!changeType && ASSIGNMENT_CHANGE_TYPES.has(changeType)
}

/** 批次表头解析后的展示字段集合 */
export interface BatchHeaderFields {
  displayOperator: string
  displayStage: string
  /** 与展示名不同或需补充技术 ID 时的 tooltip */
  stageTooltip: string | null
  /** 关联任务展示：优先 BPMN/环节解析名，否则短 ID；完整 ID 见 tooltip */
  taskDisplayLabel: string
}

export interface ChangeHistoryFormatting {
  shortId: (id: string) => string
  resolveOperator: (row: ChangeHistoryRecord) => string
  humanizeStageKey: (stageId: string | null | undefined) => string
  resolveStageDisplay: (row: ChangeHistoryRecord) => string
  resolveStageTooltip: (row: ChangeHistoryRecord, displayStage: string) => string | null
  resolveTaskDisplayLabel: (row: ChangeHistoryRecord) => string
  batchHeaderFields: (row: ChangeHistoryRecord) => BatchHeaderFields
  fieldLocationLabel: (row: ChangeHistoryRecord) => string
  formatDisplayValue: (
    raw: string | null | undefined,
    maxLen?: number,
    fieldName?: string | null,
  ) => string
  formatTimestamp: (ts: string) => string
  getChangeTypeLabel: (changeType: string) => string
  getChangeTypeTag: (changeType: string) => 'success' | 'warning' | 'danger' | 'info'
}

/** 历史记录的展示格式化、字段标签与变更类型映射；纯函数，依赖 i18n。 */
export function useChangeHistoryFormatting(
  t: TranslateFn,
  dayjs: typeof import('dayjs'),
  getMaskLookup?: () => SensitiveMaskLookup | null | undefined,
): ChangeHistoryFormatting {
  function shortId(id: string): string {
    if (!id || id.length <= 14) return id
    return `${id.slice(0, 8)}…${id.slice(-4)}`
  }

  function resolveOperator(row: ChangeHistoryRecord): string {
    const n = row.userName?.trim()
    if (n) return n
    return row.userId
  }

  function humanizeStageKey(stageId: string | null | undefined): string {
    if (!stageId) return ''
    let s = stageId
    if (/^Task_/i.test(s) && s.length > 5) {
      s = s.slice(5)
    }
    return s.replace(/_/g, ' ')
  }

  function resolveStageDisplay(row: ChangeHistoryRecord): string {
    const n = row.stageName?.trim()
    if (n) return n
    if (row.stageId === 'RETURN_TO_REQUESTER') {
      return t('changeHistory.stageReturnToRequester')
    }
    return humanizeStageKey(row.stageId)
  }

  function resolveStageTooltip(row: ChangeHistoryRecord, displayStage: string): string | null {
    if (!row.stageId) return null
    if (displayStage !== row.stageId) {
      return row.stageId
    }
    return null
  }

  function resolveTaskDisplayLabel(row: ChangeHistoryRecord): string {
    if (!row.taskInstanceId) return ''
    const stageText = resolveStageDisplay(row).trim()
    if (stageText) return stageText
    return shortId(row.taskInstanceId)
  }

  function batchHeaderFields(row: ChangeHistoryRecord): BatchHeaderFields {
    const displayStage = resolveStageDisplay(row)
    return {
      displayOperator: resolveOperator(row),
      displayStage,
      stageTooltip: resolveStageTooltip(row, displayStage),
      taskDisplayLabel: resolveTaskDisplayLabel(row),
    }
  }

  function fieldLocationLabel(row: ChangeHistoryRecord): string {
    if (row.changeType === 'PROCESS_INITIATION') {
      return t('changeHistory.processInitiation')
    }
    if (isAssignmentChangeType(row.changeType) || row.fieldName === 'claimed_by') {
      return t('changeHistory.claimedBy')
    }
    // Notes carry a fixed backend field name ("Record Note"); localise it here and keep the
    // row id suffix so a sub-table-row (RECORD scope) note points at its own row.
    if (row.changeType?.startsWith('RECORD_NOTE')) {
      const label = t('changeHistory.recordNote')
      return row.rowIdentifier
        ? `${label} · ${t('changeHistory.row')}: ${row.rowIdentifier}`
        : label
    }
    if (row.subTableName) {
      const parts = [
        `${t('changeHistory.subTable')}: ${row.subTableName}`,
        `${t('changeHistory.row')}: ${row.rowIdentifier ?? '—'}`,
      ]
      const field = row.fieldLabel?.trim() || row.fieldName
      if (field) parts.push(field)
      return parts.join(' · ')
    }
    return row.fieldLabel?.trim() || row.fieldName || '—'
  }

  function currentLookup(): SensitiveMaskLookup | null | undefined {
    return getMaskLookup?.()
  }

  function maskPlainText(text: string, fieldName: string | null | undefined): string {
    const cfg = getSensitiveMask(currentLookup(), fieldName)
    if (!isSensitiveMaskActive(cfg)) return text
    return applySensitiveMask(text, cfg!)
  }

  function formatScalarValue(value: unknown, maxLen: number, fieldName?: string | null): string {
    if (value === null || value === undefined || value === '') return '—'
    if (typeof value === 'object') {
      const displayName = objectDisplayName(value as Record<string, unknown>)
      if (displayName) return truncateText(displayName, maxLen)
      return truncateText(JSON.stringify(value), maxLen)
    }
    const masked = maskPlainText(String(value), fieldName)
    return formatFileOrText(masked, maxLen)
  }

  function objectDisplayName(value: Record<string, unknown>): string | null {
    for (const key of ['dropdown_name', 'name', 'label', 'displayName', 'display_name', 'fullName', 'username']) {
      const v = value[key]
      if (v !== null && v !== undefined && typeof v !== 'object' && String(v).trim()) {
        return String(v).trim()
      }
    }
    return null
  }
  function lookupDisplayName(value: Record<string, unknown>): string | null {
    const displayName = value.dropdown_name
    if (displayName === null || displayName === undefined || typeof displayName === 'object') {
      return null
    }
    const normalized = String(displayName).trim()
    return normalized || null
  }
  function formatObjectDiff(value: Record<string, unknown>, maxLen: number): string {
    const partMax = Math.max(32, Math.floor(maxLen / 2))
    const parts = Object.entries(value)
      .filter(([key]) => key !== 'row_id' && key !== 'id')
      .map(([key, v]) => `${key}: ${formatScalarValue(v, partMax, key)}`)
    if (parts.length === 0) {
      const idCandidate = value.id ?? value.userId ?? value.user_id ?? value.value
      if (idCandidate !== null && idCandidate !== undefined && String(idCandidate).trim()) {
        return truncateText(String(idCandidate).trim(), maxLen)
      }
      return '—'
    }
    return truncateText(parts.join('; '), maxLen)
  }

  function formatFileOrText(value: string, maxLen: number): string {
    if (isStoredFileUrl(value)) {
      return truncateText(fileDisplayText(value), maxLen)
    }
    return truncateText(value, maxLen)
  }

  function truncateText(value: string, maxLen: number): string {
    return value.length <= maxLen ? value : `${value.slice(0, maxLen)}…`
  }
  
  function formatDisplayValue(
    raw: string | null | undefined,
    maxLen = 240,
    fieldName?: string | null,
  ): string {
    if (fieldName === 'claimed_by' && (raw === null || raw === undefined || String(raw).trim() === '')) {
      return t('changeHistory.claimPool')
    }
    if (raw === null || raw === undefined || raw === '') return '—'
    const s = String(raw).trim()
    if (!s) return '—'

    // JSON object/array first — sub-table row data is serialised as JSON
    // and may contain file URLs; we must parse JSON before the file-upload
    // regex, otherwise the regex greedily captures JSON tail content.
    if ((s.startsWith('{') && s.endsWith('}')) || (s.startsWith('[') && s.endsWith(']'))) {
      try {
        const parsed = JSON.parse(s) as unknown
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
          const displayName = lookupDisplayName(parsed as Record<string, unknown>)
          if (displayName) {
            return truncateText(displayName, maxLen)
          }
          return formatObjectDiff(parsed as Record<string, unknown>, maxLen)
        }
        const compact = JSON.stringify(parsed)
        return truncateText(compact, maxLen)
      } catch {
        /* fall through */
      }
    }
    return formatFileOrText(maskPlainText(s, fieldName), maxLen)
  }

  function formatTimestamp(ts: string): string {
    if (!ts) return '-'
    const d = dayjs(ts)
    return d.isValid() ? d.format('YYYY-MM-DD HH:mm:ss') : ts
  }

  function getChangeTypeLabel(changeType: string): string {
    const map: Record<string, string> = {
      FIELD_UPDATE: t('changeHistory.fieldUpdate'),
      SUB_TABLE_ROW_ADD: t('changeHistory.subTableRowAdd'),
      SUB_TABLE_ROW_UPDATE: t('changeHistory.subTableRowUpdate'),
      SUB_TABLE_ROW_DELETE: t('changeHistory.subTableRowDelete'),
      PROCESS_INITIATION: t('changeHistory.processInitiation'),
      RECORD_NOTE_ADD: t('changeHistory.recordNoteAdd'),
      RECORD_NOTE_UPDATE: t('changeHistory.recordNoteUpdate'),
      RECORD_NOTE_DELETE: t('changeHistory.recordNoteDelete'),
      CLAIM: t('changeHistory.claim'),
      UNCLAIM: t('changeHistory.unclaim'),
      FORCE_UNCLAIM: t('changeHistory.forceUnclaim'),
      REASSIGN: t('changeHistory.reassign'),
    }
    return map[changeType] || changeType
  }

  function getChangeTypeTag(changeType: string): 'success' | 'warning' | 'danger' | 'info' {
    const map: Record<string, 'success' | 'warning' | 'danger' | 'info'> = {
      FIELD_UPDATE: 'info',
      SUB_TABLE_ROW_ADD: 'success',
      SUB_TABLE_ROW_UPDATE: 'warning',
      SUB_TABLE_ROW_DELETE: 'danger',
      PROCESS_INITIATION: 'success',
      RECORD_NOTE_ADD: 'success',
      RECORD_NOTE_UPDATE: 'warning',
      RECORD_NOTE_DELETE: 'danger',
      CLAIM: 'success',
      UNCLAIM: 'warning',
      FORCE_UNCLAIM: 'danger',
      REASSIGN: 'warning',
    }
    return map[changeType] || 'info'
  }

  return {
    shortId,
    resolveOperator,
    humanizeStageKey,
    resolveStageDisplay,
    resolveStageTooltip,
    resolveTaskDisplayLabel,
    batchHeaderFields,
    fieldLocationLabel,
    formatDisplayValue,
    formatTimestamp,
    getChangeTypeLabel,
    getChangeTypeTag,
  }
}
