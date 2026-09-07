import type { FormDefinition } from '@/api/functionUnit'
import { isAdvancedUploadRule } from '@platform-shared/upload/uploadRuleType'
import { walkFormCreateRules } from '@/utils/formDesigner'

export type FormCreateRule = Record<string, unknown> & { field?: string; type?: string }

export type BindingLike = {
  id?: number | string
  bindingId?: number | string
  tableId?: number | string | null
  bindingType?: string
}

export function findTaskSceneProcessForm<T extends Pick<FormDefinition, 'formType' | 'scene'>>(
  forms: T[] | null | undefined,
): T | undefined {
  if (!forms?.length) return undefined
  return forms.find(form => form.formType === 'PROCESS' && form.scene !== 'REQUEST')
}

export function collectFieldKeysFromRules(rules: unknown): Set<string> {
  const keys = new Set<string>()
  walkFormCreateRules(Array.isArray(rules) ? rules : [], (rule) => {
    if (typeof rule.field === 'string' && rule.field) keys.add(rule.field)
  })
  return keys
}

export function collectAdvancedUploadRules(rules: unknown): FormCreateRule[] {
  const found: FormCreateRule[] = []
  walkFormCreateRules(Array.isArray(rules) ? rules : [], (rule) => {
    if (typeof rule.field !== 'string' || !rule.field) return
    if (rule.hidden === true || rule.display === false) return
    if (isAdvancedUploadRule(rule as { type?: string; props?: Record<string, unknown> })) {
      found.push(rule as FormCreateRule)
    }
  })
  return found
}

export function parseFormConfigRules(configJson: unknown): unknown[] {
  let cfg: unknown = configJson
  if (typeof configJson === 'string') {
    try {
      cfg = JSON.parse(configJson)
    } catch {
      return []
    }
  }
  if (!cfg || typeof cfg !== 'object') return []
  const rules = (cfg as { rule?: unknown }).rule
  return Array.isArray(rules) ? rules : []
}

export function parseFormConfigSubFormRules(configJson: unknown): Record<string, unknown[]> {
  let cfg: unknown = configJson
  if (typeof configJson === 'string') {
    try {
      cfg = JSON.parse(configJson)
    } catch {
      return {}
    }
  }
  if (!cfg || typeof cfg !== 'object') return {}
  const subForms = (cfg as { subForms?: unknown }).subForms
  if (!subForms || typeof subForms !== 'object') return {}
  const out: Record<string, unknown[]> = {}
  for (const [key, value] of Object.entries(subForms as Record<string, unknown>)) {
    if (!value || typeof value !== 'object') continue
    const rule = (value as { rule?: unknown }).rule
    out[key] = Array.isArray(rule) ? rule : []
  }
  return out
}

/**
 * Advanced Upload widgets on New Request (TASK PROCESS) that are not yet on the
 * current canvas. Portal must not invent these — the designer places them.
 */
export function missingStartFormAdvancedUploadRules(
  startFormRules: unknown,
  currentFormRules: unknown,
): FormCreateRule[] {
  const present = collectFieldKeysFromRules(currentFormRules)
  return collectAdvancedUploadRules(startFormRules).filter((rule) => {
    const key = String(rule.field)
    return Boolean(key) && !present.has(key)
  })
}

export function cloneAdvancedUploadRuleForOtherCanvas(
  rule: FormCreateRule,
  opts: { readonly: boolean; idSuffix: string },
): FormCreateRule {
  const copy = JSON.parse(JSON.stringify(rule)) as FormCreateRule
  const suffix = opts.idSuffix.replace(/[^a-zA-Z0-9]/g, '').slice(0, 16) || 'copy'
  copy._fc_id = `id_${suffix}`
  copy.name = `ref_${suffix}`
  if (opts.readonly) {
    copy.readonly = true
    const props = copy.props && typeof copy.props === 'object'
      ? { ...(copy.props as Record<string, unknown>) }
      : {}
    props.readonly = true
    copy.props = props
  }
  return copy
}

