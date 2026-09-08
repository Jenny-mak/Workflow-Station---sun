import { type Ref } from 'vue'

export const OWNER_USER_PREFIX = 'user:'
export const OWNER_STEP_PREFIX = 'step:'

export type OwnerSource = 'CREATOR' | 'CASE_HANDLER'
export type OwnerChipKind = 'user' | 'step'

export type OwnerChipModel = {
  kind: OwnerChipKind
  label: string
}

/**
 * Parse `ownerConfig` (§4.1). Missing source (including leftover allowGroup-only
 * configs) is CREATOR. Legacy CURRENT_ASSIGNEE is CASE_HANDLER. Invalid JSON sets configError.
 */
export function ownerConfigSource(ownerConfig: string | undefined): OwnerSource {
  const configError = { value: false }
  return parseOwnerSource(ownerConfig, configError)
}

/**
 * New-row UX (§3.3.1): empty Creator becomes the current user. Does not
 * overwrite an existing person and never touches Case Handler.
 */
export function applyCreatorPrefill(
  target: Record<string, unknown>,
  fieldKey: string,
  ownerConfig: string | undefined,
  actor: { userId?: string; displayName?: string; username?: string } | null | undefined,
): boolean {
  if (!fieldKey || !actor?.userId) return false
  if (ownerConfigSource(ownerConfig) !== 'CREATOR') return false
  const current = target[fieldKey]
  if (current != null && String(current).trim() !== '') return false
  target[fieldKey] = `${OWNER_USER_PREFIX}${actor.userId}`
  const label = String(actor.displayName || actor.username || '').trim()
  if (label) target[`${fieldKey}__display`] = label
  return true
}

export function parseOwnerSource(
  ownerConfig: string | undefined,
  configError: Ref<boolean>,
): OwnerSource {
  try {
    const parsed = JSON.parse(ownerConfig || '{}') as { source?: unknown }
    configError.value = false
    if (parsed?.source === 'CASE_HANDLER'
      || parsed?.source === 'CURRENT_ASSIGNEE') {
      return 'CASE_HANDLER'
    }
    return 'CREATOR'
  } catch {
    configError.value = true
    return 'CREATOR'
  }
}

export function parseOwnerStep(value: string): string | null {
  const trimmed = value.trim()
  if (trimmed.startsWith(OWNER_STEP_PREFIX) && trimmed.length > OWNER_STEP_PREFIX.length) {
    return trimmed.slice(OWNER_STEP_PREFIX.length)
  }
  return null
}

/** Parses `user:<id>` or `user:<id1>,user:<id2>` into user ids. */
export function parseStoredUserIds(value: string): string[] {
  return value
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part.startsWith(OWNER_USER_PREFIX) && part.length > OWNER_USER_PREFIX.length)
    .map((part) => part.slice(OWNER_USER_PREFIX.length).trim())
    .filter((id) => id.length > 0)
}

export function ownerChips(modelValue: string | null | undefined, display: string | undefined): OwnerChipModel[] {
  const value = (modelValue || '').trim()
  const step = parseOwnerStep(value)
  if (step) {
    return [{ kind: 'step', label: (display || '').trim() || step }]
  }
  const label = (display || '').trim()
  const ids = parseStoredUserIds(value)
  if (ids.length > 0) {
    const labels = label
      ? label.split(',').map((part) => part.trim()).filter((part) => part.length > 0)
      : []
    return ids.map((id, index) => ({
      kind: 'user' as const,
      label: labels[index] || id,
    }))
  }
  if (!label) {
    return []
  }
  return label.split(',').map((part) => ({
    kind: 'user' as const,
    label: part.trim(),
  })).filter((chip) => chip.label.length > 0)
}
