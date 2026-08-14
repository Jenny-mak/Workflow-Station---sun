#!/usr/bin/env node
/**
 * Screenshot verification for the unified portal list column header.
 *
 * Views (Main Table View) used to render its own `MainTableViewColumnMenu` +
 * a separate resize handle + a view-level width dialog. It now renders the same
 * `PortalListColumnHeader` the six portal lists use. This script proves the two
 * surfaces render the identical menu and that the Views grid kept its layout.
 *
 * Usage (from frontend/):
 *   node scripts/verify-portal-column-header-unify.mjs
 *   PLAYWRIGHT_CHANNEL=chrome node scripts/verify-portal-column-header-unify.mjs
 */

import { mkdirSync } from 'fs'
import { dirname, join, resolve } from 'path'
import { fileURLToPath } from 'url'
import { loginViaPortalPassword } from './playwright-login.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const OUT_DIR = join(resolve(__dirname, '..'), 'user-portal', 'verification-screenshots')
const ORIGIN = process.env.PORTAL_ORIGIN ?? 'http://localhost:3000'

function datePrefix() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

async function loadPlaywright() {
  try {
    return await import('playwright')
  } catch {
    throw new Error(
      'playwright is not installed. From frontend/ run:\n' +
        '  pnpm install\n' +
        '  pnpm exec playwright install chromium',
    )
  }
}

async function launchBrowser() {
  const { chromium } = await loadPlaywright()
  const launchOpts = { headless: true }
  if (process.env.PLAYWRIGHT_EXECUTABLE_PATH) {
    launchOpts.executablePath = process.env.PLAYWRIGHT_EXECUTABLE_PATH
  } else if (process.env.PLAYWRIGHT_CHANNEL) {
    launchOpts.channel = process.env.PLAYWRIGHT_CHANNEL
  }
  return chromium.launch(launchOpts)
}

async function shoot(target, name) {
  const file = join(OUT_DIR, `${datePrefix()}_${name}.png`)
  await target.screenshot({ path: file })
  console.log(`  saved ${file}`)
  return file
}

/** Open the column dropdown of the Nth header and return the popup locator. */
async function openColumnMenu(page, index) {
  const trigger = page.locator('.portal-list-col-trigger').nth(index)
  await trigger.waitFor({ state: 'visible', timeout: 15000 })
  await trigger.click()
  const menu = page.locator('.el-dropdown-menu:visible').last()
  await menu.waitFor({ state: 'visible', timeout: 10000 })
  await page.waitForTimeout(300)
  return menu
}

async function closeMenu(page) {
  await page.keyboard.press('Escape')
  await page.mouse.click(5, 5)
  await page.waitForTimeout(300)
}

async function main() {
  mkdirSync(OUT_DIR, { recursive: true })
  const browser = await launchBrowser()
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } })
  const saved = []

  try {
    await loginViaPortalPassword(page)

    console.log('Views grid…')
    await page.goto(`${ORIGIN}/portal/views`, { waitUntil: 'domcontentloaded' })
    await page.locator('.view-list-panel .el-menu-item').first().waitFor({ timeout: 30000 })
    await page.locator('.view-list-panel .el-menu-item').first().click()
    await page.locator('.mtv-data-grid').waitFor({ timeout: 30000 })
    await page.waitForTimeout(2500)

    const unified = await page.locator('.mtv-data-grid .portal-list-col-header').count()
    const legacy = await page.locator('.col-header-cell').count()
    const handles = await page.locator('.mtv-data-grid .col-resize-handle').count()
    console.log(`  portal-list-col-header=${unified} legacy col-header-cell=${legacy} resize-handles=${handles}`)
    if (unified === 0) throw new Error('Views grid renders no .portal-list-col-header')
    if (legacy !== 0) throw new Error(`Legacy .col-header-cell still rendered (${legacy})`)
    if (handles === 0) throw new Error('Views grid lost its column resize handles')

    saved.push(await shoot(page, 'mtv-unified-header-row'))

    const viewsMenu = await openColumnMenu(page, 0)
    saved.push(await shoot(viewsMenu, 'mtv-unified-header-menu'))

    await viewsMenu.getByText('Column width', { exact: true }).click()
    const widthDialog = page.locator('.el-dialog:visible').last()
    await widthDialog.waitFor({ state: 'visible', timeout: 10000 })
    await page.waitForTimeout(500)
    saved.push(await shoot(widthDialog, 'mtv-unified-width-dialog'))
    await page.keyboard.press('Escape')
    await page.waitForTimeout(500)

    console.log('My Applications list…')
    await page.goto(`${ORIGIN}/portal/my-applications`, { waitUntil: 'domcontentloaded' })
    await page.locator('.portal-list-col-header').first().waitFor({ timeout: 30000 })
    await page.waitForTimeout(2500)
    saved.push(await shoot(page, 'applications-header-row'))

    const appsMenu = await openColumnMenu(page, 0)
    saved.push(await shoot(appsMenu, 'applications-header-menu'))
    await closeMenu(page)

    console.log(`\nOK — ${saved.length} screenshots written to ${OUT_DIR}`)
  } finally {
    await browser.close()
  }
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
