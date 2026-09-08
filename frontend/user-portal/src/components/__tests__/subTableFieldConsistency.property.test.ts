import { describe, it, expect, vi } from 'vitest'
import * as fc from 'fast-check'

// fast-check properties here run hundreds of iterations; under full-suite parallel workers
// they intermittently exceed the 5s default timeout (they pass in isolation).
vi.setConfig({ testTimeout: 30_000 })

import {
  buildInitialRow,
  resolveDisplayValue,
  resolveLookupCellTagText,
  unwrapSingleLookupModelValue,
  type DialogColumn,
  type ColumnType,
} from '../subTableAddDialogHelpers'

// ─── Inline deriveColumnsFromBinding (mirrors the logic in the three view files) ──
// This is a pure extraction of the mapping logic for testability.
type DerivedColumn = {
  field: string
  label: string
  type?: string
  required?: boolean
  options?: Array<{ label: string; value: any }>
  props?: Record<string, any>
}

function deriveColumnsFromBinding(
  binding: { bindingId: string | number },
  subForms: Record<string, { rule: any[] }>,
): DerivedColumn[] {
  const subFormRule = subForms?.[binding.bindingId]?.rule
  if (!subFormRule || !Array.isArray(subFormRule) || subFormRule.length === 0) return []

  return subFormRule.map((r: any) => {
    const rProps = r.props || {}
    let type: string | undefined

    if (r.type === 'input') {
      if (rProps.type === 'textarea') type = 'textarea'
      else if (rProps.type === 'password') type = 'password'
      else type = 'text'
    } else if (r.type === 'inputNumber') {
      type = 'number'
    } else if (r.type === 'select') {
      type = rProps.multiple === true ? 'checkbox' : 'select'
    } else if (r.type === 'radio') {
      type = 'radio'
    } else if (r.type === 'switch') {
      type = 'switch'
    } else if (r.type === 'datePicker') {
      type = rProps.type === 'datetime' ? 'datetime' : 'date'
    } else if (r.type === 'timePicker') {
      type = rProps.isRange === true ? 'timerange' : 'time'
    } else if (r.type === 'treeSelect') {
      type = 'treeselect'
    } else if (r.type === 'upload' || r.type === 'advancedUpload') {
      type = 'upload'
    } else if (r.type === 'userSelect' || r.type === 'user') {
      type = 'user'
    } else if (r.type === 'departmentSelect' || r.type === 'department') {
      type = 'department'
    }

    const rawOptions = r.options || rProps.options
    const options = rawOptions
      ? (type === 'cascader' ? rawOptions : rawOptions.map((o: any) => ({ label: o.label ?? o.value, value: o.value })))
      : undefined

    const passProps: Record<string, any> = {}
    const propKeys = [
      'action', 'accept', 'multiple', 'precision', 'min', 'max', 'rows', 'maxlength', 'fileNameTargetField', 'cannotDownload',
      'isRange', 'valueFormat', 'startPlaceholder', 'endPlaceholder', 'treeData', 'checkStrictly',
    ]
    for (const key of propKeys) {
      if (rProps[key] !== undefined) passProps[key] = rProps[key]
    }

    // Sync options into props.options
    if (options) passProps.options = options

    const required = r.validate?.some((v: any) => v.required) || false

    return {
      field: r.field,
      label: r.title || r.field,
      type,
      required,
      ...(options ? { options } : {}),
      ...(Object.keys(passProps).length > 0 ? { props: passProps } : {}),
    }
  })
}

// ─── Arbitraries ────────────────────────────────────────────────────────────

const allColumnTypes: ColumnType[] = [
  'text', 'textarea', 'number', 'select', 'radio', 'checkbox',
  'switch', 'date', 'datetime', 'upload', 'user', 'department',
  'password', 'timerange', 'treeselect',
]

const columnTypeArb = fc.constantFrom(...allColumnTypes)

const nonEmptyStringArb = fc.string({ minLength: 1, maxLength: 20 })

const optionArb = fc.record({
  label: nonEmptyStringArb,
  value: nonEmptyStringArb,
})

// Unique values: resolveDisplayValue returns the FIRST option matching a value,
// so duplicate generated values would make the expected label ambiguous.
const optionsArb = fc.uniqueArray(optionArb, {
  minLength: 1,
  maxLength: 10,
  selector: (o) => o.value,
})

// ─── Property 3: buildInitialRow covers all types ────────────────────────────

