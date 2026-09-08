import type { ExtractionFieldRule, ExtractionSubTableRule } from '@/api/emailMonitor'

export interface ExtractionSamplePreview {
  subject: string
  from: string
  to: string
  cc: string
  replyTo: string
  date: string
  messageId: string
  text: string
  html: string
}

export function attributePreviewValue(
  sample: ExtractionSamplePreview,
  source?: string,
): string | null {
  if (source === 'SUBJECT') return sample.subject?.trim() || null
  if (source === 'FROM') return sample.from?.trim() || null
  if (source === 'TO') return sample.to?.trim() || null
  if (source === 'CC') return sample.cc?.trim() || null
  if (source === 'REPLY_TO') return sample.replyTo?.trim() || null
  if (source === 'DATE') return sample.date?.trim() || null
  if (source === 'MESSAGE_ID') return sample.messageId?.trim() || null
  return null
}

export function stripHtml(html: string): string {
  if (!html) return ''
  const doc = new DOMParser().parseFromString(html, 'text/html')
  return doc.body?.textContent ?? ''
}

export function combinedSampleTextAndHtml(sample: ExtractionSamplePreview): string {
  const plain = sample.text?.trim() ?? ''
  const html = stripHtml(sample.html)?.trim() ?? ''
  if (!plain) return html
  if (!html) return plain
  if (plain.includes(html) || html.includes(plain)) {
    return plain.length >= html.length ? plain : html
  }
  return `${plain}\n${html}`
}

export function sourceText(sample: ExtractionSamplePreview, source?: string): string {
  if (source === 'SUBJECT') return sample.subject
  if (source === 'HTML') return stripHtml(sample.html)
  if (source === 'TEXT' || source === 'TEXT_AND_HTML') return combinedSampleTextAndHtml(sample)
  return sample.text
}

export function previewField(sample: ExtractionSamplePreview, rule: ExtractionFieldRule): string {
  try {
    let raw: string | null = null
    if (rule.type === 'DIRECT') {
      raw = attributePreviewValue(sample, rule.source) ?? sourceText(sample, rule.source)
    } else if (rule.type === 'CONST') raw = rule.value ?? null
    else if (rule.type === 'HEADER') raw = headerPreview(sample, rule.header)
    else if (rule.type === 'LABEL') raw = byLabel(sourceText(sample, rule.source), rule.label)
    else if (rule.type === 'BETWEEN') raw = between(sourceText(sample, rule.source), rule.before, rule.after)
    else if (rule.type === 'REGEX') raw = byRegex(sourceText(sample, rule.source), rule.pattern, rule.group ?? 1)
    return applyPost(raw, rule.postProcess) ?? '—'
  } catch {
    return '—'
  }
}

function headerPreview(sample: ExtractionSamplePreview, header?: string): string | null {
  const name = header?.toLowerCase()
  if (name === 'from') return sample.from || null
  if (name === 'to') return sample.to || null
  if (name === 'cc') return sample.cc || null
  if (name === 'reply-to') return sample.replyTo || null
  if (name === 'date') return sample.date || null
  if (name === 'message-id') return sample.messageId || null
  return null
}

function byLabel(text: string, label?: string): string | null {
  if (!text || !label) return null
  const idx = text.indexOf(label)
  if (idx < 0) return null
  const after = text.substring(idx + label.length)
  const eol = after.search(/[\r\n]/)
  return (eol < 0 ? after : after.substring(0, eol)).trim() || null
}

function between(text: string, before?: string, after?: string): string | null {
  if (!text || !before) return null
  const idx = text.indexOf(before)
  if (idx < 0) return null
  const start = idx + before.length
  const end = after ? text.indexOf(after, start) : -1
  return text.substring(start, end < 0 ? text.length : end).trim() || null
}

function byRegex(text: string, pattern?: string, group = 1): string | null {
  if (!text || !pattern) return null
  const m = new RegExp(pattern).exec(text)
  return m && m[group] != null ? m[group].trim() : null
}

function applyPost(value: string | null, steps?: string[]): string | null {
  if (value == null || !steps?.length) return value
  return steps.reduce((acc, step) => {
    if (acc == null) return acc
    if (step === 'TRIM') return acc.trim()
    if (step === 'DIGITS_ONLY') return acc.replace(/[^0-9]/g, '')
    if (step === 'STRIP_CURRENCY') return acc.replace(/[^0-9.,-]/g, '').trim()
    if (step === 'UPPER') return acc.toUpperCase()
    if (step === 'LOWER') return acc.toLowerCase()
    return acc
  }, value as string | null)
}

export function computePreviewRows(st: ExtractionSubTableRule, doc: Document): Record<string, string>[] {
  if (!st.columns.length) return []
  const selector = st.tableSelector?.trim() || 'table'
  let tables: Element[]
  try {
    tables = Array.from(doc.querySelectorAll(selector))
  } catch {
    return []
  }
  const table = tables[st.tableIndex ?? 0]
  if (!table) return []
  const trs = Array.from(table.querySelectorAll('tr'))
  const rows = st.headerRow ? trs.slice(1) : trs
  return rows.map((tr) => {
    const cells = Array.from(tr.querySelectorAll('td,th'))
    const obj: Record<string, string> = {}
    st.columns.forEach((col) => {
      if (col.field && col.columnIndex != null && cells[col.columnIndex]) {
        obj[col.field] = (cells[col.columnIndex].textContent ?? '').trim()
      }
    })
    return obj
  }).filter((o) => Object.keys(o).length)
}
