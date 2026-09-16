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

  const recentTaskBlock = page.locator('section.block').first()
  check(
    (await recentTaskBlock.locator('.block-title').textContent())?.trim() === 'Need Your Action',
    'Recent task block uses the action-oriented title',
  )
  const recentTaskHeaders = (await recentTaskBlock.locator('th').allTextContents()).map((text) => text.trim())
  check(
    recentTaskHeaders.includes('Function Unit') && !recentTaskHeaders.includes('Priority'),
    'Recent task table shows Function Unit instead of Priority',
    recentTaskHeaders.join(', '),
  )
  check(
    pathnameOf(await recentTaskBlock.locator('.block-link').getAttribute('href')) === '/portal/tasks',
    'My Recent Tasks View All opens To Do',
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
