/**
 * Capture Guidelines portal home + guideline articles (no login).
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const DW_SHOTS = resolve(__dirname, '../developer-workstation/verification-screenshots')
const HELP_SHOTS = resolve(__dirname, '../help/verification-screenshots')
mkdirSync(DW_SHOTS, { recursive: true })
mkdirSync(HELP_SHOTS, { recursive: true })
const DATE = new Date().toISOString().slice(0, 10)

const results = []
const rec = (n, ok, d = '') => {
  results.push({ n, ok, d })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${n}${d ? ` — ${d}` : ''}`)
}

const launchOpts = { headless: true }
if (process.env.PLAYWRIGHT_EXECUTABLE_PATH) {
  launchOpts.executablePath = process.env.PLAYWRIGHT_EXECUTABLE_PATH
} else if (process.env.PLAYWRIGHT_CHANNEL) {
  launchOpts.channel = process.env.PLAYWRIGHT_CHANNEL
}

const browser = await chromium.launch(launchOpts)
const context = await browser.newContext({ viewport: { width: 1400, height: 900 } })
await context.grantPermissions(['clipboard-read', 'clipboard-write'], {
  origin: 'http://localhost:3000',
})
const page = await context.newPage()

try {
  await page.goto('http://localhost:3000/help/', { waitUntil: 'domcontentloaded' })
  const home = page.getByTestId('help-home')
  await home.waitFor({ state: 'visible', timeout: 20000 })
  rec('Help catalog is visible without login', await home.isVisible())
  rec(
    'Home lists articles by job',
    await page.getByTestId('help-by-need').isVisible(),
  )

  const homeShot = resolve(DW_SHOTS, `${DATE}_help-portal-home.png`)
  await page.screenshot({ path: homeShot, fullPage: true })
  console.log(`screenshot ${homeShot}`)

  rec(
    'Home lists Table Design as its own job',
    await page.getByTestId('help-need-table-design').isVisible(),
  )
  rec(
    'Home lists View Design as its own job',
    await page.getByTestId('help-need-view-design').isVisible(),
  )
  rec(
    'Home job grid omits Basic / Extend indexes',
    (await page.getByTestId('help-need-form-events-basic').count()) === 0
      && (await page.getByTestId('help-need-form-events-extend').count()) === 0,
  )
  rec(
    'DW Table Design card goes to /table-design',
    (await page.getByTestId('help-card-dw-tables').getAttribute('href'))?.includes('/table-design') === true,
  )

  await page.getByTestId('help-search').locator('input').fill('Lookup')
  const lookupHit = page.getByTestId('help-search-hit-form-ctl-lookup')
  await lookupHit.waitFor({ state: 'visible', timeout: 5000 })
  rec('Search finds Lookup by title', await lookupHit.isVisible())
  await page.keyboard.press('Escape')

  rec(
    'Grey Process Design menu explains no article yet',
    (await page.getByTestId('help-nav-dw-process').getAttribute('title')) === 'No article yet',
  )
  rec(
    'Sidebar follows Developer Workstation menus',
    await page.getByRole('button', { name: 'Function Units' }).isVisible(),
  )

  await page.getByRole('button', { name: 'Admin Center' }).click()
  await page.getByRole('button', { name: 'Relation Tables' }).click()
  rec(
    'Sidebar hangs computed-fields under Admin Relation Tables',
    await page.getByTestId('help-nav-ac-rt-struct').isVisible(),
  )

  await page.goto('http://localhost:3000/help/computed-fields', {
    waitUntil: 'domcontentloaded',
  })
  const article = page.getByTestId('computed-field-guide-page')
  await article.waitFor({ state: 'visible', timeout: 15000 })
  rec('Computed-fields guideline is visible', await article.isVisible())
  rec(
    'Computed-fields samples use help_pr_line',
    (await article.textContent())?.includes('SUM(help_pr_line.line_total)') === true,
  )
  rec(
    'Computed-fields formulas are copyable code blocks',
    (await article.locator('[data-testid="help-code-block"]').count()) > 0,
  )
  rec(
    'Computed-fields has order-of-work steps',
    (await article.locator('.help-flow li').count()) >= 4,
  )
  rec(
    'URL is /help/computed-fields',
    page.url().includes('/help/computed-fields'),
    page.url(),
  )

  const articleShot = resolve(DW_SHOTS, `${DATE}_help-portal-computed-fields.png`)
  await page.screenshot({ path: articleShot, fullPage: true })
  console.log(`screenshot ${articleShot}`)

  await page.goto('http://localhost:3000/help/table-design', { waitUntil: 'domcontentloaded' })
  const tableArticle = page.getByTestId('table-design-guide-page')
  await tableArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('Table-design guideline is visible', await tableArticle.isVisible())
  rec(
    'Table-design catalogs Primary Key and Foreign Key',
    (await tableArticle.textContent())?.includes('Primary Key') === true
      && (await tableArticle.textContent())?.includes('Foreign Key') === true,
  )
  rec(
    'Table-design related links include computed-fields',
    await tableArticle.locator('.help-related a[href$="/computed-fields"]').count().then((n) => n > 0),
  )
  const tableShot = resolve(DW_SHOTS, `${DATE}_help-portal-table-design.png`)
  await page.screenshot({ path: tableShot, fullPage: true })
  console.log(`screenshot ${tableShot}`)

  await page.goto('http://localhost:3000/help/view-design', { waitUntil: 'domcontentloaded' })
  const viewArticle = page.getByTestId('view-design-guide-page')
  await viewArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('View-design guideline is visible', await viewArticle.isVisible())
  rec(
    'View-design documents Access control pairing',
    (await viewArticle.textContent())?.includes('Access control') === true
      && (await viewArticle.textContent())?.includes('System Administrator') === true,
  )
  rec(
    'View-design has #access',
    await viewArticle.locator('#access').count().then((n) => n > 0),
  )
  const viewShot = resolve(DW_SHOTS, `${DATE}_help-portal-view-design.png`)
  await page.screenshot({ path: viewShot, fullPage: true })
  console.log(`screenshot ${viewShot}`)

  await page.goto('http://localhost:3000/help/email-send', { waitUntil: 'domcontentloaded' })
  const sendArticle = page.getByTestId('email-send-guide-page')
  await sendArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('Send-email guideline is visible', await sendArticle.isVisible())
  rec(
    'Send-email related links include Email Monitor',
    await sendArticle.locator('.help-related a[href$="/email-monitor"]').count().then((n) => n > 0),
  )
  rec(
    'Send-email HTML mode keeps style tags',
    (await sendArticle.textContent())?.includes('<style>') === true,
  )
  rec(
    'Send-email field catalog names Sender Email',
    (await sendArticle.textContent())?.includes('Sender Email (From address)') === true,
  )
  rec(
    'Send-email field catalog names From override',
    (await sendArticle.textContent())?.includes('From (override)') === true,
  )
  rec(
    'Send-email field labels are not copyable',
    (await sendArticle.locator('[data-testid="help-copy-btn"]').count()) === 0,
  )
  rec('URL is /help/email-send', page.url().includes('/help/email-send'), page.url())
  const sendShot = resolve(DW_SHOTS, `${DATE}_help-portal-email-send.png`)
  await page.screenshot({ path: sendShot, fullPage: true })
  console.log(`screenshot ${sendShot}`)

  const monitorErrors = []
  const onMonitorPageError = (err) => monitorErrors.push(String(err))
  page.on('pageerror', onMonitorPageError)
  await page.goto('http://localhost:3000/help/email-monitor', { waitUntil: 'domcontentloaded' })
  const monitorArticle = page.getByTestId('email-monitor-guide-page')
  await monitorArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('Email-monitor guideline is visible', await monitorArticle.isVisible())
  rec(
    'Email-monitor has no pageerror (vue-i18n braces)',
    monitorErrors.length === 0,
    monitorErrors.join(' | '),
  )
  rec(
    'Email-monitor names Vendor quote to PR',
    (await monitorArticle.textContent())?.includes('Vendor quote to PR') === true,
  )
  rec(
    'Email-monitor field catalog names Rule Name',
    (await monitorArticle.textContent())?.includes('Rule Name') === true,
  )
  rec(
    'Email-monitor field catalog names Start process when email arrives',
    (await monitorArticle.textContent())?.includes('Start process when email arrives') === true,
  )
  rec(
    'Email-monitor shows literal JSON [{url,name}]',
    (await monitorArticle.textContent())?.includes('JSON [{url,name}]') === true,
  )
  rec(
    'Email-monitor shows literal {subject}.eml',
    (await monitorArticle.textContent())?.includes('{subject}.eml') === true,
  )
  rec(
    'Email-monitor shows literal imap-uid:{uid}',
    (await monitorArticle.textContent())?.includes('imap-uid:{uid}') === true,
  )
  rec(
    'URL is /help/email-monitor',
    page.url().includes('/help/email-monitor'),
    page.url(),
  )
  const monitorShot = resolve(DW_SHOTS, `${DATE}_help-portal-email-monitor.png`)
  await page.screenshot({ path: monitorShot, fullPage: true })
  console.log(`screenshot ${monitorShot}`)

  monitorErrors.length = 0
  await page.getByTestId('help-locale-select').selectOption('zh-CN')
  await monitorArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec(
    'Email-monitor zh-CN has no pageerror (vue-i18n braces)',
    monitorErrors.length === 0,
    monitorErrors.join(' | '),
  )
  rec(
    'Email-monitor zh-CN still visible',
    await monitorArticle.isVisible(),
  )
  rec(
    'Email-monitor zh-CN shows literal JSON [{url,name}]',
    (await monitorArticle.textContent())?.includes('JSON [{url,name}]') === true,
  )
  rec(
    'Email-monitor zh-CN shows literal {主题}.eml',
    (await monitorArticle.textContent())?.includes('{主题}.eml') === true,
  )
  rec(
    'Email-monitor zh-CN shows literal imap-uid:{uid}',
    (await monitorArticle.textContent())?.includes('imap-uid:{uid}') === true,
  )
  const monitorZhShot = resolve(HELP_SHOTS, `${DATE}_help-email-monitor-zh-CN.png`)
  await page.screenshot({ path: monitorZhShot, fullPage: true })
  console.log(`screenshot ${monitorZhShot}`)

  monitorErrors.length = 0
  await page.getByTestId('help-locale-select').selectOption('zh-TW')
  await monitorArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec(
    'Email-monitor zh-TW has no pageerror (vue-i18n braces)',
    monitorErrors.length === 0,
    monitorErrors.join(' | '),
  )
  rec(
    'Email-monitor zh-TW still visible',
    await monitorArticle.isVisible(),
  )
  rec(
    'Email-monitor zh-TW shows literal JSON [{url,name}]',
    (await monitorArticle.textContent())?.includes('JSON [{url,name}]') === true,
  )
  rec(
    'Email-monitor zh-TW shows literal {主旨}.eml',
    (await monitorArticle.textContent())?.includes('{主旨}.eml') === true,
  )
  rec(
    'Email-monitor zh-TW shows literal imap-uid:{uid}',
    (await monitorArticle.textContent())?.includes('imap-uid:{uid}') === true,
  )
  const monitorTwShot = resolve(HELP_SHOTS, `${DATE}_help-email-monitor-zh-TW.png`)
  await page.screenshot({ path: monitorTwShot, fullPage: true })
  console.log(`screenshot ${monitorTwShot}`)
  page.off('pageerror', onMonitorPageError)
  await page.getByTestId('help-locale-select').selectOption('en')

  await page.getByRole('button', { name: 'User Portal' }).click()
  await page.getByRole('button', { name: 'Task' }).click()
  rec(
    'Sidebar hangs task-delegate under Portal To Do',
    await page.getByTestId('help-nav-up-todo').isVisible(),
  )

  await page.goto('http://localhost:3000/help/task-delegate', { waitUntil: 'domcontentloaded' })
  const delegateArticle = page.getByTestId('task-delegate-guide-page')
  await delegateArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('Task-delegate guideline is visible', await delegateArticle.isVisible())
  rec(
    'Task-delegate names Specified BU and Role',
    (await delegateArticle.textContent())?.includes('Specified BU and Role') === true,
  )
  rec(
    'URL is /help/task-delegate',
    page.url().includes('/help/task-delegate'),
    page.url(),
  )
  const delegateShot = resolve(DW_SHOTS, `${DATE}_help-portal-task-delegate.png`)
  await page.screenshot({ path: delegateShot, fullPage: true })
  console.log(`screenshot ${delegateShot}`)

  const llms = await page.goto('http://localhost:3000/help/llms.txt', { waitUntil: 'domcontentloaded' })
  const llmsText = llms ? await llms.text() : ''
  rec('llms.txt is served', llms?.ok() === true && llmsText.includes('/help/computed-fields'))
  rec('llms.txt lists task-delegate', llmsText.includes('/help/task-delegate'))
  rec('llms.txt lists table-design', llmsText.includes('/help/table-design'))
  rec('llms.txt lists view-design', llmsText.includes('/help/view-design'))
  rec('llms.txt lists form-events', llmsText.includes('/help/form-events'))
  rec('llms.txt lists form-events-basic', llmsText.includes('/help/form-events-basic'))
  rec('llms.txt lists form-ctl-input', llmsText.includes('/help/form-ctl-input'))
  rec('llms.txt lists form-ctl-select', llmsText.includes('/help/form-ctl-select'))
  rec('llms.txt lists form-ctl-sub-table', llmsText.includes('/help/form-ctl-sub-table'))
  rec('llms.txt lists form-ctl-lookup', llmsText.includes('/help/form-ctl-lookup'))
  rec('llms.txt lists form-events-extend', llmsText.includes('/help/form-events-extend'))
  rec('llms.txt lists form-events-layout', llmsText.includes('/help/form-events-layout'))

  await page.goto('http://localhost:3000/help/form-events', { waitUntil: 'domcontentloaded' })
  const eventsArticle = page.getByTestId('form-events-guide-page')
  await eventsArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('Form-events guideline is visible', await eventsArticle.isVisible())
  rec(
    'Form-events catalogs when change and hook_load run',
    (await eventsArticle.textContent())?.includes('hook_load') === true
      && (await eventsArticle.textContent())?.includes('leaves the box or presses Enter') === true
      && (await eventsArticle.textContent())?.includes('Typical place to fill Requester') === true,
  )
  rec(
    'Form-events names the user object',
    (await eventsArticle.textContent())?.includes('user.activeBusinessUnitName') === true,
  )
  rec(
    'Form-events names script parameters',
    (await eventsArticle.textContent())?.includes('$inject.api') === true,
  )
  rec(
    'Form-events documents required and hide',
    (await eventsArticle.textContent())?.includes('api.required(true') === true
      && (await eventsArticle.textContent())?.includes("api.hidden(true, 'cost_center')") === true,
  )
  rec(
    'Form-events indexes setValue shapes to control pages',
    (await eventsArticle.locator('a[href*="form-ctl-date"]').count()) > 0
      && (await eventsArticle.locator('a[href*="form-ctl-select"]').count()) > 0
      && (await eventsArticle.locator('a[href*="form-ctl-checkbox"]').count()) > 0
      && (await eventsArticle.textContent())?.includes('Option value (A)') === true
      && (await eventsArticle.textContent())?.includes("api.setValue('quantity', 1)") !== true,
  )
  rec(
    'Form-events documents lock, options, banner, lookup, focus',
    (await eventsArticle.textContent())?.includes("api.disabled(true, 'cost_center')") === true
      && (await eventsArticle.textContent())?.includes("api.setOptions('scenario'") === true
      && (await eventsArticle.textContent())?.includes('api.setFormNotification(') === true
      && (await eventsArticle.textContent())?.includes('api.setLookupFilter(field') === true
      && (await eventsArticle.textContent())?.includes("api.setFocus('request_title')") === true,
  )
  const eventsCode = eventsArticle.locator('[data-testid="help-code-block"]').first()
  rec('Form-events renders a copyable code block', await eventsCode.isVisible())
  const eventPres = await eventsArticle.locator('.help-code-pre').allInnerTexts()
  rec(
    'Form-events pretty-prints an if sample',
    eventPres.some((text) => /\{\s*\n/.test(text)) === true,
  )
  const eventsCopy = eventsCode.getByTestId('help-copy-btn')
  await eventsCopy.click()
  rec(
    'Form-events copy control reports Copied',
    await eventsCopy
      .getByText('Copied')
      .waitFor({ state: 'visible', timeout: 2000 })
      .then(() => true)
      .catch(() => false),
  )
  rec(
    'Form-events failures: error blocks Save, banner does not',
    (await eventsArticle.textContent())?.includes('blocks Save') === true
      && (await eventsArticle.textContent())?.includes('ERROR banner does not block Save') === true,
  )
  rec(
    'Sidebar hangs How to write events under Controls',
    await page.getByTestId('help-nav-dw-forms').isVisible(),
  )
  rec('URL is /help/form-events', page.url().includes('/help/form-events'), page.url())
  const eventsShot = resolve(DW_SHOTS, `${DATE}_help-portal-form-events.png`)
  await page.screenshot({ path: eventsShot, fullPage: true })
  console.log(`screenshot ${eventsShot}`)

  for (const hash of ['when', 'set-shapes', 'errors', 'disabled', 'options', 'notify', 'lookup', 'chrome']) {
    await page.goto(`http://localhost:3000/help/form-events#${hash}`, {
      waitUntil: 'domcontentloaded',
    })
    await eventsArticle.waitFor({ state: 'visible', timeout: 15000 })
    if (hash === 'notify' || hash === 'errors' || hash === 'disabled') {
      const fig = page.locator(`#${hash} .help-figure img`)
      await fig.waitFor({ state: 'visible', timeout: 10000 })
      await fig.evaluate((el) =>
        el.complete ? Promise.resolve() : new Promise((resolveLoad) => { el.onload = resolveLoad }),
      )
      const box = await fig.boundingBox()
      const natural = await fig.evaluate((el) => ({ w: el.naturalWidth, h: el.naturalHeight }))
      rec(
        `${hash} preview figure is cropped to form content`,
        box != null && box.height < 720 && natural.h > 0 && natural.h < 1800,
        `cssH=${Math.round(box?.height ?? 0)} png=${natural.w}×${natural.h}`,
      )
    }
    const hashShot = resolve(DW_SHOTS, `${DATE}_help-portal-form-events-${hash}.png`)
    await page.screenshot({ path: hashShot, fullPage: true })
    console.log(`screenshot ${hashShot}`)
  }

  await page.goto('http://localhost:3000/help/form-events-basic', {
    waitUntil: 'domcontentloaded',
  })
  const basicArticle = page.getByTestId('form-events-basic-guide-page')
  await basicArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('Form-events-basic index is visible', await basicArticle.isVisible())
  rec(
    'Form-events-basic is the Basic index',
    (await basicArticle.textContent())?.includes('stock field palette') === true,
  )
  rec(
    'Basic index intro links to How to write events',
    (await basicArticle.locator('a.help-inline-link[href$="/form-events"]').count()) > 0,
  )
  rec(
    'Sidebar hangs Select under Controls',
    await page.getByTestId('help-nav-dw-form-ctl-select').isVisible(),
  )
  const basicShot = resolve(DW_SHOTS, `${DATE}_help-portal-form-events-basic.png`)
  await page.screenshot({ path: basicShot, fullPage: true })
  console.log(`screenshot ${basicShot}`)

  await page.goto('http://localhost:3000/help/form-events-basic#checkbox', {
    waitUntil: 'domcontentloaded',
  })
  await page.waitForURL('**/help/form-ctl-checkbox', { timeout: 10000 })
  rec('Old hash /form-events-basic#checkbox redirects to /form-ctl-checkbox', true)

  await page.goto('http://localhost:3000/help/form-ctl-input', {
    waitUntil: 'domcontentloaded',
  })
  const inputArticle = page.getByTestId('input-guide-page')
  await inputArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('Input control guideline is visible', await inputArticle.isVisible())
  rec(
    'Input page title is Input',
    (await inputArticle.locator('h1').textContent())?.trim() === 'Input',
  )
  rec(
    'Input field catalog names Sensitive Mask and Field',
    (await inputArticle.textContent())?.includes('Sensitive Mask') === true
      && (await inputArticle.textContent())?.includes('Field') === true,
  )
  rec(
    'Input catalog labels are not copyable',
    (await inputArticle.locator('.help-code-chip [data-testid="help-copy-btn"]').count()) === 0,
  )
  rec(
    'Input event sample is copyable',
    (await inputArticle.locator('[data-testid="help-code-block"] [data-testid="help-copy-btn"]').count()) > 0,
  )
  rec(
    'Input page is not a Basic dump of Textarea rows',
    (await inputArticle.textContent())?.includes('Whether the height is adaptive') !== true,
  )
  rec(
    'Input Events note links to How to write events',
    (await inputArticle.locator('a.help-inline-link[href$="/form-events"]').count()) > 0,
  )
  rec(
    'Input intro links to Textarea',
    (await inputArticle.locator('a.help-inline-link[href$="/form-ctl-textarea"]').count()) > 0,
  )
  rec(
    'URL is /help/form-ctl-input',
    page.url().includes('/help/form-ctl-input'),
    page.url(),
  )
  const inputShot = resolve(DW_SHOTS, `${DATE}_help-portal-form-ctl-input.png`)
  await page.screenshot({ path: inputShot, fullPage: true })
  console.log(`screenshot ${inputShot}`)

  await page.goto('http://localhost:3000/help/form-ctl-password', {
    waitUntil: 'domcontentloaded',
  })
  const passwordArticle = page.getByTestId('password-guide-page')
  await passwordArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('Password control guideline is visible', await passwordArticle.isVisible())
  rec(
    'Password catalog labels are not copyable',
    (await passwordArticle.locator('.help-code-chip [data-testid="help-copy-btn"]').count()) === 0,
  )
  rec(
    'Password event sample is copyable',
    (await passwordArticle.locator('[data-testid="help-code-block"] [data-testid="help-copy-btn"]').count()) > 0,
  )
  const passwordShot = resolve(DW_SHOTS, `${DATE}_help-portal-form-ctl-password.png`)
  await page.screenshot({ path: passwordShot, fullPage: true })
  console.log(`screenshot ${passwordShot}`)

  await page.goto('http://localhost:3000/help/form-ctl-select', {
    waitUntil: 'domcontentloaded',
  })
  const selectArticle = page.getByTestId('select-guide-page')
  await selectArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('Select control guideline is visible', await selectArticle.isVisible())
  rec(
    'Select documents Scenario required sample',
    (await selectArticle.textContent())?.includes("value === 'A'") === true,
  )
  rec(
    'Select documents setOptions',
    (await selectArticle.textContent())?.includes("api.setOptions('scenario'") === true,
  )
  rec(
    'Select documents setValue option value A, not the label',
    (await selectArticle.textContent())?.includes("api.setValue('scenario', 'A')") === true
      && (await selectArticle.textContent())?.includes('not the label') === true,
  )
  rec(
    'Select field catalog names Default Value',
    (await selectArticle.textContent())?.includes('Default Value') === true,
  )
  const selectShot = resolve(DW_SHOTS, `${DATE}_help-portal-form-ctl-select.png`)
  await page.screenshot({ path: selectShot, fullPage: true })
  console.log(`screenshot ${selectShot}`)

  await page.goto('http://localhost:3000/help/form-ctl-date', {
    waitUntil: 'domcontentloaded',
  })
  const dateArticle = page.getByTestId('date-guide-page')
  await dateArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('Date control guideline is visible', await dateArticle.isVisible())
  rec(
    'Date Events documents YYYY-MM-DD setValue',
    (await dateArticle.textContent())?.includes("api.setValue('start_date', '2026-09-11')") === true
      && (await dateArticle.textContent())?.includes('YYYY-MM-DD') === true,
  )
  const dateShot = resolve(DW_SHOTS, `${DATE}_help-portal-form-ctl-date.png`)
  await page.screenshot({ path: dateShot, fullPage: true })
  console.log(`screenshot ${dateShot}`)

  await page.goto('http://localhost:3000/help/form-ctl-checkbox', {
    waitUntil: 'domcontentloaded',
  })
  const checkboxArticle = page.getByTestId('checkbox-guide-page')
  await checkboxArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('Checkbox control guideline is visible', await checkboxArticle.isVisible())
  rec(
    'Checkbox Events documents a key array including empty []',
    (await checkboxArticle.textContent())?.includes("api.setValue(field, ['A', 'B'])") === true
      && (await checkboxArticle.textContent())?.includes('empty is []') === true,
  )
  const checkboxShot = resolve(DW_SHOTS, `${DATE}_help-portal-form-ctl-checkbox.png`)
  await page.screenshot({ path: checkboxShot, fullPage: true })
  console.log(`screenshot ${checkboxShot}`)

  await page.goto('http://localhost:3000/help/form-ctl-radio', {
    waitUntil: 'domcontentloaded',
  })
  const radioArticle = page.getByTestId('radio-guide-page')
  await radioArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('Radio control guideline is visible', await radioArticle.isVisible())
  rec(
    'Radio overview explains a single visible choice, not drag-from-palette',
    (await radioArticle.textContent())?.includes('exactly one') === true
      && (await radioArticle.textContent())?.includes('Drag Radio from Basic') !== true,
  )
  const radioShot = resolve(DW_SHOTS, `${DATE}_help-portal-form-ctl-radio.png`)
  await page.screenshot({ path: radioShot, fullPage: true })
  console.log(`screenshot ${radioShot}`)

  await page.goto('http://localhost:3000/help/form-events-extend#subTable', {
    waitUntil: 'domcontentloaded',
  })
  await page.waitForURL('**/help/form-ctl-sub-table', { timeout: 10000 })
  rec('Old hash /form-events-extend#subTable redirects to /form-ctl-sub-table', true)
  const subTableArticle = page.getByTestId('subTable-guide-page')
  await subTableArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('Sub-Table guideline is visible', await subTableArticle.isVisible())
  rec(
    'Sub-Table names help_pr_line and Sub Table Binding',
    (await subTableArticle.textContent())?.includes('help_pr_line') === true
      && (await subTableArticle.textContent())?.includes('Sub Table Binding') === true,
  )
  const subTableShot = resolve(DW_SHOTS, `${DATE}_help-portal-form-ctl-sub-table.png`)
  await page.screenshot({ path: subTableShot, fullPage: true })
  console.log(`screenshot ${subTableShot}`)

  await page.goto('http://localhost:3000/help/form-events-extend#lookup', {
    waitUntil: 'domcontentloaded',
  })
  await page.waitForURL('**/help/form-ctl-lookup', { timeout: 10000 })
  rec('Old hash /form-events-extend#lookup redirects to /form-ctl-lookup', true)
  const lookupArticle = page.getByTestId('lookup-guide-page')
  await lookupArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('Lookup guideline is visible', await lookupArticle.isVisible())
  rec(
    'Lookup documents Lookup Config and setLookupFilter',
    (await lookupArticle.textContent())?.includes('Lookup Config') === true
      && (await lookupArticle.textContent())?.includes('setLookupFilter') === true,
  )
  const lookupShot = resolve(DW_SHOTS, `${DATE}_help-portal-form-ctl-lookup.png`)
  await page.screenshot({ path: lookupShot, fullPage: true })
  console.log(`screenshot ${lookupShot}`)

  await page.goto('http://localhost:3000/help/form-events-extend', {
    waitUntil: 'domcontentloaded',
  })
  const extendArticle = page.getByTestId('form-events-extend-guide-page')
  await extendArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('Form-events-extend guideline is visible', await extendArticle.isVisible())
  rec(
    'Form-events-extend keeps Inline Form and Owner',
    (await extendArticle.textContent())?.includes('Inline Form') === true
      && (await extendArticle.textContent())?.includes('Owner') === true,
  )
  rec(
    'Form-events-extend intro links to Sub-Table and Lookup articles',
    (await extendArticle.locator('a.help-inline-link[href$="/form-ctl-sub-table"]').count()) > 0
      && (await extendArticle.locator('a.help-inline-link[href$="/form-ctl-lookup"]').count()) > 0,
  )
  rec(
    'Form-events-extend scripts are copyable code blocks',
    (await extendArticle.locator('[data-testid="help-code-block"]').count()) > 0,
  )
  const extendShot = resolve(DW_SHOTS, `${DATE}_help-portal-form-events-extend.png`)
  await page.screenshot({ path: extendShot, fullPage: true })
  console.log(`screenshot ${extendShot}`)

  await page.goto('http://localhost:3000/help/form-events-layout#elButton', {
    waitUntil: 'domcontentloaded',
  })
  const layoutArticle = page.getByTestId('form-events-layout-guide-page')
  await layoutArticle.waitFor({ state: 'visible', timeout: 15000 })
  rec('Form-events-layout guideline is visible', await layoutArticle.isVisible())
  rec(
    'Form-events-layout names Button click',
    (await layoutArticle.textContent())?.includes('user && user.displayName') === true,
  )
  const layoutShot = resolve(DW_SHOTS, `${DATE}_help-portal-form-events-layout.png`)
  await page.screenshot({ path: layoutShot, fullPage: true })
  console.log(`screenshot ${layoutShot}`)

  await page.goto('http://localhost:3000/dev/help/computed-fields', {
    waitUntil: 'domcontentloaded',
  })
  await page.waitForURL('**/help/computed-fields', { timeout: 10000 })
  rec('Legacy /dev/help/computed-fields redirects to /help/computed-fields', true)
} finally {
  await browser.close()
}

const failed = results.filter((r) => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} passed`)
if (failed.length) process.exit(1)
