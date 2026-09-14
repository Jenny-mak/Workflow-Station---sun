import { describe, expect, it } from 'vitest'
import { formatTodoCurrentAssignee, isTodoClaimHold } from '../todoCurrentAssignee'

describe('todoCurrentAssignee', () => {
  it('shows the assigned person on direct USER rows instead of a dash', () => {
    expect(isTodoClaimHold({ claimPoolTask: false, assignee: 'u-1' })).toBe(false)
    expect(formatTodoCurrentAssignee(
      { assignee: 'u-1', assigneeName: 'Ada Chen', claimedByCurrentUser: false },
      'You',
    )).toBe('Ada Chen')
  })

  it('falls back to the assignee id when the display name is missing', () => {
    expect(formatTodoCurrentAssignee(
      { assignee: 'u-1', claimedByCurrentUser: false },
      'You',
    )).toBe('u-1')
  })

  it('keeps a dash when nobody is assigned, including a free role-pool row', () => {
    expect(formatTodoCurrentAssignee({ claimPoolTask: true }, 'You')).toBe('-')
    expect(isTodoClaimHold({ claimPoolTask: true, assignee: '' })).toBe(false)
  })

  it('labels a role-pool hold by the signed-in user as You', () => {
    expect(isTodoClaimHold({ claimPoolTask: true, assignee: 'me' })).toBe(true)
    expect(formatTodoCurrentAssignee(
      { assignee: 'me', assigneeName: 'Ada Chen', claimedByCurrentUser: true },
      'You',
    )).toBe('You')
  })

  it('shows a colleague name on a role-pool hold they took', () => {
    expect(formatTodoCurrentAssignee(
      { assignee: 'u-2', assigneeName: 'Bo Li', claimedByCurrentUser: false },
      'You',
    )).toBe('Bo Li')
  })
})
