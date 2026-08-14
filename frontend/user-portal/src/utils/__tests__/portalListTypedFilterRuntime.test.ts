import { describe, it, expect } from 'vitest'
import {
  createDefaultPortalListColumnState,
  formatPortalListFilterDays,
  matchPortalListFilter,
  parsePortalListFilterDays,
  pruneUnsupportedFilters,
  type PortalListColumnMeta,
} from '../portalListGridRuntime'

const dateColumn: PortalListColumnMeta = {
  field: 'startTime',
  kind: 'DATETIME',
  filterable: true,
  sortable: true,
  groupable: true,
  operators: ['on', 'before', 'after', 'between', 'isNotNull', 'isNull'],
  options: [],
}

const enumColumn: PortalListColumnMeta = {
  field: 'status',
  kind: 'ENUM',
  filterable: true,
  sortable: true,
  groupable: true,
  operators: ['eq', 'ne', 'isNotNull', 'isNull'],
  options: ['ACTIVE', 'SUSPENDED'],
}

describe('parsePortalListFilterDays', () => {
  it('reads one day, or two for between', () => {
    expect(parsePortalListFilterDays('on', '2026-03-05')).toEqual(['2026-03-05'])
    expect(parsePortalListFilterDays('between', '2026-03-01,2026-03-31'))
      .toEqual(['2026-03-01', '2026-03-31'])
  })

  it('accepts a full timestamp by taking its calendar day', () => {
    expect(parsePortalListFilterDays('before', '2026-03-05T13:45:00')).toEqual(['2026-03-05'])
  })

  it('reports unreadable values as nothing picked', () => {
    expect(parsePortalListFilterDays('on', 'last week')).toEqual([])
    expect(parsePortalListFilterDays('between', '2026-03-01')).toEqual([])
    expect(parsePortalListFilterDays('on', '')).toEqual([])
  })

  it('round-trips through the value string the API carries', () => {
    const days = ['2026-03-01', '2026-03-31']
    expect(parsePortalListFilterDays('between', formatPortalListFilterDays(days))).toEqual(days)
  })
})

describe('matchPortalListFilter calendar-day operators', () => {
  const cell = '2026-03-05T09:30:00'

  it('matches the whole day regardless of time of day', () => {
    expect(matchPortalListFilter(cell, { operator: 'on', value: '2026-03-05' })).toBe(true)
    expect(matchPortalListFilter(cell, { operator: 'on', value: '2026-03-06' })).toBe(false)
  })

  it('treats before / after as excluding the picked day itself', () => {
    expect(matchPortalListFilter(cell, { operator: 'before', value: '2026-03-06' })).toBe(true)
    expect(matchPortalListFilter(cell, { operator: 'before', value: '2026-03-05' })).toBe(false)
    expect(matchPortalListFilter(cell, { operator: 'after', value: '2026-03-04' })).toBe(true)
    expect(matchPortalListFilter(cell, { operator: 'after', value: '2026-03-05' })).toBe(false)
  })

  it('includes both ends of a range and tolerates a reversed one', () => {
    expect(matchPortalListFilter(cell, { operator: 'between', value: '2026-03-05,2026-03-09' })).toBe(true)
    expect(matchPortalListFilter(cell, { operator: 'between', value: '2026-03-09,2026-03-05' })).toBe(true)
    expect(matchPortalListFilter(cell, { operator: 'between', value: '2026-03-06,2026-03-09' })).toBe(false)
  })

  it('does not silently pass a row when the filter value is unusable', () => {
    expect(matchPortalListFilter(cell, { operator: 'on', value: 'yesterday' })).toBe(false)
    expect(matchPortalListFilter('not a date', { operator: 'on', value: '2026-03-05' })).toBe(false)
  })
})

describe('pruneUnsupportedFilters', () => {
  it('drops a persisted filter whose operator the column kind never accepts', () => {
    const state = createDefaultPortalListColumnState()
    state.filters.startTime = { operator: 'contains', value: '2024' }
    state.filters.status = { operator: 'eq', value: 'ACTIVE' }

    expect(pruneUnsupportedFilters(state, [dateColumn, enumColumn])).toBe(true)
    expect(state.filters.startTime).toBeUndefined()
    expect(state.filters.status).toEqual({ operator: 'eq', value: 'ACTIVE' })
  })

  it('drops a filter on a column the list no longer declares', () => {
    const state = createDefaultPortalListColumnState()
    state.filters.retiredColumn = { operator: 'contains', value: 'x' }

    expect(pruneUnsupportedFilters(state, [enumColumn])).toBe(true)
    expect(state.filters.retiredColumn).toBeUndefined()
  })

  it('leaves state untouched when the declarations have not loaded', () => {
    const state = createDefaultPortalListColumnState()
    state.filters.startTime = { operator: 'contains', value: '2024' }

    expect(pruneUnsupportedFilters(state, [])).toBe(false)
    expect(state.filters.startTime).toEqual({ operator: 'contains', value: '2024' })
  })
})
