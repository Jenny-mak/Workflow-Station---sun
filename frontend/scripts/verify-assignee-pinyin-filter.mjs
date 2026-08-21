/**
 * My Requests Current Assignee picker: Chinese 李 must list 李娜; pinyin li must not.
 */
import { mkdirSync } from 'fs'
import { dirname, join } from 'path'
import { fileURLToPath } from 'url'
import { chromium } from 'playwright'
import { loginViaPortalPassword } from './playwright-login.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const outDir = join(__dirname, '..', 'user-portal', 'verification-screenshots')
mkdirSync(outDir, { recursive: true })

function stamp(slug) {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return join(
    outDir,
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}_${slug}.png`,
  )
}

const browser = await chromium.launch({ headless: true, channel: 'chrome' })
const page = await (await browser.newContext({ viewport: { width: 1400, height: 900 } })).newPage()

async function openAssigneeFilter() {
  await page.goto('http://localhost:3000/portal/my-applications', { waitUntil: 'domcontentloaded' })
  await page.locator('.list-data-grid').waitFor({ timeout: 60000 })
  const assigneeHeader = page.locator('.list-col-header', {
    hasText: /Current Assignee|当前处理人|目前處理人/,
  }).locator('.list-col-trigger')
  await assigneeHeader.first().scrollIntoViewIfNeeded()
  await assigneeHeader.first().click()
  await page.getByRole('menuitem', { name: /Filter by|筛选依据|篩選依據/ }).click()
  await page.locator('.el-dialog').filter({ hasText: /Current Assignee|当前处理人|目前處理人/ }).waitFor()
  if (await page.locator('.list-filter-user-hint').count()) {
    throw new Error('filter-user hint should have been removed')
  }
}

try {
  await loginViaPortalPassword(page, { buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role' })
  await openAssigneeFilter()

  const valueInput = page.locator('.list-filter-user input')
  await valueInput.click()
  await valueInput.fill('李')
  await page.locator('.el-select-dropdown__item').filter({ hasText: '李娜' }).first().waitFor({ timeout: 15000 })
  const chineseShot = stamp('my-requests-assignee-filter-li-char')
  await page.screenshot({ path: chineseShot, fullPage: false })
  console.log(`[verify] 李: ${chineseShot}`)

  await valueInput.fill('')
  await valueInput.fill('li')
  await page.locator('.el-select-dropdown__empty').locator('visible=true').waitFor({ timeout: 15000 })
  if (await page.locator('.el-select-dropdown__item').locator('visible=true').filter({ hasText: '李娜' }).count()) {
    throw new Error('pinyin li must not list 李娜')
  }
  const pinyinShot = stamp('my-requests-assignee-filter-li-pinyin')
  await page.screenshot({ path: pinyinShot, fullPage: false })
  console.log(`[verify] li: ${pinyinShot}`)
} finally {
  await browser.close()
}
