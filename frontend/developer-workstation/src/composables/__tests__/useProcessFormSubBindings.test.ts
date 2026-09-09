import { describe, expect, it } from 'vitest'
import {
  extractMappableFields,
  loadMainFieldOptions,
  type SubTableFieldOption,
} from '../email/useProcessFormSubBindings'
import type { TableBinding, TableDefinition } from '@/api/functionUnit'

function field(name: string, pk = false, dataType = 'VARCHAR'): TableDefinition['fieldDefinitions'][number] {
  return {
    fieldName: name,
    displayName: name.replace('_', ' '),
    isPrimaryKey: pk,
    dataType,
  } as TableDefinition['fieldDefinitions'][number]
}

describe('useProcessFormSubBindings field mapping', () => {
  it('includes primary key on main table when includePrimaryKey is true', () => {
    const table: TableDefinition = {
      id: 113,
      tableName: 'HMDC_Case',
      tableType: 'MAIN',
      fieldDefinitions: [field('case_number', true), field('legal_hold')],
    } as TableDefinition

    const options = extractMappableFields(table, true)
    expect(options.map((o: SubTableFieldOption) => o.fieldName)).toEqual(['case_number', 'legal_hold'])
  })

  it('excludes computed and audit columns from email mapping', () => {
    const table: TableDefinition = {
      id: 113,
      tableName: 'HMDC_Case',
      tableType: 'MAIN',
      fieldDefinitions: [
        field('title'),
        { ...field('total'), isComputed: true },
        field('created_at'),
        field('created_by'),
      ],
    } as TableDefinition

    const options = extractMappableFields(table, true)
    expect(options.map((o) => o.fieldName)).toEqual(['title'])
  })

  it('excludes primary key on sub-table bindings by default', () => {
    const table: TableDefinition = {
      id: 271,
      tableName: 'HMDC_Transaction',
      tableType: 'SUB',
      fieldDefinitions: [field('id', true), field('card_number')],
    } as TableDefinition

    const options = extractMappableFields(table)
    expect(options.map((o) => o.fieldName)).toEqual(['card_number'])
  })

  it('loadMainFieldOptions keeps business primary key from PRIMARY binding', () => {
    const bindings: TableBinding[] = [
      { id: 1, tableId: 113, bindingType: 'PRIMARY' } as TableBinding,
    ]
    const tableById = new Map<number, TableDefinition>([
      [
        113,
        {
          id: 113,
          tableName: 'HMDC_Case',
          tableType: 'MAIN',
          fieldDefinitions: [field('case_number', true), field('legal_hold')],
        } as TableDefinition,
      ],
    ])

    const mainOptions = loadMainFieldOptions(bindings, tableById)
    expect(mainOptions.map((o) => o.fieldName)).toContain('case_number')
  })

  it('keeps FILE dataType on mappable main fields', () => {
    const table: TableDefinition = {
      id: 113,
      tableName: 'HMDC_Case',
      tableType: 'MAIN',
      fieldDefinitions: [field('quote_files', false, 'FILE'), field('title')],
    } as TableDefinition

    const options = extractMappableFields(table, true)
    expect(options.find((o) => o.fieldName === 'quote_files')?.dataType).toBe('FILE')
  })
})