describe('Property 3: buildInitialRow covers all types', () => {
  // Feature: sub-table-field-consistency, Property 3: buildInitialRow covers all types
  // Validates: Requirements 5.5, 6.5
  it('every column field is present as a key with the correct initial value', () => {
    fc.assert(
      fc.property(
        fc.array(
          fc.record({
            field: nonEmptyStringArb,
            type: columnTypeArb,
          }),
          { minLength: 1, maxLength: 15 },
        ),
        (rawCols) => {
          // Deduplicate fields to avoid ambiguity
          const seen = new Set<string>()
          const columns: DialogColumn[] = rawCols
            .filter((c) => {
              if (seen.has(c.field)) return false
              seen.add(c.field)
              return true
            })
            .map((c) => ({ field: c.field, label: c.field, type: c.type }))

          const row = buildInitialRow(columns)

          for (const col of columns) {
            expect(Object.keys(row)).toContain(col.field)

            if (col.type === 'timerange') {
              expect(row[col.field]).toBeNull()
            } else if (col.type === 'password') {
              expect(row[col.field]).toBe('')
            } else if (col.type === 'checkbox') {
              expect(Array.isArray(row[col.field])).toBe(true)
            } else if (col.type === 'treeselect') {
              // single-select (no props.multiple) → ''
              expect(row[col.field]).toBe('')
            } else if (col.type === 'number') {
              expect(row[col.field]).toBeUndefined()
            } else if (col.type === 'switch') {
              expect(row[col.field]).toBe(false)
            } else if (col.type === 'date' || col.type === 'datetime') {
              expect(row[col.field]).toBeNull()
            } else {
              expect(row[col.field]).toBe('')
            }
          }
        },
      ),
      { numRuns: 100 },
    )
  })
})

describe('Lookup selected display field', () => {
  it('resolveDisplayValue prefers selectedDisplayField over displayFields[0]', () => {
    const col: DialogColumn = {
      field: 'assignee',
      label: 'assignee',
      type: 'lookup',
      props: {
        displayFields: ['id', 'username'],
        selectedDisplayField: 'username',
      },
    }
    expect(resolveDisplayValue(col, { id: 'user-e2e-lina', username: 'e2e lina' })).toBe('e2e lina')
  })

  it('getLookupSelectedDisplayField reads selectedDisplayField from lookupConfig JSON', () => {
    const col: DialogColumn = {
      field: 'assignee',
      label: 'assignee',
      type: 'lookup',
      props: {
        lookupConfig: JSON.stringify({
          displayFields: ['id', 'username'],
          selectedDisplayField: 'username',
        }),
        displayFields: ['id'],
      },
    }
    expect(resolveDisplayValue(col, { id: 'user-e2e-lina', username: 'e2e lina' })).toBe('e2e lina')
  })

  it('resolveLookupCellTagText reads selectedDisplayField only from lookupConfig on props', () => {
    const props = {
      lookupConfig: JSON.stringify({
        displayFields: ['id', 'username'],
        selectedDisplayField: 'username',
        searchFields: ['id'],
      }),
      displayFields: ['id', 'username'],
      displayField: 'id',
    }
    expect(
      resolveLookupCellTagText(props, { id: 'user-e2e-lina', username: 'e2e lina' }),
    ).toBe('e2e lina')
  })

  it('resolveLookupCellTagText prefers username over PK when props only carry lookupConfig', () => {
    const props = {
      lookupConfig: JSON.stringify({
        searchFields: ['id'],
        displayFields: ['username'],
        selectedDisplayField: 'username',
      }),
      searchFields: ['id'],
    }
    const hydrated = {
      id: 'c9c70955-37cb-4d17-b3f7-2e01ddd34bab',
      username: '45201959',
    }
    expect(resolveLookupCellTagText(props, hydrated)).toBe('45201959')
  })

  it('resolveLookupCellTagText does not treat PK as display when selectedDisplayField missing', () => {
    const props = {
      lookupConfig: JSON.stringify({
        searchFields: ['id'],
        displayFields: ['username'],
      }),
      searchFields: ['id'],
    }
    expect(
      resolveLookupCellTagText(props, {
        id: 'c9c70955-37cb-4d17-b3f7-2e01ddd34bab',
        username: '45201959',
      }),
    ).toBe('45201959')
  })

  it('resolveLookupCellTagText returns dash for scalar-only synthetic row until hydrated', () => {
    const props = {
      lookupConfig: JSON.stringify({
        searchFields: ['id'],
        displayFields: ['username'],
        selectedDisplayField: 'username',
      }),
      searchFields: ['id'],
    }
    expect(
      resolveLookupCellTagText(props, { id: 'c9c70955-37cb-4d17-b3f7-2e01ddd34bab' }),
    ).toBe('-')
  })

  it('unwrapSingleLookupModelValue takes first row from multi LOOKUP array for single-select forms', () => {
    const rows = [
      { status_id: '4', status_name: 'A llll' },
      { status_id: '5', status_name: 'A d dddd' },
    ]
    expect(unwrapSingleLookupModelValue(rows)).toEqual(rows[0])
    expect(unwrapSingleLookupModelValue(null)).toBe(null)
    expect(unwrapSingleLookupModelValue({ status_id: '4' })).toEqual({ status_id: '4' })
  })

  it('resolveLookupCellTagText uses username when cell stores full user snapshot object', () => {
    const props = {
      lookupConfig: JSON.stringify({
        searchFields: ['id'],
        displayFields: ['username'],
        selectedDisplayField: 'username',
      }),
      searchFields: ['id'],
    }
    const row = {
      id: 'c9c70955-37cb-4d17-b3f7-2e01ddd34bab',
      username: '45201959',
      full_name: '45201959',
      email: '45201959@qq.com',
      status: 'ACTIVE',
    }
    expect(resolveLookupCellTagText(props, row)).toBe('45201959')
  })
})

