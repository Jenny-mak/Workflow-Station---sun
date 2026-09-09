package com.portal.component;

import com.platform.common.subtable.SubTableStoreKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves {@code dw_field_definitions.is_primary_key} for a change-history slice key.
 *
 * <p>Uncached on purpose: {@link ChangeHistoryComponent} is a singleton, and caching here would
 * keep serving a primary key after the designer changed it.
 *
 * <p>Table-name lookup is scoped to {@code dw_function_units.code} when the caller has it.
 * Without that code, a name that maps to more than one function unit returns empty — never a
 * mixed list of primary-key columns.
 */
@Slf4j
final class ChangeHistoryDesignerPrimaryKeyLookup {

    private ChangeHistoryDesignerPrimaryKeyLookup() {
    }

    static List<String> resolve(JdbcTemplate jdbcTemplate, String sliceKey) {
        return resolve(jdbcTemplate, sliceKey, null);
    }

    static List<String> resolve(JdbcTemplate jdbcTemplate, String sliceKey, String functionUnitCode) {
        if (sliceKey == null || sliceKey.isBlank() || jdbcTemplate == null) {
            return List.of();
        }
        try {
            String tableName = SubTableStoreKeys.tableNameOf(sliceKey);
            if (tableName != null && !tableName.isBlank()) {
                return queryByTableName(jdbcTemplate, tableName, functionUnitCode);
            }
            if (sliceKey.chars().allMatch(Character::isDigit)) {
                return queryByBindingId(jdbcTemplate, sliceKey);
            }
            List<String> byHistoryName = queryByTableName(jdbcTemplate, sliceKey, functionUnitCode);
            if (!byHistoryName.isEmpty()) {
                return byHistoryName;
            }
            String recovered = tableNameFromHistoryNormalizedCanonicalKey(sliceKey);
            if (recovered != null) {
                return queryByTableName(jdbcTemplate, recovered, functionUnitCode);
            }
        } catch (RuntimeException ex) {
            log.warn("Could not resolve primary key for sub-table slice {}: {}",
                    sliceKey, ex.getMessage());
        }
        return List.of();
    }

    /**
     * {@link ChangeHistoryComponent#normalizeSubTableNameForHistory} replaces non-alphanumerics
     * with {@code _}, so {@link SubTableStoreKeys#DW_PREFIX} {@code dw:} becomes {@code dw_}.
     * Invert that encoding; do not treat an arbitrary {@code dw_*} physical table name as a prefix.
     */
    static String tableNameFromHistoryNormalizedCanonicalKey(String historyName) {
        if (historyName == null || historyName.isBlank()) {
            return null;
        }
        String dwHistoryPrefix = storeKeyHistoryPrefix(SubTableStoreKeys.DW_PREFIX);
        if (historyName.startsWith(dwHistoryPrefix)
                && historyName.length() > dwHistoryPrefix.length()) {
            return historyName.substring(dwHistoryPrefix.length());
        }
        String rtHistoryPrefix = storeKeyHistoryPrefix(SubTableStoreKeys.RT_PREFIX);
        if (historyName.startsWith(rtHistoryPrefix)
                && historyName.length() > rtHistoryPrefix.length()) {
            return historyName.substring(rtHistoryPrefix.length());
        }
        return null;
    }

    private static String storeKeyHistoryPrefix(String storePrefix) {
        return storePrefix.replace(':', '_');
    }

    private static List<String> queryByTableName(
            JdbcTemplate jdbcTemplate, String tableName, String functionUnitCode) {
        if (functionUnitCode != null && !functionUnitCode.isBlank()) {
            return jdbcTemplate.queryForList("""
                    SELECT f.field_name
                    FROM dw_field_definitions f
                    JOIN dw_table_definitions t ON t.id = f.table_id
                    JOIN dw_function_units fu ON fu.id = t.function_unit_id
                    WHERE lower(t.table_name) = lower(?)
                      AND fu.code = ?
                      AND COALESCE(f.is_primary_key, false) = true
                    ORDER BY f.sort_order NULLS LAST, f.id
                    """, String.class, tableName, functionUnitCode);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT f.field_name, t.function_unit_id
                FROM dw_field_definitions f
                JOIN dw_table_definitions t ON t.id = f.table_id
                WHERE lower(t.table_name) = lower(?)
                  AND COALESCE(f.is_primary_key, false) = true
                ORDER BY f.sort_order NULLS LAST, f.id
                """, tableName);
        Set<Object> functionUnits = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            functionUnits.add(row.get("function_unit_id"));
            Object fieldName = row.get("field_name");
            if (fieldName != null) {
                names.add(String.valueOf(fieldName));
            }
        }
        if (functionUnits.size() > 1) {
            return List.of();
        }
        return names;
    }

    private static List<String> queryByBindingId(JdbcTemplate jdbcTemplate, String bindingId) {
        return jdbcTemplate.queryForList("""
                SELECT f.field_name
                FROM dw_form_table_bindings b
                JOIN dw_field_definitions f ON f.table_id = b.table_id
                WHERE b.id = ? AND COALESCE(f.is_primary_key, false) = true
                ORDER BY f.sort_order NULLS LAST, f.id
                """, String.class, Long.valueOf(bindingId));
    }
}
