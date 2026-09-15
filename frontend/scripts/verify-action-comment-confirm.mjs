/**
 * Screenshots + assertions for Action Require Comment / Confirm Message,
 * and Designer English labels that must stay on one line.
 *
 * Run from frontend/:  node scripts/verify-action-comment-confirm.mjs
 *
 * Output (must keep):
 *   developer-workstation/verification-screenshots/{date}_dw-action-comment-confirm-labels.png
 *   user-portal/verification-screenshots/{date}_portal-approve-confirm.png
 *   user-portal/verification-screenshots/{date}_portal-approve-comment-required.png
 *   user-portal/verification-screenshots/{date}_portal-delegate-reason-required.png
 *   user-portal/verification-screenshots/{date}_portal-invalid-action-config.png
 */
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { loginViaDwPassword, loginViaPortalPassword } from './playwright-login.mjs'

const ORIGIN = process.env.ORIGIN ?? 'http://localhost:3000'
const DATE = new Date().toISOString().slice(0, 10)
const FRONTEND = join(dirname(fileURLToPath(import.meta.url)), '..')
const DW_OUT = join(FRONTEND, 'developer-workstation/verification-screenshots')
const PORTAL_OUT = join(FRONTEND, 'user-portal/verification-screenshots')
const TODO_SIZE = 20
const MAX_TASKS = 5

mkdirSync(DW_OUT, { recursive: true })
mkdirSync(PORTAL_OUT, { recursive: true })

const failures = []
function check(label, ok, detail = '') {
  console.log(`[${ok ? 'pass' : 'FAIL'}] ${label}${detail ? ` — ${detail}` : ''}`)
  if (!ok) failures.push(`${label}${detail ? `: ${detail}` : ''}`)
}

async function shot(page, dir, name, locator) {
  const p = join(dir, `${DATE}_${name}.png`)
  if (locator) {
    await locator.screenshot({ path: p })
  } else {
    await page.screenshot({ path: p })
  }
  console.log('[shot]', p)
  return p
}

async function queryTodo(page) {
  const res = await page.request.post(`${ORIGIN}/api/portal/tasks/todo/query`, {
    data: { page: 0, size: TODO_SIZE },
  })
  const body = await res.json().catch(() => ({}))
  const pageData = body.data ?? body
  const content = pageData.content ?? pageData.records ?? []
  return Array.isArray(content) ? content : []
}

function taskIdOf(row) {
  return String(row.taskId || row.id || '').trim()
}

async function resolveFuWithActions(page) {
  if (process.env.FU_ID) return String(process.env.FU_ID)
  const listRes = await page.request.get(`${ORIGIN}/api/v1/function-units`, {
    params: { page: 0, size: 50 },
  })
  const listBody = await listRes.json().catch(() => ({}))
  const data = listBody.data ?? listBody
  const content = data.content ?? data.records ?? (Array.isArray(data) ? data : [])
  console.log(`[fu] list HTTP ${listRes.status()} rows=${Array.isArray(content) ? content.length : '?'}`)
  for (const fu of content) {
    const id = fu.id ?? fu.functionUnitId
    if (!id) continue
    const aRes = await page.request.get(`${ORIGIN}/api/v1/function-units/${id}/actions`)
    const aBody = await aRes.json().catch(() => ({}))
    const actions = aBody.data ?? aBody
    if (Array.isArray(actions) && actions.length > 0) {
      console.log(`[fu] ${id} has ${actions.length} actions`)
      return String(id)
    }
  }
  const fallback = content[0]?.id ?? content[0]?.functionUnitId
  return fallback ? String(fallback) : ''
}

function injectActions(actions, extra) {
  const list = Array.isArray(actions) ? [...actions] : []
  for (const item of extra) {
    if (!list.some((a) => a.actionId === item.actionId)) {
      list.push(item)
    }
  }
  return list
}