// ─── Property 5: Password field masked display ───────────────────────────────

describe('Property 5: Password field masked display', () => {
  // Feature: sub-table-field-consistency, Property 5: Password field masked display
  // Validates: Requirements 2.6
  it('resolveDisplayValue returns "••••••" for any non-empty password value', () => {
    fc.assert(
      fc.property(
        fc.string({ minLength: 1 }),
        (rawValue) => {
          const col: DialogColumn = { field: 'pwd', label: 'Password', type: 'password' }
          expect(resolveDisplayValue(col, rawValue)).toBe('••••••')
        },
      ),
      { numRuns: 100 },
    )
  })
})

describe('Sensitive mask list-cell display', () => {
  it('resolveDisplayValue applies last4 mask for text columns with sensitiveMask', () => {
    const col: DialogColumn = {
      field: 'card',
      label: 'Card',
      type: 'text',
      props: {
        sensitiveMask: {
          enabled: true,
          preset: 'last4',
          keepPrefix: 0,
          keepSuffix: 4,
          maskChar: '*',
        },
      },
    }
    expect(resolveDisplayValue(col, '6222021234567890')).toBe('************7890')
  })
})

// ─── Property 6: Timerange formatted display ─────────────────────────────────

describe('Property 6: Timerange formatted display', () => {
  // Feature: sub-table-field-consistency, Property 6: Timerange formatted display
  // Validates: Requirements 5.7
  it('resolveDisplayValue returns "start - end" for a two-element array', () => {
    fc.assert(
      fc.property(
        fc.tuple(nonEmptyStringArb, nonEmptyStringArb),
        ([start, end]) => {
          const col: DialogColumn = { field: 'tr', label: 'Time Range', type: 'timerange' }
          expect(resolveDisplayValue(col, [start, end])).toBe(`${start} - ${end}`)
        },
      ),
      { numRuns: 100 },
    )
  })
})

// ─── Property 4: Option value to label resolution ────────────────────────────

describe('Property 4: Option value to label resolution', () => {
  // Feature: sub-table-field-consistency, Property 4: Option value to label resolution
  // Validates: Requirements 1.7, 3.3, 4.3
  it('resolveDisplayValue returns the option label for radio/select when value exists in options', () => {
    fc.assert(
      fc.property(
        fc.constantFrom('radio' as ColumnType, 'select' as ColumnType),
        optionsArb,
        fc.nat(),
        (type, options, idx) => {
          const picked = options[idx % options.length]
          const col: DialogColumn = {
            field: 'f',
            label: 'F',
            type,
            props: { options },
          }
          expect(resolveDisplayValue(col, picked.value)).toBe(picked.label)
        },
      ),
      { numRuns: 100 },
    )
  })

  it('resolveDisplayValue returns comma-separated labels for checkbox when values exist in options', () => {
    fc.assert(
      fc.property(
        optionsArb,
        fc.nat(),
        (options, idx) => {
          const picked = options[idx % options.length]
          const col: DialogColumn = {
            field: 'f',
            label: 'F',
            type: 'checkbox',
            props: { options },
          }
          const result = resolveDisplayValue(col, [picked.value])
          expect(result).toBe(picked.label)
        },
      ),
      { numRuns: 100 },
    )
  })
})

