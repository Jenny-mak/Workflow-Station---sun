import { describe, it, expect, vi } from 'vitest'
import { buildInitialRow } from '../rowInit'
import type { DialogColumn } from '../types'

vi.mock('@/api/auth', () => ({
  getUser: () => ({ userId: 'u-hmdc', displayName: 'hase-hmdc', username: 'hase-hmdc' }),
}))

describe('buildInitialRow Owner Creator', () => {
  it('prefills Creator on +Add and leaves Case Handler empty', () => {
    const columns: DialogColumn[] = [
      {
        field: 'row_owner',
        label: 'Row Owner',
        type: 'owner',
        props: { ownerConfig: '{"source":"CREATOR"}' },
      },
      {
        field: 'row_case_handler',
        label: 'Row Case Handler',
        type: 'owner',
        props: { ownerConfig: '{"source":"CASE_HANDLER"}' },
      },
    ]
    const row = buildInitialRow(columns)
    expect(row.row_owner).toBe('user:u-hmdc')
    expect(row.row_owner__display).toBe('hase-hmdc')
    expect(row.row_case_handler).toBe('')
  })
})
