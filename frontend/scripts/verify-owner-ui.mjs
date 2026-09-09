/**
 * Owner UI screenshots via the machine's Google Chrome (channel: chrome).
 * Writes frontend/{developer-workstation,user-portal}/verification-screenshots/2026-09-07_*.png
 */
import { mkdirSync } from 'fs'
import { join, dirname } from 'path'
import { fileURLToPath } from 'url'
import { chromium } from 'playwright'
import { loginViaDwPassword, loginViaPortalPassword } from './playwright-login.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ORIGIN = (process.env.HELP_GUIDE_ORIGIN ?? 'http://localhost:3000').replace(/\/$/, '')
const DATE = '2026-09-07'
const FU_ID = process.env.OWNER_DEMO_FU_ID ?? '50006'

async function shot(page, dir, slug, selector) {
  mkdirSync(dir, { recursive: true })
  const dest = join(dir, `${DATE}_${slug}.png`)
  if (selector) {
    const loc = page.locator(selector).first()
    if ((await loc.count()) > 0 && (await loc.isVisible().catch(() => false))) {
      await loc.screenshot({ path: dest })
      console.log('wrote', dest)
      return dest
    }
  }
  await page.screenshot({ path: dest, fullPage: false })
  console.log(selector ? 'wrote-fallback' : 'wrote', dest)
  return dest
}

async function dismissDwWorkspace(page) {
  const dialog = page.locator('.el-dialog').filter({
    hasText: /选择工作区|Select a workspace|選擇工作區/,
  }).first()
  if (!(await dialog.isVisible({ timeout: 2500 }).catch(() => false))) return
  console.log('dw workspace dialog visible — selecting first team')
  await dialog.locator('.el-radio').first().click()
  await dialog.locator('button').filter({ hasText: /进入|Enter|確認|确认/ }).click()
  await page.waitForLoadState('domcontentloaded').catch(() => {})
  await page.waitForTimeout(2000)
}

const browser = await chromium.launch({ channel: 'chrome', headless: true })

// --- DW: Owner source dropdown ---
const dwDir = join(__dirname, '../developer-workstation/verification-screenshots')
const dwCtx = await browser.newContext({ viewport: { width: 1440, height: 1100 } })
const dw = await dwCtx.newPage()
await loginViaDwPassword(dw)
await dw.evaluate(() => localStorage.setItem('ws_dw_active_group', '__ALL__'))
await dw.goto(`${ORIGIN}/dev/function-units/${FU_ID}`, { waitUntil: 'domcontentloaded', timeout: 60000 })
await dw.waitForTimeout(2000)
await dismissDwWorkspace(dw)
console.log('dw url', dw.url())
await shot(dw, dwDir, 'owner-fu-landing', null)

const formsTab = dw.locator('.el-tabs__item').filter({ hasText: /Form Design|表单设计|表單設計/ }).first()
await formsTab.waitFor({ timeout: 20000 })
await formsTab.click()
await dw.waitForTimeout(2000)
await shot(dw, dwDir, 'owner-forms-tab', null)

const requestScene = dw.locator('.el-tabs__item').filter({ hasText: /My Requests|我的申请|我的申請|REQUEST/i }).first()
if (await requestScene.count()) {
  await requestScene.click()
  await dw.waitForTimeout(800)
}

const editBtn = dw.locator('.el-table__body tr:visible').first().locator('button').filter({
  hasText: /Edit|编辑|編輯/,
}).first()
if (await editBtn.count()) {
  await editBtn.click()
  await dw.waitForTimeout(2500)
}

await shot(dw, dwDir, 'owner-form-canvas', null)

const canvasOwner = dw.locator('.el-form-item, ._fc-l-item, .fc-form-item').filter({
  hasText: /Creator|Case Handler|Current Handler|创建人|归属人|经办/,
}).first()
if (await canvasOwner.count()) {
  await canvasOwner.click()
  await dw.waitForTimeout(800)
} else {
  const byTitle = dw.getByText(/Creator|Case Handler/, { exact: false }).first()
  if (await byTitle.count()) await byTitle.click()
  await dw.waitForTimeout(800)
}

await shot(dw, dwDir, 'owner-source-select', '.owner-config-editor')
const select = dw.locator('.owner-config-editor__select')
if (await select.count()) {
  await select.click()
  await dw.waitForTimeout(400)
  await shot(dw, dwDir, 'owner-source-options', 'body')
}
await dwCtx.close()

// --- Portal: start Owner Demo ---
const portalDir = join(__dirname, '../user-portal/verification-screenshots')
const pCtx = await browser.newContext({ viewport: { width: 1440, height: 1100 } })
const portal = await pCtx.newPage()
await loginViaPortalPassword(portal, { user: 'e2e_zhangwei', pass: 'password' })

const listRes = await portal.request.get(`${ORIGIN}/api/portal/processes/definitions`)
const listJson = await listRes.json().catch(() => ({}))
const available = Array.isArray(listJson.data) ? listJson.data : []
const ownerProc = available.find((r) =>
  String(r.name || r.processName || r.processDefinitionName || '').includes('Owner Demo')
  || String(r.key || r.processDefinitionKey || '').includes('owner-demo'))
console.log('definitions', available.length, 'owner', ownerProc?.key || ownerProc?.processDefinitionKey, ownerProc?.name)

await portal.goto(`${ORIGIN}/portal/processes`, { waitUntil: 'domcontentloaded' })
await portal.waitForTimeout(2500)
await shot(portal, portalDir, 'owner-process-list', null)

const key = ownerProc?.key || ownerProc?.processDefinitionKey
if (key) {
  await portal.goto(`${ORIGIN}/portal/processes/start/${encodeURIComponent(key)}`, {
    waitUntil: 'domcontentloaded',
    timeout: 60000,
  })
  await portal.waitForTimeout(3500)
  await shot(portal, portalDir, 'owner-process-start', '.form-layout, .el-form')
  await shot(portal, portalDir, 'owner-field-runtime', '.owner-field')
}

const appsRes = await portal.request.get(`${ORIGIN}/api/portal/processes/my-applications?page=0&size=20`)
const appsJson = await appsRes.json().catch(() => ({}))
const apps = appsJson.data?.records || appsJson.data?.content || []
const ownerApp = (Array.isArray(apps) ? apps : []).find((r) =>
  String(r.processName || r.processDefinitionName || r.functionUnitName || '').includes('Owner Demo')
  || String(r.processDefinitionKey || '').includes('owner-demo'))
console.log('apps', Array.isArray(apps) ? apps.length : 0, 'ownerApp', ownerApp?.id || ownerApp?.processInstanceId)

await portal.goto(`${ORIGIN}/portal/applications`, { waitUntil: 'domcontentloaded' })
await portal.waitForTimeout(2500)
await shot(portal, portalDir, 'owner-my-requests', null)

const appId = ownerApp?.id || ownerApp?.processInstanceId
if (appId) {
  await portal.goto(`${ORIGIN}/portal/applications/${encodeURIComponent(appId)}`, {
    waitUntil: 'domcontentloaded',
    timeout: 60000,
  })
  await portal.waitForTimeout(3500)
  await shot(portal, portalDir, 'owner-application-detail', null)
}

await pCtx.close()
await browser.close()
console.log('done')
