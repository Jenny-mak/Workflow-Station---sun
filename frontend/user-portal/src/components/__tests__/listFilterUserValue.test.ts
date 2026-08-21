import { describe, expect, it } from 'vitest'
import { resolveUserFilterValue } from '@platform-shared/list/listFilterUserValue'

describe('resolveUserFilterValue', () => {
  it('returns null when nothing is selected and there are no hits', () => {
    expect(resolveUserFilterValue('', [])).toBeNull()
    expect(resolveUserFilterValue('sun', [])).toBeNull()
  })

  it('uses the selected hit id when the draft already holds a person id', () => {
    expect(
      resolveUserFilterValue('e26-id', [
        { value: 'e26-id' },
        { value: 'other-id' },
      ]),
    ).toBe('e26-id')
  })

  it('maps a typed pinyin query to the only search hit instead of sending the query string', () => {
    expect(
      resolveUserFilterValue('sun', [{ value: 'e26-id' }]),
    ).toBe('e26-id')
  })

  it('maps an empty draft to the only search hit so Apply can confirm a unique person', () => {
    expect(resolveUserFilterValue('', [{ value: 'e26-id' }])).toBe('e26-id')
  })

  it('does not guess when several people match the query', () => {
    expect(
      resolveUserFilterValue('sun', [{ value: 'e26-id' }, { value: 'other-id' }]),
    ).toBeNull()
  })

  it('does not treat a typed query as a person id while search is in flight', () => {
    expect(
      resolveUserFilterValue('sun', [], { appliedValue: 'sun', loading: true }),
    ).toBeNull()
  })

  it('keeps a previously applied person id when id search returns no hits', () => {
    expect(
      resolveUserFilterValue('e26-id', [], { appliedValue: 'e26-id', loading: false }),
    ).toBe('e26-id')
  })
})
