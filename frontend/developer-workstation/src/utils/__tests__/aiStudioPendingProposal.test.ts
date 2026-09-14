import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  aiStudioPendingProposalStorageKey,
  clearAiStudioPendingProposal,
  loadAiStudioPendingProposal,
  saveAiStudioPendingProposal
} from '../aiStudioDraft'

describe('aiStudioDraft pending proposal store', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('round-trips a pending proposal per function unit', () => {
    saveAiStudioPendingProposal(7, { jobId: 'job-1', phase: 'PROCESS_DESIGN', submittedAt: 123 })
    expect(loadAiStudioPendingProposal(7)).toEqual({ jobId: 'job-1', phase: 'PROCESS_DESIGN', submittedAt: 123 })
    expect(loadAiStudioPendingProposal(8)).toBeNull()

    clearAiStudioPendingProposal(7)
    expect(loadAiStudioPendingProposal(7)).toBeNull()
  })

  it('discards and removes corrupt or malformed records', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    localStorage.setItem(aiStudioPendingProposalStorageKey(1), '{not json')
    expect(loadAiStudioPendingProposal(1)).toBeNull()
    expect(localStorage.getItem(aiStudioPendingProposalStorageKey(1))).toBeNull()

    localStorage.setItem(aiStudioPendingProposalStorageKey(2),
      JSON.stringify({ jobId: 'x', phase: 'NOT_A_PHASE', submittedAt: 1 }))
    expect(loadAiStudioPendingProposal(2)).toBeNull()
    expect(localStorage.getItem(aiStudioPendingProposalStorageKey(2))).toBeNull()
    expect(warn).toHaveBeenCalledTimes(2)
  })
})
