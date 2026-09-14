export interface HelpSearchEntry {
  id: string
  path: string
  titleKey: string
  summaryKey: string
}

export function filterHelpSearch(
  entries: readonly HelpSearchEntry[],
  query: string,
  translate: (key: string) => string,
): HelpSearchEntry[] {
  const q = query.trim().toLowerCase()
  if (!q) return []
  const scored: { entry: HelpSearchEntry; score: number }[] = []
  for (const entry of entries) {
    const title = translate(entry.titleKey).toLowerCase()
    const summary = translate(entry.summaryKey).toLowerCase()
    const path = entry.path.toLowerCase()
    let score = 0
    if (title === q) score = 100
    else if (title.startsWith(q)) score = 80
    else if (title.includes(q)) score = 60
    else if (path.includes(q.replace(/\s+/g, '-'))) score = 40
    else if (summary.includes(q)) score = 20
    if (score) scored.push({ entry, score })
  }
  scored.sort((a, b) => b.score - a.score)
  return scored.slice(0, 8).map((row) => row.entry)
}
