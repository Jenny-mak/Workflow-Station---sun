/**
 * New Request → To Do / My Request / Completed: independent Advanced Upload
 * files must stay on the drop-zone widget (not an el-input) on the main form
 * and on the matching attachment-table column.
 *
 * Designer copy runs first so Assign Task + Main (My Request) share New Request
 * field keys. Then a new portal request is started (never the old diverged instance).
 *
 * From frontend/: node scripts/verify-portal-advanced-upload-new-request-flow.mjs
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { loginViaDwPassword, loginViaPortalPassword } from './playwright-login.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const DW_SHOTS = resolve(__dirname, '../developer-workstation/verification-screenshots')
const UP_SHOTS = resolve(__dirname, '../user-portal/verification-screenshots')
mkdirSync(DW_SHOTS, { recursive: true })
mkdirSync(UP_SHOTS, { recursive: true })

const DATE = new Date().toISOString().slice(0, 10)
const ORIGIN = process.env.ORIGIN ?? 'http://localhost:3000'
const FU_ID = process.env.FU_ID ?? '50008'
const PROCESS_KEY = process.env.PROCESS_KEY ?? 'multi-instance-subtask-demo-clone-20260826-ztxbzz'
const FILE_TAG = `adv-e2e-${Date.now()}`
const MAIN_FILE = `${FILE_TAG}-main.pdf`
const ATTACH_FILE = `${FILE_TAG}-attach.pdf`

const results = []
function rec(n, ok, d = '') {
  results.push({ n, ok, d })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${n}${d ? ` — ${d}` : ''}`)
}

function tmpPdf(name) {
  const dir = join(tmpdir(), 'ws-adv-upload-e2e')
  mkdirSync(dir, { recursive: true })
  const path = join(dir, name)
  writeFileSync(path, '%PDF-1.1\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n')
  return path
}

function unwrap(json) {
  if (!json || typeof json !== 'object') return json
  if ('data' in json && json.data != null) return json.data
  return json
}

async function shot(page, dir, slug, fullPage = true) {
  const path = join(dir, `${DATE}_${slug}.png`)
  await page.screenshot({ path, fullPage })
  console.log(`screenshot ${path}`)
  return path
}

async function openFormDesign(page) {
  await page.goto(`${ORIGIN}/dev/function-units/${FU_ID}`, { waitUntil: 'domcontentloaded' })
  await page.locator('.el-tabs').first().waitFor({ state: 'visible', timeout: 30000 })
  await page.getByRole('tab', { name: 'Form Design', exact: true }).click()
  await page.locator('.form-designer').first().waitFor({ state: 'visible', timeout: 30000 })
}

/**
 * The FU edit page rate-limits (429) when forms are opened back to back, and a
 * swallowed click leaves the previous form's canvas on screen — which silently
 * turned into "saved" passes. Confirm the canvas belongs to formName before
 * returning, and retry once after backing off.
 */
async function openSceneForm(page, sceneName, formName) {
  for (let attempt = 0; attempt < 3; attempt++) {
    await page.waitForTimeout(attempt === 0 ? 1200 : 5000)
    const tab = page.locator('.form-scene-tabs .el-tabs__item').filter({ hasText: sceneName }).first()
    await tab.click({ timeout: 15000 }).catch(() => {})
    await page.waitForTimeout(900)
    await page.getByText(formName, { exact: true }).first().click({ timeout: 15000 }).catch(() => {})
    const designerUp = await page.locator('fc-designer, .fc-designer-wrapper').first()
      .waitFor({ state: 'visible', timeout: 25000 }).then(() => true).catch(() => false)
    const stillOnList = await page.locator('.el-message').filter({ hasText: /Select a form first/i }).count()
    if (designerUp && stillOnList === 0) {
      await page.waitForTimeout(1200)
      return true
    }
    await backToFormList(page).catch(() => {})
  }
  rec(`${formName} opened in designer`, false, 'canvas never loaded')
  return false
}

async function backToFormList(page) {
  await page.getByRole('button', { name: /Back to List|返回列表/i }).first().click({ timeout: 10000 })
  await page.locator('.form-list-sidebar').first().waitFor({ state: 'visible', timeout: 20000 })
}

/**
 * Copy the New Request Advanced Upload field onto this canvas, then save.
 * That is configuration reuse (same Field), not inventing a table column.
 */
