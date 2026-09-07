import { ADVANCED_UPLOAD_TYPE, isAnyUploadType, NATIVE_UPLOAD_TYPE } from './uploadRuleType'

/**
 * Runtime / preview list views are stored separately from the sub-form canvas.
 * Advanced Upload often lives only on the canvas — union those FILE columns
 * onto the list without duplicating an already-present field.
 */
export function unionListViewWithSubFormUploadColumns<T extends { field?: string; type?: string }>(
  listColumns: T[],
  subFormColumns: T[],
): T[] {
  const present = new Set(
    listColumns.map(col => String(col.field || '')).filter(Boolean),
  )
  const extras: T[] = []
  for (const col of subFormColumns) {
    const field = String(col.field || '')
    if (!field || present.has(field)) continue
    if (!isAnyUploadType(col.type)) continue
    extras.push(
      col.type === ADVANCED_UPLOAD_TYPE
        ? { ...col, type: NATIVE_UPLOAD_TYPE }
        : col,
    )
    present.add(field)
  }
  return extras.length === 0 ? listColumns : [...listColumns, ...extras]
}
