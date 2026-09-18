import type { ListColumnMeta } from './columnMeta'

type TableRow = Record<string, unknown>

function csvCell(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (Array.isArray(value)) {
    value = value.map((item) => {
      if (item && typeof item === 'object') {
        const record = item as TableRow
        return record.label ?? record.displayName ?? record.name ?? record.id ?? JSON.stringify(item)
      }
      return item
    }).join(', ')
  } else if (typeof value === 'object') {
    value = JSON.stringify(value)
  }
  return `"${String(value).replace(/"/g, '""')}"`
}

function rowValue(row: TableRow, field: string): unknown {
  if (field in row) return row[field]
  const nestedData = row.data
  if (nestedData && typeof nestedData === 'object') {
    return (nestedData as TableRow)[field]
  }
  return undefined
}

function safeFilename(value: string): string {
  const normalized = value.trim().replace(/[^a-zA-Z0-9._-]+/g, '-')
  return normalized || 'export'
}

export function buildTableCsv<T extends object>(options: {
  rows: T[]
  columns: ListColumnMeta[]
}): string {
  const headers = options.columns.map((column) => csvCell(column.label))
  const records = options.rows.map((row) =>
    options.columns.map((column) => csvCell(rowValue(row as TableRow, column.field))).join(','),
  )
  return `\uFEFF${[headers.join(','), ...records].join('\r\n')}`
}

export function exportTableCsv<T extends object>(options: {
  rows: T[]
  columns: ListColumnMeta[]
  filename: string
}): void {
  const blob = new Blob([buildTableCsv(options)], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${safeFilename(options.filename)}-${new Date().toISOString().slice(0, 10)}.csv`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}
