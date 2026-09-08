export interface FileTransferRow {
  storedName: string
  fileDescription: string | null
}

interface ApiEnvelope<T> {
  success?: boolean
  data?: T
  error?: { message?: string }
}

const QUERY_URL = '/api/v1/upload/file-transfers/query'

function patchUrl(storedName: string): string {
  return `/api/v1/upload/file-transfers/${encodeURIComponent(storedName)}`
}

async function readJson<T>(response: Response): Promise<T> {
  const body = await response.json() as ApiEnvelope<T>
  if (!response.ok || body.success === false) {
    const message = body.error?.message || `Request failed (${response.status})`
    throw new Error(message)
  }
  if (body.data === undefined) {
    throw new Error('Empty file-transfer response')
  }
  return body.data
}

export async function queryFileTransfers(storedNames: string[]): Promise<FileTransferRow[]> {
  if (storedNames.length === 0) return []
  const response = await fetch(QUERY_URL, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ storedNames }),
  })
  return readJson<FileTransferRow[]>(response)
}

export async function updateFileDescription(
  storedName: string,
  fileDescription: string,
): Promise<FileTransferRow> {
  const response = await fetch(patchUrl(storedName), {
    method: 'PATCH',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileDescription }),
  })
  return readJson<FileTransferRow>(response)
}
