import type { FormField, FormTab } from './formRendererHelpers'
import {
  isSnapshotValueChanged,
  type DiffRow,
} from './snapshotDiffHelpers'
import {
  buildSnapshotSubTableSections,
  formatSnapshotSubTableCell,
  snapshotSubTableRows,
  snapshotTableSiblingBindingIds,
  type SnapshotSubTableBindingSource,
  type SnapshotSubTableColumn,
  type SnapshotSubTableSection,
} from './snapshotDiffSubTables'
import { readRowIdentityToken } from '@/utils/subTableRowIdentity'

export interface SnapshotSubTableDiffBlock {
  rowIndex: number
  preview: string
  rows: DiffRow[]
}

export interface SnapshotSubTableDiffGroup {
  bindingId: number
  tableLabel: string
  blocks: SnapshotSubTableDiffBlock[]
}

function columnAsField(col: SnapshotSubTableColumn): FormField {
  return { key: col.field, label: col.label, type: col.type || 'text' }
}

/**
 * 这个 section 所属表配置的主键列。快照行与实时行按身份配对，身份来自配置而非列名猜测。
 */
function sectionPrimaryKeyFields(
  section: SnapshotSubTableSection,
  bindings?: SnapshotSubTableBindingSource[],
): string[] | null {
  const self = (bindings || []).find(b => Number(b.bindingId) === Number(section.bindingId))
  return self?.primaryKeyFields ?? null
}

function liveRowsForSection(
  liveValues: Record<string, unknown>,
  section: SnapshotSubTableSection,
  bindings?: SnapshotSubTableBindingSource[],
): Record<string, unknown>[] {
  const seen = new Set<string>()
  const rows: Record<string, unknown>[] = []
  const pk = sectionPrimaryKeyFields(section, bindings)
  for (const bindingId of snapshotTableSiblingBindingIds(section.bindingId, bindings)) {
    for (const row of snapshotSubTableRows(liveValues, bindingId)) {
      const token = readRowIdentityToken(row, pk) ?? `idx:${bindingId}:${rows.length}`
      if (seen.has(token)) continue
      seen.add(token)
      rows.push(row)
    }
  }
  return rows
}

function matchLiveRow(
  snapRow: Record<string, unknown>,
  liveMap: Map<string, Record<string, unknown>>,
  liveRows: Record<string, unknown>[],
  snapshotCount: number,
  primaryKeyFields?: string[] | null,
): Record<string, unknown> | undefined {
  const token = readRowIdentityToken(snapRow, primaryKeyFields)
  if (token) return liveMap.get(token)
  // FALLBACK(ux): one snapshot row and one live row on the same table — pair them.
  if (snapshotCount === 1 && liveRows.length === 1) return liveRows[0]
  return undefined
}

function snapshotRowPreview(
  row: Record<string, unknown>,
  columns: SnapshotSubTableColumn[],
): string {
  for (const col of columns) {
    const text = formatSnapshotSubTableCell(row, col.field, col.type)
    if (text && text !== '-') return text
  }
  return ''
}

function snapshotRowToDiffRows(
  section: SnapshotSubTableSection,
  snapRow: Record<string, unknown>,
  liveRow: Record<string, unknown> | undefined,
  rowIndex: number,
): DiffRow[] {
  return section.columns.map(col => {
    const field = columnAsField(col)
    const sv = snapRow[col.field]
    const lv = liveRow ? liveRow[col.field] : undefined
    return {
      key: `${section.bindingId}:${rowIndex}:${col.field}`,
      label: col.label,
      snapshotValue: sv,
      liveValue: lv,
      changed: isSnapshotValueChanged(sv, lv, field),
    }
  })
}

export function buildSnapshotSubTableDiffGroups(
  fields: FormField[],
  snapshotValues: Record<string, unknown>,
  liveValues: Record<string, unknown>,
  bindings?: SnapshotSubTableBindingSource[],
  tabs?: FormTab[],
  fieldsAfterTabs?: FormField[],
): SnapshotSubTableDiffGroup[] {
  return buildSnapshotSubTableSections(
    fields, snapshotValues, bindings, tabs, fieldsAfterTabs,
  ).map(section => {
    const sectionPk = sectionPrimaryKeyFields(section, bindings)
    const liveRows = liveRowsForSection(liveValues, section, bindings)
    const liveMap = new Map<string, Record<string, unknown>>()
    for (const row of liveRows) {
      const token = readRowIdentityToken(row, sectionPk)
      if (token && !liveMap.has(token)) liveMap.set(token, row)
    }
    const blocks = section.snapshotRows.map((snapRow, rowIndex) => {
      const liveRow = matchLiveRow(
        snapRow, liveMap, liveRows, section.snapshotRows.length, sectionPk,
      )
      return {
        rowIndex,
        preview: snapshotRowPreview(snapRow, section.columns),
        rows: snapshotRowToDiffRows(section, snapRow, liveRow, rowIndex),
      }
    })
    return { bindingId: section.bindingId, tableLabel: section.tableLabel, blocks }
  })
}
