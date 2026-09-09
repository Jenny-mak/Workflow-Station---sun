#!/usr/bin/env node
/**
 * Screenshot Email Monitor Field Mapping with Source = Attachments.
 *
 * Usage (from frontend/):
 *   node scripts/verify-dw-email-field-mapping-attachments.mjs
 *
 * Output: developer-workstation/verification-screenshots/{date}_email-field-mapping-attachments.png
 */
import { mkdirSync } from 'fs'
import { dirname, join, resolve } from 'path'
import { fileURLToPath } from 'url'
import { chromium } from 'playwright'
import { loginViaDwPassword } from './playwright-login.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const OUT_DIR = resolve(__dirname, '..', 'developer-workstation', 'verification-screenshots')
const ORIGIN = (process.env.LOGIN_ORIGIN ?? 'http://localhost:3000').replace(/\/$/, '')

function datePrefix() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

async function firstFunctionUnitId(page) {
  const res = await page.request.get(`${ORIGIN}/api/v1/function-units`, {
    params: { page: 0, size: 20 },
  })
  const body = await res.json().catch(() => ({}))
  const rows = body.data?.content ?? body.content ?? body.data ?? []
  const list = Array.isArray(rows) ? rows : []
  const id = list[0]?.id
  if (!id) {
    throw new Error(`No Function Unit found (HTTP ${res.status()})`)
  }
  return id
}

async function clickTab(page, name) {
  const tab = page.getByRole('tab', { name, exact: true })
  if ((await tab.count()) === 0) {
    throw new Error(`Tab not found: ${name}`)
  }
  await tab.first().scrollIntoViewIfNeeded()
  await tab.first().click()
}

async function main() {
  mkdirSync(OUT_DIR, { recursive: true })
  const outPath = join(OUT_DIR, `${datePrefix()}_email-field-mapping-attachments.png`)

  const browser = await chromium.launch({ headless: true })
  const page = await (
    await browser.newContext({ viewport: { width: 1440, height: 1100 } })
  ).newPage()

  try {
    await loginViaDwPassword(page, { loginOrigin: ORIGIN })
    const fuId = await firstFunctionUnitId(page)
    console.log(`[fu] ${fuId}`)

    await page.goto(`${ORIGIN}/dev/function-units/${fuId}`, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(2000)

    await clickTab(page, 'Email Monitors')
    await page.waitForTimeout(1200)

    await page.getByRole('button', { name: 'New Monitor' }).click()
    await page.waitForSelector('.el-dialog', { timeout: 15000 })
    await page.waitForTimeout(800)

    await clickTab(page, 'Field Mapping')
    await page.waitForSelector('.email-field-mapping-table', { timeout: 10000 })
    await page.waitForTimeout(400)

    await page.getByRole('button', { name: 'Add Field' }).click()
    await page.waitForTimeout(400)

    const sourceSelect = page.locator('.email-field-mapping-table .el-table__row .el-select').nth(1)
    await sourceSelect.click()
    await page.waitForTimeout(300)
    const attachmentsOpt = page.locator('.el-select-dropdown__item').filter({ hasText: /^Attachments$/ })
    if ((await attachmentsOpt.count()) === 0) {
      throw new Error('Attachments source option not found')
    }
    await attachmentsOpt.last().click()
    await page.waitForTimeout(500)

    const mapping = page.locator('.email-field-mapping-table')
    await mapping.scrollIntoViewIfNeeded()
    await mapping.screenshot({ path: outPath })
    console.log(`[saved] ${outPath}`)
  } finally {
    await browser.close()
  }
}

main().catch((err) => {
  console.error('[verify-dw-email-field-mapping-attachments] FAILED:', err.message)
  process.exit(1)
})
