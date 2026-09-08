import type { Component } from 'vue'

export interface Guideline {
  id: string
  path: string
  titleKey: string
  summaryKey: string
  load: () => Promise<{ default: Component }>
}

export interface NavLeaf {
  kind: 'leaf'
  id: string
  titleKey: string
  /** Article path, optional hash, e.g. /email-send#connection */
  to?: string
  /** Sidebar only — skip the home card grid (per-control leaves). */
  omitFromHome?: boolean
}

/** Leaf that already has a guideline `to` (home cards, not grey menus). */
export type NavLeafWithArticle = NavLeaf & { to: string }

export interface NavGroup {
  kind: 'group'
  id: string
  titleKey: string
  open?: boolean
  children: NavNode[]
}

export type NavNode = NavGroup | NavLeaf

/** Add a guideline: append here, then add the view + i18n keys + a NAV_TREE leaf. */
export const GUIDELINES: Guideline[] = [
  {
    id: 'computed-fields',
    path: '/computed-fields',
    titleKey: 'guides.computedFields.title',
    summaryKey: 'guides.computedFields.summary',
    load: () => import('@/views/ComputedFieldGuide.vue'),
  },
  {
    id: 'email-send',
    path: '/email-send',
    titleKey: 'guides.emailSend.title',
    summaryKey: 'guides.emailSend.summary',
    load: () => import('@/views/EmailSendGuide.vue'),
  },
  {
    id: 'email-monitor',
    path: '/email-monitor',
    titleKey: 'guides.emailMonitor.title',
    summaryKey: 'guides.emailMonitor.summary',
    load: () => import('@/views/EmailMonitorGuide.vue'),
  },
  {
    id: 'up-tasks-to-claim',
    path: '/up-tasks-to-claim',
    titleKey: 'guides.upTasksToClaim.title',
    summaryKey: 'guides.upTasksToClaim.summary',
    load: () => import('@/views/UpTasksToClaimGuide.vue'),
  },
  {
    id: 'task-delegate',
    path: '/task-delegate',
    titleKey: 'guides.taskDelegate.title',
    summaryKey: 'guides.taskDelegate.summary',
    load: () => import('@/views/TaskDelegateGuide.vue'),
  },
  {
    id: 'form-upload',
    path: '/form-upload',
    titleKey: 'guides.formUpload.title',
    summaryKey: 'guides.formUpload.summary',
    load: () => import('@/views/FormUploadGuide.vue'),
  },
  {
    id: 'form-events',
    path: '/form-events',
    titleKey: 'guides.formEvents.title',
    summaryKey: 'guides.formEvents.summary',
    load: () => import('@/views/FormEventsGuide.vue'),
  },
  {
    id: 'form-events-basic',
    path: '/form-events-basic',
    titleKey: 'guides.formEventsBasic.title',
    summaryKey: 'guides.formEventsBasic.summary',
    load: () => import('@/views/FormEventsBasicGuide.vue'),
  },
  {
    id: 'form-events-extend',
    path: '/form-events-extend',
    titleKey: 'guides.formEventsExtend.title',
    summaryKey: 'guides.formEventsExtend.summary',
    load: () => import('@/views/FormEventsExtendGuide.vue'),
  },
  {
    id: 'form-events-layout',
    path: '/form-events-layout',
    titleKey: 'guides.formEventsLayout.title',
    summaryKey: 'guides.formEventsLayout.summary',
    load: () => import('@/views/FormEventsLayoutGuide.vue'),
  },
]

function formControlLeaf(id: string, titleKey: string, to: string): NavLeaf {
  return { kind: 'leaf', id, titleKey, to, omitFromHome: true }
}

