import { describe, expect, it } from 'vitest'
import {
  PLATFORM_ROW_UUID_FIELD,
  ensureRowIdentity,
  ensureSliceRowIdentities,
  ensureSubTableMapIdentities,
  readRowIdentityToken,
  rowHasIdentity,
} from '../subTableRowIdentity'

describe('subTableRowIdentity', () => {
  it('names the same key the backend writes', () => {
    // Must stay byte-identical to backend SubTableRowIdentity.CANONICAL_FIELD. They diverged once:
    // this module wrote `row_id` while the backend had moved to `platformRowUuid`, so a submitted
    // row carried two UUIDs under two keys and Change History read one edit as a delete plus an add.
    expect(PLATFORM_ROW_UUID_FIELD).toBe('platformRowUuid')
  })

  it('leaves rows that already have the platform key untouched', () => {
    const row = { [PLATFORM_ROW_UUID_FIELD]: 'existing', channel: 'Email' }
    expect(ensureRowIdentity(row)).toBe(false)
    expect(row[PLATFORM_ROW_UUID_FIELD]).toBe('existing')
  })

  it('does not treat a business column as an identity by name', () => {
    // `row_id` and `id` are real designer columns on tables in this database, so matching the name
    // proves nothing about what the value means. Without configuration, the row is anonymous.
    const row = { row_id: 'ATM-DC-PW-TRANS-000003', id: 'pk-1' } as Record<string, unknown>
    expect(rowHasIdentity(row)).toBe(false)
    expect(ensureRowIdentity(row)).toBe(true)
    expect(row[PLATFORM_ROW_UUID_FIELD]).toBeTruthy()
    // …and the designer's own values are left exactly as they were.
    expect(row.row_id).toBe('ATM-DC-PW-TRANS-000003')
    expect(row.id).toBe('pk-1')
  })

  it('recognises a configured primary key, whatever it is named', () => {
    const row = { correspondence_id: 'Corr-000004', channel: 'Email' }
    expect(rowHasIdentity(row, ['correspondence_id'])).toBe(true)
    expect(ensureRowIdentity(row, ['correspondence_id'])).toBe(false)
    expect(row).not.toHaveProperty(PLATFORM_ROW_UUID_FIELD)
    expect(readRowIdentityToken(row, ['correspondence_id'])).toBe('Corr-000004')
  })

  it('prefers the platform key over a configured designer key', () => {
    const row = { [PLATFORM_ROW_UUID_FIELD]: 'generated', correspondence_id: 'Corr-000004' }
    expect(readRowIdentityToken(row, ['correspondence_id'])).toBe('generated')
  })

  it('assigns distinct identities to anonymous rows in one slice', () => {
    const rows = [{ channel: 'Email' }, { channel: 'SMS' }] as Record<string, unknown>[]
    expect(ensureSliceRowIdentities(rows)).toBe(2)
    expect(rows[0]![PLATFORM_ROW_UUID_FIELD]).toBeTruthy()
    expect(rows[1]![PLATFORM_ROW_UUID_FIELD]).toBeTruthy()
    expect(rows[0]![PLATFORM_ROW_UUID_FIELD]).not.toBe(rows[1]![PLATFORM_ROW_UUID_FIELD])
  })

  it('does not stamp a uuid on rows already carrying their configured key', () => {
    const rows = [{ correspondence_id: 'Corr-1' }, { correspondence_id: 'Corr-2' }]
    expect(ensureSliceRowIdentities(rows, ['correspondence_id'])).toBe(0)
    expect(rows[0]).not.toHaveProperty(PLATFORM_ROW_UUID_FIELD)
  })

  it('does not stamp a second uuid on name aliases when a numeric slice exists', () => {
    const canonical = { channel: 'Email' } as Record<string, unknown>
    const aliasCopy = { channel: 'Email' } as Record<string, unknown>
    expect(ensureSubTableMapIdentities({
      '1301': [canonical],
      'ACQ Correspondence': [aliasCopy],
    })).toBe(1)
    expect(canonical[PLATFORM_ROW_UUID_FIELD]).toBeTruthy()
    expect(aliasCopy).not.toHaveProperty(PLATFORM_ROW_UUID_FIELD)
  })

  it('applies each slice its own configured primary key', () => {
    const keyed = { correspondence_id: 'Corr-1' } as Record<string, unknown>
    const anonymous = { channel: 'SMS' } as Record<string, unknown>
    const assigned = ensureSubTableMapIdentities(
      { 'dw:correspondence': [keyed], 'dw:notes': [anonymous] },
      { 'dw:correspondence': ['correspondence_id'] },
    )
    expect(assigned).toBe(1)
    expect(keyed).not.toHaveProperty(PLATFORM_ROW_UUID_FIELD)
    expect(anonymous[PLATFORM_ROW_UUID_FIELD]).toBeTruthy()
  })

  it('reads the first identity token in field-priority order', () => {
    expect(readRowIdentityToken({ [PLATFORM_ROW_UUID_FIELD]: 'r1', id: 'pk-9' })).toBe('r1')
    expect(readRowIdentityToken({ id: 'pk-9' })).toBeNull()
    expect(readRowIdentityToken({ id: 'pk-9' }, ['id'])).toBe('pk-9')
    expect(readRowIdentityToken({ channel: 'Email' })).toBeNull()
  })
})
