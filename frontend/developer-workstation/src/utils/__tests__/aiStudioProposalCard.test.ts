import { describe, expect, it } from 'vitest'
import {
  buildFixRequest, fallbackGroups, formatUndoNote, groupPreview, hasBlockingIssues, needsReplaceConfirm
} from '../aiStudioProposalCard'
import type { AiStudioProposalPreview } from '@/api/aiGeneration'

const t = (k: string) => `T(${k})`

describe('aiStudioProposalCard', () => {
  it('groups preview items per slice with new/update/replace counts in designer order', () => {
    const preview: AiStudioProposalPreview = {
      items: [
        { slice: 'emailTemplates', name: 'Order Shipped', action: 'NEW' },
        { slice: 'emailTemplates', name: 'Order Approved', action: 'UPDATE' },
        { slice: 'tableDefinitions', name: 'orders', action: 'REPLACE' },
        { slice: 'tableDefinitions', name: 'order_lines', action: 'REPLACE' },
        { slice: 'serviceTaskBindings', name: 'svc_sync', action: 'REBIND' }
      ],
      replacements: [{ slice: 'tableDefinitions', replacesExisting: 3 }],
      issues: [],
      checked: true
    }
    const groups = groupPreview(preview, t)
    expect(groups.map(g => g.slice)).toEqual(['tableDefinitions', 'emailTemplates', 'serviceTaskBindings'])
    const tables = groups[0]
    expect(tables.label).toBe('T(functionUnit.tables)')
    expect(tables.replaceCount).toBe(2)
    expect(tables.replacesExisting).toBe(3)
    const templates = groups[1]
    expect(templates.newCount).toBe(1)
    expect(templates.updateCount).toBe(1)
    expect(templates.replacesExisting).toBeNull()
    expect(templates.items.map(i => `${i.name}:${i.action}`)).toEqual(['Order Shipped:NEW', 'Order Approved:UPDATE'])
    expect(groups[2].rebindCount).toBe(1)
  })

  it('falls back to per-slice counts when the proposal has no preview', () => {
    const groups = fallbackGroups({
      formDefinitions: [{ formName: 'a' }, { formName: 'b' }],
      processDefinition: { bpmnXml: '<x/>' },
      emailTemplates: []
    }, t)
    expect(groups.map(g => `${g.slice}:${g.replaceCount}`)).toEqual(['formDefinitions:2', 'processDefinition:1'])
    expect(groups.every(g => g.items.length === 0 && g.replacesExisting === null)).toBe(true)
  })

  it('buildFixRequest lists only errors and caps the list', () => {
    const base = { items: [], replacements: [], checked: true }
    expect(buildFixRequest({ ...base, issues: [] }, t)).toBe('')
    expect(buildFixRequest(null, t)).toBe('')
    expect(buildFixRequest({ ...base, issues: [
      { severity: 'WARNING', errorType: 'W', fieldPath: 'a', description: 'just a warning' }
    ] }, t)).toBe('')

    const two = buildFixRequest({ ...base, issues: [
      { severity: 'ERROR', errorType: 'REFERENCE_NOT_FOUND', fieldPath: 'emailMonitorRules[0].connectionName', description: "Connection not found: inbox@x.com" },
      { severity: 'WARNING', errorType: 'W', fieldPath: 'x', description: 'ignored' },
      { severity: 'ERROR', errorType: 'FIELD_CONSTRAINT', fieldPath: '', description: 'name must not be empty' }
    ] }, t)
    expect(two).toContain('T(ai.studio.workspace.proposalFixRequest)')
    expect(two).toContain('- emailMonitorRules[0].connectionName: Connection not found: inbox@x.com')
    expect(two).toContain('- name must not be empty')
    expect(two).not.toContain('ignored')

    const many = buildFixRequest({ ...base, issues: Array.from({ length: 11 }, (_, i) => (
      { severity: 'ERROR' as const, errorType: 'E', fieldPath: `f${i}`, description: `problem ${i}` }
    )) }, t)
    expect(many).toContain('f7: problem 7')
    expect(many).not.toContain('f8: problem 8')
    expect(many).toContain('T(ai.studio.workspace.proposalFixMore)')
  })

  it('blocks only on ERROR issues', () => {
    const base: AiStudioProposalPreview = { items: [], replacements: [], issues: [], checked: true }
    expect(hasBlockingIssues(base)).toBe(false)
    expect(hasBlockingIssues({ ...base, issues: [{ severity: 'WARNING', errorType: 'X', fieldPath: 'p', description: 'd' }] })).toBe(false)
    expect(hasBlockingIssues({ ...base, issues: [{ severity: 'ERROR', errorType: 'X', fieldPath: 'p', description: 'd' }] })).toBe(true)
    expect(hasBlockingIssues(null)).toBe(false)
    expect(hasBlockingIssues(undefined)).toBe(false)
  })

  it('asks for confirmation unless the backend marks the apply undoable', () => {
    const base: AiStudioProposalPreview = { items: [], replacements: [], issues: [], checked: true }
    expect(needsReplaceConfirm({ ...base, undoable: true })).toBe(false)
    expect(needsReplaceConfirm({ ...base, undoable: false })).toBe(true)
    // 老线程里的预览没有 undoable、或根本没有预览：多问一次
    expect(needsReplaceConfirm(base)).toBe(true)
    expect(needsReplaceConfirm(null)).toBe(true)
  })

  it('formats undo notes through i18n with the slice label, name and detail', () => {
    const tp = (k: string, p: Record<string, unknown>) => `T(${k}|${p.target ?? ''}|${p.detail ?? ''})`
    expect(formatUndoNote({ slice: 'emailTemplates', name: 'Order Shipped', outcome: 'DELETED', detail: null }, tp))
      .toBe('T(ai.studio.workspace.undoOutcome.DELETED|T(emailTemplate.title||) "Order Shipped"|)')
    expect(formatUndoNote({ slice: 'serviceTaskBindings', name: 'svc_sync', outcome: 'REBOUND', detail: 'old-key' }, tp))
      .toBe('T(ai.studio.workspace.undoOutcome.REBOUND|T(functionUnit.automation||) "svc_sync"|old-key)')
    // 整片还原没有对象名
    expect(formatUndoNote({ slice: 'processDefinition', name: null, outcome: 'RESTORED', detail: null }, tp))
      .toBe('T(ai.studio.workspace.undoOutcome.RESTORED|T(functionUnit.process||)|)')
  })
})
