/**
 * Designer Action configJson helpers for Require Comment / Confirm Message.
 * Target Status is intentionally unread.
 */

export class InvalidActionConfigJsonError extends Error {
  constructor() {
    super('INVALID_ACTION_CONFIG_JSON')
    this.name = 'InvalidActionConfigJsonError'
  }
}

export function parseActionConfigJson(configJson?: string | null): Record<string, unknown> {
  if (configJson == null || String(configJson).trim() === '') {
    return {}
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(configJson)
  } catch {
    throw new InvalidActionConfigJsonError()
  }
  if (parsed != null && typeof parsed === 'object' && !Array.isArray(parsed)) {
    return parsed as Record<string, unknown>
  }
  throw new InvalidActionConfigJsonError()
}

/** `null` means the payload is not an object JSON — callers must surface an error. */
export function tryParseActionConfigJson(configJson?: string | null): Record<string, unknown> | null {
  try {
    return parseActionConfigJson(configJson)
  } catch {
    return null
  }
}

export function actionRequiresComment(config: Record<string, unknown>): boolean {
  return config.requireComment === true
}

export function actionConfirmMessage(config: Record<string, unknown>): string | null {
  if (typeof config.confirmMessage !== 'string') {
    return null
  }
  const msg = config.confirmMessage.trim()
  return msg === '' ? null : msg
}
