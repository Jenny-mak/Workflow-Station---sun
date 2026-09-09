/**
 * Seed several Owner Demo / MI Demo instances at different nodes and assert
 * Creator + Case Handler against docs/design/owner-field-component.md.
 *
 * Leaves the instances in the portal so you can click them:
 *   node frontend/scripts/seed-owner-demo-matrix.mjs
 *
 * Needs the edge stack on http://localhost:3000.
 */
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { loginViaPortalPassword } from './playwright-login.mjs'

const ORIGIN = (process.env.PORTAL_ORIGIN ?? 'http://localhost:3000').replace(/\/$/, '')
const ORDINARY = process.env.OWNER_DEMO_CODE ?? 'owner-demo-20260907-gehibh'
const MI = process.env.OWNER_MI_CODE ?? 'fu-20260422-23tfag'
const OUT = join(dirname(fileURLToPath(import.meta.url)), '../user-portal/verification-screenshots')
mkdirSync(OUT, { recursive: true })

const INITIATOR = { user: 'e2e_zhangwei', pass: 'password' }
const REVIEWER = { user: 'developer', pass: 'password', buCode: '共享财务中心', roleCode: 'MANAGER' }
const MI_START = {
  user: 'e2e_zhangwei', pass: 'password', buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role',
}

const results = []
const catalog = []

function check(scene, label, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  results.push({ ok, scene, label, actual, expected })
  console.log(`${ok ? 'PASS' : 'FAIL'}  [${scene}] ${label}`)
  if (!ok) console.log(`        expected ${JSON.stringify(expected)}\n        actual   ${JSON.stringify(actual)}`)
}

function findRow(payload, instanceId, key = 'processInstanceId') {
  const seen = new Set()
  const walk = (node) => {
    if (!node || typeof node !== 'object' || seen.has(node)) return null
    seen.add(node)
    if (Array.isArray(node)) {
      for (const item of node) {
        const hit = item && typeof item === 'object' && (item[key] === instanceId || item.id === instanceId)
          ? item
          : walk(item)
        if (hit) return hit
      }
      return null
    }
    for (const value of Object.values(node)) {
      const hit = walk(value)
      if (hit) return hit
    }
    return null
  }
  return walk(payload)
}

const browser = await chromium.launch({ channel: 'chrome', headless: true })

async function session(creds) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const page = await ctx.newPage()
  const me = await loginViaPortalPassword(page, { ...creds, loginOrigin: ORIGIN })
  const api = async (method, path, data) => {
    const res = await page.request.fetch(`${ORIGIN}/api/portal${path}`, { method, data })
    return { status: res.status(), body: await res.json().catch(() => ({})) }
  }
  return { ctx, page, api, userId: me.userId }
}

async function liveVars(api, instanceId) {
  const { body } = await api('GET', `/processes/${instanceId}/form`)
  return (body.data ?? body).fieldValues ?? {}
}

async function startOrdinary(api, stamp) {
  const title = `Owner matrix ${stamp}`
  const started = await api('POST', `/processes/${encodeURIComponent(ORDINARY)}/start`, {
    processDefinitionKey: ORDINARY,
    formData: { title },
    priority: 'NORMAL',
  })
  const id = started.body.data?.id
  if (!id) throw new Error(`ordinary start failed: ${started.status} ${JSON.stringify(started.body).slice(0, 280)}`)
  return { id, title }
}

async function todoFor(api, instanceId) {
  const { body } = await api('POST', '/tasks/todo/query', { page: 0, size: 50 })
  return findRow(body, instanceId)
}

function remember(scene, instanceId, note, extra = {}) {
  catalog.push({
    scene,
    instanceId,
    note,
    request: `${ORIGIN}/portal/applications/${instanceId}`,
    ...extra,
  })
}

const initiator = await session(INITIATOR)
const reviewer = await session(REVIEWER)
const miUser = await session(MI_START)
const stamp = Date.now()

// --- 1. Owner Demo: sitting at Review (unclaimed pool) ---
{
  const scene = 'ordinary-review-pool'
  const { id, title } = await startOrdinary(initiator.api, `${stamp}-pool`)
  const vars = await liveVars(initiator.api, id)
  check(scene, 'Creator = initiator', vars.case_creator, `user:${initiator.userId}`)
  check(scene, 'Case Handler is user: pool, not step:', String(vars.case_handler ?? '').startsWith('user:'), true)
  check(scene, 'Creator untouched vs handler', vars.case_creator !== vars.case_handler || String(vars.case_handler).includes(initiator.userId), true)
  remember(scene, id, `at Review (unclaimed). title=${title}`)
}

// --- 2. Owner Demo: Review claimed ---
{
  const scene = 'ordinary-review-claimed'
  const { id, title } = await startOrdinary(initiator.api, `${stamp}-claimed`)
  const task = await todoFor(reviewer.api, id)
  if (!task) throw new Error(`${scene}: no Review To Do`)
  const taskId = task.id ?? task.taskId
  await reviewer.api('POST', `/tasks/${taskId}/claim`)
  const vars = await liveVars(initiator.api, id)
  check(scene, 'claim writes reviewer as Case Handler', vars.case_handler, `user:${reviewer.userId}`)
  check(scene, 'Creator still initiator', vars.case_creator, `user:${initiator.userId}`)
  remember(scene, id, `Review claimed by developer. title=${title}`, {
    todo: `${ORIGIN}/portal/tasks/${taskId}`,
  })
}

