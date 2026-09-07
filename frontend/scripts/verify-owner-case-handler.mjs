/**
 * End-to-end check of Owner `source=CASE_HANDLER` against
 * docs/design/owner-field-component.md §3.3.3 / §6.3 / §6.6 / §6.7.
 *
 * Drives one Owner Demo request through Start -> Review -> Close and asserts, at
 * every node, the persisted main-table Owner values, the completion snapshots,
 * and the Basic Info / My Requests "Current Assignee" display.
 *
 *   node frontend/scripts/verify-owner-case-handler.mjs
 *
 * Needs the dev compose stack up on http://localhost:3000.
 * Initiator: e2e_zhangwei. Review handler: developer (candidate of the
 * FIXED_BU_ROLE E2E_FINANCE / MANAGER step).
 */
import { execSync } from 'node:child_process'
import { chromium } from 'playwright'
import { loginViaPortalPassword } from './playwright-login.mjs'

const ORIGIN = (process.env.HELP_GUIDE_ORIGIN ?? 'http://localhost:3000').replace(/\/$/, '')
const CODE = process.env.OWNER_DEMO_CODE ?? 'owner-demo-20260907-gehibh'
const INITIATOR = { user: 'e2e_zhangwei', pass: 'password' }
// The Review step is FIXED_BU_ROLE, and pool tasks are workspace-scoped: the
// reviewer must be logged into the E2E_FINANCE / MANAGER workspace to see them.
// The workspace context exposes the BU by name, not by the E2E_FINANCE code.
const REVIEWER = { user: 'developer', pass: 'password', buCode: '共享财务中心', roleCode: 'MANAGER' }

const results = []
function check(section, label, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  results.push({ ok, section, label, actual, expected })
  console.log(`${ok ? 'PASS' : 'FAIL'}  [${section}] ${label}`)
  if (!ok) console.log(`        expected ${JSON.stringify(expected)}\n        actual   ${JSON.stringify(actual)}`)
}

const browser = await chromium.launch({ channel: 'chrome', headless: true })

async function session(creds) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const page = await ctx.newPage()
  const me = await loginViaPortalPassword(page, creds)
  const api = async (method, path, data) => {
    const res = await page.request.fetch(`${ORIGIN}/api/portal${path}`, { method, data })
    return { status: res.status(), body: await res.json().catch(() => ({})) }
  }
  return { ctx, page, api, userId: me.userId }
}

/** Live main-table variables as the portal itself serves them. */
async function liveVars(api, instanceId) {
  const { body } = await api('GET', `/processes/${instanceId}/form`)
  return (body.data ?? body).fieldValues ?? {}
}

/** Portal list endpoints wrap rows differently per view; find the row by instance. */
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

const initiator = await session(INITIATOR)
const reviewer = await session(REVIEWER)
console.log(`initiator=${initiator.userId} reviewer=${reviewer.userId}\n`)

// ---------------------------------------------------------------- Stage 1: start
const title = `CaseHandler check ${Date.now()}`
const started = await initiator.api('POST', `/processes/${encodeURIComponent(CODE)}/start`, {
  processDefinitionKey: CODE,
  formData: { title },
  priority: 'NORMAL',
})
const instanceId = started.body.data?.id
if (!instanceId) throw new Error(`start failed: ${started.status} ${JSON.stringify(started.body).slice(0, 300)}`)
console.log(`instance ${instanceId}\n`)

let vars = await liveVars(initiator.api, instanceId)
check('3.3.1', 'Creator = submitter', vars.case_creator, `user:${initiator.userId}`)
const poolAtReview = String(vars.case_handler ?? '')
console.log(`        (Review pool = ${poolAtReview})`)
check('3.3.3', 'in-progress Case Handler is a user: value, not step:',
  poolAtReview.startsWith('user:'), true)
check('3.3.3', 'both CASE_HANDLER columns agree', vars.case_assignee, vars.case_handler)

// §6.7 — Basic Info / My Requests show the same thing as the main Case Handler.
const detail = await initiator.api('GET', `/processes/${instanceId}`)
const listed = await initiator.api('GET', '/processes/my-applications?page=0&size=20')
const listRow = findRow(listed.body, instanceId, 'id')
const poolNames = poolAtReview.split(',').filter(Boolean).length
check('6.7', 'Basic Info Current Assignee is populated while in progress',
  !!detail.body.data?.currentAssignee, true)
check('6.7', 'My Requests row matches Basic Info',
  listRow?.currentAssignee, detail.body.data?.currentAssignee)
console.log(`        (display = ${detail.body.data?.currentAssignee}; ${poolNames} id(s) in the column)\n`)

