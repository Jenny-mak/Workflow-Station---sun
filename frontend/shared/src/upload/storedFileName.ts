/** Extract the stored file name from a platform upload URL. */
export function extractStoredFileName(url: string): string {
  const trimmed = url.trim()
  if (!trimmed) return ''
  const path = trimmed.split('#')[0].split('?')[0]
  const marker = '/upload/files/'
  const idx = path.toLowerCase().lastIndexOf(marker)
  if (idx < 0) return ''
  const name = path.slice(idx + marker.length)
  if (!name || name.includes('/') || name.includes('\\') || name.includes('..')) return ''
  return name
}

export function extractStoredFileNames(urls: string[]): string[] {
  const names: string[] = []
  const seen = new Set<string>()
  for (const url of urls) {
    const name = extractStoredFileName(url)
    if (!name || seen.has(name)) continue
    seen.add(name)
    names.push(name)
  }
  return names
}