const FORM_DESIGN_NAV: NavGroup = {
  kind: 'group',
  id: 'dw-form-design',
  titleKey: 'nav.formDesign',
  children: [
    { kind: 'leaf', id: 'dw-form-upload', titleKey: 'guides.formUpload.title', to: '/form-upload' },
    {
      kind: 'group',
      id: 'dw-form-controls',
      titleKey: 'nav.formControls',
      children: [
        { kind: 'leaf', id: 'dw-forms', titleKey: 'nav.formEventsHub', to: '/form-events' },
        {
          kind: 'group',
          id: 'dw-form-basic',
          titleKey: 'nav.formMenuBasic',
          children: [
            formControlLeaf('dw-form-ctl-input', 'nav.formCtlInput', '/form-events-basic#input'),
            formControlLeaf('dw-form-ctl-textarea', 'nav.formCtlTextarea', '/form-events-basic#textarea'),
            formControlLeaf('dw-form-ctl-password', 'nav.formCtlPassword', '/form-events-basic#password'),
            formControlLeaf('dw-form-ctl-inputNumber', 'nav.formCtlInputNumber', '/form-events-basic#inputNumber'),
            formControlLeaf('dw-form-ctl-radio', 'nav.formCtlRadio', '/form-events-basic#radio'),
            formControlLeaf('dw-form-ctl-checkbox', 'nav.formCtlCheckbox', '/form-events-basic#checkbox'),
            formControlLeaf('dw-form-ctl-select', 'nav.formCtlSelect', '/form-events-basic#select'),
            formControlLeaf('dw-form-ctl-switch', 'nav.formCtlSwitch', '/form-events-basic#switch'),
            formControlLeaf('dw-form-ctl-slider', 'nav.formCtlSlider', '/form-events-basic#slider'),
            formControlLeaf('dw-form-ctl-rate', 'nav.formCtlRate', '/form-events-basic#rate'),
            formControlLeaf('dw-form-ctl-date', 'nav.formCtlDate', '/form-events-basic#datePicker'),
            formControlLeaf('dw-form-ctl-dateRange', 'nav.formCtlDateRange', '/form-events-basic#dateRange'),
            formControlLeaf('dw-form-ctl-time', 'nav.formCtlTime', '/form-events-basic#timePicker'),
            formControlLeaf('dw-form-ctl-timeRange', 'nav.formCtlTimeRange', '/form-events-basic#timeRange'),
            formControlLeaf('dw-form-ctl-cascader', 'nav.formCtlCascader', '/form-events-basic#cascader'),
            formControlLeaf('dw-form-ctl-color', 'nav.formCtlColorPicker', '/form-events-basic#colorPicker'),
            formControlLeaf('dw-form-ctl-upload', 'nav.formCtlUpload', '/form-events-basic#upload'),
            formControlLeaf('dw-form-ctl-tree', 'nav.formCtlTree', '/form-events-basic#tree'),
            formControlLeaf('dw-form-ctl-treeSelect', 'nav.formCtlTreeSelect', '/form-events-basic#elTreeSelect'),
            formControlLeaf('dw-form-ctl-transfer', 'nav.formCtlTransfer', '/form-events-basic#transfer'),
            formControlLeaf('dw-form-ctl-editor', 'nav.formCtlEditor', '/form-events-basic#editor'),
          ],
        },
        {
          kind: 'group',
          id: 'dw-form-extend',
          titleKey: 'nav.formMenuExtend',
          children: [
            formControlLeaf('dw-form-ctl-subTable', 'nav.formCtlSubTable', '/form-events-extend#subTable'),
            formControlLeaf('dw-form-ctl-inline', 'nav.formCtlInlineForm', '/form-events-extend#inlineSubForm'),
            formControlLeaf('dw-form-ctl-linkForm', 'nav.formCtlLinkForm', '/form-events-extend#linkForm'),
            formControlLeaf('dw-form-ctl-lookup', 'nav.formCtlLookup', '/form-events-extend#lookup'),
            formControlLeaf('dw-form-ctl-owner', 'nav.formCtlOwner', '/form-events-extend#owner'),
            formControlLeaf('dw-form-ctl-recordNote', 'nav.formCtlRecordNote', '/form-events-extend#recordNote'),
          ],
        },
        {
          kind: 'group',
          id: 'dw-form-mi',
          titleKey: 'nav.formMenuMi',
          children: [
            formControlLeaf('dw-form-ctl-mi', 'nav.formCtlMiAssignment', '/form-events-extend#miAssignment'),
          ],
        },
        {
          kind: 'group',
          id: 'dw-form-layout',
          titleKey: 'nav.formMenuLayout',
          children: [
            formControlLeaf('dw-form-ctl-row', 'nav.formCtlRow', '/form-events-layout#fcRow'),
            formControlLeaf('dw-form-ctl-col', 'nav.formCtlCol', '/form-events-layout#col'),
            formControlLeaf('dw-form-ctl-card', 'nav.formCtlCard', '/form-events-layout#elCard'),
            formControlLeaf('dw-form-ctl-tabs', 'nav.formCtlTabs', '/form-events-layout#elTabs'),
            formControlLeaf('dw-form-ctl-tabPane', 'nav.formCtlTabPane', '/form-events-layout#elTabPane'),
            formControlLeaf('dw-form-ctl-collapse', 'nav.formCtlCollapse', '/form-events-layout#elCollapse'),
            formControlLeaf('dw-form-ctl-collapseItem', 'nav.formCtlCollapseItem', '/form-events-layout#elCollapseItem'),
            formControlLeaf('dw-form-ctl-title', 'nav.formCtlTitle', '/form-events-layout#fcTitle'),
            formControlLeaf('dw-form-ctl-html', 'nav.formCtlHtml', '/form-events-layout#html'),
            formControlLeaf('dw-form-ctl-divider', 'nav.formCtlDivider', '/form-events-layout#elDivider'),
            formControlLeaf('dw-form-ctl-alert', 'nav.formCtlAlert', '/form-events-layout#elAlert'),
            formControlLeaf('dw-form-ctl-space', 'nav.formCtlSpace', '/form-events-layout#space'),
          ],
        },
        {
          kind: 'group',
          id: 'dw-form-aide',
          titleKey: 'nav.formMenuAide',
          children: [
            formControlLeaf('dw-form-ctl-button', 'nav.formCtlButton', '/form-events-layout#elButton'),
            formControlLeaf('dw-form-ctl-tag', 'nav.formCtlTag', '/form-events-layout#elTag'),
            formControlLeaf('dw-form-ctl-image', 'nav.formCtlImage', '/form-events-layout#elImage'),
          ],
        },
      ],
    },
  ],
}
/**
 * Mirrors the three product sidebars (and Function Unit designer tabs).
 * Leaves without `to` are menus that exist in the product but have no article yet.
 */
