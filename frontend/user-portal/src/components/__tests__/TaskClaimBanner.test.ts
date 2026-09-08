import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import TaskClaimBanner from '@/components/tasks/TaskClaimBanner.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string, named?: Record<string, unknown>) =>
      named ? `${key}:${Object.values(named).join(',')}` : key,
  }),
}))

type BannerTask = {
  claimPoolTask?: boolean
  claimable?: boolean
  claimedByCurrentUser?: boolean
  canForceUnclaim?: boolean
  canReassign?: boolean
  assignee?: string
  assigneeName?: string
}

let wrapper: VueWrapper | null = null

function mountBanner(task: BannerTask) {
  wrapper = mount(TaskClaimBanner, {
    props: { task, submitting: false },
    global: { plugins: [ElementPlus] },
  })
  return wrapper
}

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('TaskClaimBanner', () => {
  it('stays out of the way for tasks that are not BU Role pool rows', () => {
    const w = mountBanner({ claimPoolTask: false, assignee: 'alice' })
    expect(w.find('[data-test="task-claim-banner"]').exists()).toBe(false)
  })

  it('offers Claim while the request is still free', () => {
    const w = mountBanner({ claimPoolTask: true, claimable: true })

    expect(w.find('[data-test="task-claim-banner-title"]').text()).toBe('task.claimAvailable')
    expect(w.find('[data-test="task-claim-btn"]').exists()).toBe(true)
    expect(w.find('[data-test="task-unclaim-btn"]').exists()).toBe(false)
  })

  it('offers Unclaim to the member holding the request', () => {
    const w = mountBanner({ claimPoolTask: true, claimedByCurrentUser: true, assignee: 'me' })

    expect(w.find('[data-test="task-claim-banner-title"]').text()).toBe('task.claimHeldByYou')
    expect(w.find('[data-test="task-unclaim-btn"]').exists()).toBe(true)
    expect(w.find('[data-test="task-claim-btn"]').exists()).toBe(false)
  })

  it('names the holder and offers no action to the rest of the role', () => {
    const w = mountBanner({ claimPoolTask: true, assignee: 'alice', assigneeName: 'Alice Chen' })

    expect(w.find('[data-test="task-claim-banner-title"]').text()).toBe(
      'task.claimHeldByOther:Alice Chen',
    )
    expect(w.find('[data-test="task-claim-btn"]').exists()).toBe(false)
    expect(w.find('[data-test="task-unclaim-btn"]').exists()).toBe(false)
  })

  it('offers Force Unclaim when the current user may release someone else hold', () => {
    const w = mountBanner({
      claimPoolTask: true,
      assignee: 'alice',
      assigneeName: 'Alice Chen',
      canForceUnclaim: true,
    })

    expect(w.find('[data-test="task-force-unclaim-btn"]').exists()).toBe(true)
    expect(w.find('[data-test="task-claim-banner"]').text()).toContain('task.forceUnclaimHint')
  })

  it('emits claim and unclaim so the page owns the API call', async () => {
    const free = mountBanner({ claimPoolTask: true, claimable: true })
    await free.find('[data-test="task-claim-btn"]').trigger('click')
    expect(free.emitted('claim')).toHaveLength(1)
    free.unmount()

    const mine = mountBanner({ claimPoolTask: true, claimedByCurrentUser: true })
    await mine.find('[data-test="task-unclaim-btn"]').trigger('click')
    expect(mine.emitted('unclaim')).toHaveLength(1)
  })

  it('offers Reassign next to Claim when the current user may assign the pool row', () => {
    const w = mountBanner({
      claimPoolTask: true,
      claimable: true,
      canReassign: true,
    })

    expect(w.find('[data-test="task-claim-btn"]').exists()).toBe(true)
    expect(w.find('[data-test="task-reassign-btn"]').exists()).toBe(true)
  })

  it('emits reassign from the Reassign button', async () => {
    const w = mountBanner({
      claimPoolTask: true,
      assignee: 'alice',
      canForceUnclaim: true,
      canReassign: true,
    })
    await w.find('[data-test="task-reassign-btn"]').trigger('click')
    expect(w.emitted('reassign')).toHaveLength(1)
  })
})