// ------------------------------------------------------- Stage 2: claim the Review task
const todo = await reviewer.api('POST', '/tasks/todo/query', { page: 0, size: 50 })
const reviewTask = findRow(todo.body, instanceId)
if (!reviewTask) throw new Error('reviewer has no To Do task for this instance')
const reviewTaskId = reviewTask.id ?? reviewTask.taskId
await reviewer.api('POST', `/tasks/${reviewTaskId}/claim`)

vars = await liveVars(initiator.api, instanceId)
check('3.3.3', 'claim overwrites Case Handler with the claimer',
  vars.case_handler, `user:${reviewer.userId}`)
check('3.3.3', 'Creator untouched by claim', vars.case_creator, `user:${initiator.userId}`)

// ------------------------------------------------- Stage 3: complete Review (approve)
await reviewer.api('POST', `/tasks/${reviewTaskId}/complete`, {
  taskId: reviewTaskId,
  action: 'APPROVE',
  comment: 'case handler check',
  formData: { title },
})

vars = await liveVars(initiator.api, instanceId)
const reviewSnap = vars[`_snapshot_${reviewTaskId}`]?.fieldValues ?? {}
check('6.6', 'Review snapshot froze the actual operator',
  reviewSnap.case_handler, `user:${reviewer.userId}`)
check('6.6', 'Review snapshot carries every Owner column on that form',
  reviewSnap.case_assignee, `user:${reviewer.userId}`)
check('6.6', 'Review snapshot keeps Creator', reviewSnap.case_creator, `user:${initiator.userId}`)
check('3.3.3', 'live Case Handler moved on to the Close assignee (initiator)',
  vars.case_handler, `user:${initiator.userId}`)

// ---------------------------------------------------- Stage 4: complete Close (terminal)
const todo2 = await initiator.api('POST', '/tasks/todo/query', { page: 0, size: 50 })
const closeTask = findRow(todo2.body, instanceId)
if (!closeTask) throw new Error('initiator has no Close task')
const closeTaskId = closeTask.id ?? closeTask.taskId
await initiator.api('POST', `/tasks/${closeTaskId}/complete`, {
  taskId: closeTaskId,
  action: 'APPROVE',
  comment: 'close',
  formData: { title },
})

vars = await liveVars(initiator.api, instanceId)
const closeSnap = vars[`_snapshot_${closeTaskId}`]?.fieldValues ?? {}
check('6.6', 'Close snapshot froze its own operator',
  closeSnap.case_handler, `user:${initiator.userId}`)
// The snapshot is the whole form payload, so it may carry Owner columns that are
// not on this node's form. §6.6 only requires that whatever it carries is the
// value frozen at completion time.
check('6.6', 'other Owner columns in the Close snapshot are frozen, not cleared',
  closeSnap.case_assignee, `user:${initiator.userId}`)
check('3.3.3', 'terminal clears main Case Handler', String(vars.case_handler ?? ''), '')
check('3.3.3', 'terminal clears the second CASE_HANDLER column too',
  String(vars.case_assignee ?? ''), '')
check('3.3.3', 'terminal does NOT clear Creator', vars.case_creator, `user:${initiator.userId}`)
check('6.6', 'Review snapshot survives the terminal clear',
  vars[`_snapshot_${reviewTaskId}`]?.fieldValues?.case_handler, `user:${reviewer.userId}`)

const detail2 = await initiator.api('GET', `/processes/${instanceId}`)
check('6.7', 'Basic Info Current Assignee is empty once terminal',
  String(detail2.body.data?.currentAssignee ?? ''), '')

// ------------------------------------- Stage 5: the values are really persisted
// The API could in principle compute Owner on the fly; read the row the portal
// stores so "was it actually written" is answered by the database, not the response.
const sql = `select variables from up_process_instance where id='${instanceId}'`
const stored = JSON.parse(execSync(
  `docker exec -e SQL=${JSON.stringify(sql)} platform-postgres-dev `
  + `sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "$SQL"'`,
).toString().trim() || '{}')
check('persist', 'Creator is stored in up_process_instance.variables',
  stored.case_creator, `user:${initiator.userId}`)
check('persist', 'terminal-cleared Case Handler is stored as empty, not stale',
  String(stored.case_handler ?? ''), '')
check('persist', 'the Review snapshot is stored with its frozen operator',
  stored[`_snapshot_${reviewTaskId}`]?.fieldValues?.case_handler, `user:${reviewer.userId}`)

console.log(`\ninstance ${instanceId}`)
console.log(`review task ${reviewTaskId} / close task ${closeTaskId}`)
const failed = results.filter(r => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} checks passed`)
await browser.close()
process.exit(failed.length === 0 ? 0 : 1)
