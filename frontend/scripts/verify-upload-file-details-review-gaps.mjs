/**
 * Fill the File-details review gaps:
 * 1) DW Form Preview drawer (Preview + Download + Save, FileNet hidden)
 * 2) Can not download hides Download in the same drawer
 *
 * From frontend/: node scripts/verify-upload-file-details-review-gaps.mjs
 *
 * Prefers the editable New Request form (PROCESS + scene !== REQUEST). The previous
 * run picked PROCESS/REQUEST (My Request), which is read-only so upload never POSTs.
 */
import { chromium } from 'playwright'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { loginViaDwPassword, loginViaPortalPassword } from './playwright-login.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const DW_SHOTS = resolve(__dirname, '../developer-workstation/verification-screenshots')
const PORTAL_SHOTS = resolve(__dirname, '../user-portal/verification-screenshots')
mkdirSync(DW_SHOTS, { recursive: true })
mkdirSync(PORTAL_SHOTS, { recursive: true })
const DATE = new Date().toISOString().slice(0, 10)
const origin = process.env.ORIGIN ?? 'http://localhost:3000'
const PREFERRED_FU_CODES = (process.env.FU_CODE ?? 'fu-20260422-23tfag')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean)

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

function blobHasCannotDownload(blob) {
  return /can(?:not|Not)Download"\s*:\s*true/.test(blob)
}

function blobHasAdvancedUpload(blob) {
  return blob.includes('"type":"advancedUpload"') || blob.includes('"type":"formUploadDrop"')
}

function isEditableScene(form) {
  return String(form.scene || 'TASK') !== 'REQUEST'
}

