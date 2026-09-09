package com.portal.component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges nested-lifted rows with later top-level slices by persisted identity
 * (designer PK first, then the platform key). Nested lift runs first, so a
 * filled nested field is kept when a later top-level shadow carries a
 * different non-blank value. Blank never overwrites filled. Rows are never
 * collapsed because they share a business value.
 */
final class ChangeHistorySubTableSliceMerger {

    private ChangeHistorySubTableSliceMerger() {
    }

    static void mergeSliceRows(
            Map<String, Map<String, Object>> rowsByIdentity,
            List<Map<String, Object>> filteredRows,
            List<String> pkFields) {
        if (filteredRows == null) {
            return;
        }
        List<String> keys = pkFields == null ? List.of() : pkFields;
        for (Map<String, Object> row : filteredRows) {
            if (row == null) {
                continue;
            }
            Map<String, Object> candidate = row instanceof LinkedHashMap
                    ? row
                    : new LinkedHashMap<>(row);
            ChangeHistoryAuditRowKey.stamp(candidate, keys);
            String matchKey = findMatchingKey(rowsByIdentity, candidate, keys);
            if (matchKey != null) {
                putMerged(rowsByIdentity, matchKey, candidate, keys);
                continue;
            }
            String identity = ChangeHistoryAuditRowKey.resolve(candidate);
            if (identity == null) {
                rowsByIdentity.put(unidentifiedStorageKey(), candidate);
                continue;
            }
            rowsByIdentity.put(identity, candidate);
        }
    }

    static void mergeFilteredTableRows(
            Map<String, Map<String, Map<String, Object>>> rowsByTableAndIdentity,
            Map<String, Object> filteredTables,
            ChangeHistoryBindingAliases aliases) {
        if (filteredTables == null || filteredTables.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : filteredTables.entrySet()) {
            if (!(entry.getValue() instanceof List<?> rows)) {
                continue;
            }
            Map<String, Map<String, Object>> rowsByIdentity = rowsByTableAndIdentity
                    .computeIfAbsent(entry.getKey(), ignored -> new LinkedHashMap<>());
            mergeSliceRows(rowsByIdentity, castRows(rows), primaryKeyFields(aliases, entry.getKey()));
        }
    }

    static Map<String, Object> unionPreferringValues(
            Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (left != null) {
            merged.putAll(left);
        }
        if (right == null) {
            return merged;
        }
        for (Map.Entry<String, Object> field : right.entrySet()) {
            Object incoming = field.getValue();
            if (isBlankAuditValue(incoming)) {
                continue;
            }
            Object existing = merged.get(field.getKey());
            if (isBlankAuditValue(existing)) {
                merged.put(field.getKey(), incoming);
            }
        }
        return merged;
    }

    static boolean isBlankAuditValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        return false;
    }

    private static void putMerged(
            Map<String, Map<String, Object>> rowsByIdentity,
            String matchKey,
            Map<String, Object> candidate,
            List<String> keys) {
        Map<String, Object> merged = unionPreferringValues(rowsByIdentity.get(matchKey), candidate);
        ChangeHistoryAuditRowKey.stamp(merged, keys);
        String newKey = ChangeHistoryAuditRowKey.resolve(merged);
        if (newKey != null && !newKey.equals(matchKey)) {
            rowsByIdentity.remove(matchKey);
            rowsByIdentity.put(newKey, merged);
        } else {
            rowsByIdentity.put(matchKey, merged);
        }
    }

    private static String findMatchingKey(
            Map<String, Map<String, Object>> rowsByIdentity,
            Map<String, Object> row,
            List<String> pkFields) {
        for (Map.Entry<String, Map<String, Object>> entry : rowsByIdentity.entrySet()) {
            if (ChangeHistoryAuditRowKey.sameLogicalRow(entry.getValue(), row, pkFields)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Map storage only. Never written onto the row and never used as
     * {@code rowIdentifier} — unidentified rows stay unpaired.
     */
    private static String unidentifiedStorageKey() {
        return "\0unidentified:" + java.util.UUID.randomUUID();
    }

    private static List<String> primaryKeyFields(ChangeHistoryBindingAliases aliases, String historyName) {
        if (aliases == null || historyName == null) {
            return List.of();
        }
        for (Map.Entry<String, String> entry : aliases.bindingToHistoryName().entrySet()) {
            if (historyName.equalsIgnoreCase(entry.getValue())) {
                return aliases.primaryKeyFields(entry.getKey());
            }
        }
        String bindingId = aliases.aliasToBinding().get(ChangeHistoryFilterMaps.normalizeAlias(historyName));
        return aliases.primaryKeyFields(bindingId);
    }

    private static List<Map<String, Object>> castRows(List<?> rows) {
        List<Map<String, Object>> typed = new ArrayList<>();
        for (Object rowObj : rows) {
            if (rowObj instanceof Map<?, ?> rawRow) {
                typed.add(ChangeHistoryFilterMaps.castMap(rawRow));
            }
        }
        return typed;
    }
}
