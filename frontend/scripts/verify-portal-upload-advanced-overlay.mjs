/**
 * Portal must not invent Advanced Upload widgets that are missing from this
 * scene’s canvas. Extra fields that exist only on New Request stay off
 * My Request / To Do until the designer places the same field on that form.
 *
 * Positive control: table-bound Meeting Doc (already on those canvases) still renders.
 * Screenshots: frontend/user-portal/verification-screenshots/
 */
import { mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { loginViaPortalPassword } from './playwright-login.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const OUT = resolve(__dirname, '../user-portal/verification-screenshots')
mkdirSync(OUT, { recursive: true })
const DATE = new Date().toISOString().slice(0, 10)
const ORIGIN = process.env.ORIGIN ?? 'http://localhost:3000'
const APP_ID = process.env.APP_ID ?? 'a217f0ad-aa8b-11f1-a63f-220648137284'
const TASK_ID = process.env.TASK_ID ?? 'a23c690a-aa8b-11f1-a63f-220648137284'

const results = []
const rec = (n, ok, d = '') => {
  results.push({ n, ok, d })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${n}${d ? ` — ${d}` : ''}`)
}

async function openGeneral(page) {
  const generalTab = page.getByRole('tab', { name: /General|概览|一般/i }).first()
  if (await generalTab.count()) await generalTab.click().catch(() => {})
  await page.waitForTimeout(1000)
}

async function textVisible(page, label, timeout = 4000) {
  const loc = page.getByText(label, { exact: false }).first()
  return loc.waitFor({ state: 'visible', timeout }).then(() => true).catch(() => false)
}

async function assertCanvasOnly(page, scene) {
  await page.getByTestId('form-upload-drop').first().waitFor({ state: 'visible', timeout: 20000 }).catch(() => {})
  rec(`${scene} still shows canvas upload (Meeting Doc)`, await textVisible(page, 'Meeting Doc', 8000))
  rec(`${scene} does not invent Advanced Upload2`, !(await textVisible(page, 'Advanced Upload2')))
  rec(`${scene} does not invent Upload1`, !(await textVisible(page, 'Upload1')))
  const shot = join(OUT, `${DATE}_portal-${scene.toLowerCase().replace(/\s+/g, '-')}-no-invented-advanced-upload.png`)
  await page.screenshot({ path: shot })
  console.log(`screenshot ${shot}`)
}

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()

try {
  await loginViaPortalPassword(page, { buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role' })

  await page.goto(`${ORIGIN}/portal/applications/${APP_ID}`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(5000)
  await openGeneral(page)
  await assertCanvasOnly(page, 'My Request')

  await context.clearCookies()
  await page.evaluate(() => {
    localStorage.clear()
    sessionStorage.clear()
  }).catch(() => {})
  await loginViaPortalPassword(page, {
    user: '123456',
    pass: 'password',
    buCode: 'hase-hmdc',
    roleCode: 'HMDC_Assign_Role',
  })
  await page.goto(`${ORIGIN}/portal/tasks/${TASK_ID}`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(5000)
  await openGeneral(page)
  await assertCanvasOnly(page, 'To Do')
} finally {
  await browser.close()
}

const failed = results.filter((r) => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} passed`)
if (failed.length) process.exit(1)
