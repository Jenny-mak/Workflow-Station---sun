/**
 * Admin Audit list viewport: table fills leftover height so Element Plus owns
 * the scrollbars; pagination sits at the viewport bottom. Run after rebuilding
 * admin-center-frontend.
 *
 * Usage (from frontend/):
 *   node scripts/verify-admin-audit-list-viewport.mjs
 *
 * Output: admin-center/verification-screenshots/{date}_admin-audit-*-viewport.png
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
const MIN_GRID_HEIGHT_PX = 220
/** admin-main 24px + page-container 20px padding, plus a few pixels of slack. */
const MAX_PAGER_FROM_BOTTOM_PX = 56

mkdirSync(OUT, { recursive: true })

function check(label, ok, detail) {
  console.log(`${ok ? '[PASS]' : '[FAIL]'} ${label}${detail ? ` — ${detail}` : ''}`)
  if (!ok) throw new Error(`${label}${detail ? `: ${detail}` : ''}`)
}

async function waitUntilLayoutPinned(page, grid, pager) {
  const gridEl = await grid.elementHandle()
  const pagerEl = await pager.elementHandle()
  if (!gridEl || !pagerEl) {
    throw new Error('grid or pagination handle missing')
  }
  await page.waitForFunction(
    ([gridNode, pagerNode, minGrid, maxFromBottom]) => {
      const loading = document.querySelector('.page-container .el-loading-mask')
      if (loading) {
        const style = window.getComputedStyle(loading)
        if (style.display !== 'none' && style.visibility !== 'hidden') return false
      }
      const gr = gridNode.getBoundingClientRect()
      const pr = pagerNode.getBoundingClientRect()
      const table = gridNode.querySelector('.el-table')
      if (!table) return false
      const tableH = table.getBoundingClientRect().height
      const pagerFromBottom = window.innerHeight - pr.bottom
      const inner = gridNode.querySelector('.el-table__inner-wrapper')
      const wrap = gridNode.querySelector('.el-scrollbar__wrap')
        || gridNode.querySelector('.el-table__body-wrapper')
      const innerH = inner instanceof HTMLElement ? getComputedStyle(inner).height : ''
      const constrained = innerH.endsWith('px') && parseFloat(innerH) > minGrid
        && wrap instanceof HTMLElement && wrap.clientHeight > minGrid * 0.5
      return gr.height > minGrid
        && tableH > minGrid
        && constrained
        && pagerFromBottom >= -2
        && pagerFromBottom <= maxFromBottom
    },
    [gridEl, pagerEl, MIN_GRID_HEIGHT_PX, MAX_PAGER_FROM_BOTTOM_PX],
    { timeout: 20000 },
  )
}

const PAGES = [
  { slug: 'admin-audit-admin-center-viewport', path: '/admin/audit/admin-center' },
  { slug: 'admin-audit-user-portal-viewport', path: '/admin/audit/user-portal' },
]

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const page = await (await browser.newContext({ viewport: VIEWPORT })).newPage()

try {
  await loginViaAdminPassword(page, { loginOrigin: ORIGIN })

  for (const item of PAGES) {
    const url = `${ORIGIN}${item.path}`
    console.log(`[goto] ${url}`)
    await page.goto(url, { waitUntil: 'domcontentloaded' })
    const grid = page.locator('.page-container .list-data-grid-scroll').last()
    const pager = page.locator('.page-container .list-pagination').last()
    await grid.waitFor({ timeout: 20000 })
    await pager.waitFor({ timeout: 20000 })
    await grid.locator('.el-table__header').waitFor({ state: 'visible', timeout: 20000 })
    await waitUntilLayoutPinned(page, grid, pager)

    const shot = join(OUT, `${DATE}_${item.slug}.png`)
    await page.screenshot({ path: shot })
    console.log(`[SHOT] ${shot}`)

    const metrics = await page.evaluate(() => {
      const gridNode = document.querySelector('.page-container .list-data-grid-scroll')
      const pagerNode = document.querySelector('.page-container .list-pagination')
      const table = gridNode?.querySelector('.el-table')
      const gr = gridNode?.getBoundingClientRect()
      const pr = pagerNode?.getBoundingClientRect()
      const inner = gridNode?.querySelector('.el-table__inner-wrapper')
      const wrap = gridNode?.querySelector('.el-scrollbar__wrap')
        || gridNode?.querySelector('.el-table__body-wrapper')
      const innerH = inner instanceof HTMLElement ? getComputedStyle(inner).height : ''
      return {
        gridHeight: gr?.height ?? 0,
        tableHeight: table?.getBoundingClientRect().height ?? 0,
        pagerFromBottom: pr ? window.innerHeight - pr.bottom : null,
        hasTableCard: document.querySelectorAll('.page-container .table-card .list-data-grid-scroll').length > 0,
        headerVisible: !!gridNode?.querySelector('.el-table__header'),
        innerHeightCss: innerH,
        wrapClientHeight: wrap instanceof HTMLElement ? wrap.clientHeight : 0,
      }
    })

    check(`${item.slug} uses table-card host`, metrics.hasTableCard)
    check(`${item.slug} table header visible`, metrics.headerVisible)
    check(
      `${item.slug} grid pane taller than a 2-row hug`,
      metrics.gridHeight > MIN_GRID_HEIGHT_PX,
      `gridHeight=${metrics.gridHeight.toFixed(1)}`,
    )
    check(
      `${item.slug} pagination at viewport bottom`,
      metrics.pagerFromBottom != null
        && metrics.pagerFromBottom >= -2
        && metrics.pagerFromBottom <= MAX_PAGER_FROM_BOTTOM_PX,
      `pagerFromBottom=${metrics.pagerFromBottom?.toFixed(1)}`,
    )
    check(
      `${item.slug} constrained table body`,
      typeof metrics.innerHeightCss === 'string'
        && metrics.innerHeightCss.endsWith('px')
        && parseFloat(metrics.innerHeightCss) > MIN_GRID_HEIGHT_PX
        && metrics.wrapClientHeight > MIN_GRID_HEIGHT_PX * 0.5
        && metrics.tableHeight > MIN_GRID_HEIGHT_PX,
      `tableHeight=${metrics.tableHeight.toFixed(1)} innerHeightCss=${metrics.innerHeightCss} wrapClientHeight=${metrics.wrapClientHeight}`,
    )
  }
} finally {
  await browser.close()
}

console.log('[OK] both Admin Audit menus fill leftover viewport with table scrollbars')
