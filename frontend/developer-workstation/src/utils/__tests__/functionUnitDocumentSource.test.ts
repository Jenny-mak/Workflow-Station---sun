import { describe, it, expect } from 'vitest'
import { createI18n, type ComposerTranslation } from 'vue-i18n'
import en from '@/i18n/locales/en'
import zhCN from '@/i18n/locales/zh-CN'
import { documentVersionLabel, formatDocSyncError, formatDocumentSource } from '../functionUnitDocumentSource'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en, 'zh-CN': zhCN } })
const t = i18n.global.t as unknown as ComposerTranslation
const te = (key: string) => i18n.global.te(key)

describe('documentVersionLabel', () => {
  it('shows the design round and the version inside it', () => {
    expect(documentVersionLabel({ version: 7, majorVersion: 2, minorVersion: 1 })).toBe('v2.1')
    expect(documentVersionLabel({ version: 1, majorVersion: 1, minorVersion: 1 })).toBe('v1.1')
  })

  it('falls back to the internal sequence for rows without the new fields', () => {
    expect(documentVersionLabel({ version: 4 } as never)).toBe('v4')
    expect(documentVersionLabel(null)).toBe('')
  })
})

describe('formatDocumentSource', () => {
  it('translates the source codes written by the backend', () => {
    expect(formatDocumentSource(t, 'MANUAL')).toBe('Manual edit')
    expect(formatDocumentSource(t, 'IMPORTED')).toBe('Imported')
    expect(formatDocumentSource(t, 'CLONED')).toBe('Cloned')
    expect(formatDocumentSource(t, 'RESTORED:1.3')).toBe('Restored from v1.3')
    expect(formatDocumentSource(t, 'ROLLBACK:1.0.2')).toBe('Rolled back to version 1.0.2')
    expect(formatDocumentSource(t, 'AI_SYNC:')).toBe('AI Studio · full check')
    expect(formatDocumentSource(t, 'AI_SYNC:TABLE_DESIGN,FORM_DESIGN'))
      .toBe(`AI Studio · ${en.functionUnit.tables}, ${en.functionUnit.forms} confirmed`)
  })

  it('follows the UI language', () => {
    i18n.global.locale.value = 'zh-CN'
    try {
      expect(formatDocumentSource(t, 'MANUAL')).toBe('手动编辑')
      expect(formatDocumentSource(t, 'AI_SYNC:')).toBe('AI Studio · 全量检查')
    } finally {
      i18n.global.locale.value = 'en'
    }
  })

  it('shows free text from the old AI Generate as is', () => {
    expect(formatDocumentSource(t, 'AI generated document')).toBe('AI generated document')
    expect(formatDocumentSource(t, 'RESTORED:')).toBe('RESTORED:')
    expect(formatDocumentSource(t, null)).toBe('')
  })
})

describe('formatDocSyncError', () => {
  it('uses the translation for known codes and the raw message otherwise', () => {
    expect(formatDocSyncError(t, te, 'AI_STUDIO_DOC_SYNC_QUEUE_FULL', 'Too many…'))
      .toBe(en.ai.error.AI_STUDIO_DOC_SYNC_QUEUE_FULL)
    expect(formatDocSyncError(t, te, 'SOMETHING_NEW', 'raw text')).toBe('raw text')
    expect(formatDocSyncError(t, te, 'SOMETHING_NEW', undefined)).toBe('SOMETHING_NEW')
  })
})
