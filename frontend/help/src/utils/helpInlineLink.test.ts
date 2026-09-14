import assert from 'node:assert/strict'
import test from 'node:test'
import { helpInlineTitleKey, parseHelpInlineText } from './helpInlineLink.ts'

test('resolves How to write events and Basic control paths', () => {
  assert.equal(helpInlineTitleKey('/form-events'), 'nav.formEventsHub')
  assert.equal(helpInlineTitleKey('/form-events#options'), 'nav.formEventsHub')
  assert.equal(helpInlineTitleKey('/form-ctl-textarea'), 'nav.formCtlTextarea')
  assert.equal(helpInlineTitleKey('/form-ctl-date#events'), 'nav.formCtlDate')
  assert.equal(helpInlineTitleKey('/form-events-extend#owner'), 'nav.formCtlOwner')
  assert.equal(helpInlineTitleKey('/form-upload'), 'guides.formUpload.title')
  assert.equal(helpInlineTitleKey('/form-ctl-lookup'), 'nav.formCtlLookup')
  assert.equal(helpInlineTitleKey('/table-design'), 'guides.tableDesign.title')
  assert.equal(helpInlineTitleKey('/view-design'), 'guides.viewDesign.title')
})

test('rejects unsafe or unknown tokens', () => {
  assert.equal(helpInlineTitleKey('javascript:alert(1)'), undefined)
  assert.equal(helpInlineTitleKey('//evil.example'), undefined)
  assert.equal(helpInlineTitleKey('/form-events?x=1'), undefined)
  assert.equal(helpInlineTitleKey('/no-such-guide'), undefined)
})

test('parses mixed copy into text and link parts', () => {
  const parts = parseHelpInlineText(
    'Full api methods are on [[/form-events]]. See [[/form-ctl-textarea]].',
  )
  assert.deepEqual(parts, [
    { kind: 'text', text: 'Full api methods are on ' },
    { kind: 'link', to: '/form-events', titleKey: 'nav.formEventsHub' },
    { kind: 'text', text: '. See ' },
    { kind: 'link', to: '/form-ctl-textarea', titleKey: 'nav.formCtlTextarea' },
    { kind: 'text', text: '.' },
  ])
})

test('parses Chinese copy around a How to write events token', () => {
  const parts = parseHelpInlineText(
    '完整 api 方法见 [[/form-events]]。',
  )
  assert.deepEqual(parts, [
    { kind: 'text', text: '完整 api 方法见 ' },
    { kind: 'link', to: '/form-events', titleKey: 'nav.formEventsHub' },
    { kind: 'text', text: '。' },
  ])
})

test('unknown token stays visible as text, not a link', () => {
  const parts = parseHelpInlineText('See [[/missing-page]].')
  assert.deepEqual(parts, [
    { kind: 'text', text: 'See ' },
    { kind: 'text', text: '[[/missing-page]]' },
    { kind: 'text', text: '.' },
  ])
})
