import axios from 'axios'
import { ElMessage } from 'element-plus'
import { refreshToken as refreshAuthToken, clearAuth, getUser } from './auth'
import i18n from '@/i18n'
import { pickHttpErrorBodyMessage } from '@/utils/httpErrorMessage'
import { redirectToUnifiedLogin, setSsoReturnPath } from '@/utils/sso'

declare module 'axios' {
  interface AxiosRequestConfig {
    /** 调用方自己处理失败（如有本地回落），不弹全局错误提示；401 刷新与登录跳转照常 */
    silentError?: boolean
  }
}

let isRefreshing = false
let failedQueue: Array<{ resolve: Function; reject: Function }> = []

const processQueue = (error: any, token: string | null = null) => {
  failedQueue.forEach(prom => {
    if (error) {
      prom.reject(error)
    } else {
      prom.resolve(token)
    }
  })
  failedQueue = []
}

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  withCredentials: true
})

api.interceptors.request.use(
  config => {
    // This UI is English-only; without the header the backend answers in the browser's language.
    config.headers['Accept-Language'] = i18n.global.locale.value

    // Add X-User-Id request header for backend permission check
    const user = getUser()
    if (user && user.userId) {
      config.headers['X-User-Id'] = user.userId
    }
    
    return config
  },
  error => Promise.reject(error)
)

api.interceptors.response.use(
  response => response.data,
  async error => {
    const originalRequest = error.config
    const { response } = error
    
    if (response?.status === 401 && !originalRequest._retry) {
      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          failedQueue.push({ resolve, reject })
        }).then(() => {
          return api(originalRequest)
        }).catch(err => Promise.reject(err))
      }

      originalRequest._retry = true
      isRefreshing = true

      try {
        await refreshAuthToken()
        // refresh_token cookie auto-sent by the browser; backend sets new access_token cookie
        processQueue(null, null)
        return api(originalRequest)
      } catch (refreshError) {
        processQueue(refreshError, null)
        clearAuth()
        setSsoReturnPath(window.location.pathname + window.location.search)
        redirectToUnifiedLogin('developer-workstation', { autoSso: true })
        return Promise.reject(refreshError)
      } finally {
        isRefreshing = false
      }
    }

    if (originalRequest?.silentError) {
      return Promise.reject(error)
    }

    if (response) {
      const errorMsg = pickHttpErrorBodyMessage(response.data)

      switch (response.status) {
        case 401:
          ElMessage.error(errorMsg || i18n.global.t('api.unauthorized'))
          break
        case 403:
          // 403 indicates insufficient permissions; if no user stored, redirect to login
          const user = getUser()
          if (!user) {
            clearAuth()
            setSsoReturnPath(window.location.pathname + window.location.search)
            redirectToUnifiedLogin('developer-workstation', { autoSso: true })
            ElMessage.warning(i18n.global.t('api.pleaseLogin'))
          } else {
            ElMessage.error(errorMsg || i18n.global.t('api.noPermission'))
          }
          break
        case 400:
          ElMessage.error(errorMsg || i18n.global.t('api.requestFailed'))
          break
        case 404:
          ElMessage.error(errorMsg || i18n.global.t('api.requestFailed'))
          break
        case 422:
          ElMessage.error(errorMsg || i18n.global.t('api.requestFailed'))
          break
        case 429:
          ElMessage.warning(i18n.global.t('api.tooManyRequests'))
          break
        case 500:
          ElMessage.error(errorMsg || i18n.global.t('api.requestFailed'))
          break
        case 502:
        case 503:
          ElMessage.error(i18n.global.t('api.networkError'))
          break
        default:
          ElMessage.error(errorMsg || i18n.global.t('api.requestFailed'))
      }
    } else {
      // Skip global error message for AI generation endpoints — AiPanel handles its own errors
      const url = error.config?.url || ''
      if (!url.includes('/ai-generation/')) {
        ElMessage.error(i18n.global.t('api.networkError'))
      }
    }
    return Promise.reject(error)
  }
)

export default api
