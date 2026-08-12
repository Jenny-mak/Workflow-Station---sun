import { describe, expect, it } from 'vitest'
import {
  applyGridRuntime,
  applyGroupBy,
  applyGroupHeadersWithCounts,
  activeFiltersForQuery,
  clampColumnWidth,
  createDefaultGridRuntime,
  moveColumn,
  setColumnWidth,
} from '../mainTableViewGridRuntime'

describe('mainTableViewGridRuntime', () => {
  const rows = [
    { processInstanceId: '1', values: { name: 'Beta', status: 'Open' } },
    { processInstanceId: '2', values: { name: 'Alpha', status: 'Closed' } },
  ]

  it('sorts rows ascending by field', () => {
    const state = createDefaultGridRuntime()
    state.sort = { fieldName: 'name', direction: 'ASC' }
    const sorted = applyGridRuntime(rows, state)
    expect(sorted[0].values.name).toBe('Alpha')
  })

  it('filters rows by contains operator', () => {
    const state = createDefaultGridRuntime()
    state.filters.status = { operator: 'eq', value: 'Open' }
    const filtered = applyGridRuntime(rows, state)
    expect(filtered).toHaveLength(1)
    expect(filtered[0].processInstanceId).toBe('1')
  })

  it('groups rows with header rows', () => {
    const grouped = applyGroupBy(rows, 'status')
    expect(grouped[0]).toMatchObject({ _isGroupHeader: true, _groupLabel: 'Open' })
  })

  it('applyGroupHeadersWithCounts uses full-set counts on a page slice', () => {
    const page = [
      { processInstanceId: '3', values: { name: 'C', status: 'Open' } },
      { processInstanceId: '4', values: { name: 'D', status: 'Closed' } },
    ]
    const display = applyGroupHeadersWithCounts(page, 'status', { Open: 12, Closed: 3 })
    expect(display[0]).toMatchObject({ _isGroupHeader: true, _groupLabel: 'Open', _groupCount: 12 })
    expect(display[2]).toMatchObject({ _isGroupHeader: true, _groupLabel: 'Closed', _groupCount: 3 })
  })

  it('activeFiltersForQuery drops empty value filters', () => {
    const active = activeFiltersForQuery({
      a: { operator: 'contains', value: '  ' },
      b: { operator: 'isNull', value: '' },
      c: { operator: 'eq', value: 'x' },
    })
    expect(Object.keys(active).sort()).toEqual(['b', 'c'])
  })

  it('moveColumn swaps order', () => {
    const state = createDefaultGridRuntime()
    state.columnOrder = ['a', 'b', 'c']
    moveColumn(state, 'b', 'left')
    expect(state.columnOrder).toEqual(['b', 'a', 'c'])
  })

  it('clampColumnWidth enforces min and max', () => {
    expect(clampColumnWidth(30)).toBe(60)
    expect(clampColumnWidth(900)).toBe(600)
  })

  it('setColumnWidth stores clamped width', () => {
    const state = createDefaultGridRuntime()
    setColumnWidth(state, 'name', 180)
    expect(state.columnWidths.name).toBe(180)
  })
})