// ─── Property 1: Options path consistency ────────────────────────────────────

describe('Property 1: Options path consistency', () => {
  // Feature: sub-table-field-consistency, Property 1: Options path consistency
  // Validates: Requirements 1.4, 1.5, 1.6, 3.1, 4.1
  it('for select/radio/checkbox columns, props.options equals the top-level options array', () => {
    const optionTypeArb = fc.constantFrom('select', 'radio', 'checkbox')
    const optionItemArb = fc.record({
      label: fc.string({ minLength: 1, maxLength: 20 }),
      value: fc.string({ minLength: 1, maxLength: 20 }),
    })

    fc.assert(
      fc.property(
        fc.array(
          fc.record({
            field: fc.string({ minLength: 1, maxLength: 10 }),
            type: optionTypeArb,
            options: fc.array(optionItemArb, { minLength: 1, maxLength: 5 }),
          }),
          { minLength: 1, maxLength: 8 },
        ),
        (rawRules) => {
          // Deduplicate fields
          const seen = new Set<string>()
          const rules = rawRules.filter((r) => {
            if (seen.has(r.field)) return false
            seen.add(r.field)
            return true
          })

          // Build subForm rules in form-create format
          const subFormRules = rules.map((r) => ({
            field: r.field,
            title: r.field,
            // checkbox maps from select with multiple=true
            type: r.type === 'checkbox' ? 'select' : r.type,
            props: {
              multiple: r.type === 'checkbox',
              options: r.options,
            },
          }))

          const columns = deriveColumnsFromBinding(
            { bindingId: 'test' },
            { test: { rule: subFormRules } },
          )

          for (const col of columns) {
            // top-level options must exist
            expect(col.options).toBeDefined()
            // props.options must also exist and equal top-level options
            expect(col.props?.options).toBeDefined()
            expect(col.props?.options).toEqual(col.options)
          }
        },
      ),
      { numRuns: 100 },
    )
  })
})

// ─── Property 2: Type mapping completeness ───────────────────────────────────

describe('Property 2: Type mapping completeness', () => {
  // Feature: sub-table-field-consistency, Property 2: Type mapping completeness
  // Validates: Requirements 2.3, 5.3, 6.3, 8.4
  it('every known form-create rule type produces a defined DialogColumn type', () => {
    type RuleSpec = { ruleType: string; props: Record<string, any>; expectedType: string }
    const ruleSpecs: RuleSpec[] = [
      { ruleType: 'input', props: {}, expectedType: 'text' },
      { ruleType: 'input', props: { type: 'textarea' }, expectedType: 'textarea' },
      { ruleType: 'input', props: { type: 'password' }, expectedType: 'password' },
      { ruleType: 'inputNumber', props: {}, expectedType: 'number' },
      { ruleType: 'select', props: {}, expectedType: 'select' },
      { ruleType: 'select', props: { multiple: true }, expectedType: 'checkbox' },
      { ruleType: 'radio', props: {}, expectedType: 'radio' },
      { ruleType: 'switch', props: {}, expectedType: 'switch' },
      { ruleType: 'datePicker', props: {}, expectedType: 'date' },
      { ruleType: 'datePicker', props: { type: 'datetime' }, expectedType: 'datetime' },
      { ruleType: 'timePicker', props: {}, expectedType: 'time' },
      { ruleType: 'timePicker', props: { isRange: true }, expectedType: 'timerange' },
      { ruleType: 'treeSelect', props: {}, expectedType: 'treeselect' },
      { ruleType: 'upload', props: {}, expectedType: 'upload' },
      { ruleType: 'userSelect', props: {}, expectedType: 'user' },
      { ruleType: 'departmentSelect', props: {}, expectedType: 'department' },
    ]

    fc.assert(
      fc.property(
        fc.constantFrom(...ruleSpecs),
        fc.string({ minLength: 1, maxLength: 10 }),
        (spec, fieldName) => {
          const columns = deriveColumnsFromBinding(
            { bindingId: 'test' },
            {
              test: {
                rule: [{ field: fieldName, title: fieldName, type: spec.ruleType, props: spec.props }],
              },
            },
          )

          expect(columns).toHaveLength(1)
          expect(columns[0].type).toBe(spec.expectedType)
        },
      ),
      { numRuns: 100 },
    )
  })
})
