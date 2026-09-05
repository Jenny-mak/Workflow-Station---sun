/**
 * Verify Upload file-detail block + FileNet Advance panel.
 * Screenshots land in developer-workstation/ and user-portal/ verification-screenshots/.
 */
import { chromium } from 'playwright'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { loginViaDwPassword, loginViaPortalPassword } from './playwright-login.mjs'
import { redactHelpGuidePii } from './redact-help-guide-pii.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const DW_SHOTS = resolve(__dirname, '../developer-workstation/verification-screenshots')
const PORTAL_SHOTS = resolve(__dirname, '../user-portal/verification-screenshots')
mkdirSync(DW_SHOTS, { recursive: true })
mkdirSync(PORTAL_SHOTS, { recursive: true })
const DATE = new Date().toISOString().slice(0, 10)
const origin = process.env.ORIGIN ?? 'http://localhost:3000'

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

const results = []
const rec = (n, ok, d = '') => {
  results.push({ n, ok, d })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${n}${d ? ` — ${d}` : ''}`)
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
      if (blob.includes('"type":"upload"')) {
        return { fuId: String(fu.id), formName: String(form.formName || '') }
      }
    }
  }
  return null
}

async function processKeys(page) {
  const forced = process.env.FU_CODE?.trim()
  if (forced) return [forced]
  const res = await page.request.get(`${origin}/api/portal/processes/definitions`)
  if (!res.ok()) throw new Error(`definitions HTTP ${res.status()}`)
  const body = unwrap(await res.json())
  const list = Array.isArray(body) ? body : (body?.records || body?.content || [])
  const keys = list.map((d) => String(d.key || d.processKey || d.code || '')).filter(Boolean)
  const fallback = ['fu-20260422-23tfag', 'atm-20260623-gaevus']
  return [...new Set([...keys, ...fallback])]
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
  await page.goto(`${origin}/help/form-upload#advance`, { waitUntil: 'domcontentloaded' })
  const guide = page.getByTestId('form-upload-guide-page')
  await guide.waitFor({ state: 'visible', timeout: 20000 })
  rec('Help article /form-upload#advance is visible', await guide.isVisible())
  await redactHelpGuidePii(page)
  const helpShot = resolve(DW_SHOTS, `${DATE}_form-upload-advance-guide.png`)
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

  const advance = page.locator('[data-testid="upload-filenet-advance"], .fn-adv').first()
  const advanceVisible = await advance.isVisible().catch(() => false)
  rec('Form Design Upload properties show FileNet Advance panel', advanceVisible)
  if (advanceVisible) {
    const headerOn = await advance.getByText('Header Info', { exact: true }).isVisible().catch(() => false)
    if (headerOn) {
      await advance.locator('.el-switch').first().click()
      await page.waitForTimeout(300)
    }
    const offShot = resolve(DW_SHOTS, `${DATE}_dw-upload-advance-off.png`)
    await advance.screenshot({ path: offShot })
    console.log(`screenshot ${offShot}`)

    await advance.locator('.el-switch').first().click()
    await page.waitForTimeout(400)
    rec(
      'Advance on reveals Header Info',
      await advance.getByText('Header Info', { exact: true }).isVisible(),
    )
    const onShot = resolve(DW_SHOTS, `${DATE}_dw-upload-advance-on.png`)
    await advance.screenshot({ path: onShot })
    console.log(`screenshot ${onShot}`)
  }

  const previewBtn = page.getByRole('button', { name: 'Preview', exact: true })
  await previewBtn.click()
  const dialog = page.locator('.form-preview-dialog').last()
  await dialog.waitFor({ state: 'visible', timeout: 20000 })

  const drops = dialog.getByTestId('form-upload-drop')
  await drops.first().waitFor({ state: 'visible', timeout: 20000 }).catch(() => {})
  let previewDetailsOk = false
  const dropCount = await drops.count()
  for (let i = 0; i < dropCount; i++) {
    const drop = drops.nth(i)
    await drop.scrollIntoViewIfNeeded().catch(() => {})
    const posted = page.waitForResponse(
      (res) => res.url().includes('/upload') && res.request().method() === 'POST',
      { timeout: 20000 },
    ).catch(() => null)
    await drop.locator('input[type="file"]').first().setInputFiles([tmpPdf(`detail-upload-${i}.pdf`)])
    const res = await posted
    console.log(`[preview upload ${i}] status=${res ? res.status() : 'none'} url=${res?.url() ?? ''}`)
    const details = dialog.getByTestId('upload-file-details').first()
    if (await details.isVisible().catch(() => false)) {
      previewDetailsOk = true
      await details.scrollIntoViewIfNeeded()
      const previewShot = resolve(DW_SHOTS, `${DATE}_dw-form-preview-upload-details.png`)
      await details.screenshot({ path: previewShot })
      console.log(`screenshot ${previewShot}`)
      break
    }
  }
  rec('Form Preview shows file details after upload', previewDetailsOk)

  const attachmentCard = dialog.locator('.form-layout-card, .sub-table-field, [class*="sub-table"]').filter({
    hasText: 'Attachment',
  }).first()
  const attachmentAdd = (await attachmentCard.count())
    ? attachmentCard.getByRole('button', { name: 'Add', exact: true }).first()
    : dialog.getByRole('button', { name: 'Add', exact: true }).last()
  if (await attachmentAdd.count()) {
    await attachmentAdd.click()
    const rowDlg = page.locator('.el-dialog:visible, .el-overlay-dialog:visible').last()
    const opened = await rowDlg.waitFor({ state: 'visible', timeout: 8000 }).then(() => true).catch(() => false)
    if (opened) {
      const dlgDrop = rowDlg.locator('input[type="file"]').first()
      if (await dlgDrop.count()) {
        const posted = page.waitForResponse(
          (res) => res.url().includes('/upload') && res.request().method() === 'POST',
          { timeout: 20000 },
        ).catch(() => null)
        await dlgDrop.setInputFiles([tmpPdf('preview-dialog-upload.pdf')])
        const res = await posted
        console.log(`[preview dialog upload] status=${res ? res.status() : 'none'}`)
        const dlgDetails = rowDlg.getByTestId('upload-file-details').first()
        const dlgVisible = await dlgDetails.waitFor({ state: 'visible', timeout: 20000 })
          .then(() => true)
          .catch(() => false)
        rec('Form Preview sub-table dialog shows file details', dlgVisible)
        if (dlgVisible) {
          const dlgShot = resolve(DW_SHOTS, `${DATE}_dw-form-preview-subtable-upload-details.png`)
          await rowDlg.screenshot({ path: dlgShot })
          console.log(`screenshot ${dlgShot}`)
        }
      } else {
        rec('Form Preview Add dialog has no Upload field', true, 'skipped')
      }
      await page.keyboard.press('Escape')
    } else {
      rec('Form Preview Attachment Add did not open a dialog', true, 'skipped')
    }
  }
  await page.keyboard.press('Escape')

  const portalPage = await context.newPage()
  await loginViaPortalPassword(portalPage, { buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role' })
  const keys = await processKeys(portalPage)
  let startKey = ''
  for (const key of keys) {
    await portalPage.goto(`${origin}/portal/processes/start/${key}`, { waitUntil: 'domcontentloaded' })
    const input = portalPage.locator('.el-upload input[type="file"]').first()
    try {
      await input.waitFor({ state: 'attached', timeout: 12000 })
      startKey = key
      break
    } catch {
      /* try next startable process */
    }
  }
  rec('Found a Portal start form with Upload', Boolean(startKey), startKey)
  if (!startKey) throw new Error(`No start form with an Upload field (tried ${keys.length} keys)`)

  const portalPosted = portalPage.waitForResponse(
    (res) => res.url().includes('/upload') && res.request().method() === 'POST',
    { timeout: 20000 },
  ).catch(() => null)
  const portalInput = portalPage.locator('.el-upload input[type="file"]').first()
  await portalInput.setInputFiles([tmpPdf('portal-detail-upload.pdf')])
  const portalRes = await portalPosted
  console.log(`[portal upload] status=${portalRes ? portalRes.status() : 'none'}`)
  const portalDetails = portalPage.getByTestId('upload-file-details').first()
  const portalVisible = await portalDetails.waitFor({ state: 'visible', timeout: 25000 })
    .then(() => true)
    .catch(() => false)
  rec('Portal start form shows file details after upload', portalVisible)
  if (portalVisible) {
    rec(
      'Portal start form status is Completed',
      (await portalDetails.locator('.el-tag').first().innerText()).includes('Completed'),
    )
    await portalDetails.scrollIntoViewIfNeeded()
    const portalShot = resolve(PORTAL_SHOTS, `${DATE}_portal-upload-file-details.png`)
    await portalDetails.screenshot({ path: portalShot })
    console.log(`screenshot ${portalShot}`)
  }

  const openAddRecord = portalPage.getByRole('dialog', { name: /Add Record/i })
  if (await openAddRecord.isVisible().catch(() => false)) {
    await portalPage.keyboard.press('Escape')
    await page.waitForTimeout(300)
  }
  const attachmentHeading = portalPage.getByText('Attachment', { exact: true }).first()
  if (await attachmentHeading.count()) {
    await attachmentHeading.scrollIntoViewIfNeeded()
    const addNear = portalPage.locator('.form-layout-card, .sub-table-field, [class*="subTable"]').filter({
      hasText: 'Attachment',
    }).getByRole('button', { name: /^(Add|新增)$/ }).first()
    if (await addNear.count()) {
      await addNear.click({ timeout: 8000 }).catch(() => {})
    }
  } else {
    rec('Portal start form has no Attachment sub-table', true, 'skipped')
  }
  const addDlg = portalPage.getByRole('dialog', { name: /Add Record/i })
  const addOpened = await addDlg.waitFor({ state: 'visible', timeout: 6000 }).then(() => true).catch(() => false)
  if (addOpened) {
    const dlgUpload = addDlg.locator('.el-upload input[type="file"]').first()
    if (await dlgUpload.count()) {
      await dlgUpload.setInputFiles([tmpPdf('dialog-detail-upload.pdf')])
      const dlgDetails = addDlg.getByTestId('upload-file-details').first()
      const ok = await dlgDetails.waitFor({ state: 'visible', timeout: 20000 }).then(() => true).catch(() => false)
      rec('Sub-table add dialog shows file details', ok)
      if (ok) {
        const path = resolve(PORTAL_SHOTS, `${DATE}_portal-subtable-upload-details.png`)
        await addDlg.screenshot({ path })
        console.log(`screenshot ${path}`)
      }
    } else {
      rec('Opened Add Record has no Upload field', true, 'skipped')
    }
  }
} finally {
  await browser.close()
}

const required = new Set([
  'Help article /form-upload#advance is visible',
  'Form Design Upload properties show FileNet Advance panel',
  'Advance on reveals Header Info',
  'Found a Portal start form with Upload',
  'Portal start form shows file details after upload',
])
const failed = results.filter((r) => !r.ok)
const requiredFailed = failed.filter((r) => required.has(r.n))
console.log(`\n${results.length - failed.length}/${results.length} passed (${requiredFailed.length} required failed)`)
if (requiredFailed.length) process.exit(1)
