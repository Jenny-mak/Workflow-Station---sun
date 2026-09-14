import { describe, expect, it } from 'vitest'
import { formCtlHelpPath } from '../formCtlHelpPath'

describe('formCtlHelpPath', () => {
  it('returns the Basic index when nothing is selected', () => {
    expect(formCtlHelpPath(null)).toBe('/form-events-basic')
    expect(formCtlHelpPath(undefined)).toBe('/form-events-basic')
  })

  it('maps Input, Textarea, and Password from type and props.type', () => {
    expect(formCtlHelpPath({ type: 'input' })).toBe('/form-ctl-input')
    expect(formCtlHelpPath({ type: 'input', props: { type: 'textarea' } })).toBe('/form-ctl-textarea')
    expect(formCtlHelpPath({ type: 'input', props: { type: 'password' } })).toBe('/form-ctl-password')
  })

  it('maps DateRange and TimeRange from picker props', () => {
    expect(formCtlHelpPath({ type: 'datePicker' })).toBe('/form-ctl-date')
    expect(formCtlHelpPath({ type: 'datePicker', props: { type: 'daterange' } })).toBe('/form-ctl-date-range')
    expect(formCtlHelpPath({ type: 'timePicker' })).toBe('/form-ctl-time')
    expect(formCtlHelpPath({ type: 'timePicker', props: { isRange: true } })).toBe('/form-ctl-time-range')
  })

  it('maps Hermes Editor and Transfer types', () => {
    expect(formCtlHelpPath({ type: 'editor' })).toBe('/form-ctl-editor')
    expect(formCtlHelpPath({ type: 'transfer' })).toBe('/form-ctl-transfer')
    expect(formCtlHelpPath({ type: 'select' })).toBe('/form-ctl-select')
  })

  it('maps Extend Sub-Table and Lookup to their articles', () => {
    expect(formCtlHelpPath({ type: 'subTable' })).toBe('/form-ctl-sub-table')
    expect(formCtlHelpPath({ type: 'lookup' })).toBe('/form-ctl-lookup')
    expect(formCtlHelpPath({ type: 'advancedUpload' })).toBe('/form-upload')
  })
})
