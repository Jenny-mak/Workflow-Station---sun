import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const here = dirname(fileURLToPath(import.meta.url))
const frontendRoot = join(here, '../../../../../')

describe('admin audit list viewport', () => {
  it('keeps filter and batch bars from shrinking the grid', () => {
    const scss = readFileSync(join(here, '../../../styles/listDataGrid.scss'), 'utf8')
    expect(scss).toContain('.page-container:has(.list-data-grid-scroll) > .filter-card')
    expect(scss).toContain('.page-container:has(.list-data-grid-scroll) > .batch-bar')
    expect(scss).not.toContain('.audit-list-page .table-card')
    expect(scss).not.toContain('.audit-list-page .list-data-grid-inner')
  })

  it('hosts both Audit lists on table-card with in-table empty state', () => {
    const adminAudit = readFileSync(join(here, '../index.vue'), 'utf8')
    const portalAudit = readFileSync(join(here, '../user-portal/index.vue'), 'utf8')
    expect(adminAudit).toContain('class="page-container audit-list-page"')
    expect(portalAudit).toContain('class="page-container audit-list-page"')
    expect(adminAudit).toContain('class="table-card"')
    expect(portalAudit).toContain('class="table-card"')
    expect(adminAudit).not.toContain('audit-grid-shell')
    expect(portalAudit).not.toContain('up-audit-grid-shell')
    expect(adminAudit).toContain('<template #empty>')
    expect(portalAudit).toContain('<template #empty>')
    expect(adminAudit).not.toContain('class="empty-state"')
    expect(portalAudit).not.toContain('class="empty-state"')
  })

  it('fills leftover viewport so el-table owns the scrollbars', () => {
    const adminAudit = readFileSync(join(here, '../index.vue'), 'utf8')
    const portalAudit = readFileSync(join(here, '../user-portal/index.vue'), 'utf8')
    expect(adminAudit).toContain("gridTableHeight || '100%'")
    expect(portalAudit).toContain("gridTableHeight || '100%'")
    expect(adminAudit).not.toContain('useListTableFitHeight')
    expect(portalAudit).not.toContain('useListTableFitHeight')
    expect(adminAudit).not.toContain(':height="tableHeight"')
    expect(portalAudit).not.toContain(':height="tableHeight"')
  })

  it('waits for pinned layout instead of a fixed sleep', () => {
    const script = readFileSync(
      join(frontendRoot, 'scripts/verify-admin-audit-list-viewport.mjs'),
      'utf8',
    )
    expect(script).toContain('waitUntilLayoutPinned')
    expect(script).toContain('pagination at viewport bottom')
    expect(script).toContain('constrained table body')
    expect(script).not.toContain('waitForTimeout')
  })
})
