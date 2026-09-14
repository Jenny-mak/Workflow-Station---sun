/**
 * Capture Designer screens for /help/ article figures (DW login, no SSO).
 * Writes PNGs into frontend/help/public/guides/.
 * Requires HELP_GUIDE_FU_ID from create-help-demo-purchase-request.mjs.
 * After recapture:
 * Preview-effect shots clip to form fields (not the tall dialog body) and trim leftover whitespace.
 * 1. Bump GUIDE_FIGURE_REV in frontend/help/src/components/GuideArticle.vue
 * 2. cd frontend/help && pnpm run build   (copies public/guides → dist/guides)
 * 3. Rebuild platform-help-frontend — Dockerfile.local only COPY dist, not public/
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { loginViaDwPassword } from './playwright-login.mjs'
import { redactHelpGuidePii } from './redact-help-guide-pii.mjs'
import { trimPngWhitespace } from './trim-png-whitespace.mjs'

const FU_ID = process.env.HELP_GUIDE_FU_ID?.trim()
if (!FU_ID) {
  console.error(
    'HELP_GUIDE_FU_ID is required. Run: node scripts/create-help-demo-purchase-request.mjs',
  )
  process.exit(1)
}

const __dirname = dirname(fileURLToPath(import.meta.url))
const OUT = resolve(__dirname, '../help/public/guides')
mkdirSync(OUT, { recursive: true })

const origin = 'http://localhost:3000'

const launchOpts = { headless: true }
if (process.env.PLAYWRIGHT_EXECUTABLE_PATH) {
  launchOpts.executablePath = process.env.PLAYWRIGHT_EXECUTABLE_PATH
} else if (process.env.PLAYWRIGHT_CHANNEL) {
  launchOpts.channel = process.env.PLAYWRIGHT_CHANNEL
}

const browser = await chromium.launch(launchOpts)
const page = await (await browser.newContext({ viewport: { width: 1440, height: 1300 } })).newPage()

async function shot(name, locator) {
  await redactHelpGuidePii(page)
  const target = locator ?? page
  const path = resolve(OUT, name)
  await target.screenshot({ path })
  console.log(`wrote ${path}`)
}

async function clickTab(label) {
  const tab = page.getByRole('tab', { name: label, exact: true })
  await tab.waitFor({ state: 'visible', timeout: 15000 })
  await tab.click()
  await page.waitForTimeout(600)
}

async function openFormPreview() {
  const previewBtn = page
    .locator('.form-editor-view .header-actions')
    .getByRole('button', { name: 'Preview', exact: true })
  await previewBtn.waitFor({ state: 'visible', timeout: 15000 })
  await previewBtn.click()
  const dlg = page.locator('.form-preview-dialog')
  await dlg.waitFor({ state: 'visible', timeout: 20000 })
  await dlg.locator('.form-preview-wrapper, form-create').first().waitFor({ state: 'visible', timeout: 20000 })
  await page.waitForTimeout(800)
  return dlg
}

async function closeFormPreview() {
  await page.keyboard.press('Escape')
  await page.locator('.form-preview-dialog').waitFor({ state: 'hidden', timeout: 8000 }).catch(() => {})
  await page.waitForTimeout(400)
}

function previewBodySelector() {
  return '.form-preview-dialog .el-dialog__body'
}

async function injectPreviewBanner() {
  await page.evaluate((bodySel) => {
    const body = document.querySelector(bodySel)
    if (!body) return
    body.querySelectorAll('.help-capture-banner').forEach((n) => n.remove())
    const banner = document.createElement('div')
    banner.className =
      'help-capture-banner el-alert el-alert--warning is-light form-event-banner'
    banner.setAttribute('role', 'alert')
    banner.innerHTML =
      '<i class="el-icon el-alert__icon"><svg viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg"><path fill="currentColor" d="M512 64a448 448 0 1 1 0 896 448 448 0 0 1 0-896zm0 832a384 384 0 1 0 0-768 384 384 0 0 0 0 768zm48-176a48 48 0 1 1-96 0 48 48 0 0 1 96 0zm-48-368a32 32 0 0 1 32 32v224a32 32 0 0 1-64 0V384a32 32 0 0 1 32-32z"/></svg></i><div class="el-alert__content"><span class="el-alert__title">Check the title</span></div>'
    body.insertBefore(banner, body.firstChild)
  }, previewBodySelector())
}

async function injectPreviewFieldError() {
  await page.evaluate(() => {
    const items = [...document.querySelectorAll('.form-preview-dialog .el-form-item')]
    const titleItem = items.find((el) => /^Title\b/.test(el.querySelector('.el-form-item__label')?.textContent?.trim() || ''))
    if (!titleItem) return
    titleItem.classList.add('is-error')
    let err = titleItem.querySelector('.el-form-item__error')
    if (!err) {
      err = document.createElement('div')
      err.className = 'el-form-item__error'
      titleItem.appendChild(err)
    }
    err.textContent = 'Title is required'
  })
}

async function injectPreviewDisabledCostCenter() {
  await page.evaluate(() => {
    const items = [...document.querySelectorAll('.form-preview-dialog .el-form-item')]
    const ccItem = items.find((el) => /^Cost center\b/i.test(el.querySelector('.el-form-item__label')?.textContent?.trim() || ''))
    if (!ccItem) return
    ccItem.querySelectorAll('.el-input, .el-select, .el-input-number').forEach((el) => {
      el.classList.add('is-disabled')
    })
    ccItem.querySelectorAll('input, textarea').forEach((el) => {
      el.setAttribute('disabled', 'disabled')
    })
  })
}

async function shotPreviewForm(name, dlg) {
  await redactHelpGuidePii(page)
  const clip = await dlg.evaluate((root) => {
    const scope =
      root.querySelector('.preview-container') ||
      root.querySelector('.form-preview-wrapper') ||
      root.querySelector('.el-dialog__body') ||
      root
    const nodes = [
      ...scope.querySelectorAll(
        '.el-alert, .help-capture-banner, .el-form-item, .el-transfer, .el-card, .el-empty',
      ),
    ]
    const rects = nodes
      .map((n) => n.getBoundingClientRect())
      .filter((r) => r.width >= 4 && r.height >= 4)
    const box = scope.getBoundingClientRect()
    const pad = 12
    if (!rects.length) {
      return {
        x: box.x,
        y: box.y,
        width: box.width,
        height: Math.min(box.height, 720),
      }
    }
    const left = Math.max(box.left, Math.min(...rects.map((r) => r.left)) - pad)
    const top = Math.max(box.top, Math.min(...rects.map((r) => r.top)) - pad)
    const right = Math.min(box.right, Math.max(...rects.map((r) => r.right)) + pad)
    const bottom = Math.min(box.bottom, Math.max(...rects.map((r) => r.bottom)) + pad)
    return {
      x: left,
      y: top,
      width: Math.max(1, right - left),
      height: Math.max(1, bottom - top),
    }
  })
  const view = page.viewportSize()
  const x = Math.max(0, Math.floor(clip.x))
  const y = Math.max(0, Math.floor(clip.y))
  const width = Math.max(1, Math.floor(Math.min(clip.width, (view?.width ?? clip.width) - x)))
  const height = Math.max(1, Math.floor(Math.min(clip.height, (view?.height ?? clip.height) - y)))
  const path = resolve(OUT, name)
  await page.screenshot({ path, clip: { x, y, width, height } })
  const trim = await trimPngWhitespace(page, path)
  console.log(`wrote ${path}${trim.trimmed ? ` (trimmed to ${trim.width}×${trim.height})` : ''}`)
}

async function capturePreviewEffect(name, injectFn) {
  const dlg = await openFormPreview()
  await injectFn()
  await page.waitForTimeout(500)
  await shotPreviewForm(name, dlg)
  await closeFormPreview()
}

async function clickBpmnNode(text) {
  const node = page.locator('.djs-element').filter({ hasText: text }).first()
  if (await node.count()) {
    await node.click({ force: true })
    await page.waitForTimeout(800)
  }
}

async function openPaletteGroup(name) {
  const menu = page.locator('.fc-designer-wrapper ._fc-l-menu-item').filter({ hasText: new RegExp(`^${name}$`) }).first()
  if (await menu.count()) {
    await menu.click()
    await page.waitForTimeout(400)
  }
}

async function openBasicPalette() {
  await openPaletteGroup('Basic')
}

async function clickExtendWidget(paletteName) {
  if (paletteName === 'Sub-Table') {
    const widget = page.locator('.fc-designer-wrapper .sub-table-placeholder-widget').first()
    if (await widget.count()) {
      await widget.click({ force: true })
      return true
    }
  }
  if (paletteName === 'Lookup') {
    const box = page.locator('.fc-designer-wrapper ._fc-m input[placeholder="Click to search"]').first()
    if (await box.count()) {
      await box.click({ force: true })
      return true
    }
  }
  return false
}

async function captureExtendControlFigures() {
  const extendPropsShots = [
    ['Sub-Table', 'dw-form-ctl-sub-table-props.png', /Sub-Table|help_pr_line/],
    ['Lookup', 'dw-form-ctl-lookup-props.png', /Lookup|Click to search/],
  ]
  for (const [paletteName, file, canvasRe] of extendPropsShots) {
    try {
      await openPaletteGroup('Extend')
      const clicked = await clickExtendWidget(paletteName)
      if (!clicked) await selectOrDropBasicControl(paletteName, canvasRe)
      await page.waitForTimeout(400)
      await showComponentPropsPanel()
      const text = await configPanelText()
      if (!panelMatchesControl(text, paletteName)) {
        throw new Error(`${paletteName} panel mismatch: ${text.slice(0, 180).replace(/\n/g, ' | ')}`)
      }
      const panel = await visibleRightAside()
      if (await panel.count()) await shot(file, panel)
    } catch (ctlErr) {
      console.warn(`skip ${file}`, ctlErr)
    }
  }
}

async function clickDesignerField(labelRe) {
  const source = labelRe instanceof RegExp ? labelRe.source : String(labelRe)
  const flags = labelRe instanceof RegExp ? labelRe.flags : ''
  return page.evaluate(
    ({ source: src, flags: fl }) => {
      const re = new RegExp(src, fl)
      const canvas = document.querySelector('.fc-designer-wrapper ._fc-m')
      const item = [...(canvas?.querySelectorAll('.el-form-item') || [])].find((el) => {
        const label = el.querySelector('.el-form-item__label')?.textContent?.trim() || ''
        return re.test(label)
      })
      if (!item || !item.offsetWidth) return false
      item.scrollIntoView({ block: 'center', inline: 'nearest' })
      item.click()
      return true
    },
    { source, flags },
  )
}

async function canvasItemCount() {
  return page.evaluate(
    () =>
      [...document.querySelectorAll('.fc-designer-wrapper ._fc-m .el-form-item')].filter(
        (el) => el.offsetWidth && el.offsetHeight,
      ).length,
  )
}

async function dragPaletteItem(item, canvas) {
  await item.scrollIntoViewIfNeeded()
  await page.waitForTimeout(150)
  const before = await canvasItemCount()
  const from = await item.boundingBox()
  const to = await canvas.boundingBox()
  if (!from || !to) throw new Error('no drag boxes')
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2)
  await page.mouse.down()
  await page.mouse.move(to.x + to.width * 0.55, to.y + Math.min(140, to.height * 0.3), { steps: 28 })
  await page.mouse.up()
  await page.waitForTimeout(700)
  return (await canvasItemCount()) > before
}

async function clickLastCanvasItem() {
  return page.evaluate(() => {
    const items = [...document.querySelectorAll('.fc-designer-wrapper ._fc-m .el-form-item')].filter(
      (el) => el.offsetWidth && el.offsetHeight,
    )
    const last = items[items.length - 1]
    if (!last) return ''
    last.scrollIntoView({ block: 'center' })
    last.click()
    return last.querySelector('.el-form-item__label')?.textContent?.trim() || ''
  })
}

async function configPanelText() {
  return page.evaluate(() => {
    const aside = [...document.querySelectorAll('.fc-designer-wrapper aside._fc-r')].find((el) => el.offsetWidth)
    return aside?.querySelector('._fc-r-config')?.innerText || aside?.innerText || ''
  })
}

const CONFIG_MARKERS = {
  Input: 'Sensitive Mask',
  Textarea: 'Whether the height is adaptive',
  Password: 'Whether to display the clear button',
  InputNumber: 'Precision of input value',
  Radio: 'Text color when button form is activated',
  Checkbox: 'Minimum number that can be checked',
  Select: 'Is it searchable',
  Switch: 'Text description when opening',
  Slider: 'Step',
  Rate: 'Whether to allow half selection',
  Date: 'Unlink the two date panels in the range selector',
  DateRange: 'Separator when selecting range',
  Time: 'Whether to use arrows for time selection',
  TimeRange: 'Whether to use arrows for time selection',
  Cascader: 'Placeholder',
  ColorPicker: 'Whether transparency selection is supported',
  Upload: 'Upload address (required)',
  Tree: 'Horizontal indent (px) between adjacent level nodes',
  TreeSelect: 'Whether to expand or shrink nodes when clicking on them',
  Transfer: 'Left Title',
  Editor: 'Max Length',
  'Sub-Table': 'Sub Table Binding',
  Lookup: 'Lookup Config',
}

function panelMatchesControl(text, paletteName) {
  const marker = CONFIG_MARKERS[paletteName]
  if (paletteName === 'Transfer') {
    return text.includes('Left Title') || text.includes('Sort strategy of list elements')
  }
  if (paletteName === 'Editor') {
    return text.includes('Max Length') || text.includes('Rows') || /\nEditor\n/.test(`\n${text}`)
  }
  if (!marker || !text.includes(marker)) return false
  if (paletteName === 'Input' && !text.includes('Sensitive Mask')) return false
  if (paletteName === 'Password' && text.includes('Sensitive Mask')) return false
  if (paletteName === 'Textarea' && text.includes('Sensitive Mask')) return false
  if (paletteName === 'Slider' && text.includes('Precision of input value')) return false
  if (paletteName === 'Date' && !text.includes('Placeholder content for non-range selection')) return false
  if (paletteName === 'DateRange' && text.includes('Placeholder content for non-range selection')) return false
  if (paletteName === 'Cascader' && text.includes('Is it searchable')) return false
  return true
}

async function selectOrDropBasicControl(paletteName, canvasRe) {
  if (canvasRe && (await clickDesignerField(canvasRe))) {
    await showComponentPropsPanel()
    if (panelMatchesControl(await configPanelText(), paletteName)) return
  }
  if (await clickDesignerField(new RegExp(`^${paletteName}$`))) {
    await page.waitForTimeout(200)
    await showComponentPropsPanel()
    if (panelMatchesControl(await configPanelText(), paletteName)) return
  }
  const wrap = page.locator('.fc-designer-wrapper').first()
  const items = wrap.locator('._fc-l-item').filter({ hasText: new RegExp(`^${paletteName}$`) })
  const canvas = wrap.locator('._fc-m .form-create, ._fc-m').first()
  const n = await items.count()
  if (!n || !(await canvas.count())) {
    throw new Error(`palette item ${paletteName} not found`)
  }
  for (let i = n - 1; i >= 0; i -= 1) {
    if (!(await dragPaletteItem(items.nth(i), canvas))) continue
    await clickLastCanvasItem()
    await page.waitForTimeout(300)
    await showComponentPropsPanel()
    const text = await configPanelText()
    if (panelMatchesControl(text, paletteName)) return
  }
  throw new Error(`drop ${paletteName} did not match this control’s Props`)
}

async function visibleRightAside() {
  return page.locator('.fc-designer-wrapper aside._fc-r').filter({ visible: true }).first()
}

async function showComponentPropsPanel() {
  const aside = await visibleRightAside()
  const componentTab = aside.locator('._fc-r-tab').filter({ hasText: /^Component$/ }).first()
  if (await componentTab.count()) {
    await componentTab.click()
    await page.waitForTimeout(200)
  }
}

async function captureBasicControlFigures() {
  await openBasicPalette()
  const palette = page.locator('.fc-designer-wrapper ._fc-l').first()
  if (await palette.count()) {
    await shot('dw-form-ctl-basic-palette.png', palette)
  }

  const basicPropsShots = [
    ['Input', 'dw-form-ctl-input-props.png', /^Title\b/],
    ['Textarea', 'dw-form-ctl-textarea-props.png', null],
    ['Password', 'dw-form-ctl-password-props.png', null],
    ['InputNumber', 'dw-form-ctl-input-number-props.png', /^Window/i],
    ['Radio', 'dw-form-ctl-radio-props.png', null],
    ['Checkbox', 'dw-form-ctl-checkbox-props.png', null],
    ['Select', 'dw-form-ctl-select-props.png', /^Scenario\b/],
    ['Switch', 'dw-form-ctl-switch-props.png', null],
    ['Slider', 'dw-form-ctl-slider-props.png', null],
    ['Rate', 'dw-form-ctl-rate-props.png', null],
    ['Date', 'dw-form-ctl-date-props.png', /^Start date\b/i],
    ['DateRange', 'dw-form-ctl-date-range-props.png', null],
    ['Time', 'dw-form-ctl-time-props.png', null],
    ['TimeRange', 'dw-form-ctl-time-range-props.png', null],
    ['Cascader', 'dw-form-ctl-cascader-props.png', null],
    ['ColorPicker', 'dw-form-ctl-color-picker-props.png', null],
    ['Upload', 'dw-form-ctl-upload-props.png', null],
    ['Tree', 'dw-form-ctl-tree-props.png', null],
    ['TreeSelect', 'dw-form-ctl-tree-select-props.png', null],
    ['Transfer', 'dw-form-ctl-transfer-props.png', null],
    ['Editor', 'dw-form-ctl-editor-props.png', null],
  ]
  for (const [paletteName, file, canvasRe] of basicPropsShots) {
    try {
      await openBasicPalette()
      await selectOrDropBasicControl(paletteName, canvasRe)
      await page.waitForTimeout(400)
      await showComponentPropsPanel()
      const text = await configPanelText()
      if (!panelMatchesControl(text, paletteName)) {
        throw new Error(`${paletteName} panel mismatch: ${text.slice(0, 180).replace(/\n/g, ' | ')}`)
      }
      const panel = await visibleRightAside()
      if (await panel.count()) await shot(file, panel)
    } catch (ctlErr) {
      console.warn(`skip ${file}`, ctlErr)
    }
  }
}

try {
  await loginViaDwPassword(page)
  await page.goto(`${origin}/dev/function-units/${FU_ID}`, { waitUntil: 'domcontentloaded' })
  await page.locator('.el-tabs').first().waitFor({ state: 'visible', timeout: 25000 })
  await page.waitForTimeout(1500)

  await clickTab('Table Design')
  const mainTableRow = page.locator('.el-table__row').filter({ hasText: 'help_pr' }).filter({ hasNotText: 'help_pr_line' }).first()
  await mainTableRow.waitFor({ state: 'visible', timeout: 10000 })
  await mainTableRow.click()
  await page.locator('.table-fields-grid').waitFor({ state: 'visible', timeout: 10000 })
  await page.waitForTimeout(800)
  await shot('dw-table-design.png', page.locator('.designer-workspace'))
  await page.getByRole('button', { name: 'Back to List' }).click()
  await page.waitForTimeout(400)

  try {
    await clickTab('View Design')
    await page.locator('.main-table-view-designer').waitFor({ state: 'visible', timeout: 15000 })
    await page.waitForTimeout(800)
    await shot('dw-view-design.png', page.locator('.designer-workspace, .view-design-tab, .main-table-view-designer').first())
  } catch (viewErr) {
    console.warn('skip dw-view-design.png', viewErr)
  }

  await clickTab('Form Design')
  try {
    const formEdit = page.locator('.form-list-sidebar .el-table__row').filter({ hasText: 'help_pr' }).getByRole('button', { name: 'Edit' }).first()
    await formEdit.waitFor({ state: 'visible', timeout: 10000 })
    await formEdit.click()
    await page.locator('.fc-designer-wrapper').first().waitFor({ state: 'visible', timeout: 15000 })
    await page.waitForTimeout(800)

    try {
      await captureBasicControlFigures()
    } catch (ctlErr) {
      console.warn('skip Basic control screenshots', ctlErr)
    }
    try {
      await captureExtendControlFigures()
    } catch (ctlErr) {
      console.warn('skip Extend control screenshots', ctlErr)
    }

    const scenarioField = page.locator('.el-form-item').filter({ hasText: /^Scenario/ }).first()
    if (await scenarioField.count()) await scenarioField.click({ force: true })
    await page.waitForTimeout(500)
    await shot('dw-form-events-canvas.png', page.locator('.fc-designer-wrapper').first())

    const formTab = page.locator('._fc-r-tab').filter({ hasText: /^Form$/ }).first()
    if (await formTab.count()) {
      await formTab.click()
      await page.waitForTimeout(800)
      const formEventBtn = page.locator('._fd-fn-list .el-button').first()
      if (await formEventBtn.count()) {
        await formEventBtn.scrollIntoViewIfNeeded()
        await page.waitForTimeout(400)
      }
      await shot('dw-form-events-form-tab.png', page.locator('.fc-designer-wrapper').first())
    }

    await capturePreviewEffect('dw-form-events-preview-notify.png', injectPreviewBanner)
    await capturePreviewEffect('dw-form-events-preview-errors.png', injectPreviewFieldError)
    await capturePreviewEffect('dw-form-events-preview-disabled.png', injectPreviewDisabledCostCenter)

    const titleField = page.locator('.el-form-item').filter({ hasText: /^Title/ }).first()
    if (await titleField.count()) await titleField.click({ force: true })
    else await page.locator('.el-form-item').first().click({ force: true })
    await page.waitForTimeout(500)
    await page.locator('._fd-event .el-button').first().click()
    const eventDlg = page.locator('._fd-event-dialog').last()
    await eventDlg.waitFor({ state: 'visible', timeout: 8000 })
    await page.waitForTimeout(500)
    await shot('dw-form-events.png', eventDlg)
    await page.keyboard.press('Escape')
    await page.waitForTimeout(300)

    await page.getByRole('button', { name: 'Back to List' }).click()
    await page.waitForTimeout(400)
  } catch (err) {
    console.warn('skip form-events screenshots', err)
    const back = page.getByRole('button', { name: 'Back to List' })
    if (await back.count()) await back.click().catch(() => {})
    await page.waitForTimeout(400)
  }

  await clickTab('Connections')
  await shot('dw-connections.png', page.locator('.designer-workspace'))
  const inboundRow = page.locator('.connection-designer .el-table__row').filter({ hasText: /Gmail|IMAP/i }).first()
  if (await inboundRow.count()) {
    await inboundRow.getByRole('button', { name: 'Edit' }).click()
  } else {
    await page.getByRole('button', { name: 'New Connection' }).click()
  }
  const connDlg = page.locator('.connection-form-dialog').last()
  await connDlg.waitFor({ state: 'visible', timeout: 8000 })
  await page.waitForTimeout(600)
  await shot('dw-connections-inbound.png', connDlg)
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)

  await clickTab('Email Templates')
  await shot('dw-email-templates.png', page.locator('.designer-workspace'))
  const editTpl = page.locator('.designer-workspace .el-table').getByRole('button', { name: 'Edit' }).first()
  if (await editTpl.count()) {
    await editTpl.click({ timeout: 8000 })
    const tplDlg = page.locator('.email-template-form-dialog, .el-dialog').filter({ hasText: 'Body' }).last()
    await tplDlg.waitFor({ state: 'visible', timeout: 8000 })
    await page.locator('[data-testid="email-body-split"]').waitFor({ state: 'visible', timeout: 8000 })
    await page.waitForTimeout(800)
    await shot('dw-email-body.png', tplDlg)
    await page.keyboard.press('Escape')
    await page.waitForTimeout(400)
  }

  await clickTab('Email Monitors')
  await shot('dw-email-monitors.png', page.locator('.designer-workspace'))
  const editMonitor = page.locator('.email-monitor-designer .el-table').getByRole('button', { name: 'Edit' }).first()
  if (await editMonitor.count()) {
    await editMonitor.click()
    const monitorDlg = page.locator('.el-dialog').filter({ hasText: 'Field Extraction' }).last()
    await monitorDlg.waitFor({ state: 'visible', timeout: 15000 })
    await page.waitForTimeout(600)
    await clickTab('Sample Email')
    await page.evaluate(() => {
      const dlg = document.querySelector('.el-dialog:last-of-type')
      if (!dlg) return
      const setInput = (label, value) => {
        const item = [...dlg.querySelectorAll('.el-form-item')].find((el) =>
          el.querySelector('.el-form-item__label')?.textContent?.trim().startsWith(label),
        )
        const input = item?.querySelector('input, textarea')
        if (input instanceof HTMLInputElement || input instanceof HTMLTextAreaElement) {
          input.value = value
          input.dispatchEvent(new Event('input', { bubbles: true }))
        }
      }
      setInput('Subject', 'Vendor quote for help_pr — CaseNo: 2026001')
      setInput('From', 'Vendor Desk <vendor@example.com>')
      setInput('To', 'Procurement Team <procurement@example.com>')
      setInput('Cc', 'audit@example.com')
      setInput('Reply-To', 'noreply@example.com')
      setInput('Sent date', '2026-09-08T08:30:00Z')
      setInput('Message-ID', '<help-demo-msg@example.com>')
      setInput('Plain Text Body', 'Case No: 2026001\nAmount: HKD 1,200.00')
    })
    await page.waitForTimeout(400)
    await shot('dw-email-extraction-sample.png', monitorDlg)
    await clickTab('Field Mapping')
    await page.evaluate(() => {
      const dlg = document.querySelector('.el-dialog:last-of-type')
      if (!dlg) return
      const addBtn = [...dlg.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Add Field')
      addBtn?.click()
    })
    await page.waitForTimeout(800)
    await page.evaluate(() => {
      const dlg = document.querySelector('.el-dialog:last-of-type')
      if (!dlg) return
      const row = dlg.querySelector('.el-table__body tr:last-child')
      if (!row) return
      const selects = row.querySelectorAll('.el-select')
      if (selects.length >= 2) {
        selects[1].click()
      }
    })
    await page.waitForTimeout(300)
    const fromOpt = page.getByRole('option', { name: 'From (sender)', exact: true })
    if (await fromOpt.count()) {
      await fromOpt.click()
    }
    await page.waitForTimeout(500)
    await shot('dw-email-field-mapping.png', monitorDlg)
    await page.keyboard.press('Escape')
    await page.waitForTimeout(400)
  }

  await clickTab('Process Design')
  await page.waitForTimeout(1500)
  await clickBpmnNode('Send approval notice')
  await shot('dw-send-task.png', page.locator('.designer-workspace'))

  await clickBpmnNode(/^Start$/)
  const inboundBody = page.locator('.start-email-monitor')
  if ((await inboundBody.count()) === 0 || !(await inboundBody.isVisible().catch(() => false))) {
    await page.locator('.el-collapse-item').filter({ hasText: 'Inbound Email Trigger' }).locator('.el-collapse-item__header').click({ force: true })
    await page.waitForTimeout(500)
  }
  if (await inboundBody.count()) {
    await inboundBody.scrollIntoViewIfNeeded()
    await page.waitForTimeout(600)
  }
  await shot('dw-start-event.png', page.locator('.designer-workspace'))
} finally {
  await browser.close()
}

console.log('')
console.log('Next: cd frontend/help && pnpm run build')
console.log('Then: docker compose ... up -d --build platform-help-frontend')
console.log('(Help image only ships dist/guides — public/ alone is not served.)')
