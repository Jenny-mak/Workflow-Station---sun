/**
 * My Request: Meeting Doc cards, count, drawer, in-app preview.
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

const results = []
const rec = (n, ok, d = '') => {
  results.push({ n, ok, d })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${n}${d ? ` — ${d}` : ''}`)
}

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()

try {
  await loginViaPortalPassword(page, { buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role' })
  await page.goto(`${ORIGIN}/portal/applications/${APP_ID}`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(5000)
  const generalTab = page.getByRole('tab', { name: /General|概览|一般/i }).first()
  if (await generalTab.count()) await generalTab.click().catch(() => {})
  await page.waitForTimeout(1000)

  const drops = page.getByTestId('form-upload-drop')
  await drops.first().waitFor({ state: 'visible', timeout: 20000 }).catch(() => {})
  const dropCount = await drops.count()
  rec('My Request shows at least one Advanced Upload drop zone', dropCount >= 1, `drops=${dropCount}`)

  const cards = page.getByTestId('upload-file-card')
  const cardCount = await cards.count()
  rec('Meeting Doc shows two stored files as cards', cardCount >= 2, `cards=${cardCount}`)

  if (dropCount > 0) {
    await drops.first().scrollIntoViewIfNeeded()
    const dropShot = join(OUT, `${DATE}_portal-myrequest-upload-cards.png`)
    await drops.first().screenshot({ path: dropShot })
    console.log(`screenshot ${dropShot}`)
  } else {
    const overview = join(OUT, `${DATE}_portal-myrequest-upload-cards.png`)
    await page.screenshot({ path: overview, fullPage: true })
    console.log(`screenshot ${overview}`)
  }

  if (cardCount > 0) {
    await cards.first().scrollIntoViewIfNeeded()
    console.log(`[card] ${JSON.stringify(await cards.first().evaluate((el) => ({
      text: el.textContent,
      disabled: el.hasAttribute('disabled'),
    })))}`)
    const popupPromise = page.waitForEvent('popup', { timeout: 8000 }).catch(() => null)
    await cards.first().click({ force: true })
    const details = page.getByTestId('upload-file-details')
    const drawerOk = await details.waitFor({ state: 'visible', timeout: 8000 }).then(() => true).catch(() => false)
    rec('Clicking a file card opens the details drawer', drawerOk)
    if (drawerOk) {
      const drawerShot = join(OUT, `${DATE}_portal-myrequest-upload-drawer.png`)
      await details.screenshot({ path: drawerShot })
      console.log(`screenshot ${drawerShot}`)
      const link = details.locator('.upload-file-details__link').first()
      if (await link.count()) {
        await link.click()
        const popup = await popupPromise
        const dialog = page.locator('[data-test="file-preview-shell"]')
        const previewOk = Boolean(popup) || await dialog.waitFor({ state: 'visible', timeout: 8000 }).then(() => true).catch(() => false)
        rec('Callback URL opens in-app preview', previewOk, popup ? 'popup' : 'dialog')
        if (popup) {
          await popup.waitForTimeout(1500)
          const previewShot = join(OUT, `${DATE}_portal-myrequest-upload-preview.png`)
          await popup.screenshot({ path: previewShot, fullPage: true })
          console.log(`screenshot ${previewShot}`)
        } else if (await dialog.count()) {
          const previewShot = join(OUT, `${DATE}_portal-myrequest-upload-preview.png`)
          await dialog.screenshot({ path: previewShot })
          console.log(`screenshot ${previewShot}`)
        }
      } else {
        rec('Callback URL opens in-app preview', false, 'no callback link')
      }
    }
  }
} finally {
  await browser.close()
}

const failed = results.filter((r) => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} passed`)
if (failed.length) process.exit(1)
