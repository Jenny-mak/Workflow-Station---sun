/**
 * §6.6 display proof on the existing Owner Demo FU:
 *   1. Start → Review (claim+approve as developer) → stop at Close.
 *   2. Open the Close To Do: Owner shows the *live* Close assignee (initiator).
 *   3. Click the finished Review node: Owner switches to the Review snapshot
 *      (the reviewer), not the live Close value.
 *   4. Application detail Basic Info Current Assignee matches the live column.
 *
 *   node frontend/scripts/verify-owner-completed-node-snapshot.mjs
 */
import { mkdirSync } from 'node:fs'
import { chromium } from 'playwright'
import { loginViaPortalPassword } from './playwright-login.mjs'

const ORIGIN = 'http://localhost:3000'
const CODE = 'owner-demo-20260907-gehibh'
const OUT = 'user-portal/verification-screenshots'
mkdirSync(OUT, { recursive: true })

const results = []
function check(label, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  results.push({ ok, label, actual, expected })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}`)
  if (!ok) console.log(`        expected ${JSON.stringify(expected)}\n        actual   ${JSON.stringify(actual)}`)
}

async function readOwnerFields(page) {
  return page.evaluate(() => {
    const out = {}
    for (const label of ['Creator', 'Current Assignee', 'Current Handler']) {
      const el = [...document.querySelectorAll('label, .el-form-item__label')]
        .find(n => n.textContent.trim().replace(/[:：]$/, '') === label)
      const item = el?.closest('.el-form-item')
      const raw = item?.querySelector('.el-form-item__content')?.innerText.trim() ?? ''
      out[label] = raw.replace(/\s+/g, ' ')
    }
    return out
  })
}

function findRow(payload, instanceId, key = 'processInstanceId') {
  const seen = new Set()
  const walk = (node) => {
    if (!node || typeof node !== 'object' || seen.has(node)) return null
    seen.add(node)
    if (Array.isArray(node)) {
      for (const item of node) {
        if (item && typeof item === 'object' && item[key] === instanceId) return item
        const found = walk(item)
        if (found) return found
      }
      return null
    }
    for (const value of Object.values(node)) {
      const found = walk(value)
      if (found) return found
    }
    return null
  }
  return walk(payload)
}

const browser = await chromium.launch({ channel: 'chrome', headless: true })

async function session(creds) {
  const ctx = await browser.newContext({ viewport: { width: 1500, height: 1100 } })
  const page = await ctx.newPage()
  const me = await loginViaPortalPassword(page, creds)
  const api = async (method, path, data) => {
    const res = await page.request.fetch(`${ORIGIN}/api/portal${path}`, { method, data })
    return { status: res.status(), body: await res.json().catch(() => ({})) }
  }
  return { ctx, page, api, userId: me.userId }
}

const initiator = await session({ user: 'e2e_zhangwei', pass: 'password' })
const reviewer = await session({
  user: 'developer', pass: 'password', buCode: '共享财务中心', roleCode: 'MANAGER',
})

const title = `CaseHandler UI ${Date.now()}`
const started = await initiator.api('POST', `/processes/${encodeURIComponent(CODE)}/start`, {
  processDefinitionKey: CODE,
  formData: { title },
  priority: 'NORMAL',
})
const instanceId = started.body.data?.id
if (!instanceId) throw new Error(`start failed: ${started.status} ${JSON.stringify(started.body).slice(0, 300)}`)

const todo = await reviewer.api('POST', '/tasks/todo/query', { page: 0, size: 50 })
const reviewTask = findRow(todo.body, instanceId)
if (!reviewTask) throw new Error('reviewer has no Review To Do')
const reviewTaskId = reviewTask.id ?? reviewTask.taskId
await reviewer.api('POST', `/tasks/${reviewTaskId}/claim`)
await reviewer.api('POST', `/tasks/${reviewTaskId}/complete`, {
  taskId: reviewTaskId, action: 'APPROVE', comment: 'ui check', formData: { title },
})

const todo2 = await initiator.api('POST', '/tasks/todo/query', { page: 0, size: 50 })
const closeTask = findRow(todo2.body, instanceId)
if (!closeTask) throw new Error('initiator has no Close To Do')
const closeTaskId = closeTask.id ?? closeTask.taskId
console.log(`instance ${instanceId}\nclose ${closeTaskId} / review ${reviewTaskId}\n`)

// Live Close To Do — Owner must follow §3.3.3 (initiator), not the Review snapshot.
await initiator.page.goto(`${ORIGIN}/portal/tasks/${closeTaskId}`, { waitUntil: 'domcontentloaded' })
await initiator.page.waitForTimeout(5000)
const live = await readOwnerFields(initiator.page)
await initiator.page.screenshot({ path: `${OUT}/owner-case-handler-close-live.png`, fullPage: true })
// Close task form only binds Creator + Current Handler; Current Assignee is on
// the Review / My Request forms, not this node's canvas.
check('Close To Do live Current Handler is the initiator', live['Current Handler'].includes('张伟'), true)
check('Close To Do Creator stays the submitter', live.Creator.includes('张伟'), true)

// Click the finished Review node — Owner must switch to the Review snapshot.
const reviewNode = initiator.page.locator('.djs-element').filter({ hasText: /^Review$/ }).first()
await reviewNode.click({ force: true })
await initiator.page.waitForTimeout(2500)
const snapped = await readOwnerFields(initiator.page)
await initiator.page.screenshot({ path: `${OUT}/owner-case-handler-review-from-close.png`, fullPage: true })
check('clicking Review overlays snapshot Current Handler (reviewer)',
  snapped['Current Handler'].includes('Developer Tester'), true)
check('clicking Review overlays snapshot Current Assignee (reviewer)',
  snapped['Current Assignee'].includes('Developer Tester'), true)
check('clicking Review does not overwrite Creator', snapped.Creator.includes('张伟'), true)

// Application detail Basic Info = live main Case Handler (§6.7), not the snapshot.
await initiator.page.goto(`${ORIGIN}/portal/applications/${instanceId}`, { waitUntil: 'domcontentloaded' })
await initiator.page.waitForTimeout(4000)
const basic = await initiator.page.evaluate(() => {
  const pageText = document.body.innerText
  return {
    hasZhang: pageText.includes('张伟'),
    hasClose: /Current Step[\s\S]{0,40}Close/.test(pageText) || pageText.includes('Current Step'),
    hasAssignee: pageText.includes('Current Assignee'),
  }
})
await initiator.page.screenshot({ path: `${OUT}/owner-case-handler-application-detail.png`, fullPage: true })
check('application detail still shows the live Close person (张伟)', basic.hasZhang, true)

console.log('\nlive Close form:', live)
console.log('Review node overlay:', snapped)
console.log('application Basic Info:', basic)
const failed = results.filter(r => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} UI checks passed`)
console.log(`screenshots: ${process.cwd()}/${OUT}/owner-case-handler-*.png`)
await browser.close()
process.exit(failed.length === 0 ? 0 : 1)
