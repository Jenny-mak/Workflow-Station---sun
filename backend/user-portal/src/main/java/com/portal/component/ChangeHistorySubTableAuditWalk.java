package com.portal.component;

import com.platform.common.jdbc.SubTableRowIdentity;
import com.platform.common.jdbc.SubTableRowKeySupport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks submitted {@code __subTables__}, including nested link-child slices on
 * parent rows, and keeps one audit row per identity.
 */
final class ChangeHistorySubTableAuditWalk {

    /** Link-child tables nest one level under the parent row. */
    private static final int MAX_NESTED_SUB_TABLE_LIFT_DEPTH = 2;

    private ChangeHistorySubTableAuditWalk() {
    }

    static Map<String, Object> filter(
            Map<?, ?> submittedTables,
            Map<?, ?> enrichedTables,
            Map<String, Set<String>> editableByBinding,
            ChangeHistoryBindingAliases aliases,
            Map<String, Map<String, String>> lookupDisplayByBinding) {
        return filter(submittedTables, enrichedTables, editableByBinding, aliases,
                lookupDisplayByBinding, MAX_NESTED_SUB_TABLE_LIFT_DEPTH);
    }

    static Map<String, Object> filter(
            Map<?, ?> submittedTables,
            Map<?, ?> enrichedTables,
            Map<String, Set<String>> editableByBinding,
            ChangeHistoryBindingAliases aliases,
            Map<String, Map<String, String>> lookupDisplayByBinding,
            int remainingLiftDepth) {
        if (lookupDisplayByBinding == null) {
            lookupDisplayByBinding = Map.of();
        }
        Map<String, Integer> bestPriorityByBinding = new HashMap<>();
        Map<String, Map<String, Map<String, Object>>> rowsByTableAndIdentity = new LinkedHashMap<>();
        List<Map.Entry<?, ?>> entries = sortedEntries(submittedTables, aliases);
        liftNestedSlices(entries, enrichedTables, editableByBinding, aliases,
                lookupDisplayByBinding, rowsByTableAndIdentity, remainingLiftDepth);
        appendEditableSlices(entries, enrichedTables, editableByBinding, aliases,
                lookupDisplayByBinding, bestPriorityByBinding, rowsByTableAndIdentity);
        Map<String, Object> result = new LinkedHashMap<>();
        rowsByTableAndIdentity.forEach((tableName, rows) ->
                result.put(tableName, stripAuditKeys(rows.values())));
        return result;
    }

    private static List<Map.Entry<?, ?>> sortedEntries(
            Map<?, ?> submittedTables,
            ChangeHistoryBindingAliases aliases) {
        List<Map.Entry<?, ?>> entries = new ArrayList<>(submittedTables.entrySet());
        entries.sort((left, right) -> {
            int priority = Integer.compare(
                    aliasPriority(left.getKey(), aliases), aliasPriority(right.getKey(), aliases));
            return priority != 0 ? priority
                    : String.valueOf(left.getKey()).compareToIgnoreCase(String.valueOf(right.getKey()));
        });
        return entries;
    }

    private static void liftNestedSlices(
            List<Map.Entry<?, ?>> entries,
            Map<?, ?> enrichedTables,
            Map<String, Set<String>> editableByBinding,
            ChangeHistoryBindingAliases aliases,
            Map<String, Map<String, String>> lookupDisplayByBinding,
            Map<String, Map<String, Map<String, Object>>> rowsByTableAndIdentity,
            int remainingLiftDepth) {
        for (Map.Entry<?, ?> entry : entries) {
            String rawKey = ChangeHistoryFilterMaps.stringValue(entry.getKey());
            if (rawKey == null || !(entry.getValue() instanceof List<?> submittedRows)) {
                continue;
            }
            String bindingId = aliases.aliasToBinding().getOrDefault(
                    ChangeHistoryFilterMaps.normalizeAlias(rawKey), rawKey);
            List<?> enrichedRows = findRows(enrichedTables, rawKey, bindingId, aliases);
            liftNestedSubmittedSubTables(submittedRows, enrichedRows, editableByBinding, aliases,
                    lookupDisplayByBinding, rowsByTableAndIdentity, remainingLiftDepth,
                    aliases.primaryKeyFieldsByBinding().get(bindingId));
        }
    }

