/**
 * Verify DesignerHelpLink targets: Connections, Form events, selected control,
 * Table Design, and View Design access.
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { loginViaDwPassword } from './playwright-login.mjs'
import { redactHelpGuidePii } from './redact-help-guide-pii.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const DW_SHOTS = resolve(__dirname, '../developer-workstation/verification-screenshots')
mkdirSync(DW_SHOTS, { recursive: true })
const DATE = new Date().toISOString().slice(0, 10)
const origin = 'http://localhost:3000'

const results = []
const rec = (n, ok, d = '') => {
  results.push({ n, ok, d })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${n}${d ? ` — ${d}` : ''}`)
}

/**
 * @param {import('playwright').Page} page
 * @param {import('playwright').BrowserContext} context
 * @param {{
 *   testId: string
 *   visible: string
 *   opens: string
 *   parts: string[]
 *   linkShot: string
 *   targetShot: string
 *   shot?: import('playwright').Locator
 * }} opts
 */
async function assertHelpLink(page, context, opts) {
  const helpLink = page.getByTestId(opts.testId)
  await helpLink.waitFor({ state: 'visible', timeout: 15000 })
  rec(opts.visible, await helpLink.isVisible())
  const href = (await helpLink.getAttribute('href')) || ''
  rec(`${opts.opens} (href)`, opts.parts.every((part) => href.includes(part)), href)
  await redactHelpGuidePii(page)
  if (opts.shot) {
    const linkPath = resolve(DW_SHOTS, `${DATE}_${opts.linkShot}.png`)
    await opts.shot.screenshot({ path: linkPath })
    console.log(`screenshot ${linkPath}`)
  }
  const popupPromise = context.waitForEvent('page')
  await helpLink.click()
  const popup = await popupPromise
  const escaped = opts.parts[0].replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  await popup.waitForURL(new RegExp(escaped), { timeout: 15000 })
  rec(opts.opens, opts.parts.every((part) => popup.url().includes(part)), popup.url())
  const targetPath = resolve(DW_SHOTS, `${DATE}_${opts.targetShot}.png`)
  await popup.screenshot({ path: targetPath, fullPage: true })
  console.log(`screenshot ${targetPath}`)
  await popup.close()
}

async function resolveHelpFuId(page) {
  const forced = process.env.HELP_GUIDE_FU_ID?.trim()
  if (forced) return forced
  const res = await page.request.get(`${origin}/api/v1/function-units?page=0&size=200`)
  const body = await res.json()
  const data = body?.data
  const records = data?.records || data?.content || (Array.isArray(data) ? data : [])
  const purchase = records.find((fu) =>
    /Purchase Request/i.test(String(fu.name || fu.functionUnitName || '')),
  )
  return purchase ? String(purchase.id) : '50007'
}

const launchOpts = { headless: true }
if (process.env.PLAYWRIGHT_EXECUTABLE_PATH) {
  launchOpts.executablePath = process.env.PLAYWRIGHT_EXECUTABLE_PATH
} else if (process.env.PLAYWRIGHT_CHANNEL) {
  launchOpts.channel = process.env.PLAYWRIGHT_CHANNEL
}

const browser = await chromium.launch(launchOpts)
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()

