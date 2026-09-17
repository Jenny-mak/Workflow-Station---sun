/**
 * Standing Delegations menu: page header + ?, USER/BU+Role create dialogs,
 * Delegated tab (shared list), workspace switch.
 *
 * Usage (from frontend/):
 *   node scripts/verify-portal-delegations-menu.mjs
 *
 * Output: user-portal/verification-screenshots/{date}_delegations-*.png
 */
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { loginViaPortalPassword } from './playwright-login.mjs'

const ORIGIN = process.env.ORIGIN ?? 'http://localhost:3000'
const OUT = join(dirname(fileURLToPath(import.meta.url)), '../user-portal/verification-screenshots')
const DATE = new Date().toISOString().slice(0, 10)

mkdirSync(OUT, { recursive: true })

function check(label, ok, detail) {
  console.log(`${ok ? '[PASS]' : '[FAIL]'} ${label}${detail ? ` — ${detail}` : ''}`)
  if (!ok) throw new Error(`${label}${detail ? `: ${detail}` : ''}`)
}

async function shot(page, slug, options = {}) {
  const path = join(OUT, `${DATE}_${slug}.png`)
  await page.screenshot({ path, fullPage: false, ...options })
  console.log(`[SHOT] ${path} url=${page.url()}`)
  return path
}

async function shotLocator(locator, slug) {
  const path = join(OUT, `${DATE}_${slug}.png`)
  await locator.screenshot({ path })
  console.log(`[SHOT] ${path}`)
  return path
}

async function waitForDelegationsPage(page) {
  await page.locator('.delegations-page').waitFor({ timeout: 30000 })
  await page.locator('.el-loading-mask').waitFor({ state: 'hidden', timeout: 20000 }).catch(() => {})
}

function createDialog(page) {
  return page.locator('.el-dialog').filter({ hasText: /Create Delegation|创建委托|建立委託/ }).first()
}

async function openCreateDialog(page) {
  await page.locator('.delegations-page .page-header .el-button--primary').click()
  const dialog = createDialog(page)
  await dialog.waitFor({ state: 'visible', timeout: 15000 })
  await page.waitForTimeout(700)
  return dialog
}

async function closeCreateDialog(page) {
  const dialog = createDialog(page)
  if (!(await dialog.isVisible().catch(() => false))) return
  const cancel = dialog.locator('button').filter({ hasText: /Cancel|取消/ }).first()
  if (await cancel.count()) {
    await cancel.click()
  } else {
    await page.keyboard.press('Escape')
  }
  await dialog.waitFor({ state: 'hidden', timeout: 10000 }).catch(() => {})
  await page.locator('.el-overlay').waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {})
}

const launchOpts = { headless: true, channel: 'chrome' }
if (process.env.PLAYWRIGHT_EXECUTABLE_PATH) {
  delete launchOpts.channel
  launchOpts.executablePath = process.env.PLAYWRIGHT_EXECUTABLE_PATH
} else if (process.env.PLAYWRIGHT_CHANNEL) {
  launchOpts.channel = process.env.PLAYWRIGHT_CHANNEL
}
const browser = await chromium.launch(launchOpts)
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('console', (msg) => {
  if (msg.type() === 'error') console.log(`[page-error] ${msg.text()}`)
})

