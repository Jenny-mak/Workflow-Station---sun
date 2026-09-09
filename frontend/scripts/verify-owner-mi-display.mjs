/**
 * §3.3.3 / §6.7 against existing MI FUs (no schema change):
 * My Requests Current Assignee must show the outer MI box name, not the inner
 * assignee, when the instance is sitting in a multi-instance step.
 */
import { chromium } from 'playwright'
import { loginViaPortalPassword } from './playwright-login.mjs'

const ORIGIN = 'http://localhost:3000'
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const ctx = await browser.newContext()
const page = await ctx.newPage()
await loginViaPortalPassword(page, { user: 'developer', pass: 'password' })
const res = await page.request.fetch(`${ORIGIN}/api/portal/processes/my-applications?page=0&size=50`)
const rows = (await res.json()).data?.content ?? []

const results = []
function check(label, ok, extra) {
  results.push({ ok, label })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${extra ? `  (${extra})` : ''}`)
}

const mi = rows.filter(r => r.status === 'RUNNING' && r.currentStepName
  && /multi|Multi-instance/i.test(r.currentStepName))
const ordinary = rows.filter(r => r.status === 'RUNNING' && r.currentStepName
  && !/multi|Multi-instance/i.test(r.currentStepName))
const done = rows.filter(r => r.status === 'COMPLETED')

check('found at least one running MI instance', mi.length > 0, `n=${mi.length}`)
for (const r of mi) {
  const expected = r.currentStepName
  check(`MI ${r.processDefinitionKey} @ ${r.currentStepName} shows box name, not a person`,
    r.currentAssignee === expected, `assignee=${JSON.stringify(r.currentAssignee)}`)
}
for (const r of ordinary.slice(0, 4)) {
  check(`ordinary ${r.processDefinitionKey} @ ${r.currentStepName} is a person, not step:`,
    !!r.currentAssignee && r.currentAssignee !== r.currentStepName
      && !String(r.currentAssignee).startsWith('step:'),
    `assignee=${JSON.stringify(r.currentAssignee)}`)
}
for (const r of done.slice(0, 3)) {
  check(`terminal ${r.processDefinitionKey} Current Assignee is empty`,
    r.currentAssignee == null || r.currentAssignee === '',
    `assignee=${JSON.stringify(r.currentAssignee)}`)
}

const failed = results.filter(r => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} MI display checks passed`)
await browser.close()
process.exit(failed.length === 0 ? 0 : 1)
