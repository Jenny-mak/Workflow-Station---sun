import { describe, expect, it } from 'vitest'
import { isLegacy, isStudioStageReadOnly, latestServerId, mergeThread, pushLocal, toImportMessages } from '../aiStudioSharedThread'
import type { AiStudioChatMessage } from '../aiStudioDraft'
import type { AiStudioThreadMessage } from '@/api/aiStudioThread'

const server = (id: number, role: 'USER' | 'ASSISTANT', content: string,
  extra: Partial<AiStudioThreadMessage> = {}): AiStudioThreadMessage => ({
  id, phase: 'TABLE_DESIGN', role, content, authorName: 'Alice', mine: true,
  createdAt: '2026-09-17T00:00:00Z', proposal: null, ...extra
})

const card = (applied: boolean, appliedByMe: boolean): AiStudioThreadMessage['proposal'] => ({
  scope: 'EMAIL_TEMPLATES', data: { emailTemplates: [] }, preview: null,
  applied, appliedByName: applied ? 'Alice' : null, appliedByMe, appliedAt: null
})

describe('aiStudioSharedThread', () => {
  it('server messages replace local ones and local notes keep their place', () => {
    const local: AiStudioChatMessage[] = [{ role: 'assistant', text: 'intro', isPhaseNote: true, anchorId: 0 }]
    pushLocal(local, { role: 'assistant', text: 'old error', isError: true })
    expect(local[1].anchorId).toBe(0)

    const merged = mergeThread(local, [server(1, 'USER', 'q1'), server(2, 'ASSISTANT', 'a1')])
    expect(merged.map(m => m.text)).toEqual(['intro', 'old error', 'q1', 'a1'])
    expect(latestServerId(merged)).toBe(2)

    // 锚在 #2 之后的"已停止"，在队友的新消息 #3 之前
    pushLocal(merged, { role: 'assistant', text: 'stopped', isPhaseNote: true })
    const again = mergeThread(merged, [
      server(1, 'USER', 'q1'), server(2, 'ASSISTANT', 'a1'),
      server(3, 'USER', 'bob asks', { authorName: 'Bob', mine: false })
    ])
    expect(again.map(m => m.text)).toEqual(['intro', 'old error', 'q1', 'a1', 'stopped', 'bob asks'])
    expect(again[5]).toMatchObject({ role: 'user', mine: false, authorName: 'Bob', serverId: 3 })
  })

  it('keeps object identity for known server messages and drops the replaced optimistic question', () => {
    const local: AiStudioChatMessage[] = []
    const first = mergeThread(local, [server(1, 'ASSISTANT', 'a', { proposal: card(false, false) })])
    const cardObj = first[0]
    const asked = pushLocal(first, { role: 'user', text: 'q2', localOnly: true })
    const next = mergeThread(first, [
      server(1, 'ASSISTANT', 'a', { proposal: card(false, false) }),
      server(2, 'USER', 'q2')
    ], [asked])
    expect(next[0]).toBe(cardObj)
    expect(next.map(m => m.text)).toEqual(['a', 'q2'])
    expect(next[1].serverId).toBe(2)
  })

  it('carries document check results separately from proposals', () => {
    const docSync = { status: 'UPDATED' as const, phases: ['TABLE_DESIGN'], documents: {} }
    const [msg] = mergeThread([], [server(9, 'ASSISTANT', 'Documents check (UPDATED): x', { docSync })])
    expect(msg.docSync).toEqual(docSync)
    expect(msg.proposal).toBeUndefined()

    const [plain] = mergeThread([msg], [server(9, 'ASSISTANT', 'edited', { docSync: null })])
    expect(plain.docSync).toBeUndefined()
  })

  it('keeps the undo token only while the server says I applied it', () => {
    const [msg] = mergeThread([], [server(5, 'ASSISTANT', 'p', { proposal: card(false, false) })])
    msg.proposal!.applied = true
    msg.proposal!.undo = { token: 't-1', until: null }

    const [mineApplied] = mergeThread([msg], [server(5, 'ASSISTANT', 'p', { proposal: card(true, true) })])
    expect(mineApplied.proposal?.undo?.token).toBe('t-1')

    const [teammateView] = mergeThread([], [server(5, 'ASSISTANT', 'p', { proposal: card(true, false) })])
    expect(teammateView.proposal).toMatchObject({ applied: true, appliedByName: 'Alice', appliedByMe: false, undo: null })

    const [reverted] = mergeThread([mineApplied], [server(5, 'ASSISTANT', 'p', { proposal: card(false, false) })])
    expect(reverted.proposal?.undo).toBeNull()
  })

  it('keeps issues written back by a failed apply across refreshes', () => {
    const [msg] = mergeThread([], [server(5, 'ASSISTANT', 'p', { proposal: card(false, false) })])
    const issues = [{ severity: 'ERROR' as const, errorType: 'E', fieldPath: 'x', description: 'broken' }]
    msg.proposal!.preview = { items: [], replacements: [], issues, checked: true }
    msg.proposal!.localIssues = true
    const [after] = mergeThread([msg], [server(5, 'ASSISTANT', 'p', { proposal: card(false, false) })])
    expect(after.proposal?.preview?.issues).toEqual(issues)
  })

  it('shows legacy local history only while the server thread is empty', () => {
    const legacy: AiStudioChatMessage[] = [
      { role: 'assistant', text: 'intro', isPhaseNote: true },
      { role: 'user', text: 'old question' },
      { role: 'assistant', text: 'old answer', proposal: { scope: 'TABLES', data: { tableDefinitions: [] }, applied: true, undo: { token: 'x', until: null } } },
      { role: 'user', text: 'failed send', localOnly: true }
    ]
    expect(mergeThread(legacy, []).map(m => m.text)).toEqual(['intro', 'old question', 'old answer', 'failed send'])
    expect(mergeThread(legacy, [server(9, 'USER', 'teammate')]).map(m => m.text))
      .toEqual(['intro', 'failed send', 'teammate'])

    expect(legacy.filter(isLegacy).map(m => m.text)).toEqual(['old question', 'old answer'])
    expect(toImportMessages(legacy)).toEqual([
      { role: 'USER', content: 'old question', proposal: null },
      { role: 'ASSISTANT', content: 'old answer', proposal: { scope: 'TABLES', data: { tableDefinitions: [] }, preview: null, applied: true } }
    ])
    expect(toImportMessages(undefined)).toEqual([])
  })

  it('stage is read-only when either the shared thread or the function unit denies modify', () => {
    expect(isStudioStageReadOnly(true, false, { canModify: true })).toBe(true)
    expect(isStudioStageReadOnly(true, true, { canModify: false })).toBe(true)
    expect(isStudioStageReadOnly(true, true, { canModify: true })).toBe(false)
    // thread endpoint down → fall back to the function unit's own flag (missing = read-only)
    expect(isStudioStageReadOnly(false, true, { canModify: false })).toBe(true)
    expect(isStudioStageReadOnly(false, true, {})).toBe(true)
    expect(isStudioStageReadOnly(false, true, { canModify: true })).toBe(false)
    // function unit not loaded yet: do not lock early
    expect(isStudioStageReadOnly(false, true, null)).toBe(false)
  })
})