export const NAV_TREE: NavNode[] = [
  {
    kind: 'group',
    id: 'dw',
    titleKey: 'nav.dw',
    open: true,
    children: [
      {
        kind: 'group',
        id: 'dw-fu',
        titleKey: 'nav.functionUnits',
        open: true,
        children: [
          { kind: 'leaf', id: 'dw-process', titleKey: 'nav.processDesign' },
          {
            kind: 'leaf',
            id: 'dw-tables',
            titleKey: 'nav.tableDesign',
            to: '/computed-fields',
          },
          FORM_DESIGN_NAV,
          { kind: 'leaf', id: 'dw-view', titleKey: 'nav.viewDesign' },
          { kind: 'leaf', id: 'dw-action', titleKey: 'nav.actionDesign' },
          { kind: 'leaf', id: 'dw-fu-automation', titleKey: 'nav.fuAutomation' },
          {
            kind: 'leaf',
            id: 'dw-connections',
            titleKey: 'nav.connections',
            to: '/email-send#connection',
          },
          {
            kind: 'leaf',
            id: 'dw-email-templates',
            titleKey: 'nav.emailTemplates',
            to: '/email-send#template',
          },
          {
            kind: 'leaf',
            id: 'dw-email-monitors',
            titleKey: 'nav.emailMonitors',
            to: '/email-monitor',
          },
          { kind: 'leaf', id: 'dw-decisions', titleKey: 'nav.decisionDesign' },
          { kind: 'leaf', id: 'dw-versions', titleKey: 'nav.versionManagement' },
        ],
      },
      { kind: 'leaf', id: 'dw-automation', titleKey: 'nav.automation' },
    ],
  },
  {
    kind: 'group',
    id: 'admin',
    titleKey: 'nav.admin',
    children: [
      { kind: 'leaf', id: 'ac-dashboard', titleKey: 'nav.acDashboard' },
      { kind: 'leaf', id: 'ac-users', titleKey: 'nav.acUsers' },
      {
        kind: 'group',
        id: 'ac-entitlement',
        titleKey: 'nav.acEntitlement',
        children: [
          { kind: 'leaf', id: 'ac-org', titleKey: 'nav.acOrganization' },
          { kind: 'leaf', id: 'ac-vg', titleKey: 'nav.acVirtualGroup' },
          { kind: 'leaf', id: 'ac-role', titleKey: 'nav.acRoles' },
        ],
      },
      { kind: 'leaf', id: 'ac-fu', titleKey: 'nav.acFunctionUnit' },
      {
        kind: 'group',
        id: 'ac-bi',
        titleKey: 'nav.acBi',
        children: [
          { kind: 'leaf', id: 'ac-bi-reg', titleKey: 'nav.acBiRegistry' },
          { kind: 'leaf', id: 'ac-bi-assign', titleKey: 'nav.acBiAssignment' },
          { kind: 'leaf', id: 'ac-bi-rbac', titleKey: 'nav.acBiRbac' },
        ],
      },
      {
        kind: 'group',
        id: 'ac-audit',
        titleKey: 'nav.acAudit',
        children: [
          { kind: 'leaf', id: 'ac-audit-admin', titleKey: 'nav.acAuditAdmin' },
          { kind: 'leaf', id: 'ac-audit-portal', titleKey: 'nav.acAuditPortal' },
        ],
      },
      {
        kind: 'group',
        id: 'ac-rt',
        titleKey: 'nav.acRelationTables',
        children: [
          {
            kind: 'leaf',
            id: 'ac-rt-struct',
            titleKey: 'nav.acTableStructure',
            to: '/computed-fields#relation',
          },
          { kind: 'leaf', id: 'ac-rt-data', titleKey: 'nav.acTableData' },
        ],
      },
      { kind: 'leaf', id: 'ac-pieces', titleKey: 'nav.acPieces' },
      { kind: 'leaf', id: 'ac-flows', titleKey: 'nav.acFlowMigration' },
      { kind: 'leaf', id: 'ac-config', titleKey: 'nav.acSystemConfig' },
    ],
  },
  {
    kind: 'group',
    id: 'portal',
    titleKey: 'nav.portal',
    children: [
      { kind: 'leaf', id: 'up-home', titleKey: 'nav.upHome' },
      {
        kind: 'group',
        id: 'up-task',
        titleKey: 'nav.upSectionTask',
        children: [
          { kind: 'leaf', id: 'up-todo', titleKey: 'nav.upTodo', to: '/up-tasks-to-claim' },
          { kind: 'leaf', id: 'up-delegate', titleKey: 'guides.taskDelegate.title', to: '/task-delegate' },
          { kind: 'leaf', id: 'up-done', titleKey: 'nav.upCompleted' },
        ],
      },
      {
        kind: 'group',
        id: 'up-request',
        titleKey: 'nav.upSectionRequest',
        children: [
          { kind: 'leaf', id: 'up-new', titleKey: 'nav.upNewRequests' },
          { kind: 'leaf', id: 'up-mine', titleKey: 'nav.upMyRequests' },
        ],
      },
      {
        kind: 'group',
        id: 'up-audit',
        titleKey: 'nav.upSectionAudit',
        children: [{ kind: 'leaf', id: 'up-all', titleKey: 'nav.upAllRequests' }],
      },
      {
        kind: 'group',
        id: 'up-data',
        titleKey: 'nav.upSectionData',
        children: [
          { kind: 'leaf', id: 'up-rt', titleKey: 'nav.upRelationTables' },
          { kind: 'leaf', id: 'up-views', titleKey: 'nav.upViews' },
        ],
      },
      {
        kind: 'group',
        id: 'up-setup',
        titleKey: 'nav.upSectionSetup',
        children: [
          { kind: 'leaf', id: 'up-deleg', titleKey: 'nav.upDelegations' },
          { kind: 'leaf', id: 'up-profile', titleKey: 'nav.upProfileSetup' },
        ],
      },
    ],
  },
]

