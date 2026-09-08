/**
 * Sub-table JSON-row identity. Mirrors backend `SubTableRowIdentity`: assign the platform key once,
 * before any binding/name alias is stamped, so deserialized copies keep the same identity instead of
 * each receiving a new UUID.
 *
 * The two sides must name the SAME key. They briefly did not — the backend key was renamed to
 * `platformRowUuid` while this file still wrote `row_id`, so a row submitted from the task form
 * carried the frontend's UUID under a key the backend could not see, and the backend stamped a
 * second one. Change History then paired rows by two different keys and read one edited row as a
 * delete plus an add.
 */

/**
 * The key this module writes when a row has no identity at all.
 *
 * Kept byte-identical to backend `SubTableRowIdentity.CANONICAL_FIELD`. It is deliberately not a
 * plausible column name: the value is a platform-generated UUID, not a designer field.
 */
export const PLATFORM_ROW_UUID_FIELD = 'platformRowUuid'

/**
 * Keys that carry a row's platform identity, most authoritative first.
 *
 * Only the platform's own key belongs here. This list used to continue
 * `'row_id', 'rowId', 'rowID', 'id_idw', '_rowKey', 'rowKey', 'id'` — guesses at what a designer
 * might have named their primary key. Every one of them is wrong somewhere: a table keyed by
 * `correspondence_id` matches none, while `row_id` and `id` are real business columns on tables in
 * this database, so matching the name proved nothing about what the value meant.
 *
 * Which columns identify a row is configuration (`dw_field_definitions.is_primary_key`), delivered
 * per binding as `primaryKeyFields`. Callers holding a binding pass it to the functions below; the
 * merge helpers in `subTableRowMerge` already take it as an argument.
 */
export const SUB_TABLE_IDENTITY_FIELDS = [PLATFORM_ROW_UUID_FIELD] as const

function identityValue(value: unknown): string | null {
  if (value == null) return null
  const text = String(value).trim()
  return text.length > 0 ? text : null
}

function newRowId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `row_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`
}

/**
 * The keys to look for on a row of a given table: the platform key first, then the columns the
 * DESIGNER declared as that table's primary key.
 *
 * Passing the configured key is what lets a row be recognised by its real identity —
 * `correspondence_id`, `case_number`, or any other name a user chose — instead of by a fixed list
 * of names this module would otherwise have to guess.
 */
function lookupOrder(designerPrimaryKeyFields?: readonly string[] | null): string[] {
  const order: string[] = [...SUB_TABLE_IDENTITY_FIELDS]
  for (const field of designerPrimaryKeyFields ?? []) {
    if (typeof field !== 'string') continue
    const name = field.trim()
    if (name && !order.includes(name)) order.push(name)
  }
  return order
}

export function rowHasIdentity(
  row: Record<string, unknown>,
  designerPrimaryKeyFields?: readonly string[] | null,
): boolean {
  return lookupOrder(designerPrimaryKeyFields).some(field => identityValue(row[field]) != null)
}

/** First non-blank identity value, platform key first then configured primary key columns. */
export function readRowIdentityToken(
  row: Record<string, unknown>,
  designerPrimaryKeyFields?: readonly string[] | null,
): string | null {
  for (const field of lookupOrder(designerPrimaryKeyFields)) {
    const text = identityValue(row[field])
    if (text) return text
  }
  return null
}

/** @return true when a platform row UUID was assigned */
export function ensureRowIdentity(
  row: Record<string, unknown>,
  designerPrimaryKeyFields?: readonly string[] | null,
): boolean {
  if (rowHasIdentity(row, designerPrimaryKeyFields)) return false
  row[PLATFORM_ROW_UUID_FIELD] = newRowId()
  return true
}

export function ensureSliceRowIdentities(
  rows: unknown[],
  designerPrimaryKeyFields?: readonly string[] | null,
): number {
  if (!Array.isArray(rows)) return 0
  let assigned = 0
  for (const item of rows) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) continue
    const row = item as Record<string, unknown>
    if (ensureRowIdentity(row, designerPrimaryKeyFields)) assigned += 1
    const nested = row.__subTables__
    if (nested && typeof nested === 'object' && !Array.isArray(nested)) {
      // Nested slices belong to other tables, whose primary keys this caller does not hold.
      assigned += ensureSubTableMapIdentities(nested as Record<string, unknown>)
    }
  }
  return assigned
}

function isNumericSliceKey(key: string): boolean {
  return key.length > 0 && /^\d+$/.test(key)
}

/**
 * Assign identity on canonical numeric binding-id slices first. Name aliases of
 * those slices must not receive a second UUID — JSON copies would otherwise
 * look like row add + delete in Change History.
 *
 * @param primaryKeyFieldsBySliceKey this table's configured primary key, per slice key, when the
 *        caller holds the bindings. Omitted entries fall back to the platform key alone.
 */
export function ensureSubTableMapIdentities(
  subTables: Record<string, unknown>,
  primaryKeyFieldsBySliceKey?: Readonly<Record<string, readonly string[] | null | undefined>> | null,
): number {
  const numericKeys: string[] = []
  const aliasKeys: string[] = []
  for (const key of Object.keys(subTables)) {
    if (isNumericSliceKey(key)) numericKeys.push(key)
    else aliasKeys.push(key)
  }
  const keys = numericKeys.length > 0 ? numericKeys : aliasKeys
  let assigned = 0
  for (const key of keys) {
    const value = subTables[key]
    if (Array.isArray(value)) {
      assigned += ensureSliceRowIdentities(value, primaryKeyFieldsBySliceKey?.[key])
    }
  }
  return assigned
}
