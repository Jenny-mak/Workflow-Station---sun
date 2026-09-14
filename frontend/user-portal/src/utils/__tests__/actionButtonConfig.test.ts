import { describe, expect, it } from 'vitest'
import {
  actionConfirmMessage,
  actionRequiresComment,
  InvalidActionConfigJsonError,
  parseActionConfigJson,
  tryParseActionConfigJson,
} from '../actionButtonConfig'

describe('parseActionConfigJson', () => {
  it('returns empty object for missing or blank JSON', () => {
    expect(parseActionConfigJson(undefined)).toEqual({})
    expect(parseActionConfigJson(null)).toEqual({})
    expect(parseActionConfigJson('')).toEqual({})
    expect(parseActionConfigJson('   ')).toEqual({})
  })

  it('throws when the payload is not an object JSON', () => {
    expect(() => parseActionConfigJson('not-json')).toThrow(InvalidActionConfigJsonError)
    expect(() => parseActionConfigJson('[]')).toThrow(InvalidActionConfigJsonError)
    expect(() => parseActionConfigJson('"x"')).toThrow(InvalidActionConfigJsonError)
    expect(tryParseActionConfigJson('not-json')).toBeNull()
    expect(tryParseActionConfigJson('[]')).toBeNull()
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