async function copyAndSave(page, scene, formName, slug) {
  if (!(await openSceneForm(page, scene, formName))) return false
  const btn = page.getByTestId('add-advanced-upload-from-new-request')
  const visible = await btn.waitFor({ state: 'visible', timeout: 10000 }).then(() => true).catch(() => false)
  rec(`${formName} shows Add Advanced Upload from New Request`, visible)
  if (!visible) {
    if (slug) await shot(page, DW_SHOTS, slug, true)
    await backToFormList(page).catch(() => {})
    return false
  }
  await btn.click()
  await page.waitForTimeout(1500)
  await page.getByRole('button', { name: /^Save$|^保存$/ }).first().click({ timeout: 8000 }).catch(() => {})
  const saved = await page.locator('.el-message').filter({ hasText: /Saved successfully|保存成功/i })
    .waitFor({ state: 'visible', timeout: 20000 })
    .then(() => true)
    .catch(() => false)
  rec(`${formName} saved`, saved, saved ? '' : 'no save toast')
  if (slug) await shot(page, DW_SHOTS, slug, true)
  await backToFormList(page).catch(() => {})
  return saved
}

function filenameOnPage(page, name) {
  return page.getByTestId('upload-file-card').filter({ hasText: name })
}

/**
 * The independent widget only — Meeting Doc (Basic Upload on `fileupload`) also
 * renders a drop zone, and asserting on "any drop zone" let the Basic Upload
 * path pass for the Advanced one.
 */
function advancedUploadItem(page) {
  return page.locator('.el-form-item')
    .filter({ has: page.getByTestId('form-upload-drop') })
    .filter({ hasText: /Advanced Upload/i })
    .first()
}

async function assertDropZoneNotTextbox(page, scene, fileName) {
  const item = advancedUploadItem(page)
  const found = await item.waitFor({ state: 'visible', timeout: 25000 }).then(() => true).catch(() => false)
  rec(`${scene} renders the independent Advanced Upload drop zone`, found)
  if (!found) return false
  await item.scrollIntoViewIfNeeded().catch(() => {})
  const cards = item.getByTestId('upload-file-card').filter({ hasText: fileName })
  const cardOk = await cards.first().waitFor({ state: 'visible', timeout: 20000 }).then(() => true).catch(() => false)
  rec(`${scene} shows ${fileName} on the Advanced Upload cards`, cardOk,
    `cards=${await item.getByTestId('upload-file-card').count()}`)
  const textboxed = await page.locator('.el-input__inner, textarea.el-textarea__inner').evaluateAll((els, name) => {
    return els.some((el) => String(el.value || '').includes(name))
  }, fileName)
  rec(`${scene} does not put the file into a text box`, !textboxed)
  return cardOk && !textboxed
}

/**
 * Issue 2: the file uploaded into the attachment row's Advanced Upload on New
 * Request must still be on that row in To Do / My Request.
 */
async function assertAttachmentFileVisible(page, scene, fileName) {
  const region = page.locator('.sub-table-field')
  const count = await region.count()
  for (let i = 0; i < count; i++) {
    await region.nth(i).scrollIntoViewIfNeeded().catch(() => {})
  }
  // Sub-table rows hydrate after the form shell, so a single check can read the
  // pre-hydration "-" cell and call a working column broken.
  const inTable = await region.filter({ hasText: fileName }).first()
    .waitFor({ state: 'visible', timeout: 25000 }).then(() => true).catch(() => false)
  if (inTable) {
    rec(`${scene} attachment row shows ${fileName}`, true, 'visible in sub-table')
    return true
  }
  // Column may be collapsed into the row dialog — open the first row and look there.
  for (let i = 0; i < count; i++) {
    const row = region.nth(i).locator('tbody tr').first()
    if (!(await row.count())) continue
    const opener = row.locator('button').filter({ hasText: /View|Detail|Edit|查看|详情|编辑/i }).first()
    if (!(await opener.count())) continue
    await opener.click({ force: true }).catch(() => {})
    await page.waitForTimeout(1500)
    const dlg = page.locator('.el-dialog:visible').last()
    const hit = await dlg.filter({ hasText: fileName }).count()
    await page.keyboard.press('Escape').catch(() => {})
    await page.waitForTimeout(500)
    if (hit > 0) {
      rec(`${scene} attachment row shows ${fileName}`, true, 'visible in row dialog')
      return true
    }
  }
  rec(`${scene} attachment row shows ${fileName}`, false, `sub-tables=${count}`)
  return false
}

async function uploadOnAdvancedUpload(page, filePath, expectedName) {
  const item = advancedUploadItem(page)
  await item.waitFor({ state: 'visible', timeout: 25000 })
  const input = item.locator('input[type="file"]').first()
  await input.waitFor({ state: 'attached', timeout: 15000 })
  await input.setInputFiles(filePath)
  await item.getByTestId('upload-file-card').filter({ hasText: expectedName }).first()
    .waitFor({ state: 'visible', timeout: 25000 })
}

