import type { FormRules } from 'element-plus'
import { isSystemAuditField, normalizeAuditFieldName } from '@platform-shared/systemAuditFields'
import type { DialogColumn } from './types'
import { getUser, type UserInfo } from '@/api/auth'
import { applyCreatorPrefill } from '@/composables/owner/useOwnerFieldModel'

/** Format the current instant as {@code YYYY-MM-DD HH:mm:ss} in UTC+8 (Asia/Shanghai). */
function formatTimestampLocal(): string {
  const d = new Date()
  const utc8 = new Date(d.getTime() + (d.getTimezoneOffset() + 480) * 60000)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${utc8.getFullYear()}-${pad(utc8.getMonth() + 1)}-${pad(utc8.getDate())} ${pad(utc8.getHours())}:${pad(utc8.getMinutes())}:${pad(utc8.getSeconds())}`
}

/**
 * Name DECISION lives in @platform-shared/systemAuditFields (exact four names, kept in
 * sync with backend platform-common SystemAuditFields). Only the fill VALUES are
 * portal-specific (timestamp format + current-user display name).
 */
// Null prototype: looked up with designer-provided field names, so an inherited
// key like "__proto__" must resolve to undefined, not Object.prototype members.
const AUDIT_FILLERS: Readonly<Record<string, (user: UserInfo | null) => string>> = Object.assign(
  Object.create(null),
  {
    created_at: () => formatTimestampLocal(),
    updated_at: () => formatTimestampLocal(),
    created_by: (user: UserInfo | null) => user?.displayName || user?.username || '',
    updated_by: (user: UserInfo | null) => user?.displayName || user?.username || '',
  },
)

/**
 * Assign a designer-provided field name as an own enumerable key. Plain
 * `obj[field] = value` breaks for names like "__proto__" (mutates the
 * prototype instead of creating a key, so the field silently vanishes).
 */
function setOwnField(target: Record<string, unknown>, field: string, value: unknown): void {
  Object.defineProperty(target, field, {
    value,
    writable: true,
    enumerable: true,
    configurable: true,
  })
}

/**
 * Single source of truth for "is this a system audit field?" (portal frontend).
 * Use everywhere — column enrichment, readonly marking, dialog guards.
 */
export function isAuditField(fieldName: string): boolean {
  return isSystemAuditField(fieldName)
}

/**
 * Fill created_* / updated_* audit values on a row when it is SAVED from the Add dialog.
 * Must never run when the dialog opens — audit values are generated at real insert time
 * (dialog save), so an abandoned dialog leaves no timestamps behind.
 */
export function applyAuditFieldDefaults(row: Record<string, unknown>, columns: DialogColumn[]): void {
  // Resolve user once — all audit columns share the same value.
  let cachedUser: UserInfo | null | undefined
  const resolveUser = (): UserInfo | null => {
    if (cachedUser === undefined) {
      try {
        cachedUser = getUser()
      } catch {
        cachedUser = null
      }
    }
    return cachedUser
  }

  for (const col of columns) {
    const filler = AUDIT_FILLERS[normalizeAuditFieldName(col.field)]
    if (filler) setOwnField(row, col.field, filler(resolveUser()))
  }
}

/**
 * Refresh {@code updated_at} / {@code updated_by} fields on an existing row during edit.
 * Only touches the "updated" family — created_at / created_by are left unchanged.
 */
export function applyEditAuditDefaults(row: Record<string, unknown>, columns: DialogColumn[]): void {
  let cachedUser: UserInfo | null | undefined
  const resolveUser = (): UserInfo | null => {
    if (cachedUser === undefined) {
      try { cachedUser = getUser() } catch { cachedUser = null }
    }
    return cachedUser
  }

  for (const col of columns) {
    const n = normalizeAuditFieldName(col.field)
    if (n !== 'updated_at' && n !== 'updated_by') continue
    const filler = AUDIT_FILLERS[n]
    if (filler) setOwnField(row, col.field, filler(resolveUser()))
  }
}

function initialValueFor(col: DialogColumn): unknown {
  if (col.defaultValue !== undefined) {
    return typeof col.defaultValue === 'object' && col.defaultValue !== null
      ? JSON.parse(JSON.stringify(col.defaultValue))
      : col.defaultValue
  }
  switch (col.type) {
    case 'number':
      return undefined
    case 'switch':
      return false
    case 'checkbox':
      return []
    case 'date':
    case 'datetime':
    case 'timerange':
      return null
    case 'treeselect':
      return col.props?.multiple ? [] : ''
    case 'rate':
    case 'slider':
      return 0
    case 'colorPicker':
      return ''
    case 'tree':
      return []
    case 'transfer':
      return []
    case 'cascader':
      return []
    case 'lookup':
      return null
    case 'editor':
      return ''
    case 'signature':
      return ''
    default:
      // text, textarea, password, radio, select, user, department
      return ''
  }
}

export function buildInitialRow(columns: DialogColumn[]): Record<string, unknown> {
  const row: Record<string, unknown> = {}
  for (const col of columns) {
    setOwnField(row, col.field, initialValueFor(col))
  }

  // Audit fields (created_at / created_by / updated_at / updated_by) stay empty here:
  // they are filled by applyAuditFieldDefaults at dialog SAVE time, never on open.
  // Owner Creator is the opposite: §3.3.1 pre-fills the current user when the row is created.
  let cachedUser: UserInfo | null | undefined
  for (const col of columns) {
    if (col.type !== 'owner') continue
    if (cachedUser === undefined) {
      try {
        cachedUser = getUser()
      } catch {
        cachedUser = null
      }
    }
    const ownerConfig = typeof col.props?.ownerConfig === 'string'
      ? col.props.ownerConfig
      : JSON.stringify(col.props?.ownerConfig ?? {})
    applyCreatorPrefill(row, col.field, ownerConfig, cachedUser)
  }

  return row
}

function isEmptyFormValue(value: unknown): boolean {
  if (value == null) return true
  if (typeof value === 'string' && value.trim() === '') return true
  return false
}

/** Keep seeded PK/FK/runtime values when form-create or empty inputs omit them on save. */
export function mergeFormRowWithSeed(
  seed: Record<string, unknown> | null | undefined,
  form: Record<string, unknown>,
): Record<string, unknown> {
  const row = { ...form }
  if (!seed) return row
  for (const [key, seedVal] of Object.entries(seed)) {
    // Own-key read: for a field named "__proto__", row[key] would surface the
    // inherited prototype object and wrongly count as a non-empty form value.
    const current = Object.prototype.hasOwnProperty.call(row, key) ? row[key] : undefined
    if (isEmptyFormValue(current) && !isEmptyFormValue(seedVal)) {
      setOwnField(row, key, seedVal)
    }
  }
  return row
}

function defaultRequiredTrigger(col: DialogColumn): 'blur' | 'change' {
  return col.type === 'select' || col.type === 'date' || col.type === 'datetime' || col.type === 'checkbox'
    || col.type === 'cascader' || col.type === 'transfer' || col.type === 'lookup' || col.type === 'switch'
    ? 'change'
    : 'blur'
}

function buildRequiredOnlyRules(col: DialogColumn): Array<Record<string, unknown>> {
  const trigger = defaultRequiredTrigger(col)
  if (col.type === 'switch') {
    return [{
      type: 'boolean',
      required: true,
      message: `${col.label} is required`,
      trigger,
    }]
  }
  return [{ required: true, message: `${col.label} is required`, trigger }]
}

/**
 * Build Element Plus form rules for the Add/Edit dialog.
 * Prefer Form Design validate rules on {@link DialogColumn.rules}; fall back to
 * required-only synthesis for columns that only carry the required flag (e.g. list-view schema).
 */
export function buildRules(columns: DialogColumn[]): FormRules {
  const rules: FormRules = {}
  for (const col of columns) {
    // Auto-PK / readonly FK / audit fields are system-filled (audit values only appear at save);
    // form-create disabled fields often fail required checks.
    if (col.readonly || isAuditField(col.field)) continue

    if (Array.isArray(col.rules) && col.rules.length > 0) {
      setOwnField(rules as unknown as Record<string, unknown>, col.field, col.rules)
      continue
    }

    if (col.required) {
      setOwnField(rules as unknown as Record<string, unknown>, col.field, buildRequiredOnlyRules(col))
    }
  }
  return rules
}
