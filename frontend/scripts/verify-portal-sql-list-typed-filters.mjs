#!/usr/bin/env node
/**
 * Screenshot verification for type-aware column filters on Applications, Drafts
 * and Permissions (the SQL-backed lists that PR-B did not wire).
 *
 * Usage (from frontend/):
 *   node scripts/verify-portal-sql-list-typed-filters.mjs
 */

import { mkdirSync } from 'fs'
import { dirname, join, resolve } from 'path'
import { fileURLToPath } from 'url'
import { launchChromium } from './playwright-browser.mjs'
import { loginViaPortalPassword } from './playwright-login.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const OUT_DIR = join(resolve(__dirname, '..'), 'user-portal', 'verification-screenshots')
const ORIGIN = process.env.PORTAL_ORIGIN ?? 'http://localhost:3000'

function datePrefix() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

async function shoot(target, name) {
  const file = join(OUT_DIR, `${datePrefix()}_${name}.png`)
  await target.screenshot({ path: file })
  console.log(`  saved ${file}`)
  return file
}

async function openFilterDialog(page, scope, index) {
  const trigger = scope.locator('.portal-list-col-trigger').nth(index)
  await trigger.waitFor({ state: 'visible', timeout: 20000 })
  await trigger.click()
  const menu = page.locator('.el-dropdown-menu:visible').last()
  await menu.waitFor({ state: 'visible', timeout: 10000 })
  await menu.getByText('Filter by', { exact: true }).click()
  const dialog = page.locator('.el-dialog:visible').last()
  await dialog.waitFor({ state: 'visible', timeout: 10000 })
  await page.waitForTimeout(600)
  return dialog
}

/** Click X on a visible dialog. Do not remove overlays — Element Plus reuses them. */
async function closeDialog(page) {
  const dialog = page.locator('.el-dialog:visible').last()
  if (await dialog.count()) {
    await dialog.locator('.el-dialog__headerbtn').click()
  }
  for (let i = 0; i < 25 && (await page.locator('.el-dialog:visible').count()); i += 1) {
    await page.waitForTimeout(200)
  }
  if (await page.locator('.el-dialog:visible').count()) {
    throw new Error('Filter dialog did not close')
  }
  await page.waitForTimeout(400)
}

/** Close the operator dropdown without dismissing the filter dialog (Escape would). */
async function operatorLabels(page, dialog) {
  await dialog.locator('.el-select').first().click()
  await page.waitForTimeout(400)
  const popper = page.locator('.el-select-dropdown:visible').last()
  const labels = await popper.locator('.el-select-dropdown__item').allInnerTexts()
  await dialog.locator('.el-dialog__title').click()
  await page.waitForTimeout(300)
  return labels.map((l) => l.trim())
}

function expectOperators(actual, expected, column) {
  const missing = expected.filter((e) => !actual.includes(e))
  if (missing.length) {
    throw new Error(`${column}: operators ${JSON.stringify(missing)} missing from ${JSON.stringify(actual)}`)
  }
}

function expectNoOperators(actual, forbidden, column) {
  const present = forbidden.filter((f) => actual.includes(f))
  if (present.length) {
    throw new Error(`${column}: text operators ${JSON.stringify(present)} should not be offered`)
  }
}

async function fetchColumns(page, path) {
  return page.evaluate(async (p) => {
    const res = await fetch(p, { credentials: 'include' })
    const body = await res.json()
    return { status: res.status, columns: body?.data ?? [] }
  }, path)
}

