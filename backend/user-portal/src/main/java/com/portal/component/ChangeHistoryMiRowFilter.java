package com.portal.component;

import com.portal.entity.ChangeHistory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Multi-instance To Do hides other participants on {@code miParticipantRow}
 * tables. Shared sub-tables keep their own row ids.
 */
@Slf4j
final class ChangeHistoryMiRowFilter {

    private ChangeHistoryMiRowFilter() {
    }

    static List<ChangeHistory> retainForMiTask(
            JdbcTemplate jdbcTemplate,
            List<ChangeHistory> entities,
            String processInstanceId,
            String rowIdentifier) {
        if (rowIdentifier == null || rowIdentifier.isBlank()) {
            return entities;
        }
        Set<String> miCollectionTables = resolveMiCollectionHistoryNames(jdbcTemplate, processInstanceId);
        return entities.stream()
                .filter(entity -> keepHistoryRowForMiTask(entity, rowIdentifier, miCollectionTables))
                .toList();
    }

    static boolean keepHistoryRowForMiTask(
            ChangeHistory entity,
            String rowIdentifier,
            Set<String> miCollectionTables) {
        if (entity.getRowIdentifier() == null || rowIdentifier.equals(entity.getRowIdentifier())) {
            return true;
        }
        if (miCollectionTables.isEmpty()) {
            return false;
        }
        String table = ChangeHistoryComponent.normalizeSubTableNameForHistory(entity.getSubTableName());
        return table == null || !miCollectionTables.contains(table);
    }

    private static Set<String> resolveMiCollectionHistoryNames(JdbcTemplate jdbcTemplate, String processInstanceId) {
        Set<String> names = new HashSet<>();
        try {
            List<String> tableNames = jdbcTemplate.query(
                    """
                            SELECT DISTINCT COALESCE(td.table_name, rt.table_name) AS table_name
                            FROM up_process_instance pi
                            INNER JOIN dw_function_units fu
                                ON fu.code = COALESCE(NULLIF(pi.function_unit_code, ''), pi.process_definition_key)
                            INNER JOIN dw_form_definitions fd ON fd.function_unit_id = fu.id
                            INNER JOIN dw_form_table_bindings binding ON binding.form_id = fd.id
                            LEFT JOIN dw_table_definitions td ON td.id = binding.table_id
                            LEFT JOIN rt_table_definitions rt ON rt.id = binding.relation_table_id
                            WHERE pi.id = ? AND binding.binding_link_mode = 'miParticipantRow'
                            """,
                    (rs, rowNum) -> rs.getString("table_name"),
                    processInstanceId);
            addNormalizedTableNames(names, tableNames);
        } catch (Exception ex) {
            log.debug("Could not resolve MI collection tables for {}: {}", processInstanceId, ex.getMessage());
        }
        return names;
    }

    private static void addNormalizedTableNames(Set<String> names, List<String> tableNames) {
        if (tableNames == null) {
            return;
        }
        for (String tableName : tableNames) {
            String normalized = ChangeHistoryComponent.normalizeSubTableNameForHistory(tableName);
            if (normalized != null) {
                names.add(normalized);
            }
        }
    }
}
