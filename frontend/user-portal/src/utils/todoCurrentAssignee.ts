/** To Do Current Assignee cell: claim-pool hold vs any other assigned row. */

export function isTodoClaimHold(task: {
  claimPoolTask?: boolean
  assignee?: string | null
}): boolean {
  return Boolean(task.claimPoolTask && String(task.assignee ?? '').trim())
}

export function formatTodoCurrentAssignee(
  task: {
    assignee?: string | null
    assigneeName?: string | null
    claimedByCurrentUser?: boolean
  },
  youLabel: string,
): string {
  const name = String(task.assigneeName || task.assignee || '').trim()
  if (!name) return '-'
  if (task.claimedByCurrentUser) return youLabel
  return name
}
