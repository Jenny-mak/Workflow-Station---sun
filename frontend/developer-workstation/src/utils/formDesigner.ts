/**
 * Form Designer Utilities
 * Pure functions for form designer operations — no Vue reactivity dependency.
 */

import type { FormDefinition, FormType } from '@/api/functionUnit'
import {
  isAdvancedUploadRule,
  isAnyUploadType,
} from '@platform-shared/upload/uploadRuleType'

const FORM_TYPE_SORT_ORDER: Record<FormType, number> = {
  PROCESS: 0,
  TASK: 1,
  ACTION: 2,
  DETAIL: 3,
}

/** Sort forms for Form Design list: Process → Task → Action → Detail, then by name. */
export function sortFormsByType(forms: FormDefinition[]): FormDefinition[] {
  return [...forms].sort((a, b) => {
    const typeDiff = FORM_TYPE_SORT_ORDER[a.formType] - FORM_TYPE_SORT_ORDER[b.formType]
    if (typeDiff !== 0) return typeDiff
    return a.formName.localeCompare(b.formName, undefined, { sensitivity: 'base' })
  })
}

/**
 * Which table a form renders. Single source of truth for every "group / filter forms by table"
 * caller, so the Views Form grouping and the View Design detail-form picker can never disagree
 * about where a form belongs.
 *
 * <p>Resolution order mirrors {@code useTableFieldRules.getPrimaryBindingFieldDefinitions}:
 * the PRIMARY binding, then the legacy {@code boundTableId}, and finally a lone binding of any
 * type — older forms exist whose only binding is not marked PRIMARY.
 */
export function resolveFormTableId(form: {
  tableBindings?: Array<{ bindingType?: string; tableId?: number | null }> | null
  boundTableId?: number | null
} | null | undefined): number | null {
  if (!form) return null
  const bindings = form.tableBindings ?? []
  const primary = bindings.find(
    b => String(b?.bindingType ?? '').toUpperCase() === 'PRIMARY',
  )
  if (primary?.tableId != null) return primary.tableId
  if (form.boundTableId != null) return form.boundTableId
  if (bindings.length === 1 && bindings[0]?.tableId != null) return bindings[0].tableId
  return null
}

/**
 * A MAIN-table row is a request, so the portal opens the request detail page for it rather than a
 * designed form. MAIN tables therefore get no Views Form group and bind no detail form.
 */
export function isMainTableDefinition(table: { tableType?: string | null } | null | undefined): boolean {
  return String(table?.tableType ?? '').toUpperCase() === 'MAIN'
}

export interface ViewsFormGroup {
  key: string
  label: string
  forms: any[]
  views: any[]
}

/**
 * Views Form content, grouped by table: the detail forms bound to each table alongside that
 * table's views, so a view's detail form is chosen next to the forms that can serve it.
 *
 * <p>Unlike View Design's grouping, empty groups are kept — a table whose views have no detail
 * form yet is exactly where a developer needs to make a selection.
 *
 * <p>The MAIN table is left out entirely, and so is any form it would have held: a DETAIL form
 * bound to the MAIN table can never be opened at runtime, because a MAIN row shows the request
 * detail page instead. Forms with no resolvable table are dropped for the same reason — they are
 * not reachable either. Only forms that a real table group claims are listed; use
 * {@link countGroupedViewsForms} for any badge that must agree with what is rendered.
 */
export function buildViewsFormGroups(
  forms: any[],
  tables: any[],
  mainTableViews: any[],
): ViewsFormGroup[] {
  const detailForms = (forms || []).filter(f => f?.formType === 'DETAIL')
  const formsByTable = new Map<number, any[]>()
  for (const form of detailForms) {
    const tableId = resolveFormTableId(form)
    if (tableId == null) continue
    const list = formsByTable.get(tableId) || []
    list.push(form)
    formsByTable.set(tableId, list)
  }

  const viewsByTable = new Map<number, any[]>()
  for (const view of mainTableViews || []) {
    const tableId = Number(view?.mainTableId)
    if (!Number.isFinite(tableId)) continue
    const list = viewsByTable.get(tableId) || []
    list.push(view)
    viewsByTable.set(tableId, list)
  }

  const groupedTables = (tables || []).filter(table => !isMainTableDefinition(table))
  const groups = groupedTables.map(table => ({
    key: `table-${table.id}`,
    label: table.tableDisplayName || table.tableName,
    forms: formsByTable.get(table.id) || [],
    views: (viewsByTable.get(table.id) || [])
      .slice()
      .sort((a, b) => String(a.viewName || '').localeCompare(String(b.viewName || ''))),
  }))
  // Only tables that can hold a form or a view are worth a heading.
  return groups.filter(g => g.forms.length > 0 || g.views.length > 0)
}

