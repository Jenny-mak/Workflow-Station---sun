import type { ComposerTranslation } from 'vue-i18n'
import type { FunctionUnitDocument } from '@/api/functionUnitDocument'
import { AI_STUDIO_PHASES, aiStudioPhaseLabel, type AiStudioPhase } from './aiStudioDraft'

/**
 * 文档版本号：v{大版本}.{小版本}。大版本只在"开始新的 AI 设计"后进位，其余保存都只加小版本。
 * 老接口（没有这两个字段）回落到内部序号。
 */
export function documentVersionLabel(
  doc: Pick<FunctionUnitDocument, 'version' | 'majorVersion' | 'minorVersion'> | null | undefined
): string {
  if (!doc) return ''
  if (doc.majorVersion == null || doc.minorVersion == null) return `v${doc.version}`
  return `v${doc.majorVersion}.${doc.minorVersion}`
}

/**
 * 文档版本来源（后端 summary 列，见 FunctionUnitDocumentService.SUMMARY_*）→ 界面文案。
 * MANUAL / IMPORTED / CLONED / AI_ONE_CLICK / RESTORED:<v> / ROLLBACK:<version> / AI_SYNC:<阶段,…>（空 = 全量核对）；
 * 认不出的是旧 AI Generate 写入的自由文本，原样显示。
 */
export function formatDocumentSource(t: ComposerTranslation, summary: string | null | undefined): string {
  if (!summary) return ''
  if (summary === 'MANUAL' || summary === 'IMPORTED' || summary === 'CLONED' || summary === 'AI_ONE_CLICK') {
    return t(`functionUnit.documents.source.${summary}`)
  }
  const [kind, ...rest] = summary.split(':')
  const arg = rest.join(':')
  if (kind === 'RESTORED' && arg) return t('functionUnit.documents.source.RESTORED', { version: arg })
  if (kind === 'ROLLBACK' && arg) return t('functionUnit.documents.source.ROLLBACK', { version: arg })
  if (kind === 'AI_SYNC' && rest.length) {
    const phases = arg.split(',').filter(Boolean)
    if (!phases.length) return t('functionUnit.documents.source.AI_SYNC_FULL')
    const labels = phases.map(p => (AI_STUDIO_PHASES as readonly string[]).includes(p)
      ? aiStudioPhaseLabel(t, p as AiStudioPhase)
      : p)
    return t('functionUnit.documents.source.AI_SYNC', { phases: labels.join(', ') })
  }
  return summary
}

/** 文档同步失败原因：已知错误码走 ai.error.* 翻译，未知的才显示后端原文。 */
export function formatDocSyncError(
  t: ComposerTranslation,
  te: (key: string) => boolean,
  code: string | undefined,
  message: string | undefined
): string {
  if (code && te(`ai.error.${code}`)) return t(`ai.error.${code}`)
  return message ?? code ?? ''
}
