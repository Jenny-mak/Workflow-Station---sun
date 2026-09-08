package com.admin.component;

import com.admin.dto.response.FormContentDTO;
import com.admin.dto.response.TableBindingDTO;
import com.admin.dto.response.TableFieldDefinitionDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.jdbc.SubTableRowIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads form → data table bindings (dw_form_table_bindings) and enriches them with
 * field definitions (dw/rt_field_definitions) for assembled function unit content.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormTableBindingLoader {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Attach tableBindings to each form DTO by querying dw_form_table_bindings.
     * Prefers sourceId match; falls back to form_name match for forms without sourceId.
     */
    public void attachTableBindings(List<FormContentDTO> forms) {
        if (forms.isEmpty()) return;

        try {
            List<String> formSourceIds = forms.stream()
                    .map(FormContentDTO::getSourceId)
                    .filter(sid -> sid != null && !sid.isBlank())
                    .distinct()
                    .toList();

            List<String> formNamesForFallback = forms.stream()
                    .filter(f -> f.getSourceId() == null || f.getSourceId().isBlank())
                    .map(FormContentDTO::getName)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();

            Map<String, List<TableBindingDTO>> bindingsBySourceId = new LinkedHashMap<>();
            Map<String, List<TableBindingDTO>> bindingsByFormName = new LinkedHashMap<>();

            if (!formSourceIds.isEmpty()) {
                String placeholders = formSourceIds.stream().map(n -> "?").collect(Collectors.joining(","));
                // LEFT JOIN both dw_table_definitions (SUB/PRIMARY via table_id) and rt_table_definitions
                // (RELATED via relation_table_id) so designer-configured display names propagate to portal
                // for all binding types — mirrors user-portal ProcessFormComponent.loadSubTableBindingMapsForForm.
                String sql =
                        "SELECT fd.id as form_id, ftb.id as binding_id, ftb.binding_type, ftb.binding_mode, " +
                        "       ftb.sub_mode, ftb.foreign_key_field, ftb.binding_link_mode, ftb.sort_order, " +
                        "       COALESCE(td.id, rt.id) as table_id, " +
                        "       COALESCE(td.table_name, rt.table_name) AS table_name, " +
                        "       COALESCE(td.table_display_name, rt.display_name) AS table_display_name, " +
                        "       COALESCE(td.table_type, 'RELATION') as table_type, " +
                        "       COALESCE(td.display_name, rt.description) as table_description, " +
                        "       (SELECT array_agg(fd_inner.field_name ORDER BY fd_inner.sort_order NULLS LAST, fd_inner.id) " +
                        "        FROM dw_field_definitions fd_inner " +
                        "        WHERE fd_inner.table_id = td.id AND COALESCE(fd_inner.is_primary_key, false) = true) AS primary_key_fields " +
                        "FROM dw_form_definitions fd " +
                        "JOIN dw_form_table_bindings ftb ON ftb.form_id = fd.id " +
                        "LEFT JOIN dw_table_definitions td ON td.id = ftb.table_id " +
                        "LEFT JOIN rt_table_definitions rt ON rt.id = ftb.relation_table_id " +
                        "WHERE fd.id::text IN (" + placeholders + ") " +
                        "ORDER BY fd.id, ftb.sort_order";
                jdbcTemplate.query(sql, rs -> {
                    String formId = rs.getString("form_id");
                    bindingsBySourceId.computeIfAbsent(formId, k -> new ArrayList<>()).add(mapBindingRow(rs));
                }, formSourceIds.toArray());
            }

            if (!formNamesForFallback.isEmpty()) {
                String placeholders = formNamesForFallback.stream().map(n -> "?").collect(Collectors.joining(","));
                String sql =
                        "SELECT latest.form_name, ftb.id as binding_id, ftb.binding_type, ftb.binding_mode, " +
                        "       ftb.sub_mode, ftb.foreign_key_field, ftb.binding_link_mode, ftb.sort_order, " +
                        "       COALESCE(td.id, rt.id) as table_id, " +
                        "       COALESCE(td.table_name, rt.table_name) AS table_name, " +
                        "       COALESCE(td.table_display_name, rt.display_name) AS table_display_name, " +
                        "       COALESCE(td.table_type, 'RELATION') as table_type, " +
                        "       COALESCE(td.display_name, rt.description) as table_description, " +
                        "       (SELECT array_agg(fd_inner.field_name ORDER BY fd_inner.sort_order NULLS LAST, fd_inner.id) " +
                        "        FROM dw_field_definitions fd_inner " +
                        "        WHERE fd_inner.table_id = td.id AND COALESCE(fd_inner.is_primary_key, false) = true) AS primary_key_fields " +
                        "FROM (SELECT DISTINCT ON (form_name) id, form_name, config_json FROM dw_form_definitions " +
                        "      WHERE form_name IN (" + placeholders + ") ORDER BY form_name, id DESC) latest " +
                        "JOIN dw_form_table_bindings ftb ON ftb.form_id = latest.id " +
                        "LEFT JOIN dw_table_definitions td ON td.id = ftb.table_id " +
                        "LEFT JOIN rt_table_definitions rt ON rt.id = ftb.relation_table_id " +
                        "ORDER BY latest.form_name, ftb.sort_order";
                jdbcTemplate.query(sql, rs -> {
                    String formName = rs.getString("form_name");
                    bindingsByFormName.computeIfAbsent(formName, k -> new ArrayList<>()).add(mapBindingRow(rs));
                }, formNamesForFallback.toArray());
            }

            for (List<TableBindingDTO> list : bindingsBySourceId.values()) {
                enrichBindingsWithFieldDefinitions(list);
            }
            for (List<TableBindingDTO> list : bindingsByFormName.values()) {
                enrichBindingsWithFieldDefinitions(list);
            }

            // Attach bindings: prefer sourceId match, fallback to form_name
            for (FormContentDTO form : forms) {
                List<TableBindingDTO> bindings;
                if (form.getSourceId() != null && !form.getSourceId().isBlank()) {
                    bindings = bindingsBySourceId.getOrDefault(form.getSourceId(), Collections.emptyList());
                } else {
                    bindings = bindingsByFormName.getOrDefault(form.getName(), Collections.emptyList());
                }
                form.setTableBindings(bindings);
            }
            log.info("Attached tableBindings to {} forms", forms.size());
        } catch (Exception e) {
            log.warn("Failed to load tableBindings: {}", e.getMessage());
            for (FormContentDTO form : forms) {
                form.setTableBindings(Collections.emptyList());
            }
        }
    }

    private TableBindingDTO mapBindingRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return TableBindingDTO.builder()
                .bindingId(rs.getLong("binding_id"))
                .tableId(readNullableLong(rs, "table_id"))
                .bindingType(rs.getString("binding_type"))
                .bindingMode(rs.getString("binding_mode"))
                .subMode(rs.getString("sub_mode"))
                .foreignKeyField(rs.getString("foreign_key_field"))
                .bindingLinkMode(rs.getString("binding_link_mode"))
                .sortOrder(rs.getInt("sort_order"))
                .tableName(rs.getString("table_name"))
                .tableDisplayName(rs.getString("table_display_name"))
                .tableType(rs.getString("table_type"))
                .tableDescription(rs.getString("table_description"))
                // Designer PK as configured; the generated-identity fallback for tables that declare
                // none is applied in enrichBindingsWithFieldDefinitions, where this table's field
                // names are known (needed for the collision guard).
                .primaryKeyFields(readTextArrayColumn(rs, "primary_key_fields"))
                .build();
    }

    /**
     * The row-identity columns runtime code may key a row by.
     *
     * <p>Designer-declared primary keys win. When a table declares none — the majority of them:
     * measured 13 of 24 in dev, including PRIMARY (main) bindings and every RELATED one, since the
     * PK subquery only ever reads {@code dw_field_definitions} — this reports the identity the
     * platform generates instead.
     *
     * <p><b>What that identity is.</b> A generated <b>UUID</b>. It is not a designer column and
     * carries no business meaning: {@link SubTableRowIdentity#ensureIdentity} puts
     * {@code UUID.randomUUID()} on any row persisted without an identity, driven from
     * {@code ProcessInstance}'s {@code @PrePersist}/{@code @PreUpdate}, so every stored row of a
     * PK-less table has one. The name of the key it lives under is deliberately taken from
     * {@link SubTableRowIdentity#CANONICAL_FIELD} rather than written out here: the reader must name
     * whatever the writer chose, and spelling it as a literal is exactly how the two halves drift
     * apart. Renaming the constant moves both sides at once.
     *
     * <p><b>Why this exists.</b> The write side handed each row a UUID while the read side told the
     * client the table had no primary key, so every consumer that needs to tell two rows apart
     * invented its own rule — hardcoded {@code 'id'} / {@code 'row_id'} column names, or "does this
     * value look like a UUID" shape tests. Each silently answers wrong on some Function Unit; the
     * shape test in particular reads a real {@code prefixedSequence} key such as {@code Corr-000004}
     * as "not a primary key".
     *
     * <p><b>Collision guard.</b> A designer is free to create a business column that happens to be
     * named like the platform key (three tables in dev already have a field called {@code row_id},
     * all of them declared as the primary key, which is the harmless case). If such a column exists
     * but is NOT the primary key, the generated identity would be indistinguishable from that
     * business value, so no identity is reported at all — callers then treat the table as
     * unidentifiable rather than keying rows by an unrelated business column.
     */
    private List<String> resolveRowIdentityFields(
            List<String> designerPrimaryKeyFields, Set<String> designerFieldNames) {
        if (designerPrimaryKeyFields != null && !designerPrimaryKeyFields.isEmpty()) {
            return designerPrimaryKeyFields;
        }
        String generated = SubTableRowIdentity.CANONICAL_FIELD;
        boolean shadowedByBusinessColumn = designerFieldNames != null
                && designerFieldNames.stream().anyMatch(name -> generated.equalsIgnoreCase(name));
        if (shadowedByBusinessColumn) {
            log.warn("Table declares a business column named '{}' but no primary key; "
                    + "reporting no row identity rather than keying rows by that column", generated);
            return List.of();
        }
        return List.of(generated);
    }

    private void enrichBindingsWithFieldDefinitions(List<TableBindingDTO> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        Set<Long> dwTableIds = new HashSet<>();
        Set<Long> rtTableIds = new HashSet<>();
        for (TableBindingDTO binding : bindings) {
            if (binding.getTableId() == null) {
                continue;
            }
            if ("RELATION".equalsIgnoreCase(binding.getTableType())) {
                rtTableIds.add(binding.getTableId());
            } else {
                dwTableIds.add(binding.getTableId());
            }
        }
        Map<Long, List<TableFieldDefinitionDTO>> dwFields = loadFieldDefinitionsFromTable("dw_field_definitions", dwTableIds);
        Map<Long, List<TableFieldDefinitionDTO>> rtFields = loadFieldDefinitionsFromTable("rt_field_definitions", rtTableIds);
        realignForeignKeyReferencesToLivePrimaryKeys(dwFields, rtFields);
        for (TableBindingDTO binding : bindings) {
            if (binding.getTableId() == null) {
                binding.setFieldDefinitions(Collections.emptyList());
                continue;
            }
            List<TableFieldDefinitionDTO> fields = "RELATION".equalsIgnoreCase(binding.getTableType())
                    ? rtFields.getOrDefault(binding.getTableId(), Collections.emptyList())
                    : dwFields.getOrDefault(binding.getTableId(), Collections.emptyList());
            binding.setFieldDefinitions(fields);

            // A RELATED binding's PK never came from the SQL above (that subquery only reads
            // dw_field_definitions), so resolve it from the field definitions just attached before
            // falling back — otherwise every relation table would report the generated identity even
            // when it declares a perfectly good primary key of its own.
            List<String> declared = binding.getPrimaryKeyFields();
            if (declared == null || declared.isEmpty()) {
                declared = fields.stream()
                        .filter(f -> Boolean.TRUE.equals(f.getIsPrimaryKey()))
                        .map(TableFieldDefinitionDTO::getFieldName)
                        .filter(Objects::nonNull)
                        .toList();
            }
            Set<String> fieldNames = fields.stream()
                    .map(TableFieldDefinitionDTO::getFieldName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            binding.setPrimaryKeyFields(resolveRowIdentityFields(declared, fieldNames));
        }
    }

    /**
     * Resolve every FK's {@code refPrimaryKeyFields} from the referenced table's CURRENT primary key
     * columns instead of trusting the copy stored on the FK row.
     *
     * <p>{@code ref_primary_key_fields} duplicates the parent's PK column names as free text. Renaming
     * a parent PK in Table Design therefore leaves every child FK pointing at a column that no longer
     * exists, and nothing fails loudly: the Portal's FK guard simply cannot find that column on the
     * parent row and refuses every child row Add with "create a &lt;parent&gt; record first". Deriving
     * the names here — from the same live {@code *_field_definitions} config this method already
     * loaded — makes a rename self-correcting at read time, so no data repair is ever needed and a
     * stale stored copy cannot break the runtime.
     *
     * <p>Only overrides when the referenced table's PK is actually known and differs; an FK pointing
     * at a table outside this form's bindings keeps whatever it had (nothing better is available).
     */
    private void realignForeignKeyReferencesToLivePrimaryKeys(
            Map<Long, List<TableFieldDefinitionDTO>> dwFields,
            Map<Long, List<TableFieldDefinitionDTO>> rtFields) {
        Map<Long, List<String>> primaryKeysByTable = new LinkedHashMap<>();
        for (Map<Long, List<TableFieldDefinitionDTO>> source : List.of(dwFields, rtFields)) {
            for (Map.Entry<Long, List<TableFieldDefinitionDTO>> e : source.entrySet()) {
                List<String> pk = e.getValue().stream()
                        .filter(f -> Boolean.TRUE.equals(f.getIsPrimaryKey()))
                        .map(TableFieldDefinitionDTO::getFieldName)
                        .filter(n -> n != null && !n.isBlank())
                        .toList();
                if (!pk.isEmpty()) {
                    primaryKeysByTable.put(e.getKey(), pk);
                }
            }
        }
        if (primaryKeysByTable.isEmpty()) {
            return;
        }
        for (Map<Long, List<TableFieldDefinitionDTO>> source : List.of(dwFields, rtFields)) {
            for (List<TableFieldDefinitionDTO> fields : source.values()) {
                for (TableFieldDefinitionDTO field : fields) {
                    if (!Boolean.TRUE.equals(field.getIsForeignKey()) || field.getRefTableId() == null) {
                        continue;
                    }
                    List<String> livePk = primaryKeysByTable.get(field.getRefTableId());
                    if (livePk == null || livePk.equals(field.getRefPrimaryKeyFields())) {
                        continue;
                    }
                    log.info("FK {} -> table {}: realigned refPrimaryKeyFields {} to live PK {}",
                            field.getFieldName(), field.getRefTableId(),
                            field.getRefPrimaryKeyFields(), livePk);
                    field.setRefPrimaryKeyFields(livePk);
                }
            }
        }
    }

    private Map<Long, List<TableFieldDefinitionDTO>> loadFieldDefinitionsFromTable(String table, Set<Long> tableIds) {
        if (tableIds == null || tableIds.isEmpty()) {
            return Collections.emptyMap();
        }
        String placeholders = tableIds.stream().map(id -> "?").collect(Collectors.joining(","));
        // is_computed / computed_field_json exist on both dw_ and rt_ field definitions
        // (00-schema/65 and /66), so the two table kinds still share one query.
        String sql =
                "SELECT table_id, field_name, data_type, is_primary_key, is_foreign_key, ref_table_id, " +
                "       ref_primary_key_fields, pk_generation_json, fk_display_mode, " +
                "       is_computed, computed_field_json " +
                "FROM " + table + " WHERE table_id IN (" + placeholders + ") " +
                "ORDER BY table_id, sort_order NULLS LAST, id";
        Map<Long, List<TableFieldDefinitionDTO>> byTable = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            Long tableId = readNullableLong(rs, "table_id");
            if (tableId == null) {
                return;
            }
            TableFieldDefinitionDTO field = TableFieldDefinitionDTO.builder()
                    .fieldName(rs.getString("field_name"))
                    .dataType(rs.getString("data_type"))
                    .isPrimaryKey(rs.getBoolean("is_primary_key"))
                    .isForeignKey(rs.getBoolean("is_foreign_key"))
                    .refTableId(readNullableLong(rs, "ref_table_id"))
                    .refPrimaryKeyFields(readJsonStringList(rs, "ref_primary_key_fields"))
                    .pkGeneration(readJsonMap(rs, "pk_generation_json"))
                    .fkDisplayMode(rs.getString("fk_display_mode"))
                    .isComputed(rs.getBoolean("is_computed"))
                    .computedField(readJsonMap(rs, "computed_field_json"))
                    .build();
            byTable.computeIfAbsent(tableId, k -> new ArrayList<>()).add(field);
        }, tableIds.toArray());
        return byTable;
    }

    private List<String> readJsonStringList(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        String json = rs.getString(column);
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("Failed to parse JSON list column {}: {}", column, e.getMessage());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        String json = rs.getString(column);
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse JSON map column {}: {}", column, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static List<String> readTextArrayColumn(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Array arr = rs.getArray(column);
        if (arr == null) {
            return Collections.emptyList();
        }
        Object[] raw = (Object[]) arr.getArray();
        if (raw == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(raw.length);
        for (Object o : raw) {
            if (o != null) {
                out.add(o.toString());
            }
        }
        return out;
    }

    private static Long readNullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object v = rs.getObject(column);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
