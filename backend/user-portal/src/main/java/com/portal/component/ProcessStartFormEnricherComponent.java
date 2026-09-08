package com.portal.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.dto.PkGenerationConfig;
import com.platform.common.fk.PrimaryKeyAllocationService;
import com.portal.exception.PortalException;
import com.portal.service.UserDisplayNameResolver;
import com.portal.util.SystemAuditFieldFiller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single write-path for process-start field filling (Portal UI and email START_PROCESS).
 *
 * <p>Order is fixed: main auto-PK → structural FK → sub-table auto-PK → FK again (nested
 * parents now have PKs) → Owner → audit → computed formulas → Request ID.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessStartFormEnricherComponent {

    private static final String SUB_TABLES_KEY = "__subTables__";
    private static final char UNIT_SEP = '\u001f';

    private record AutoPkField(String fieldName, PkGenerationConfig config) {}

    private record FkMeta(Long tableId, String fieldName, Long refTableId, List<String> refPkFields) {}

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PrimaryKeyAllocationService primaryKeyAllocationService;
    private final PortalPrimaryKeyAllocationComponent portalPrimaryKeyAllocationComponent;
    private final ProcessSubTablePrimaryKeyEnricherComponent processSubTablePrimaryKeyEnricherComponent;
    private final OwnerFieldComponent ownerFieldComponent;
    private final ComputedFieldRecalculator computedFieldRecalculator;
    private final RequestIdEnricher requestIdEnricher;
    private final UserDisplayNameResolver userDisplayNameResolver;

    public void enrichOnInsert(String functionUnitCode, String userId, Map<String, Object> variables) {
        if (variables == null) {
            return;
        }
        Long fuId = requireFunctionUnitId(functionUnitCode);
        Long primaryTableId = loadPrimaryTableId(fuId);
        if (primaryTableId != null) {
            allocateMainAutoPks(primaryTableId, variables);
        }
        Map<String, Long> sliceToTable = loadSliceKeyToTableId(fuId);
        List<FkMeta> fks = loadForeignKeys(fuId);
        fillForeignKeys(variables, primaryTableId, sliceToTable, fks);
        processSubTablePrimaryKeyEnricherComponent.allocateMissingPrimaryKeysInVariables(
                functionUnitCode, variables);
        fillForeignKeys(variables, primaryTableId, sliceToTable, fks);
        ownerFieldComponent.applyOnSubmit(functionUnitCode,
                new OwnerFieldComponent.OwnerWriteContext(userId, userId, null, null, null), variables);
        SystemAuditFieldFiller.fillOnInsert(variables, userDisplayNameResolver.resolve(userId));
        computedFieldRecalculator.recalculate(functionUnitCode, variables);
        requestIdEnricher.stampRequestId(functionUnitCode, variables);
    }

    private Long requireFunctionUnitId(String functionUnitCode) {
        if (functionUnitCode == null || functionUnitCode.isBlank()) {
            throw new PortalException("400", "functionUnitCode is required for process start enrich");
        }
        return portalPrimaryKeyAllocationComponent.resolveFunctionUnitIdForAllocation(functionUnitCode);
    }

    private Long loadPrimaryTableId(Long functionUnitId) {
        return jdbcTemplate.query(
                """
                SELECT ftb.table_id
                FROM dw_form_definitions fd
                INNER JOIN dw_form_table_bindings ftb
                    ON ftb.form_id = fd.id AND ftb.binding_type = 'PRIMARY'
                WHERE fd.function_unit_id = ? AND fd.form_type = 'PROCESS'
                ORDER BY ftb.sort_order NULLS LAST, ftb.id
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getLong("table_id") : null,
                functionUnitId);
    }

    private void allocateMainAutoPks(Long tableId, Map<String, Object> variables) {
        for (AutoPkField pk : loadAutoPkFields(tableId)) {
            if (!isBlank(variables.get(pk.fieldName()))) {
                continue;
            }
            List<String> values = primaryKeyAllocationService.allocate(
                    tableId, pk.fieldName(), pk.config(), 1, "");
            if (values != null && !values.isEmpty()) {
                variables.put(pk.fieldName(), values.get(0));
            }
        }
    }

    private List<AutoPkField> loadAutoPkFields(Long tableId) {
        return jdbcTemplate.query(
                """
                SELECT field_name, pk_generation_json::text AS json
                FROM dw_field_definitions
                WHERE table_id = ? AND COALESCE(is_primary_key, false) = true
                """,
                rs -> {
                    List<AutoPkField> out = new ArrayList<>();
                    while (rs.next()) {
                        PkGenerationConfig config = toPkConfig(
                                parseJsonMap(rs.getString("json"), "pk_generation_json", tableId));
                        if ("manual".equalsIgnoreCase(config.getStrategy())) {
                            continue;
                        }
                        out.add(new AutoPkField(rs.getString("field_name"), config));
                    }
                    return out;
                },
                tableId);
    }

    private void fillForeignKeys(
            Map<String, Object> main,
            Long primaryTableId,
            Map<String, Long> sliceToTable,
            List<FkMeta> fks) {
        if (sliceToTable.isEmpty() || fks.isEmpty()) {
            return;
        }
        Object sub = main.get(SUB_TABLES_KEY);
        if (!(sub instanceof Map<?, ?>)) {
            return;
        }
        Map<Long, Map<String, Object>> ancestors = new HashMap<>();
        if (primaryTableId != null) {
            ancestors.put(primaryTableId, main);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> subTables = (Map<String, Object>) sub;
        walkSubTables(subTables, ancestors, sliceToTable, fks);
    }

    @SuppressWarnings("unchecked")
    private void walkSubTables(
            Map<String, Object> subTables,
            Map<Long, Map<String, Object>> ancestors,
            Map<String, Long> sliceToTable,
            List<FkMeta> fks) {
        for (Map.Entry<String, Object> entry : subTables.entrySet()) {
            Long tableId = resolveTableId(entry.getKey(), sliceToTable);
            if (tableId == null || !(entry.getValue() instanceof List<?> rows)) {
                continue;
            }
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Map<String, Object> row = (Map<String, Object>) item;
                applyFksToRow(row, tableId, ancestors, fks);
                if (row.get(SUB_TABLES_KEY) instanceof Map<?, ?> nested) {
                    Map<Long, Map<String, Object>> childAncestors = new HashMap<>(ancestors);
                    childAncestors.put(tableId, row);
                    walkSubTables((Map<String, Object>) nested, childAncestors, sliceToTable, fks);
                }
            }
        }
    }

    private void applyFksToRow(
            Map<String, Object> row,
            Long tableId,
            Map<Long, Map<String, Object>> ancestors,
            List<FkMeta> fks) {
        for (FkMeta fk : fks) {
            if (!tableId.equals(fk.tableId()) || !isBlank(row.get(fk.fieldName()))) {
                continue;
            }
            String encoded = encodeCompositePk(fk.refPkFields(), ancestors.get(fk.refTableId()));
            if (encoded != null) {
                row.put(fk.fieldName(), encoded);
            }
        }
    }

    private Map<String, Long> loadSliceKeyToTableId(Long functionUnitId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                """
                SELECT ftb.id AS binding_id, ftb.table_id, td.table_name
                FROM dw_form_table_bindings ftb
                INNER JOIN dw_form_definitions fd ON fd.id = ftb.form_id
                INNER JOIN dw_table_definitions td ON td.id = ftb.table_id
                WHERE fd.function_unit_id = ? AND ftb.table_id IS NOT NULL
                """,
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("bindingId", rs.getLong("binding_id"));
                    m.put("tableId", rs.getLong("table_id"));
                    m.put("tableName", rs.getString("table_name"));
                    return m;
                },
                functionUnitId);
        Map<String, Long> out = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long bindingId = (Long) row.get("bindingId");
            Long tableId = (Long) row.get("tableId");
            String tableName = (String) row.get("tableName");
            out.put(String.valueOf(bindingId), tableId);
            if (tableName != null && !tableName.isBlank()) {
                out.putIfAbsent(tableName, tableId);
                out.putIfAbsent(tableName.toLowerCase(Locale.ROOT), tableId);
            }
        }
        return out;
    }

    private List<FkMeta> loadForeignKeys(Long functionUnitId) {
        return jdbcTemplate.query(
                """
                SELECT fd.table_id, fd.field_name, fd.ref_table_id,
                       fd.ref_primary_key_fields::text AS ref_pk
                FROM dw_field_definitions fd
                INNER JOIN dw_table_definitions td ON td.id = fd.table_id
                WHERE td.function_unit_id = ?
                  AND COALESCE(fd.is_foreign_key, false) = true
                  AND fd.ref_table_id IS NOT NULL
                """,
                rs -> {
                    List<FkMeta> out = new ArrayList<>();
                    while (rs.next()) {
                        List<String> refPks = parseStringList(
                                rs.getString("ref_pk"), "ref_primary_key_fields", functionUnitId);
                        if (refPks.isEmpty()) {
                            continue;
                        }
                        out.add(new FkMeta(
                                rs.getLong("table_id"),
                                rs.getString("field_name"),
                                rs.getLong("ref_table_id"),
                                refPks));
                    }
                    return out;
                },
                functionUnitId);
    }

    private static Long resolveTableId(String sliceKey, Map<String, Long> sliceToTable) {
        Long tableId = sliceToTable.get(sliceKey);
        if (tableId != null) {
            return tableId;
        }
        return sliceToTable.get(sliceKey.toLowerCase(Locale.ROOT));
    }

    static String encodeCompositePk(List<String> refPkFields, Map<String, Object> parent) {
        if (refPkFields == null || refPkFields.isEmpty() || parent == null) {
            return null;
        }
        List<String> ordered = new ArrayList<>(refPkFields);
        Collections.sort(ordered);
        if (ordered.size() == 1) {
            Object value = lookupIgnoreCase(parent, ordered.get(0));
            return isBlank(value) ? null : String.valueOf(value);
        }
        List<String> parts = new ArrayList<>();
        for (String field : ordered) {
            Object value = lookupIgnoreCase(parent, field);
            if (isBlank(value)) {
                return null;
            }
            parts.add(field + "=" + value);
        }
        return String.join(String.valueOf(UNIT_SEP), parts);
    }

    private static Object lookupIgnoreCase(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && lower.equals(e.getKey().toLowerCase(Locale.ROOT))) {
                return e.getValue();
            }
        }
        return null;
    }

    private List<String> parseStringList(String json, String fieldLabel, Object scopeId) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            if (parsed == null) {
                return List.of();
            }
            return parsed.stream().filter(s -> s != null && !s.isBlank()).toList();
        } catch (Exception e) {
            log.warn("Failed to parse {} for scope {}: {}", fieldLabel, scopeId, e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> parseJsonMap(String json, String fieldLabel, Object scopeId) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse {} for scope {}: {}", fieldLabel, scopeId, e.getMessage());
            return Map.of();
        }
    }

    private PkGenerationConfig toPkConfig(Map<String, Object> json) {
        if (json == null || json.isEmpty()) {
            return PkGenerationConfig.builder().strategy("uuid").build();
        }
        return objectMapper.convertValue(json, PkGenerationConfig.class);
    }

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }
}
