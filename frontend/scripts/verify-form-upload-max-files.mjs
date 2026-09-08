/**
 * Verify Form Design Upload Max files editor + /help/form-upload.
 * Screenshots land in developer-workstation/verification-screenshots/.
 */
import { chromium } from 'playwright'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { loginViaDwPassword } from './playwright-login.mjs'
import { redactHelpGuidePii } from './redact-help-guide-pii.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const DW_SHOTS = resolve(__dirname, '../developer-workstation/verification-screenshots')
mkdirSync(DW_SHOTS, { recursive: true })
const DATE = new Date().toISOString().slice(0, 10)
const origin = 'http://localhost:3000'

function unwrap(json) {
  return json && typeof json === 'object' && 'data' in json ? json.data : json
}

async function findUploadForm(page) {
  const forced = process.env.HELP_GUIDE_FU_ID?.trim()
  const res = await page.request.get(`${origin}/api/v1/function-units?page=0&size=200`)
  const body = unwrap(await res.json())
  const records = body?.records || body?.content || (Array.isArray(body) ? body : [])
  for (const fu of records) {
    if (forced && String(fu.id) !== forced) continue
    const fr = await page.request.get(`${origin}/api/v1/function-units/${fu.id}/forms`)
    const forms = unwrap(await fr.json())
    const list = Array.isArray(forms) ? forms : []
    for (const form of list) {
      const blob = JSON.stringify(form.configJson ?? form.data ?? form)
      if (blob.includes('"type":"advancedUpload"') || blob.includes('"type":"upload"')) {
        return { fuId: String(fu.id), formName: String(form.formName || '') }
      }
    }
  }
  return null
}

function tmpPdf(name) {
  const dir = join(tmpdir(), 'ws-form-upload-verify')
  mkdirSync(dir, { recursive: true })
  const path = join(dir, name)
  writeFileSync(path, '%PDF-1.1\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n')
  return path
}

const results = []
const rec = (n, ok, d = '') => {
  results.push({ n, ok, d })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${n}${d ? ` — ${d}` : ''}`)
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
  await page.goto(`${origin}/help/form-upload#max-files`, { waitUntil: 'domcontentloaded' })
  const guide = page.getByTestId('form-upload-guide-page')
  await guide.waitFor({ state: 'visible', timeout: 20000 })
  rec('Help article /form-upload is visible', await guide.isVisible())
  const helpShot = resolve(DW_SHOTS, `${DATE}_form-upload-guide.png`)
  await page.screenshot({ path: helpShot, fullPage: true })
  console.log(`screenshot ${helpShot}`)

  await loginViaDwPassword(page)
  const found = await findUploadForm(page)
  rec(
    'Found a Function Unit whose form has an Upload field',
    Boolean(found?.fuId && found.formName),
    found ? `${found.fuId} / ${found.formName}` : '',
  )
  if (!found?.fuId || !found.formName) throw new Error('No Function Unit with an Upload field')

  await page.goto(`${origin}/dev/function-units/${found.fuId}`, { waitUntil: 'domcontentloaded' })
  await page.locator('.el-tabs').first().waitFor({ state: 'visible', timeout: 25000 })
  await page.getByRole('tab', { name: 'Form Design', exact: true }).click()
  await page.locator('.form-designer').first().waitFor({ state: 'visible', timeout: 25000 })
  await page.getByText(found.formName, { exact: true }).first().click()
  await page.locator('fc-designer, .fc-designer-wrapper').first().waitFor({ state: 'visible', timeout: 25000 })
  await page.waitForTimeout(1500)

  const uploadItem = page.locator('.fc-form-item, ._fd-drag-item, .el-form-item').filter({
    has: page.locator('.el-upload'),
  }).first()
  if (await uploadItem.count()) {
    await uploadItem.click({ timeout: 8000 })
  } else {
    await page.locator('.el-upload').first().click({ timeout: 8000 })
  }
  await page.waitForTimeout(800)

  const helpLink = page.getByTestId('upload-max-files-guide-link')
  const linkVisible = await helpLink.isVisible().catch(() => false)
  rec('Form Design Upload properties show Max files help ?', linkVisible)
  rec(
    'Native Maximum number of uploads allowed is hidden',
    (await page.getByText('Maximum number of uploads allowed', { exact: true }).count()) === 0,
  )

  if (linkVisible) {
    await redactHelpGuidePii(page)
    const propsShot = resolve(DW_SHOTS, `${DATE}_dw-form-upload-max-files.png`)
    const propsPanel = page.locator('.upload-max-files-editor').first()
    if (await propsPanel.count()) {
      await propsPanel.screenshot({ path: propsShot })
    } else {
      await page.locator('.designer-workspace').screenshot({ path: propsShot })
    }
    console.log(`screenshot ${propsShot}`)

    const wideShot = resolve(DW_SHOTS, `${DATE}_dw-form-upload-props-no-native-limit.png`)
    const right = page.locator('._fd-right, ._fd-config, .fc-style').filter({
      has: page.getByTestId('upload-max-files-guide-link'),
    }).first()
    if (await right.count()) await right.screenshot({ path: wideShot })
    else await page.screenshot({ path: wideShot })
    console.log(`screenshot ${wideShot}`)

    const popupPromise = context.waitForEvent('page')
    await helpLink.click()
    const popup = await popupPromise
    await popup.waitForURL(/\/help\/form-upload/, { timeout: 15000 })
    rec('Help ? opens /help/form-upload', popup.url().includes('/help/form-upload'), popup.url())
    await popup.close()
  }

  const previewBtn = page.getByRole('button', { name: 'Preview', exact: true })
  await previewBtn.click()
  const dialog = page.locator('.form-preview-dialog').last()
  await dialog.waitFor({ state: 'visible', timeout: 20000 })
  const drop = dialog.getByTestId('form-upload-drop').first()
  await drop.waitFor({ state: 'visible', timeout: 20000 })
  rec('Form Preview uses the shared upload drop zone', await drop.isVisible())
  const previewInput = drop.locator('input[type="file"]').first()
  const isMultiple = (await previewInput.getAttribute('multiple')) !== null
  if (isMultiple) {
    rec('Form Preview file input is multiple', true)
    await previewInput.setInputFiles([
      tmpPdf('alpha-upload.pdf'),
      tmpPdf('beta-upload.pdf'),
      tmpPdf('gamma-upload.pdf'),
    ])
    await page.waitForFunction(() => {
      const cards = [...document.querySelectorAll('.form-preview-dialog [data-testid="upload-file-card"]')]
      return cards.filter((el) => /alpha-upload|beta-upload|gamma-upload/.test(el.textContent || '')).length >= 3
    }, null, { timeout: 25000 })
    rec('Form Preview keeps three files after one picker', true)
  } else {
    rec('Form Preview drop zone present for a single-file field', true)
  }

  const previewShot = resolve(DW_SHOTS, `${DATE}_dw-form-preview-upload-drop.png`)
  await drop.screenshot({ path: previewShot })
  console.log(`screenshot ${previewShot}`)
} finally {
  await browser.close()
}

const failed = results.filter((r) => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} passed`)
if (failed.length) process.exit(1)
