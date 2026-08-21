import { describe, expect, it } from 'vitest'
import {
  userMatchesListFilterQuery,
  type SearchUserItem,
} from '@/composables/list/searchListFilterUsers'

const lina: SearchUserItem = {
  id: 'lina-id',
  username: 'e2e_lina',
  displayName: '李娜',
  fullName: '李娜',
  employeeId: 'E26-2001',
}

describe('userMatchesListFilterQuery', () => {
  it('keeps 李娜 when the query is the Chinese surname 李', () => {
    expect(userMatchesListFilterQuery(lina, '李')).toBe(true)
  })

  it('does not treat pinyin li as a match against 李娜', () => {
    expect(userMatchesListFilterQuery(lina, 'li')).toBe(false)
    expect(userMatchesListFilterQuery(lina, 'lina')).toBe(false)
  })

  it('still matches staff id', () => {
    expect(userMatchesListFilterQuery(lina, 'E26-2001')).toBe(true)
    expect(userMatchesListFilterQuery(lina, 'e26-2001')).toBe(true)
  })
})
