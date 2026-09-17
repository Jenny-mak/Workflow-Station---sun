/**
 * 浏览器侧 DSP AMToken 读取。
 *
 * AI Generate 的模型调用直连集团 AI gateway，Bearer 凭证就是这个 token——后端不持有共享密钥，
 * 每轮对话都由前端把当前用户的 AMToken 经 `X-AM-Token` 头透传下去，gateway 侧因此审计到人。
 *
 * 取值顺序与统一登录页的 DSP 免密流程一致（见 `frontend/login/src/api/dsp.ts`）：
 * URL 上显式注入的 `?am_token=` 优先（mock / 排障用），否则读 `AMToken` cookie。
 *
 * 取不到时返回空串：后端会以 `AI_GATEWAY_TOKEN_MISSING` 显式失败，不会退化成匿名调用。
 */

/** cookie 名，与后端 `ai-generation.gateway.am-token-name` / admin-center `sso.dsp.am-token-name` 对齐。 */
const AM_TOKEN_COOKIE = 'AMToken'

/** 从 URL `?am_token=` 读取显式注入的 AMToken。 */
function readQueryAmToken(): string {
  try {
    return new URLSearchParams(window.location.search).get('am_token') ?? ''
  } catch {
    return ''
  }
}

/** 从 `AMToken` cookie 读取。HttpOnly cookie 读不到，此时返回空串。 */
function readCookieAmToken(): string {
  const match = document.cookie.match(new RegExp(`(?:^|;\\s*)${AM_TOKEN_COOKIE}=([^;]+)`))
  return match ? decodeURIComponent(match[1]) : ''
}

/** 取当前浏览器侧 AMToken；取不到返回空串。 */
export function readAmToken(): string {
  return readQueryAmToken() || readCookieAmToken()
}

/** 透传 AMToken 的请求头；读不到就不带（后端以 AI_GATEWAY_TOKEN_MISSING 显式失败，不做匿名调用）。 */
export function amTokenHeaders(): Record<string, string> {
  const amToken = readAmToken()
  return amToken ? { 'X-AM-Token': amToken } : {}
}
