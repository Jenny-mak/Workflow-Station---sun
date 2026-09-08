import { describe, expect, it } from 'vitest'
import { ref } from 'vue'
import {
  applyCreatorPrefill,
  ownerChips,
  parseOwnerSource,
  parseStoredUserIds,
} from '../useOwnerFieldModel'

describe('parseStoredUserIds', () => {
  it('parses one and many user: tokens', () => {
    expect(parseStoredUserIds('user:u-a,user:u-b')).toEqual(['u-a', 'u-b'])
    expect(parseStoredUserIds('user:u-bob')).toEqual(['u-bob'])
    expect(parseStoredUserIds('step:multi')).toEqual([])
    expect(parseStoredUserIds('')).toEqual([])
  })
})

describe('ownerChips', () => {
  it('splits an unclaimed pool into one chip per stored user', () => {
    expect(ownerChips('user:u-a,user:u-b', 'Ann, Ben')).toEqual([
      { kind: 'user', label: 'Ann' },
      { kind: 'user', label: 'Ben' },
    ])
  })

  it('uses the stored id when display is missing', () => {
    expect(ownerChips('user:u-bob', undefined)).toEqual([
      { kind: 'user', label: 'u-bob' },
    ])
  })

  it('falls back to comma-separated display when the main value is empty', () => {
    expect(ownerChips('', 'Ann, Ben')).toEqual([
      { kind: 'user', label: 'Ann' },
      { kind: 'user', label: 'Ben' },
    ])
  })
})

describe('parseOwnerSource', () => {
  it('reads CASE_HANDLER and leftover CURRENT_ASSIGNEE, but not CURRENT_HANDLER', () => {
    const configError = ref(false)
    expect(parseOwnerSource('{"source":"CASE_HANDLER"}', configError)).toBe('CASE_HANDLER')
    expect(parseOwnerSource('{"source":"CURRENT_ASSIGNEE"}', configError)).toBe('CASE_HANDLER')
    expect(parseOwnerSource('{"source":"CURRENT_HANDLER"}', configError)).toBe('CREATOR')
    expect(configError.value).toBe(false)
  })
})

describe('applyCreatorPrefill', () => {
  const actor = { userId: 'u-hmdc', displayName: 'hase-hmdc', username: 'hase-hmdc' }

  it('fills an empty Creator and leaves Case Handler empty', () => {
    const row: Record<string, unknown> = { case_creator: '', current_handler: '' }
    expect(applyCreatorPrefill(row, 'case_creator', '{"source":"CREATOR"}', actor)).toBe(true)
    expect(applyCreatorPrefill(row, 'current_handler', '{"source":"CASE_HANDLER"}', actor)).toBe(false)
    expect(row.case_creator).toBe('user:u-hmdc')
    expect(row.case_creator__display).toBe('hase-hmdc')
    expect(row.current_handler).toBe('')
  })

  it('does not overwrite a Creator that is already stored', () => {
    const row: Record<string, unknown> = { case_creator: 'user:u-zhangwei' }
    expect(applyCreatorPrefill(row, 'case_creator', '{"source":"CREATOR"}', actor)).toBe(false)
    expect(row.case_creator).toBe('user:u-zhangwei')
  })
})

describe('ownerChips step', () => {
  it('renders MI outer name as plain step text', () => {
    expect(ownerChips('step:multi', 'multi')).toEqual([
      { kind: 'step', label: 'multi' },
    ])
  })
})
