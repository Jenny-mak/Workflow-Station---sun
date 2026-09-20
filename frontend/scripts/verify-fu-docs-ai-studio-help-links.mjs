/**
 * The two help `?` icons added with the FU documents / Build with AI guidelines:
 * Function Unit Settings → Requirements toolbar, and the Build with AI dialog title.
 * HELP_GUIDE_FU_ID = any Function Unit the developer account can modify.
 */
import { chromium } from 'playwright'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { loginViaDwPassword } from './playwright-login.mjs'

const FU_ID = process.env.HELP_GUIDE_FU_ID?.trim()
if (!FU_ID) {
  console.error('HELP_GUIDE_FU_ID is required')
  process.exit(1)
}
const origin = 'http://localhost:3000'
const SHOTS = resolve(dirname(fileURLToPath(import.meta.url)), '../developer-workstation/verification-screenshots')
const DATE = new Date().toISOString().slice(0, 10)
const failures = []
const rec = (label, ok, extra = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${extra ? ` → ${extra}` : ''}`)
  if (!ok) failures.push(label)
}

const browser = await chromium.launch()
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage()
try {
  await loginViaDwPassword(page)

  async function checkLink(testId, path, testPageId) {
    const link = page.getByTestId(testId)
    await link.waitFor({ state: 'visible', timeout: 15000 })
    const href = (await link.getAttribute('href')) ?? ''
    rec(`${testId} points at /help${path}`, href.endsWith(`/help${path}`), href)
    const [popup] = await Promise.all([context.waitForEvent('page'), link.click()])
    await popup.getByTestId(testPageId).waitFor({ state: 'visible', timeout: 15000 })
    rec(`${testId} opens the article in a new tab`, popup.url().includes(`/help${path}`), popup.url())
    await popup.close()
  }

  await page.goto(`${origin}/dev/function-units/${FU_ID}?settings=REQUIREMENTS`, { waitUntil: 'domcontentloaded' })
  await checkLink('fu-documents-guide-link', '/fu-documents', 'fu-documents-guide-page')
  await page.screenshot({ path: resolve(SHOTS, `${DATE}_help-link-fu-documents.png`) })

  await page.goto(`${origin}/dev/function-units/${FU_ID}`, { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: /AI Studio/ }).first().click()
  await checkLink('ai-studio-guide-link', '/ai-studio', 'ai-studio-guide-page')
  await page.screenshot({ path: resolve(SHOTS, `${DATE}_help-link-ai-studio.png`) })
} catch (e) {
  console.error(e)
  failures.push(`exception: ${e.message}`)
} finally {
  await browser.close()
  console.log(failures.length ? `\n${failures.length} FAILED` : '\nALL PASS')
  process.exit(failures.length ? 1 : 0)
}
