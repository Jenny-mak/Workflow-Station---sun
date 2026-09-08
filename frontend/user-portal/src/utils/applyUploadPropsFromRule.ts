import type { FormField } from '@/components/formRendererHelpers'
import { isCannotDownload, uploadPropsBlockDownload } from '@/utils/filePreview'
import { resolveUploadMaxFileSizeMb, resolveUploadMaxFiles } from '@platform-shared/upload/uploadFieldValue'
import { isAdvancedUploadRule, isAnyUploadType } from '@platform-shared/upload/uploadRuleType'

const DEFAULT_UPLOAD_URL = '/api/v1/upload'
const EMPTY_BLOCKED_KEYS = new Set<string>()
const EMPTY_MAX_FILES = new Map<string, number>()
const flagsCache = new WeakMap<object, UploadSceneFlags>()

type FormLike = { data?: unknown; configJson?: unknown }

/** Per-FU upload flags copied onto REQUEST / other scene clones. */
export interface UploadSceneFlags {
  cannotDownload: Set<string>
  maxFiles: Map<string, number>
}

export type UploadSceneFlagsArg = Set<string> | UploadSceneFlags

function asSceneFlags(flags?: UploadSceneFlagsArg): UploadSceneFlags {
  if (!flags) return { cannotDownload: EMPTY_BLOCKED_KEYS, maxFiles: EMPTY_MAX_FILES }
  if (flags instanceof Set) return { cannotDownload: flags, maxFiles: EMPTY_MAX_FILES }
  return flags
}

/**
 * Copy designer upload props onto a FormField (action/accept/limit + cannotDownload).
 * Honors both `cannotDownload` (designer switch) and form-create native `canNotDownload`.
 * `blockedFieldKeys` covers FU scene copies that never received the switch (My Request / TASK).
 */
export function applyUploadPropsFromRule(
  field: FormField,
  rule: { type?: string; props?: Record<string, unknown>; cannotDownload?: unknown; canNotDownload?: unknown },
  blockedFieldKeys?: UploadSceneFlagsArg,
): void {
  if (!isAnyUploadType(rule.type)) return
  field.type = 'upload'
  const scene = asSceneFlags(blockedFieldKeys)
  const inheritedAdvanced = field.key != null
    && (scene.cannotDownload.has(field.key) || scene.maxFiles.has(field.key))
  field.advancedUpload = isAdvancedUploadRule(rule) || inheritedAdvanced
  const props = rule.props && typeof rule.props === 'object' ? rule.props : {}
  const action = props.action
  field.uploadUrl = (typeof action === 'string' && action && action !== '/')
    ? action
    : DEFAULT_UPLOAD_URL
  field.uploadAccept = typeof props.accept === 'string' ? props.accept : ''
  if (field.advancedUpload) {
    const inheritedLimit = field.key != null ? scene.maxFiles.get(field.key) : undefined
    field.uploadLimit = isAdvancedUploadRule(rule)
      ? resolveUploadMaxFiles(props)
      : (inheritedLimit ?? resolveUploadMaxFiles(props))
    field.uploadMaxFileSizeMb = resolveUploadMaxFileSizeMb(props)
  } else if (typeof props.limit === 'number' && Number.isInteger(props.limit) && props.limit >= 1) {
    field.uploadLimit = props.limit
  }
  if (typeof props.fileNameTargetField === 'string' && props.fileNameTargetField) {
    field.fileNameTargetField = props.fileNameTargetField
  }
  if (uploadRuleBlocksDownload(rule) || (field.key != null && scene.cannotDownload.has(field.key))) {
    field.cannotDownload = true
  }
}

export function uploadRuleBlocksDownload(
  rule: { type?: string; props?: Record<string, unknown>; cannotDownload?: unknown; canNotDownload?: unknown } | null | undefined,
): boolean {
  if (!rule || !isAnyUploadType(rule.type)) return false
  return uploadPropsBlockDownload(rule.props)
    || isCannotDownload(rule.cannotDownload)
    || isCannotDownload(rule.canNotDownload)
}

