#!/usr/bin/env node
/**
 * Screenshot verification for type-aware column filters on the Delegations lists.
 *
 * The header filter dialog used to show one free-text box and the same eight string
 * operators for every column. It now asks the backend what each column is
 * (`GET /delegations/columns`, `GET /delegations/audit/columns`) and renders the matching
 * control: a person picker for user columns, a code picker for enums, and a day picker
 * with calendar-day operators for timestamps.
 *
 * Usage (from frontend/):
 *   node scripts/verify-portal-typed-column-filters.mjs
 *   PLAYWRIGHT_CHANNEL=chrome node scripts/verify-portal-typed-column-filters.mjs
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

/** Open the Nth column's dropdown and click "Filter by"; returns the dialog locator. */
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

/** Escape first dismisses any open select popper, so close the dialog by its own button. */
async function closeDialog(page) {
  await page.keyboard.press('Escape')
  await page.waitForTimeout(300)
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
  await page.waitForTimeout(300)
}

/** Operator labels currently offered by the open dialog. */
async function operatorLabels(page, dialog) {
  await dialog.locator('.el-select').first().click()
  await page.waitForTimeout(400)
  const popper = page.locator('.el-select-dropdown:visible').last()
  const labels = await popper.locator('.el-select-dropdown__item').allInnerTexts()
  await page.keyboard.press('Escape')
  await page.waitForTimeout(300)
  return labels.map((l) => l.trim())
}

function expectOperators(actual, expected, column) {
  const missing = expected.filter((e) => !actual.includes(e))
  if (missing.length) {
    throw new Error(`${column}: operators ${JSON.stringify(missing)} missing from ${JSON.stringify(actual)}`)
  }
}

/** Calendar day of every row in the audit table's TIME column (5th). */
async function auditDays(page, scope) {
  void page
  const cells = await scope.locator('.el-table__body-wrapper tr td:nth-child(5) .cell').allInnerTexts()
  return cells.map((c) => c.trim().slice(0, 10)).filter((d) => /^\d{4}-\d{2}-\d{2}$/.test(d))
}

function expectNoOperators(actual, forbidden, column) {
  const present = forbidden.filter((f) => actual.includes(f))
  if (present.length) {
    throw new Error(`${column}: text operators ${JSON.stringify(present)} should not be offered`)
  }
}

