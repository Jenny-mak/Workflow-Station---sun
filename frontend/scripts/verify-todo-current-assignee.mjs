/**
 * Login as developer, walk every workspace, query To Do, screenshot a page
 * that has a non-claim-pool row with an assignee (Direct Assignment etc.).
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { loginViaPortalPassword } from './playwright-login.mjs'

const ORIGIN = 'http://localhost:3000'
const OUT_DIR = join(dirname(fileURLToPath(import.meta.url)), '../user-portal/verification-screenshots')
const DATE = new Date().toISOString().slice(0, 10)
const USERS = [
  { user: '123456', pass: 'password' },
  { user: 'developer', pass: 'password' },
  { user: 'e2e_lina', pass: 'password' },
]

async function loginBody(request, user, extra = {}) {
  const res = await request.post(`${ORIGIN}/api/portal/auth/login`, {
    data: { username: user.user, password: user.pass, ...extra },
  })
  return res.json().catch(() => ({}))
}

function workspaceLabel(c) {
  return `${c.businessUnitCode || c.businessUnitName || c.businessUnitId}/${c.roleCode || c.roleName || c.roleId}`
}

function summarize(row) {
  return {
    requestId: row.requestId,
    assignmentType: row.assignmentType,
    bpmnAssigneeType: row.bpmnAssigneeType,
    claimPoolTask: row.claimPoolTask,
    assignee: row.assignee,
    assigneeName: row.assigneeName,
  }
}

async function main() {
  mkdirSync(OUT_DIR, { recursive: true })
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1600, height: 900 } })
  const page = await context.newPage()
  await page.goto(`${ORIGIN}/portal/`, { waitUntil: 'commit' })

  let picked = null
  for (const user of USERS) {
    const first = await loginBody(page.request, user)
    const contexts = first.workspaceContexts || []
    if (!contexts.length && first.user) {
      contexts.push({ businessUnitCode: undefined, roleCode: undefined })
    }
    console.log(`[workspaces] ${user.user}`, contexts.map(workspaceLabel).join(', ') || '(none)')
    for (const c of contexts) {
      const session = await loginViaPortalPassword(page, {
        user: user.user,
        pass: user.pass,
        buCode: c.businessUnitCode || c.businessUnitName,
        roleCode: c.roleCode || c.roleName,
      })
      const res = await page.request.post(`${ORIGIN}/api/portal/tasks/todo/query`, {
        data: { page: 0, size: 50 },
      })
      const body = await res.json().catch(() => ({}))
      const rows = body.data?.content || body.content || []
      const total = body.data?.totalElements ?? rows.length
      console.log(`[todo] ${user.user} ${workspaceLabel(c)} user=${session.userId} http=${res.status()} total=${total}`)
      for (const row of rows.slice(0, 8)) {
        console.log('  ', JSON.stringify(summarize(row)))
      }
      const direct = rows.find((r) => !r.claimPoolTask && String(r.assignee || r.assigneeName || '').trim())
      if (direct && !picked) {
        picked = { user, context: c, row: direct }
        break
      }
    }
    if (picked) break
  }

  if (!picked) {
    console.log('[result] no non-pool assigned To Do row')
    await browser.close()
    process.exit(2)
  }

  await page.goto(`${ORIGIN}/portal/tasks`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2500)
  const cells = await page.locator('[data-test="todo-current-assignee"]').allTextContents()
  console.log('[cells]', JSON.stringify(cells))
  console.log('[picked]', JSON.stringify(summarize(picked.row)))
  const outPath = join(OUT_DIR, `${DATE}_todo-current-assignee-direct.png`)
  await page.screenshot({ path: outPath, fullPage: true })
  console.log('[saved]', outPath)

  const dashOnly = cells.length > 0 && cells.every((t) => t.trim() === '-')
  const pickedShown = cells.some((t) => {
    const name = String(picked.row.assigneeName || picked.row.assignee).trim()
    return t.includes(name) || t.trim() === 'You'
  })
  if (dashOnly || !pickedShown) {
    throw new Error(`Current Assignee still missing names. cells=${JSON.stringify(cells)}`)
  }
  await browser.close()
}

main().catch(async (err) => {
  console.error(err)
  process.exit(1)
})
