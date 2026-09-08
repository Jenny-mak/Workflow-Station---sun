import { describe, expect, it } from 'vitest'
import type { FormField } from '../formRendererHelpers'
import { buildSnapshotSubTableDiffGroups } from '../snapshotDiffSubTableGroups'
import {
  buildSnapshotSubTableSections,
  collectSnapshotSubTableTargets,
  formatSnapshotSubTableCell,
  snapshotSubTableColumns,
} from '../snapshotDiffSubTables'

describe('snapshotDiffSubTables', () => {
  const fields: FormField[] = [
    { key: 'I', label: 'Meeting Name', type: 'input' },
    { key: '__subTable_50544', label: '__subTable_50544', type: 'subTable', _bindingId: 50544 },
    { key: '__subTable_50547', label: 'Inline Form', type: 'inlineSubForm', _bindingId: 50547 },
  ]

  it('collects sub-table widgets by binding id without using underscore keys as titles', () => {
    const targets = collectSnapshotSubTableTargets(fields)
    expect(targets).toEqual([
      { bindingId: 50544, fallbackLabel: '' },
      { bindingId: 50547, fallbackLabel: 'Inline Form' },
    ])
  })

  it('builds sections with table display names, column labels, and snapshot rows', () => {
    const sections = buildSnapshotSubTableSections(
      fields,
      {
        I: '1',
        __subTables__: {
          50544: [{ name: 'liam', test: '1', id: 'uuid-1', __subTables__: { 50547: [] } }],
          50547: [{ name: '1', test: '1', sub_task_id: 'Test-000008' }],
        },
      },
      [
        {
          bindingId: 50544,
          tableName: 'subtable',
          columns: [
            { field: 'name', label: 'Name', type: 'input' },
            { field: 'linkForm:-90', label: 'Link Form', type: 'linkForm' },
          ],
        },
        {
          bindingId: 50547,
          tableName: 'people',
          columns: [
            { field: 'name', label: 'Name', type: 'input' },
            { field: 'test', label: 'Test', type: 'input' },
          ],
        },
      ],
    )
    expect(sections.map(s => s.tableLabel)).toEqual(['subtable', 'people'])
    expect(sections[0].columns.map(c => c.label)).toEqual(['Name'])
    expect(sections[0].snapshotRows[0].name).toBe('liam')
    expect(sections[1].snapshotRows[0].test).toBe('1')
  })

  it('appends snapshot bag tables that are not placed on the form, using designer table names', () => {
    const sections = buildSnapshotSubTableSections(
      [{ key: 'I', label: 'Meeting Name', type: 'input' }],
      {
        I: '1',
        __subTables__: {
          50542: [{ name: 'file-a.pdf' }],
        },
      },
      [{ bindingId: 50542, tableName: 'attachment', columns: [{ field: 'name', label: 'Name' }] }],
    )
    expect(sections.map(s => s.tableLabel)).toEqual(['attachment'])
    expect(sections[0].snapshotRows[0].name).toBe('file-a.pdf')
  })

  it('skips linkForm columns and formats user-like cells as display names', () => {
    const cols = snapshotSubTableColumns({
      bindingId: 1,
      columns: [
        { field: 'assignee', label: 'Assignee', type: 'user' },
        { field: 'open', label: 'Details', type: 'linkForm' },
      ],
    })
    expect(cols.map(c => c.field)).toEqual(['assignee'])
    expect(formatSnapshotSubTableCell(
      { assignee: { id: 'u1', display_name: 'liam', username: '123456' } },
      'assignee',
    )).toBe('liam')
    expect(formatSnapshotSubTableCell(
      { file: '/api/v1/upload/files/bc7a8506.jpg?originalName=MSI_MEG_GODLIKE.jpg' },
      'file',
    )).toBe('MSI_MEG_GODLIKE.jpg')
  })

  it('omits RELATED lookup catalogs, empty grids, and duplicate table names', () => {
    const sections = buildSnapshotSubTableSections(
      [
        { key: 'stage', label: '', type: 'subTable', _bindingId: 9001 },
        { key: 'corr_a', label: '', type: 'subTable', _bindingId: 1141 },
        { key: 'corr_b', label: '', type: 'subTable', _bindingId: 1128 },
        { key: 'empty_sub', label: '', type: 'subTable', _bindingId: 1999 },
      ],
      {
        __subTables__: {
          9001: [],
          1141: [{ comment: 'first' }, { comment: 'second' }],
          1128: [{ comment: 'first' }],
          1999: [],
        },
      },
      [
        { bindingId: 9001, tableName: 'HMDC Case Stage', bindingType: 'RELATED', tableType: 'RELATION' },
        { bindingId: 1141, tableId: 50310, tableName: 'ATM Correspondence', bindingType: 'SUB', tableType: 'SUB' },
        { bindingId: 1128, tableId: 50310, tableName: 'ATM Correspondence', bindingType: 'SUB', tableType: 'SUB' },
        { bindingId: 1999, tableName: 'Sub-table', bindingType: 'SUB', tableType: 'SUB' },
      ],
    )
    expect(sections.map(s => s.tableLabel)).toEqual(['ATM Correspondence'])
    expect(sections[0].snapshotRows).toHaveLength(2)
  })

  it('flattens sub-table rows into Field / Snapshot / Current / Status rows like the main form', () => {
    const groups = buildSnapshotSubTableDiffGroups(
      [{ key: 'corr', label: '', type: 'subTable', _bindingId: 1141 }],
      {
        __subTables__: {
          1141: [
            { row_id: 'r1', comment: 'first', type: { dropdown_name: 'Email' } },
            { row_id: 'r2', comment: 'second', type: { dropdown_name: 'Letter' } },
          ],
        },
      },
      {
        __subTables__: {
          1141: [
            { row_id: 'r1', comment: 'first-updated', type: { dropdown_name: 'Email' } },
            { row_id: 'r2', comment: 'second', type: { dropdown_name: 'Letter' } },
          ],
        },
      },
      [
        {
          bindingId: 1141,
          tableId: 50310,
          tableName: 'ATM Correspondence',
          bindingType: 'SUB',
          // This table really is keyed by `row_id` in Table Design. Snapshot and live rows pair by
          // the identity the table DECLARES; a bare column named `row_id` is a business value until
          // configuration says it is the key.
          primaryKeyFields: ['row_id'],
          columns: [
            { field: 'comment', label: 'Comment', type: 'input' },
            { field: 'type', label: 'Type', type: 'lookup' },
          ],
        },
      ],
    )
    expect(groups).toHaveLength(1)
    expect(groups[0].tableLabel).toBe('ATM Correspondence')
    expect(groups[0].blocks).toHaveLength(2)
    expect(groups[0].blocks[0].preview).toBe('first')
    expect(groups[0].blocks[0].rows.map(r => r.label)).toEqual(['Comment', 'Type'])
    expect(groups[0].blocks[0].rows.find(r => r.label === 'Comment')?.changed).toBe(true)
    expect(groups[0].blocks[0].rows.find(r => r.label === 'Type')?.changed).toBe(false)
    expect(groups[0].blocks[1].rows.every(r => !r.changed)).toBe(true)
  })

  it('compares live rows from a sibling binding of the same table', () => {
    const groups = buildSnapshotSubTableDiffGroups(
      [{ key: 'corr', label: '', type: 'subTable', _bindingId: 1141 }],
      {
        __subTables__: {
          1141: [{ row_id: 'r1', comment: 'first' }],
        },
      },
      {
        __subTables__: {
          1128: [{ row_id: 'r1', comment: 'first' }],
        },
      },
      [
        { bindingId: 1141, tableId: 50310, tableName: 'ATM Correspondence', bindingType: 'SUB',
          columns: [{ field: 'comment', label: 'Comment', type: 'input' }] },
        { bindingId: 1128, tableId: 50310, tableName: 'ATM Correspondence', bindingType: 'SUB',
          columns: [{ field: 'comment', label: 'Comment', type: 'input' }] },
      ],
    )
    expect(groups[0].blocks[0].rows[0]?.changed).toBe(false)
    expect(groups[0].blocks[0].rows[0]?.liveValue).toBe('first')
  })
})
