/** form-create stock Upload (Basic palette). */
export const NATIVE_UPLOAD_TYPE = 'upload'

/** Platform Advanced Upload (Extend palette): cards, size cap, FileNet. */
export const ADVANCED_UPLOAD_TYPE = 'advancedUpload'

export function isNativeUploadType(type?: string | null): boolean {
  return type === NATIVE_UPLOAD_TYPE
}

export function isAdvancedUploadType(type?: string | null): boolean {
  return type === ADVANCED_UPLOAD_TYPE
}

export function isAnyUploadType(type?: string | null): boolean {
  return isNativeUploadType(type) || isAdvancedUploadType(type)
}

/** Portal FormRenderer field type for both designer upload widgets. */
export function mapDesignerUploadToPortalFieldType(ruleType?: string | null): 'upload' | undefined {
  return isAnyUploadType(ruleType) ? NATIVE_UPLOAD_TYPE : undefined
}

function isPositiveInt(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 1
}

/**
 * Saved Basic `upload` rules that already carry platform props were created
 * before Advanced Upload was split out. Do not use resolveUploadMaxFiles here —
 * that defaults every bare upload to 10.
 */
export function ruleHasAdvancedUploadProps(
  rule: { props?: Record<string, unknown> | null; cannotDownload?: unknown } | null | undefined,
): boolean {
  if (!rule) return false
  if (rule.cannotDownload === true) return true
  const props = rule.props
  if (!props || typeof props !== 'object') return false
  if (props.cannotDownload === true || props.canNotDownload === true) return true
  const fileNet = props.fileNet
  if (fileNet && typeof fileNet === 'object' && (fileNet as { enabled?: unknown }).enabled === true) {
    return true
  }
  if (isPositiveInt(props.maxFiles) || isPositiveInt(props.maxFileSizeMb)) return true
  return false
}

export function isAdvancedUploadRule(
  rule: { type?: string; props?: Record<string, unknown> | null; cannotDownload?: unknown } | null | undefined,
): boolean {
  if (!rule) return false
  if (isAdvancedUploadType(rule.type)) return true
  return isNativeUploadType(rule.type) && ruleHasAdvancedUploadProps(rule)
}

/** Rewrite a legacy enhanced `upload` rule to `advancedUpload`. Returns true when changed. */
export function promoteLegacyAdvancedUploadRule(rule: Record<string, unknown> | null | undefined): boolean {
  if (!rule || typeof rule !== 'object') return false
  if (rule.type !== NATIVE_UPLOAD_TYPE) return false
  if (!ruleHasAdvancedUploadProps(rule as { props?: Record<string, unknown>; cannotDownload?: unknown })) {
    return false
  }
  rule.type = ADVANCED_UPLOAD_TYPE
  return true
}

export function walkPromoteLegacyAdvancedUploadRules(rules: unknown): void {
  if (!Array.isArray(rules)) return
  const stack = [...rules] as Array<Record<string, unknown>>
  while (stack.length) {
    const rule = stack.pop()
    if (!rule || typeof rule !== 'object') continue
    promoteLegacyAdvancedUploadRule(rule)
    const props = rule.props && typeof rule.props === 'object'
      ? rule.props as Record<string, unknown>
      : undefined
    const children = [rule.children, props?.children, props?.list, props?.items, props?.fields]
      .find(Array.isArray) as unknown[] | undefined
    if (children) {
      for (const child of children) {
        if (child && typeof child === 'object') stack.push(child as Record<string, unknown>)
      }
    }
  }
}
