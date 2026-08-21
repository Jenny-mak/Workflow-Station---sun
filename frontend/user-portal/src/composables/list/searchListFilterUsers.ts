import { request as portalRequest } from '@/api/request'
import type { ListColumnOption } from '@platform-shared/list/columnMeta'

export interface SearchUserItem {
  id: string
  displayName?: string
  fullName?: string
  username: string
  employeeId?: string
}

/**
 * People-picker source for USER list filters. Keyword is searched by admin-center;
 * this picker then keeps rows whose **visible** name or staff id contains the query.
 * Username / email matches (e.g. pinyin {@code li} → {@code e2e_lina}) are dropped so
 * the dropdown follows what the cell shows (李娜), not the login handle.
 */
export async function searchListFilterUsers(query: string): Promise<ListColumnOption[]> {
  const res = await portalRequest.get<{ data?: SearchUserItem[] }>('/tasks/users/search', {
    params: { keyword: query },
  })
  const users = (res as { data?: unknown })?.data
  if (!Array.isArray(users)) {
    throw new Error('user search did not return a user list')
  }
  const options: ListColumnOption[] = []
  for (const raw of users) {
    const user = raw as SearchUserItem
    if (!user?.id || !user.username) {
      throw new Error('user search returned an entry without id/username')
    }
    if (!userMatchesListFilterQuery(user, query)) {
      continue
    }
    options.push({ value: user.id, label: userFilterLabel(user) })
  }
  return options
}

/** Match the picker query against display name / legal name / staff id only. */
export function userMatchesListFilterQuery(user: SearchUserItem, query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (!needle) {
    return false
  }
  const haystacks = [user.displayName, user.fullName, user.employeeId]
  return haystacks.some((value) => (value ?? '').toLowerCase().includes(needle))
}

function userFilterLabel(user: SearchUserItem): string {
  const name = user.displayName || user.fullName || user.username
  const staffId = user.employeeId || user.username
  if (staffId && staffId !== name) {
    return `${name} (${staffId})`
  }
  return name
}
