export const DASHBOARD_ROUTES = {
  todo: '/tasks',
  completedTasks: '/tasks/completed',
  myRequests: '/my-applications',
  newRequest: '/processes',
  delegations: '/delegations',
  profileSetup: '/permissions'
} as const

interface TaskRouteIdentity {
  taskId?: string
  id?: string
}

/** Home task entries always resolve with the task id, never the process-instance id. */
export function dashboardTaskDetailRoute(task: TaskRouteIdentity): string | null {
  const taskId = task.taskId || task.id
  return taskId ? `/tasks/${encodeURIComponent(taskId)}` : null
}

/** Home request entries remain on the initiator-facing My Request detail. */
export function dashboardRequestDetailRoute(processInstanceId?: string): string | null {
  return processInstanceId
    ? `/applications/${encodeURIComponent(processInstanceId)}`
    : null
}

export function dashboardMyRequestsRoute(status?: 'RUNNING' | 'COMPLETED' | 'DRAFT') {
  return status
    ? { path: DASHBOARD_ROUTES.myRequests, query: { status } }
    : { path: DASHBOARD_ROUTES.myRequests }
}
