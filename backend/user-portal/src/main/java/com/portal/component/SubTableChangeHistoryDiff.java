package com.portal.component;

import com.portal.dto.SubTableChange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Sub-table Change History diff: record only user-visible row add/update/delete.
 * Incomplete subset copies in the same snapshot are dropped. Two complete rows
 * with the same payload stay two rows ({@link com.platform.common.jdbc.SubTableRowIdentity}).
 * Nested vs top-level extra UUIDs are resolved by the submission filter, not here.
 */
final class SubTableChangeHistoryDiff {

    private SubTableChangeHistoryDiff() {
    }

    static List<SubTableChange> compute(
            List<Map<String, Object>> oldRows,
            List<Map<String, Object>> newRows) {
        List<HeldRow> oldHeld = hold(collapseShadowCopies(oldRows));
        List<HeldRow> newHeld = hold(collapseShadowCopies(newRows));
        boolean[] pairedOld = new boolean[oldHeld.size()];
        boolean[] pairedNew = new boolean[newHeld.size()];
        List<SubTableChange> changes = new ArrayList<>();
        pairByStableId(oldHeld, newHeld, pairedOld, pairedNew, changes);
        pairByFingerprint(oldHeld, newHeld, pairedOld, pairedNew, changes);
        pairRemainingRowsAsUpdates(oldHeld, newHeld, pairedOld, pairedNew, changes);
        emitUnpaired(oldHeld, newHeld, pairedOld, pairedNew, changes);
        return changes;
    }

    /**
     * Drop incomplete subset copies of a richer row in the same snapshot.
     */
    static List<Map<String, Object>> collapseShadowCopies(List<Map<String, Object>> rows) {
        List<Map<String, Object>> kept = new ArrayList<>();
        if (rows == null) {
            return kept;
        }
        for (Map<String, Object> row : rows) {
            if (row != null) {
                absorbShadowCopy(kept, row);
            }
        }
        return kept;
    }

    static boolean isShadowCopy(Map<String, Object> shadow, Map<String, Object> richer) {
        if (shadow == null || richer == null) {
            return false;
        }
        int shadowCount = 0;
        for (Map.Entry<String, Object> field : shadow.entrySet()) {
            if (!isBusinessAuditField(field.getKey()) || isBlankAuditValue(field.getValue())) {
                continue;
            }
            shadowCount++;
            if (!lookupValuesEqual(field.getValue(), richer.get(field.getKey()))) {
                return false;
            }
        }
        return shadowCount < nonEmptyBusinessFieldCount(richer);
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

    /**
     * Nested link-child copies often get a new UUID while the user edited or added
     * rows. Pair leftover old rows to leftover new rows as updates so a row-count
     * increase is ADD+UPDATE, never a phantom DELETE of the previous identity.
     */
    private static void pairRemainingRowsAsUpdates(
            List<HeldRow> oldHeld, List<HeldRow> newHeld,
            boolean[] pairedOld, boolean[] pairedNew,
            List<SubTableChange> changes) {
        while (indexOfUnpaired(pairedOld) >= 0 && indexOfUnpaired(pairedNew) >= 0) {
            int bestOld = -1;
            int bestNew = -1;
            int bestScore = -1;
            for (int o = 0; o < oldHeld.size(); o++) {
                if (pairedOld[o]) continue;
                for (int n = 0; n < newHeld.size(); n++) {
                    if (pairedNew[n]) continue;
                    int score = overlapScore(oldHeld.get(o).row, newHeld.get(n).row);
                    if (score > bestScore) {
                        bestScore = score;
                        bestOld = o;
                        bestNew = n;
                    }
                }
            }
            boolean singleton = unpairedCount(pairedOld) == 1 && unpairedCount(pairedNew) == 1;
            if (bestOld < 0 || (bestScore < 1 && !singleton)) {
                return;
            }
            pairedOld[bestOld] = true;
            pairedNew[bestNew] = true;
            addUpdateIfUserChanged(changes, firstId(oldHeld.get(bestOld).id, newHeld.get(bestNew).id),
                    oldHeld.get(bestOld).row, newHeld.get(bestNew).row);
        }
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
            if (Objects.equals(oldVal, field.getValue()) || lookupValuesEqual(oldVal, field.getValue())) continue;
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

    private static void absorbShadowCopy(List<Map<String, Object>> kept, Map<String, Object> candidate) {
        for (int i = 0; i < kept.size(); i++) {
            Map<String, Object> existing = kept.get(i);
            if (isShadowCopy(candidate, existing)) {
                return;
            }
            if (isShadowCopy(existing, candidate)) {
                kept.set(i, candidate);
                return;
            }
        }
        kept.add(candidate);
    }

    private static Map<String, Object> fingerprint(Map<String, Object> row) {
        Map<String, Object> fp = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(row.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            if (!isBusinessAuditField(key) || isBlankAuditValue(row.get(key))) continue;
            fp.put(key, fingerprintValue(row.get(key)));
        }
        return fp;
    }

    private static boolean isBusinessAuditField(String key) {
        return !ChangeHistoryComponent.isSubTableRowMetadataField(key)
                && !ChangeHistoryComponent.isAssigneeValueField(key);
    }

    private static int nonEmptyBusinessFieldCount(Map<String, Object> row) {
        int count = 0;
        for (Map.Entry<String, Object> field : row.entrySet()) {
            if (isBusinessAuditField(field.getKey()) && !isBlankAuditValue(field.getValue())) {
                count++;
            }
        }
        return count;
    }

    private static boolean isBlankAuditValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        return false;
    }

    private static int overlapScore(Map<String, Object> oldRow, Map<String, Object> newRow) {
        int score = 0;
        for (Map.Entry<String, Object> field : newRow.entrySet()) {
            String key = field.getKey();
            if (!isBusinessAuditField(key)) continue;
            if (!oldRow.containsKey(key)) continue;
            if (lookupValuesEqual(oldRow.get(key), field.getValue())) {
                score++;
            }
        }
        return score;
    }

    private static boolean lookupValuesEqual(Object left, Object right) {
        if (Objects.equals(left, right)) {
            return true;
        }
        Object leftId = fingerprintValue(left);
        Object rightId = fingerprintValue(right);
        return leftId != null && Objects.equals(leftId, rightId);
    }

    private static Object fingerprintValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object id = map.get("id");
            return id != null ? id : value;
        }
        return value;
    }

    private static List<HeldRow> hold(List<Map<String, Object>> rows) {
        List<HeldRow> held = new ArrayList<>();
        if (rows == null) return held;
        for (Map<String, Object> row : rows) {
            if (row == null) continue;
            held.add(new HeldRow(ChangeHistoryComponent.resolveRowIdentifier(row), row));
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
