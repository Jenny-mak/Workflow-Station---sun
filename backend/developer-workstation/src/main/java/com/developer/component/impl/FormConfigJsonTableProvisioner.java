package com.developer.component.impl;

import com.developer.component.TableDesignComponent;
import com.developer.dto.FieldDefinitionRequest;
import com.developer.dto.TableDefinitionRequest;
import com.developer.entity.FieldDefinition;
import com.developer.entity.FormDefinition;
import com.developer.entity.FormTableBinding;
import com.developer.entity.TableDefinition;
import com.developer.enums.BindingLinkMode;
import com.developer.enums.BindingMode;
import com.developer.enums.BindingType;
import com.developer.enums.DataType;
import com.developer.enums.FormType;
import com.developer.enums.SubMode;
import com.developer.enums.TableType;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.FormTableBindingRepository;
import com.developer.repository.TableDefinitionRepository;
import com.developer.util.FormConfigJsonPasteBindingMapper;
import com.developer.util.FormCreateRuleToFieldMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * When pasting form configJson into a Function Unit that lacks matching tables/bindings,
 * create MAIN/SUB tables (and RELATED sys_users bindings) then return stale→new binding maps.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FormConfigJsonTableProvisioner {

    static final long SYS_USERS_RELATION_TABLE_ID = -1_000_000_001L;
    static final String SUB_FK_FIELD = "main_id";

    private final TableDesignComponent tableDesignComponent;
    private final TableDefinitionRepository tableDefinitionRepository;
    private final FormTableBindingRepository formTableBindingRepository;
    private final FormDefinitionRepository formDefinitionRepository;

    public record ProvisionResult(
            Map<Long, Long> bindingIdMapping,
            List<String> createdTableNames,
            boolean provisioned
    ) {
    }

    /**
     * Ensure the form has bindings covering foreign ids in {@code configJson}.
     * Creates missing MAIN/SUB tables when no suitable table exists on the FU.
     */
    public ProvisionResult provision(
            Long functionUnitId,
            FormDefinition form,
            Map<String, Object> configJson) {
        Map<Long, Long> bindingMap = new LinkedHashMap<>();
        List<String> created = new ArrayList<>();
        if (form == null || form.getId() == null || configJson == null) {
            return new ProvisionResult(bindingMap, created, false);
        }

        List<FormTableBinding> bindings = formTableBindingRepository.findByFormIdWithTable(form.getId());
        List<TableDefinition> tables =
                tableDefinitionRepository.findByFunctionUnitIdWithFields(functionUnitId);

        FormTableBinding primary = bindings.stream()
                .filter(b -> b.getBindingType() == BindingType.PRIMARY)
                .findFirst()
                .orElse(null);
        if (primary == null) {
            TableDefinition main = matchOrCreateMain(functionUnitId, form, configJson, tables, created);
            if (main != null) {
                primary = saveBinding(form, main, null, BindingType.PRIMARY,
                        primaryMode(form), null, BindingLinkMode.structuralFk, null, nextSort(bindings));
                bindings = formTableBindingRepository.findByFormIdWithTable(form.getId());
                if (form.getBoundTable() == null) {
                    form.setBoundTable(main);
                }
                formDefinitionRepository.save(form);
                tables = tableDefinitionRepository.findByFunctionUnitIdWithFields(functionUnitId);
            }
        }

        Set<Long> targetIds = bindings.stream()
                .map(FormTableBinding::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        FormConfigJsonPasteBindingMapper.MappingResult mapping =
                FormConfigJsonPasteBindingMapper.buildMapping(configJson, bindings, tableFields(tables));
        bindingMap.putAll(mapping.bindingIdMapping());

        Set<Long> stillForeign = new LinkedHashSet<>();
        for (Long id : collectAllReferencedBindingIds(configJson)) {
            if (!targetIds.contains(id) && !bindingMap.containsKey(id)) {
                stillForeign.add(id);
            }
        }

        int subIndex = 1;
        for (Long staleId : stillForeign) {
            String key = String.valueOf(staleId);
            final List<FormTableBinding> currentBindings = bindings;
            if (isRelationLike(configJson, key) || isLookupOnly(configJson, staleId)) {
                FormTableBinding related = currentBindings.stream()
                        .filter(b -> b.getBindingType() == BindingType.RELATED)
                        .filter(b -> Objects.equals(b.getRelationTableId(), SYS_USERS_RELATION_TABLE_ID))
                        .findFirst()
                        .orElseGet(() -> saveBinding(form, null, SYS_USERS_RELATION_TABLE_ID,
                                BindingType.RELATED, BindingMode.READONLY, null,
                                BindingLinkMode.structuralFk, null, nextSort(currentBindings)));
                bindingMap.put(staleId, related.getId());
                bindings = formTableBindingRepository.findByFormIdWithTable(form.getId());
                continue;
            }

            Set<String> fields = collectSubFields(configJson, key);
            TableDefinition sub = matchSubByFields(tables, fields);
            String fk;
            if (sub == null) {
                // Nothing local matches the pasted rule — materialise the sub-table (with its FK
                // column, declared as such) so the pasted form has something to bind to.
                sub = createSubTable(functionUnitId, form, fields, subIndex++, created, staleId);
                tables = tableDefinitionRepository.findByFunctionUnitIdWithFields(functionUnitId);
                fk = SUB_FK_FIELD;
            } else {
                fk = resolveFkField(sub);
                if (!hasField(sub, fk)) {
                    // A real table matched, but it declares no foreign key. This used to fall into
                    // createSubTable and quietly produce a SECOND, near-duplicate table alongside
                    // the one the user already has. Skip instead: the matched table is theirs, the
                    // missing FK is a Table Design gap only they can resolve, and duplicating it
                    // would leave two tables competing for the same rows.
                    log.warn("Skip SUB provision for stale {} on form {} — matched sub-table '{}' "
                            + "(id {}) declares no foreign key; mark the parent-referencing column "
                            + "in Table Design",
                            staleId, form.getId(), String.valueOf(sub.getTableName()), sub.getId());
                    continue;
                }
            }
            if (sub == null) {
                continue;
            }
            if (primary == null) {
                log.warn("Skip SUB provision for stale {} — form {} has no PRIMARY binding",
                        staleId, form.getId());
                continue;
            }
            final String fkFinal = fk;
            final TableDefinition subFinal = sub;
            FormTableBinding existingSub = currentBindings.stream()
                    .filter(b -> b.getBindingType() == BindingType.SUB)
                    .filter(b -> b.getTable() != null && Objects.equals(b.getTable().getId(), subFinal.getId()))
                    .findFirst()
                    .orElse(null);
            FormTableBinding subBinding = existingSub != null ? existingSub
                    : saveBinding(form, subFinal, null, BindingType.SUB, subMode(form),
                    fkFinal, BindingLinkMode.structuralFk, SubMode.FULL, nextSort(currentBindings));
            bindingMap.put(staleId, subBinding.getId());
            bindings = formTableBindingRepository.findByFormIdWithTable(form.getId());
        }

        boolean provisioned = !created.isEmpty() || !bindingMap.isEmpty();
        if (provisioned) {
            log.info("Provisioned form {} bindings: mapped={}, createdTables={}",
                    form.getId(), bindingMap, created);
        }
        return new ProvisionResult(bindingMap, created, provisioned);
    }

    private TableDefinition matchOrCreateMain(
            Long functionUnitId,
            FormDefinition form,
            Map<String, Object> configJson,
            List<TableDefinition> tables,
            List<String> created) {
        List<FieldDefinitionRequest> fields = FormCreateRuleToFieldMapper.fromRules(configJson.get("rule"));
        Set<String> names = fields.stream().map(FieldDefinitionRequest::getFieldName).collect(Collectors.toSet());
        // A DETAIL form renders one row of a sub-table — MAIN rows open the request detail page —
        // so its primary table is a SUB table. Never auto-create a MAIN table for one.
        if (form.getFormType() == FormType.DETAIL) {
            return tables.stream()
                    .filter(t -> t.getTableType() == TableType.SUB)
                    .max((a, b) -> Integer.compare(score(a, names), score(b, names)))
                    .filter(t -> score(t, names) > 0)
                    .orElse(null);
        }
        TableDefinition matched = tables.stream()
                .filter(t -> t.getTableType() == TableType.MAIN)
                .max((a, b) -> Integer.compare(score(a, names), score(b, names)))
                .filter(t -> score(t, names) > 0 || names.isEmpty())
                .orElse(tables.stream().filter(t -> t.getTableType() == TableType.MAIN).findFirst().orElse(null));
        if (matched != null) {
            return matched;
        }
        SourceTableHint hint = resolveSourcePrimaryHint(configJson);
        String preferred = preferredName(hint,
                FormCreateRuleToFieldMapper.sanitizeTableNamePart(form.getFormName()) + "_main");
        String tableName = uniqueTableName(preferred);
        String displayName = preferredDisplay(hint, form.getFormName() + " Main");
        TableDefinitionRequest req = TableDefinitionRequest.builder()
                .tableName(tableName)
                .tableDisplayName(displayName)
                .tableType(TableType.MAIN)
                .description("Auto-created from pasted form config")
                .fields(fields)
                .build();
        TableDefinition createdTable = tableDesignComponent.create(functionUnitId, req);
        created.add(tableName);
        return createdTable;
    }

    private TableDefinition createSubTable(
            Long functionUnitId,
            FormDefinition form,
            Set<String> fieldNames,
            int index,
            List<String> created,
            Long staleBindingId) {
        List<FieldDefinitionRequest> fields = FormCreateRuleToFieldMapper.fromFieldNames(fieldNames);
        // Ensure FK column exists for PROCESS/TASK SUB binding rules.
        boolean hasFk = fields.stream().anyMatch(f -> SUB_FK_FIELD.equals(f.getFieldName()));
        if (!hasFk) {
            fields.add(0, FieldDefinitionRequest.builder()
                    .fieldName(SUB_FK_FIELD)
                    .dataType(DataType.VARCHAR)
                    .length(64)
                    .nullable(true)
                    .displayName("Main Row Id")
                    .sortOrder(0)
                    // Mark it as the foreign key, not just create the column. `isForeignKey` is
                    // what the Form Designer shows as "Structural FK Fields" and what runtime MI
                    // classification reads to tell a participant-child sub-table from a shared one;
                    // a column created without it leaves the table looking like it has no parent
                    // reference at all. Measured in dev: 8 of 15 sub-tables are in that state, so
                    // the designer dialog shows them an empty FK row.
                    .isForeignKey(true)
                    .build());
            for (int i = 1; i < fields.size(); i++) {
                fields.get(i).setSortOrder(i);
            }
        }
        SourceTableHint hint = resolveSourceTableHint(staleBindingId);
        String preferred = preferredName(hint,
                FormCreateRuleToFieldMapper.sanitizeTableNamePart(form.getFormName()) + "_sub_" + index);
        String tableName = uniqueTableName(preferred);
        String displayName = preferredDisplay(hint, form.getFormName() + " Sub " + index);
        TableDefinitionRequest req = TableDefinitionRequest.builder()
                .tableName(tableName)
                .tableDisplayName(displayName)
                .tableType(TableType.SUB)
                .description("Auto-created from pasted form config")
                .fields(fields)
                .build();
        TableDefinition createdTable = tableDesignComponent.create(functionUnitId, req);
        created.add(tableName);
        return createdTable;
    }

    /**
     * Prefer source table_name from pasted binding ids; fall back when missing / invalid.
     */
    record SourceTableHint(String tableName, String tableDisplayName) {
    }

    private SourceTableHint resolveSourceTableHint(Long bindingId) {
        if (bindingId == null) {
            return null;
        }
        return formTableBindingRepository.findByIdWithTable(bindingId)
                .map(FormTableBinding::getTable)
                .filter(Objects::nonNull)
                .map(FormConfigJsonTableProvisioner::hintFrom)
                .orElse(null);
    }

    private SourceTableHint resolveSourcePrimaryHint(Map<String, Object> configJson) {
        for (Long bindingId : collectAllReferencedBindingIds(configJson)) {
            Optional<FormTableBinding> opt = formTableBindingRepository.findByIdWithTable(bindingId);
            if (opt.isEmpty()) {
                continue;
            }
            FormTableBinding binding = opt.get();
            if (binding.getBindingType() == BindingType.PRIMARY && binding.getTable() != null) {
                return hintFrom(binding.getTable());
            }
            if (binding.getForm() == null || binding.getForm().getId() == null) {
                continue;
            }
            SourceTableHint primary = formTableBindingRepository
                    .findByFormIdWithTable(binding.getForm().getId()).stream()
                    .filter(b -> b.getBindingType() == BindingType.PRIMARY)
                    .map(FormTableBinding::getTable)
                    .filter(Objects::nonNull)
                    .map(FormConfigJsonTableProvisioner::hintFrom)
                    .findFirst()
                    .orElse(null);
            if (primary != null) {
                return primary;
            }
        }
        return null;
    }

    private static SourceTableHint hintFrom(TableDefinition table) {
        return new SourceTableHint(table.getTableName(), table.getTableDisplayName());
    }

    private static String preferredName(SourceTableHint hint, String fallback) {
        if (hint != null && isValidTableIdentifier(hint.tableName())) {
            return hint.tableName();
        }
        return fallback;
    }

    private static String preferredDisplay(SourceTableHint hint, String fallback) {
        if (hint == null) {
            return fallback;
        }
        if (hint.tableDisplayName() != null && !hint.tableDisplayName().isBlank()) {
            return hint.tableDisplayName();
        }
        if (hint.tableName() != null && !hint.tableName().isBlank()) {
            return hint.tableName();
        }
        return fallback;
    }

    private static boolean isValidTableIdentifier(String name) {
        return name != null && name.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }

    /**
     * The column this existing sub-table uses to reference its parent.
     *
     * <p>Read from Table Design ({@code isForeignKey}) — the same metadata the Form Designer shows
     * as "Structural FK Fields". The {@code row_id} / {@code case_id} guesses that used to follow
     * were wrong on almost every table: neither name appears as a foreign key anywhere in dev.
     *
     * <p>{@link #SUB_FK_FIELD} remains the last resort only because the caller creates the table
     * (and that column, marked as the FK) when this returns something the table does not have —
     * there it is this code's own convention, not a guess about someone else's schema.
     */
    private static String resolveFkField(TableDefinition sub) {
        if (sub != null && sub.getFieldDefinitions() != null) {
            for (FieldDefinition field : sub.getFieldDefinitions()) {
                if (Boolean.TRUE.equals(field.getIsForeignKey())
                        && field.getFieldName() != null && !field.getFieldName().isBlank()) {
                    return field.getFieldName();
                }
            }
        }
        return SUB_FK_FIELD;
    }

    private static boolean hasField(TableDefinition table, String fieldName) {
        return table.getFieldDefinitions() != null && table.getFieldDefinitions().stream()
                .anyMatch(f -> fieldName.equals(f.getFieldName()));
    }

    private String uniqueTableName(String preferred) {
        String candidate = preferred;
        int n = 2;
        while (!tableDesignComponent.isTableNameAvailable(candidate, null)) {
            candidate = preferred + "_" + n++;
            if (n > 100) {
                candidate = preferred + "_" + System.currentTimeMillis();
                break;
            }
        }
        return candidate;
    }

    private static Map<Long, Set<String>> tableFields(List<TableDefinition> tables) {
        Map<Long, Set<String>> out = new LinkedHashMap<>();
        for (TableDefinition t : tables) {
            if (t.getId() == null || t.getFieldDefinitions() == null) {
                continue;
            }
            out.put(t.getId(), t.getFieldDefinitions().stream()
                    .map(f -> f.getFieldName())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(HashSet::new)));
        }
        return out;
    }

    private static int score(TableDefinition table, Set<String> names) {
        if (table.getFieldDefinitions() == null || names.isEmpty()) {
            return 0;
        }
        Set<String> tableFields = table.getFieldDefinitions().stream()
                .map(f -> f.getFieldName())
                .collect(Collectors.toSet());
        return (int) names.stream().filter(tableFields::contains).count();
    }

    private static TableDefinition matchSubByFields(List<TableDefinition> tables, Set<String> fields) {
        TableDefinition best = null;
        int bestScore = 0;
        for (TableDefinition t : tables) {
            if (t.getTableType() != TableType.SUB) {
                continue;
            }
            int s = score(t, fields);
            if (s > bestScore) {
                bestScore = s;
                best = t;
            }
        }
        return bestScore > 0 ? best : null;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> collectSubFields(Map<String, Object> config, String key) {
        Set<String> fields = new LinkedHashSet<>();
        Object views = config.get("subListViews");
        if (views instanceof Map<?, ?> map && map.get(key) instanceof Map<?, ?> entry) {
            Object columns = ((Map<String, Object>) entry).get("columns");
            if (columns instanceof List<?> list) {
                for (Object col : list) {
                    if (col instanceof Map<?, ?> c && c.get("fieldName") instanceof String name) {
                        fields.add(name);
                    }
                }
            }
        }
        if (fields.isEmpty()) {
            Object subForms = config.get("subForms");
            if (subForms instanceof Map<?, ?> map && map.get(key) instanceof Map<?, ?> entry) {
                Object rule = ((Map<String, Object>) entry).get("rule");
                FormCreateRuleToFieldMapper.fromRules(rule).forEach(f -> fields.add(f.getFieldName()));
            }
        }
        fields.removeIf(n -> !FormCreateRuleToFieldMapper.isProvisionableFieldName(n));
        return fields;
    }

    private static Set<Long> collectAllReferencedBindingIds(Map<String, Object> config) {
        return FormConfigJsonPasteBindingMapper.collectReferencedBindingIds(config);
    }

    private static boolean isRelationLike(Map<String, Object> config, String key) {
        Object relationViews = config.get("relationViews");
        return relationViews instanceof Map<?, ?> map && map.containsKey(key);
    }

    private static boolean isLookupOnly(Map<String, Object> config, Long staleId) {
        String key = String.valueOf(staleId);
        if (isRelationLike(config, key)) {
            return true;
        }
        Object subForms = config.get("subForms");
        if (subForms instanceof Map<?, ?> map && map.containsKey(key)) {
            return false;
        }
        Object subListViews = config.get("subListViews");
        if (subListViews instanceof Map<?, ?> map && map.containsKey(key)) {
            return false;
        }
        return !ruleHasSubTable(config.get("rule"), key);
    }

    @SuppressWarnings("unchecked")
    private static boolean ruleHasSubTable(Object ruleNode, String key) {
        if (ruleNode instanceof List<?> list) {
            return list.stream().anyMatch(n -> ruleHasSubTable(n, key));
        }
        if (!(ruleNode instanceof Map<?, ?> raw)) {
            return false;
        }
        Map<String, Object> node = (Map<String, Object>) raw;
        if ("subTable".equals(node.get("type"))) {
            Object bid = node.get("_bindingId");
            if (bid == null && node.get("props") instanceof Map<?, ?> props) {
                bid = ((Map<String, Object>) props).get("_bindingId");
            }
            if (key.equals(String.valueOf(bid))) {
                return true;
            }
        }
        if (node.get("children") instanceof List<?> children) {
            return children.stream().anyMatch(c -> ruleHasSubTable(c, key));
        }
        return false;
    }

    private static BindingMode primaryMode(FormDefinition form) {
        return form.getFormType() == FormType.PROCESS ? BindingMode.EDITABLE : BindingMode.READONLY;
    }

    private static BindingMode subMode(FormDefinition form) {
        return form.getFormType() == FormType.PROCESS ? BindingMode.EDITABLE : BindingMode.READONLY;
    }

    private static int nextSort(List<FormTableBinding> bindings) {
        return bindings == null ? 0 : bindings.size();
    }

    private FormTableBinding saveBinding(
            FormDefinition form,
            TableDefinition table,
            Long relationTableId,
            BindingType bindingType,
            BindingMode bindingMode,
            String foreignKeyField,
            BindingLinkMode linkMode,
            SubMode subMode,
            int sortOrder) {
        FormTableBinding binding = FormTableBinding.builder()
                .form(form)
                .table(table)
                .relationTableId(relationTableId)
                .bindingType(bindingType)
                .bindingMode(bindingMode)
                .foreignKeyField(foreignKeyField)
                .bindingLinkMode(linkMode)
                .sortOrder(sortOrder)
                .subMode(subMode)
                .build();
        return formTableBindingRepository.save(binding);
    }
}
