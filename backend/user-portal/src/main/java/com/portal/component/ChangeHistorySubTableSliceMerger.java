package com.portal.component;

import com.platform.common.jdbc.SubTableRowIdentity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges nested-lifted rows with later top-level slices. Later-slice identities
 * that were not already lifted are skipped so a new UUID on the same table
 * cannot become a second audit row.
 */
final class ChangeHistorySubTableSliceMerger {

    private ChangeHistorySubTableSliceMerger() {
    }

    static void mergeSliceRows(
            Map<String, Map<String, Object>> rowsByIdentity,
            List<Map<String, Object>> filteredRows) {
        boolean laterSlice = !rowsByIdentity.isEmpty();
        for (Map<String, Object> row : filteredRows) {
            String identity = SubTableRowIdentity.identityOf(row);
            if (identity == null) {
                identity = "__index_" + rowsByIdentity.size();
            }
            Map<String, Object> existing = rowsByIdentity.get(identity);
            if (existing == null) {
                if (!laterSlice) {
                    rowsByIdentity.put(identity, row);
                }
                continue;
            }
            if (SubTableChangeHistoryDiff.isShadowCopy(existing, row)) {
                rowsByIdentity.put(identity, row);
            }
        }
    }

    static void mergeFilteredTableRows(
            Map<String, Map<String, Map<String, Object>>> rowsByTableAndIdentity,
            Map<String, Object> filteredTables,
            boolean overwrite) {
        if (filteredTables == null || filteredTables.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : filteredTables.entrySet()) {
            if (!(entry.getValue() instanceof List<?> rows)) {
                continue;
            }
            Map<String, Map<String, Object>> rowsByIdentity = rowsByTableAndIdentity
                    .computeIfAbsent(entry.getKey(), ignored -> new LinkedHashMap<>());
            mergeTableRows(rowsByIdentity, rows, overwrite);
        }
    }

    private static void mergeTableRows(
            Map<String, Map<String, Object>> rowsByIdentity,
            List<?> rows,
            boolean overwrite) {
        for (Object rowObj : rows) {
            if (!(rowObj instanceof Map<?, ?> rawRow)) {
                continue;
            }
            Map<String, Object> row = ChangeHistoryFilterMaps.castMap(rawRow);
            String identity = SubTableRowIdentity.identityOf(row);
            if (identity == null) {
                identity = "__index_" + rowsByIdentity.size();
            }
            if (overwrite || !rowsByIdentity.containsKey(identity)) {
                rowsByIdentity.put(identity, row);
            }
        }
    }
}
