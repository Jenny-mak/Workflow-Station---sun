/**
 * Capture Email Monitor help figures only (sample + field mapping tabs).
 * Writes into frontend/help/public/guides/ with PII redaction.
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { loginViaDwPassword } from './playwright-login.mjs'
import { redactHelpGuidePii } from './redact-help-guide-pii.mjs'

const FU_ID = process.env.HELP_GUIDE_FU_ID?.trim() || '50006'
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
const page = await (await browser.newContext({ viewport: { width: 1440, height: 1400 } })).newPage()

async function shot(name, locator) {
  await redactHelpGuidePii(page)
  const target = locator ?? page
  const path = resolve(OUT, name)
  await target.screenshot({ path })
  console.log(`wrote ${path}`)
}

async function clickTab(label) {
  const tab = typeof label === 'string'
    ? page.getByRole('tab', { name: label, exact: true })
    : page.getByRole('tab', { name: label })
  await tab.waitFor({ state: 'visible', timeout: 15000 })
  await tab.click()
  await page.waitForTimeout(600)
}

try {
  await loginViaDwPassword(page)
  await page.goto(`${origin}/dev/function-units/${FU_ID}`, { waitUntil: 'domcontentloaded' })
  await page.locator('.el-tabs').first().waitFor({ state: 'visible', timeout: 25000 })
  await page.waitForTimeout(1200)
  await clickTab('Email Monitors')
  await page.locator('.email-monitor-designer').waitFor({ state: 'visible', timeout: 20000 })
  await page.waitForTimeout(1000)

  const editBtn = page
    .locator('.email-monitor-designer')
    .getByRole('button', { name: /^Edit$|^编辑$|^編輯$/i })
    .first()
  if (await editBtn.count()) {
    await editBtn.click()
  } else {
    await page.locator('.email-monitor-designer').getByRole('button', { name: /New Monitor|新建监听|新建監聽/i }).click()
  }

  const monitorDlg = page.locator('.el-dialog').filter({ hasText: /Field Extraction|字段提取|欄位擷取/i }).last()
  await monitorDlg.waitFor({ state: 'visible', timeout: 15000 })
  await page.waitForTimeout(600)

  await clickTab(/Sample Email|样例邮件|樣例郵件/i)
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

  await clickTab(/Field Mapping|字段映射|欄位對應/i)
  await page.getByRole('button', { name: /^Add Field$|^添加字段$|^新增欄位$/i }).click()
  await page.waitForTimeout(500)
  const sourceSelect = monitorDlg.locator('.el-table__body tr:last-child .el-select').nth(1)
  await sourceSelect.click()
  await page.waitForTimeout(300)
  await page.getByRole('option', { name: /From \(sender\)|发件人|寄件人/i }).click()
  await page.waitForTimeout(600)
  await shot('dw-email-field-mapping.png', monitorDlg)
} finally {
  await browser.close()
}

console.log('Done. Bump GUIDE_FIGURE_REV and rebuild platform-help-frontend.')
