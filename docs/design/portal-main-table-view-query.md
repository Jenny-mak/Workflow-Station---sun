# Portal Main Table View — query semantics (DB-authoritative)

## Goal

Filter, sort, group counts, pagination, and `total` for Views are executed in PostgreSQL against
`up_process_instance` (and JSONB `variables`). There is **no** in-memory 5000-row cap.

## Column filter / sort semantics

| Column type | Stored value used for WHERE / ORDER BY | Display in grid |
|---|---|---|
| Normal field | `variables->>'fieldName'` | Same / formatted |
| System: `process_status` | `status` | status |
| System: `start_time` | `start_time` | timestamp |
| System: `initiator` | `COALESCE(start_user_name, start_user_id)` | name or id |
| System: `current_step` | `current_node` | node id/name |
| `lookup_display` / `fk_display` | **Source field** (`lookupSourceField`) raw PK / scalar in JSON | Frontend hydrate to label |

Filtering by hydrated lookup/FK **display labels** is **not** supported until a separate display-index
exists. Header filters on those columns match the stored source value (same as pre-hydrate client filter).

## Involvement (`restrictToInvolvedUsers`)

SQL predicate (non-admin):

1. `start_user_id = currentUser`, or
2. `EXISTS` historic task assignee on `ACT_HI_TASKINST`, or
3. `variables->'__subTables__'::text ILIKE '%userId%'` (pragmatic MI participant hint)

(3) is not a full key-walk of participant fields; false positives possible for short user ids.

## SUB views

Rows are `jsonb_array_elements` of each form-table binding key under `__subTables__`,
`DISTINCT ON (process_id, row id)` to match prior Java dedupe.

## To Do / Completed lists

Reuse the same **API contract** idea (`filters` / `sortField` / `groupBy` / server `total`) in a
**separate PR** against task tables — do not mix with this JSONB Views engine.

## Indexes

See `deploy/init-scripts/00-schema/65-up-process-instance-mtv-query-indexes.sql`:

- `(function_unit_code, start_time DESC)`
- GIN `(variables jsonb_path_ops)`