// --- 3. Owner Demo: Review done, sitting at Close ---
{
  const scene = 'ordinary-at-close'
  const { id, title } = await startOrdinary(initiator.api, `${stamp}-close`)
  const review = await todoFor(reviewer.api, id)
  if (!review) throw new Error(`${scene}: no Review To Do`)
  const reviewId = review.id ?? review.taskId
  await reviewer.api('POST', `/tasks/${reviewId}/claim`)
  await reviewer.api('POST', `/tasks/${reviewId}/complete`, {
    taskId: reviewId, action: 'APPROVE', comment: 'matrix', formData: { title },
  })
  const vars = await liveVars(initiator.api, id)
  const snap = vars[`_snapshot_${reviewId}`]?.fieldValues ?? {}
  check(scene, 'Review snapshot froze reviewer', snap.case_handler, `user:${reviewer.userId}`)
  check(scene, 'live Case Handler is Close assignee (initiator)', vars.case_handler, `user:${initiator.userId}`)
  check(scene, 'Creator still initiator', vars.case_creator, `user:${initiator.userId}`)
  const close = await todoFor(initiator.api, id)
  remember(scene, id, `at Close after Review. title=${title}`, {
    todo: close ? `${ORIGIN}/portal/tasks/${close.id ?? close.taskId}` : undefined,
  })
}

// --- 4. Owner Demo: terminal ---
{
  const scene = 'ordinary-terminal'
  const { id, title } = await startOrdinary(initiator.api, `${stamp}-done`)
  const review = await todoFor(reviewer.api, id)
  if (!review) throw new Error(`${scene}: no Review To Do`)
  const reviewId = review.id ?? review.taskId
  await reviewer.api('POST', `/tasks/${reviewId}/claim`)
  await reviewer.api('POST', `/tasks/${reviewId}/complete`, {
    taskId: reviewId, action: 'APPROVE', comment: 'matrix', formData: { title },
  })
  const close = await todoFor(initiator.api, id)
  if (!close) throw new Error(`${scene}: no Close To Do`)
  const closeId = close.id ?? close.taskId
  await initiator.api('POST', `/tasks/${closeId}/complete`, {
    taskId: closeId, action: 'APPROVE', comment: 'close', formData: { title },
  })
  const vars = await liveVars(initiator.api, id)
  check(scene, 'terminal clears Case Handler', String(vars.case_handler ?? ''), '')
  check(scene, 'terminal keeps Creator', vars.case_creator, `user:${initiator.userId}`)
  check(scene, 'Review snapshot survives terminal',
    vars[`_snapshot_${reviewId}`]?.fieldValues?.case_handler, `user:${reviewer.userId}`)
  remember(scene, id, `COMPLETED. title=${title}`)
}

// --- 5. MI Demo: after auto-complete submit, sitting at assignment ---
{
  const scene = 'mi-at-assignment'
  const started = await miUser.api('POST', `/processes/${encodeURIComponent(MI)}/start`, {
    processDefinitionKey: MI,
    formData: { tiuer: `owner-matrix-${stamp}` },
    priority: 'NORMAL',
  })
  const id = started.body.data?.id
  if (!id) throw new Error(`${scene}: start failed ${started.status} ${JSON.stringify(started.body).slice(0, 280)}`)
  const vars = await liveVars(miUser.api, id)
  check(scene, 'Creator = starter', vars.case_owner, `user:${miUser.userId}`)
  const handler = String(vars.case_handler ?? '')
  check(scene, 'assignment Case Handler is a person, not step:multi',
    handler.startsWith('user:') && !handler.includes('step:'), true)
  const todo = await todoFor(miUser.api, id)
  remember(scene, id, `MI FU at ${started.body.data?.currentNode || 'assignment'}`, {
    todo: todo ? `${ORIGIN}/portal/tasks/${todo.id ?? todo.taskId}` : undefined,
  })
}

// --- 6. Existing running MI rows: list Current Assignee = outer box name ---
{
  const scene = 'mi-list-live'
  const listed = await miUser.api('GET', '/processes/my-applications?page=0&size=50')
  const rows = listed.body.data?.content ?? listed.body.data?.records ?? []
  const miRows = rows.filter((r) => r.status === 'RUNNING' && /multi/i.test(String(r.currentStepName || r.currentNode || '')))
  check(scene, 'found at least one running MI request', miRows.length > 0, true)
  for (const row of miRows.slice(0, 3)) {
    const expected = row.currentStepName || row.currentNode
    check(scene, `${row.id} list Current Assignee = outer box`, row.currentAssignee, expected)
  }
}

// Screenshots of two contrasting ordinary states + one MI
const closeRow = catalog.find((c) => c.scene === 'ordinary-at-close')
if (closeRow?.todo) {
  await initiator.page.goto(closeRow.todo, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await initiator.page.waitForTimeout(5000)
  await initiator.page.screenshot({ path: `${OUT}/2026-09-07_owner-matrix-close-todo.png` })
  console.log('wrote', `${OUT}/2026-09-07_owner-matrix-close-todo.png`)
}
const miRow = catalog.find((c) => c.scene === 'mi-at-assignment')
if (miRow?.request) {
  await miUser.page.goto(miRow.request, { waitUntil: 'domcontentloaded', timeout: 60000 })
  await miUser.page.waitForTimeout(5000)
  await miUser.page.screenshot({ path: `${OUT}/2026-09-07_owner-matrix-mi-request.png` })
  console.log('wrote', `${OUT}/2026-09-07_owner-matrix-mi-request.png`)
}

console.log('\n=== Owner demo catalog ===')
for (const row of catalog) {
  console.log(`\n[${row.scene}] ${row.note}`)
  console.log(`  Request  ${row.request}`)
  if (row.todo) console.log(`  To Do    ${row.todo}`)
}

const failed = results.filter((r) => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} checks passed`)
await browser.close()
process.exit(failed.length === 0 ? 0 : 1)