const results = []
const rec = (n, ok, d = '') => {
  results.push({ n, ok, d })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${n}${d ? ` — ${d}` : ''}`)
}

async function listForms(page) {
  const res = await page.request.get(`${origin}/api/v1/function-units?page=0&size=200`)
  const body = unwrap(await res.json())
  const records = body?.records || body?.content || (Array.isArray(body) ? body : [])
  const out = []
  for (const fu of records) {
    const fr = await page.request.get(`${origin}/api/v1/function-units/${fu.id}/forms`)
    const forms = unwrap(await fr.json())
    const list = Array.isArray(forms) ? forms : []
    for (const form of list) {
      const blob = JSON.stringify(form.configJson ?? form.data ?? form)
      out.push({
        fuId: String(fu.id),
        fuCode: String(fu.code || fu.functionUnitCode || ''),
        formName: String(form.formName || ''),
        formType: String(form.formType || ''),
        scene: String(form.scene || 'TASK'),
        blob,
        hasUpload: blobHasAdvancedUpload(blob) || blob.includes('"type":"upload"'),
        hasAdvanced: blobHasAdvancedUpload(blob),
        cannotDownload: blobHasCannotDownload(blob),
      })
    }
  }
  return out
}

function pickNewRequestForm(forms) {
  const editableAdvanced = forms.filter((f) => f.hasAdvanced && isEditableScene(f))
  const preferred = editableAdvanced.find((f) => PREFERRED_FU_CODES.includes(f.fuCode) && f.formType === 'PROCESS')
    || editableAdvanced.find((f) => PREFERRED_FU_CODES.includes(f.fuCode))
  return preferred
    || editableAdvanced.find((f) => f.formType === 'PROCESS')
    || editableAdvanced.find((f) => f.formType === 'TASK')
    || editableAdvanced[0]
    || forms.find((f) => f.hasUpload && isEditableScene(f))
}

function pickCannotDownloadForm(forms, exclude) {
  const ranked = forms.filter((f) => f.cannotDownload && f.hasUpload)
  const notSame = ranked.filter((f) => !(exclude && f.fuId === exclude.fuId && f.formName === exclude.formName))
  return notSame.find((f) => isEditableScene(f))
    || ranked.find((f) => isEditableScene(f))
    || notSame[0]
    || ranked[0]
}

async function openDesignerForm(page, fuId, formName, scene) {
  await page.goto(`${origin}/dev/function-units/${fuId}`, { waitUntil: 'domcontentloaded' })
  await page.locator('.el-tabs').first().waitFor({ state: 'visible', timeout: 25000 })
  await page.getByRole('tab', { name: 'Form Design', exact: true }).click()
  await page.locator('.form-designer').first().waitFor({ state: 'visible', timeout: 25000 })
  const sceneTabName = scene === 'REQUEST' ? /^Request/ : /^Task/
  const sceneTab = page.locator('.form-scene-tabs .el-tabs__item').filter({ hasText: sceneTabName }).first()
  if (await sceneTab.count()) await sceneTab.click()
  await page.waitForTimeout(500)
  const sidebar = page.locator('.form-list-sidebar')
  const named = sidebar.getByText(formName, { exact: true }).first()
  if (await named.count()) await named.click({ timeout: 15000 })
  else await page.getByText(formName, { exact: true }).first().click({ timeout: 15000 })
  await page.locator('fc-designer, .fc-designer-wrapper').first().waitFor({ state: 'visible', timeout: 25000 })
  await page.waitForTimeout(1200)
}

async function openPreviewDialog(page) {
  await page.getByRole('button', { name: 'Preview', exact: true }).click()
  const dialog = page.locator('.form-preview-dialog').last()
  await dialog.waitFor({ state: 'visible', timeout: 20000 })
  await dialog.locator('.el-loading-mask').waitFor({ state: 'hidden', timeout: 25000 }).catch(() => {})
  await page.waitForTimeout(800)
  return dialog
}

function asPage(host) {
  return typeof host.page === 'function' ? host.page() : host
}

async function dumpDropState(host, label) {
  const drops = host.getByTestId('form-upload-drop')
  const n = await drops.count()
  console.log(`[${label}] drops=${n}`)
  for (let i = 0; i < n; i++) {
    const drop = drops.nth(i)
    const cls = await drop.getAttribute('class').catch(() => '')
    const inputs = await drop.locator('input[type="file"]').count()
    const disabled = await drop.evaluate((el) => el.classList.contains('is-disabled')).catch(() => false)
    console.log(`[${label}] drop[${i}] class=${cls} disabled=${disabled} inputs=${inputs}`)
  }
}

async function uploadOnHost(host, fileName) {
  const page = asPage(host)
  await dumpDropState(host, fileName)
  const drops = host.getByTestId('form-upload-drop')
  const dropCount = await drops.count()
  for (let i = 0; i < dropCount; i++) {
    const drop = drops.nth(i)
    if (!(await drop.isVisible().catch(() => false))) continue
    if (await drop.evaluate((el) => el.classList.contains('is-disabled')).catch(() => false)) {
      console.log(`[upload ${fileName}] skip disabled drop ${i}`)
      continue
    }
    const input = drop.locator('input[type="file"]').first()
    if (!(await input.count())) continue
    const posted = page.waitForResponse(
      (res) => res.url().includes('/upload') && res.request().method() === 'POST',
      { timeout: 20000 },
    ).catch(() => null)
    await drop.scrollIntoViewIfNeeded().catch(() => {})
    await input.setInputFiles([tmpPdf(`${i}-${fileName}`)])
    const res = await posted
    console.log(`[upload ${fileName} drop ${i}] status=${res ? res.status() : 'none'} url=${res?.url() ?? ''}`)
    const card = drop.getByTestId('upload-file-card').first()
    if (await card.waitFor({ state: 'visible', timeout: 12000 }).then(() => true).catch(() => false)) {
      await card.click()
      const details = page.getByTestId('upload-file-details').first()
      const ok = await details.waitFor({ state: 'visible', timeout: 12000 }).then(() => true).catch(() => false)
      if (ok) return details
    }
  }
  if (dropCount > 0) return null
  const basic = host.locator('.el-upload input[type="file"]')
  const basicCount = await basic.count()
  console.log(`[upload ${fileName}] basic inputs=${basicCount}`)
  for (let i = 0; i < basicCount; i++) {
    const input = basic.nth(i)
    const posted = page.waitForResponse(
      (res) => res.url().includes('/upload') && res.request().method() === 'POST',
      { timeout: 20000 },
    ).catch(() => null)
    await input.setInputFiles([tmpPdf(`basic-${i}-${fileName}`)])
    const res = await posted
    console.log(`[upload ${fileName} basic ${i}] status=${res ? res.status() : 'none'}`)
    const card = host.getByTestId('upload-file-card').first()
    if (await card.waitFor({ state: 'visible', timeout: 12000 }).then(() => true).catch(() => false)) {
      await card.click()
      const details = page.getByTestId('upload-file-details').first()
      const ok = await details.waitFor({ state: 'visible', timeout: 12000 }).then(() => true).catch(() => false)
      if (ok) return details
    }
  }
  return null
}

async function assertDetailsChrome(details, { expectDownload }) {
  const [preview, downloadCount, save, cb, filenet] = await Promise.all([
    details.getByTestId('upload-file-preview').isVisible(),
    details.getByTestId('upload-file-download').count(),
    details.getByTestId('upload-file-save').isVisible(),
    details.getByText('Callback URL', { exact: true }).count(),
    details.getByText('Auto Send to FileNet', { exact: true }).count(),
  ])
  return {
    preview,
    download: downloadCount > 0,
    save,
    fileNetHidden: cb === 0 && filenet === 0,
    expectDownload,
  }
}

async function captureCannotDownloadOnPortal(context) {
  const portalPage = await context.newPage()
  portalPage.on('request', (req) => {
    if (req.method() === 'POST' && req.url().includes('/upload')) {
      console.log(`[portal req] ${req.method()} ${req.url()}`)
    }
  })
  await loginViaPortalPassword(portalPage, { buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role' })
  const defs = await portalPage.request.get(`${origin}/api/portal/processes/definitions`)
  const defBody = unwrap(await defs.json())
  const list = Array.isArray(defBody) ? defBody : (defBody?.records || defBody?.content || [])
  const keys = [...new Set([
    ...PREFERRED_FU_CODES,
    ...list.map((d) => String(d.key || d.processKey || d.code || '')).filter(Boolean),
  ])]
  for (const key of keys) {
    await portalPage.goto(`${origin}/portal/processes/start/${key}`, { waitUntil: 'domcontentloaded' })
    const hasUpload = await portalPage.locator('.el-upload input[type="file"], [data-testid="form-upload-drop"]')
      .first()
      .waitFor({ state: 'visible', timeout: 8000 })
      .then(() => true)
      .catch(() => false)
    if (!hasUpload) continue
    const drops = portalPage.getByTestId('form-upload-drop')
    const dropCount = await drops.count()
    const attempts = Math.max(dropCount, 1)
    for (let i = 0; i < attempts; i++) {
      const host = dropCount ? drops.nth(i) : portalPage
      const details = dropCount
        ? await uploadOnHost(portalPage, `portal-cd-${key}-${i}.pdf`)
        : await uploadOnHost(portalPage, `portal-cd-${key}.pdf`)
      if (!details) continue
      const downloadCount = await details.getByTestId('upload-file-download').count()
      const previewVisible = await details.getByTestId('upload-file-preview').isVisible()
      if (downloadCount === 0 && previewVisible) {
        const shot = resolve(PORTAL_SHOTS, `${DATE}_portal-upload-cannot-download-details.png`)
        await details.screenshot({ path: shot })
        console.log(`screenshot ${shot}`)
        rec('Portal File details hides Download when Can not download is on', true, key)
        rec('Can not download still shows Preview', true, key)
        return true
      }
      await portalPage.keyboard.press('Escape')
      if (dropCount) break
    }
  }
  rec('Portal File details hides Download when Can not download is on', false, 'no start form with the flag')
  return false
}

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()
page.on('request', (req) => {
  if (req.method() === 'POST' && /upload/i.test(req.url())) {
    console.log(`[dw req] ${req.method()} ${req.url()}`)
  }
})
page.on('pageerror', (err) => console.log(`[dw pageerror] ${err.message}\n${err.stack || ''}`))
page.on('console', (msg) => {
  if (msg.type() === 'error') console.log(`[dw console.${msg.type()}] ${msg.text()}`)
})

try {
  await loginViaDwPassword(page)
  const forms = await listForms(page)
  const processForm = pickNewRequestForm(forms)
  rec(
    'Found a DW New Request form with Upload for Form Preview',
    Boolean(processForm),
    processForm ? `${processForm.fuId} ${processForm.fuCode} ${processForm.formType}/${processForm.scene} ${processForm.formName}` : '',
  )
  if (!processForm) throw new Error('No DW editable form with an Upload field')

  await openDesignerForm(page, processForm.fuId, processForm.formName, processForm.scene)
  const dialog = await openPreviewDialog(page)
  const dwDetails = await uploadOnHost(dialog, 'dw-preview-details.pdf')
  rec('DW Form Preview shows File details after upload', Boolean(dwDetails))
  if (dwDetails) {
    const chrome = await assertDetailsChrome(dwDetails, { expectDownload: !processForm.cannotDownload })
    rec('DW Form Preview shows Preview', chrome.preview)
    rec('DW Form Preview shows Save', chrome.save)
    rec('DW Form Preview hides FileNet fields', chrome.fileNetHidden)
    if (!processForm.cannotDownload) {
      rec('DW Form Preview shows Download when Can not download is off', chrome.download)
    }
    const shot = resolve(DW_SHOTS, `${DATE}_dw-form-preview-upload-details.png`)
    await dwDetails.screenshot({ path: shot })
    console.log(`screenshot ${shot}`)
  } else {
    const failShot = resolve(DW_SHOTS, `${DATE}_dw-form-preview-upload-details-FAIL.png`)
    await dialog.screenshot({ path: failShot }).catch(() => {})
    console.log(`screenshot ${failShot}`)
  }
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)

  const blocked = pickCannotDownloadForm(forms, processForm)
  rec(
    'Found a form with Can not download',
    Boolean(blocked),
    blocked ? `${blocked.fuId} ${blocked.formType}/${blocked.scene} ${blocked.formName} editable=${isEditableScene(blocked)}` : 'none in DW forms',
  )

  let capturedBlocked = false
  if (blocked && isEditableScene(blocked)) {
    await openDesignerForm(page, blocked.fuId, blocked.formName, blocked.scene)
    const blockedDialog = await openPreviewDialog(page)
    const blockedDetails = await uploadOnHost(blockedDialog, 'dw-cannot-download.pdf')
    rec('DW Form Preview opens File details when Can not download is on', Boolean(blockedDetails))
    if (blockedDetails) {
      const chrome = await assertDetailsChrome(blockedDetails, { expectDownload: false })
      rec('Can not download hides Download in File details', chrome.preview && !chrome.download)
      rec('Can not download still shows Preview', chrome.preview)
      const shot = resolve(DW_SHOTS, `${DATE}_dw-form-preview-cannot-download-details.png`)
      await blockedDetails.screenshot({ path: shot })
      console.log(`screenshot ${shot}`)
      capturedBlocked = chrome.preview && !chrome.download
    } else {
      const failShot = resolve(DW_SHOTS, `${DATE}_dw-form-preview-cannot-download-FAIL.png`)
      await blockedDialog.screenshot({ path: failShot }).catch(() => {})
      console.log(`screenshot ${failShot}`)
    }
  }

  if (!capturedBlocked) {
    await captureCannotDownloadOnPortal(context)
  }
} finally {
  await browser.close()
}

const required = [
  'DW Form Preview shows File details after upload',
  'DW Form Preview shows Preview',
  'DW Form Preview shows Save',
  'DW Form Preview hides FileNet fields',
]
const failed = results.filter((r) => !r.ok)
const requiredFailed = failed.filter((r) => required.includes(r.n))
const blockedOk = results.some((r) => r.ok && (
  r.n === 'Can not download hides Download in File details'
  || r.n === 'Portal File details hides Download when Can not download is on'
))
if (!blockedOk) requiredFailed.push({ n: 'Can not download hides Download', ok: false })
console.log(`\n${results.length - failed.length}/${results.length} passed`)
if (requiredFailed.length || !blockedOk) process.exit(1)
