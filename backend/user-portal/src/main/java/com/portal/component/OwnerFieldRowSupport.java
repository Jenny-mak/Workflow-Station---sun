package com.portal.component;

import com.platform.common.jdbc.SubTableRowIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owner stored-value parse / row identity helpers. Kept out of
 * {@link OwnerFieldComponent} so that class stays under the quality line budget.
 */
final class OwnerFieldRowSupport {

    private OwnerFieldRowSupport() {
    }

    static String joinStoredUserValues(List<String> ids) {
        return ids.stream()
                .map(id -> OwnerCaseHandlerCalculator.USER_PREFIX + id)
                .collect(Collectors.joining(","));
    }

    static List<String> parseStoredUserIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(OwnerCaseHandlerCalculator.USER_PREFIX)
                    && trimmed.length() > OwnerCaseHandlerCalculator.USER_PREFIX.length()) {
                String id = trimmed.substring(OwnerCaseHandlerCalculator.USER_PREFIX.length()).trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    static boolean isStoredOwnerValue(String value) {
        return value != null && !value.isBlank()
                && value.startsWith(OwnerCaseHandlerCalculator.USER_PREFIX);
    }

    static String scalar(Map<String, Object> record, String field) {
        if (record == null) {
            return "";
        }
        Object raw = record.get(field);
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> previousSubSlices(Map<String, Object> previousVariables) {
        if (previousVariables == null
                || !(previousVariables.get(OwnerFieldComponent.SUB_TABLES_KEY) instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> previousSliceRows(Map<String, Object> previousSlices, String sliceKey) {
        if (previousSlices == null) {
            return List.of();
        }
        Object rows = previousSlices.get(sliceKey);
        if (rows == null) {
            rows = previousSlices.get(sliceKey.toLowerCase(Locale.ROOT));
        }
        if (!(rows instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> typed = new ArrayList<>();
        for (Object row : list) {
            if (row instanceof Map<?, ?> map) {
                typed.add((Map<String, Object>) map);
            }
        }
        return typed;
    }

    @SuppressWarnings("unchecked")
    static boolean rowMatchesCurrentItem(Map<String, Object> row, Map<String, Object> variables) {
        return rowMatchesCurrentItem(row, variables, List.of());
    }

    @SuppressWarnings("unchecked")
    static boolean rowMatchesCurrentItem(Map<String, Object> row, Map<String, Object> variables,
                                         List<String> designerPrimaryKeyFields) {
        if (variables == null) {
            return false;
        }
        Object item = variables.get(OwnerFieldComponent.CURRENT_ITEM_KEY);
        if (!(item instanceof Map<?, ?>)) {
            item = variables.get(OwnerFieldComponent.CURRENT_ITEM_ALIAS);
        }
        if (!(item instanceof Map<?, ?> itemMap)) {
            return false;
        }
        Set<String> identities = SubTableRowIdentity.identityValuesOf(row, designerPrimaryKeyFields);
        if (identities.isEmpty()) {
            return false;
        }
        Set<String> itemIds = SubTableRowIdentity.identityValuesOf(
                (Map<String, Object>) itemMap, designerPrimaryKeyFields);
        itemIds.retainAll(identities);
        return !itemIds.isEmpty();
    }

    static Map<String, Object> matchPreviousRow(List<Map<String, Object>> previousRows,
                                                Map<String, Object> row) {
        return matchPreviousRow(previousRows, row, List.of());
    }

    static Map<String, Object> matchPreviousRow(List<Map<String, Object>> previousRows,
                                                Map<String, Object> row,
                                                List<String> designerPrimaryKeyFields) {
        if (previousRows == null || previousRows.isEmpty()) {
            return null;
        }
        Set<String> identities = SubTableRowIdentity.identityValuesOf(row, designerPrimaryKeyFields);
        if (identities.isEmpty()) {
            return null;
        }
        for (Map<String, Object> previous : previousRows) {
            Set<String> previousIds = SubTableRowIdentity.identityValuesOf(previous, designerPrimaryKeyFields);
            previousIds.retainAll(identities);
            if (!previousIds.isEmpty()) {
                return previous;
            }
        }
        return null;
    }
}