/**
 * How many DETAIL forms {@link buildViewsFormGroups} actually renders.
 *
 * <p>The Views tab badge cannot simply count DETAIL forms: one bound to the MAIN table, or to no
 * table at all, is deliberately not listed, and counting it would promise a row the tab never
 * draws.
 */
export function countGroupedViewsForms(
  forms: any[],
  tables: any[],
  mainTableViews: any[],
): number {
  return buildViewsFormGroups(forms, tables, mainTableViews)
    .reduce((total, group) => total + group.forms.length, 0)
}

/**
 * Deep-clone form rules via JSON round-trip. Falls back to shallow copy on failure.
 */
export function cloneFormRules(rules: any[]): any[] {
  if (!Array.isArray(rules) || rules.length === 0) return []
  try {
    return JSON.parse(JSON.stringify(rules))
  } catch {
    return rules.slice()
  }
}

/**
 * Walk rule tree and inject upload button labels so legacy saved rules
 * (which omit uploadText) never show hardcoded fallback text.
 */
export function injectUploadButtonLabels(rules: any[], uploadText: string): void {
  const walk = (items: any[]) => {
    for (const r of items) {
      if (!r || typeof r !== 'object') continue
      if (isAnyUploadType(r.type)) {
        r.props = r.props || {}
        if (r.props.uploadText == null || r.props.uploadText === '') {
          r.props.uploadText = uploadText
        }
      }
      if (Array.isArray(r.children) && r.children.length) walk(r.children)
    }
  }
  walk(rules)
}

import {
  applyUploadFileDisplayMeta,
  extractUploadNameFromResponse,
  extractUploadUrlFromResponse,
  getFilenameFromUrl,
} from '@/components/designer/uploadFieldUtils'
import { ensureEmptyFormOptionsEvents } from '@/utils/formCreateDefaultEvents'
import { extractFileLinks } from '@platform-shared/list/fileNames'
import {
  joinTargetFileNames,
  resolveUploadMaxFileSizeMb,
  resolveUploadMaxFiles,
  splitUploadFileList,
} from '@platform-shared/upload/uploadFieldValue'
import { queuedUploadRequest } from '@platform-shared/upload/queuedUploadRequest'

/**
 * Collect upload rules from a form-create rule tree (including nested layout children).
 */
export function collectUploadRulesFromTree(rules: any[]): Array<{ field: string; type: string; props?: Record<string, unknown> }> {
  const out: Array<{ field: string; type: string; props?: Record<string, unknown> }> = []
  const walk = (items: any[]) => {
    for (const r of items || []) {
      if (!r || typeof r !== 'object') continue
      if (isAnyUploadType(r.type) && r.field) {
        out.push({ field: String(r.field), type: String(r.type), props: r.props })
      }
      const children = getRuleChildren(r)
      if (children.length) walk(children)
    }
  }
  walk(rules)
  return out
}

/**
 * Wire form-create upload rules so successful uploads persist URL strings or file arrays.
 * form-create requires assigning file.url in onSuccess — otherwise v-model stays empty.
 */
export function injectPreviewUploadHandlers(
  rules: any[],
  formData: { value: Record<string, unknown> },
  uploadSession?: { value: Record<string, { url: string; name?: string }> },
): void {
  const walk = (items: any[]) => {
    for (const r of items) {
      if (!r || typeof r !== 'object') continue
      if (isAdvancedUploadRule(r) && r.field) {
        stampPreviewUploadRule(r, formData, uploadSession)
      }
      const children = getRuleChildren(r)
      if (children.length) walk(children)
    }
  }
  walk(rules)
}