async function openFirstTodo(page) {
  let tasks = await queryTodo(page)
  if (tasks.length === 0) {
    await loginViaPortalPassword(page)
    await page.goto(`${ORIGIN}/portal/tasks`, { waitUntil: 'domcontentloaded' })
    await page.locator('.tasks-page').waitFor({ timeout: 30000 }).catch(() => {})
    tasks = await queryTodo(page)
  }
  check('To Do list has rows', tasks.length > 0, `count=${tasks.length}`)
  for (const row of tasks.slice(0, MAX_TASKS)) {
    const id = taskIdOf(row)
    if (!id) continue
    await page.goto(`${ORIGIN}/portal/tasks/${id}`, { waitUntil: 'domcontentloaded' })
    const bar = page.locator('.action-section .right-actions')
    try {
      await bar.waitFor({ timeout: 20000 })
      return { id, bar }
    } catch {
      console.log(`[skip] task ${id} has no action bar`)
    }
  }
  return null
}

const browser = await chromium.launch({ headless: true })

try {
  const dwPage = await (await browser.newContext({ viewport: { width: 1440, height: 1000 } })).newPage()
  await loginViaDwPassword(dwPage)
  const fuId = await resolveFuWithActions(dwPage)
  check('Found a Function Unit to open Action Design', !!fuId, fuId || 'none')
  if (!fuId) {
    await shot(dwPage, DW_OUT, 'dw-action-comment-confirm-no-fu')
  } else {
  await dwPage.goto(`${ORIGIN}/dev/function-units/${fuId}?tab=actions`, { waitUntil: 'domcontentloaded' })
  await dwPage.waitForTimeout(4000)
  const actionsTab = dwPage.locator('.el-tabs__item', { hasText: /Action Design/i }).first()
  if (await actionsTab.count()) {
    await actionsTab.click()
    await dwPage.waitForTimeout(1500)
  }

  const firstRow = dwPage.locator('.el-table__row').first()
  try {
    await firstRow.waitFor({ timeout: 8000 })
  } catch {
    await shot(dwPage, DW_OUT, 'dw-action-comment-confirm-empty-list', dwPage.locator('body'))
  }
  check('Action list has a row to open', (await firstRow.count()) > 0)
  const editBtn = firstRow.getByRole('button', { name: /Edit/i }).first()
  if (await editBtn.count()) {
    await editBtn.click()
  } else if (await firstRow.count()) {
    await firstRow.click()
  }
  await dwPage.waitForTimeout(800)

  const typeSelect = dwPage.locator('.action-editor .el-select').first()
  if (await typeSelect.count()) {
    await typeSelect.click()
    await dwPage.waitForTimeout(400)
    const approveOpt = dwPage.locator('.el-select-dropdown__item', { hasText: /^\s*Approve\s*$/ }).first()
    if (await approveOpt.count()) {
      await approveOpt.click()
      await dwPage.waitForTimeout(600)
    } else {
      await dwPage.keyboard.press('Escape')
    }
  }

  const requireItem = dwPage.getByTestId('action-require-comment')
  const confirmItem = dwPage.getByTestId('action-confirm-message')
  try {
    await requireItem.waitFor({ timeout: 10000 })
    const requireText = (await requireItem.locator('.el-form-item__label').innerText()).replace(/\s+/g, ' ').trim()
    const confirmText = (await confirmItem.locator('.el-form-item__label').innerText()).replace(/\s+/g, ' ').trim()
    check('English Require Comment label', requireText === 'Comment required', requireText)
    check('English Confirm Message label', confirmText === 'Confirmation prompt', confirmText)
    const requireBox = await requireItem.locator('.el-form-item__label').boundingBox()
    const confirmBox = await confirmItem.locator('.el-form-item__label').boundingBox()
    check('Comment required label is one line', !!requireBox && requireBox.height <= 36 && !requireText.includes('\n'), `h=${requireBox?.height}`)
    check('Confirmation prompt label is one line', !!confirmBox && confirmBox.height <= 36 && !confirmText.includes('\n'), `h=${confirmBox?.height}`)
    await shot(dwPage, DW_OUT, 'dw-action-comment-confirm-labels', dwPage.locator('.action-editor'))
  } catch (err) {
    await shot(dwPage, DW_OUT, 'dw-action-comment-confirm-labels', dwPage.locator('body'))
    check('Opened Approve Comment/Confirm fields', false, err.message)
  }
  }
  await dwPage.close()

  const portalPage = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage()
  await loginViaPortalPassword(portalPage, { buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role' })
  await portalPage.goto(`${ORIGIN}/portal/tasks`, { waitUntil: 'domcontentloaded' })
  await portalPage.locator('.tasks-page').waitFor({ timeout: 30000 }).catch(() => {})

  await portalPage.route('**/api/portal/tasks/*', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    const res = await route.fetch()
    const body = await res.json().catch(() => null)
    const data = body?.data
    if (data?.taskId) {
      data.actions = injectActions(data.actions, [
        {
          actionId: 'verify-approve-comment',
          actionName: 'VerifyApprove',
          actionType: 'APPROVE',
          configJson: JSON.stringify({
            requireComment: true,
            confirmMessage: 'Proceed with approval?',
          }),
        },
        {
          actionId: 'verify-delegate-reason',
          actionName: 'VerifyDelegate',
          actionType: 'DELEGATE',
          configJson: JSON.stringify({ requireComment: true }),
        },
        {
          actionId: 'verify-bad-config',
          actionName: 'VerifyBadConfig',
          actionType: 'APPROVE',
          configJson: 'not-json',
        },
      ])
    }
    await route.fulfill({
      status: res.status(),
      headers: { ...res.headers(), 'content-type': 'application/json' },
      body: JSON.stringify(body),
    })
  })

  const opened = await openFirstTodo(portalPage)
  check('Opened a task detail with action bar', !!opened)
  if (!opened) {
    await shot(portalPage, PORTAL_OUT, 'portal-action-comment-no-task')
  } else {
    const { bar } = opened

    await bar.getByRole('button', { name: 'VerifyApprove' }).click()
    const confirmBox = portalPage.locator('.el-message-box')
    await confirmBox.waitFor({ timeout: 8000 })
    const confirmText = await confirmBox.innerText()
    check('Confirm Message is shown', confirmText.includes('Proceed with approval?'))
    await shot(portalPage, PORTAL_OUT, 'portal-approve-confirm', confirmBox)
    await confirmBox.getByRole('button', { name: /OK|确定|確定/ }).click()

    const approveDialog = portalPage.locator('.el-dialog').filter({
      has: portalPage.getByTestId('approve-comment-field'),
    })
    await approveDialog.waitFor({ timeout: 8000 })
    const required = await portalPage.getByTestId('approve-comment-field').evaluate((el) => el.classList.contains('is-required'))
    check('Approve comment field is required', required)
    await shot(portalPage, PORTAL_OUT, 'portal-approve-comment-required', approveDialog)
    await approveDialog.getByRole('button', { name: /Cancel|取消/ }).click()
    await approveDialog.waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {})

    await bar.getByRole('button', { name: 'VerifyDelegate' }).click()
    const delegateDialog = portalPage.locator('.el-dialog').filter({
      has: portalPage.locator('.task-action-dialog-title'),
    })
    await delegateDialog.waitFor({ timeout: 8000 })
    const reasonRequired = await portalPage.getByTestId('action-reason-field').evaluate((el) => el.classList.contains('is-required'))
    check('Delegate reason field is required', reasonRequired)
    await shot(portalPage, PORTAL_OUT, 'portal-delegate-reason-required', delegateDialog)
    await delegateDialog.getByRole('button', { name: /Cancel|取消/ }).click()
    await delegateDialog.waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {})

    await bar.getByRole('button', { name: 'VerifyBadConfig' }).click()
    const err = portalPage.locator('.el-message--error').first()
    await err.waitFor({ timeout: 8000 })
    check('Invalid configJson shows an error toast', (await err.count()) > 0)
    await shot(portalPage, PORTAL_OUT, 'portal-invalid-action-config')
  }
  await portalPage.close()
} finally {
  await browser.close()
}

if (failures.length) {
  console.error('\nFAILURES:', failures.join('; '))
  process.exit(1)
}
console.log('\nAll checks passed.')
