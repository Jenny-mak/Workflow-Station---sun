/**
 * Continue Owner Demo draft / start via UI, then screenshot My Applications + detail.
 */
import { mkdirSync } from 'fs'
import { join, dirname } from 'path'
import { fileURLToPath } from 'url'
import { chromium } from 'playwright'
import { loginViaPortalPassword } from './playwright-login.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ORIGIN = (process.env.HELP_GUIDE_ORIGIN ?? 'http://localhost:3000').replace(/\/$/, '')
const DATE = '2026-09-07'
const portalDir = join(__dirname, '../user-portal/verification-screenshots')
mkdirSync(portalDir, { recursive: true })

async function shot(page, slug) {
  const dest = join(portalDir, `${DATE}_${slug}.png`)
  await page.screenshot({ path: dest, fullPage: false })
  console.log('wrote', dest, 'url=', page.url())
  return dest
}

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 1100 } })
const page = await ctx.newPage()
await loginViaPortalPassword(page, { user: 'e2e_zhangwei', pass: 'password' })

for (const key of ['50006', 'owner-demo-20260907-gehibh']) {
  const res = await page.request.post(`${ORIGIN}/api/portal/processes/${encodeURIComponent(key)}/start`, {
    data: {
      processDefinitionKey: key,
      formData: { title: `Owner screenshot ${Date.now()}` },
      priority: 'NORMAL',
    },
  })
  const body = await res.json().catch(() => ({}))
  console.log('start', key, res.status(), JSON.stringify(body).slice(0, 400))
  const instanceId = body.data?.id || body.data?.processInstanceId
  if (instanceId) {
    await page.goto(`${ORIGIN}/portal/applications/${encodeURIComponent(instanceId)}`, {
      waitUntil: 'domcontentloaded',
      timeout: 60000,
    })
    await page.waitForTimeout(4000)
    await shot(page, 'owner-application-detail')
    await page.goto(`${ORIGIN}/portal/my-applications`, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(2500)
    await shot(page, 'owner-my-requests-after-start')
    await ctx.close()
    await browser.close()
    console.log('done via api')
    process.exit(0)
  }
}

await page.goto(`${ORIGIN}/portal/my-applications`, { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(2000)
const draftsTab = page.locator('.el-tabs__item').filter({ hasText: /Draft|草稿/ }).first()
await draftsTab.click()
await page.waitForTimeout(1500)
await shot(page, 'owner-drafts')
const draftLink = page.locator('.el-table__body .el-link, .el-table__body a').filter({
  hasText: /Owner Demo/,
}).first()
if (await draftLink.count()) {
  await draftLink.click()
  await page.waitForTimeout(3500)
  await shot(page, 'owner-process-start-from-draft')
  await page.locator('button').filter({ hasText: /Submit Application|提交申请|提交申請/ }).last().click()
  await page.waitForTimeout(5000)
  await shot(page, 'owner-after-submit')
}

await ctx.close()
await browser.close()
console.log('done')