async function main() {
  mkdirSync(OUT_DIR, { recursive: true })
  const browser = await launchChromium()
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } })
  const saved = []

  try {
    await loginViaPortalPassword(page)

    console.log('Applications…')
    await page.goto(`${ORIGIN}/portal/my-applications`, { waitUntil: 'domcontentloaded' })
    await page.locator('.portal-list-col-header').first().waitFor({ timeout: 30000 })
    await page.waitForTimeout(2000)
    saved.push(await shoot(page, 'sql-lists-applications'))

    const appMeta = await fetchColumns(page, '/api/portal/processes/my-applications/columns')
    if (appMeta.status !== 200) throw new Error(`application columns HTTP ${appMeta.status}`)
    const appKinds = Object.fromEntries(appMeta.columns.map((c) => [c.field, c.kind]))
    console.log(`  application kinds: ${JSON.stringify(appKinds)}`)
    if (appKinds.startTime !== 'DATETIME') throw new Error('startTime is not DATETIME')
    if (appKinds.status !== 'ENUM') throw new Error('status is not ENUM')

    const rejected = await page.evaluate(async () => {
      const filters = encodeURIComponent(JSON.stringify({ startTime: { operator: 'contains', value: '2026' } }))
      const res = await fetch(`/api/portal/processes/my-applications?page=0&size=20&filters=${filters}`, {
        credentials: 'include',
      })
      return res.status
    })
    console.log(`  contains-on-startTime → HTTP ${rejected}`)
    if (rejected !== 400) {
      throw new Error(`Unsupported operator should be rejected with 400, got ${rejected}`)
    }

    const appTable = () => page.locator('.application-table')
    // requestId, businessKey, currentStepName, currentAssignee, startTime, status
    const dateDialog = await openFilterDialog(page, appTable(), 4)
    const dateOps = await operatorLabels(page, dateDialog)
    expectOperators(dateOps, ['On', 'Before', 'After', 'Between'], 'startTime')
    expectNoOperators(dateOps, ['Contains'], 'startTime')
    saved.push(await shoot(page, 'sql-lists-applications-filter-date'))
    await closeDialog(page)

    const statusDialog = await openFilterDialog(page, appTable(), 5)
    expectNoOperators(await operatorLabels(page, statusDialog), ['Contains'], 'status')
    saved.push(await shoot(page, 'sql-lists-applications-filter-enum'))
    await closeDialog(page)

    console.log('Drafts…')
    await page.getByRole('tab', { name: /Draft/i }).click()
    await page.waitForTimeout(1500)
    await page.getByText('Save Time').first().waitFor({ timeout: 20000 })
    saved.push(await shoot(page, 'sql-lists-drafts'))

    const draftMeta = await fetchColumns(page, '/api/portal/processes/drafts/columns')
    if (draftMeta.status !== 200) throw new Error(`draft columns HTTP ${draftMeta.status}`)
    const draftKinds = Object.fromEntries(draftMeta.columns.map((c) => [c.field, c.kind]))
    if (draftKinds.updatedAt !== 'DATETIME') throw new Error('updatedAt is not DATETIME')

    const saveTimeHeader = page.locator('.portal-list-col-header').filter({ hasText: 'Save Time' })
    const draftDialog = await openFilterDialog(page, saveTimeHeader, 0)
    expectOperators(await operatorLabels(page, draftDialog), ['On', 'Before'], 'updatedAt')
    saved.push(await shoot(page, 'sql-lists-drafts-filter-date'))
    await closeDialog(page)

    console.log('Permissions…')
    await page.goto(`${ORIGIN}/portal/permissions`, { waitUntil: 'domcontentloaded' })
    await page.locator('.portal-list-col-header').first().waitFor({ timeout: 30000 })
    await page.waitForTimeout(2000)
    saved.push(await shoot(page, 'sql-lists-permissions'))

    const permMeta = await fetchColumns(page, '/api/portal/permissions/requests/columns')
    if (permMeta.status !== 200) throw new Error(`permission columns HTTP ${permMeta.status}`)
    const permKinds = Object.fromEntries(permMeta.columns.map((c) => [c.field, c.kind]))
    console.log(`  permission kinds: ${JSON.stringify(permKinds)}`)
    if (permKinds.requestType !== 'ENUM') throw new Error('requestType is not ENUM')
    if (permKinds.submittedBy !== 'USER') throw new Error('submittedBy is not USER')
    if (permKinds.createdAt !== 'DATETIME') throw new Error('createdAt is not DATETIME')

    const typeDialog = await openFilterDialog(
      page,
      page.locator('.portal-list-col-header:visible').filter({ hasText: 'Request Type' }),
      0,
    )
    expectNoOperators(await operatorLabels(page, typeDialog), ['Contains'], 'requestType')
    saved.push(await shoot(page, 'sql-lists-permissions-filter-enum'))
    await closeDialog(page)

    const createdDialog = await openFilterDialog(
      page,
      page.locator('.portal-list-col-header:visible').filter({ hasText: 'Apply Time' }),
      0,
    )
    expectOperators(await operatorLabels(page, createdDialog), ['On', 'Between'], 'createdAt')
    saved.push(await shoot(page, 'sql-lists-permissions-filter-date'))
    await closeDialog(page)

    console.log(`OK — ${saved.length} screenshots`)
  } finally {
    await browser.close()
  }
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
