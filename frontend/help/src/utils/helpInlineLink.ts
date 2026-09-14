import { BASIC_FORM_CONTROLS } from '../formCtlBasic.ts'
import { EXTEND_FORM_CONTROLS } from '../formCtlExtend.ts'

/** Wiki token `[[/path]]` or `[[/path#hash]]`. No `{ }` — vue-i18n interpolates those. */
const TOKEN_RE = /\[\[(\/[A-Za-z0-9\-/#]+)\]\]/g
const PATH_RE = /^\/[A-Za-z0-9\-/#]+$/

const PATH_TITLE: Record<string, string> = {
  '/form-events': 'nav.formEventsHub',
  '/form-events-basic': 'guides.formEventsBasic.title',
  '/form-events-extend': 'guides.formEventsExtend.title',
  '/form-events-extend#owner': 'nav.formCtlOwner',
  '/form-events-extend#lookup': 'nav.formCtlLookup',
  '/form-ctl-lookup#events': 'nav.formCtlLookup',
  '/form-ctl-sub-table#events': 'nav.formCtlSubTable',
  '/form-events-extend#inlineSubForm': 'nav.formCtlInlineForm',
  '/form-events-extend#linkForm': 'nav.formCtlLinkForm',
  '/form-events-extend#recordNote': 'nav.formCtlRecordNote',
  '/form-events-extend#miAssignment': 'nav.formCtlMiAssignment',
  '/form-events-layout': 'guides.formEventsLayout.title',
  '/form-upload': 'guides.formUpload.title',
  '/computed-fields': 'guides.computedFields.title',
  '/table-design': 'guides.tableDesign.title',
  '/view-design': 'guides.viewDesign.title',
}

for (const ctl of BASIC_FORM_CONTROLS) {
  PATH_TITLE[ctl.path] = ctl.navTitleKey
}
for (const ctl of EXTEND_FORM_CONTROLS) {
  PATH_TITLE[ctl.path] = ctl.navTitleKey
}

export type HelpInlinePart =
  | { kind: 'text'; text: string }
  | { kind: 'link'; to: string; titleKey: string }

export function helpInlineTitleKey(to: string): string | undefined {
  if (!PATH_RE.test(to) || to.includes('//')) return undefined
  return PATH_TITLE[to] ?? PATH_TITLE[to.split('#')[0] ?? '']
}

export function parseHelpInlineText(source: string): HelpInlinePart[] {
  const parts: HelpInlinePart[] = []
  let last = 0
  TOKEN_RE.lastIndex = 0
  let match = TOKEN_RE.exec(source)
  while (match) {
    if (match.index > last) {
      parts.push({ kind: 'text', text: source.slice(last, match.index) })
    }
    const to = match[1]
    const titleKey = helpInlineTitleKey(to)
    if (titleKey) {
      parts.push({ kind: 'link', to, titleKey })
    } else {
      parts.push({ kind: 'text', text: match[0] })
    }
    last = match.index + match[0].length
    match = TOKEN_RE.exec(source)
  }
  if (last < source.length) {
    parts.push({ kind: 'text', text: source.slice(last) })
  }
  return parts.length ? parts : [{ kind: 'text', text: source }]
}
