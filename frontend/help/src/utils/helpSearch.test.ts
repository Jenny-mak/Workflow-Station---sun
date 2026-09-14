import assert from 'node:assert/strict'
import test from 'node:test'
import { filterHelpSearch, type HelpSearchEntry } from './helpSearch.ts'

const SAMPLE: HelpSearchEntry[] = [
  {
    id: 'lookup',
    path: '/form-ctl-lookup',
    titleKey: 'Lookup',
    summaryKey: 'Search-and-pick from a relation table',
  },
  {
    id: 'table-design',
    path: '/table-design',
    titleKey: 'Tables, fields, PK and FK',
    summaryKey: 'Create columns, primary keys, and foreign keys',
  },
  {
    id: 'view-design',
    path: '/view-design',
    titleKey: 'View Design',
    summaryKey: 'Who can see a view: Business Units and Roles',
  },
]

test('filterHelpSearch ranks title matches above summary', () => {
  const hits = filterHelpSearch(SAMPLE, 'lookup', (key) => key)
  assert.equal(hits[0]?.id, 'lookup')
})

test('filterHelpSearch finds view access by summary words', () => {
  const hits = filterHelpSearch(SAMPLE, 'business units', (key) => key)
  assert.equal(hits[0]?.id, 'view-design')
})

test('filterHelpSearch matches kebab path from spaced query', () => {
  const hits = filterHelpSearch(SAMPLE, 'form ctl lookup', (key) => key)
  assert.equal(hits[0]?.id, 'lookup')
})

test('filterHelpSearch ignores blank query', () => {
  assert.deepEqual(filterHelpSearch(SAMPLE, '  ', (key) => key), [])
})