export function guideByPath(path: string): Guideline | undefined {
  return GUIDELINES.find((g) => g.path === path)
}

export function navLeavesWithArticles(nodes: NavNode[] = NAV_TREE): NavLeafWithArticle[] {
  const out: NavLeafWithArticle[] = []
  for (const node of nodes) {
    if (node.kind === 'leaf') {
      if (node.to) out.push({ ...node, to: node.to })
    } else {
      out.push(...navLeavesWithArticles(node.children))
    }
  }
  return out
}

/** Group ids that contain a leaf for this article. Prefers the hash target. */
export function navGroupIdsForArticle(
  path: string,
  hash = '',
  nodes: NavNode[] = NAV_TREE,
): string[] {
  const exact: string[] = []
  const fallback: string[] = []
  function walk(list: NavNode[], trail: string[]): void {
    for (const node of list) {
      if (node.kind === 'group') {
        walk(node.children, [...trail, node.id])
        continue
      }
      if (!node.to) continue
      const hashIndex = node.to.indexOf('#')
      const leafPath = hashIndex >= 0 ? node.to.slice(0, hashIndex) : node.to
      const leafHash = hashIndex >= 0 ? node.to.slice(hashIndex) : ''
      if (leafPath !== path) continue
      if (fallback.length === 0) fallback.push(...trail)
      if (hash ? leafHash === hash : !leafHash) {
        exact.push(...trail)
      }
    }
  }
  walk(nodes, [])
  return [...new Set(exact.length ? exact : fallback)]
}

/** Screenshot paths under /help/guides — keep in sync with public/llms.txt ## Figures */
export const HELP_GUIDE_FIGURE_PATHS: readonly string[] = [
  '/help/guides/dw-table-design.png',
  '/help/guides/dw-connections.png',
  '/help/guides/dw-connections-inbound.png',
  '/help/guides/dw-email-templates.png',
  '/help/guides/dw-email-body.png',
  '/help/guides/dw-email-monitors.png',
  '/help/guides/dw-email-extraction-sample.png',
  '/help/guides/dw-email-field-mapping.png',
  '/help/guides/dw-send-task.png',
  '/help/guides/dw-start-event.png',
  '/help/guides/dw-form-events.png',
  '/help/guides/dw-form-events-canvas.png',
  '/help/guides/dw-form-events-form-tab.png',
  '/help/guides/dw-form-events-preview-notify.png',
  '/help/guides/dw-form-events-preview-errors.png',
  '/help/guides/dw-form-events-preview-disabled.png',
] as const