try {
  await loginViaDwPassword(page)
  const fuId = await resolveHelpFuId(page)
  console.log(`HELP_GUIDE_FU_ID=${fuId}`)
  await page.goto(`${origin}/dev/function-units/${fuId}`, { waitUntil: 'domcontentloaded' })
  await page.locator('.el-tabs').first().waitFor({ state: 'visible', timeout: 25000 })
  await page.getByRole('tab', { name: 'Connections', exact: true }).click()
  await page.waitForTimeout(1200)

  const helpLink = page.getByTestId('connection-guide-link')
  await helpLink.waitFor({ state: 'visible', timeout: 15000 })
  rec('Connections toolbar has the help ?', await helpLink.isVisible())

  await redactHelpGuidePii(page)
  const connShot = resolve(DW_SHOTS, `${DATE}_dw-connection-help-link.png`)
  await page.locator('.designer-workspace').screenshot({ path: connShot })
  console.log(`screenshot ${connShot}`)

  const popupPromise = context.waitForEvent('page')
  await helpLink.click()
  const popup = await popupPromise
  await popup.waitForURL(/\/help\/email-send/, { timeout: 15000 })
  rec(
    'Help ? opens /help/email-send',
    popup.url().includes('/help/email-send'),
    popup.url(),
  )
  const helpShot = resolve(DW_SHOTS, `${DATE}_dw-connection-help-target.png`)
  await popup.screenshot({ path: helpShot, fullPage: true })
  console.log(`screenshot ${helpShot}`)
  await popup.close()

  await page.getByRole('tab', { name: 'Form Design', exact: true }).click()
  await page.waitForTimeout(1200)
  const formEdit = page
    .locator('.form-list-sidebar .el-table__row')
    .filter({ hasText: /help_pr|Request Form/ })
    .getByRole('button', { name: 'Edit' })
    .first()
  await formEdit.waitFor({ state: 'visible', timeout: 15000 })
  await formEdit.click()
  await page.locator('.fc-designer-wrapper').first().waitFor({ state: 'visible', timeout: 15000 })
  await page.waitForTimeout(800)
  const titleField = page.locator('.el-form-item').filter({ hasText: /^Title/ }).first()
  if (await titleField.count()) await titleField.click({ force: true })
  else await page.locator('.el-form-item').first().click({ force: true })
  await page.waitForFunction(
    () =>
      document
        .querySelector('[data-testid="form-control-guide-link"]')
        ?.getAttribute('href')
        ?.includes('form-ctl') === true,
    { timeout: 8000 },
  )
  await assertHelpLink(page, context, {
    testId: 'form-control-guide-link',
    visible: 'Form Design toolbar has the selected-control help ?',
    opens: 'Selected-control ? opens /help/form-ctl-input',
    parts: ['/help/form-ctl-input'],
    linkShot: 'dw-form-control-help-link',
    targetShot: 'dw-form-control-help-target',
    shot: page.locator('.editor-header'),
  })
  await page.locator('._fd-event .el-button').first().click()
  const eventDlg = page.locator('._fd-event-dialog').last()
  await eventDlg.waitFor({ state: 'visible', timeout: 8000 })
  const eventHelp = page.getByTestId('form-events-guide-link')
  await eventHelp.waitFor({ state: 'visible', timeout: 8000 })
  rec('Form event dialog has the help ?', await eventHelp.isVisible())
  await redactHelpGuidePii(page)
  const eventShot = resolve(DW_SHOTS, `${DATE}_dw-form-events-help-link.png`)
  await eventDlg.screenshot({ path: eventShot })
  console.log(`screenshot ${eventShot}`)
  const eventPopupPromise = context.waitForEvent('page')
  await eventHelp.click()
  const eventPopup = await eventPopupPromise
  await eventPopup.waitForURL(/\/help\/form-events/, { timeout: 15000 })
  rec(
    'Help ? opens /help/form-events#when',
    eventPopup.url().includes('/help/form-events') && eventPopup.url().includes('when'),
    eventPopup.url(),
  )
  const eventHelpShot = resolve(DW_SHOTS, `${DATE}_dw-form-events-help-target.png`)
  await eventPopup.screenshot({ path: eventHelpShot, fullPage: true })
  console.log(`screenshot ${eventHelpShot}`)
  await eventPopup.close()
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)
  await eventDlg.waitFor({ state: 'hidden', timeout: 8000 }).catch(() => {})

  const formConfigTab = page.locator('._fc-r-tab').filter({ hasText: /^Form$/ })
  await formConfigTab.first().waitFor({ state: 'visible', timeout: 8000 })
  await formConfigTab.first().click()
  await page.waitForTimeout(600)
  const fnBtn = page.locator('._fd-fn-list .el-button').first()
  await fnBtn.waitFor({ state: 'visible', timeout: 10000 })
  await fnBtn.click()
  const fnDlg = page.locator('._fd-fn-list-dialog').last()
  await fnDlg.waitFor({ state: 'visible', timeout: 8000 })
  const fnHelp = page.getByTestId('form-event-guide-link')
  await fnHelp.waitFor({ state: 'visible', timeout: 8000 })
  rec('Form event dialog has the help ?', await fnHelp.isVisible())
  const fnShot = resolve(DW_SHOTS, `${DATE}_dw-form-event-help-link.png`)
  await fnDlg.screenshot({ path: fnShot })
  console.log(`screenshot ${fnShot}`)
  const fnPopupPromise = context.waitForEvent('page')
  await fnHelp.click()
  const fnPopup = await fnPopupPromise
  await fnPopup.waitForURL(/\/help\/form-events/, { timeout: 15000 })
  rec(
    'Form event ? opens /help/form-events#form-level',
    fnPopup.url().includes('/help/form-events') && fnPopup.url().includes('form-level'),
    fnPopup.url(),
  )
  const fnHelpShot = resolve(DW_SHOTS, `${DATE}_dw-form-event-help-target.png`)
  await fnPopup.screenshot({ path: fnHelpShot, fullPage: true })
  console.log(`screenshot ${fnHelpShot}`)
  await fnPopup.close()
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)

  const back = page.getByRole('button', { name: 'Back to List' })
  await back.waitFor({ state: 'visible', timeout: 8000 })
  await back.click()
  await page.waitForTimeout(400)

  await page.getByRole('tab', { name: 'Table Design', exact: true }).click()
  await page.locator('.table-designer').waitFor({ state: 'visible', timeout: 15000 })
  await page.waitForTimeout(800)
  await assertHelpLink(page, context, {
    testId: 'table-design-guide-link',
    visible: 'Table Design toolbar has the help ?',
    opens: 'Table Design ? opens /help/table-design',
    parts: ['/help/table-design'],
    linkShot: 'dw-table-design-help-link',
    targetShot: 'dw-table-design-help-target',
    shot: page.locator('.designer-toolbar').first(),
  })

  await page.getByRole('tab', { name: 'View Design', exact: true }).click()
  await page.locator('.access-control-section').waitFor({ state: 'visible', timeout: 20000 })
  await page.getByTestId('view-access-guide-link').scrollIntoViewIfNeeded()
  await page.waitForTimeout(400)
  await assertHelpLink(page, context, {
    testId: 'view-access-guide-link',
    visible: 'View Design access heading has the help ?',
    opens: 'View access ? opens /help/view-design#access',
    parts: ['/help/view-design', 'access'],
    linkShot: 'dw-view-access-help-link',
    targetShot: 'dw-view-access-help-target',
    shot: page.locator('.access-control-section'),
  })
} finally {
  await browser.close()
}

const failed = results.filter((r) => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} passed`)
if (failed.length) process.exit(1)
