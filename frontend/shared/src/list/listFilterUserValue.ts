/**
 * USER list filters are a people picker: the dialog searches by name / pinyin / staff id,
 * but the value sent to the backend must be {@code sys_users.id}.
 *
 * Typed query strings such as {@code sun} must not be applied as Equals — that looks up
 * a user whose id is literally "sun" and returns an empty page.
 */

export interface UserFilterHit {
  value: string
}

export function resolveUserFilterValue(
  value: string,
  hits: ReadonlyArray<UserFilterHit>,
  options: { appliedValue?: string; loading?: boolean } = {},
): string | null {
  if (hits.some((hit) => hit.value === value)) {
    return value
  }
  const onlyHit = hits.length === 1 ? hits[0] : undefined
  if (onlyHit) {
    return onlyHit.value
  }
  // Re-opened an already-applied person id. Admin keyword search often misses a raw
  // sys_users.id, so keep that id once loading has finished with no hits. Do not do
  // this while a search is in flight — the draft may still be a typed query like "sun".
  if (
    value !== ''
    && options.appliedValue === value
    && hits.length === 0
    && !options.loading
  ) {
    return value
  }
  return null
}
