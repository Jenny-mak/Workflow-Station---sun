/**
 * Relation Tables Function Unit rails list one entry per Function Unit *code*, not one per
 * published catalog version. Before the fix both rails grouped on sys_function_units.id, so a unit
 * republished N times showed N identical entries ("ACQ" four times).
 *
 * Usage (from frontend/):
 *   node scripts/verify-admin-relation-table-fu-rail.mjs
 *
 * Output: admin-center/verification-screenshots/{date}_admin-rt-fu-rail-*.png
 */
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { loginViaAdminPassword } from './playwright-login.mjs'

const ORIGIN = process.env.ORIGIN ?? 'http://localhost:3000'
const OUT = join(dirname(fileURLToPath(import.meta.url)), '../admin-center/verification-screenshots')
const DATE = new Date().toISOString().slice(0, 10)
const VIEWPORT = { width: 1400, height: 900 }

mkdirSync(OUT, { recursive: true })

function check(label, ok, detail) {
  console.log(`${ok ? '[PASS]' : '[FAIL]'} ${label}${detail ? ` — ${detail}` : ''}`)
  if (!ok) throw new Error(`${label}${detail ? `: ${detail}` : ''}`)
}

function duplicates(values) {
  const seen = new Set()
  const dupes = new Set()
  for (const value of values) {
    if (seen.has(value)) dupes.add(value)
    seen.add(value)
  }
  return [...dupes]
}

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const page = await (await browser.newContext({ viewport: VIEWPORT })).newPage()

try {
  await loginViaAdminPassword(page, { loginOrigin: ORIGIN })

  // ---- Table Data nav groups come back keyed by code, with no per-version id ----
  const groups = await page.evaluate(async (origin) => {
    const res = await fetch(`${origin}/api/v1/admin/relation-tables/data/function-units`, {
      credentials: 'include',
    })
    if (!res.ok) throw new Error(`function-units returned ${res.status}`)
    return res.json()
  }, ORIGIN)
  console.log(`[api] ${groups.length} Table Data group(s): ${JSON.stringify(groups)}`)
  check(
    'Table Data groups carry no per-version functionUnitId',
    groups.every(g => g.functionUnitId === undefined),
  )
  check(
    'Table Data groups are unique per code',
    duplicates(groups.map(g => g.functionUnitCode)).length === 0,
    `dupes=${duplicates(groups.map(g => g.functionUnitCode)).join(', ')}`,
  )
  check(
    'Table Data group names are unique too',
    duplicates(groups.map(g => g.functionUnitName ?? g.functionUnitCode)).length === 0,
    `dupes=${duplicates(groups.map(g => g.functionUnitName ?? g.functionUnitCode)).join(', ')}`,
  )

  // ---- Table Structure left rail ----
  await page.goto(`${ORIGIN}/admin/relation-tables/structure`, { waitUntil: 'domcontentloaded' })
  const rail = page.locator('.fu-list-panel')
  await rail.waitFor({ timeout: 20000 })
  await rail.locator('.group-title').first().waitFor({ timeout: 20000 })
  const railLabels = await rail.locator('.group-title').allInnerTexts()
  console.log(`[rail] ${railLabels.length} group(s): ${railLabels.join(' | ')}`)
  check(
    'Table Structure rail has no repeated Function Unit',
    duplicates(railLabels).length === 0,
    `dupes=${duplicates(railLabels).join(', ')}`,
  )

  const railShot = join(OUT, `${DATE}_admin-rt-fu-rail-structure.png`)
  await rail.screenshot({ path: railShot })
  console.log(`[SHOT] ${railShot}`)
  const structureShot = join(OUT, `${DATE}_admin-rt-fu-rail-structure-page.png`)
  await page.screenshot({ path: structureShot })
  console.log(`[SHOT] ${structureShot}`)

  // ---- Selecting a code-keyed rail entry still filters the list ----
  const firstFu = rail.locator('.el-menu-item').nth(1)
  if (await firstFu.count()) {
    await firstFu.click()
    await page.waitForTimeout(1500)
    const rows = await page.locator('.table-card .el-table__body-wrapper tr.el-table__row').count()
    console.log(`[filter] "${railLabels[0]}" -> ${rows} row(s)`)
    check('Selecting a code rail entry returns its tables', rows > 0, `rows=${rows}`)
    const filteredShot = join(OUT, `${DATE}_admin-rt-fu-rail-structure-filtered.png`)
    await page.screenshot({ path: filteredShot })
    console.log(`[SHOT] ${filteredShot}`)
  }

  // ---- Table Data nav sub-menu ----
  await page.getByRole('menuitem', { name: 'Relation Tables', exact: true }).click()
  await page.waitForTimeout(400)
  await page.getByRole('menuitem', { name: 'Table Data', exact: true }).click()
  await page.waitForTimeout(600)
  const navLabels = (
    await page.locator('.el-sub-menu .el-menu--inline .el-menu-item').allInnerTexts()
  )
    .map(text => text.trim())
    .filter(text => text && text !== 'All Function Units' && text !== 'Table Structure')
  console.log(`[nav] Table Data entries: ${navLabels.join(' | ')}`)
  check(
    'Table Data nav has no repeated Function Unit',
    duplicates(navLabels).length === 0,
    `dupes=${duplicates(navLabels).join(', ')}`,
  )

  const navShot = join(OUT, `${DATE}_admin-rt-fu-rail-table-data-nav.png`)
  await page.locator('.el-menu--vertical').first().screenshot({ path: navShot })
  console.log(`[SHOT] ${navShot}`)
} finally {
  await browser.close()
}

console.log('[OK] both Relation Tables Function Unit rails list one entry per code')
