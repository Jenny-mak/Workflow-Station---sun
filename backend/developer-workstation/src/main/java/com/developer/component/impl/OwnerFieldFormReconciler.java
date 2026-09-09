package com.developer.component.impl;

import com.developer.entity.FieldDefinition;
import com.developer.entity.FormDefinition;
import com.developer.entity.FormTableBinding;
import com.developer.entity.TableDefinition;
import com.developer.enums.BindingType;
import com.developer.enums.DataType;
import com.developer.exception.DeveloperBusinessException;
import com.developer.repository.FieldDefinitionRepository;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.FormTableBindingRepository;
import com.developer.repository.TableDefinitionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.audit.SystemAuditFields;
import com.platform.common.i18n.I18nService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Form-save reconciliation for Owner fields ({@code type:"owner"}, see
 * {@code docs/design/owner-field-component.md} §3.4/§4.1/§5.2).
 *
 * <p>Owner is a control type on an existing VARCHAR column. Multiple Owners per
 * table/form are allowed. This reconciler never inserts {@code dw_field_definitions}.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OwnerFieldFormReconciler {

    static final String OWNER_TYPE = "owner";
    static final String SOURCE_CREATOR = "CREATOR";
    static final String SOURCE_CASE_HANDLER = "CASE_HANDLER";
    static final String SOURCE_CURRENT_ASSIGNEE = "CURRENT_ASSIGNEE";

    private final FormDefinitionRepository formDefinitionRepository;
    private final FormTableBindingRepository formTableBindingRepository;
    private final TableDefinitionRepository tableDefinitionRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final ObjectMapper objectMapper;
    private final I18nService i18nService;

    /** One owner declaration extracted from a rule tree. */
    record OwnerDeclaration(String field, String title, String source) {
    }

    /**
     * Validates owner declarations in {@code configJson}. No-op when the config
     * declares no owner field. Never provisions columns.
     *
     * @param functionUnitId owning Function Unit id
     * @param form           the form being saved (id may be {@code null} on create)
     * @param configJson     the config about to be persisted on the form
     * @throws DeveloperBusinessException on any owner rule violation (save must fail)
     */
    public void reconcile(Long functionUnitId, FormDefinition form, Map<String, Object> configJson) {
        Map<Long, List<OwnerDeclaration>> byTable = extractDeclarationsByTable(form, configJson, true);
        if (byTable.isEmpty()) {
            return;
        }
        List<FormDefinition> allForms = formDefinitionRepository.findByFunctionUnitIdWithBindings(functionUnitId);
        for (Map.Entry<Long, List<OwnerDeclaration>> entry : byTable.entrySet()) {
            Long tableId = entry.getKey();
            if (tableDefinitionRepository.findById(tableId).isEmpty()) {
                throw ownerError("OWNER_TABLE_UNBOUND", "form.owner.unbound",
                        entry.getValue().get(0).field());
            }
            List<FieldDefinition> columns =
                    fieldDefinitionRepository.findByTableDefinitionIdOrderBySortOrderAsc(tableId);
            for (OwnerDeclaration decl : entry.getValue()) {
                validateColumn(decl, columns);
            }
            validateAgainstStoredForms(allForms, form, tableId, entry.getValue());
        }
    }

    /**
     * Extracts owner declarations of one form config, keyed by target table id.
     * Main-canvas owners resolve to the PRIMARY binding table (or the bound table);
     * sub-form owners resolve to the SUB binding's table.
     *
     * @param strict when true, unresolvable targets / duplicate fields throw; when false
     *               (scanning stored configs of other forms) they are skipped
     */
    private Map<Long, List<OwnerDeclaration>> extractDeclarationsByTable(
            FormDefinition form, Map<String, Object> configJson, boolean strict) {
        Map<Long, List<OwnerDeclaration>> byTable = new LinkedHashMap<>();
        if (configJson == null) {
            return byTable;
        }
        List<OwnerDeclaration> mainOwners = collectOwners(configJson.get("rule"), strict);
        if (!mainOwners.isEmpty()) {
            Long mainTableId = resolveMainTableId(form);
            if (mainTableId == null) {
                if (strict) {
                    throw ownerError("OWNER_TABLE_UNBOUND", "form.owner.unbound", mainOwners.get(0).field());
                }
            } else {
                putOwners(byTable, mainTableId, mainOwners, strict);
            }
        }
        if (configJson.get("subForms") instanceof Map<?, ?> subForms) {
            extractSubFormOwners(subForms, byTable, strict);
        }
        return byTable;
    }

    private void extractSubFormOwners(Map<?, ?> subForms,
                                      Map<Long, List<OwnerDeclaration>> byTable, boolean strict) {
        for (Map.Entry<?, ?> sub : subForms.entrySet()) {
            if (!(sub.getValue() instanceof Map<?, ?> subEntry)) {
                continue;
            }
            List<OwnerDeclaration> subOwners = collectOwners(subEntry.get("rule"), strict);
            if (subOwners.isEmpty()) {
                continue;
            }
            Long bindingId = parseLongOrNull(String.valueOf(sub.getKey()));
            Long tableId = bindingId == null ? null : formTableBindingRepository.findByIdWithTable(bindingId)
                    .map(FormTableBinding::getTable)
                    .filter(Objects::nonNull)
                    .map(TableDefinition::getId)
                    .orElse(null);
            if (tableId == null) {
                if (strict) {
                    throw ownerError("OWNER_TABLE_UNBOUND", "form.owner.unbound", subOwners.get(0).field());
                }
                continue;
            }
            putOwners(byTable, tableId, subOwners, strict);
        }
    }

    private void putOwners(Map<Long, List<OwnerDeclaration>> byTable, Long tableId,
                           List<OwnerDeclaration> owners, boolean strict) {
        List<OwnerDeclaration> existing = byTable.computeIfAbsent(tableId, k -> new ArrayList<>());
        for (OwnerDeclaration decl : owners) {
            boolean duplicateField = existing.stream().anyMatch(e -> e.field().equals(decl.field()));
            if (duplicateField && strict) {
                throw ownerError("OWNER_DUPLICATE_IN_FORM", "form.owner.duplicate", decl.field());
            }
            if (!duplicateField) {
                existing.add(decl);
            }
        }
    }

    private Long resolveMainTableId(FormDefinition form) {
        if (form == null) {
            return null;
        }
        if (form.getId() != null) {
            Long fromPrimary = formTableBindingRepository.findByFormIdWithTable(form.getId()).stream()
                    .filter(b -> b.getBindingType() == BindingType.PRIMARY)
                    .map(FormTableBinding::getTable)
                    .filter(Objects::nonNull)
                    .map(TableDefinition::getId)
                    .findFirst()
                    .orElse(null);
            if (fromPrimary != null) {
                return fromPrimary;
            }
        }
        return form.getBoundTable() != null ? form.getBoundTable().getId() : null;
    }

    private void validateAgainstStoredForms(
            List<FormDefinition> allForms, FormDefinition currentForm, Long tableId,
            List<OwnerDeclaration> decls) {
        Map<String, String> sourceByField = new LinkedHashMap<>();
        for (OwnerDeclaration decl : decls) {
            sourceByField.put(decl.field(), decl.source());
        }
        for (FormDefinition other : allForms) {
            boolean isCurrentForm = currentForm.getId() != null
                    && Objects.equals(other.getId(), currentForm.getId());
            if (isCurrentForm) {
                continue;
            }
            Map<Long, List<OwnerDeclaration>> stored = extractDeclarationsByTable(other, other.getConfigJson(), false);
            List<OwnerDeclaration> otherDecls = stored.getOrDefault(tableId, List.of());
            for (OwnerDeclaration otherDecl : otherDecls) {
                String currentSource = sourceByField.get(otherDecl.field());
                if (currentSource != null && !currentSource.equals(otherDecl.source())) {
                    throw ownerError("OWNER_CROSS_FORM_CONFLICT", "form.owner.cross_form_conflict",
                            otherDecl.field());
                }
            }
        }
    }

    private void validateColumn(OwnerDeclaration decl, List<FieldDefinition> columns) {
        FieldDefinition col = columns.stream()
                .filter(f -> decl.field().equals(f.getFieldName()))
                .findFirst()
                .orElse(null);
        if (col == null) {
            throw ownerError("OWNER_COLUMN_MISSING", "form.owner.column_missing", decl.field());
        }
        if (col.getDataType() != DataType.VARCHAR
                || Boolean.TRUE.equals(col.getIsPrimaryKey())
                || Boolean.TRUE.equals(col.getIsComputed())
                || SystemAuditFields.isAuditField(col.getFieldName())) {
            throw ownerError("OWNER_COLUMN_INVALID", "form.owner.column_invalid", decl.field());
        }
    }

    /** Walks a form-create rule tree and collects {@code type:"owner"} declarations. */
    private List<OwnerDeclaration> collectOwners(Object ruleNode, boolean strict) {
        List<OwnerDeclaration> owners = new ArrayList<>();
        walkOwners(ruleNode, owners, strict);
        return owners;
    }

    @SuppressWarnings("unchecked")
    private void walkOwners(Object ruleNode, List<OwnerDeclaration> owners, boolean strict) {
        if (ruleNode instanceof List<?> list) {
            list.forEach(n -> walkOwners(n, owners, strict));
            return;
        }
        if (!(ruleNode instanceof Map<?, ?> raw)) {
            return;
        }
        Map<String, Object> node = (Map<String, Object>) raw;
        if (OWNER_TYPE.equals(node.get("type")) && node.get("field") instanceof String field && !field.isBlank()) {
            Map<String, Object> props = node.get("props") instanceof Map<?, ?> p
                    ? (Map<String, Object>) p
                    : Map.of();
            String title = node.get("title") instanceof String t ? t : null;
            owners.add(new OwnerDeclaration(field, title, parseSource(props.get("ownerConfig"), field, strict)));
        }
        if (node.get("children") instanceof List<?> children) {
            children.forEach(c -> walkOwners(c, owners, strict));
        }
    }

    /**
     * {@code ownerConfig} contract (§4.1): JSON {@code {"source":"CREATOR"|"CASE_HANDLER"}}.
     * Legacy {@code CURRENT_ASSIGNEE} is accepted as {@code CASE_HANDLER}.
     * Missing source (including leftover {@code allowGroup}-only configs) defaults to CREATOR.
     * Invalid JSON / unknown source fails the save.
     */
    @SuppressWarnings("unchecked")
    private String parseSource(Object ownerConfig, String field, boolean strict) {
        if (ownerConfig == null) {
            return SOURCE_CREATOR;
        }
        Map<String, Object> parsed = parseConfigMap(ownerConfig, field, strict);
        if (parsed == null) {
            return SOURCE_CREATOR;
        }
        Object source = parsed.get("source");
        if (source == null) {
            return SOURCE_CREATOR;
        }
        if (!(source instanceof String s) || s.isBlank()) {
            if (strict) {
                throw ownerError("OWNER_CONFIG_INVALID", "form.owner.config_invalid", field);
            }
            return SOURCE_CREATOR;
        }
        String normalized = s.trim().toUpperCase(Locale.ROOT);
        if (SOURCE_CURRENT_ASSIGNEE.equals(normalized)) {
            return SOURCE_CASE_HANDLER;
        }
        if (SOURCE_CREATOR.equals(normalized) || SOURCE_CASE_HANDLER.equals(normalized)) {
            return normalized;
        }
        if (strict) {
            throw ownerError("OWNER_CONFIG_INVALID", "form.owner.config_invalid", field);
        }
        return SOURCE_CREATOR;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfigMap(Object ownerConfig, String field, boolean strict) {
        if (ownerConfig instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (ownerConfig instanceof String s) {
            if (s.isBlank()) {
                return Map.of();
            }
            try {
                return objectMapper.readValue(s, Map.class);
            } catch (Exception e) {
                if (strict) {
                    throw ownerError("OWNER_CONFIG_INVALID", "form.owner.config_invalid", field);
                }
                return null;
            }
        }
        if (strict) {
            throw ownerError("OWNER_CONFIG_INVALID", "form.owner.config_invalid", field);
        }
        return null;
    }

    private static Long parseLongOrNull(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private DeveloperBusinessException ownerError(String code, String messageKey, String arg) {
        return new DeveloperBusinessException(code,
                i18nService.getMessage(messageKey, arg),
                i18nService.getMessage("form.owner.fix_suggestion"));
    }
}