async function tryUploadAttachmentRow(page, filePath, expectedName) {
  const tables = page.locator('.sub-table-field')
  const n = await tables.count()
  for (let i = 0; i < n; i++) {
    const table = tables.nth(i)
    const addBtn = table.locator('button').filter({ hasText: /^\s*Add\s*$/i }).first()
    if (!(await addBtn.count())) continue
    await addBtn.click({ force: true })
    await page.waitForTimeout(1500)
    const dlg = page.locator('.el-dialog:visible').last()
    const drop = dlg.getByTestId('form-upload-drop').first()
    const hasDrop = await drop.waitFor({ state: 'visible', timeout: 4000 }).then(() => true).catch(() => false)
    if (!hasDrop) {
      await dlg.locator('.el-dialog__headerbtn, button').filter({ hasText: /Cancel|取消|Close/i }).first()
        .click({ force: true }).catch(() => {})
      await page.keyboard.press('Escape').catch(() => {})
      await page.waitForTimeout(400)
      continue
    }
    rec('Attachment Add dialog uses drop zone (not text box)', true)
    const advancedItem = dlg.locator('.el-form-item').filter({ hasText: /Advanced Upload/i }).first()
    const advancedDrop = (await advancedItem.count())
      ? advancedItem.getByTestId('form-upload-drop').first()
      : dlg.getByTestId('form-upload-drop').nth(1)
    const target = (await advancedDrop.count()) ? advancedDrop : drop
    const input = target.locator('input[type="file"]').first()
    await input.setInputFiles(filePath)
    await filenameOnPage(page, expectedName).first().waitFor({ state: 'visible', timeout: 20000 }).catch(() => {})
    const text = dlg.locator('.el-form input:not([type=file])').first()
    if (await text.count()) {
      await text.fill(`row-${FILE_TAG}`).catch(() => {})
    }
    await dlg.locator('button').filter({ hasText: /^\s*Save\s*$/i }).first().click({ force: true })
    await page.waitForTimeout(2000)
    return true
  }
  rec('Attachment Add dialog uses drop zone (not text box)', false, 'no matching Add dialog')
  return false
}

async function submitStartForm(page) {
  const labeled = page.getByRole('button', { name: /Submit Application|Submit|提交申请|提交/i }).first()
  if (await labeled.count()) {
    await labeled.click({ force: true })
  } else {
    await page.locator('.right-actions .el-button--primary').last().click({ force: true })
  }
  await page.waitForTimeout(1500)
  const confirm = page.locator('.el-message-box button').filter({ hasText: /OK|Confirm|确定/i }).first()
  if (await confirm.count()) await confirm.click({ force: true })
  await page.waitForURL(/my-applications|tasks/, { timeout: 45000 }).catch(() => {})
  await page.waitForTimeout(3000)
}

async function latestTask(page, processKey) {
  const res = await page.request.post(`${ORIGIN}/api/portal/tasks/query`, { data: { page: 0, size: 30 } })
  const body = unwrap(await res.json())
  const rows = body?.content ?? body?.records ?? []
  return rows
    .filter((r) => String(r.processDefinitionKey || '') === processKey)
    .sort((a, b) => String(b.createTime || '').localeCompare(String(a.createTime || '')))[0]
}

async function latestApplication(page, processKey) {
  const res = await page.request.post(`${ORIGIN}/api/portal/processes/my-applications/query`, {
    data: { page: 0, size: 30 },
  })
  const body = unwrap(await res.json())
  const rows = body?.content ?? body?.records ?? []
  return rows
    .filter((r) => String(r.processDefinitionKey || r.processKey || '') === processKey
      || String(r.processDefinitionName || '').toLowerCase().includes('clone'))
    .sort((a, b) => String(b.startTime || b.createTime || '').localeCompare(String(a.startTime || a.createTime || '')))[0]
}

async function latestCompleted(page, processKey) {
  const res = await page.request.post(`${ORIGIN}/api/portal/tasks/completed/query`, { data: { page: 0, size: 30 } })
  const body = unwrap(await res.json())
  const rows = body?.content ?? body?.records ?? []
  return rows
    .filter((r) => String(r.processDefinitionKey || '') === processKey)
    .sort((a, b) => String(b.completedTime || b.endTime || '').localeCompare(String(a.completedTime || a.endTime || '')))[0]
}

async function completeCurrentTask(page) {
  const approve = page.getByRole('button', { name: /Approve|批准|同意/i }).first()
  const custom = page.locator('.action-section .right-actions .el-button--primary, .action-section .right-actions .el-button--success').first()
  if (await approve.count()) {
    await approve.click({ force: true })
  } else if (await custom.count()) {
    await custom.click({ force: true })
  } else {
    rec('To Do has an Approve/complete action', false)
    return false
  }
  await page.waitForTimeout(800)
  const dialog = page.locator('.el-dialog:visible').last()
  if (await dialog.count()) {
    const comment = dialog.locator('textarea').first()
    if (await comment.count()) await comment.fill(`e2e ${FILE_TAG}`)
    await dialog.getByRole('button', { name: /Confirm|OK|确定/i }).first().click({ force: true }).catch(() => {})
  }
  await page.waitForTimeout(6000)
  return true
}

