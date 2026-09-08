/**
 * Upload card status badge, duplicate rejection, and details drawer stacking.
 * Screenshots: frontend/user-portal/verification-screenshots/
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { loginViaPortalPassword } from './playwright-login.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const OUT = resolve(__dirname, '../user-portal/verification-screenshots')
mkdirSync(OUT, { recursive: true })
const DATE = new Date().toISOString().slice(0, 10)
const ORIGIN = process.env.ORIGIN ?? 'http://localhost:3000'

function unwrap(json) {
  return json && typeof json === 'object' && 'data' in json ? json.data : json
}

function tmpPdf(name) {
  const dir = join(tmpdir(), 'ws-form-upload-verify')
  mkdirSync(dir, { recursive: true })
  const path = join(dir, name)
  writeFileSync(path, '%PDF-1.1\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n')
  return path
}

async function processKeys(page) {
  const forced = process.env.FU_CODE?.trim()
  if (forced) return [forced]
  const res = await page.request.get(`${ORIGIN}/api/portal/processes/definitions`)
  if (!res.ok()) throw new Error(`definitions HTTP ${res.status()}`)
  const body = unwrap(await res.json())
  const list = Array.isArray(body) ? body : (body?.records || body?.content || [])
  const keys = list.map((d) => String(d.key || d.processKey || d.code || '')).filter(Boolean)
  const fallback = ['fu-20260422-23tfag', 'atm-20260623-gaevus']
  return [...new Set([...keys, ...fallback])]
}

const results = []
const rec = (n, ok, d = '') => {
  results.push({ n, ok, d })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${n}${d ? ` — ${d}` : ''}`)
}

const browser = await chromium.launch({ headless: true })
const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage()

try {
  await loginViaPortalPassword(page, { buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role' })
  const keys = await processKeys(page)
  let found = ''
  for (const key of keys) {
    await page.goto(`${ORIGIN}/portal/processes/start/${key}`, { waitUntil: 'domcontentloaded' })
    const drop = page.getByTestId('form-upload-drop').first()
    try {
      await drop.waitFor({ state: 'visible', timeout: 12000 })
      found = key
      break
    } catch {
      /* try next */
    }
  }
  if (!found) throw new Error(`No start form with an Upload drop zone (tried ${keys.length} keys)`)

  const drop = page.getByTestId('form-upload-drop').first()
  const input = drop.locator('input[type="file"]').first()
  const filePath = tmpPdf('status-badge.pdf')
  const posted = page.waitForResponse(
    (res) => res.url().includes('/upload') && res.request().method() === 'POST',
    { timeout: 20000 },
  ).catch(() => null)
  await input.setInputFiles([filePath])
  await posted

  const card = drop.getByTestId('upload-file-card').filter({ hasText: 'status-badge.pdf' }).first()
  await card.waitFor({ state: 'visible', timeout: 25000 })
  const status = card.getByTestId('upload-file-status')
  const successClass = await status.getAttribute('class')
  rec('Success card shows a green status badge', Boolean(successClass?.includes('is-success')))

  const badgeShot = join(OUT, `${DATE}_portal-upload-card-status-success.png`)
  await drop.screenshot({ path: badgeShot })
  console.log(`screenshot ${badgeShot}`)

  const beforeCount = await drop.getByTestId('upload-file-card').count()
  await input.setInputFiles([])
  const toastWait = page.locator('.el-message').filter({ hasText: /already uploaded|已存在/ }).first()
    .waitFor({ state: 'visible', timeout: 5000 })
    .then(() => true)
    .catch(() => false)
  const dupPosted = page.waitForResponse(
    (res) => res.url().includes('/upload') && res.request().method() === 'POST',
    { timeout: 4000 },
  ).catch(() => null)
  await input.setInputFiles([filePath])
  const dupRes = await dupPosted
  rec('Duplicate file does not POST /upload', dupRes == null)
  const toastVisible = await toastWait
  const afterCount = await drop.getByTestId('upload-file-card').count()
  rec('Duplicate file does not add another card', afterCount === beforeCount, `before=${beforeCount} after=${afterCount}`)
  rec('Duplicate toast is shown', toastVisible)

  const dupShot = join(OUT, `${DATE}_portal-upload-duplicate-toast.png`)
  await page.screenshot({ path: dupShot, fullPage: false })
  console.log(`screenshot ${dupShot}`)

  await card.click()
  const drawer = page.getByTestId('upload-file-details-drawer')
  const details = page.getByTestId('upload-file-details').first()
  const detailsVisible = await details.waitFor({ state: 'visible', timeout: 15000 })
    .then(() => true)
    .catch(() => false)
  rec('Clicking a success card opens the details drawer', detailsVisible)
  if (detailsVisible) {
    const box = await details.boundingBox()
    rec('Details panel is on-screen (not under the dialog overlay)', Boolean(box && box.width > 0 && box.height > 0))
    const detailsShot = join(OUT, `${DATE}_portal-upload-details-drawer.png`)
    await drawer.screenshot({ path: detailsShot }).catch(async () => {
      await page.screenshot({ path: detailsShot, fullPage: false })
    })
    console.log(`screenshot ${detailsShot}`)
  }

  const failed = results.filter((r) => !r.ok)
  if (failed.length) {
    throw new Error(failed.map((r) => r.n).join('; '))
  }
} finally {
  await browser.close()
}