export function appendClonedAdvancedUploadRules(
  currentRules: unknown[],
  missing: FormCreateRule[],
  readonly: boolean,
): unknown[] {
  const next = [...currentRules]
  missing.forEach((rule, index) => {
    next.push(cloneAdvancedUploadRuleForOtherCanvas(rule, {
      readonly,
      idSuffix: `${String(rule.field)}${index}`,
    }))
  })
  return next
}

function bindingIdOf(binding: BindingLike): number | undefined {
  const n = Number(binding.id ?? binding.bindingId)
  return Number.isFinite(n) ? n : undefined
}

function tableIdOf(binding: BindingLike): number | undefined {
  const n = Number(binding.tableId)
  return Number.isFinite(n) ? n : undefined
}

/** Pair SUB bindings on two forms that share the same physical table. */
export function pairSubBindingsByTableId(
  startBindings: BindingLike[],
  currentBindings: BindingLike[],
): Array<{ startBindingId: number; currentBindingId: number }> {
  const startSubs = startBindings.filter(b => String(b.bindingType || '').toUpperCase() === 'SUB')
  const currentSubs = currentBindings.filter(b => String(b.bindingType || '').toUpperCase() === 'SUB')
  const used = new Set<number>()
  const pairs: Array<{ startBindingId: number; currentBindingId: number }> = []
  for (const start of startSubs) {
    const startBindingId = bindingIdOf(start)
    const tableId = tableIdOf(start)
    if (startBindingId == null || tableId == null) continue
    const match = currentSubs.find((current) => {
      const currentBindingId = bindingIdOf(current)
      return currentBindingId != null && !used.has(currentBindingId) && tableIdOf(current) === tableId
    })
    if (!match) continue
    const currentBindingId = bindingIdOf(match)
    if (currentBindingId == null) continue
    used.add(currentBindingId)
    pairs.push({ startBindingId, currentBindingId })
  }
  return pairs
}

export type CopyStartFormAdvancedUploadResult = {
  mainRules: unknown[]
  subForms: Record<string, unknown[]>
  addedCount: number
  mainChanged: boolean
  changedSubBindingIds: number[]
}

/**
 * Copy Advanced Upload from New Request onto another form's main canvas and
 * onto sub-forms bound to the same physical table, keeping {@code field}.
 */
export function copyStartFormAdvancedUploadAcrossCanvases(input: {
  startMainRules: unknown
  startSubForms: Record<string, unknown[]>
  startBindings: BindingLike[]
  currentMainRules: unknown[]
  currentSubForms: Record<string, unknown[]>
  currentBindings: BindingLike[]
  readonly: boolean
}): CopyStartFormAdvancedUploadResult {
  const missingMain = missingStartFormAdvancedUploadRules(input.startMainRules, input.currentMainRules)
  const mainRules = missingMain.length > 0
    ? appendClonedAdvancedUploadRules(input.currentMainRules, missingMain, input.readonly)
    : [...input.currentMainRules]
  const subForms: Record<string, unknown[]> = { ...input.currentSubForms }
  const changedSubBindingIds: number[] = []
  let addedCount = missingMain.length
  for (const pair of pairSubBindingsByTableId(input.startBindings, input.currentBindings)) {
    const startRules = input.startSubForms[String(pair.startBindingId)] ?? []
    const currentKey = String(pair.currentBindingId)
    const currentRules = subForms[currentKey] ?? []
    const missing = missingStartFormAdvancedUploadRules(startRules, currentRules)
    if (missing.length === 0) continue
    subForms[currentKey] = appendClonedAdvancedUploadRules(
      Array.isArray(currentRules) ? currentRules : [],
      missing,
      input.readonly,
    )
    changedSubBindingIds.push(pair.currentBindingId)
    addedCount += missing.length
  }
  return {
    mainRules,
    subForms,
    addedCount,
    mainChanged: missingMain.length > 0,
    changedSubBindingIds,
  }
}
