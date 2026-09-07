import { describe, it, expect } from 'vitest'
import { ref, computed } from 'vue'
import { flattenRuleLayoutContainers } from '../formDesigner'
import { useFormPreviewColumns } from '@/composables/formDesigner/useFormPreviewColumns'

describe('flattenRuleLayoutContainers', () => {
  it('expands card/row/col containers recursively, preserving document order', () => {
    const rule = [
      { type: 'input', field: 'a', title: 'A' },
      {
        type: 'el-row',
        children: [
          { type: 'el-col', children: [{ type: 'input', field: 'b', title: 'B' }] },
          { type: 'elCard', children: [{ type: 'select', field: 'c', title: 'C' }] },
        ],
      },
      { type: 'input', field: 'd', title: 'D' },
    ]
    expect(flattenRuleLayoutContainers(rule).map(r => r.field)).toEqual(['a', 'b', 'c', 'd'])
  })

  it('reads children from props.children as well', () => {
    const rule = [
      { type: 'card', props: { children: [{ type: 'input', field: 'x', title: 'X' }] } },
    ]
    expect(flattenRuleLayoutContainers(rule).map(r => r.field)).toEqual(['x'])
  })

  it('leaves placeholders and field-bearing rules untouched', () => {
    const subTable = { type: 'subTable', title: 'Sub-Table', props: {} }
    const fieldGroup = { type: 'group', field: 'g', title: 'Repeat Group' }
    const flat = flattenRuleLayoutContainers([subTable, fieldGroup])
    expect(flat[0]).toBe(subTable)
    expect(flat[1]).toBe(fieldGroup)
  })

  it('returns [] for non-array input', () => {
    expect(flattenRuleLayoutContainers(undefined as unknown as any[])).toEqual([])
  })
})

describe('useFormPreviewColumns deriveColumnsFromBinding with Card layout', () => {
  function makeComposable() {
    return useFormPreviewColumns({
      store: { tables: [] },
      selectedForm: ref(null),
      designerSubBindings: computed(() => []),
      relationViewState: ref({}),
      subTableViewState: ref({}),
      getSubTableFormDesign: () => ({ rule: [], options: {} }),
      resolveDesignerBindingDisplayName: () => '',
      t: (key: string) => key,
    })
  }

  it('derives columns for fields nested inside a Card (FU 50013 regression)', () => {
    const { deriveColumnsFromBinding } = makeComposable()
    const subForms = {
      50113: {
        rule: [
          {
            type: 'elCard',
            props: { header: 'Shipment Info' },
            children: [
              { type: 'input', field: 'shipment_name', title: 'Shipment Name' },
              { type: 'input', field: 'carrier', title: 'Carrier' },
            ],
          },
        ],
      },
    }
    const cols = deriveColumnsFromBinding({ bindingId: 50113 }, subForms)
    expect(cols.map((c: any) => c.field)).toEqual(['shipment_name', 'carrier'])
    expect(cols.map((c: any) => c.label)).toEqual(['Shipment Name', 'Carrier'])
    // No bogus fieldless column for the Card container itself
    expect(cols.some((c: any) => c.field === undefined)).toBe(false)
  })

  it('copies Advanced Upload maxFiles and maxFileSizeMb onto preview columns', () => {
    const { deriveColumnsFromBinding } = makeComposable()
    const cols = deriveColumnsFromBinding({ bindingId: 7 }, {
      7: {
        rule: [{
          type: 'advancedUpload',
          field: 'scan',
          title: 'Scan',
          props: { action: '/api/v1/upload', maxFiles: 1, maxFileSizeMb: 50 },
        }],
      },
    })
    expect(cols).toHaveLength(1)
    expect(cols[0].type).toBe('upload')
    expect(cols[0].props.maxFiles).toBe(1)
    expect(cols[0].props.maxFileSizeMb).toBe(50)
  })

  it('copies those limits from list-view columns that resolve a sub-form rule', () => {
    const selectedForm = ref({ tableBindings: [] } as any)
    const { toSubTablePreviewColumns } = useFormPreviewColumns({
      store: { tables: [] },
      selectedForm,
      designerSubBindings: computed(() => []),
      relationViewState: ref({}),
      subTableViewState: ref({}),
      getSubTableFormDesign: () => ({ rule: [], options: {} }),
      resolveDesignerBindingDisplayName: () => '',
      t: (key: string) => key,
    })
    const cols = toSubTablePreviewColumns(7, [{
      type: 'advancedUpload',
      field: 'scan',
      title: 'Scan',
      props: { action: '/api/v1/upload', maxFiles: 1, maxFileSizeMb: 50 },
    }], {
      subListViews: {
        7: { columns: [{ fieldName: 'scan', displayName: 'Scan', dataType: 'FILE' }] },
      },
    })
    const scan = cols.find((c: any) => c.field === 'scan')
    expect(scan?.props?.maxFiles).toBe(1)
    expect(scan?.props?.maxFileSizeMb).toBe(50)
  })
})
