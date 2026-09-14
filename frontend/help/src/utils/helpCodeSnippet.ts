export type HelpCodeLang = 'js' | 'formula'
export type HelpCodeKind = 'snippet' | 'label'
export type HelpTokenType = 'kw' | 'str' | 'num' | 'fn' | 'cm' | 'op' | 'plain'

export interface HelpCodeToken {
  type: HelpTokenType
  text: string
}

const KEYWORDS = new Set([
  'if',
  'else',
  'var',
  'return',
  'true',
  'false',
  'null',
  'function',
  'typeof',
  'new',
  'this',
])

const FORMULA_FNS = new Set([
  'SUM',
  'AVG',
  'MIN',
  'MAX',
  'COUNT',
  'DATEDIFF',
  'IF',
  'AND',
  'OR',
  'SWITCH',
  'COALESCE',
])

const JS_START = /^\s*(if|var|function)\b/
const HAS_API = /\bapi\.|\$inject\b/
const HAS_FORMULA_FN = /\b(SUM|AVG|MIN|MAX|COUNT|DATEDIFF|IF|AND|OR|SWITCH|COALESCE)\s*\(/
const BINARY_OP = /[a-z_][a-z0-9_]*(?:\.[a-z_][a-z0-9_]*)?\s+[*+-]\s+[a-z_]/
const FIELD_REF = /^[A-Za-z_]\w*\.[A-Za-z_]\w*$/

export function detectHelpCodeLang(code: string): HelpCodeLang | null {
  const text = code.trim()
  if (!text) return null
  if (HAS_API.test(text) || JS_START.test(text)) return 'js'
  if (HAS_FORMULA_FN.test(text) || BINARY_OP.test(text) || FIELD_REF.test(text)) return 'formula'
  return null
}

export function resolveHelpCodeKind(
  code: string,
  kind?: HelpCodeKind,
): HelpCodeKind {
  if (kind) return kind
  return isHelpCopyWorth(code) ? 'snippet' : 'label'
}

/**
 * Copy only when the reader can paste into Formula or Event Edit and run it.
 * Property labels, button names, signatures, and parameter lists are not worth copying.
 */
export function isHelpCopyWorth(code: string, kind?: HelpCodeKind): boolean {
  if (kind === 'label') return false
  const text = code.trim()
  if (!text || isHelpSignatureStub(text)) return false
  const lang = detectHelpCodeLang(text) ?? (kind === 'snippet' ? 'js' : null)
  if (!lang) return false
  if (lang === 'js') return HAS_API.test(text)
  return true
}

function isHelpSignatureStub(text: string): boolean {
  return /(\.{3}|…)/.test(text) || /\bdefault\?/.test(text)
}

export function resolveHelpCodeLang(
  code: string,
  lang?: HelpCodeLang,
): HelpCodeLang {
  if (lang) return lang
  return detectHelpCodeLang(code) ?? 'js'
}

export function formatHelpSnippet(code: string, lang: HelpCodeLang): string {
  const text = code.replace(/\r\n/g, '\n').trim()
  if (lang !== 'js' || text.includes('\n')) return text
  return expandJsBlocks(text)
}

function expandJsBlocks(src: string): string {
  let out = ''
  let i = 0
  let paren = 0
  let brace = 0
  let indent = 0
  while (i < src.length) {
    const ch = src[i]
    if (ch === "'" || ch === '"') {
      const taken = readQuoted(src, i)
      out += taken.text
      i = taken.end
      continue
    }
    if (ch === '(') {
      paren += 1
      out += ch
      i += 1
      continue
    }
    if (ch === ')') {
      paren -= 1
      out += ch
      i += 1
      continue
    }
    const next = expandBrace(src, i, ch, paren, brace, indent, out)
    if (next) {
      out = next.out
      i = next.i
      brace = next.brace
      indent = next.indent
      continue
    }
    if (ch === ';' && paren === 0 && brace > 0) {
      indent = Math.max(indent, 1)
      out += '\n' + '  '.repeat(indent)
      i += 1
      if (src[i] === ' ') i += 1
      continue
    }
    out += ch
    i += 1
  }
  return out
}

function expandBrace(
  src: string,
  i: number,
  ch: string,
  paren: number,
  brace: number,
  indent: number,
  out: string,
): { out: string; i: number; brace: number; indent: number } | null {
  if (paren !== 0) return null
  if (ch === '{') {
    const nextIndent = indent + 1
    let cursor = i + 1
    if (src[cursor] === ' ') cursor += 1
    return {
      out: out + '{\n' + '  '.repeat(nextIndent),
      i: cursor,
      brace: brace + 1,
      indent: nextIndent,
    }
  }
  if (ch !== '}') return null
  const nextIndent = Math.max(0, indent - 1)
  let nextOut = out.replace(/[ \t]+$/, '') + '\n' + '  '.repeat(nextIndent) + '}'
  let cursor = i + 1
  const elseMatch = src.slice(cursor).match(/^\s*else\b/)
  if (elseMatch) {
    nextOut += ' else'
    cursor += elseMatch[0].length
  }
  return { out: nextOut, i: cursor, brace: Math.max(0, brace - 1), indent: nextIndent }
}

function readQuoted(src: string, start: number): { text: string; end: number } {
  const quote = src[start]
  let i = start + 1
  while (i < src.length) {
    if (src[i] === '\\' && i + 1 < src.length) {
      i += 2
      continue
    }
    if (src[i] === quote) {
      return { text: src.slice(start, i + 1), end: i + 1 }
    }
    i += 1
  }
  return { text: src.slice(start), end: src.length }
}

export function tokenizeHelpCode(code: string): HelpCodeToken[] {
  const tokens: HelpCodeToken[] = []
  let i = 0
  while (i < code.length) {
    const rest = code.slice(i)
    if (rest.startsWith('//')) {
      const end = rest.indexOf('\n')
      const take = end === -1 ? rest : rest.slice(0, end)
      tokens.push({ type: 'cm', text: take })
      i += take.length
      continue
    }
    if (rest[0] === "'" || rest[0] === '"') {
      const taken = readQuoted(code, i)
      tokens.push({ type: 'str', text: taken.text })
      i = taken.end
      continue
    }
    const word = rest.match(/^[A-Za-z_$][\w$]*/)
    if (word) {
      tokens.push({ type: classifyWord(word[0], tokens), text: word[0] })
      i += word[0].length
      continue
    }
    const num = rest.match(/^\d+(?:\.\d+)?/)
    if (num) {
      tokens.push({ type: 'num', text: num[0] })
      i += num[0].length
      continue
    }
    tokens.push({ type: rest[0] === ' ' || rest[0] === '\n' ? 'plain' : 'op', text: rest[0] })
    i += 1
  }
  return mergePlainTokens(tokens)
}

function classifyWord(word: string, tokens: HelpCodeToken[]): HelpTokenType {
  if (KEYWORDS.has(word)) return 'kw'
  if (FORMULA_FNS.has(word)) return 'fn'
  const prev = lastNonSpace(tokens)
  if (prev?.text === '.') return 'fn'
  return 'plain'
}

function lastNonSpace(tokens: HelpCodeToken[]): HelpCodeToken | undefined {
  for (let i = tokens.length - 1; i >= 0; i -= 1) {
    if (tokens[i].text.trim()) return tokens[i]
  }
  return undefined
}

function mergePlainTokens(tokens: HelpCodeToken[]): HelpCodeToken[] {
  const merged: HelpCodeToken[] = []
  for (const token of tokens) {
    const last = merged[merged.length - 1]
    if (last && last.type === token.type && (token.type === 'plain' || token.type === 'op')) {
      last.text += token.text
    } else {
      merged.push({ ...token })
    }
  }
  return merged
}

export async function copyHelpText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
    return copyWithTextarea(text)
  } catch {
    return copyWithTextarea(text)
  }
}

function copyWithTextarea(text: string): boolean {
  const el = document.createElement('textarea')
  el.value = text
  el.setAttribute('readonly', '')
  el.style.position = 'fixed'
  el.style.left = '-9999px'
  document.body.appendChild(el)
  el.select()
  let ok = false
  try {
    ok = document.execCommand('copy')
  } finally {
    document.body.removeChild(el)
  }
  return ok
}
