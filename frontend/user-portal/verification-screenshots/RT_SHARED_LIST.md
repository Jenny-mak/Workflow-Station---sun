# Relation Tables shared-list — verification notes

Design: [shared-list-components.md](../../../docs/design/shared-list-components.md) §6.5.

## Screenshots (gitignored PNGs; cite absolute paths in PR)

Captured under the main workspace (same filenames apply after copy):

- `/Users/peipei/开发/PP1.0/frontend/user-portal/verification-screenshots/2026-08-20_list-relation-tables-shared.png` — business RT shared chrome
- `/Users/peipei/开发/PP1.0/frontend/user-portal/verification-screenshots/2026-08-20_list-relation-user-after-enum.png` — User columns / ENUM kinds
- `/Users/peipei/开发/PP1.0/frontend/user-portal/verification-screenshots/2026-08-20_list-relation-user-status-enum-filter.png` — status Equals + select options

## Wrap-up vs design

| Item | Notes |
|------|--------|
| Columns in page body | Same as Views: `RelationTableDataPage.columns` (no separate `/columns`; §7 “endpoint” satisfied by POST `/data`) |
| `groupBy` | Non-blank `groupBy` on POST body → 400 / `IllegalArgumentException` |
| Deep page | Query paths WARN when elapsed &gt; 1s (`listKey`, `tableId`, `page`, `size`, `total`, `elapsedMs`; no filter values / rows) |
| Switch-table reset | `resetTableState()` clears filters / sort / search / page |

## Commands

```bash
mvn -pl backend/user-portal -am test \
  -Dtest=RelationTableColumnSpecTest,RelationTableQueryRequestTest,PortalRelationTableQueryDataSearchTest,PortalRelationTablePropertyTest \
  -Dsurefire.failIfNoSpecifiedTests=false

cd frontend/user-portal && pnpm exec vitest run src/views/__tests__/relationTablesQueryState.test.ts
```
