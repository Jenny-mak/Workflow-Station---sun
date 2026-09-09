/** Shared shape for flow-history timeline rows (portal task / process views). */
export interface HistoryRecord {
  id: string
  /**
   * Engine task id of this history row. Required to resolve `_snapshot_{taskId}`
   * when a completed diagram node must render its frozen Owner values (§6.6) —
   * `id` is a synthetic row key and MUST NOT be used for that lookup.
   */
  taskId?: string
  nodeId: string
  nodeName: string
  assigneeId?: string
  assigneeName?: string
  status: 'completed' | 'current' | 'pending' | 'rejected' | 'cancelled'
  action?: 'approve' | 'reject' | 'transfer' | 'delegate' | 'withdraw' | 'submit' | 'return' | 'draft' | 'send'
  comment?: string
  createdTime: string
  completedTime?: string
  duration?: number
  attachments?: Array<{ id: string; name: string; url: string }>
  signatureUrl?: string
  activityType?: string
}
