import type { GuideRelated, GuideSample } from '@/components/GuideArticle.vue'
import type { FormCtlDef } from '@/formCtlBasic'

function row(id: string, code: string, hint: string): GuideSample {
  return { code, hintKey: `formCtl.${id}.${hint}` }
}

function shared(code: string, hint: string): GuideSample {
  return { code, hintKey: `formCtl.shared.${hint}` }
}

export const EXTEND_FLOW_KEYS = [
  'formCtl.extendFlow1',
  'formCtl.extendFlow2',
  'formCtl.extendFlow3',
  'formCtl.extendFlow4',
  'formCtl.extendFlow5',
] as const

const EXTEND: FormCtlDef[] = [
  {
    id: 'subTable',
    path: '/form-ctl-sub-table',
    oldHash: 'subTable',
    navTitleKey: 'nav.formCtlSubTable',
    figure: 'guides/dw-form-ctl-sub-table-props.png',
    group: 'extend',
    includeBasis: false,
    includeStyle: false,
    flowKeys: [...EXTEND_FLOW_KEYS],
    eventSamples: [
      {
        code: "if (!value || !value.length) { api.setFieldError(field, 'Add at least one line') } else { api.clearFieldError(field) }",
        hintKey: 'formCtl.subTable.eventHint',
      },
    ],
    catalog: [
      row('subTable', 'Sub Table Binding', 'catBinding'),
      row('subTable', 'Select Sub Table', 'catSelect'),
      row('subTable', 'Allow Add', 'catAllowAdd'),
      row('subTable', 'Allow Edit', 'catAllowEdit'),
      row('subTable', 'Allow Delete', 'catAllowDelete'),
      row('subTable', 'Compact cells', 'catCompact'),
      row('subTable', 'Form Design', 'catFormDesign'),
      row('subTable', 'List View', 'catListView'),
      row('subTable', 'Table Columns', 'catColumns'),
      row('subTable', 'Extend Action', 'catExtendAction'),
    ],
    failKeys: ['failBinding', 'failType', 'failSave'],
    relatedIds: ['lookup'],
  },
  {
    id: 'lookup',
    path: '/form-ctl-lookup',
    oldHash: 'lookup',
    navTitleKey: 'nav.formCtlLookup',
    group: 'extend',
    includeBasis: true,
    includeStyle: true,
    flowKeys: [...EXTEND_FLOW_KEYS],
    eventSamples: [
      {
        code: "if (value) { api.setValue('cost_center', value) }",
        hintKey: 'formCtl.lookup.eventHint',
      },
      {
        code: "api.setLookupFilter(field, [{ fieldName: 'status', value: 'Active', matchType: 'eq' }])\napi.refresh(field)",
        hintKey: 'formCtl.lookup.eventHintFilter',
      },
    ],
    catalog: [
      shared('Placeholder', 'placeholder'),
      shared('Readonly', 'readonly'),
      row('lookup', 'Lookup Config', 'catConfig'),
      row('lookup', 'Relation Table', 'catRelation'),
      row('lookup', 'Search Fields', 'catSearch'),
      row('lookup', 'Display Fields', 'catDisplay'),
      row('lookup', 'Selected Display Field', 'catSelected'),
      row('lookup', 'Fixed Filters', 'catFilters'),
      row('lookup', 'Field', 'catFilterField'),
      row('lookup', 'Match', 'catFilterMatch'),
      row('lookup', 'Value', 'catFilterValue'),
      row('lookup', 'Derived / Cascade Field', 'catDerived'),
      row('lookup', 'Behavior', 'catDerivedMode'),
      row('lookup', 'Join Columns', 'catJoins'),
      row('lookup', 'Allow multiple', 'catMultiple'),
      row('lookup', 'Backfill View', 'catBackfill'),
    ],
    failKeys: ['failField', 'failRelation', 'failSave'],
    relatedIds: ['subTable'],
  },
]

export const EXTEND_FORM_CONTROLS: readonly FormCtlDef[] = EXTEND

export const EXTEND_HASH_REDIRECTS: Record<string, string> = Object.fromEntries(
  EXTEND.map((c) => [c.oldHash, c.path]),
)

export const EXTEND_INDEX_RELATED: GuideRelated[] = [
  { to: '/form-ctl-sub-table', titleKey: 'nav.formCtlSubTable' },
  { to: '/form-events-extend#inlineSubForm', titleKey: 'nav.formCtlInlineForm' },
  { to: '/form-events-extend#linkForm', titleKey: 'nav.formCtlLinkForm' },
  { to: '/form-ctl-lookup', titleKey: 'nav.formCtlLookup' },
  { to: '/form-events-extend#owner', titleKey: 'nav.formCtlOwner' },
  { to: '/form-events-extend#recordNote', titleKey: 'nav.formCtlRecordNote' },
  { to: '/form-events-extend#miAssignment', titleKey: 'nav.formCtlMiAssignment' },
  { to: '/form-upload', titleKey: 'guides.formUpload.title' },
]
