/**
 * Form Design My Request / Assign Task show “Add Advanced Upload from New Request”
 * so missing widgets are placed on that canvas (same field keys), not invented by Portal.
 * Screenshots: frontend/developer-workstation/verification-screenshots/
 */
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { loginViaDwPassword } from './playwright-login.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const DW_SHOTS = resolve(__dirname, '../developer-workstation/verification-screenshots')
mkdirSync(DW_SHOTS, { recursive: true })
const DATE = new Date().toISOString().slice(0, 10)
const ORIGIN = process.env.ORIGIN ?? 'http://localhost:3000'
const FU_ID = process.env.FU_ID ?? '50009'

const results = []
const rec = (n, ok, d = '') => {
  results.push({ n, ok, d })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${n}${d ? ` — ${d}` : ''}`)
}

async function openFormDesign(page) {
  await page.goto(`${ORIGIN}/dev/function-units/${FU_ID}`, { waitUntil: 'domcontentloaded' })
  await page.locator('.el-tabs').first().waitFor({ state: 'visible', timeout: 25000 })
  await page.getByRole('tab', { name: 'Form Design', exact: true }).click()
  await page.locator('.form-designer').first().waitFor({ state: 'visible', timeout: 25000 })
}

async function openSceneForm(page, sceneName, formName) {
  const tab = page.locator('.form-scene-tabs .el-tabs__item').filter({ hasText: sceneName }).first()
  await tab.click({ timeout: 15000 })
  await page.waitForTimeout(500)
  await page.getByText(formName, { exact: true }).first().click({ timeout: 15000 })
  await page.locator('fc-designer, .fc-designer-wrapper').first().waitFor({ state: 'visible', timeout: 25000 })
  await page.waitForTimeout(800)
}

async function assertAddButton(page, scene) {
  const btn = page.getByTestId('add-advanced-upload-from-new-request')
  const visible = await btn.waitFor({ state: 'visible', timeout: 8000 }).then(() => true).catch(() => false)
  rec(`${scene} shows Add Advanced Upload from New Request`, visible)
  const shot = resolve(DW_SHOTS, `${DATE}_dw-${scene.toLowerCase().replace(/\s+/g, '-')}-add-advanced-upload.png`)
  await page.screenshot({ path: shot })
  console.log(`screenshot ${shot}`)
}

const browser = await chromium.launch({ headless: true })
const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage()

try {
  await loginViaDwPassword(page)
  await openFormDesign(page)
  await openSceneForm(page, 'Request', 'Main (My Request)')
  await assertAddButton(page, 'My Request')

  await page.getByRole('button', { name: /Back to List|返回列表/i }).first().click({ timeout: 10000 }).catch(() => {})
  await page.locator('.form-list-sidebar').first().waitFor({ state: 'visible', timeout: 15000 }).catch(() => {})
  await openSceneForm(page, 'Task', 'Assign Task')
  await assertAddButton(page, 'Assign Task')
} finally {
  await browser.close()
}

const failed = results.filter((r) => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} passed`)
if (failed.length) process.exit(1)
