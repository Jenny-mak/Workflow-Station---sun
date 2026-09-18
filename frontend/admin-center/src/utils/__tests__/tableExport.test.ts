import { describe, expect, it } from 'vitest'
import type { ListColumnMeta } from '@platform-shared/list/columnMeta'
import { buildTableCsv } from '@platform-shared/list/tableExport'

const columns: ListColumnMeta[] = [
  { field: 'name', label: 'Name', kind: 'TEXT', filterable: true, sortable: true, operators: ['contains'] },
  { field: 'owners', label: 'Owners', kind: 'USER', filterable: true, sortable: false, operators: ['contains'] },
  { field: 'code', label: 'Code', kind: 'TEXT', filterable: true, sortable: true, operators: ['contains'] },
]

describe('buildTableCsv', () => {
  it('exports selected fields, nested relation data, and escaped values', () => {
    const csv = buildTableCsv({
      columns,
      rows: [{
        name: 'A "quoted" value',
        owners: [{ name: 'Alice' }, { displayName: 'Bob' }],
        data: { code: 'RT-01' },
      }],
    })

    expect(csv).toBe(
      '\uFEFF"Name","Owners","Code"\r\n"A ""quoted"" value","Alice, Bob","RT-01"',
    )
  })
})
