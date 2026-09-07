/** Optional login refresh used when {@code /upload} returns 401. */
type UploadAuthRefresher = () => Promise<boolean>

let refresher: UploadAuthRefresher | null = null

export function setUploadAuthRefresher(fn: UploadAuthRefresher | null): void {
  refresher = fn
}

export async function refreshUploadAuth(): Promise<boolean> {
  if (!refresher) return false
  try {
    return await refresher()
  } catch {
    return false
  }
}

export const UPLOAD_HTTP_UNAUTHORIZED = 'UPLOAD_HTTP_401'

export function isUploadUnauthorizedError(error: unknown): boolean {
  return error instanceof Error && error.message === UPLOAD_HTTP_UNAUTHORIZED
}