function stampPreviewUploadRule(
  r: Record<string, any>,
  formData: { value: Record<string, unknown> },
  uploadSession?: { value: Record<string, { url: string; name?: string }> },
): void {
  r.props = r.props || {}
  if (!r.props.uploadType) r.props.uploadType = 'file'
  if (!r.props.action || r.props.action === '/') r.props.action = '/api/v1/upload'
  const maxFiles = resolveUploadMaxFiles(r.props)
  r.props.maxFiles = maxFiles
  r.props.limit = maxFiles
  r.props.multiple = maxFiles > 1
  r.props.maxFileSizeMb = resolveUploadMaxFileSizeMb(r.props)
  // Preview-only: shared drop zone (canvas stays native fcUpload so field drag still works).
  r.type = 'formUploadDrop'
  r.props.httpRequest = queuedUploadRequest
  const field = String(r.field)
  const nameTarget = r.props.fileNameTargetField as string | undefined

  r.props.onSuccess = (
    res: unknown,
    file?: { url?: string; name?: string; value?: unknown; response?: unknown },
  ) => {
    const url = extractUploadUrlFromResponse(res)
    const displayName = extractUploadNameFromResponse(res, file) || (url ? getFilenameFromUrl(url) : '')
    if (file && url && displayName) applyUploadFileDisplayMeta(file, url, displayName)
  }
  r.props.onChange = (
    _file?: { status?: string },
    fileList?: Array<{ url?: string; name?: string; status?: string; response?: unknown }>,
  ) => {
    const { stored, display } = splitUploadFileList(fileList ?? [], maxFiles)
    formData.value[field] = display
    const links = extractFileLinks(stored)
    if (uploadSession && links[0]) {
      uploadSession.value = { ...uploadSession.value, [field]: { url: links[0].url, name: links[0].name } }
    }
    if (nameTarget) formData.value[nameTarget] = joinTargetFileNames(links)
  }
  r.props.onRemove = (
    _file?: unknown,
    fileList?: Array<{ url?: string; name?: string; status?: string; response?: unknown }>,
  ) => {
    const { stored, display } = splitUploadFileList(fileList ?? [], maxFiles)
    formData.value[field] = display
    const links = extractFileLinks(stored)
    if (uploadSession) {
      const next = { ...uploadSession.value }
      if (links[0]) next[field] = { url: links[0].url, name: links[0].name }
      else delete next[field]
      uploadSession.value = next
    }
    if (nameTarget) formData.value[nameTarget] = joinTargetFileNames(links)
  }
}

/**
 * Depth-first walk of a form-create rule tree with cycle detection.
 * fc-designer layout nodes (el-row / el-col / card) can share object references;
 * unguarded recursion on Preview paths caused main-thread hangs.
 */
export function walkFormCreateRules(
  items: unknown[],
  visit: (rule: Record<string, unknown>) => void,
  visited: WeakSet<object> = new WeakSet<object>(),
): void {
  if (!Array.isArray(items)) return
  for (const raw of items) {
    if (!raw || typeof raw !== 'object') continue
    const rule = raw as Record<string, unknown>
    if (visited.has(rule)) continue
    visited.add(rule)
    visit(rule)
    walkFormCreateRules(getRuleChildren(rule), visit, visited)
  }
}

/**
 * Deep-clone designer rules before Preview transforms so getRule() references
 * are never mutated in place (ensureFormCreateRulesValidationDeep mutates).
 */
export function snapshotRulesForPreview(rules: unknown[] | null | undefined): any[] {
  return cloneFormRules(Array.isArray(rules) ? rules : [])
}

/**
 * Get children from any known nesting pattern in a form rule item.
 */
export function getRuleChildren(item: any): any[] {
  const childSources = [
    item?.children,
    item?.props?.children,
    item?.props?.list,
    item?.props?.items,
    item?.props?.fields,
  ]
  return childSources.find(children => Array.isArray(children)) || []
}

/** Layout containers whose children are real field rules (card set + FC_SKIP_PREVIEW). */
const LAYOUT_CONTAINER_TYPES = new Set([
  'el-card', 'elCard', 'card',
  'el-row', 'elRow', 'row',
  'el-col', 'elCol', 'col',
  'group', 'subForm', 'tableForm', 'tableFormColumn',
  // Assignment Mode container nests assignee/BU/role as children (see
  // nestAssignmentFieldsIntoContainer) — sub-table column derivation needs those
  // fields visible at top level, not hidden inside an unrecognized container type.
  'miAssignment',
])

/**
 * Expand layout containers (Card/Row/Col/…) into their children so field rules nested
 * inside them still participate in sub-table column derivation. Field-bearing rules and
 * placeholders (subTable/linkForm) pass through untouched, in document order.
 */
export function flattenRuleLayoutContainers(rules: any[]): any[] {
  if (!Array.isArray(rules)) return []
  const out: any[] = []
  const walk = (items: any[]) => {
    for (const item of items) {
      if (item && typeof item === 'object' && !item.field && LAYOUT_CONTAINER_TYPES.has(String(item.type))) {
        walk(getRuleChildren(item))
      } else {
        out.push(item)
      }
    }
  }
  walk(rules)
  return out
}

/**
 * Rule types that carry a SUB binding in `_bindingId`. Both must clear the same save-time
 * guards (binding selected + binding is of type SUB) and both need the top-level
 * `_bindingId` copied back into props for preview surfaces.
 */
export const SUB_BINDING_RULE_TYPES = new Set(['subTable', 'inlineSubForm'])

