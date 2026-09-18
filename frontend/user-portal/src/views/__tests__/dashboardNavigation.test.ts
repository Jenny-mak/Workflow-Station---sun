import { describe, expect, it } from 'vitest'
import {
  DASHBOARD_ROUTES,
  dashboardMyRequestsRoute,
  dashboardRequestDetailRoute,
  dashboardTaskDetailRoute
} from '@/views/dashboard/dashboardNavigation'

describe('Home task and request navigation', () => {
  it('keeps every task overview entry in the To Do task area', () => {
    expect(DASHBOARD_ROUTES.todo).toBe('/tasks')
    expect(DASHBOARD_ROUTES.completedTasks).toBe('/tasks/completed')
  })

  it('routes every Quick Action to its corresponding portal page', () => {
    expect(DASHBOARD_ROUTES.newRequest).toBe('/processes')
    expect(DASHBOARD_ROUTES.delegations).toBe('/delegations')
    expect(DASHBOARD_ROUTES.profileSetup).toBe('/permissions')
  })

  it('opens a recent task with its task id instead of a request route', () => {
    expect(dashboardTaskDetailRoute({ taskId: 'task-123', id: 'legacy-id' }))
      .toBe('/tasks/task-123')
    expect(dashboardTaskDetailRoute({ id: 'legacy-task' })).toBe('/tasks/legacy-task')
    expect(dashboardTaskDetailRoute({})).toBeNull()
  })

  it('keeps My Request overview and detail entries in request routes', () => {
    expect(dashboardMyRequestsRoute()).toEqual({ path: '/my-applications' })
    expect(dashboardMyRequestsRoute('RUNNING')).toEqual({
      path: '/my-applications',
      query: { status: 'RUNNING' }
    })
    expect(dashboardRequestDetailRoute('process-456')).toBe('/applications/process-456')
    expect(dashboardRequestDetailRoute()).toBeNull()
  })

  it('encodes ids before putting them into a route path', () => {
    expect(dashboardTaskDetailRoute({ taskId: 'task/one' })).toBe('/tasks/task%2Fone')
    expect(dashboardRequestDetailRoute('request/one')).toBe('/applications/request%2Fone')
  })
})
