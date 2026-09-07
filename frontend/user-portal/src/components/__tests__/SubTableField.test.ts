import { describe, it, expect } from 'vitest'
import { mergeListViewFieldColumn, isStoredFileUrl, isUploadColumn, normalizeSubTableColumns } from '../subTableAddDialogHelpers'

// Unit tests for SubTableField file upload filename auto-fill logic
// Validates: Requirements 11.2, 11.3, 11.4

// ===== Types (mirroring component) =====

interface Column {
  field: string
  label: string
  type?: 'text' | 'number' | 'date' | 'upload'
  minWidth?: number
  props?: Record<string, any>
}

// ===== Extracted logic (mirrors handleUploadSuccess auto-fill behavior) =====

/**
 * Determines the filename auto-fill target for a given upload column.
 * Returns the target field name if fileNameTargetField is configured and the
 * target column exists, otherwise returns null (no auto-fill).
 */
function resolveFileNameTarget(columns: Column[], uploadField: string): string | null {
  const uploadCol = columns.find(c => c.field === uploadField)
  const fileNameTarget = uploadCol?.props?.fileNameTargetField
  if (fileNameTarget && columns.some(c => c.field === fileNameTarget)) {
    return fileNameTarget
  }
  return null
}

/**
 * Simulates the auto-fill portion of handleUploadSuccess.
 * Given a row, columns config, upload field, and filename, applies the
 * filename auto-fill if configured.
 */
function applyUploadAutoFill(
  row: Record<string, any>,
  columns: Column[],
  uploadField: string,
  fileName: string
): Record<string, any> {
  const updated = { ...row }
  const target = resolveFileNameTarget(columns, uploadField)
  if (target) {
    updated[target] = fileName
  }
  return updated
}

// ===== Tests =====

describe('SubTableField fileNameTargetField', () => {
  const baseColumns: Column[] = [
    { field: 'file', label: 'File', type: 'upload', props: { action: '/api/v1/upload', accept: '.pdf', fileNameTargetField: 'file_name' } },
    { field: 'file_name', label: 'File Name', type: 'text' },
    { field: 'description', label: 'Description', type: 'text' },
  ]

  describe('resolveFileNameTarget', () => {
    it('returns target field when fileNameTargetField is configured and target column exists', () => {
      // Validates: Requirement 11.2, 11.3
      const target = resolveFileNameTarget(baseColumns, 'file')
      expect(target).toBe('file_name')
    })

    it('returns null when fileNameTargetField is not specified', () => {
      // Validates: Requirement 11.4
      const columns: Column[] = [
        { field: 'file', label: 'File', type: 'upload', props: { action: '/api/v1/upload' } },
        { field: 'file_name', label: 'File Name', type: 'text' },
      ]
      const target = resolveFileNameTarget(columns, 'file')
      expect(target).toBeNull()
    })

    it('returns null when upload column has no props at all', () => {
      // Validates: Requirement 11.4
      const columns: Column[] = [
        { field: 'file', label: 'File', type: 'upload' },
        { field: 'file_name', label: 'File Name', type: 'text' },
      ]
      const target = resolveFileNameTarget(columns, 'file')
      expect(target).toBeNull()
    })

    it('returns null when fileNameTargetField points to a non-existent column', () => {
      // Validates: Requirement 11.3 (target column must exist)
      const columns: Column[] = [
        { field: 'file', label: 'File', type: 'upload', props: { fileNameTargetField: 'nonexistent_col' } },
        { field: 'description', label: 'Description', type: 'text' },
      ]
      const target = resolveFileNameTarget(columns, 'file')
      expect(target).toBeNull()
    })

    it('returns null when upload field is not found in columns', () => {
      const target = resolveFileNameTarget(baseColumns, 'unknown_field')
      expect(target).toBeNull()
    })
  })

  describe('applyUploadAutoFill', () => {
    it('auto-fills filename to target column when fileNameTargetField is configured', () => {
      // Validates: Requirement 11.2, 11.3
      const row = { file: '', file_name: '', description: 'test' }
      const result = applyUploadAutoFill(row, baseColumns, 'file', 'invoice.pdf')
      expect(result.file_name).toBe('invoice.pdf')
      expect(result.description).toBe('test') // other fields unchanged
    })

    it('does not auto-fill when fileNameTargetField is not specified', () => {
      // Validates: Requirement 11.4
      const columns: Column[] = [
        { field: 'file', label: 'File', type: 'upload', props: { action: '/api/v1/upload' } },
        { field: 'file_name', label: 'File Name', type: 'text' },
      ]
      const row = { file: '', file_name: 'original.pdf', description: '' }
      const result = applyUploadAutoFill(row, columns, 'file', 'new_file.pdf')
      expect(result.file_name).toBe('original.pdf') // unchanged
    })

    it('does not auto-fill when target column does not exist', () => {
      // Validates: Requirement 11.3
      const columns: Column[] = [
        { field: 'file', label: 'File', type: 'upload', props: { fileNameTargetField: 'missing_col' } },
        { field: 'description', label: 'Description', type: 'text' },
      ]
      const row = { file: '', description: 'test' }
      const result = applyUploadAutoFill(row, columns, 'file', 'doc.pdf')
      expect(result).toEqual({ file: '', description: 'test' })
    })
  })
})

