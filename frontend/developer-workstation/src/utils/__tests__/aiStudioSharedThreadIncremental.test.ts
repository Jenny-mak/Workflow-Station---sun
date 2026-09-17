import { describe, expect, it } from 'vitest'
import {
  appendServerMessages, mergeThread, parseSseBlock, pushLocal, reconnectDelay, SERVER_MESSAGES_PER_PHASE,
  toHistoryEntries, HISTORY_CONTENT_MAX
} from '../aiStudioSharedThread'
import type { AiStudioChatMessage } from '../aiStudioDraft'
import type { AiStudioThreadMessage } from '@/api/aiStudioThread'

const server = (id: number, content: string, extra: Partial<AiStudioThreadMessage> = {}): AiStudioThreadMessage => ({
  id, phase: 'TABLE_DESIGN', role: 'USER', content, authorName: 'Bob', mine: false,
  createdAt: '2026-09-17T00:00:00Z', proposal: null, ...extra
})

describe('appendServerMessages', () => {
  it('appends newer messages, updates known ones in place and drops the replaced optimistic question', () => {
    const thread = mergeThread([{ role: 'assistant', text: 'intro', isPhaseNote: true, anchorId: 0 }], [server(1, 'q1')])
    const known = thread[1]
    const asked = pushLocal(thread, { role: 'user', text: 'q2', localOnly: true })

    const next = appendServerMessages(thread, [server(3, 'a2', { role: 'ASSISTANT' }), server(2, 'q2', { mine: true }), server(1, 'q1 edited')], [asked])
    expect(next.map(m => m.text)).toEqual(['intro', 'q1 edited', 'q2', 'a2'])
    expect(next[1]).toBe(known)
    expect(next[2]).toMatchObject({ serverId: 2, mine: true, role: 'user' })
    expect(next[3]).toMatchObject({ serverId: 3, role: 'assistant' })
  })

  it('keeps only the newest server messages, like the backend', () => {
    const initial = mergeThread([], Array.from({ length: SERVER_MESSAGES_PER_PHASE }, (_, i) => server(i + 1, `m${i + 1}`)))
    pushLocal(initial, { role: 'assistant', text: 'local note', isPhaseNote: true })
    const next = appendServerMessages(initial, [server(51, 'm51'), server(52, 'm52')])
    const serverTexts = next.filter(m => m.serverId).map(m => m.text)
    expect(serverTexts).toHaveLength(SERVER_MESSAGES_PER_PHASE)
    expect(serverTexts[0]).toBe('m3')
    expect(serverTexts.at(-1)).toBe('m52')
    expect(next.some(m => m.text === 'local note')).toBe(true)
  })

  it('updates the applied state of an existing card', () => {
    const card = (applied: boolean) => server(7, 'p', {
      role: 'ASSISTANT',
      proposal: { scope: 'VIEWS', data: {}, preview: null, applied, appliedByName: applied ? 'Bob' : null, appliedByMe: false, appliedAt: null }
    })
    const thread = mergeThread([], [card(false)])
    appendServerMessages(thread, [card(true)])
    expect(thread[0].proposal).toMatchObject({ applied: true, appliedByName: 'Bob', undo: null })
  })
})

describe('reconnectDelay', () => {
  it('backs off exponentially with a 30s cap and jitter', () => {
    expect(reconnectDelay(0, () => 0.5)).toBe(1000)
    expect(reconnectDelay(3, () => 0.5)).toBe(8000)
    expect(reconnectDelay(20, () => 0.5)).toBe(30000)
    expect(reconnectDelay(0, () => 0)).toBe(800)
    expect(reconnectDelay(0, () => 1)).toBe(1200)
  })
})

describe('parseSseBlock', () => {
  it('reads named events with JSON data and ignores heartbeats', () => {
    expect(parseSseBlock('event:MESSAGE_ADDED\ndata:{"phase":"TABLE_DESIGN","messageId":5,"mine":false}'))
      .toEqual({ type: 'MESSAGE_ADDED', data: { phase: 'TABLE_DESIGN', messageId: 5, mine: false } })
    expect(parseSseBlock(':ping')).toBeNull()
    expect(parseSseBlock('')).toBeNull()
    expect(parseSseBlock('event: READY\ndata: {"mine":false}')).toEqual({ type: 'READY', data: { mine: false } })
    expect(parseSseBlock('event:X\ndata:not json')).toEqual({ type: 'X', data: 'not json' })
  })
})

describe('toHistoryEntries', () => {
  it('never sends blank or oversized content, and keeps the proposal', () => {
    const long = 'x'.repeat(5000)
    const thread: AiStudioChatMessage[] = [
      { role: 'assistant', text: 'intro', isPhaseNote: true },
      { role: 'user', text: 'add a template' },
      { role: 'assistant', text: '', proposal: { scope: 'EMAIL_TEMPLATES', data: { emailTemplates: [] } } },
      { role: 'assistant', text: '   ' },
      { role: 'assistant', text: 'oops', isError: true },
      { role: 'assistant', text: long }
    ]
    const entries = toHistoryEntries(thread, 'Proposal ready')
    expect(entries.map(e => e.role)).toEqual(['USER', 'ASSISTANT', 'ASSISTANT'])
    expect(entries[1]).toEqual({ role: 'ASSISTANT', content: 'Proposal ready', proposal: { emailTemplates: [] }, proposalScope: 'EMAIL_TEMPLATES' })
    expect(entries[2].content).toHaveLength(HISTORY_CONTENT_MAX)
    expect(entries.every(e => e.content.trim().length > 0)).toBe(true)
  })

  it('keeps only the latest entries', () => {
    const thread: AiStudioChatMessage[] = Array.from({ length: 15 }, (_, i) => ({ role: 'user' as const, text: `q${i}` }))
    expect(toHistoryEntries(thread, 'x').map(e => e.content)).toEqual(Array.from({ length: 10 }, (_, i) => `q${i + 5}`))
  })
})
