import { describe, expect, it } from 'vitest'
import {
  isGatewayUpstreamDnsFailure,
  pickHttpErrorBodyMessage,
  pickHttpErrorCode,
  resolveUserFacingHttpMessage,
} from '@/utils/httpErrorMessage'

describe('httpErrorMessage', () => {
  it('isGatewayUpstreamDnsFailure detects Kong upstream DNS errors', () => {
    expect(isGatewayUpstreamDnsFailure('name resolution failed')).toBe(true)
    expect(isGatewayUpstreamDnsFailure('failed to retry the dns/balancer resolver')).toBe(true)
    expect(isGatewayUpstreamDnsFailure('System SMTP host is not configured')).toBe(false)
  })

  it('resolveUserFacingHttpMessage maps Kong DNS body to gatewayUpstreamUnavailable', () => {
    const t = (key: string) =>
      key === 'api.gatewayUpstreamUnavailable' ? 'gateway retry hint' : key
    const msg = resolveUserFacingHttpMessage(
      { response: { status: 503, data: { message: 'name resolution failed' } } },
      t,
    )
    expect(msg).toBe('gateway retry hint')
  })

  it('pickHttpErrorBodyMessage renders validation detail objects instead of [object Object]', () => {
    const msg = pickHttpErrorBodyMessage({
      success: false,
      error: {
        code: 'AI_VALIDATION_FAILED',
        message: 'AI generated data validation failed',
        details: {
          errors: [
            { errorType: 'REFERENCE_INTEGRITY', fieldPath: 'formDefinitions[0].tableBindings[0].tableName', description: "Referenced table 'ghost' does not exist" },
            { errorType: 'FIELD_CONSTRAINT', description: 'name must not be empty' },
          ],
        },
      },
    })
    expect(msg).toBe(
      "AI generated data validation failed (formDefinitions[0].tableBindings[0].tableName: Referenced table 'ghost' does not exist; name must not be empty)",
    )
    expect(msg).not.toContain('[object Object]')
  })

  it('pickHttpErrorBodyMessage reads Kong JSON message field', () => {
    expect(pickHttpErrorBodyMessage({ message: 'name resolution failed' })).toBe(
      'name resolution failed',
    )
  })

  it('reads platform ApiResponse.error message and code', () => {
    const body = {
      success: false,
      error: {
        code: 'COMPUTED_FIELD_TYPE_MISMATCH',
        message: "Computed field 'day' produces a number but the column is declared as VARCHAR.",
      },
    }
    expect(pickHttpErrorBodyMessage(body)).toBe(
      "Computed field 'day' produces a number but the column is declared as VARCHAR.",
    )
    expect(pickHttpErrorCode(body)).toBe('COMPUTED_FIELD_TYPE_MISMATCH')
  })
})
