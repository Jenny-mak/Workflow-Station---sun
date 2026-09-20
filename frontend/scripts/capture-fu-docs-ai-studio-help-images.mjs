/**
 * Capture figures for /help/fu-documents and /help/ai-studio (DW login, no SSO).
 * Writes PNGs into frontend/help/public/guides/.
 * Requires HELP_GUIDE_FU_ID (Purchase Request demo: help_pr / help_pr_line).
 * RUN_MODEL=1 also runs one real one-click generation (about 5-15 minutes) for the
 * result-card figure. It REPLACES the design of that Function Unit, so point it at a
 * scratch copy of the demo, not at the one the other capture scripts read.
 * After recapture: bump GUIDE_FIGURE_REV, rebuild frontend/help, rebuild platform-help-frontend.
 */
import { chromium } from 'playwright'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { loginViaDwPassword } from './playwright-login.mjs'
import { redactHelpGuidePii } from './redact-help-guide-pii.mjs'
import { trimPngWhitespace } from './trim-png-whitespace.mjs'

const FU_ID = process.env.HELP_GUIDE_FU_ID?.trim()
if (!FU_ID) {
  console.error('HELP_GUIDE_FU_ID is required. Run: node scripts/create-help-demo-purchase-request.mjs')
  process.exit(1)
}
const RUN_MODEL = process.env.RUN_MODEL === '1'
const __dirname = dirname(fileURLToPath(import.meta.url))
const OUT = resolve(__dirname, '../help/public/guides')
mkdirSync(OUT, { recursive: true })
const origin = 'http://localhost:3000'
const TMP = mkdtempSync(join(tmpdir(), 'help-fu-docs-'))

const REQUIREMENTS_V1 = `# Purchase Request

## Background & Goals
Employees raise a purchase request. The department manager approves it.

## Data Requirements
- Main table help_pr: request_title, start_date, end_date, grand_total.
- Sub table help_pr_line: item_name, quantity, unit_price, line_total.
`
const REQUIREMENTS_V2 = `${REQUIREMENTS_V1}
## Business Rules & Decisions
- A request with grand_total above 5000 also needs Finance approval.
`
const ONE_CLICK_TEXT =
  'Purchase Request. Main table help_pr (request_title, start_date, end_date, grand_total) with sub table ' +
  'help_pr_line (item_name, quantity, unit_price, line_total). The employee submits the request, the ' +
  'department manager approves or rejects it, and a request with grand_total above 5000 also needs Finance approval.'

const browser = await chromium.launch({ headless: true })
const page = await (await browser.newContext({ viewport: { width: 1440, height: 1000 } })).newPage()

/** The version line shows the saver's login name; figures must not carry it. */
async function redact() {
  await redactHelpGuidePii(page)
  await page.evaluate(() => {
    for (const el of document.querySelectorAll('.document-editor__meta, .document-history__meta')) {
      for (const node of el.childNodes) {
        if (node.nodeType === Node.TEXT_NODE && node.nodeValue) {
          node.nodeValue = node.nodeValue
            .replace(/· \S+ · (\d{4}-)/, '· designer · $1')
            .replace(/^\s*\S+ · (\d{4}-)/, 'designer · $1')
        }
      }
    }
  })
}
async function shot(name, locator) {
  await redact()
  const path = resolve(OUT, name)
  await (locator ?? page).screenshot({ path })
  console.log(`wrote ${path}`)
}

try {
  await loginViaDwPassword(page)

  // ---- /help/fu-documents ----
  await page.goto(`${origin}/dev/function-units/${FU_ID}?settings=REQUIREMENTS`, { waitUntil: 'domcontentloaded' })
  const dialog = page.locator('.el-dialog:visible').first()
  const editor = dialog.locator('.document-editor').first()
  await editor.waitFor({ timeout: 20000 })
  // 文档是异步载入的：等遮罩消失再读编辑器，否则填进去的内容会被随后到达的已保存版本盖掉
  await editor.locator('.el-loading-mask').waitFor({ state: 'hidden', timeout: 15000 }).catch(() => {})
  await page.waitForTimeout(800)
  const save = editor.getByRole('button', { name: 'Save', exact: true })
  const textarea = editor.locator('textarea')
  // 重跑时库里已是 V2：先回到 V1，导入 V2 才会是「未保存」状态
  if ((await textarea.inputValue()) !== REQUIREMENTS_V1) {
    await textarea.fill(REQUIREMENTS_V1)
    await save.click()
    await page.waitForTimeout(1500)
  }
  const v2 = join(TMP, 'purchase-request-requirements.md')
  writeFileSync(v2, REQUIREMENTS_V2)
  await editor.locator('xpath=..').locator('input[type=file]').first().setInputFiles(v2).catch(async () => {
    await dialog.locator('input[type=file]').first().setInputFiles(v2)
  })
  await page.waitForTimeout(800)
  await page.locator('.el-message').first().waitFor({ state: 'hidden', timeout: 8000 }).catch(() => {})
  await shot('dw-fu-documents.png', dialog)
  await save.click()
  await page.waitForTimeout(1500)
  await editor.getByRole('button', { name: 'Version History', exact: true }).click()
  const history = page.locator('.el-drawer:visible').last()
  await history.locator('.document-history__item').first().waitFor({ timeout: 10000 })
  await page.waitForTimeout(500)
  await shot('dw-fu-documents-history.png', history)
  // 抽屉与视口等高，版本条目下面是大片空白
  await trimPngWhitespace(page, resolve(OUT, 'dw-fu-documents-history.png'))

  // ---- /help/ai-studio ----
  await page.goto(`${origin}/dev/function-units/${FU_ID}`, { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: /AI Studio/ }).first().click()
  const entry = page.locator('.ai-studio-entry-dialog')
  await entry.locator('.mode-card').first().waitFor({ timeout: 15000 })
  await page.waitForTimeout(900)
  await shot('dw-ai-studio-entry.png', entry)
  await page.locator('[data-testid=ai-studio-mode-generate]').click()
  await page.locator('textarea[data-testid=ai-studio-one-click-input]').fill(ONE_CLICK_TEXT)
  await page.waitForTimeout(400)
  await shot('dw-ai-studio-one-click.png', entry)

  if (RUN_MODEL) {
    await page.locator('[data-testid=ai-studio-entry-confirm]').click()
    await page.locator('.el-message-box__btns button').last().click()
    await page.waitForURL(/ai-studio/, { timeout: 30000 })
    await page.waitForSelector('.copilot-msg__bubble--typing', { timeout: 30000 })
    const done = await page.waitForSelector('.proposal-card', { timeout: 30 * 60 * 1000 }).then(() => true).catch(() => false)
    if (!done) throw new Error('one-click generation produced no result card')
    await page.waitForTimeout(4000)
    const fit = page.getByRole('button', { name: 'Fit Canvas', exact: true })
    if (await fit.count()) await fit.click().catch(() => {})
    await page.waitForTimeout(800)
    await shot('dw-ai-studio-result.png')
    console.log('card:', (await page.locator('.proposal-card').last().innerText()).replace(/\s+/g, ' ').slice(0, 400))
  }
} finally {
  await browser.close()
}
