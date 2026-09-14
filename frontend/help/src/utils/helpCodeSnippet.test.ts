import assert from 'node:assert/strict'
import test from 'node:test'
import {
  detectHelpCodeLang,
  formatHelpSnippet,
  isHelpCopyWorth,
  resolveHelpCodeKind,
  tokenizeHelpCode,
} from './helpCodeSnippet.ts'

test('detects form-event scripts as js', () => {
  assert.equal(
    detectHelpCodeLang("if (value) { api.setValue('requester', user && user.displayName) }"),
    'js',
  )
  assert.equal(detectHelpCodeLang("api.hidden(true, 'cost_center')"), 'js')
})

test('detects computed-field formulas', () => {
  assert.equal(detectHelpCodeLang('quantity * unit_price'), 'formula')
  assert.equal(detectHelpCodeLang('SUM(help_pr_line.line_total)'), 'formula')
  assert.equal(detectHelpCodeLang('help_pr.request_title'), 'formula')
  assert.equal(detectHelpCodeLang('end_date - start_date'), 'formula')
})

test('does not treat UI labels with hyphens or plus as formulas', () => {
  assert.equal(detectHelpCodeLang('Reply-To'), null)
  assert.equal(detectHelpCodeLang('Message-ID'), null)
  assert.equal(detectHelpCodeLang('font-weight'), null)
  assert.equal(detectHelpCodeLang('Text + HTML (recommended)'), null)
  assert.equal(detectHelpCodeLang('Auto-claim on open'), null)
  assert.equal(isHelpCopyWorth('Reply-To'), false)
  assert.equal(isHelpCopyWorth('font-weight'), false)
})

test('keeps UI labels as labels', () => {
  assert.equal(resolveHelpCodeKind('Edit'), 'label')
  assert.equal(resolveHelpCodeKind('Direction'), 'label')
  assert.equal(resolveHelpCodeKind('userId / username / email'), 'label')
  assert.equal(resolveHelpCodeKind('beforeSubmit — return false to stop Save'), 'label')
  assert.equal(resolveHelpCodeKind('Field'), 'label')
  assert.equal(resolveHelpCodeKind('Show password'), 'label')
  assert.equal(resolveHelpCodeKind('Password'), 'label')
})

test('copy is only for pasteable event scripts and formulas', () => {
  assert.equal(isHelpCopyWorth("api.hidden(true, 'cost_center')"), true)
  assert.equal(
    isHelpCopyWorth("if (value) { api.setValue('requester', user && user.displayName) }"),
    true,
  )
  assert.equal(isHelpCopyWorth('var api = $inject.api'), true)
  assert.equal(isHelpCopyWorth('quantity * unit_price'), true)
  assert.equal(isHelpCopyWorth('SUM(help_pr_line.line_total)'), true)
  assert.equal(isHelpCopyWorth('Field'), false)
  assert.equal(isHelpCopyWorth('Show password'), false)
  assert.equal(isHelpCopyWorth('var current = value'), false)
  assert.equal(isHelpCopyWorth('var name = user && user.displayName'), false)
  assert.equal(isHelpCopyWorth('AND(...) / OR(...)'), false)
  assert.equal(isHelpCopyWorth('SWITCH(value, match1, result1, …, default?)'), false)
  assert.equal(isHelpCopyWorth('COALESCE(...)'), false)
  assert.equal(isHelpCopyWorth('Edit', 'label'), false)
})

test('parameter aliases and formula stubs stay labels', () => {
  assert.equal(resolveHelpCodeKind('var current = value'), 'label')
  assert.equal(resolveHelpCodeKind('AND(...) / OR(...)'), 'label')
  assert.equal(resolveHelpCodeKind('COALESCE(...)'), 'label')
})

test('formats if/else into pasteable lines', () => {
  const formatted = formatHelpSnippet(
    "if (value) { api.setValue('requester', user && user.displayName) }",
    'js',
  )
  assert.equal(
    formatted,
    "if (value) {\n  api.setValue('requester', user && user.displayName)\n}",
  )
})

test('formats if/else and inner statements', () => {
  const formatted = formatHelpSnippet(
    "if (!value) { api.setFieldError('request_title', 'Title is required') } else { api.clearFieldError('request_title') }",
    'js',
  )
  assert.equal(
    formatted,
    "if (!value) {\n  api.setFieldError('request_title', 'Title is required')\n} else {\n  api.clearFieldError('request_title')\n}",
  )
})

test('does not expand object literals inside calls', () => {
  const src = "api.setOptions('scenario', [{ label: 'A', value: 'A' }])"
  assert.equal(formatHelpSnippet(src, 'js'), src)
})

test('tokenizes keywords and strings', () => {
  const tokens = tokenizeHelpCode("if (value) {\n  api.setValue('requester', user && user.displayName)\n}")
  const types = tokens.filter((t) => t.text.trim()).map((t) => `${t.type}:${t.text}`)
  assert.ok(types.includes('kw:if'))
  assert.ok(types.includes("str:'requester'"))
  assert.ok(types.includes('fn:setValue'))
})
