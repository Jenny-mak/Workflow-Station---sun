import { describe, expect, it } from 'vitest'
import {
  actionConfirmMessage,
  actionRequiresComment,
  parseActionConfigJson,
} from '../actionButtonConfig'

describe('parseActionConfigJson', () => {
  it('returns empty object for missing or invalid JSON', () => {
    expect(parseActionConfigJson(undefined)).toEqual({})
    expect(parseActionConfigJson(null)).toEqual({})
    expect(parseActionConfigJson('')).toEqual({})
    expect(parseActionConfigJson('not-json')).toEqual({})
    expect(parseActionConfigJson('[]')).toEqual({})
  })

  it('parses object config', () => {
    expect(parseActionConfigJson('{"requireComment":true,"confirmMessage":"Go?"}')).toEqual({
      requireComment: true,
      confirmMessage: 'Go?',
    })
  })
})

describe('actionRequiresComment', () => {
  it('is true only for boolean true (Designer switch)', () => {
    expect(actionRequiresComment({ requireComment: true })).toBe(true)
    expect(actionRequiresComment({ requireComment: false })).toBe(false)
    expect(actionRequiresComment({ requireComment: 'true' })).toBe(false)
    expect(actionRequiresComment({})).toBe(false)
  })
})

describe('actionConfirmMessage', () => {
  it('returns trimmed text or null when empty', () => {
    expect(actionConfirmMessage({ confirmMessage: '  Sure?  ' })).toBe('Sure?')
    expect(actionConfirmMessage({ confirmMessage: '   ' })).toBeNull()
    expect(actionConfirmMessage({})).toBeNull()
    expect(actionConfirmMessage({ confirmMessage: 1 })).toBeNull()
  })
})
