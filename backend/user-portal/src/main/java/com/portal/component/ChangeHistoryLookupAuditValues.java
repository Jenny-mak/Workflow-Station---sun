package com.portal.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Lookup fields submit the whole dictionary row. Change History stores the
 * designer-configured display column, not the raw object JSON.
 */
final class ChangeHistoryLookupAuditValues {

    private ChangeHistoryLookupAuditValues() {
    }

    static Object visibleAuditValue(Object value, String selectedDisplayField) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return value;
        }
        if (selectedDisplayField != null) {
            Object displayed = map.get(selectedDisplayField);
            if (displayed != null) {
                return displayed;
            }
        }
        Object id = map.get("id");
        return id != null ? id : value;
    }

    static String selectedDisplayField(Map<?, ?> rule, ObjectMapper objectMapper) {
        Object raw = rule.get("lookupConfig");
        if (raw == null && rule.get("props") instanceof Map<?, ?> props) {
            raw = props.get("lookupConfig");
        }
        Map<String, Object> config = lookupConfigMap(raw, objectMapper);
        Object displayed = config.get("selectedDisplayField");
        if (displayed == null) {
            return null;
        }
        String normalized = String.valueOf(displayed).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    static Map<String, Object> lookupConfigMap(Object raw, ObjectMapper objectMapper) {
        if (raw instanceof Map<?, ?> map) {
            return ChangeHistoryFilterMaps.castMap(map);
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }
}
