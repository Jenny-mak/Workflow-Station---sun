import { describe, it, expect } from 'vitest'
import { insertListGroupHeaders } from '@platform-shared/list/insertGroupHeaders'

describe('insertListGroupHeaders', () => {
  it('slots a header with the backend count in front of each run', () => {
    const rows = [
      { status: 'RUNNING', id: '1' },
      { status: 'RUNNING', id: '2' },
      { status: 'COMPLETED', id: '3' },
    ]
    const display = insertListGroupHeaders(rows, 'status', [
      { label: 'RUNNING', count: 10 },
      { label: 'COMPLETED', count: 4 },
    ])
    expect(display).toHaveLength(5)
    expect(display[0]).toEqual({ _isGroupHeader: true, _groupLabel: 'RUNNING', _groupCount: 10 })
    expect(display[3]).toEqual({ _isGroupHeader: true, _groupLabel: 'COMPLETED', _groupCount: 4 })
  })

  it('returns the original rows when grouping is off', () => {
    const rows = [{ status: 'RUNNING' }]
    expect(insertListGroupHeaders(rows, null, [])).toBe(rows)
  })

  it('throws when a page group was not counted by the same query', () => {
    expect(() =>
      insertListGroupHeaders([{ status: 'RUNNING' }], 'status', [{ label: 'COMPLETED', count: 1 }]),
    ).toThrow(/not counted by the server/)
  })

  it('throws when a group is missing its count', () => {
    expect(() =>
      insertListGroupHeaders([{ status: 'RUNNING' }], 'status', [{ label: 'RUNNING', count: undefined as never }]),
    ).toThrow(/missing count/)
  })
})
