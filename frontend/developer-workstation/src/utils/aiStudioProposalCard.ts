import type { AiStudioPreviewAction, AiStudioProposalPreview, AiStudioUndoNote } from '@/api/aiGeneration'

/**
 * 提案卡的展示模型：把后端预览按切片分组，算出徽标计数与阻断状态。
 * 纯函数、无 Vue 依赖，方便单测；老线程里没有 preview 的提案由 fallbackGroups 按条数退化。
 */
export interface ProposalCardItem {
  name: string
  action: AiStudioPreviewAction
}

export interface ProposalCardGroup {
  slice: string
  label: string
  newCount: number
  updateCount: number
  bindCount: number
  rebindCount: number
  /** REPLACE 类切片：提案里的条目数 */
  replaceCount: number
  /** REPLACE 类切片：Apply 会清掉的现有对象数；非 REPLACE 为 null */
  replacesExisting: number | null
  items: ProposalCardItem[]
}

type Translate = (key: string) => string

/** 切片 key → 设计器 Tab 的既有 i18n key（与 proposalItems 一致，不新造平行文案） */
export const SLICE_LABEL_KEYS: Record<string, string> = {
  tableDefinitions: 'functionUnit.tables',
  tableRelations: 'ai.studio.workspace.proposalItemRelations',
  formDefinitions: 'functionUnit.forms',
  actionDefinitions: 'functionUnit.actionDesign',
  decisionDefinitions: 'functionUnit.decisions',
  processDefinition: 'functionUnit.process',
  emailTemplates: 'emailTemplate.title',
  emailConnections: 'connection.title',
  emailMonitorRules: 'emailMonitor.title',
  mainTableViews: 'functionUnit.viewDesign',
  serviceTaskBindings: 'functionUnit.automation'
}

/** 切片在卡片上的固定顺序（与设计器 Tab 顺序一致） */
const SLICE_ORDER = Object.keys(SLICE_LABEL_KEYS)

export function groupPreview(preview: AiStudioProposalPreview, t: Translate): ProposalCardGroup[] {
  const groups = new Map<string, ProposalCardGroup>()
  const ensure = (slice: string): ProposalCardGroup => {
    let g = groups.get(slice)
    if (!g) {
      g = {
        slice,
        label: SLICE_LABEL_KEYS[slice] ? t(SLICE_LABEL_KEYS[slice]) : slice,
        newCount: 0, updateCount: 0, bindCount: 0, rebindCount: 0, replaceCount: 0,
        replacesExisting: null,
        items: []
      }
      groups.set(slice, g)
    }
    return g
  }
  for (const item of preview.items ?? []) {
    const g = ensure(item.slice)
    g.items.push({ name: item.name, action: item.action })
    switch (item.action) {
      case 'NEW': g.newCount++; break
      case 'UPDATE': g.updateCount++; break
      case 'BIND': g.bindCount++; break
      case 'REBIND': g.rebindCount++; break
      case 'REPLACE': g.replaceCount++; break
    }
  }
  for (const r of preview.replacements ?? []) {
    ensure(r.slice).replacesExisting = r.replacesExisting
  }
  return [...groups.values()].sort((a, b) => {
    const ia = SLICE_ORDER.indexOf(a.slice); const ib = SLICE_ORDER.indexOf(b.slice)
    return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib)
  })
}

/** 没有 preview 的老提案：按切片条数退化（与旧版 proposalItems 同语义） */
export function fallbackGroups(data: Record<string, unknown>, t: Translate): ProposalCardGroup[] {
  const out: ProposalCardGroup[] = []
  for (const slice of SLICE_ORDER) {
    const v = data[slice]
    const n = Array.isArray(v) ? v.length : (slice === 'processDefinition' && v ? 1 : 0)
    if (n > 0) {
      out.push({
        slice, label: t(SLICE_LABEL_KEYS[slice]),
        newCount: 0, updateCount: 0, bindCount: 0, rebindCount: 0, replaceCount: n,
        replacesExisting: null, items: []
      })
    }
  }
  return out
}

/** 预览里有 ERROR 即阻断：Apply 侧跑的是同一套校验，必然被拒 */
export function hasBlockingIssues(preview: AiStudioProposalPreview | null | undefined): boolean {
  return !!preview?.issues?.some(i => i.severity === 'ERROR')
}

/** 卡片默认展示的条目数；超出折叠成"展开全部 (N)" */
export const PROPOSAL_CARD_COLLAPSE_AT = 6

/** 一条修正指令里最多列几条错误，避免把提示词撑爆 */
const FIX_REQUEST_MAX_ISSUES = 8

/**
 * 把预校验（或 Apply 失败）的错误拼成一条修正指令。
 *
 * 走 i18n：这条消息同时是线程里显示给用户的内容和发给模型的提示，用界面语言两边才一致
 * （模型本来就按用户语言对话，用户自己提需求时也是中文）。问题明细（字段路径与原因）由后端产出，
 * 保持原样不翻译。没有 ERROR 时返回空串，调用方据此不发起修正轮。
 */
export function buildFixRequest(preview: AiStudioProposalPreview | null | undefined, t: Translate): string {
  const errors = (preview?.issues ?? []).filter(i => i.severity === 'ERROR')
  if (!errors.length) return ''
  const shown = errors.slice(0, FIX_REQUEST_MAX_ISSUES)
    .map(i => `- ${i.fieldPath ? `${i.fieldPath}: ` : ''}${i.description}`)
  const more = errors.length > shown.length
    ? '\n- ' + t('ai.studio.workspace.proposalFixMore').replace('{n}', String(errors.length - shown.length))
    : ''
  return t('ai.studio.workspace.proposalFixRequest') + '\n' + shown.join('\n') + more
}

/**
 * Apply 前是否需要二次确认（Apply 会替换现有设计且不能撤销）。
 * 以后端预览的 undoable 为准；老线程里的预览没有这个字段，按不可撤销处理（多问一次，不少问）。
 */
export function needsReplaceConfirm(preview: AiStudioProposalPreview | null | undefined): boolean {
  return preview?.undoable !== true
}

type TranslateWithParams = (key: string, params: Record<string, unknown>) => string

/** 撤销结果 → 一句界面语言的说明。对象名与失败原因来自后端，保持原样 */
export function formatUndoNote(note: AiStudioUndoNote, t: TranslateWithParams): string {
  const label = SLICE_LABEL_KEYS[note.slice] ? t(SLICE_LABEL_KEYS[note.slice], {}) : note.slice
  const target = note.name ? `${label} "${note.name}"` : label
  return t(`ai.studio.workspace.undoOutcome.${note.outcome}`, { target, detail: note.detail ?? '' })
}
