/**
 * Designer Action configJson helpers for Require Comment / Confirm Message.
 * Target Status is intentionally unread.
 */

export function parseActionConfigJson(configJson?: string | null): Record<string, unknown> {
  if (configJson == null || String(configJson).trim() === '') {
    return {}
  }
  try {
    const parsed: unknown = JSON.parse(configJson)
    if (parsed != null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>
    }
    return {}
  } catch {
    return {}
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
