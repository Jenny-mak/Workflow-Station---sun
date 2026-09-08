package com.portal.component;

import com.portal.dto.SubTableChange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Sub-table Change History diff: record only user-visible row add/update/delete.
 * A new UUID on the same business payload is identity churn, not a user edit.
 */
final class SubTableChangeHistoryDiff {

    private SubTableChangeHistoryDiff() {
    }

    static List<SubTableChange> compute(
            List<Map<String, Object>> oldRows,
            List<Map<String, Object>> newRows) {
        return compute(oldRows, newRows, null);
    }

    /**
     * @param designerPrimaryKeyFields this table's configured primary key
     *                                 ({@code dw_field_definitions.is_primary_key}). Rows are
     *                                 paired by identity, and a table's key can be named anything
     *                                 — {@code correspondence_id}, {@code case_number}, … — so it
     *                                 has to come from configuration, not from a list of likely
     *                                 column names.
     */
    static List<SubTableChange> compute(
            List<Map<String, Object>> oldRows,
            List<Map<String, Object>> newRows,
            List<String> designerPrimaryKeyFields) {
        List<HeldRow> oldHeld = hold(oldRows, designerPrimaryKeyFields);
        List<HeldRow> newHeld = hold(newRows, designerPrimaryKeyFields);
        boolean[] pairedOld = new boolean[oldHeld.size()];
        boolean[] pairedNew = new boolean[newHeld.size()];
        List<SubTableChange> changes = new ArrayList<>();
        pairByStableId(oldHeld, newHeld, pairedOld, pairedNew, changes);
        pairByFingerprint(oldHeld, newHeld, pairedOld, pairedNew, changes);
        pairSingletonReplacement(oldHeld, newHeld, pairedOld, pairedNew, changes);
        emitUnpaired(oldHeld, newHeld, pairedOld, pairedNew, changes);
        return changes;
    }

    private static void pairByStableId(
            List<HeldRow> oldHeld, List<HeldRow> newHeld,
            boolean[] pairedOld, boolean[] pairedNew,
            List<SubTableChange> changes) {
        for (int n = 0; n < newHeld.size(); n++) {
            String id = newHeld.get(n).id;
            if (id == null) continue;
            for (int o = 0; o < oldHeld.size(); o++) {
                if (pairedOld[o] || !id.equals(oldHeld.get(o).id)) continue;
                pairedOld[o] = true;
                pairedNew[n] = true;
                addUpdateIfUserChanged(changes, id, oldHeld.get(o).row, newHeld.get(n).row);
                break;
            }
        }
    }

    private static void pairByFingerprint(
            List<HeldRow> oldHeld, List<HeldRow> newHeld,
            boolean[] pairedOld, boolean[] pairedNew,
            List<SubTableChange> changes) {
        for (int o = 0; o < oldHeld.size(); o++) {
            if (pairedOld[o]) continue;
            Map<String, Object> fp = fingerprint(oldHeld.get(o).row);
            for (int n = 0; n < newHeld.size(); n++) {
                if (pairedNew[n] || !fp.equals(fingerprint(newHeld.get(n).row))) continue;
                pairedOld[o] = true;
                pairedNew[n] = true;
                String id = firstId(oldHeld.get(o).id, newHeld.get(n).id);
                addUpdateIfUserChanged(changes, id, oldHeld.get(o).row, newHeld.get(n).row);
                break;
            }
        }
    }

    private static void pairSingletonReplacement(
            List<HeldRow> oldHeld, List<HeldRow> newHeld,
            boolean[] pairedOld, boolean[] pairedNew,
            List<SubTableChange> changes) {
        int o = indexOfUnpaired(pairedOld);
        int n = indexOfUnpaired(pairedNew);
        if (o < 0 || n < 0 || unpairedCount(pairedOld) != 1 || unpairedCount(pairedNew) != 1) {
            return;
        }
        pairedOld[o] = true;
        pairedNew[n] = true;
        addUpdateIfUserChanged(changes, firstId(oldHeld.get(o).id, newHeld.get(n).id),
                oldHeld.get(o).row, newHeld.get(n).row);
    }

    private static void emitUnpaired(
            List<HeldRow> oldHeld, List<HeldRow> newHeld,
            boolean[] pairedOld, boolean[] pairedNew,
            List<SubTableChange> changes) {
        for (int n = 0; n < newHeld.size(); n++) {
            if (pairedNew[n]) continue;
            HeldRow row = newHeld.get(n);
            changes.add(change("ROW_ADD", row.id, null, row.row));
        }
        for (int o = 0; o < oldHeld.size(); o++) {
            if (pairedOld[o]) continue;
            HeldRow row = oldHeld.get(o);
            changes.add(change("ROW_DELETE", row.id, row.row, null));
        }
    }

    private static void addUpdateIfUserChanged(
            List<SubTableChange> changes, String rowId,
            Map<String, Object> oldRow, Map<String, Object> newRow) {
        Map<String, Object> newChanged = new LinkedHashMap<>();
        Map<String, Object> oldChanged = new LinkedHashMap<>();
        for (Map.Entry<String, Object> field : newRow.entrySet()) {
            String key = field.getKey();
            if (ChangeHistoryComponent.isSubTableRowMetadataField(key)) continue;
            Object oldVal = oldRow.get(key);
            if (Objects.equals(oldVal, field.getValue())) continue;
            if (isAssigneeAutofill(key, oldVal)) continue;
            newChanged.put(key, field.getValue());
            oldChanged.put(key, oldVal);
        }
        if (newChanged.isEmpty()) return;
        changes.add(change("ROW_UPDATE", rowId, oldChanged, newChanged));
    }

    private static boolean isAssigneeAutofill(String fieldName, Object oldVal) {
        if (!ChangeHistoryComponent.isAssigneeValueField(fieldName)) return false;
        if (oldVal == null) return true;
        return oldVal instanceof String s && s.isBlank();
    }

    private static Map<String, Object> fingerprint(Map<String, Object> row) {
        Map<String, Object> fp = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(row.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            if (ChangeHistoryComponent.isSubTableRowMetadataField(key)) continue;
            if (ChangeHistoryComponent.isAssigneeValueField(key)) continue;
            fp.put(key, row.get(key));
        }
        return fp;
    }

    private static List<HeldRow> hold(List<Map<String, Object>> rows,
            List<String> designerPrimaryKeyFields) {
        List<HeldRow> held = new ArrayList<>();
        if (rows == null) return held;
        for (Map<String, Object> row : rows) {
            if (row == null) continue;
            held.add(new HeldRow(
                    ChangeHistoryComponent.resolveRowIdentifier(row, designerPrimaryKeyFields), row));
        }
        return held;
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

    private static String firstId(String a, String b) {
        return a != null ? a : b;
    }

    private static int indexOfUnpaired(boolean[] paired) {
        for (int i = 0; i < paired.length; i++) {
            if (!paired[i]) return i;
        }
        return -1;
    }

    private static int unpairedCount(boolean[] paired) {
        int n = 0;
        for (boolean p : paired) {
            if (!p) n++;
        }
        return n;
    }

    private record HeldRow(String id, Map<String, Object> row) {
    }
}
