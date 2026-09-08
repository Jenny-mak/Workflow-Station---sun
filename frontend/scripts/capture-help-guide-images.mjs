/**
 * Capture Designer screens for /help/ article figures (DW login, no SSO).
 * Writes PNGs into frontend/help/public/guides/.
 * Requires HELP_GUIDE_FU_ID from create-help-demo-purchase-request.mjs.
 * After recapture:
 * 1. Bump GUIDE_FIGURE_REV in frontend/help/src/components/GuideArticle.vue
 * 2. cd frontend/help && pnpm run build   (copies public/guides → dist/guides)
 * 3. Rebuild platform-help-frontend — Dockerfile.local only COPY dist, not public/
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { loginViaDwPassword } from './playwright-login.mjs'
import { redactHelpGuidePii } from './redact-help-guide-pii.mjs'

const FU_ID = process.env.HELP_GUIDE_FU_ID?.trim()
if (!FU_ID) {
  console.error(
    'HELP_GUIDE_FU_ID is required. Run: node scripts/create-help-demo-purchase-request.mjs',
  )
  process.exit(1)
}

const __dirname = dirname(fileURLToPath(import.meta.url))
const OUT = resolve(__dirname, '../help/public/guides')
mkdirSync(OUT, { recursive: true })

const origin = 'http://localhost:3000'

const launchOpts = { headless: true }
if (process.env.PLAYWRIGHT_EXECUTABLE_PATH) {
  launchOpts.executablePath = process.env.PLAYWRIGHT_EXECUTABLE_PATH
} else if (process.env.PLAYWRIGHT_CHANNEL) {
  launchOpts.channel = process.env.PLAYWRIGHT_CHANNEL
}

const browser = await chromium.launch(launchOpts)
const page = await (await browser.newContext({ viewport: { width: 1440, height: 1300 } })).newPage()

async function shot(name, locator) {
  await redactHelpGuidePii(page)
  const target = locator ?? page
  const path = resolve(OUT, name)
  await target.screenshot({ path })
  console.log(`wrote ${path}`)
}

async function clickTab(label) {
  const tab = page.getByRole('tab', { name: label, exact: true })
  await tab.waitFor({ state: 'visible', timeout: 15000 })
  await tab.click()
  await page.waitForTimeout(600)
}

async function openFormPreview() {
  const previewBtn = page
    .locator('.form-editor-view .header-actions')
    .getByRole('button', { name: 'Preview', exact: true })
  await previewBtn.waitFor({ state: 'visible', timeout: 15000 })
  await previewBtn.click()
  const dlg = page.locator('.form-preview-dialog')
  await dlg.waitFor({ state: 'visible', timeout: 20000 })
  await dlg.locator('.form-preview-wrapper, form-create').first().waitFor({ state: 'visible', timeout: 20000 })
  await page.waitForTimeout(800)
  return dlg
}

async function closeFormPreview() {
  await page.keyboard.press('Escape')
  await page.locator('.form-preview-dialog').waitFor({ state: 'hidden', timeout: 8000 }).catch(() => {})
  await page.waitForTimeout(400)
}

function previewBodySelector() {
  return '.form-preview-dialog .el-dialog__body'
}

async function injectPreviewBanner() {
  await page.evaluate((bodySel) => {
    const body = document.querySelector(bodySel)
    if (!body) return
    body.querySelectorAll('.help-capture-banner').forEach((n) => n.remove())
    const banner = document.createElement('div')
    banner.className =
      'help-capture-banner el-alert el-alert--warning is-light form-event-banner'
    banner.setAttribute('role', 'alert')
    banner.innerHTML =
      '<i class="el-icon el-alert__icon"><svg viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg"><path fill="currentColor" d="M512 64a448 448 0 1 1 0 896 448 448 0 0 1 0-896zm0 832a384 384 0 1 0 0-768 384 384 0 0 0 0 768zm48-176a48 48 0 1 1-96 0 48 48 0 0 1 96 0zm-48-368a32 32 0 0 1 32 32v224a32 32 0 0 1-64 0V384a32 32 0 0 1 32-32z"/></svg></i><div class="el-alert__content"><span class="el-alert__title">Check the title</span></div>'
    body.insertBefore(banner, body.firstChild)
  }, previewBodySelector())
}

async function injectPreviewFieldError() {
  await page.evaluate(() => {
    const items = [...document.querySelectorAll('.form-preview-dialog .el-form-item')]
    const titleItem = items.find((el) => /^Title\b/.test(el.querySelector('.el-form-item__label')?.textContent?.trim() || ''))
    if (!titleItem) return
    titleItem.classList.add('is-error')
    let err = titleItem.querySelector('.el-form-item__error')
    if (!err) {
      err = document.createElement('div')
      err.className = 'el-form-item__error'
      titleItem.appendChild(err)
    }
    err.textContent = 'Title is required'
  })
}

async function injectPreviewDisabledCostCenter() {
  await page.evaluate(() => {
    const items = [...document.querySelectorAll('.form-preview-dialog .el-form-item')]
    const ccItem = items.find((el) => /^Cost center\b/i.test(el.querySelector('.el-form-item__label')?.textContent?.trim() || ''))
    if (!ccItem) return
    ccItem.querySelectorAll('.el-input, .el-select, .el-input-number').forEach((el) => {
      el.classList.add('is-disabled')
    })
    ccItem.querySelectorAll('input, textarea').forEach((el) => {
      el.setAttribute('disabled', 'disabled')
    })
  })
}

async function capturePreviewEffect(name, injectFn) {
  const dlg = await openFormPreview()
  await injectFn()
  await page.waitForTimeout(500)
  await shot(name, dlg.locator('.el-dialog__body'))
  await closeFormPreview()
}

async function clickBpmnNode(text) {
  const node = page.locator('.djs-element').filter({ hasText: text }).first()
  if (await node.count()) {
    await node.click({ force: true })
    await page.waitForTimeout(800)
  }
}

try {
  await loginViaDwPassword(page)
  await page.goto(`${origin}/dev/function-units/${FU_ID}`, { waitUntil: 'domcontentloaded' })
  await page.locator('.el-tabs').first().waitFor({ state: 'visible', timeout: 25000 })
  await page.waitForTimeout(1500)

  await clickTab('Table Design')
  const mainTableRow = page.locator('.el-table__row').filter({ hasText: 'help_pr' }).filter({ hasNotText: 'help_pr_line' }).first()
  await mainTableRow.waitFor({ state: 'visible', timeout: 10000 })
  await mainTableRow.click()
  await page.locator('.table-fields-grid').waitFor({ state: 'visible', timeout: 10000 })
  await page.waitForTimeout(800)
  await shot('dw-table-design.png', page.locator('.designer-workspace'))
  await page.getByRole('button', { name: 'Back to List' }).click()
  await page.waitForTimeout(400)

  await clickTab('Form Design')
  try {
    const formEdit = page.locator('.form-list-sidebar .el-table__row').filter({ hasText: 'help_pr' }).getByRole('button', { name: 'Edit' }).first()
    await formEdit.waitFor({ state: 'visible', timeout: 10000 })
    await formEdit.click()
    await page.locator('.fc-designer-wrapper').first().waitFor({ state: 'visible', timeout: 15000 })
    await page.waitForTimeout(800)

    const scenarioField = page.locator('.el-form-item').filter({ hasText: /^Scenario/ }).first()
    if (await scenarioField.count()) await scenarioField.click({ force: true })
    await page.waitForTimeout(500)
    await shot('dw-form-events-canvas.png', page.locator('.fc-designer-wrapper').first())

    const formTab = page.locator('._fc-r-tab').filter({ hasText: /^Form$/ }).first()
    if (await formTab.count()) {
      await formTab.click()
      await page.waitForTimeout(800)
      const formEventBtn = page.locator('._fd-fn-list .el-button').first()
      if (await formEventBtn.count()) {
        await formEventBtn.scrollIntoViewIfNeeded()
        await page.waitForTimeout(400)
      }
      await shot('dw-form-events-form-tab.png', page.locator('.fc-designer-wrapper').first())
    }

    await capturePreviewEffect('dw-form-events-preview-notify.png', injectPreviewBanner)
    await capturePreviewEffect('dw-form-events-preview-errors.png', injectPreviewFieldError)
    await capturePreviewEffect('dw-form-events-preview-disabled.png', injectPreviewDisabledCostCenter)

    const titleField = page.locator('.el-form-item').filter({ hasText: /^Title/ }).first()
    if (await titleField.count()) await titleField.click({ force: true })
    else await page.locator('.el-form-item').first().click({ force: true })
    await page.waitForTimeout(500)
    await page.locator('._fd-event .el-button').first().click()
    const eventDlg = page.locator('._fd-event-dialog').last()
    await eventDlg.waitFor({ state: 'visible', timeout: 8000 })
    await page.waitForTimeout(500)
    await shot('dw-form-events.png', eventDlg)
    await page.keyboard.press('Escape')
    await page.waitForTimeout(300)
    await page.getByRole('button', { name: 'Back to List' }).click()
    await page.waitForTimeout(400)
  } catch (err) {
    console.warn('skip form-events screenshots', err)
    const back = page.getByRole('button', { name: 'Back to List' })
    if (await back.count()) await back.click().catch(() => {})
    await page.waitForTimeout(400)
  }

  await clickTab('Connections')
  await shot('dw-connections.png', page.locator('.designer-workspace'))
  const inboundRow = page.locator('.connection-designer .el-table__row').filter({ hasText: /Gmail|IMAP/i }).first()
  if (await inboundRow.count()) {
    await inboundRow.getByRole('button', { name: 'Edit' }).click()
  } else {
    await page.getByRole('button', { name: 'New Connection' }).click()
  }
  const connDlg = page.locator('.connection-form-dialog').last()
  await connDlg.waitFor({ state: 'visible', timeout: 8000 })
  await page.waitForTimeout(600)
  await shot('dw-connections-inbound.png', connDlg)
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)

  await clickTab('Email Templates')
  await shot('dw-email-templates.png', page.locator('.designer-workspace'))
  const editTpl = page.locator('.designer-workspace .el-table').getByRole('button', { name: 'Edit' }).first()
  if (await editTpl.count()) {
    await editTpl.click({ timeout: 8000 })
    const tplDlg = page.locator('.email-template-form-dialog, .el-dialog').filter({ hasText: 'Body' }).last()
    await tplDlg.waitFor({ state: 'visible', timeout: 8000 })
    await page.locator('[data-testid="email-body-split"]').waitFor({ state: 'visible', timeout: 8000 })
    await page.waitForTimeout(800)
    await shot('dw-email-body.png', tplDlg)
    await page.keyboard.press('Escape')
    await page.waitForTimeout(400)
  }

  await clickTab('Email Monitors')
  await shot('dw-email-monitors.png', page.locator('.designer-workspace'))
  const editMonitor = page.locator('.email-monitor-designer .el-table').getByRole('button', { name: 'Edit' }).first()
  if (await editMonitor.count()) {
    await editMonitor.click()
    const monitorDlg = page.locator('.el-dialog').filter({ hasText: 'Field Extraction' }).last()
    await monitorDlg.waitFor({ state: 'visible', timeout: 15000 })
    await page.waitForTimeout(600)
    await clickTab('Sample Email')
    await page.evaluate(() => {
      const dlg = document.querySelector('.el-dialog:last-of-type')
      if (!dlg) return
      const setInput = (label, value) => {
        const item = [...dlg.querySelectorAll('.el-form-item')].find((el) =>
          el.querySelector('.el-form-item__label')?.textContent?.trim().startsWith(label),
        )
        const input = item?.querySelector('input, textarea')
        if (input instanceof HTMLInputElement || input instanceof HTMLTextAreaElement) {
          input.value = value
          input.dispatchEvent(new Event('input', { bubbles: true }))
        }
      }
      setInput('Subject', 'Vendor quote for help_pr — CaseNo: 2026001')
      setInput('From', 'Vendor Desk <vendor@example.com>')
      setInput('To', 'Procurement Team <procurement@example.com>')
      setInput('Cc', 'audit@example.com')
      setInput('Reply-To', 'noreply@example.com')
      setInput('Sent date', '2026-09-08T08:30:00Z')
      setInput('Message-ID', '<help-demo-msg@example.com>')
      setInput('Plain Text Body', 'Case No: 2026001\nAmount: HKD 1,200.00')
    })
    await page.waitForTimeout(400)
    await shot('dw-email-extraction-sample.png', monitorDlg)
    await clickTab('Field Mapping')
    await page.evaluate(() => {
      const dlg = document.querySelector('.el-dialog:last-of-type')
      if (!dlg) return
      const addBtn = [...dlg.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Add Field')
      addBtn?.click()
    })
    await page.waitForTimeout(800)
    await page.evaluate(() => {
      const dlg = document.querySelector('.el-dialog:last-of-type')
      if (!dlg) return
      const row = dlg.querySelector('.el-table__body tr:last-child')
      if (!row) return
      const selects = row.querySelectorAll('.el-select')
      if (selects.length >= 2) {
        selects[1].click()
      }
    })
    await page.waitForTimeout(300)
    const fromOpt = page.getByRole('option', { name: 'From (sender)', exact: true })
    if (await fromOpt.count()) {
      await fromOpt.click()
    }
    await page.waitForTimeout(500)
    await shot('dw-email-field-mapping.png', monitorDlg)
    await page.keyboard.press('Escape')
    await page.waitForTimeout(400)
  }

  await clickTab('Process Design')
  await page.waitForTimeout(1500)
  await clickBpmnNode('Send approval notice')
  await shot('dw-send-task.png', page.locator('.designer-workspace'))

  await clickBpmnNode(/^Start$/)
  const inboundBody = page.locator('.start-email-monitor')
  if ((await inboundBody.count()) === 0 || !(await inboundBody.isVisible().catch(() => false))) {
    await page.locator('.el-collapse-item').filter({ hasText: 'Inbound Email Trigger' }).locator('.el-collapse-item__header').click({ force: true })
    await page.waitForTimeout(500)
  }
  if (await inboundBody.count()) {
    await inboundBody.scrollIntoViewIfNeeded()
    await page.waitForTimeout(600)
  }
  await shot('dw-start-event.png', page.locator('.designer-workspace'))
} finally {
  await browser.close()
}

console.log('')
console.log('Next: cd frontend/help && pnpm run build')
console.log('Then: docker compose ... up -d --build platform-help-frontend')
console.log('(Help image only ships dist/guides — public/ alone is not served.)')
