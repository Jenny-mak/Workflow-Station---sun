package com.developer.component.impl;

import com.developer.entity.ActionDefinition;
import com.developer.entity.DecisionDefinition;
import com.developer.entity.EmailConnection;
import com.developer.entity.EmailMonitorRule;
import com.developer.entity.EmailTemplate;
import com.developer.entity.FieldDefinition;
import com.developer.entity.ForeignKey;
import com.developer.entity.FormDefinition;
import com.developer.entity.FormStageBinding;
import com.developer.entity.FormTableBinding;
import com.developer.entity.FunctionUnit;
import com.developer.entity.LinkFormComponent;
import com.developer.entity.SubTableViewConfig;
import com.developer.entity.SubTableViewField;
import com.developer.entity.TableDefinition;
import com.developer.entity.TableRelation;
import com.developer.dto.RequestIdConfig;
import com.developer.enums.ActionType;
import com.developer.enums.BindingLinkMode;
import com.developer.enums.BindingMode;
import com.developer.enums.BindingType;
import com.developer.enums.ConnectionType;
import com.developer.enums.EmailConnectionDirection;
import com.developer.enums.EmailMonitorActionType;
import com.developer.enums.OAuthProvider;
import com.developer.enums.DataType;
import com.developer.enums.FormScene;
import com.developer.enums.FormType;
import com.developer.enums.SubMode;
import com.developer.enums.TableType;
import com.developer.exception.DeveloperBusinessException;
import com.developer.exception.ResourceNotFoundException;
import com.developer.repository.ActionDefinitionRepository;
import com.developer.repository.DecisionDefinitionRepository;
import com.developer.repository.EmailConnectionRepository;
import com.developer.repository.EmailMonitorRuleRepository;
import com.developer.repository.EmailTemplateRepository;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.FormTableBindingRepository;
import com.developer.repository.LinkFormComponentRepository;
import com.developer.repository.SubTableViewConfigRepository;
import com.developer.repository.TableDefinitionRepository;
import com.developer.repository.TableRelationRepository;
import com.developer.util.FormConfigJsonBindingIdRewriter;
import com.developer.util.FormConfigJsonOrphanBindingRepair;
import com.developer.validation.DmnXmlParser;
import com.platform.common.mail.EmailConnectionPortability;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 功能单元导入写入协作类。
 * 负责把解析后的导入数据落库为实体：表/外键/表关系/表单壳/表单绑定/动作/决策，
 * 以及对应的反序列化辅助逻辑。行为与拆分前逐字一致。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FunctionUnitImportWriter {

    private final TableDefinitionRepository tableDefinitionRepository;
    private final FormDefinitionRepository formDefinitionRepository;
    private final ActionDefinitionRepository actionDefinitionRepository;
    private final DecisionDefinitionRepository decisionDefinitionRepository;
    private final EmailConnectionRepository emailConnectionRepository;
    private final EmailMonitorRuleRepository emailMonitorRuleRepository;
    private final EmailTemplateRepository emailTemplateRepository;
    private final FormTableBindingRepository formTableBindingRepository;
    private final LinkFormComponentRepository linkFormComponentRepository;
    private final TableRelationRepository tableRelationRepository;
    private final SubTableViewConfigRepository subTableViewConfigRepository;
    private final DmnXmlParser dmnXmlParser;
    private final ObjectMapper objectMapper;

    void recordSourceIdMapping(Object sourceIdObj, Long newId, Map<Long, Long> mapping) {
        if (sourceIdObj instanceof Number sourceId && newId != null) {
            mapping.put(sourceId.longValue(), newId);
        }
    }

    void recordConnectionUidMapping(Object sourceUidObj, String savedUid, Map<String, String> mapping) {
        if (savedUid == null || savedUid.isBlank() || sourceUidObj == null) {
            return;
        }
        String sourceUid = String.valueOf(sourceUidObj).trim();
        if (sourceUid.isEmpty()) {
            return;
        }
        mapping.put(sourceUid, savedUid);
    }

    TableDefinition importTable(FunctionUnit functionUnit, Map<String, Object> tableData) {
        TableDefinition table = TableDefinition.builder()
                .functionUnit(functionUnit)
                .tableName((String) tableData.get("tableName"))
                .tableType(TableType.valueOf((String) tableData.get("tableType")))
                .tableDisplayName((String) tableData.get("tableDisplayName"))
                .displayName((String) tableData.get("description"))
                .requestIdConfig(parseRequestIdConfig(tableData.get("requestIdConfig")))
                .build();
        table = tableDefinitionRepository.save(table);

        // Import field definitions
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) tableData.get("fields");
        if (fields != null) {
            for (int i = 0; i < fields.size(); i++) {
                Map<String, Object> fieldData = fields.get(i);
                Integer sortOrder = fieldData.get("sortOrder") instanceof Number number
                        ? number.intValue()
                        : i;
                Boolean nullable = fieldData.get("nullable") instanceof Boolean boolVal ? boolVal : true;
                Boolean isPrimaryKey = fieldData.get("isPrimaryKey") instanceof Boolean pkVal ? pkVal : false;
                Boolean isUnique = fieldData.get("isUnique") instanceof Boolean uniqueVal ? uniqueVal : false;
                Boolean isForeignKey = fieldData.get("isForeignKey") instanceof Boolean fkVal ? fkVal : false;
                FieldDefinition.FieldDefinitionBuilder fieldBuilder = FieldDefinition.builder()
                        .tableDefinition(table)
                        .fieldName((String) fieldData.get("fieldName"))
                        .dataType(DataType.valueOf((String) fieldData.get("dataType")))
                        .length(fieldData.get("length") != null ? ((Number) fieldData.get("length")).intValue() : null)
                        .precision(fieldData.get("precision") != null ? ((Number) fieldData.get("precision")).intValue() : null)
                        .scale(fieldData.get("scale") != null ? ((Number) fieldData.get("scale")).intValue() : null)
                        .nullable(nullable)
                        .defaultValue((String) fieldData.get("defaultValue"))
                        .isPrimaryKey(isPrimaryKey)
                        .isUnique(isUnique)
                        .displayName((String) fieldData.get("displayName"))
                        .sortOrder(sortOrder)
                        // FK/PK runtime metadata; refTableId is re-resolved by name in a second pass (importFieldRefMetadata)
                        .isForeignKey(isForeignKey)
                        .refPrimaryKeyFields(parseStringList(fieldData.get("refPrimaryKeyFields")))
                        .pkGenerationJson(parseJsonMap(fieldData.get("pkGenerationJson")))
                        .relationCardinality((String) fieldData.get("relationCardinality"));
                if (fieldData.get("fkDisplayMode") instanceof String fkDisplayMode) {
                    fieldBuilder.fkDisplayMode(fkDisplayMode);
                }
                applyComputedField(fieldBuilder, fieldData);
                table.getFieldDefinitions().add(fieldBuilder.build());
            }
            tableDefinitionRepository.save(table);
        }
        return table;
    }

    void importForeignKeys(List<Map<String, Object>> tables,
                           Map<String, Long> importedTableNameToId,
                           Map<String, Map<String, FieldDefinition>> importedFieldLookup) {
        for (Map<String, Object> tableData : tables) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> foreignKeys = (List<Map<String, Object>>) tableData.get("foreignKeys");
            if (foreignKeys == null || foreignKeys.isEmpty()) {
                continue;
            }
            String tableName = (String) tableData.get("tableName");
            Long tableId = importedTableNameToId.get(tableName);
            if (tableId == null) {
                continue;
            }
            TableDefinition table = tableDefinitionRepository.findByIdWithFields(tableId)
                    .orElseThrow(() -> new ResourceNotFoundException("TableDefinition", tableId));
            Map<String, FieldDefinition> fieldByName = importedFieldLookup.getOrDefault(tableName, Map.of());
            for (Map<String, Object> fkData : foreignKeys) {
                String fieldName = (String) fkData.get("fieldName");
                String refTableName = (String) fkData.get("refTableName");
                String refFieldName = (String) fkData.get("refFieldName");
                FieldDefinition field = fieldByName.get(fieldName);
                Map<String, FieldDefinition> refFieldByName = importedFieldLookup.getOrDefault(refTableName, Map.of());
                FieldDefinition refField = refFieldByName.get(refFieldName);
                Long refTableId = importedTableNameToId.get(refTableName);
                if (field == null || refField == null || refTableId == null) {
                    log.warn("Skipping foreign key import for {}.{} -> {}.{} (missing field/table)",
                            tableName, fieldName, refTableName, refFieldName);
                    continue;
                }
                TableDefinition refTable = tableDefinitionRepository.getReferenceById(refTableId);
                ForeignKey foreignKey = ForeignKey.builder()
                        .tableDefinition(table)
                        .fieldDefinition(field)
                        .refTableDefinition(refTable)
                        .refFieldDefinition(refField)
                        .onDelete(fkData.get("onDelete") instanceof String onDelete ? onDelete : "NO ACTION")
                        .onUpdate(fkData.get("onUpdate") instanceof String onUpdate ? onUpdate : "NO ACTION")
                        .build();
                table.getForeignKeys().add(foreignKey);
            }
            if (!table.getForeignKeys().isEmpty()) {
                tableDefinitionRepository.save(table);
            }
        }
    }

    /**
     * Second pass after all tables imported: resolve each field's refTableName back to the
     * newly-assigned table id. Mirrors importForeignKeys which also needs the full table id map.
     */
    void importFieldRefMetadata(List<Map<String, Object>> tables,
                                Map<String, Long> importedTableNameToId) {
        for (Map<String, Object> tableData : tables) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) tableData.get("fields");
            if (fields == null || fields.isEmpty()) {
                continue;
            }
            boolean hasRefMetadata = fields.stream().anyMatch(f -> f.get("refTableName") != null);
            if (!hasRefMetadata) {
                continue;
            }
            String tableName = (String) tableData.get("tableName");
            Long tableId = importedTableNameToId.get(tableName);
            if (tableId == null) {
                continue;
            }
            TableDefinition table = tableDefinitionRepository.findByIdWithFields(tableId)
                    .orElseThrow(() -> new ResourceNotFoundException("TableDefinition", tableId));
            Map<String, FieldDefinition> fieldByName = new HashMap<>();
            for (FieldDefinition field : table.getFieldDefinitions()) {
                fieldByName.put(field.getFieldName(), field);
            }
            boolean dirty = false;
            for (Map<String, Object> fieldData : fields) {
                String refTableName = (String) fieldData.get("refTableName");
                if (refTableName == null) {
                    continue;
                }
                Long refTableId = importedTableNameToId.get(refTableName);
                FieldDefinition field = fieldByName.get((String) fieldData.get("fieldName"));
                if (refTableId == null || field == null) {
                    log.warn("Skipping field ref metadata for {}.{} -> {} (missing field/table)",
                            tableName, fieldData.get("fieldName"), refTableName);
                    continue;
                }
                field.setRefTableId(refTableId);
                dirty = true;
            }
            if (dirty) {
                tableDefinitionRepository.save(table);
            }
        }
    }

    void importTableRelations(FunctionUnit functionUnit,
                              List<Map<String, Object>> tableRelations,
                              Map<String, Long> importedTableNameToId) {
        for (Map<String, Object> relationData : tableRelations) {
            String sourceTableName = (String) relationData.get("sourceTableName");
            String targetTableName = (String) relationData.get("targetTableName");
            Long sourceTableId = importedTableNameToId.get(sourceTableName);
            Long targetTableId = importedTableNameToId.get(targetTableName);
            if (sourceTableId == null || targetTableId == null) {
                log.warn("Skipping table relation import {} -> {} (table not found)",
                        sourceTableName, targetTableName);
                continue;
            }
            TableRelation relation = TableRelation.builder()
                    .functionUnit(functionUnit)
                    .sourceTableId(sourceTableId)
                    .sourceFieldName((String) relationData.get("sourceFieldName"))
                    .relationType((String) relationData.get("relationType"))
                    .targetTableId(targetTableId)
                    .targetFieldName((String) relationData.get("targetFieldName"))
                    .build();
            tableRelationRepository.save(relation);
        }
    }

    FormDefinition importFormShell(FunctionUnit functionUnit,
                                   Map<String, Object> formData,
                                   Map<String, Long> importedTableNameToId) {
        Map<String, Object> configJsonMap = parseConfigJsonObject(formData.get("configJson"));
        Map<String, String> fieldPermissions = parseFieldPermissions(formData.get("fieldPermissions"));
        Boolean showLiveValues = formData.get("showLiveValues") instanceof Boolean boolVal ? boolVal : true;

        FormDefinition form = FormDefinition.builder()
                .functionUnit(functionUnit)
                .formName((String) formData.get("formName"))
                .formType(FormType.valueOf((String) formData.get("formType")))
                .scene(parseScene(formData.get("scene")))
                .displayName((String) formData.get("description"))
                .configJson(configJsonMap != null ? configJsonMap : new HashMap<>())
                .fieldPermissions(fieldPermissions)
                .showLiveValues(showLiveValues)
                .build();

        String boundTableName = (String) formData.get("boundTableName");
        if (boundTableName != null && importedTableNameToId.containsKey(boundTableName)) {
            form.setBoundTable(tableDefinitionRepository.getReferenceById(importedTableNameToId.get(boundTableName)));
        }

        return formDefinitionRepository.save(form);
    }

    Map<Long, Long> finalizeFormImport(FormDefinition form,
                            Map<String, Object> formData,
                            Map<String, Long> importedTableNameToId,
                            Map<Long, Long> relationTableIdMapping,
                            Map<Long, Long> formIdMapping,
                            Map<Long, Long> componentIdMapping) {
        Map<Long, Long> bindingIdMapping =
                importFormTableBindings(form, formData, importedTableNameToId, relationTableIdMapping);

        Map<String, Object> configJson = parseConfigJsonObject(formData.get("configJson"));
        if (configJson == null) {
            configJson = form.getConfigJson() != null ? new HashMap<>(form.getConfigJson()) : new HashMap<>();
        } else {
            configJson = new HashMap<>(configJson);
        }
        FormConfigJsonBindingIdRewriter.remapIds(
                configJson, bindingIdMapping, formIdMapping, componentIdMapping, relationTableIdMapping);
        FormConfigJsonOrphanBindingRepair.repairOrphanedBindingKeys(
                configJson, formTableBindingRepository.findByFormIdOrderBySortOrder(form.getId()));
        form.setConfigJson(configJson);

        importFormStageBindings(form, formData);
        formDefinitionRepository.save(form);
        return bindingIdMapping;
    }

    /**
     * Recreates a dw_link_form_components row for the imported unit. The linked form is resolved by
     * NAME first (names are stable across environments), falling back to the form id mapping.
     * Returns null (and logs) when the linked form cannot be resolved — a dangling linkedFormId would
     * make every consumer of this component 500 or render nothing.
     */
    LinkFormComponent importLinkFormComponent(FunctionUnit functionUnit,
                                              Map<String, Object> componentData,
                                              Map<String, Long> importedFormNameToId,
                                              Map<Long, Long> formIdMapping) {
        Long linkedFormId = null;
        if (componentData.get("linkedFormName") instanceof String linkedFormName
                && importedFormNameToId.containsKey(linkedFormName)) {
            linkedFormId = importedFormNameToId.get(linkedFormName);
        } else if (componentData.get("linkedFormId") instanceof Number sourceFormId) {
            linkedFormId = formIdMapping.get(sourceFormId.longValue());
        }
        if (linkedFormId == null) {
            log.warn("Skipping link form component '{}' import: linked form not resolvable (name={}, id={})",
                    componentData.get("componentName"),
                    componentData.get("linkedFormName"), componentData.get("linkedFormId"));
            return null;
        }
        LinkFormComponent component = LinkFormComponent.builder()
                .functionUnitId(functionUnit.getId())
                .componentName((String) componentData.get("componentName"))
                .linkedFormId(linkedFormId)
                .displayField((String) componentData.get("displayField"))
                .linkText((String) componentData.get("linkText"))
                .columnLabel((String) componentData.get("columnLabel"))
                .sortOrder(componentData.get("sortOrder") instanceof Number number ? number.intValue() : null)
                .configJson((String) componentData.get("configJson"))
                .build();
        return linkFormComponentRepository.save(component);
    }

    private Map<Long, Long> importFormTableBindings(FormDefinition form,
                                                    Map<String, Object> formData,
                                                    Map<String, Long> importedTableNameToId,
                                                    Map<Long, Long> relationTableIdMapping) {
        Map<Long, Long> bindingIdMapping = new HashMap<>();
        Object bindingsObj = formData.get("tableBindings");
        if (!(bindingsObj instanceof List<?> bindingsList)) {
            return bindingIdMapping;
        }
        for (Object bindingObj : bindingsList) {
            if (!(bindingObj instanceof Map<?, ?> bindingMapRaw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> bindingData = (Map<String, Object>) bindingMapRaw;
            BindingType bindingType = BindingType.valueOf((String) bindingData.get("bindingType"));
            BindingMode bindingMode = BindingMode.valueOf((String) bindingData.get("bindingMode"));

            TableDefinition table = null;
            String tableName = (String) bindingData.get("tableName");
            if (tableName != null && importedTableNameToId.containsKey(tableName)) {
                table = tableDefinitionRepository.getReferenceById(importedTableNameToId.get(tableName));
            }

            Long relationTableId = bindingData.get("relationTableId") instanceof Number number
                    ? number.longValue() : null;
            // Remap RELATED binding's relationTableId from the source rt id to the freshly imported rt id.
            if (relationTableId != null && relationTableIdMapping != null
                    && relationTableIdMapping.containsKey(relationTableId)) {
                relationTableId = relationTableIdMapping.get(relationTableId);
            }
            Integer sortOrder = bindingData.get("sortOrder") instanceof Number number
                    ? number.intValue() : null;
            SubMode subMode = bindingData.get("subMode") instanceof String subModeStr
                    ? SubMode.valueOf(subModeStr) : null;
            BindingLinkMode bindingLinkMode = bindingData.get("bindingLinkMode") instanceof String linkModeStr
                    ? BindingLinkMode.valueOf(linkModeStr) : BindingLinkMode.structuralFk;

            FormTableBinding.FormTableBindingBuilder bindingBuilder = FormTableBinding.builder()
                    .form(form)
                    .table(table)
                    .relationTableId(relationTableId)
                    .bindingType(bindingType)
                    .bindingMode(bindingMode)
                    .foreignKeyField((String) bindingData.get("foreignKeyField"))
                    .sortOrder(sortOrder)
                    .bindingLinkMode(bindingLinkMode)
                    .subMode(subMode);
            FormTableBinding savedBinding = formTableBindingRepository.save(bindingBuilder.build());

            importSubTableViewConfigIfPresent(savedBinding, bindingData.get("subTableViewConfig"));

            if (bindingData.get("bindingId") instanceof Number sourceBindingId) {
                bindingIdMapping.put(sourceBindingId.longValue(), savedBinding.getId());
            }
        }
        return bindingIdMapping;
    }

    /**
     * Restore the sub-table list view config + fields for a FULL-mode SUB binding, and link the
     * new config id back onto the binding (subListViewId). Mirrors FunctionUnitCloner.
     */
    private void importSubTableViewConfigIfPresent(FormTableBinding savedBinding, Object configObj) {
        if (!(configObj instanceof Map<?, ?> configMapRaw)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> configData = (Map<String, Object>) configMapRaw;

        SubTableViewConfig config = SubTableViewConfig.builder()
                .binding(savedBinding)
                .viewFields(new java.util.ArrayList<>())
                .build();
        SubTableViewConfig savedConfig = subTableViewConfigRepository.save(config);

        if (configData.get("viewFields") instanceof List<?> viewFields) {
            List<SubTableViewField> fields = new java.util.ArrayList<>();
            for (Object fieldObj : viewFields) {
                if (!(fieldObj instanceof Map<?, ?> fieldMapRaw)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> fieldData = (Map<String, Object>) fieldMapRaw;
                Integer columnWidth = fieldData.get("columnWidth") instanceof Number number
                        ? number.intValue() : null;
                Integer sortOrder = fieldData.get("sortOrder") instanceof Number number
                        ? number.intValue() : 0;
                Boolean visible = fieldData.get("visible") instanceof Boolean boolVal ? boolVal : true;
                fields.add(SubTableViewField.builder()
                        .viewConfig(savedConfig)
                        .fieldName((String) fieldData.get("fieldName"))
                        .displayLabel((String) fieldData.get("displayLabel"))
                        .columnWidth(columnWidth)
                        .sortOrder(sortOrder)
                        .visible(visible)
                        .build());
            }
            savedConfig.setViewFields(fields);
            savedConfig = subTableViewConfigRepository.save(savedConfig);
        }

        savedBinding.setSubListViewId(savedConfig.getId());
        formTableBindingRepository.save(savedBinding);
    }

    private void importFormStageBindings(FormDefinition form, Map<String, Object> formData) {
        Object stageBindingsObj = formData.get("stageBindings");
        if (!(stageBindingsObj instanceof List<?> stageBindingsList)) {
            return;
        }
        for (Object stageObj : stageBindingsList) {
            if (!(stageObj instanceof Map<?, ?> stageMapRaw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> stageData = (Map<String, Object>) stageMapRaw;
            Boolean readOnly = stageData.get("readOnly") instanceof Boolean boolVal ? boolVal : false;
            FormStageBinding stageBinding = FormStageBinding.builder()
                    .form(form)
                    .stageId((String) stageData.get("stageId"))
                    .stageName((String) stageData.get("stageName"))
                    .readOnly(readOnly)
                    .scene(parseScene(stageData.get("scene")))
                    .build();
            form.getStageBindings().add(stageBinding);
        }
    }

    /**
     * Packages exported before the scene axis carry no scene; they describe the
     * To Do design, which is what TASK means.
     */
    private static FormScene parseScene(Object raw) {
        if (raw instanceof String name && !name.isBlank()) {
            try {
                return FormScene.valueOf(name.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                log.warn("Unknown form scene '{}' in import package; treating as TASK", name);
            }
        }
        return FormScene.TASK;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfigJsonObject(Object configJsonObj) {
        if (configJsonObj instanceof Map<?, ?> map) {
            return new HashMap<>((Map<String, Object>) map);
        }
        if (configJsonObj instanceof String str && !str.isBlank()) {
            try {
                return objectMapper.readValue(str, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warn("Failed to parse configJson string: {}", e.getMessage());
            }
        }
        return null;
    }

    private RequestIdConfig parseRequestIdConfig(Object requestIdConfigObj) {
        if (requestIdConfigObj == null) {
            return null;
        }
        return objectMapper.convertValue(requestIdConfigObj, RequestIdConfig.class);
    }

    @SuppressWarnings("unchecked")
    private void applyComputedField(FieldDefinition.FieldDefinitionBuilder fieldBuilder,
                                    Map<String, Object> fieldData) {
        boolean computed = Boolean.TRUE.equals(fieldData.get("isComputed"));
        fieldBuilder.isComputed(computed);
        if (!computed) {
            fieldBuilder.computedFieldJson(null);
            return;
        }
        Map<String, Object> definition = parseJsonMap(fieldData.get("computedField"));
        if (definition == null || definition.isEmpty()) {
            throw new DeveloperBusinessException("COMPUTED_FIELD_IMPORT_INVALID",
                    "Field '" + fieldData.get("fieldName")
                            + "' is marked computed but has no usable formula JSON");
        }
        fieldBuilder.computedFieldJson(definition);
    }

    private List<String> parseStringList(Object obj) {
        if (obj instanceof List<?> list) {
            List<String> result = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            return new HashMap<>((Map<String, Object>) map);
        }
        if (obj instanceof String s && !s.isBlank()) {
            try {
                return objectMapper.readValue(s, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private Map<String, String> parseFieldPermissions(Object fieldPermissionsObj) {
        if (fieldPermissionsObj instanceof Map<?, ?> map) {
            Map<String, String> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
            return result;
        }
        return new HashMap<>();
    }

    ActionDefinition importAction(FunctionUnit functionUnit, Map<String, Object> actionData) {
        Map<String, Object> configJsonMap = parseConfigJsonObject(actionData.get("configJson"));
        Boolean isDefault = actionData.get("isDefault") instanceof Boolean boolVal ? boolVal : false;
        ActionDefinition action = ActionDefinition.builder()
                .functionUnit(functionUnit)
                .actionName((String) actionData.get("actionName"))
                .actionType(ActionType.valueOf((String) actionData.get("actionType")))
                .configJson(configJsonMap != null ? configJsonMap : new HashMap<>())
                .icon((String) actionData.get("icon"))
                .buttonColor((String) actionData.get("buttonColor"))
                .displayName((String) actionData.get("description"))
                .isDefault(isDefault)
                .build();
        return actionDefinitionRepository.save(action);
    }

    EmailConnection importEmailConnection(FunctionUnit functionUnit, Map<String, Object> connectionData) {
        EmailConnection.EmailConnectionBuilder builder = EmailConnection.builder()
                .connectionUid(connectionData.get("connectionUid") != null
                        ? (String) connectionData.get("connectionUid")
                        : UUID.randomUUID().toString())
                .functionUnit(functionUnit)
                .name((String) connectionData.get("name"))
                .connectionType(connectionData.get("connectionType") != null
                        ? ConnectionType.valueOf((String) connectionData.get("connectionType"))
                        : ConnectionType.SMTP)
                .host(connectionData.get("host") != null ? (String) connectionData.get("host") : "")
                .port(connectionData.get("port") != null ? ((Number) connectionData.get("port")).intValue() : 587)
                .username((String) connectionData.get("username"))
                .credentialEncrypted(EmailConnectionPortability.readEncryptedCredential(connectionData))
                .fromEmail((String) connectionData.get("fromEmail"))
                .fromName((String) connectionData.get("fromName"))
                .useTls(connectionData.get("useTls") != null ? (Boolean) connectionData.get("useTls") : true)
                .enabled(connectionData.get("enabled") != null ? (Boolean) connectionData.get("enabled") : true)
                .direction(connectionData.get("direction") != null
                        ? EmailConnectionDirection.valueOf((String) connectionData.get("direction"))
                        : EmailConnectionDirection.OUTBOUND)
                .mailboxAddress((String) connectionData.get("mailboxAddress"))
                .imapHost((String) connectionData.get("imapHost"))
                .imapPort(connectionData.get("imapPort") != null
                        ? ((Number) connectionData.get("imapPort")).intValue() : null)
                .imapUseSsl(connectionData.get("imapUseSsl") != null
                        ? (Boolean) connectionData.get("imapUseSsl") : null)
                .oauthScopes((String) connectionData.get("oauthScopes"))
                .oauthRefreshTokenEncrypted((String) connectionData.get("oauthRefreshTokenEncrypted"))
                .oauthAccessTokenEncrypted((String) connectionData.get("oauthAccessTokenEncrypted"));
        if (connectionData.get("oauthProvider") instanceof String provider
                && !provider.isBlank()) {
            builder.oauthProvider(OAuthProvider.valueOf(provider));
        }
        if (connectionData.get("tokenExpiresAt") instanceof String expiresAt
                && !expiresAt.isBlank()) {
            builder.tokenExpiresAt(Instant.parse(expiresAt));
        }
        return emailConnectionRepository.save(builder.build());
    }

    EmailTemplate importEmailTemplate(FunctionUnit functionUnit, Map<String, Object> templateData) {
        EmailTemplate template = EmailTemplate.builder()
                .functionUnit(functionUnit)
                .name((String) templateData.get("name"))
                .subject((String) templateData.get("subject"))
                .bodyHtml((String) templateData.get("bodyHtml"))
                .enabled(templateData.get("enabled") instanceof Boolean enabledVal ? enabledVal : true)
                .build();
        return emailTemplateRepository.save(template);
    }

    void importEmailMonitorRule(FunctionUnit functionUnit,
                                Map<String, Object> ruleData,
                                Map<Long, Long> formIdMapping,
                                Map<Long, Long> bindingIdMapping) {
        Long targetFormId = null;
        if (ruleData.get("targetFormId") instanceof Number sourceFormId) {
            long sourceId = sourceFormId.longValue();
            Long mapped = formIdMapping.get(sourceId);
            if (mapped == null) {
                throw new DeveloperBusinessException("IMPORT_EMAIL_MONITOR_UNMAPPED",
                        "Email monitor references targetFormId that was not imported: " + sourceId);
            }
            targetFormId = mapped;
        }
        String targetBindingId = remapMonitorTargetBindingId(ruleData.get("targetBindingId"), bindingIdMapping);
        // Process key must match the target FU BPMN process id (rewritten to functionUnit.code).
        EmailMonitorRule rule = EmailMonitorRule.builder()
                .ruleUid(ruleData.get("ruleUid") != null
                        ? (String) ruleData.get("ruleUid")
                        : UUID.randomUUID().toString())
                .functionUnit(functionUnit)
                .name((String) ruleData.get("name"))
                .enabled(ruleData.get("enabled") instanceof Boolean enabledVal ? enabledVal : true)
                .connectionUid((String) ruleData.get("connectionUid"))
                .processDefinitionKey(functionUnit.getCode())
                .startEventId((String) ruleData.get("startEventId"))
                .folderLabel(ruleData.get("folderLabel") instanceof String folder
                        ? folder : "INBOX")
                .filterFrom((String) ruleData.get("filterFrom"))
                .filterSubject((String) ruleData.get("filterSubject"))
                .actionType(ruleData.get("actionType") instanceof String actionTypeStr
                        ? EmailMonitorActionType.valueOf(actionTypeStr)
                        : EmailMonitorActionType.START_PROCESS)
                .targetFormId(targetFormId)
                .targetBindingId(targetBindingId)
                .systemInitiatorUserId((String) ruleData.get("systemInitiatorUserId"))
                .extractionRules(parseJsonMap(ruleData.get("extractionRules")))
                .correlation(parseJsonMap(ruleData.get("correlation")))
                .pollIntervalSeconds(ruleData.get("pollIntervalSeconds") instanceof Number poll
                        ? poll.intValue() : 60)
                .reviewOnMissing(ruleData.get("reviewOnMissing") instanceof Boolean review
                        ? review : true)
                .build();
        emailMonitorRuleRepository.save(rule);
    }

    private static String remapMonitorTargetBindingId(Object rawBindingId, Map<Long, Long> bindingIdMapping) {
        if (rawBindingId == null) {
            return null;
        }
        String raw = String.valueOf(rawBindingId);
        try {
            long oldId = Long.parseLong(raw);
            Long mapped = bindingIdMapping.get(oldId);
            if (mapped == null) {
                throw new DeveloperBusinessException("IMPORT_EMAIL_MONITOR_UNMAPPED",
                        "Email monitor references targetBindingId that was not imported: " + raw);
            }
            return String.valueOf(mapped);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /**
     * Import decision definitions (from DMN XML)
     * Use DmnXmlParser for decisionKey/hitPolicy; name from XML decision element
     * Conflict: overwrite same decisionKey within functionUnit
     */
    void importDecision(FunctionUnit functionUnit, String dmnXml) {
        if (dmnXml == null || dmnXml.isBlank()) {
            log.warn("Skipping empty DMN XML during import");
            return;
        }

        String decisionKey = dmnXmlParser.extractDecisionKey(dmnXml);
        if (decisionKey == null || decisionKey.isBlank()) {
            log.warn("Skipping DMN XML without decision key during import");
            return;
        }

        String hitPolicy = dmnXmlParser.extractHitPolicy(dmnXml);

        // Extract decision name from the model
        String decisionName = null;
        try {
            var model = dmnXmlParser.parseToModel(dmnXml);
            if (model != null && model.getDecisionName() != null) {
                decisionName = model.getDecisionName();
            }
        } catch (Exception e) {
            log.warn("Failed to parse decision name from DMN XML for key {}: {}", decisionKey, e.getMessage());
        }

        // Handle conflict: overwrite if same decisionKey exists in this functionUnit
        List<DecisionDefinition> existing = decisionDefinitionRepository.findByFunctionUnitId(functionUnit.getId());
        existing.stream()
                .filter(d -> decisionKey.equals(d.getDecisionKey()))
                .findFirst()
                .ifPresent(d -> decisionDefinitionRepository.deleteById(d.getId()));

        DecisionDefinition decision = DecisionDefinition.builder()
                .functionUnit(functionUnit)
                .decisionKey(decisionKey)
                .decisionName(decisionName)
                .dmnXml(dmnXml)
                .hitPolicy(hitPolicy)
                .build();
        decisionDefinitionRepository.save(decision);
    }
}
