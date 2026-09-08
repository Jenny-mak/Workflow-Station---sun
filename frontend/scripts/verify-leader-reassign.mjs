/**
 * Leader Reassign: To Do Claimed By + Reassign, help #leader, Admin audit filters.
 */
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { loginViaAdminPassword, loginViaPortalPassword } from './playwright-login.mjs'

const ORIGIN = process.env.ORIGIN ?? 'http://localhost:3000'
const DATE = new Date().toISOString().slice(0, 10)
const PORTAL_OUT = join(dirname(fileURLToPath(import.meta.url)), '../user-portal/verification-screenshots')
const ADMIN_OUT = join(dirname(fileURLToPath(import.meta.url)), '../admin-center/verification-screenshots')
const HELP_OUT = join(dirname(fileURLToPath(import.meta.url)), '../help/verification-screenshots')

mkdirSync(PORTAL_OUT, { recursive: true })
mkdirSync(ADMIN_OUT, { recursive: true })
mkdirSync(HELP_OUT, { recursive: true })

let failures = 0
function check(label, ok, detail) {
  console.log(`${ok ? '[PASS]' : '[FAIL]'} ${label}${detail ? ` — ${detail}` : ''}`)
  if (!ok) failures++
}

const browser = await chromium.launch()

try {
  const helpPage = await browser.newPage({ viewport: { width: 1400, height: 900 } })
  await helpPage.goto(`${ORIGIN}/help/up-tasks-to-claim#leader`, { waitUntil: 'domcontentloaded' })
  await helpPage.waitForSelector('[data-testid="up-tasks-to-claim-guide-page"]', { timeout: 20000 })
  const leaderSection = helpPage.locator('#leader')
  await leaderSection.waitFor({ state: 'visible', timeout: 10000 })
  await leaderSection.scrollIntoViewIfNeeded()
  const leaderBody = await leaderSection.textContent()
  check('Help #leader mentions Reassign', /Reassign/i.test(leaderBody || ''))
  const helpShot = join(HELP_OUT, `${DATE}_up-tasks-to-claim-leader-reassign.png`)
  await helpPage.screenshot({ path: helpShot, fullPage: false })
  console.log(`[SHOT] ${helpShot}`)
  await helpPage.close()

  const portalPage = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  await loginViaPortalPassword(portalPage, { buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role' })
  await portalPage.goto(`${ORIGIN}/portal/tasks`, { waitUntil: 'domcontentloaded' })
  await portalPage.waitForSelector('.list-data-grid, .el-table', { timeout: 25000 })
  await portalPage.locator('.el-loading-mask').waitFor({ state: 'hidden', timeout: 20000 }).catch(() => {})
  const claimedByHeader = await portalPage.getByText('Claimed By', { exact: true }).count()
  check('To Do shows Claimed By column', claimedByHeader > 0)
  const reassignCount = await portalPage.locator('[data-test="todo-reassign-btn"]').count()
  if (reassignCount === 0) {
    console.log('[WARN] No Reassign button on current To Do page (no authorized claim-pool row)')
  } else {
    check('To Do Reassign button is present', true, `count=${reassignCount}`)
  }
  const todoShot = join(PORTAL_OUT, `${DATE}_todo-claimed-by-reassign.png`)
  await portalPage.screenshot({ path: todoShot, fullPage: false })
  console.log(`[SHOT] ${todoShot}`)
  if (reassignCount > 0) {
    await portalPage.locator('[data-test="todo-reassign-btn"]').first().click()
    await portalPage.waitForSelector('.task-reassign-dialog, [data-test="task-reassign-select"]', { timeout: 8000 })
    check('Reassign dialog opened', await portalPage.locator('[data-test="task-reassign-select"]').count() > 0)
    const dialogShot = join(PORTAL_OUT, `${DATE}_todo-reassign-dialog.png`)
    await portalPage.screenshot({ path: dialogShot, fullPage: false })
    console.log(`[SHOT] ${dialogShot}`)
  }
  await portalPage.close()

  const adminPage = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  await loginViaAdminPassword(adminPage)
  await adminPage.goto(`${ORIGIN}/admin/audit/user-portal`, { waitUntil: 'domcontentloaded' })
  await adminPage.waitForSelector('.search-form', { timeout: 25000 })
  await adminPage.locator('.search-form .el-select').nth(1).click()
  await adminPage.waitForSelector('.el-select-dropdown:visible', { timeout: 8000 })
  const optionText = await adminPage.locator('.el-select-dropdown:visible .el-select-dropdown__item').allTextContents()
  check('Admin audit filter includes Reassign', optionText.some((t) => /Reassign/i.test(t)))
  check('Admin audit filter includes Claim', optionText.some((t) => /^Claim$/.test(t.trim())))
  const reassignOption = adminPage.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: /^Reassign$/ })
  await reassignOption.scrollIntoViewIfNeeded()
  const auditShot = join(ADMIN_OUT, `${DATE}_up-audit-assignment-filters.png`)
  await adminPage.screenshot({ path: auditShot, fullPage: false })
  console.log(`[SHOT] ${auditShot}`)
  await adminPage.close()
} finally {
  await browser.close()
}

if (failures > 0) {
  process.exit(1)
}