    private static void appendEditableSlices(
            List<Map.Entry<?, ?>> entries,
            Map<?, ?> enrichedTables,
            Map<String, Set<String>> editableByBinding,
            ChangeHistoryBindingAliases aliases,
            Map<String, Map<String, String>> lookupDisplayByBinding,
            Map<String, Integer> bestPriorityByBinding,
            Map<String, Map<String, Map<String, Object>>> rowsByTableAndIdentity) {
        for (Map.Entry<?, ?> entry : entries) {
            String rawKey = ChangeHistoryFilterMaps.stringValue(entry.getKey());
            if (rawKey == null || !(entry.getValue() instanceof List<?> submittedRows)) {
                continue;
            }
            String bindingId = aliases.aliasToBinding().getOrDefault(
                    ChangeHistoryFilterMaps.normalizeAlias(rawKey), rawKey);
            Set<String> editableFields = editableByBinding.get(bindingId);
            if (editableFields == null || editableFields.isEmpty()) {
                continue;
            }
            int priority = aliasPriority(rawKey, aliases);
            Integer bestPriority = bestPriorityByBinding.putIfAbsent(bindingId, priority);
            if (bestPriority != null && priority >= bestPriority) {
                continue;
            }
            List<?> enrichedRows = findRows(enrichedTables, rawKey, bindingId, aliases);
            appendFilteredSlice(rawKey, bindingId, submittedRows, enrichedRows, editableFields,
                    lookupDisplayByBinding.getOrDefault(bindingId, Map.of()), aliases,
                    priority, bestPriorityByBinding, rowsByTableAndIdentity);
        }
    }

    private static void appendFilteredSlice(
            String rawKey,
            String bindingId,
            List<?> submittedRows,
            List<?> enrichedRows,
            Set<String> editableFields,
            Map<String, String> lookupDisplayByField,
            ChangeHistoryBindingAliases aliases,
            int priority,
            Map<String, Integer> bestPriorityByBinding,
            Map<String, Map<String, Map<String, Object>>> rowsByTableAndIdentity) {
        if (lookupDisplayByField == null) {
            lookupDisplayByField = Map.of();
        }
        // 这张表配置的主键列——行身份按配置解析，而不是假设某个名字的列就是身份。
        List<String> designerPk = aliases.primaryKeyFieldsByBinding().get(bindingId);
        List<Map<String, Object>> filteredRows = filterSubmittedRows(
                submittedRows, enrichedRows, editableFields, lookupDisplayByField, designerPk);
        String outputKey = aliases.bindingToHistoryName().get(bindingId);
        if (outputKey == null) {
            outputKey = ChangeHistoryComponent.normalizeSubTableNameForHistory(rawKey);
        }
        if (outputKey == null) {
            return;
        }
        Map<String, Map<String, Object>> rowsByIdentity = rowsByTableAndIdentity
                .computeIfAbsent(outputKey, ignored -> new LinkedHashMap<>());
        if (submittedRows.isEmpty() && rowsByIdentity.isEmpty()
                && priority <= bestPriorityByBinding.get(bindingId)) {
            return;
        }
        if (submittedRows.isEmpty()) {
            return;
        }
        ChangeHistorySubTableSliceMerger.mergeSliceRows(rowsByIdentity, filteredRows, designerPk);
    }

    private static List<Map<String, Object>> filterSubmittedRows(
            List<?> submittedRows,
            List<?> enrichedRows,
            Set<String> editableFields,
            Map<String, String> lookupDisplayByField,
            List<String> designerPrimaryKeyFields) {
        List<Map<String, Object>> filteredRows = new ArrayList<>();
        for (int i = 0; i < submittedRows.size(); i++) {
            if (!(submittedRows.get(i) instanceof Map<?, ?> submittedRow)) {
                continue;
            }
            Map<String, Object> filteredRow = copyIdentityAndEditableFields(
                    submittedRow,
                    findEnrichedRow(submittedRow, enrichedRows, i, designerPrimaryKeyFields),
                    editableFields, lookupDisplayByField, designerPrimaryKeyFields);
            if (!filteredRow.isEmpty()) {
                ChangeHistoryAuditRowKey.stamp(filteredRow, designerPrimaryKeyFields);
                filteredRows.add(filteredRow);
            }
        }
        return filteredRows;
    }

    private static Map<String, Object> copyIdentityAndEditableFields(
            Map<?, ?> submittedRow,
            Map<?, ?> enrichedRow,
            Set<String> editableFields,
            Map<String, String> lookupDisplayByField,
            List<String> designerPrimaryKeyFields) {
        Map<String, Object> filteredRow = new LinkedHashMap<>();
        // 平台键 + **这张表配置的主键列**：身份字段必须原样保留，否则下次保存时行匹配不上。
        // 只用 IDENTITY_FIELDS 会丢掉主键叫 correspondence_id / id 之类的表的身份列。
        for (String identityField : SubTableRowIdentity.identityFieldsFor(designerPrimaryKeyFields)) {
            Object identity = enrichedRow.containsKey(identityField)
                    ? enrichedRow.get(identityField)
                    : submittedRow.get(identityField);
            if (identity != null) {
                filteredRow.put(identityField, identity);
            }
        }
        for (String field : editableFields) {
            if (submittedRow.containsKey(field)) {
                filteredRow.put(field, ChangeHistoryLookupAuditValues.visibleAuditValue(
                        submittedRow.get(field), lookupDisplayByField.get(field)));
            }
        }
        return filteredRow;
    }

