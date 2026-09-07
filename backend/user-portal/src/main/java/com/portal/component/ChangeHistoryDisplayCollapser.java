package com.portal.component;

import com.portal.entity.ChangeHistory;
import com.portal.enums.ChangeType;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hides nested-copy noise already written to {@code up_change_history}: a fill-in
 * UPDATE plus an identical ADD of the same business values. Does not rewrite
 * stored rows. A genuine clear-all UPDATE is left visible.
 */
final class ChangeHistoryDisplayCollapser {

    private ChangeHistoryDisplayCollapser() {
    }

    static List<ChangeHistory> collapse(List<ChangeHistory> entities) {
        if (entities == null || entities.isEmpty()) {
            return entities;
        }
        Map<String, List<ChangeHistory>> groups = new LinkedHashMap<>();
        for (ChangeHistory entity : entities) {
            groups.computeIfAbsent(groupKey(entity), ignored -> new ArrayList<>()).add(entity);
        }
        IdentityHashMap<ChangeHistory, Boolean> drop = new IdentityHashMap<>();
        for (List<ChangeHistory> group : groups.values()) {
            dropShadowAdds(group, drop);
        }
        if (drop.isEmpty()) {
            return entities;
        }
        List<ChangeHistory> kept = new ArrayList<>();
        for (ChangeHistory entity : entities) {
            if (!drop.containsKey(entity)) {
                kept.add(entity);
            }
        }
        return kept;
    }

    private static void dropShadowAdds(
            List<ChangeHistory> group,
            IdentityHashMap<ChangeHistory, Boolean> drop) {
        List<ChangeHistory> updates = byType(group, ChangeType.SUB_TABLE_ROW_UPDATE);
        List<ChangeHistory> adds = byType(group, ChangeType.SUB_TABLE_ROW_ADD);
        if (updates.isEmpty() || adds.isEmpty()) {
            return;
        }
        Map<String, String> updateNew = valuesByField(updates, true);
        Map<String, String> addNew = valuesByField(adds, true);
        if (!isShadowAdd(updateNew, addNew) || !allBlank(valuesByField(updates, false))) {
            return;
        }
        for (ChangeHistory add : adds) {
            drop.put(add, Boolean.TRUE);
        }
    }

    private static boolean isShadowAdd(Map<String, String> updateNew, Map<String, String> addNew) {
        boolean overlap = false;
        for (Map.Entry<String, String> field : addNew.entrySet()) {
            if (!updateNew.containsKey(field.getKey())) {
                continue;
            }
            overlap = true;
            if (!Objects.equals(field.getValue(), updateNew.get(field.getKey()))) {
                return false;
            }
        }
        return overlap;
    }

    private static List<ChangeHistory> byType(List<ChangeHistory> group, ChangeType type) {
        List<ChangeHistory> matched = new ArrayList<>();
        for (ChangeHistory entity : group) {
            if (type.equals(entity.getChangeType())) {
                matched.add(entity);
            }
        }
        return matched;
    }

    private static Map<String, String> valuesByField(List<ChangeHistory> rows, boolean newValue) {
        Map<String, String> values = new LinkedHashMap<>();
        for (ChangeHistory row : rows) {
            String field = row.getFieldName();
            if (field == null || field.isBlank()) {
                continue;
            }
            values.put(field, normalize(newValue ? row.getNewValue() : row.getOldValue()));
        }
        return values;
    }

    private static boolean allBlank(Map<String, String> values) {
        if (values.isEmpty()) {
            return false;
        }
        for (String value : values.values()) {
            if (!value.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static String groupKey(ChangeHistory entity) {
        String table = entity.getSubTableName();
        if (table == null || table.isBlank()) {
            return "field|" + System.identityHashCode(entity);
        }
        return String.valueOf(entity.getStageId()) + '|' + table + '|' + entity.getTimestamp();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
