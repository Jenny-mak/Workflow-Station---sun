/**
 * My Request File details: Download and Preview must stay enabled on a readonly form.
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
const APP_ID = process.env.APP_ID ?? '34cb12ea-b0bc-11f1-b946-bab74cb05d7e'

const results = []
const rec = (n, ok, d = '') => {
  results.push({ n, ok, d })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${n}${d ? ` — ${d}` : ''}`)
}

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, acceptDownloads: true })
const page = await context.newPage()

try {
  await loginViaPortalPassword(page, { buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role' })
  await page.goto(`${ORIGIN}/portal/applications/${APP_ID}`, { waitUntil: 'domcontentloaded' })
  await page.getByTestId('upload-file-card').first().waitFor({ state: 'visible', timeout: 25000 })
  await page.getByTestId('upload-file-card').first().click({ force: true })
  const details = page.getByTestId('upload-file-details')
  rec('File details drawer opens', await details.waitFor({ state: 'visible', timeout: 8000 }).then(() => true).catch(() => false))

  const preview = details.getByTestId('upload-file-preview')
  const download = details.getByTestId('upload-file-download')
  rec('Preview is visible', await preview.isVisible().catch(() => false))
  rec('Download is visible', await download.isVisible().catch(() => false))
  rec('Preview is not disabled', !(await preview.isDisabled()))
  rec('Download is not disabled', !(await download.isDisabled()))

  const drawerShot = join(OUT, `${DATE}_portal-myrequest-file-details-actions.png`)
  await details.screenshot({ path: drawerShot })
  console.log(`screenshot ${drawerShot}`)

  const downloadPromise = page.waitForEvent('download', { timeout: 15000 }).catch(() => null)
  await download.click({ force: true })
  const file = await downloadPromise
  rec('Download starts a file', Boolean(file), file ? file.suggestedFilename() : '')

  const popupPromise = page.waitForEvent('popup', { timeout: 10000 }).catch(() => null)
  await preview.click({ force: true })
  const popup = await popupPromise
  const dialog = page.locator('[data-test="file-preview-shell"]')
  const overlayOk = await dialog.waitFor({ state: 'visible', timeout: 3000 }).then(() => true).catch(() => false)
  rec('Preview opens in-app preview', Boolean(popup) || overlayOk, popup ? 'popup' : overlayOk ? 'overlay' : '')
  if (popup) {
    await popup.waitForLoadState('domcontentloaded').catch(() => {})
    await popup.waitForTimeout(1500)
    const previewShot = join(OUT, `${DATE}_portal-myrequest-file-details-preview.png`)
    await popup.screenshot({ path: previewShot, fullPage: true })
    console.log(`screenshot ${previewShot}`)
  } else if (overlayOk) {
    const previewShot = join(OUT, `${DATE}_portal-myrequest-file-details-preview.png`)
    await dialog.screenshot({ path: previewShot })
    console.log(`screenshot ${previewShot}`)
  }
} finally {
  await browser.close()
}

const failed = results.filter((r) => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} passed`)
if (failed.length) process.exit(1)
