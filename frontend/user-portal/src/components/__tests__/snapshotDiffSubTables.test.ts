import { describe, expect, it } from 'vitest'
import type { FormField } from '../formRendererHelpers'
import { buildSnapshotSubTableDiffGroups } from '../snapshotDiffSubTableGroups'
import {
  buildSnapshotSubTableSections,
  collectSnapshotSubTableTargets,
  formatSnapshotSubTableCell,
  snapshotSubTableColumns,
  type SnapshotSubTableBindingSource,
} from '../snapshotDiffSubTables'

describe('snapshotDiffSubTables', () => {
  const fields: FormField[] = [
    { key: 'I', label: 'Meeting Name', type: 'input' },
    { key: '__subTable_50544', label: '__subTable_50544', type: 'subTable', _bindingId: 50544 },
    { key: '__subTable_50547', label: 'Inline Form', type: 'inlineSubForm', _bindingId: 50547 },
  ]

  const meetingBindings: SnapshotSubTableBindingSource[] = [
    {
      bindingId: 50544,
      tableName: 'subtable',
      designerTableName: 'subtable',
      columns: [
        { field: 'name', label: 'Name', type: 'input' },
        { field: 'linkForm:-90', label: 'Link Form', type: 'linkForm' },
      ],
    },
    {
      bindingId: 50547,
      tableName: 'people',
      designerTableName: 'people',
      columns: [
        { field: 'name', label: 'Name', type: 'input' },
        { field: 'test', label: 'Test', type: 'input' },
      ],
    },
  ]

  it('collects sub-table widgets by store key without using underscore keys as titles', () => {
    const targets = collectSnapshotSubTableTargets(fields, meetingBindings)
    expect(targets).toEqual([
      { storeKey: 'dw:subtable', fallbackLabel: '' },
      { storeKey: 'dw:people', fallbackLabel: 'Inline Form' },
    ])
  })

  it('collapses two form widgets that bind the same designer table', () => {
    const siblingFields: FormField[] = [
      { key: 'a', label: '', type: 'subTable', _bindingId: 1141 },
      { key: 'b', label: '', type: 'subTable', _bindingId: 1128 },
    ]
    const targets = collectSnapshotSubTableTargets(siblingFields, [
      { bindingId: 1141, designerTableName: 'atm_correspondence', tableName: 'ATM Correspondence' },
      { bindingId: 1128, designerTableName: 'atm_correspondence', tableName: 'ATM Correspondence' },
    ])
    expect(targets).toEqual([
      { storeKey: 'dw:atm_correspondence', fallbackLabel: '' },
    ])
  })

  it('builds sections from canonical dw: keys, not numeric binding ids', () => {
    const sections = buildSnapshotSubTableSections(
      fields,
      {
        I: '1',
        __subTables__: {
          'dw:subtable': [{ name: 'liam', test: '1', id: 'uuid-1', __subTables__: { 'dw:people': [] } }],
          'dw:people': [{ name: '1', test: '1', sub_task_id: 'Test-000008' }],
        },
      },
      meetingBindings,
    )
    expect(sections.map(s => s.storeKey)).toEqual(['dw:subtable', 'dw:people'])
    expect(sections.map(s => s.tableLabel)).toEqual(['subtable', 'people'])
    expect(sections[0].columns.map(c => c.label)).toEqual(['Name'])
    expect(sections[0].snapshotRows[0].name).toBe('liam')
    expect(sections[1].snapshotRows[0].test).toBe('1')
  })

  it('ignores leftover bindingId keys when the canonical slice exists', () => {
    const sections = buildSnapshotSubTableSections(
      [{ key: 'tx', label: '', type: 'subTable', _bindingId: 1135 }],
      {
        __subTables__: {
          1135: [{ arn: 'stale' }],
          'dw:atm_transaction': [{ arn: 'canonical' }],
        },
      },
      [{
        bindingId: 1135,
        designerTableName: 'atm_transaction',
        tableName: 'ATM Transaction',
        bindingType: 'SUB',
        columns: [{ field: 'arn', label: 'ARN' }],
      }],
    )
    expect(sections).toHaveLength(1)
    expect(sections[0].storeKey).toBe('dw:atm_transaction')
    expect(sections[0].snapshotRows[0].arn).toBe('canonical')
  })

  it('does not read deprecated numeric bindingId keys', () => {
    const sections = buildSnapshotSubTableSections(
      [{ key: 'tx', label: '', type: 'subTable', _bindingId: 1135 }],
      { __subTables__: { 1135: [{ arn: 'legacy' }] } },
      [{
        bindingId: 1135,
        designerTableName: 'atm_transaction',
        tableName: 'ATM Transaction',
        bindingType: 'SUB',
      }],
    )
    expect(sections).toEqual([])
  })

  it('appends snapshot bag tables that are not placed on the form, using designer table names', () => {
    const sections = buildSnapshotSubTableSections(
      [{ key: 'I', label: 'Meeting Name', type: 'input' }],
      {
        I: '1',
        __subTables__: {
          'dw:attachment': [{ name: 'file-a.pdf' }],
        },
      },
      [{
        bindingId: 50542,
        designerTableName: 'attachment',
        tableName: 'attachment',
        columns: [{ field: 'name', label: 'Name' }],
      }],
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
          'rt:hmdc_case_stage': [],
          'dw:atm_correspondence': [{ comment: 'first' }, { comment: 'second' }],
          'dw:sub-table': [],
        },
      },
      [
        {
          bindingId: 9001,
          tableName: 'HMDC Case Stage',
          relationTableId: 1,
          relationTableName: 'hmdc_case_stage',
          bindingType: 'RELATED',
          tableType: 'RELATION',
        },
        {
          bindingId: 1141,
          tableId: 50310,
          designerTableName: 'atm_correspondence',
          tableName: 'ATM Correspondence',
          bindingType: 'SUB',
          tableType: 'SUB',
        },
        {
          bindingId: 1128,
          tableId: 50310,
          designerTableName: 'atm_correspondence',
          tableName: 'ATM Correspondence',
          bindingType: 'SUB',
          tableType: 'SUB',
        },
        {
          bindingId: 1999,
          designerTableName: 'sub-table',
          tableName: 'Sub-table',
          bindingType: 'SUB',
          tableType: 'SUB',
        },
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
          'dw:atm_correspondence': [
            { correspondence_id: 'r1', comment: 'first', type: { dropdown_name: 'Email' } },
            { correspondence_id: 'r2', comment: 'second', type: { dropdown_name: 'Letter' } },
          ],
        },
      },
      {
        __subTables__: {
          'dw:atm_correspondence': [
            { correspondence_id: 'r1', comment: 'first-updated', type: { dropdown_name: 'Email' } },
            { correspondence_id: 'r2', comment: 'second', type: { dropdown_name: 'Letter' } },
          ],
        },
      },
      [
        {
          bindingId: 1141,
          tableId: 50310,
          designerTableName: 'atm_correspondence',
          tableName: 'ATM Correspondence',
          bindingType: 'SUB',
          primaryKeyFields: ['correspondence_id'],
          columns: [
            { field: 'comment', label: 'Comment', type: 'input' },
            { field: 'type', label: 'Type', type: 'lookup' },
          ],
        },
      ],
    )
    expect(groups).toHaveLength(1)
    expect(groups[0].storeKey).toBe('dw:atm_correspondence')
    expect(groups[0].tableLabel).toBe('ATM Correspondence')
    expect(groups[0].blocks).toHaveLength(2)
    expect(groups[0].blocks[0].preview).toBe('first')
    expect(groups[0].blocks[0].rows.map(r => r.label)).toEqual(['Comment', 'Type'])
    expect(groups[0].blocks[0].rows.find(r => r.label === 'Comment')?.changed).toBe(true)
    expect(groups[0].blocks[0].rows.find(r => r.label === 'Type')?.changed).toBe(false)
    expect(groups[0].blocks[1].rows.every(r => !r.changed)).toBe(true)
  })

  it('compares live rows from the same canonical key when two form bindings share the table', () => {
    const groups = buildSnapshotSubTableDiffGroups(
      [{ key: 'corr', label: '', type: 'subTable', _bindingId: 1141 }],
      {
        __subTables__: {
          'dw:atm_correspondence': [{ correspondence_id: 'r1', comment: 'first' }],
        },
      },
      {
        __subTables__: {
          'dw:atm_correspondence': [{ correspondence_id: 'r1', comment: 'first' }],
        },
      },
      [
        {
          bindingId: 1141,
          tableId: 50310,
          designerTableName: 'atm_correspondence',
          tableName: 'ATM Correspondence',
          bindingType: 'SUB',
          primaryKeyFields: ['correspondence_id'],
          columns: [{ field: 'comment', label: 'Comment', type: 'input' }],
        },
        {
          bindingId: 1128,
          tableId: 50310,
          designerTableName: 'atm_correspondence',
          tableName: 'ATM Correspondence',
          bindingType: 'SUB',
          columns: [{ field: 'comment', label: 'Comment', type: 'input' }],
        },
      ],
    )
    expect(groups[0].blocks[0].rows[0]?.changed).toBe(false)
    expect(groups[0].blocks[0].rows[0]?.liveValue).toBe('first')
  })

  it('renders ATM completed-snapshot slices that only exist under dw: keys', () => {
    const sections = buildSnapshotSubTableSections(
      [
        { key: 'case_number', label: 'Case Number', type: 'input' },
        { key: '__subTable_tx', label: '', type: 'subTable', _bindingId: 1135 },
        { key: '__subTable_corr', label: '', type: 'subTable', _bindingId: 1141 },
      ],
      {
        case_number: 'ATM-DC-PW-000002',
        __subTables__: {
          'dw:atm_transaction': [{ arn: '1', row_id: 'ATM-DC-PW-TRANS-000005' }],
          'dw:atm_correspondence': [
            { correspondence_id: 'Corr-1' },
            { correspondence_id: 'Corr-2' },
            { correspondence_id: 'Corr-3' },
            { correspondence_id: 'Corr-4' },
          ],
          'rt:hmdc_dropdown': [],
        },
      },
      [
        {
          bindingId: 1135,
          designerTableName: 'atm_transaction',
          tableName: 'ATM Transaction',
          bindingType: 'SUB',
          columns: [{ field: 'arn', label: 'ARN' }],
        },
        {
          bindingId: 1141,
          designerTableName: 'atm_correspondence',
          tableName: 'ATM Correspondence',
          bindingType: 'SUB',
          columns: [{ field: 'correspondence_id', label: 'Correspondence ID' }],
        },
        {
          bindingId: 9001,
          relationTableId: 8,
          relationTableName: 'hmdc_dropdown',
          tableName: 'HMDC Dropdown',
          bindingType: 'RELATED',
          tableType: 'RELATION',
        },
      ],
    )
    expect(sections.map(s => s.storeKey)).toEqual(['dw:atm_transaction', 'dw:atm_correspondence'])
    expect(sections[0].snapshotRows).toHaveLength(1)
    expect(sections[1].snapshotRows).toHaveLength(4)
  })
})