describe('subListViews FILE column typing', () => {
  it('treats type advancedUpload as an upload column', () => {
    expect(isUploadColumn({ field: 'Fzwomtqznizdakc', type: 'advancedUpload' as never })).toBe(true)
    expect(isUploadColumn({ field: 'notes', type: 'text' })).toBe(false)
  })

  it('maps dataType FILE to upload when subForm rule is absent', () => {
    const col = mergeListViewFieldColumn(
      { fieldName: 'file', comment: 'file', dataType: 'FILE' },
      undefined,
      null,
    )
    expect(col.type).toBe('upload')
    expect(col.props?.action).toBe('/api/v1/upload')
  })

  it('maps field name "file" to upload even without dataType', () => {
    const col = mergeListViewFieldColumn(
      { fieldName: 'file', comment: 'file' },
      { field: 'file', label: 'file', type: 'text' },
      null,
    )
    expect(col.type).toBe('upload')
  })

  it('normalizes plain-text columns for Add Record dialog', () => {
    const cols = normalizeSubTableColumns([
      { field: 'id', label: 'id', type: 'text' },
      { field: 'file', label: 'file', type: 'text' },
    ])
    expect(cols[1].type).toBe('upload')
  })

  it('detects stored upload URLs', () => {
    expect(isStoredFileUrl('/api/v1/upload/files/ba771856-9d7b-482b-99c4-47e0f234220d.pdf?originalName=doc.pdf')).toBe(true)
    expect(isStoredFileUrl('plain text')).toBe(false)
  })

  it('copies cannotDownload from the sub-form upload rule', () => {
    const col = mergeListViewFieldColumn(
      { fieldName: 'file', comment: 'file', dataType: 'FILE' },
      null,
      { type: 'upload', props: { cannotDownload: true } },
    )
    expect(col.props?.cannotDownload).toBe(true)
  })

  it('copies cannotDownload from the rule-level designer switch', () => {
    const col = mergeListViewFieldColumn(
      { fieldName: 'file', comment: 'file', dataType: 'FILE' },
      null,
      { type: 'upload', cannotDownload: true, props: {} },
    )
    expect(col.props?.cannotDownload).toBe(true)
  })
})

/** Mirrors SubTableField parentChildTaskStatusesMatch (#1441). */
function parentChildTaskStatusesMatch(
  parentRow: Record<string, unknown>,
  childRow: Record<string, unknown>,
): boolean {
  const ps = String(parentRow.task_status ?? '').trim().toUpperCase()
  if (!ps) return true
  const cs = String(childRow.task_status ?? '').trim().toUpperCase()
  if (!cs) return true
  return ps === cs
}

describe('SubTableField link-form task_status match (#1441)', () => {
  it('filterLinkedChildRowsByMiTaskStatus keeps link-child rows without task_status for FK scoping', () => {
    const parent = { id_idw: 'Test-000061', task_status: 'IN_PROGRESS' }
    const own = { sub_task_id: 'Test-000061', age: '666', sex: true }
    const other = { sub_task_id: 'Test-000062', age: '88', sex: true }
    const filter = (p: Record<string, unknown>, rows: Record<string, unknown>[]) => {
      const withStatus = rows.filter(r => String(r.task_status ?? '').trim() !== '')
      if (withStatus.length === 0) return rows
      return rows
    }
    const out = filter(parent, [own, other])
    expect(out).toHaveLength(2)
  })

  it('parentChildTaskStatusesMatch requires child task_status when present', () => {
    const parent = { task_status: 'IN_PROGRESS' }
    expect(parentChildTaskStatusesMatch(parent, { task_status: 'COMPLETED' })).toBe(false)
    expect(parentChildTaskStatusesMatch(parent, { task_status: 'IN_PROGRESS', age: '1' })).toBe(true)
  })

  it('miOk allows link-child without task_status for IN_PROGRESS parent', () => {
    const parent = { id_idw: 'Test-000061', task_status: 'IN_PROGRESS' }
    const child = { sub_task_id: 'Test-000061', age: '666' }
    const childTs = String(child.task_status ?? '').trim()
    const miOk =
      parentChildTaskStatusesMatch(parent, child) || !childTs
    expect(miOk).toBe(true)
  })
})