export function stampCannotDownloadProp(
  target: Record<string, unknown>,
  sourceProps: Record<string, unknown> | undefined,
  fieldKey?: string,
  blockedFieldKeys?: Set<string>,
  sourceRule?: { type?: string; cannotDownload?: unknown; canNotDownload?: unknown } | null,
): void {
  if (
    uploadPropsBlockDownload(sourceProps)
    || (sourceRule != null && (
      isCannotDownload(sourceRule.cannotDownload)
      || isCannotDownload(sourceRule.canNotDownload)
    ))
    || (fieldKey != null && blockedFieldKeys?.has(fieldKey))
  ) {
    target.cannotDownload = true
  }
}

/** Cached per `content.forms` array instance (loaders set it once per FU fetch). */
export function uploadSceneFlagsFromForms(forms: FormLike[] | null | undefined): UploadSceneFlags {
  if (!forms?.length) return { cannotDownload: EMPTY_BLOCKED_KEYS, maxFiles: EMPTY_MAX_FILES }
  const hit = flagsCache.get(forms)
  if (hit) return hit
  const flags = collectUploadSceneFlags(forms)
  flagsCache.set(forms, flags)
  return flags
}

export function cannotDownloadFieldKeysFromForms(forms: FormLike[] | null | undefined): Set<string> {
  return uploadSceneFlagsFromForms(forms).cannotDownload
}

export function collectCannotDownloadFieldKeysFromForms(forms: FormLike[] | null | undefined): Set<string> {
  return collectUploadSceneFlags(forms).cannotDownload
}

function collectUploadSceneFlags(forms: FormLike[] | null | undefined): UploadSceneFlags {
  const cannotDownload = new Set<string>()
  const maxFiles = new Map<string, number>()
  if (!forms?.length) return { cannotDownload, maxFiles }
  for (const form of forms) {
    collectFromConfig(cannotDownload, maxFiles, form.data ?? form.configJson)
  }
  return { cannotDownload, maxFiles }
}

function collectFromConfig(keys: Set<string>, maxFiles: Map<string, number>, raw: unknown): void {
  let cfg: unknown = raw
  if (typeof raw === 'string') {
    try {
      cfg = JSON.parse(raw)
    } catch {
      // FALLBACK(ux): malformed form JSON skips cannot-download enrichment only.
      return
    }
  }
  if (!cfg || typeof cfg !== 'object') return
  const obj = cfg as Record<string, unknown>
  walkUploadRules(keys, maxFiles, obj.rule)
  const subForms = obj.subForms
  if (!subForms || typeof subForms !== 'object') return
  for (const sub of Object.values(subForms as Record<string, unknown>)) {
    if (sub && typeof sub === 'object') {
      walkUploadRules(keys, maxFiles, (sub as Record<string, unknown>).rule)
    }
  }
}

function nestedRuleChildren(rule: Record<string, unknown>): unknown[] {
  const props = rule.props && typeof rule.props === 'object'
    ? rule.props as Record<string, unknown>
    : undefined
  const sources = [rule.children, props?.children, props?.list, props?.items, props?.fields]
  return (sources.find(Array.isArray) as unknown[] | undefined) ?? []
}

function walkUploadRules(keys: Set<string>, maxFiles: Map<string, number>, rules: unknown): void {
  if (!Array.isArray(rules)) return
  const stack = [...rules] as Array<Record<string, unknown>>
  while (stack.length) {
    const rule = stack.pop()
    if (!rule || typeof rule !== 'object') continue
    for (const child of nestedRuleChildren(rule)) {
      if (child && typeof child === 'object') stack.push(child as Record<string, unknown>)
    }
    if (typeof rule.field !== 'string') continue
    const typed = rule as { type?: string; props?: Record<string, unknown>; cannotDownload?: unknown }
    if (uploadRuleBlocksDownload(typed)) keys.add(rule.field)
    if (isAdvancedUploadRule(typed)) {
      maxFiles.set(rule.field, resolveUploadMaxFiles(typed.props))
    }
  }
}