const browser = await chromium.launch({ headless: true })
let copied = false
try {
  const dw = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const dwPage = await dw.newPage()
  await loginViaDwPassword(dwPage, { loginOrigin: ORIGIN })
  await openFormDesign(dwPage)
  copied = await copyAndSave(dwPage, 'Request', 'Main (My Request)', 'dw-my-request-copy-advanced-upload')
  copied = (await copyAndSave(dwPage, 'Request', 'Assign Task (My Request)', 'dw-assign-task-my-request-copy-advanced-upload')) || copied
  copied = (await copyAndSave(dwPage, 'Task', 'Assign Task', 'dw-assign-task-copy-advanced-upload')) || copied
  await dw.close()

  const portal = await browser.newContext({ viewport: { width: 1440, height: 1100 } })
  const page = await portal.newPage()
  await loginViaPortalPassword(page, { buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role' })

  await page.goto(`${ORIGIN}/portal/processes/start/${PROCESS_KEY}`, { waitUntil: 'domcontentloaded' })
  await page.getByTestId('form-upload-drop').first().waitFor({ state: 'visible', timeout: 30000 })
  rec('New Request shows independent Advanced Upload drop zone', true)
  await uploadOnAdvancedUpload(page, tmpPdf(MAIN_FILE), MAIN_FILE)
  rec('New Request accepted a file on Advanced Upload', true)
  await tryUploadAttachmentRow(page, tmpPdf(ATTACH_FILE), ATTACH_FILE)
  await shot(page, UP_SHOTS, 'portal-advanced-upload-new-request', true)

  await submitStartForm(page)
  rec('New Request submitted', /my-applications|tasks/.test(page.url()), page.url())

  const app = await latestApplication(page, PROCESS_KEY)
  rec('My Request instance is queryable', Boolean(app?.id), app?.id || 'none')
  if (app?.id) {
    await page.goto(`${ORIGIN}/portal/applications/${app.id}`, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(5000)
    const generalTab = page.getByRole('tab', { name: /General|概览|一般/i }).first()
    if (await generalTab.count()) await generalTab.click().catch(() => {})
    await page.waitForTimeout(1000)
    await assertDropZoneNotTextbox(page, 'My Request', MAIN_FILE)
    await assertAttachmentFileVisible(page, 'My Request', ATTACH_FILE)
    await shot(page, UP_SHOTS, 'portal-advanced-upload-my-request', true)
  }

  const todo = await latestTask(page, PROCESS_KEY)
  rec('To Do task is queryable', Boolean(todo?.taskId), todo?.taskId || 'none')
  if (todo?.taskId) {
    await page.goto(`${ORIGIN}/portal/tasks/${todo.taskId}`, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(6000)
    await assertDropZoneNotTextbox(page, 'To Do', MAIN_FILE)
    await assertAttachmentFileVisible(page, 'To Do', ATTACH_FILE)
    await shot(page, UP_SHOTS, 'portal-advanced-upload-todo', true)
    const completedOk = await completeCurrentTask(page)
    rec('Completed the To Do task', completedOk)
    if (completedOk) {
      const done = await latestCompleted(page, PROCESS_KEY)
      rec('Completed Task is queryable', Boolean(done?.taskId), done?.taskId || 'none')
      if (done?.taskId) {
        const q = new URLSearchParams()
        if (done.completedTime) q.set('snapshotTime', done.completedTime)
        if (done.taskName) q.set('snapshotTaskName', done.taskName)
        q.set('snapshotTaskId', done.taskId)
        if (done.processInstanceId) q.set('processInstanceId', String(done.processInstanceId))
        if (done.processDefinitionKey) q.set('processDefinitionKey', String(done.processDefinitionKey))
        await page.goto(`${ORIGIN}/portal/tasks/${done.taskId}?${q.toString()}`, { waitUntil: 'domcontentloaded' })
        await page.waitForTimeout(6000)
        await assertDropZoneNotTextbox(page, 'Completed Task', MAIN_FILE)
        await shot(page, UP_SHOTS, 'portal-advanced-upload-completed', true)
      }
    }
  }

  await portal.close()
} catch (e) {
  rec('script completed without throw', false, e?.message || String(e))
  console.error(e)
} finally {
  await browser.close()
}

const failed = results.filter((r) => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} passed`)
if (failed.length) {
  for (const f of failed) console.log(`  - ${f.n}${f.d ? ` (${f.d})` : ''}`)
  process.exit(1)
}
