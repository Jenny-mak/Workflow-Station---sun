package com.portal.component;

import com.platform.common.jdbc.SubTableRowIdentity;
import com.portal.dto.SubTableChange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Sub-table Change History diff: record only user-visible row add/update/delete.
 * Rows match by persisted identity (designer PK first, then
 * {@link SubTableRowIdentity#CANONICAL_FIELD}), never by business field values.
 * Incomplete copies of the same identity are unioned before comparison.
 */
final class SubTableChangeHistoryDiff {

    private SubTableChangeHistoryDiff() {
    }

    static List<SubTableChange> compute(
            List<Map<String, Object>> oldRows,
            List<Map<String, Object>> newRows) {
        return compute(oldRows, newRows, List.of());
    }

    /**
     * @param designerPrimaryKeyFields this table's configured primary key
     *                                 ({@code dw_field_definitions.is_primary_key}).
     *                                 The column name is whatever the designer chose;
     *                                 it is not inferred from likely names.
     */
    static List<SubTableChange> compute(
            List<Map<String, Object>> oldRows,
            List<Map<String, Object>> newRows,
            List<String> designerPrimaryKeyFields) {
        List<String> keys = designerPrimaryKeyFields == null ? List.of() : designerPrimaryKeyFields;
        List<HeldRow> oldHeld = hold(collapseByIdentity(oldRows, keys), keys);
        List<HeldRow> newHeld = hold(collapseByIdentity(newRows, keys), keys);
        boolean[] pairedOld = new boolean[oldHeld.size()];
        boolean[] pairedNew = new boolean[newHeld.size()];
        List<SubTableChange> changes = new ArrayList<>();
        pairByIdentity(oldHeld, newHeld, pairedOld, pairedNew, changes, keys);
        emitUnpaired(oldHeld, newHeld, pairedOld, pairedNew, changes);
        return changes;
    }

    static List<Map<String, Object>> collapseByIdentity(
            List<Map<String, Object>> rows, List<String> pkFields) {
        List<Map<String, Object>> kept = new ArrayList<>();
        if (rows == null) {
            return kept;
        }
        List<String> keys = pkFields == null ? List.of() : pkFields;
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            Map<String, Object> candidate = new LinkedHashMap<>(row);
            ChangeHistoryAuditRowKey.stamp(candidate, keys);
            boolean merged = false;
            for (int i = 0; i < kept.size(); i++) {
                if (ChangeHistoryAuditRowKey.sameLogicalRow(kept.get(i), candidate, keys)) {
                    Map<String, Object> union = ChangeHistorySubTableSliceMerger
                            .unionPreferringValues(kept.get(i), candidate);
                    ChangeHistoryAuditRowKey.stamp(union, keys);
                    kept.set(i, union);
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    private static void pairByIdentity(
            List<HeldRow> oldHeld, List<HeldRow> newHeld,
            boolean[] pairedOld, boolean[] pairedNew,
            List<SubTableChange> changes, List<String> pkFields) {
        for (int n = 0; n < newHeld.size(); n++) {
            for (int o = 0; o < oldHeld.size(); o++) {
                if (pairedOld[o] || pairedNew[n]) {
                    continue;
                }
                if (!ChangeHistoryAuditRowKey.sameLogicalRow(oldHeld.get(o).row, newHeld.get(n).row, pkFields)) {
                    continue;
                }
                pairedOld[o] = true;
                pairedNew[n] = true;
                String id = newHeld.get(n).id != null ? newHeld.get(n).id : oldHeld.get(o).id;
                addUpdateIfUserChanged(changes, id, oldHeld.get(o).row, newHeld.get(n).row, pkFields);
                break;
            }
        }
    }

    private static void emitUnpaired(
            List<HeldRow> oldHeld, List<HeldRow> newHeld,
            boolean[] pairedOld, boolean[] pairedNew,
            List<SubTableChange> changes) {
        for (int n = 0; n < newHeld.size(); n++) {
            if (!pairedNew[n]) {
                HeldRow row = newHeld.get(n);
                changes.add(change("ROW_ADD", row.id, null, withoutAuditKey(row.row)));
            }
        }
        for (int o = 0; o < oldHeld.size(); o++) {
            if (!pairedOld[o]) {
                HeldRow row = oldHeld.get(o);
                changes.add(change("ROW_DELETE", row.id, withoutAuditKey(row.row), null));
            }
        }
    }

    private static void addUpdateIfUserChanged(
            List<SubTableChange> changes, String rowId,
            Map<String, Object> oldRow, Map<String, Object> newRow, List<String> pkFields) {
        Map<String, Object> newChanged = new LinkedHashMap<>();
        Map<String, Object> oldChanged = new LinkedHashMap<>();
        for (Map.Entry<String, Object> field : newRow.entrySet()) {
            String key = field.getKey();
            if (ChangeHistoryComponent.isSubTableRowMetadataField(key) || isPrimaryKeyField(key, pkFields)) {
                continue;
            }
            Object oldVal = oldRow.get(key);
            if (Objects.equals(oldVal, field.getValue())) {
                continue;
            }
            newChanged.put(key, field.getValue());
            oldChanged.put(key, oldVal);
        }
        if (!newChanged.isEmpty()) {
            changes.add(change("ROW_UPDATE", rowId, oldChanged, newChanged));
        }
    }

    private static boolean isPrimaryKeyField(String fieldName, List<String> pkFields) {
        if (fieldName == null || pkFields == null) {
            return false;
        }
        for (String pk : pkFields) {
            if (fieldName.equalsIgnoreCase(pk)) {
                return true;
            }
        }
        return false;
    }

    private static List<HeldRow> hold(List<Map<String, Object>> rows, List<String> pkFields) {
        List<HeldRow> held = new ArrayList<>();
        if (rows == null) {
            return held;
        }
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            ChangeHistoryAuditRowKey.stamp(row, pkFields);
            held.add(new HeldRow(ChangeHistoryAuditRowKey.resolve(row), row));
        }
        return held;
    }

    private static Map<String, Object> withoutAuditKey(Map<String, Object> row) {
        if (row == null || !row.containsKey(ChangeHistoryAuditRowKey.FIELD)) {
            return row;
        }
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.remove(ChangeHistoryAuditRowKey.FIELD);
        return copy;
    }

    private static SubTableChange change(
            String type, String rowId, Map<String, Object> oldValues, Map<String, Object> newValues) {
        return SubTableChange.builder()
                .changeType(type)
                .rowIdentifier(rowId)
                .oldValues(oldValues)
                .newValues(newValues)
                .build();
    }

    private record HeldRow(String id, Map<String, Object> row) {
    }
}