    private static void liftNestedSubmittedSubTables(
            List<?> submittedRows,
            List<?> enrichedRows,
            Map<String, Set<String>> editableByBinding,
            ChangeHistoryBindingAliases aliases,
            Map<String, Map<String, String>> lookupDisplayByBinding,
            Map<String, Map<String, Map<String, Object>>> rowsByTableAndIdentity,
            int remainingLiftDepth,
            List<String> parentPrimaryKeyFields) {
        if (remainingLiftDepth <= 0) {
            return;
        }
        for (int i = 0; i < submittedRows.size(); i++) {
            if (!(submittedRows.get(i) instanceof Map<?, ?> submittedRow)) {
                continue;
            }
            Object nestedSubmitted = submittedRow.get("__subTables__");
            if (!(nestedSubmitted instanceof Map<?, ?> nestedMap) || nestedMap.isEmpty()) {
                continue;
            }
            Map<?, ?> enrichedRow = findEnrichedRow(submittedRow, enrichedRows, i, parentPrimaryKeyFields);
            Object nestedEnrichedObj = enrichedRow.get("__subTables__");
            Map<?, ?> nestedEnriched = nestedEnrichedObj instanceof Map<?, ?> map ? map : Map.of();
            Map<String, Object> nestedFiltered = filter(
                    nestedMap, nestedEnriched, editableByBinding, aliases, lookupDisplayByBinding,
                    remainingLiftDepth - 1);
            ChangeHistorySubTableSliceMerger.mergeFilteredTableRows(rowsByTableAndIdentity, nestedFiltered, aliases);
        }
    }

    private static int aliasPriority(Object rawKeyValue, ChangeHistoryBindingAliases aliases) {
        String rawKey = ChangeHistoryFilterMaps.stringValue(rawKeyValue);
        if (rawKey == null) {
            return Integer.MAX_VALUE;
        }
        return aliases.aliasPriorities().getOrDefault(
                ChangeHistoryFilterMaps.normalizeAlias(rawKey), Integer.MAX_VALUE);
    }

    private static Map<?, ?> findEnrichedRow(Map<?, ?> submittedRow, List<?> enrichedRows,
            int fallbackIndex, List<String> designerPrimaryKeyFields) {
        Set<String> submittedIdentities = rowIdentities(submittedRow, designerPrimaryKeyFields);
        if (!submittedIdentities.isEmpty()) {
            for (Object candidate : enrichedRows) {
                if (candidate instanceof Map<?, ?> row
                        && !java.util.Collections.disjoint(submittedIdentities,
                                rowIdentities(row, designerPrimaryKeyFields))) {
                    return row;
                }
            }
            return Map.of();
        }
        return fallbackIndex < enrichedRows.size() && enrichedRows.get(fallbackIndex) instanceof Map<?, ?> row
                ? row
                : Map.of();
    }

    /**
     * Identity values of a row, for pairing a submitted row with its enriched counterpart.
     *
     * <p>The platform-generated key plus the columns this table DECLARES as its primary key,
     * threaded down from {@link ChangeHistoryBindingAliases#primaryKeyFieldsByBinding()}. Without
     * the configured key a table keyed by {@code correspondence_id} pairs on the platform key
     * alone, which rows saved through other paths may not carry — the pairing then falls back to
     * array position. Never a guessed column name: an unresolved key yields an empty set, and
     * {@link #findEnrichedRow} falls back to index, which mis-pairs at worst rather than merging
     * two genuinely different rows.
     */
    private static Set<String> rowIdentities(Map<?, ?> row, List<String> designerPrimaryKeyFields) {
        return SubTableRowIdentity.identityValuesOf(
                SubTableRowKeySupport.normalizeStringKeyMap(row), designerPrimaryKeyFields);
    }

    private static List<Map<String, Object>> stripAuditKeys(Iterable<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.remove(ChangeHistoryAuditRowKey.FIELD);
            out.add(copy);
        }
        return out;
    }

    private static List<?> findRows(
            Map<?, ?> enrichedTables,
            String rawKey,
            String bindingId,
            ChangeHistoryBindingAliases aliases) {
        Object exact = enrichedTables.get(rawKey);
        if (exact instanceof List<?> rows) {
            return rows;
        }
        String expectedBinding = aliases.aliasToBinding().getOrDefault(
                ChangeHistoryFilterMaps.normalizeAlias(rawKey), bindingId);
        for (Map.Entry<?, ?> candidate : enrichedTables.entrySet()) {
            String candidateKey = ChangeHistoryFilterMaps.stringValue(candidate.getKey());
            if (candidateKey == null || !(candidate.getValue() instanceof List<?> rows)) {
                continue;
            }
            String candidateBinding = aliases.aliasToBinding()
                    .getOrDefault(ChangeHistoryFilterMaps.normalizeAlias(candidateKey), candidateKey);
            if (expectedBinding.equals(candidateBinding)) {
                return rows;
            }
        }
        return List.of();
    }
}