async function main() {
  mkdirSync(OUT_DIR, { recursive: true })
  const browser = await launchChromium()
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } })
  const saved = []

  try {
    await loginViaPortalPassword(page)

    console.log('Delegations — my rules…')
    await page.goto(`${ORIGIN}/portal/delegations`, { waitUntil: 'domcontentloaded' })
    await page.locator('.portal-list-col-header').first().waitFor({ timeout: 30000 })
    await page.waitForTimeout(2000)
    saved.push(await shoot(page, 'delegations-typed-filters-list'))

    // The declarations must have reached the page; without them every column would be text.
    const meta = await page.evaluate(async () => {
      const res = await fetch('/api/portal/delegations/columns', { credentials: 'include' })
      return res.json()
    })
    const columns = meta?.data ?? []
    const kinds = Object.fromEntries(columns.map((c) => [c.field, c.kind]))
    console.log(`  column kinds: ${JSON.stringify(kinds)}`)
    if (kinds.delegateId !== 'USER') throw new Error('delegateId is not declared as a USER column')
    if (kinds.delegationType !== 'ENUM') throw new Error('delegationType is not declared as an ENUM column')
    if (kinds.startTime !== 'DATETIME') throw new Error('startTime is not declared as a DATETIME column')

    // A text operator on a timestamp column used to be dropped and the page served
    // unfiltered; it must now come back as a refusal.
    const rejected = await page.evaluate(async () => {
      const filters = encodeURIComponent(JSON.stringify({ createdAt: { operator: 'contains', value: '2026' } }))
      const res = await fetch(`/api/portal/delegations/audit?page=0&size=20&filters=${filters}`, {
        credentials: 'include',
      })
      return res.status
    })
    console.log(`  contains-on-timestamp → HTTP ${rejected}`)
    if (rejected !== 400) {
      throw new Error(`Unsupported operator should be rejected with 400, got ${rejected}`)
    }

    // Column order on this list: delegateId, delegationType, startTime, endTime, status.
    // Both tab panes stay in the DOM, so column indexes must be scoped to the visible one.
    const pane = () => page.locator('.el-tab-pane:visible').first()

    const userDialog = await openFilterDialog(page, pane(), 0)
    const userOps = await operatorLabels(page, userDialog)
    expectOperators(userOps, ['Equals', 'Does not equal'], 'delegateId')
    expectNoOperators(userOps, ['Contains', 'Begins with'], 'delegateId')
    await userDialog.locator('.el-select').last().click()
    await page.waitForTimeout(900)
    saved.push(await shoot(page, 'delegations-filter-user-picker'))
    await closeDialog(page)

    const enumDialog = await openFilterDialog(page, pane(), 1)
    const enumOps = await operatorLabels(page, enumDialog)
    expectNoOperators(enumOps, ['Contains'], 'delegationType')
    await enumDialog.locator('.el-select').last().click()
    await page.waitForTimeout(600)
    saved.push(await shoot(page, 'delegations-filter-enum-picker'))
    await closeDialog(page)

    const dateDialog = await openFilterDialog(page, pane(), 2)
    const dateOps = await operatorLabels(page, dateDialog)
    expectOperators(dateOps, ['On', 'Before', 'After', 'Between'], 'startTime')
    expectNoOperators(dateOps, ['Contains', 'Equals'], 'startTime')
    if (!(await dateDialog.locator('.el-date-editor').count())) {
      throw new Error('startTime filter does not render a date picker')
    }
    saved.push(await shoot(dateDialog, 'delegations-filter-date-picker'))

    // Between must swap the single day picker for a range picker.
    await dateDialog.locator('.el-select').first().click()
    await page.waitForTimeout(400)
    await page.locator('.el-select-dropdown:visible').last().getByText('Between', { exact: true }).click()
    await page.waitForTimeout(600)
    if (!(await dateDialog.locator('.el-range-editor').count())) {
      throw new Error('Between operator does not render a date range picker')
    }
    saved.push(await shoot(dateDialog, 'delegations-filter-date-range'))
    await closeDialog(page)

    console.log('Delegations — audit records…')
    await page.getByRole('tab', { name: /Audit|審計|审计/ }).click()
    await page.waitForTimeout(2500)
    saved.push(await shoot(page, 'delegations-audit-typed-filters-list'))

    // Audit column order: operationType, delegatorId, delegateId, operationResult, createdAt.
    const auditDate = await openFilterDialog(page, pane(), 4)
    const auditOps = await operatorLabels(page, auditDate)
    expectOperators(auditOps, ['On', 'Before', 'After', 'Between'], 'createdAt')
    saved.push(await shoot(auditDate, 'delegations-audit-filter-date-picker'))
    await closeDialog(page)

    // A day filter must actually reach SQL, not just render: pick the newest row's day
    // and every remaining row must fall on it.
    const daysBefore = await auditDays(page, pane())
    const target = daysBefore[0]
    const distinct = new Set(daysBefore).size
    console.log(`  audit rows span ${distinct} day(s); filtering on ${target}`)
    if (distinct < 2) {
      console.warn('  (only one day present — the filter can only be shown to keep matching rows)')
    }

    const applyDialog = await openFilterDialog(page, pane(), 4)
    await applyDialog.locator('.el-date-editor input').fill(target)
    await page.keyboard.press('Enter')
    await page.waitForTimeout(400)
    await applyDialog.getByRole('button', { name: 'Confirm' }).click()
    await page.waitForTimeout(2500)

    const daysAfter = await auditDays(page, pane())
    console.log(`  ${daysBefore.length} rows → ${daysAfter.length} rows on ${target}`)
    if (!daysAfter.length) throw new Error(`Day filter on ${target} returned no rows`)
    const stray = daysAfter.filter((d) => d !== target)
    if (stray.length) throw new Error(`Day filter kept rows from other days: ${JSON.stringify(stray)}`)
    saved.push(await shoot(page, 'delegations-audit-filtered-by-day'))

    console.log(`\nOK — ${saved.length} screenshots written to ${OUT_DIR}`)
  } finally {
    await browser.close()
  }
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