/**
 * MVP boundary enforcement for the Inline Form (`inlineSubForm`) widget: it may only be dropped
 * onto the main-form canvas, never into a SUB table's own Form Design canvas. Every fc-designer
 * instance (main + each sub-binding tab) shares the same designerConfig/component registration,
 * so nothing else stops a designer from placing it inside a sub-designer, where it can construct
 * an indirect self-reference cycle (binding A embeds an Inline Form pointing at B, whose own form
 * embeds one pointing back at A) that the runtime's visited-bindingId guard does not fully cover
 * either — see docs/design/inline-sub-form-component.md §关键约束 3.
 *
 * @param draggedRuleName the drag-rule name being dropped (fc-designer's `checkDrag` `menu.name`)
 * @param activeDesignerTabName the currently active designer tab ('main' or a binding id string)
 */
export function isInlineSubFormDropAllowed(
  draggedRuleName: string | undefined,
  activeDesignerTabName: string,
): boolean {
  if (draggedRuleName !== 'inlineSubForm') return true
  return activeDesignerTabName === 'main'
}

/**
 * Recursively collect all rules that bind a SUB table (subTable / inlineSubForm) from a rule tree.
 */
export function collectSubTableRules(items: any[]): any[] {
  const result: any[] = []
  for (const item of items || []) {
    if (!item) continue
    if (SUB_BINDING_RULE_TYPES.has(item.type)) result.push(item)
    const children = getRuleChildren(item)
    if (children.length) result.push(...collectSubTableRules(children))
  }
  return result
}

/**
 * Recursively collect the scope of every recordNote rule in a rule tree
 * (normalized to 'TABLE' | 'RECORD'); used to enforce one component per scope.
 */
export function collectRecordNoteScopes(items: any[]): string[] {
  const result: string[] = []
  for (const item of items || []) {
    if (!item) continue
    if (item.type === 'recordNote') {
      result.push(item.props?.scope === 'TABLE' ? 'TABLE' : 'RECORD')
    }
    const children = getRuleChildren(item)
    if (children.length) result.push(...collectRecordNoteScopes(children))
  }
  return result
}

/**
 * Copy top-level `_bindingId` into `props._bindingId` on every SUB-binding rule (non-mutating).
 * Persisted rules keep `_bindingId` only at top level (the drag rule's parseRule strips the
 * props copy on save), but the placeholder widgets read props — so preview surfaces that
 * feed saved rules straight into form-create must run this or nested placeholders render
 * as "unconfigured".
 */
export function withSubTableBindingIdInProps(items: any[]): any[] {
  const visited = new WeakSet<object>()

  function mapList(list: any[]): any[] {
    return (list || []).map((item) => mapItem(item))
  }

  function mapItem(item: any): any {
    if (!item || typeof item !== 'object') return item
    if (visited.has(item)) return item
    visited.add(item)

    let next = item
    if (SUB_BINDING_RULE_TYPES.has(item.type) && item._bindingId != null && item.props?._bindingId == null) {
      next = { ...item, props: { ...(item.props || {}), _bindingId: item._bindingId } }
    }
    const children = getRuleChildren(next)
    if (!children.length) return next

    const mapped = mapList(children)
    if (mapped === children) return next
    next = next === item ? { ...item } : next
    if (Array.isArray(next.children)) next.children = mapped
    else if (next.props?.children) next.props = { ...next.props, children: mapped }
    else if (Array.isArray(next.props?.list)) next.props = { ...next.props, list: mapped }
    else if (Array.isArray(next.props?.items)) next.props = { ...next.props, items: mapped }
    else if (Array.isArray(next.props?.fields)) next.props = { ...next.props, fields: mapped }
    return next
  }

  return mapList(items)
}

/**
 * Check if a rule item is a card/layout container.
 */
export function isCardRule(item: any): boolean {
  return ['el-card', 'elCard', 'card'].includes(item?.type)
}

/**
 * Extract a human-readable label from a card/layout rule item.
 */
export function getLayoutLabel(item: any): string {
  return String(item?.title || item?.props?.header || item?.props?.title || '')
}

/**
 * Merge persisted form-create options with defaults, ensuring upload labels are injected.
 */
export function mergeLoadedFormOptions(
  stored: Record<string, any> | undefined,
  defaults: Record<string, any>,
  clickToUploadText: string,
): Record<string, any> {
  const baseDefaults = ensureEmptyFormOptionsEvents(defaults)
  if (!stored || Object.keys(stored).length === 0) {
    return { ...baseDefaults }
  }
  const merged = ensureEmptyFormOptionsEvents({
    ...baseDefaults,
    ...stored,
    form: { ...baseDefaults.form, ...(stored.form || {}) },
    language: {
      ...(baseDefaults.language as Record<string, unknown>),
      ...(stored.language || {}),
      en: {
        ...((baseDefaults.language as { en?: Record<string, string> })?.en || {}),
        ...((stored.language as { en?: Record<string, string> } | undefined)?.en || {}),
        clickToUpload: clickToUploadText,
      },
    },
  })
  return merged
}