try {
  await loginViaPortalPassword(page, { buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role' })
  await page.goto(`${ORIGIN}/portal/delegations`, { waitUntil: 'domcontentloaded' })
  await waitForDelegationsPage(page)

  const helpLink = page.getByTestId('delegation-guide-link')
  check('Standing-delegation ? help link is visible', await helpLink.isVisible())
  check(
    'Help link targets /help/up-delegations',
    (await helpLink.getAttribute('href') || '').includes('/help/up-delegations'),
    await helpLink.getAttribute('href'),
  )

  const tabLabels = await page.locator('.el-tabs__item').allTextContents()
  const tabText = tabLabels.join(' | ')
  check(
    'Delegated tab copy is 委托任务 / Delegated (not Proxy Tasks)',
    tabLabels.some((t) => /委托任务|委託任務|Delegated/.test(t)) && !tabLabels.some((t) => /Proxy Tasks/.test(t)),
    tabText,
  )
  await shot(page, 'delegations-my-rules')

  const dialog = await openCreateDialog(page)
  const userLookup = page.getByTestId('delegation-user-lookup')
  check('USER create dialog shows user lookup (not hardcoded users)', await userLookup.isVisible())
  check(
    'USER radio is present',
    await dialog.getByText(/指定用户|Specified user|指定使用者/i).first().isVisible(),
  )
  await userLookup.click()
  const storedUser = await page.evaluate(() => {
    try {
      return JSON.parse(localStorage.getItem('ws_up_user') || '{}')
    } catch {
      return {}
    }
  })
  const lookupDropdown = page.locator('.lookup-dropdown:visible').last()
  await lookupDropdown.waitFor({ state: 'visible', timeout: 10000 })
  await lookupDropdown.locator('.el-loading-mask').waitFor({ state: 'hidden', timeout: 15000 }).catch(() => {})
  const firstPage = ((await lookupDropdown.locator('.el-table__body').textContent()) || '').trim()
  check(
    'Delegate To lookup loaded other users',
    firstPage.length > 0 && !/No Data|暂无数据|暫無資料/.test(firstPage),
    firstPage.slice(0, 240),
  )
  check(
    'First page omits current user',
    !storedUser.username || !firstPage.includes(storedUser.username),
    `username=${storedUser.username || '(missing)'} table="${firstPage.slice(0, 240)}"`,
  )
  const lookupInput = userLookup.locator('input').first()
  if (storedUser.username) {
    await lookupInput.fill(storedUser.username)
    await page.waitForTimeout(500)
    await lookupDropdown.locator('.el-loading-mask').waitFor({ state: 'hidden', timeout: 10000 }).catch(() => {})
  }
  const searched = ((await lookupDropdown.locator('.el-table__body').textContent()) || '').trim()
  check(
    'Search for self yields no row',
    !storedUser.username || !searched.includes(storedUser.username),
    `username=${storedUser.username || '(missing)'} table="${searched.slice(0, 240)}"`,
  )
  await shot(page, 'delegations-create-user-lookup-no-self')
  await dialog.getByText(/指定用户|Specified user|指定使用者/i).first().click()
  await lookupDropdown.waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {})
  await shotLocator(dialog, 'delegations-create-user')

  const typeSelect = dialog.getByTestId('delegation-type-select')
  await typeSelect.click()
  await page.waitForTimeout(400)
  const typeDropdown = page.locator('.el-select-dropdown:visible').last()
  const typeOptions = ((await typeDropdown.textContent()) || '').trim()
  check(
    'Create type options exclude Urgent',
    /All|全部/.test(typeOptions) && /Partial|部分/.test(typeOptions) && /Temporary|临时|臨時/.test(typeOptions)
      && !/Urgent|紧急委托|緊急委託/.test(typeOptions),
    typeOptions,
  )
  await typeDropdown.getByText(/Partial|部分/).first().click()
  await page.waitForTimeout(500)
  const fuSelect = dialog.getByTestId('delegation-process-types')
  check('Partial shows Function Unit name picker', await fuSelect.isVisible())
  await fuSelect.click()
  await page.waitForTimeout(500)
  await shotLocator(dialog, 'delegations-create-partial-fu')
  await page.keyboard.press('Escape')
  await page.waitForTimeout(300)

  await dialog.getByText(/指定 BU|Specified BU/i).first().click()
  await page.waitForTimeout(800)
  const cascader = dialog.locator('.el-cascader')
  const roleSelect = dialog.locator('.el-select').first()
  check('BU+Role create dialog shows BU cascader', await cascader.isVisible())
  check('BU+Role create dialog shows Role select', await roleSelect.isVisible())
  await shotLocator(dialog, 'delegations-create-bu-role')
  await closeCreateDialog(page)

  const proxyTab = page.locator('.el-tabs__item').filter({ hasText: /委托任务|委託任務|^Delegated$/ }).first()
  await proxyTab.click()
  const proxyPane = page.locator('#pane-proxy')
  await proxyPane.waitFor({ state: 'visible', timeout: 15000 })
  await page.locator('.el-loading-mask').waitFor({ state: 'hidden', timeout: 20000 }).catch(() => {})
  const proxyEmpty = await proxyPane.getByText(/No delegated tasks|暂无委托任务|暫無委託任務/).count()
  const proxyHeaders = await proxyPane.getByText(/REQUEST ID|申请编号|申請編號/i).count()
  check(
    'Delegated tab shows shared list (headers or empty state)',
    proxyHeaders > 0 || proxyEmpty > 0,
  )
  await shot(page, 'delegations-proxy-tasks')

  const switchBtn = page.locator('.workspace-context-bar .ctx-switch')
  const canSwitch = (await switchBtn.count()) > 0 && (await switchBtn.isVisible())
  if (canSwitch) {
    const beforeLabel = (await page.locator('.workspace-context-bar .ctx-text').textContent()) || ''
    await switchBtn.click()
    await page.waitForTimeout(400)
    await shot(page, 'delegations-workspace-switcher-open')
    const other = page.locator('.el-dropdown-menu__item:not(.is-disabled)').first()
    if (await other.count()) {
      const pickLabel = ((await other.textContent()) || '').trim()
      await other.click()
      await page.waitForTimeout(3000)
      await page.goto(`${ORIGIN}/portal/delegations`, { waitUntil: 'domcontentloaded' })
      await waitForDelegationsPage(page)
      await page.locator('.el-tabs__item').filter({ hasText: /委托任务|委託任務|^Delegated$/ }).first().click()
      await page.waitForTimeout(1500)
      const afterLabel = (await page.locator('.workspace-context-bar .ctx-text').textContent()) || ''
      check(
        'Workspace switcher changed active BU+Role',
        afterLabel.trim() !== '' && afterLabel.trim() !== beforeLabel.trim(),
        `before="${beforeLabel.trim()}" after="${afterLabel.trim()}" pick="${pickLabel}"`,
      )
      await shot(page, 'delegations-proxy-tasks-workspace-switched')
    } else {
      await page.keyboard.press('Escape')
      await shot(page, 'delegations-workspace-switch-no-other-context')
      console.log('[WARN] No other workspace context to switch to')
    }
  } else {
    await shot(page, 'delegations-workspace-switcher-hidden')
    console.log('[WARN] Workspace switcher not visible for this user')
  }

  const helpPage = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  await helpPage.goto(`${ORIGIN}/help/up-delegations`, { waitUntil: 'domcontentloaded' })
  const article = helpPage.getByTestId('up-delegations-guide-page')
  await article.waitFor({ state: 'visible', timeout: 20000 })
  check('Standing-delegation Help article is visible', await article.isVisible())
  const helpShot = join(OUT, `${DATE}_help-up-delegations.png`)
  await helpPage.screenshot({ path: helpShot, fullPage: true })
  console.log(`[SHOT] ${helpShot}`)
  await helpPage.close()

  console.log('[OK] delegations menu screenshots complete')
} catch (err) {
  const failPath = join(OUT, `${DATE}_delegations-menu-fail.png`)
  await page.screenshot({ path: failPath, fullPage: true }).catch(() => {})
  console.error(`[FAIL] saved ${failPath}`)
  throw err
} finally {
  await browser.close()
}
