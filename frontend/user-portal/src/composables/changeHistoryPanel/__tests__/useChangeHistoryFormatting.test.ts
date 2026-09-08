import { describe, expect, it } from 'vitest'
import dayjs from 'dayjs'
import { useChangeHistoryFormatting } from '../useChangeHistoryFormatting'
import { excludeEmptyValueChanges } from '../useChangeHistoryLoader'

const t = ((key: string, params?: Record<string, unknown>) => {
  if (key === 'changeHistory.batchChanges') return `${params?.count ?? 0} changes`
  return key
}) as never
describe('useChangeHistoryFormatting', () => {
  it('hides only records with no user-visible old or new value', () => {
    const rows = [
      { id: 1, oldValue: null, newValue: '' },
      { id: 2, oldValue: null, newValue: 'liam' },
      { id: 3, oldValue: 'previous', newValue: '' },
    ] as never
    expect(excludeEmptyValueChanges(rows).map(row => row.id)).toEqual([2, 3])
  })
  it('formats lookup object values as dropdown name only', () => {
    const { formatDisplayValue } = useChangeHistoryFormatting(t, dayjs)
    const value = JSON.stringify({
      enabled: true,
      created_at: '2026-06-23 15:42:03',
      created_by: 'admin',
      dropdown_name: 'USD',
      dropdown_category: 'Billing currency',
    })
    expect(formatDisplayValue(value)).toBe('USD')
  })
  it('reduces lookup payloads with metadata to the display name', () => {
    const { formatDisplayValue } = useChangeHistoryFormatting(t, dayjs)
    const value = JSON.stringify({
      enabled: true,
      created_at: '2026-06-23 15:42:03',
      created_by: 'admin',
      updated_at: '2026-06-23 15:42:03',
      updated_by: 'admin',
      dropdown_name: 'USD',
      dropdown_category: 'Billing currency',
    })
    expect(formatDisplayValue(value)).toBe('USD')
  })
  it('formats JSON object diffs as changed field list', () => {
    const { formatDisplayValue } = useChangeHistoryFormatting(t, dayjs)
    const value = JSON.stringify({
      row_id: 'ATM-DC-PW-TRANS-000010',
      card_number: '12',
      merchant_name: '23',
    })
    expect(formatDisplayValue(value)).toBe('card_number: 12; merchant_name: 23')
  })
  it('formats nested dropdown and uploaded file values compactly', () => {
    const { formatDisplayValue } = useChangeHistoryFormatting(t, dayjs)
    const value = JSON.stringify({
      incoming_channel: { row_id: 'd1', dropdown_name: 'Branch' },
      file: '/api/v1/upload/files/336fd6f4.jpg?originalName=lilong.JPG',
    })
    expect(formatDisplayValue(value)).toBe('incoming_channel: Branch; file: lilong.JPG')
  })
  it('formats a stored upload URL as the original file name', () => {
    const { formatDisplayValue } = useChangeHistoryFormatting(t, dayjs)
    expect(formatDisplayValue(
      '/api/v1/upload/files/bc7a8506-aeb4-428a-881e-fe6887b65ed7.jpg?originalName=MSI_MEG_GODLIKE.jpg',
    )).toBe('MSI_MEG_GODLIKE.jpg')
  })
  it('keeps id-like payloads readable instead of rendering em dash', () => {
    const { formatDisplayValue } = useChangeHistoryFormatting(t, dayjs)
    const value = JSON.stringify({ id: '8b3f4b67-08b7-4a12-8f3d-6789a1e8ac20' })
    expect(formatDisplayValue(value)).toBe('8b3f4b67-08b7-4a12-8f3d-6789a1e8ac20')
  })
  it('prefers display_name style keys for lookup-like objects', () => {
    const { formatDisplayValue } = useChangeHistoryFormatting(t, dayjs)
    const value = JSON.stringify({ display_name: 'Liam L Li', user_id: '45455063' })
    expect(formatDisplayValue(value)).toBe('display_name: Liam L Li; user_id: 45455063')
  })
  it('preserves ordinary object fields even when they include a name', () => {
    const { formatDisplayValue } = useChangeHistoryFormatting(t, dayjs)
    const value = JSON.stringify({ name: 'Item A', status: 'APPROVED', amount: '100' })
    expect(formatDisplayValue(value)).toBe('name: Item A; status: APPROVED; amount: 100')
  })

  it('masks scalar field values when sensitiveMaskLookup is provided', () => {
    const lookup = new Map([
      ['card_number', {
        enabled: true,
        preset: 'last4' as const,
        keepPrefix: 0,
        keepSuffix: 4,
        maskChar: '*',
        revealPlainOnFocus: false,
      }],
    ])
    const { formatDisplayValue } = useChangeHistoryFormatting(t, dayjs, () => lookup)
    expect(formatDisplayValue('6222021234567890', 240, 'card_number')).toBe('************7890')
  })

  it('masks matching keys inside sub-table JSON row diffs', () => {
    const lookup = new Map([
      ['card_number', {
        enabled: true,
        preset: 'last4' as const,
        keepPrefix: 0,
        keepSuffix: 4,
        maskChar: '*',
        revealPlainOnFocus: false,
      }],
    ])
    const { formatDisplayValue } = useChangeHistoryFormatting(t, dayjs, () => lookup)
    const value = JSON.stringify({
      row_id: 'ATM-001',
      card_number: '6222021234567890',
      merchant_name: 'Shop',
    })
    expect(formatDisplayValue(value)).toBe('card_number: ************7890; merchant_name: Shop')
  })

  it('labels assignment history as Claimed By and maps empty holder to the pool', () => {
    const { fieldLocationLabel, formatDisplayValue, getChangeTypeLabel } = useChangeHistoryFormatting(t, dayjs)
    expect(fieldLocationLabel({ changeType: 'REASSIGN', fieldName: 'claimed_by' } as never))
      .toBe('changeHistory.claimedBy')
    expect(formatDisplayValue(null, 240, 'claimed_by')).toBe('changeHistory.claimPool')
    expect(formatDisplayValue('Alice Chen', 240, 'claimed_by')).toBe('Alice Chen')
    expect(getChangeTypeLabel('CLAIM')).toBe('changeHistory.claim')
    expect(getChangeTypeLabel('UNCLAIM')).toBe('changeHistory.unclaim')
    expect(getChangeTypeLabel('FORCE_UNCLAIM')).toBe('changeHistory.forceUnclaim')
    expect(getChangeTypeLabel('REASSIGN')).toBe('changeHistory.reassign')
  })
})