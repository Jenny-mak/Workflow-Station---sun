package com.portal.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.audit.SystemAuditFields;
import com.platform.common.jdbc.SubTableRowIdentity;
import com.platform.common.subtable.SubTableStoreKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the audit payload from the immutable user submission rather than from
 * variables after
 * PK/FK, MI, workflow and audit-field enrichment. Only fields editable in the
 * current form survive.
 * Platform audit columns ({@link SystemAuditFields}) are never attributed to the user.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangeHistorySubmissionFilter {
    /** Row-identity field names preserved through any field-level filtering so row matching on
     *  subsequent saves keeps working even when the identity field itself is not user-editable. */
    public static final List<String> ROW_IDENTITY_FIELDS = SubTableRowIdentity.IDENTITY_FIELDS;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public Map<String, Object> copyPayload(Map<String, Object> source) {
        if (source == null || source.isEmpty())
            return new LinkedHashMap<>();
        return objectMapper.convertValue(source, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    public Map<String, Object> filterProcessSubmission(String functionUnitCode,
            Map<String, Object> submitted,
            Map<String, Object> enriched) {
        return retainUserEditableSubmission(submitted, enriched,
                loadProcessFormDefinition(functionUnitCode));
    }

    public Map<String, Object> filterTaskSubmission(String processInstanceId,
            String stageId,
            Map<String, Object> submitted,
            Map<String, Object> enriched) {
        return retainUserEditableSubmission(submitted, enriched,
                loadTaskFormDefinition(processInstanceId, stageId));
    }

    public Object filterProcessSubTableBaseline(String functionUnitCode, Object storedSubTables) {
        return filterSubTableBaseline(storedSubTables, loadProcessFormDefinition(functionUnitCode));
    }

    public Object filterTaskSubTableBaseline(String stageId, Object storedSubTables) {
        return filterTaskSubTableBaseline(null, stageId, storedSubTables);
    }

    public Object filterTaskSubTableBaseline(String processInstanceId,
            String stageId,
            Object storedSubTables) {
        return filterSubTableBaseline(storedSubTables,
                loadTaskFormDefinition(processInstanceId, stageId));
    }

    private Object filterSubTableBaseline(Object storedSubTables, Map<String, Object> formDefinition) {
        if (!(storedSubTables instanceof Map<?, ?> tables))
            return null;
        Map<String, Object> wrapper = Map.of("__subTables__", ChangeHistoryFilterMaps.castMap(tables));
        return retainUserEditableSubmission(wrapper, wrapper, formDefinition).get("__subTables__");
    }

    /**
     * Resolves which fields are explicitly marked {@code READONLY} for each sub-table binding
     * (composite-key {@code bindingId:field} permission entries), keyed by numeric bindingId (as
     * it appears in {@code configJson.subForms} and in the {@code __subTables__}
     * numeric-bindingId alias) — bindings absent from the result have no field-level permission
     * configured at all.
     *
     * <p>Deny-list, not allow-list: the Form Designer's field-permission editor
     * ({@code useFormSave.ts}'s {@code fieldPermissions?.[key] || 'EDITABLE'}) only ever persists
     * an entry when a field was explicitly toggled away from the default, and the default is
     * {@code EDITABLE} — a field with no entry at all is exactly as editable as an explicit
     * {@code EDITABLE} entry. Treating "no explicit EDITABLE entry" as "not editable" (the
     * allow-list this method used to build) silently strips every field from a binding's rows the
     * moment ANY single field on that binding gets an explicit {@code READONLY} entry (e.g. a
     * designer marking just {@code bu_code}/{@code role_code} read-only also wiped {@code name},
     * {@code assignee}, … from every save — #1524-class regression, confirmed via a captured
     * submit payload that had the correct edited row going in and thin identity-only rows coming
     * out the other side).
     *
     * <p>Used by {@code TaskFormComponent#submitTaskForm} to enforce sub-table field permissions
     * on the numeric-bindingId-keyed slice actually persisted into process variables, in place,
     * preserving values (e.g. allocated primary keys) already computed by upstream enrichment on
     * the same map. This intentionally does not consider design-time
     * readonly/disabled/hidden rule flags (those are a separate, unrelated axis that
     * {@link #retainUserEditableSubmission}'s audit-trail computation already applies, but which
     * main-form submit-time enforcement has never applied either).
     */
    public Map<String, Set<String>> resolveSubFormFieldPermissionsByBinding(String processInstanceId,
            String stageId) {
        Map<String, Object> formDefinition = loadTaskFormDefinition(processInstanceId, stageId);
        if (formDefinition == null || formDefinition.isEmpty()) {
            return Map.of();
        }
        Map<String, String> permissions = stringMapValue(formDefinition.get("fieldPermissions"));
        if (permissions.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> result = new HashMap<>();
        for (Map.Entry<String, String> entry : permissions.entrySet()) {
            int sep = entry.getKey().indexOf(':');
            if (sep <= 0) {
                continue;
            }
            String bindingId = entry.getKey().substring(0, sep);
            String field = entry.getKey().substring(sep + 1);
            if (!"EDITABLE".equalsIgnoreCase(entry.getValue())) {
                result.computeIfAbsent(bindingId, ignored -> new HashSet<>()).add(field);
            }
        }
        return result;
    }

    public Map<String, Object> retainUserEditableSubmission(Map<String, Object> submitted,
            Map<String, Object> enriched,
            Map<String, Object> formDefinition) {
        if (submitted == null || submitted.isEmpty() || formDefinition == null || formDefinition.isEmpty()) {
            return Map.of();
        }
        if (truthy(formDefinition.get("readOnly")))
            return Map.of();
        Map<String, Object> config = mapValue(formDefinition.get("configJson"));
        if (config.isEmpty())
            return Map.of();
        Map<String, String> permissions = stringMapValue(formDefinition.get("fieldPermissions"));
        Map<String, String> topLevelLookupDisplay = new HashMap<>();
        Set<String> topLevelEditable = collectEditableFields(config, permissions, topLevelLookupDisplay);
        Map<String, Map<String, String>> lookupDisplayByBinding = new HashMap<>();
        Map<String, Set<String>> editableByBinding = collectEditableSubFormFields(
                config, permissions, lookupDisplayByBinding);
        ChangeHistoryBindingAliases aliases = resolveBindingContract(
                formDefinition, topLevelEditable, editableByBinding);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : topLevelEditable) {
            if (!submitted.containsKey(field))
                continue;
            result.put(field, ChangeHistoryLookupAuditValues.visibleAuditValue(
                    submitted.get(field), topLevelLookupDisplay.get(field)));
        }
        Object submittedSubTables = submitted.get("__subTables__");
        if (submittedSubTables instanceof Map<?, ?> rawTables) {
            Map<?, ?> enrichedTables = enriched != null && enriched.get("__subTables__") instanceof Map<?, ?> map
                    ? map
                    : Map.of();
            Map<String, Object> filteredTables = ChangeHistorySubTableAuditWalk.filter(
                    rawTables, enrichedTables, editableByBinding, aliases, lookupDisplayByBinding);
            if (!filteredTables.isEmpty())
                result.put("__subTables__", filteredTables);
        }
        return copyPayload(result);
    }

    private ChangeHistoryBindingAliases resolveBindingContract(Map<String, Object> formDefinition,
            Set<String> topLevelEditable,
            Map<String, Set<String>> editableByBinding) {
        Map<String, String> aliasToBinding = new HashMap<>();
        Map<String, String> bindingToHistoryName = new HashMap<>();
        Map<String, Integer> aliasPriorities = new HashMap<>();
        editableByBinding.keySet().forEach(id -> {
            aliasToBinding.put(normalizeAlias(id), id);
            aliasPriorities.put(normalizeAlias(id), 0);
        });
        String formId = stringValue(formDefinition.get("formId"));
        if (formId == null)
            return new ChangeHistoryBindingAliases(aliasToBinding, bindingToHistoryName, aliasPriorities);
        try {
            List<Map<String, Object>> bindings = jdbcTemplate.queryForList(
                    """
                            SELECT binding.id, binding.binding_type, binding.binding_mode,
                                COALESCE(td.table_name, rt.table_name) AS table_name,
                                COALESCE(td.table_display_name, rt.display_name) AS table_display_name,
                                sibling.id AS sibling_id,
                                binding.relation_table_id
                            FROM dw_form_definitions form
                            INNER JOIN dw_form_table_bindings binding ON binding.form_id = form.id
                            LEFT JOIN dw_table_definitions td ON td.id = binding.table_id
                            LEFT JOIN rt_table_definitions rt ON rt.id = binding.relation_table_id
                            LEFT JOIN dw_form_definitions sibling_form
                                ON sibling_form.function_unit_id = form.function_unit_id
                            LEFT JOIN dw_form_table_bindings sibling ON sibling.form_id = sibling_form.id
                                AND ((binding.table_id IS NOT NULL AND sibling.table_id = binding.table_id)
                                    OR (binding.relation_table_id > 0
                                        AND sibling.relation_table_id = binding.relation_table_id))
                            WHERE form.id = ?
                            """, Long.valueOf(formId));
            for (Map<String, Object> binding : bindings) {
                String type = stringValue(binding.get("binding_type"));
                String mode = stringValue(binding.get("binding_mode"));
                if ("PRIMARY".equalsIgnoreCase(type) && !"EDITABLE".equalsIgnoreCase(mode)) {
                    topLevelEditable.clear();
                }
                if (!"PRIMARY".equalsIgnoreCase(type) && !"EDITABLE".equalsIgnoreCase(mode)) {
                    String bindingId = stringValue(binding.get("id"));
                    if (bindingId != null)
                        editableByBinding.remove(bindingId);
                }
            }
            for (Map<String, Object> binding : bindings) {
                String bindingId = stringValue(binding.get("id"));
                if (bindingId == null || !editableByBinding.containsKey(bindingId))
                    continue;
                registerAlias(aliasToBinding, aliasPriorities, bindingId, binding.get("table_name"), 1);
                registerAlias(aliasToBinding, aliasPriorities, bindingId, binding.get("table_display_name"), 2);
                registerAlias(aliasToBinding, aliasPriorities, bindingId, binding.get("sibling_id"), 3);
                String tableName = stringValue(binding.get("table_name"));
                if (tableName != null)
                    bindingToHistoryName.putIfAbsent(bindingId, tableName);
                // Portal persists __subTables__ under SubTableStoreKeys (dw:/rt:), not binding
                // ids. Without this alias the audit payload drops every user-edited sub-table.
                registerAlias(aliasToBinding, aliasPriorities, bindingId,
                        SubTableStoreKeys.storeKey(tableName, tableName,
                                isRelationTableBinding(binding.get("relation_table_id"))),
                        1);
            }
        } catch (RuntimeException ex) {
            // Form binding metadata is authoritative. If it cannot be read, fail closed
            // rather
            // than attributing potentially read-only values to the user.
            log.warn("Could not resolve form binding modes for change history form {}: {}",
                    formId, ex.getMessage());
            topLevelEditable.clear();
            editableByBinding.clear();
        }
        return new ChangeHistoryBindingAliases(aliasToBinding, bindingToHistoryName, aliasPriorities);
    }

    private Set<String> collectEditableFields(Map<String, Object> config,
            Map<String, String> permissions,
            Map<String, String> lookupDisplayByField) {
        Set<String> fields = new HashSet<>();
        collectEditableRules(config.get("rule"), permissions, fields, true, null, lookupDisplayByField);
        return fields;
    }

    private Map<String, Set<String>> collectEditableSubFormFields(Map<String, Object> config,
            Map<String, String> permissions,
            Map<String, Map<String, String>> lookupDisplayByBinding) {
        Map<String, Set<String>> result = new HashMap<>();
        Object subForms = config.get("subForms");
        if (!(subForms instanceof Map<?, ?> forms))
            return result;
        for (Map.Entry<?, ?> entry : forms.entrySet()) {
            String bindingId = stringValue(entry.getKey());
            if (bindingId == null || !(entry.getValue() instanceof Map<?, ?> rawConfig))
                continue;
            Set<String> fields = new HashSet<>();
            Map<String, String> lookupDisplay = new HashMap<>();
            collectEditableRules(rawConfig.get("rule"), permissions, fields, true, bindingId, lookupDisplay);
            result.put(bindingId, fields);
            if (!lookupDisplay.isEmpty())
                lookupDisplayByBinding.put(bindingId, lookupDisplay);
        }
        return result;
    }

    private void collectEditableRules(Object rulesValue,
            Map<String, String> permissions,
            Set<String> fields,
            boolean ancestorEditable,
            String bindingId,
            Map<String, String> lookupDisplayByField) {
        if (!(rulesValue instanceof List<?> rules))
            return;
        for (Object ruleValue : rules) {
            if (!(ruleValue instanceof Map<?, ?> rule))
                continue;
            String field = stringValue(rule.get("field"));
            boolean effectiveEditable = ancestorEditable && isRuleContainerEditable(rule);
            if (field != null && effectiveEditable && isEditableRule(field, rule, permissions, bindingId)) {
                fields.add(field);
                String displayField = ChangeHistoryLookupAuditValues.selectedDisplayField(rule, objectMapper);
                if (displayField != null)
                    lookupDisplayByField.putIfAbsent(field, displayField);
            }
            collectEditableRules(rule.get("children"), permissions, fields, effectiveEditable, bindingId,
                    lookupDisplayByField);
        }
    }

    private void collectConfiguredFields(Object rulesValue, Set<String> fields) {
        if (!(rulesValue instanceof List<?> rules))
            return;
        for (Object ruleValue : rules) {
            if (!(ruleValue instanceof Map<?, ?> rule))
                continue;
            String field = stringValue(rule.get("field"));
            if (field != null)
                fields.add(field);
            collectConfiguredFields(rule.get("children"), fields);
        }
    }

    private boolean isRuleContainerEditable(Map<?, ?> rule) {
        if (truthy(rule.get("readonly")) || truthy(rule.get("disabled")) || truthy(rule.get("hidden"))) {
            return false;
        }
        if (Boolean.FALSE.equals(rule.get("display")))
            return false;
        Object propsValue = rule.get("props");
        return !(propsValue instanceof Map<?, ?> props)
                || (!truthy(props.get("readonly")) && !truthy(props.get("disabled"))
                        && !truthy(props.get("hidden")));
    }

    /**
     * @param bindingId when non-null, this is a sub-form field: permission is looked up under
     *                  the composite {@code "${bindingId}:${field}"} key, namespaced separately
     *                  from the main form's bare-{@code field} keys so same-named fields on
     *                  different tables cannot collide (see field-level sub-table permission
     *                  extension). {@code null} means the main-form field, preserving the
     *                  original bare-key lookup exactly.
     */
    private boolean isEditableRule(String field,
            Map<?, ?> rule,
            Map<String, String> permissions,
            String bindingId) {
        if (field.startsWith("__"))
            return false;
        // Platform-managed audit columns are never user edits (even if a form rule exists).
        if (SystemAuditFields.isAuditField(field))
            return false;
        String permission = permissions.get(bindingId != null ? bindingId + ":" + field : field);
        if (permission != null && !"EDITABLE".equalsIgnoreCase(permission))
            return false;
        return isRuleContainerEditable(rule);
    }

    private void registerAlias(Map<String, String> aliases, Map<String, Integer> priorities,
            String bindingId, Object value, int priority) {
        String alias = stringValue(value);
        if (alias == null)
            return;
        String normalized = normalizeAlias(alias);
        aliases.putIfAbsent(normalized, bindingId);
        priorities.putIfAbsent(normalized, priority);
    }

    /** TASK-scene PROCESS form only — REQUEST (My Request) clones are display-only. */
    private Map<String, Object> loadProcessFormDefinition(String functionUnitCode) {
        if (functionUnitCode == null || functionUnitCode.isBlank())
            return Map.of();
        return queryFormDefinition(
                """
                        SELECT fd.id AS form_id, fd.config_json::text AS config_json,
                            fd.field_permissions::text AS field_permissions
                        FROM dw_form_definitions fd
                        INNER JOIN dw_function_units fu ON fu.id = fd.function_unit_id
                        WHERE fu.code = ? AND fd.form_type = 'PROCESS' AND fd.scene = 'TASK'
                        ORDER BY fd.id DESC LIMIT 1
                        """, functionUnitCode.trim());
    }

    /**
     * Form Change History uses for a task stage: BPMN {@code formId} first, then
     * {@code dw_form_stage_bindings}. Snapshot capture uses this same resolution.
     */
    public Map<String, Object> resolveTaskFormDefinition(String processInstanceId, String stageId) {
        return loadTaskFormDefinition(processInstanceId, stageId);
    }

    /**
     * Main-table field names to freeze in a completed-task snapshot: permission keys
     * plus every {@code field} on the form config (including readonly). Composite
     * {@code bindingId:field} keys stay out — those belong to {@code __subTables__}.
     */
    public Set<String> snapshotFieldKeys(String processInstanceId, String stageId) {
        return snapshotFieldKeys(loadTaskFormDefinition(processInstanceId, stageId));
    }

    Set<String> snapshotFieldKeys(Map<String, Object> formDefinition) {
        if (formDefinition == null || formDefinition.isEmpty())
            return Set.of();
        Set<String> keys = new LinkedHashSet<>();
        Map<String, String> permissions = stringMapValue(formDefinition.get("fieldPermissions"));
        for (String key : permissions.keySet()) {
            if (key != null && !key.contains(":"))
                keys.add(key);
        }
        Map<String, Object> config = mapValue(formDefinition.get("configJson"));
        collectConfiguredFields(config.get("rule"), keys);
        keys.removeIf(key -> key.startsWith("__") || SystemAuditFields.isAuditField(key));
        return keys;
    }

    private Map<String, Object> loadTaskFormDefinition(String processInstanceId, String stageId) {
        if (processInstanceId == null || processInstanceId.isBlank()
                || stageId == null || stageId.isBlank())
            return Map.of();
        Long bpmnFormId = ChangeHistoryBpmnFormResolver.resolve(
                jdbcTemplate, processInstanceId, stageId.trim());
        if (bpmnFormId != null) {
            Map<String, Object> deployedForm = queryFormDefinition(
                    """
                            SELECT fd.id AS form_id, fd.config_json::text AS config_json,
                            fd.field_permissions::text AS field_permissions,
                            false AS read_only
                            FROM dw_form_definitions fd
                            WHERE fd.id = ? AND fd.form_type IN ('TASK', 'PROCESS')
                            LIMIT 1
                            """, bpmnFormId);
            if (!deployedForm.isEmpty())
                return deployedForm;
        }
        Map<String, Object> boundForm = queryFormDefinition(
                """
                          SELECT fd.id AS form_id, fd.config_json::text AS config_json,
                              fd.field_permissions::text AS field_permissions,
                              sb.read_only
                        FROM up_process_instance pi
                        INNER JOIN dw_function_units fu
                            ON fu.code = COALESCE(NULLIF(pi.function_unit_code, ''), pi.process_definition_key)
                        INNER JOIN dw_form_definitions fd ON fd.function_unit_id = fu.id
                        INNER JOIN dw_form_stage_bindings sb ON sb.form_id = fd.id
                        WHERE pi.id = ? AND sb.stage_id = ? AND fd.form_type = 'TASK'
                        ORDER BY fd.id DESC LIMIT 1
                        """, processInstanceId.trim(), stageId.trim());
        return boundForm;
    }

    static Long resolveTaskFormId(String bpmnXml, String stageId) {
        return ChangeHistoryBpmnFormResolver.resolveTaskFormId(bpmnXml, stageId);
    }

    private Map<String, Object> queryFormDefinition(String sql, Object... parameters) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, parameters);
            if (rows.isEmpty())
                return Map.of();
            Map<String, Object> row = rows.get(0);
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("configJson", parseObject(row.get("config_json")));
            definition.put("fieldPermissions", parseStringMap(row.get("field_permissions")));
            definition.put("readOnly", truthy(row.get("read_only")));
            String formId = stringValue(row.get("form_id"));
            if (formId != null)
                definition.put("formId", formId);
            return definition;
        } catch (RuntimeException ex) {
            log.warn("Could not resolve form metadata for user change history: {}", ex.getMessage());
            return Map.of();
        }
    }

    private Map<String, Object> parseObject(Object raw) {
        if (raw instanceof Map<?, ?> map)
            return ChangeHistoryFilterMaps.castMap(map);
        if (raw == null || String.valueOf(raw).isBlank())
            return Map.of();
        try {
            return objectMapper.readValue(String.valueOf(raw), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private Map<String, String> parseStringMap(Object raw) {
        if (raw instanceof Map<?, ?> map)
            return stringMapValue(map);
        if (raw == null || String.valueOf(raw).isBlank())
            return Map.of();
        try {
            return objectMapper.readValue(String.valueOf(raw), new TypeReference<Map<String, String>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? ChangeHistoryFilterMaps.castMap(map) : Map.of();
    }

    private static Map<String, String> stringMapValue(Object value) {
        if (!(value instanceof Map<?, ?> map))
            return Map.of();
        Map<String, String> result = new HashMap<>();
        map.forEach((key, item) -> {
            if (key != null && item != null)
                result.put(String.valueOf(key), String.valueOf(item));
        });
        return result;
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String stringValue(Object value) {
        return ChangeHistoryFilterMaps.stringValue(value);
    }

    private static boolean isRelationTableBinding(Object relationTableId) {
        if (relationTableId instanceof Number number) {
            return number.longValue() > 0;
        }
        if (relationTableId == null) {
            return false;
        }
        try {
            return Long.parseLong(String.valueOf(relationTableId).trim()) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String normalizeAlias(String value) {
        return ChangeHistoryFilterMaps.normalizeAlias(value);
    }
}