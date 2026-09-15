/** 单条明细 → 文案：校验错误对象（fieldPath/description）优先，其它对象取 message/description，再退到 key=value。 */
function detailEntryToText(v: unknown): string {
  if (v == null) return ''
  if (typeof v !== 'object') return String(v).trim()
  const o = v as Record<string, unknown>
  const desc = typeof o.description === 'string' ? o.description.trim()
    : typeof o.message === 'string' ? o.message.trim() : ''
  const path = typeof o.fieldPath === 'string' ? o.fieldPath.trim()
    : typeof o.field === 'string' ? o.field.trim() : ''
  if (path && desc) return `${path}: ${desc}`
  if (desc) return desc
  if (path) return path
  // 兜底：不再 String(obj) 出 "[object Object]"，而是 key=value 平铺
  return Object.entries(o)
    .filter(([, val]) => val != null && typeof val !== 'object')
    .map(([k, val]) => `${k}=${String(val)}`)
    .join(', ')
}

function flattenUnknownDetails(details: unknown): string | undefined {
  if (details == null) return undefined
  if (typeof details === 'string' && details.trim().length > 0) return details.trim()
  if (typeof details === 'object' && details !== null && !Array.isArray(details)) {
    const vals = Object.values(details as Record<string, unknown>).flatMap(v =>
      Array.isArray(v) ? v : [v]
    )
    const parts = vals.map(detailEntryToText).map(s => s.trim()).filter(Boolean)
    if (parts.length) return parts.join('; ')
  }
  return undefined
}

/**
 * 从 HTTP 错误响应体提取用户可读文案。
 * 兼容：ApiResponse.error、RFC 7807、校验 details、纯字符串 body。
 */
export function pickHttpErrorBodyMessage(data: unknown): string | undefined {
  if (data == null || data === '') return undefined
  if (typeof data === 'string') {
    const s = data.trim()
    if (!s) return undefined
    if (s.startsWith('{') || s.startsWith('[')) {
      try {
        return pickHttpErrorBodyMessage(JSON.parse(s) as unknown)
      } catch {
        /* 非 JSON 则当作纯文案 */
      }
    }
    return s
  }
  if (typeof data !== 'object') return undefined
  const o = data as Record<string, unknown>

  const err = o.error

  // ① platform / developer ApiResponse：error 为对象，优先取其 message + details
  if (err && typeof err === 'object') {
    const e = err as Record<string, unknown>
    const msg =
      typeof e.message === 'string' && e.message.trim().length > 0 ? e.message.trim() : undefined
    const det = flattenUnknownDetails(e.details) ?? flattenUnknownDetails(o.details)
    if (msg && det && !msg.includes(det)) return `${msg} (${det})`
    if (msg) return msg
    if (det) return det
  }

  // ② 网关 / Spring / 手写 JSON：顶层 message（如 {\"error\":\"FORBIDDEN\",\"message\":\"...\"}）
  if (typeof o.message === 'string' && o.message.trim().length > 0) return o.message.trim()

  // ③ error 仅为字符串常量时最后再当文案（此前会盖住 ②）
  if (typeof err === 'string' && err.trim().length > 0) return err.trim()

  if (typeof o.detail === 'string' && o.detail.trim().length > 0) return o.detail.trim()
  if (typeof o.title === 'string' && o.title.trim().length > 0) return o.title.trim()

  const detRoot = flattenUnknownDetails(o.details)
  if (detRoot) return detRoot

  return undefined
}

/** Machine code from ApiResponse.error.code / errorCode, when the body is a platform error. */
export function pickHttpErrorCode(data: unknown): string | undefined {
  if (data == null || typeof data !== 'object') return undefined
  const o = data as Record<string, unknown>
  const err = o.error
  if (err && typeof err === 'object') {
    const e = err as Record<string, unknown>
    if (typeof e.code === 'string' && e.code.trim()) return e.code.trim()
    if (typeof e.errorCode === 'string' && e.errorCode.trim()) return e.errorCode.trim()
  }
  if (typeof o.code === 'string' && o.code.trim()) return o.code.trim()
  if (typeof o.errorCode === 'string' && o.errorCode.trim()) return o.errorCode.trim()
  return undefined
}

const AXIOS_STATUS_ONLY = /^Request failed with status code \d+$/i

/** Kong upstream DNS failure after backend container recreate (raw body: "name resolution failed"). */
export function isGatewayUpstreamDnsFailure(message: string | undefined): boolean {
  if (!message) return false
  return /name resolution failed|dns\/balancer resolver/i.test(message)
}

export function resolveUserFacingHttpMessage(error: unknown, t: (key: string) => string): string {
  const ax = error as {
    response?: { status?: number; data?: unknown }
    message?: string
    request?: unknown
  }
  const status = ax.response?.status
  const fromBody =
    ax.response?.data !== undefined ? pickHttpErrorBodyMessage(ax.response.data) : undefined
  if (fromBody) {
    if (isGatewayUpstreamDnsFailure(fromBody)) {
      return t('api.gatewayUpstreamUnavailable')
    }
    return fromBody
  }

  if (status === 401) return t('api.unauthorized')
  if (status === 403) return t('api.noPermission')
  if (status === 400) return t('api.invalidParams')
  if (status === 404) return t('api.notFound')
  if (status === 409) return t('api.conflict')
  if (status === 422) return t('api.businessError')
  if (status === 429) return t('api.tooManyRequests')
  if (status === 500) return t('api.serverError')
  if (status === 502 || status === 503) return t('api.gatewayUpstreamUnavailable')

  const raw = ax.message?.trim()
  if (raw && !AXIOS_STATUS_ONLY.test(raw)) return raw
  if (status) return t('api.requestFailed')
  if (ax.response === undefined && ax.request) return t('api.networkError')
  return t('api.requestFailed')
}
