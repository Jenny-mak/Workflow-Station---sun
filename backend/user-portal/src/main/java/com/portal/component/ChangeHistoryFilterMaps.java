package com.portal.component;

import java.util.LinkedHashMap;
import java.util.Map;

final class ChangeHistoryFilterMaps {

    private ChangeHistoryFilterMaps() {
    }

    static Map<String, Object> castMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    static String normalizeAlias(String value) {
        String normalized = ChangeHistoryComponent.normalizeSubTableNameForHistory(value);
        return normalized != null ? normalized : value.trim().toLowerCase();
    }
}
