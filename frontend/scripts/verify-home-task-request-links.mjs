#!/usr/bin/env node

import { chromium } from 'playwright'
import { loginViaPortalPassword } from './playwright-login.mjs'

const portalOrigin = process.env.PORTAL_ORIGIN || 'http://localhost:3101'
const homeUrl = `${portalOrigin}/portal/dashboard`
const channel = process.env.PLAYWRIGHT_CHANNEL || 'chrome'

function check(condition, label, detail = '') {
  if (!condition) {
    throw new Error(`${label}${detail ? `: ${detail}` : ''}`)
  }
  console.log(`PASS ${label}${detail ? ` (${detail})` : ''}`)
}

function pathnameOf(href) {
  return new URL(href, portalOrigin).pathname
}

const browser = await chromium.launch({ headless: true, channel })
const page = await (await browser.newContext({ viewport: { width: 1400, height: 1000 } })).newPage()

try {
  await loginViaPortalPassword(page)
  await page.goto(homeUrl, { waitUntil: 'domcontentloaded' })
  await page.locator('.task-overview-card').waitFor({ state: 'visible' })

  check(await page.locator('.command-primary').count() === 0, 'Top New Request button is removed')
  check(await page.locator('.page-heading-tools .command').count() === 1, 'Refresh is placed in the page heading tools')

  const taskOverviewPaths = await page.locator('.task-overview-card a').evaluateAll((links) =>
    links.map((link) => new URL(link.href).pathname))
  check(
    JSON.stringify(taskOverviewPaths) === JSON.stringify([
      '/portal/tasks',
      '/portal/tasks',
      '/portal/tasks/completed',
    ]),
    'Tasks card links stay in To Do routes',
    taskOverviewPaths.join(', '),
  )
  check(
    await page.locator('.task-overview-card .task-figure').count() === 2,
    'Tasks card keeps its two original metric links',
  )
  const taskMetricValue = page.locator('.task-overview-card .task-figure-value').first()
  const requestMetricValue = page.locator('.ledger-half').first().locator('.figure-num').first()
  const taskMetricSize = await taskMetricValue.evaluate((el) => getComputedStyle(el).fontSize)
  const requestMetricSize = await requestMetricValue.evaluate((el) => getComputedStyle(el).fontSize)
  check(taskMetricSize === requestMetricSize, 'Task and My Requests numbers use the same size', taskMetricSize)
  const taskMetricColor = await taskMetricValue.evaluate((el) => getComputedStyle(el).color)
  await page.locator('.task-overview-card .task-figure').first().hover()
  await page.waitForTimeout(200)
  const taskMetricHoverColor = await taskMetricValue.evaluate((el) => getComputedStyle(el).color)
  check(taskMetricHoverColor !== taskMetricColor, 'Task number changes color on hover', taskMetricHoverColor)

  const quickActionPaths = await page.locator('.quick-actions-card a').evaluateAll((links) =>
    links.map((link) => new URL(link.href).pathname))
  const expectedQuickActionPaths = [
    '/portal/processes',
    '/portal/delegations',
    '/portal/permissions',
  ]
  check(
    JSON.stringify(quickActionPaths) === JSON.stringify(expectedQuickActionPaths),
    'Quick Actions expose the expected destinations',
    quickActionPaths.join(', '),
  )

  for (let index = 0; index < expectedQuickActionPaths.length; index += 1) {
    const expectedPath = expectedQuickActionPaths[index]
    await page.locator('.quick-actions-card a').nth(index).click()
    await page.waitForURL((url) => url.pathname === expectedPath)
    check(new URL(page.url()).pathname === expectedPath, 'Quick Action navigates correctly', expectedPath)
    await page.goto(homeUrl, { waitUntil: 'domcontentloaded' })
    await page.locator('.quick-actions-card').waitFor({ state: 'visible' })
  }

  const recentTaskBlock = page.locator('section.block').first()
  check(
    (await recentTaskBlock.locator('.block-title').textContent())?.trim() === 'Need Your Action',
    'Recent task block uses the action-oriented title',
  )
  await recentTaskBlock.locator('.task-table').waitFor({ state: 'visible' })
  const recentTaskHeaders = (await recentTaskBlock.locator('th').allTextContents()).map((text) => text.trim())
  check(
    recentTaskHeaders.includes('Function Unit') && !recentTaskHeaders.includes('Priority'),
    'Recent task table shows Function Unit instead of Priority',
    recentTaskHeaders.join(', '),
  )
  check(
    pathnameOf(await recentTaskBlock.locator('.block-link').getAttribute('href')) === '/portal/tasks',
    'Need Your Action View All opens To Do',
  )
  await recentTaskBlock.locator('.data-row').first().click()
  await page.locator('.task-detail-page').waitFor({ state: 'visible' })
  check(/^\/portal\/tasks\/[^/]+$/.test(new URL(page.url()).pathname), 'Recent task opens task detail', page.url())
  check(await page.locator('.application-detail-page').count() === 0, 'Recent task does not render My Request form')

  await page.goto(homeUrl, { waitUntil: 'domcontentloaded' })
  await page.locator('.ledger').waitFor({ state: 'visible' })
  const myRequestPaths = await page.locator('.ledger-half').first().locator('a').evaluateAll((links) =>
    links.map((link) => `${new URL(link.href).pathname}${new URL(link.href).search}`))
  check(
    myRequestPaths.every((path) => path.startsWith('/portal/my-applications')),
    'My Requests summary links stay in My Requests',
    myRequestPaths.join(', '),
  )

  const recentRequestBlock = page.locator('section.block').nth(1)
  check(
    pathnameOf(await recentRequestBlock.locator('.block-link').getAttribute('href')) === '/portal/my-applications',
    'My Recent Requests View All opens My Requests',
  )
  await recentRequestBlock.locator('.data-row').first().click()
  await page.locator('.application-detail-page').waitFor({ state: 'visible' })
  check(
    /^\/portal\/applications\/[^/]+$/.test(new URL(page.url()).pathname),
    'Recent request opens My Request detail',
    page.url(),
  )
  check(await page.locator('.task-detail-page').count() === 0, 'Recent request does not render To Do task form')
} finally {
  await browser.close()
}
