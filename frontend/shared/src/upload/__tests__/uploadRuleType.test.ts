import { describe, expect, it } from 'vitest'
import {
  ADVANCED_UPLOAD_TYPE,
  NATIVE_UPLOAD_TYPE,
  isAdvancedUploadRule,
  isAnyUploadType,
  mapDesignerUploadToPortalFieldType,
  promoteLegacyAdvancedUploadRule,
  ruleHasAdvancedUploadProps,
  walkPromoteLegacyAdvancedUploadRules,
} from '../uploadRuleType'

describe('uploadRuleType', () => {
  it('treats stock upload without platform props as native', () => {
    const rule = { type: NATIVE_UPLOAD_TYPE, props: { action: '/', multiple: false, limit: 1 } }
    expect(ruleHasAdvancedUploadProps(rule)).toBe(false)
    expect(isAdvancedUploadRule(rule)).toBe(false)
    expect(promoteLegacyAdvancedUploadRule(rule)).toBe(false)
    expect(rule.type).toBe(NATIVE_UPLOAD_TYPE)
  })

  it('does not treat a disabled FileNet stub as Advanced Upload', () => {
    const rule = {
      type: NATIVE_UPLOAD_TYPE,
      props: { fileNet: { enabled: false, headerInfo: [] } },
    }
    expect(ruleHasAdvancedUploadProps(rule)).toBe(false)
    expect(isAdvancedUploadRule(rule)).toBe(false)
  })

  it('promotes a saved upload that already has maxFiles', () => {
    const rule: Record<string, unknown> = {
      type: NATIVE_UPLOAD_TYPE,
      props: { maxFiles: 10, action: '/api/v1/upload' },
    }
    expect(isAdvancedUploadRule(rule)).toBe(true)
    expect(promoteLegacyAdvancedUploadRule(rule)).toBe(true)
    expect(rule.type).toBe(ADVANCED_UPLOAD_TYPE)
  })

  it('walks nested children', () => {
    const rules = [{
      type: 'elCol',
      children: [{ type: NATIVE_UPLOAD_TYPE, props: { maxFileSizeMb: 20 } }],
    }]
    walkPromoteLegacyAdvancedUploadRules(rules)
    expect((rules[0].children[0] as { type: string }).type).toBe(ADVANCED_UPLOAD_TYPE)
  })

  it('recognizes both designer types', () => {
    expect(isAnyUploadType(NATIVE_UPLOAD_TYPE)).toBe(true)
    expect(isAnyUploadType(ADVANCED_UPLOAD_TYPE)).toBe(true)
    expect(isAnyUploadType('input')).toBe(false)
    expect(isAdvancedUploadRule({ type: ADVANCED_UPLOAD_TYPE, props: {} })).toBe(true)
    expect(mapDesignerUploadToPortalFieldType(ADVANCED_UPLOAD_TYPE)).toBe(NATIVE_UPLOAD_TYPE)
    expect(mapDesignerUploadToPortalFieldType('input')).toBeUndefined()
  })
})
