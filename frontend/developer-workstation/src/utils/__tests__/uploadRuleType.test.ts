import { describe, expect, it } from 'vitest'
import {
  ADVANCED_UPLOAD_TYPE,
  NATIVE_UPLOAD_TYPE,
  isAdvancedUploadRule,
  promoteLegacyAdvancedUploadRule,
  ruleHasAdvancedUploadProps,
} from '@platform-shared/upload/uploadRuleType'

describe('uploadRuleType', () => {
  it('does not treat stock Basic upload as Advanced Upload', () => {
    const rule = { type: NATIVE_UPLOAD_TYPE, props: { action: '/', limit: 1 } }
    expect(ruleHasAdvancedUploadProps(rule)).toBe(false)
    expect(isAdvancedUploadRule(rule)).toBe(false)
  })

  it('promotes a saved upload that already has platform props', () => {
    const rule: Record<string, unknown> = {
      type: NATIVE_UPLOAD_TYPE,
      props: { maxFiles: 10 },
    }
    expect(promoteLegacyAdvancedUploadRule(rule)).toBe(true)
    expect(rule.type).toBe(ADVANCED_UPLOAD_TYPE)
  })
})
