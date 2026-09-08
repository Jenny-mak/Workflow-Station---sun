package com.developer.component.impl;

import com.developer.dto.RequestIdConfig;
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
import com.developer.entity.ProcessDefinition;
import com.developer.entity.SubTableViewConfig;
import com.developer.entity.SubTableViewField;
import com.developer.entity.TableDefinition;
import com.developer.entity.TableRelation;
import com.developer.enums.FunctionUnitStatus;
import com.developer.exception.DeveloperBusinessException;
import com.developer.exception.ResourceNotFoundException;
import com.developer.repository.ActionDefinitionRepository;
import com.developer.repository.DecisionDefinitionRepository;
import com.developer.repository.EmailConnectionRepository;
import com.developer.repository.EmailMonitorRuleRepository;
import com.developer.repository.EmailTemplateRepository;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.FormStageBindingRepository;
import com.developer.repository.FormTableBindingRepository;
import com.developer.repository.FunctionUnitRepository;
import com.developer.repository.ProcessDefinitionRepository;
import com.developer.repository.SubTableViewConfigRepository;
import com.developer.repository.TableDefinitionRepository;
import com.developer.repository.TableRelationRepository;
import com.developer.component.TableDesignComponent;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.security.WorkspaceAccessAction;
import com.developer.service.MainTableViewService;
import com.developer.util.BpmnIdRewriter;
import com.developer.util.BpmnProcessIdRewriter;
import com.developer.util.DeveloperWorkstationSequenceSynchronizer;
import com.developer.util.FormConfigJsonBindingIdRewriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 功能单元克隆协作类。
 * 负责深拷贝功能单元的表/字段/外键/关系/表单/动作/决策/流程定义，并重写 BPMN 与表单配置中的 ID 引用。
 */
@Component
@RequiredArgsConstructor
@Slf4j
class FunctionUnitCloner {

    private final FunctionUnitRepository functionUnitRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final TableDefinitionRepository tableDefinitionRepository;
    private final FormDefinitionRepository formDefinitionRepository;
    private final ActionDefinitionRepository actionDefinitionRepository;
    private final DecisionDefinitionRepository decisionDefinitionRepository;
    private final FormTableBindingRepository formTableBindingRepository;
    private final FormStageBindingRepository formStageBindingRepository;
    private final TableRelationRepository tableRelationRepository;
    private final SubTableViewConfigRepository subTableViewConfigRepository;
    private final EmailConnectionRepository emailConnectionRepository;
    private final EmailMonitorRuleRepository emailMonitorRuleRepository;
    private final EmailTemplateRepository emailTemplateRepository;
    private final ObjectMapper objectMapper;
    private final FunctionUnitWorkspaceAccessService functionUnitWorkspaceAccessService;
    private final DeveloperWorkstationSequenceSynchronizer sequenceSynchronizer;
    private final FunctionUnitCodeGenerator codeGenerator;
    private final MainTableViewService mainTableViewService;
    private final TableDesignComponent tableDesignComponent;

    @Transactional
    FunctionUnit clone(Long id, String newName) {
        functionUnitWorkspaceAccessService.assertCanAccess(id, WorkspaceAccessAction.MODIFY);
        sequenceSynchronizer.synchronizeAll();
        if (functionUnitRepository.existsByName(newName)) {
            throw new DeveloperBusinessException("CONFLICT_NAME_EXISTS",
                    "Function unit name already exists: " + newName,
                    "Please use a different name");
        }

        FunctionUnit source = functionUnitRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FunctionUnit", id));
        List<TableDefinition> sourceTables = tableDefinitionRepository.findByFunctionUnitIdWithFields(id);
        List<FormDefinition> sourceForms = formDefinitionRepository.findByFunctionUnitIdWithBindings(id);
        List<TableRelation> sourceRelations = tableRelationRepository.findByFunctionUnitId(id);

        // Create new function unit with new unique code
        FunctionUnit cloned = FunctionUnit.builder()
                .name(newName)
                .code(codeGenerator.generateUniqueCode(newName))
                .displayName(source.getDisplayName())
                .tags(source.getTags() != null ? new java.util.ArrayList<>(source.getTags()) : new java.util.ArrayList<>())
                .icon(source.getIcon())
                .status(FunctionUnitStatus.DRAFT)
                .build();
        cloned = functionUnitRepository.save(cloned);

        // Clone order: clone all ProcessDefinition dependencies (tables/forms/actions) first,
        // collect old→new ID map, then write process definition and rewrite BPMN ID references.
        // Otherwise BPMN still references source subTableId/formId/actionIds and deploy validation fails.

        // Clone table definitions. table_name is globally unique (uk_dw_table_name spans dw_ + rt_),
        // so each cloned table gets a fresh unique name; sourceTableName→newTableName lets us rewrite
        // BPMN table-name references (e.g. MI subTableName) to the clone's tables.
        Map<Long, TableDefinition> tableMapping = new HashMap<>();
        Map<String, String> sourceToNewTableName = new HashMap<>();
        for (TableDefinition sourceTable : sourceTables) {
            String newTableName = generateUniqueTableName(sourceTable.getTableName());
            TableDefinition clonedTable = cloneTable(sourceTable, cloned, newTableName);
            tableMapping.put(sourceTable.getId(), clonedTable);
            if (sourceTable.getTableName() != null) {
                sourceToNewTableName.put(sourceTable.getTableName(), newTableName);
            }
        }

        // Field-level FK metadata points at table ids, so it can only be remapped once every table exists
        remapClonedFieldRefTableIds(sourceTables, tableMapping);

        // Clone FK relations after all tables (FKs may cross tables)
        Map<Long, Map<String, FieldDefinition>> clonedFieldLookup = new HashMap<>();
        for (Map.Entry<Long, TableDefinition> entry : tableMapping.entrySet()) {
            Map<String, FieldDefinition> fieldMap = new HashMap<>();
            for (FieldDefinition field : entry.getValue().getFieldDefinitions()) {
                fieldMap.put(field.getFieldName(), field);
            }
            clonedFieldLookup.put(entry.getKey(), fieldMap);
        }
        for (TableDefinition sourceTable : sourceTables) {
            if (sourceTable.getForeignKeys() != null) {
                TableDefinition clonedTable = tableMapping.get(sourceTable.getId());
                for (ForeignKey sourceFk : sourceTable.getForeignKeys()) {
                    TableDefinition clonedRefTable = sourceFk.getRefTableDefinition() != null
                            ? tableMapping.get(sourceFk.getRefTableDefinition().getId()) : null;
                    FieldDefinition clonedField = sourceFk.getFieldDefinition() != null
                            ? clonedFieldLookup.getOrDefault(sourceTable.getId(), Map.of())
                                .get(sourceFk.getFieldDefinition().getFieldName()) : null;
                    FieldDefinition clonedRefField = sourceFk.getRefFieldDefinition() != null && clonedRefTable != null
                            ? clonedFieldLookup.getOrDefault(sourceFk.getRefTableDefinition().getId(), Map.of())
                                .get(sourceFk.getRefFieldDefinition().getFieldName()) : null;

                    if (clonedField != null && clonedRefTable != null && clonedRefField != null) {
                        ForeignKey clonedFk = ForeignKey.builder()
                                .tableDefinition(clonedTable)
                                .fieldDefinition(clonedField)
                                .refTableDefinition(clonedRefTable)
                                .refFieldDefinition(clonedRefField)
                                .onDelete(sourceFk.getOnDelete())
                                .onUpdate(sourceFk.getOnUpdate())
                                .build();
                        clonedTable.getForeignKeys().add(clonedFk);
                    }
                }
                tableDefinitionRepository.save(clonedTable);
            }
        }

        cloneTableRelations(sourceRelations, cloned, tableMapping);

        // Clone form definitions (with TableBindings); collect form id map + binding id map
        Map<Long, Long> formIdMapping = new HashMap<>();
        Map<Long, Long> bindingIdMapping = new HashMap<>();
        for (FormDefinition sourceForm : sourceForms) {
            FormDefinition clonedForm = cloneForm(sourceForm, cloned, tableMapping, bindingIdMapping);
            formIdMapping.put(sourceForm.getId(), clonedForm.getId());
        }

        // Clone action definitions; collect action id map
        Map<Long, Long> actionIdMapping = new HashMap<>();
        for (ActionDefinition sourceAction : source.getActionDefinitions()) {
            ActionDefinition clonedAction = cloneAction(sourceAction, cloned);
            actionIdMapping.put(sourceAction.getId(), clonedAction.getId());
        }

        // Clone decision definitions
        for (DecisionDefinition sourceDecision : source.getDecisionDefinitions()) {
            cloneDecision(sourceDecision, cloned);
        }

        Map<Long, Long> connectionIdMapping = new HashMap<>();
        Map<String, String> connectionUidMapping = new HashMap<>();
        cloneEmailConnections(id, cloned, connectionIdMapping, connectionUidMapping);
        Map<Long, Long> emailTemplateIdMapping = cloneEmailTemplates(id, cloned);
        cloneEmailMonitors(id, cloned, formIdMapping, bindingIdMapping, connectionUidMapping);

        // Clone process definition last; rewrite BPMN ID references
        if (source.getProcessDefinition() != null) {
            // Old→new ID map (fallback)
            Map<Long, Long> tableIdMapping = new HashMap<>();
            for (Map.Entry<Long, TableDefinition> entry : tableMapping.entrySet()) {
                tableIdMapping.put(entry.getKey(), entry.getValue().getId());
            }
            // Name→new ID map for name-first resolution (fixes dirty BPMN where id/name diverge).
            // Keyed by the SOURCE table name, because that's what the source BPMN still carries at
            // resolution time; the name VALUE itself is rewritten afterwards via sourceToNewTableName.
            Map<String, Long> clonedTableNameToId = new HashMap<>();
            for (TableDefinition sourceTable : sourceTables) {
                TableDefinition clonedTable = tableMapping.get(sourceTable.getId());
                if (sourceTable.getTableName() != null && clonedTable != null) {
                    clonedTableNameToId.put(sourceTable.getTableName(), clonedTable.getId());
                }
            }
            Map<String, Long> clonedFormNameToId = new HashMap<>();
            for (FormDefinition sourceForm : sourceForms) {
                Long clonedFormId = formIdMapping.get(sourceForm.getId());
                if (clonedFormId != null) {
                    clonedFormNameToId.put(sourceForm.getFormName(), clonedFormId);
                }
            }
            String rewrittenBpmn = BpmnIdRewriter.rewrite(
                    source.getProcessDefinition().getBpmnXml(),
                    tableIdMapping,
                    formIdMapping,
                    actionIdMapping,
                    clonedTableNameToId,
                    clonedFormNameToId,
                    sourceToNewTableName,
                    connectionIdMapping,
                    emailTemplateIdMapping,
                    connectionUidMapping);
            rewrittenBpmn = BpmnProcessIdRewriter.rewriteToFunctionUnitCode(rewrittenBpmn, cloned.getCode());
            ProcessDefinition clonedProcess = ProcessDefinition.builder()
                    .functionUnit(cloned)
                    .functionUnitVersionId(cloned.getId())
                    .bpmnXml(rewrittenBpmn)
                    .build();
            processDefinitionRepository.save(clonedProcess);
        }

        mainTableViewService.cloneViewsForFunctionUnit(id, cloned, tableMapping, formIdMapping);
        return cloned;
    }

    /**
     * Derive a globally-unique table name for a cloned table. {@code uk_dw_table_name} is global and the
     * designer's availability check spans both {@code dw_table_definitions} and {@code rt_table_definitions},
     * so we append {@code _copyN} (and a short random tail as a last resort) until the name is free.
     * Names stay within VARCHAR(100) and the BPMN/identifier-safe {@code [a-z0-9_]} subset.
     */
    private String generateUniqueTableName(String sourceTableName) {
        String base = sourceTableName != null && !sourceTableName.isBlank() ? sourceTableName : "table";
        // Reserve room for the longest suffix we append ("_copy" + up to ~3 digits, or "_" + 6 random).
        final int maxBaseLen = 92;
        if (base.length() > maxBaseLen) {
            base = base.substring(0, maxBaseLen);
        }
        for (int i = 1; i <= 50; i++) {
            String candidate = base + "_copy" + (i == 1 ? "" : i);
            if (tableDesignComponent.isTableNameAvailable(candidate, null)) {
                return candidate;
            }
        }
        // Last resort: random tail. SecureRandom not needed — uniqueness, not unpredictability.
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder tail = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                tail.append(chars.charAt(random.nextInt(chars.length())));
            }
            String candidate = base + "_" + tail;
            if (tableDesignComponent.isTableNameAvailable(candidate, null)) {
                return candidate;
            }
        }
        throw new DeveloperBusinessException("CLONE_TABLE_NAME_EXHAUSTED",
                "Could not derive a unique table name for clone of '" + sourceTableName + "'");
    }

    private TableDefinition cloneTable(TableDefinition source, FunctionUnit target, String newTableName) {
        TableDefinition cloned = TableDefinition.builder()
                .functionUnit(target)
                .tableName(newTableName)
                .tableType(source.getTableType())
                .tableDisplayName(source.getTableDisplayName())
                .displayName(source.getDisplayName())
                .requestIdConfig(copyRequestIdConfig(source.getRequestIdConfig()))
                .build();
        cloned = tableDefinitionRepository.save(cloned);

        // Clone fields
        for (FieldDefinition sourceField : source.getFieldDefinitions()) {
            FieldDefinition.FieldDefinitionBuilder fieldBuilder = FieldDefinition.builder()
                    .tableDefinition(cloned)
                    .fieldName(sourceField.getFieldName())
                    .dataType(sourceField.getDataType())
                    .length(sourceField.getLength())
                    .precision(sourceField.getPrecision())
                    .scale(sourceField.getScale())
                    .nullable(sourceField.getNullable())
                    .defaultValue(sourceField.getDefaultValue())
                    .isPrimaryKey(sourceField.getIsPrimaryKey())
                    .isUnique(sourceField.getIsUnique())
                    .displayName(sourceField.getDisplayName())
                    .sortOrder(sourceField.getSortOrder())
                    // FK/PK runtime metadata — the runtime FK auto-fill and PK generation strategy
                    // (uuid / autoIncrement / prefixedSequence) read the field-level columns, not dw_foreign_keys.
                    // refTableId still points at the SOURCE table here; remapClonedFieldRefTableIds
                    // rewrites it once every table of the FU has been cloned.
                    .isForeignKey(sourceField.getIsForeignKey())
                    .refTableId(sourceField.getRefTableId())
                    .refPrimaryKeyFields(sourceField.getRefPrimaryKeyFields() != null
                            ? new ArrayList<>(sourceField.getRefPrimaryKeyFields()) : null)
                    .pkGenerationJson(deepCopyMap(sourceField.getPkGenerationJson()))
                    .isComputed(Boolean.TRUE.equals(sourceField.getIsComputed()))
                    .computedFieldJson(copyComputedFieldJson(sourceField))
                    .relationCardinality(sourceField.getRelationCardinality());
            if (sourceField.getFkDisplayMode() != null) {
                fieldBuilder.fkDisplayMode(sourceField.getFkDisplayMode());
            }
            cloned.getFieldDefinitions().add(fieldBuilder.build());
        }

        return tableDefinitionRepository.save(cloned);
    }

    private RequestIdConfig copyRequestIdConfig(RequestIdConfig source) {
        if (source == null) {
            return null;
        }
        return RequestIdConfig.builder()
                .fieldNames(source.getFieldNames() != null ? new ArrayList<>(source.getFieldNames()) : null)
                .separator(source.getSeparator())
                .build();
    }

    /**
     * Rewrite each cloned field's {@code refTableId} from the source table id to the clone's id.
     * Runs after every table is cloned because a field may reference a table that is cloned later —
     * the same reason {@code dw_foreign_keys} rows are cloned in a second pass.
     */
    private void remapClonedFieldRefTableIds(List<TableDefinition> sourceTables,
                                             Map<Long, TableDefinition> tableMapping) {
        for (TableDefinition sourceTable : sourceTables) {
            TableDefinition clonedTable = tableMapping.get(sourceTable.getId());
            if (clonedTable == null) {
                continue;
            }
            boolean dirty = false;
            for (FieldDefinition clonedField : clonedTable.getFieldDefinitions()) {
                Long sourceRefTableId = clonedField.getRefTableId();
                if (sourceRefTableId == null) {
                    continue;
                }
                TableDefinition clonedRefTable = tableMapping.get(sourceRefTableId);
                if (clonedRefTable == null) {
                    // Reference outside this FU (dirty legacy data). Keep the id so the clone resolves
                    // exactly like the source instead of silently dropping the FK metadata.
                    log.warn("Clone: field {}.{} references table id {} outside the cloned function unit; keeping source id",
                            clonedTable.getTableName(), clonedField.getFieldName(), sourceRefTableId);
                    continue;
                }
                clonedField.setRefTableId(clonedRefTable.getId());
                dirty = true;
            }
            if (dirty) {
                tableDefinitionRepository.save(clonedTable);
            }
        }
    }

    private void cloneTableRelations(List<TableRelation> sourceRelations,
                                     FunctionUnit cloned,
                                     Map<Long, TableDefinition> tableMapping) {
        for (TableRelation sourceRelation : sourceRelations) {
            TableDefinition sourceTable = tableMapping.get(sourceRelation.getSourceTableId());
            TableDefinition targetTable = tableMapping.get(sourceRelation.getTargetTableId());
            if (sourceTable == null || targetTable == null) {
                log.warn("Skipping table relation clone: sourceTableId={}, targetTableId={}",
                        sourceRelation.getSourceTableId(), sourceRelation.getTargetTableId());
                continue;
            }
            TableRelation clonedRelation = TableRelation.builder()
                    .functionUnit(cloned)
                    .sourceTableId(sourceTable.getId())
                    .sourceFieldName(sourceRelation.getSourceFieldName())
                    .relationType(sourceRelation.getRelationType())
                    .targetTableId(targetTable.getId())
                    .targetFieldName(sourceRelation.getTargetFieldName())
                    .build();
            tableRelationRepository.save(clonedRelation);
        }
    }

    private FormDefinition cloneForm(FormDefinition source, FunctionUnit target,
                                     Map<Long, TableDefinition> tableMapping,
                                     Map<Long, Long> bindingIdMapping) {
        Map<String, Object> configJson = deepCopyMap(source.getConfigJson());
        Map<String, String> fieldPermissions = source.getFieldPermissions() != null
                ? new HashMap<>(source.getFieldPermissions()) : new HashMap<>();

        FormDefinition cloned = FormDefinition.builder()
                .functionUnit(target)
                .formName(source.getFormName())
                .formType(source.getFormType())
                .scene(source.getScene())
                .configJson(configJson != null ? configJson : new HashMap<>())
                .displayName(source.getDisplayName())
                .fieldPermissions(fieldPermissions)
                .showLiveValues(source.getShowLiveValues())
                .build();

        if (source.getBoundTable() != null && tableMapping.containsKey(source.getBoundTable().getId())) {
            cloned.setBoundTable(tableMapping.get(source.getBoundTable().getId()));
        }

        FormDefinition savedForm = formDefinitionRepository.save(cloned);

        Map<Long, Long> formBindingIdMapping = new HashMap<>();
        List<FormTableBinding> sourceBindings = formTableBindingRepository.findByFormIdWithTable(source.getId());
        for (FormTableBinding sourceBinding : sourceBindings) {
            TableDefinition clonedTable = sourceBinding.getTable() != null
                    ? tableMapping.get(sourceBinding.getTable().getId()) : null;
            FormTableBinding clonedBinding = FormTableBinding.builder()
                    .form(savedForm)
                    .table(clonedTable)
                    .relationTableId(sourceBinding.getRelationTableId())
                    .bindingType(sourceBinding.getBindingType())
                    .bindingMode(sourceBinding.getBindingMode())
                    .foreignKeyField(sourceBinding.getForeignKeyField())
                    .sortOrder(sourceBinding.getSortOrder())
                    .subMode(sourceBinding.getSubMode())
                    .build();
            FormTableBinding savedBinding = formTableBindingRepository.save(clonedBinding);
            formBindingIdMapping.put(sourceBinding.getId(), savedBinding.getId());
            bindingIdMapping.put(sourceBinding.getId(), savedBinding.getId());
            cloneSubTableViewConfigIfPresent(sourceBinding, savedBinding);
        }

        for (FormStageBinding sourceStage : formStageBindingRepository.findByFormId(source.getId())) {
            FormStageBinding clonedStage = FormStageBinding.builder()
                    .form(savedForm)
                    .stageId(sourceStage.getStageId())
                    .stageName(sourceStage.getStageName())
                    .readOnly(sourceStage.getReadOnly())
                    .scene(sourceStage.getScene())
                    .build();
            formStageBindingRepository.save(clonedStage);
        }

        if (configJson != null) {
            FormConfigJsonBindingIdRewriter.remapBindingIds(configJson, formBindingIdMapping);
            savedForm.setConfigJson(configJson);
            savedForm = formDefinitionRepository.save(savedForm);
        }

        return savedForm;
    }

    private void cloneEmailConnections(Long sourceFunctionUnitId,
                                       FunctionUnit target,
                                       Map<Long, Long> connectionIdMapping,
                                       Map<String, String> connectionUidMapping) {
        for (EmailConnection source : emailConnectionRepository
                .findByFunctionUnitIdOrderByNameAsc(sourceFunctionUnitId)) {
            String newUid = UUID.randomUUID().toString();
            if (source.getConnectionUid() != null) {
                connectionUidMapping.put(source.getConnectionUid(), newUid);
            }
            EmailConnection cloned = EmailConnection.builder()
                    .connectionUid(newUid)
                    .functionUnit(target)
                    .name(source.getName())
                    .connectionType(source.getConnectionType())
                    .host(source.getHost() != null ? source.getHost() : "")
                    .port(source.getPort())
                    .username(source.getUsername())
                    .credentialEncrypted(source.getCredentialEncrypted())
                    .fromEmail(source.getFromEmail())
                    .fromName(source.getFromName())
                    .useTls(source.getUseTls())
                    .enabled(source.getEnabled())
                    .direction(source.getDirection())
                    .oauthProvider(source.getOauthProvider())
                    .oauthRefreshTokenEncrypted(source.getOauthRefreshTokenEncrypted())
                    .oauthAccessTokenEncrypted(source.getOauthAccessTokenEncrypted())
                    .tokenExpiresAt(source.getTokenExpiresAt())
                    .mailboxAddress(source.getMailboxAddress())
                    .imapHost(source.getImapHost())
                    .imapPort(source.getImapPort())
                    .imapUseSsl(source.getImapUseSsl())
                    .oauthScopes(source.getOauthScopes())
                    .build();
            EmailConnection saved = emailConnectionRepository.save(cloned);
            connectionIdMapping.put(source.getId(), saved.getId());
        }
    }

    private Map<Long, Long> cloneEmailTemplates(Long sourceFunctionUnitId, FunctionUnit target) {
        Map<Long, Long> templateIdMapping = new HashMap<>();
        for (EmailTemplate source : emailTemplateRepository
                .findByFunctionUnitIdOrderByNameAsc(sourceFunctionUnitId)) {
            EmailTemplate cloned = EmailTemplate.builder()
                    .functionUnit(target)
                    .name(source.getName())
                    .subject(source.getSubject())
                    .bodyHtml(source.getBodyHtml())
                    .enabled(source.getEnabled())
                    .build();
            EmailTemplate saved = emailTemplateRepository.save(cloned);
            templateIdMapping.put(source.getId(), saved.getId());
        }
        return templateIdMapping;
    }

    private void cloneEmailMonitors(Long sourceFunctionUnitId,
                                    FunctionUnit target,
                                    Map<Long, Long> formIdMapping,
                                    Map<Long, Long> bindingIdMapping,
                                    Map<String, String> connectionUidMapping) {
        for (EmailMonitorRule source : emailMonitorRuleRepository
                .findByFunctionUnitIdOrderByNameAsc(sourceFunctionUnitId)) {
            String mappedUid = remapRequiredConnectionUid(
                    source.getConnectionUid(), connectionUidMapping, source.getName());
            Long targetFormId = remapOptionalFormId(
                    source.getTargetFormId(), formIdMapping, source.getName());
            String targetBindingId = remapOptionalBindingId(
                    source.getTargetBindingId(), bindingIdMapping, source.getName());
            EmailMonitorRule cloned = EmailMonitorRule.builder()
                    .ruleUid(UUID.randomUUID().toString())
                    .functionUnit(target)
                    .name(source.getName())
                    .enabled(source.getEnabled())
                    .connectionUid(mappedUid)
                    .processDefinitionKey(target.getCode())
                    .startEventId(source.getStartEventId())
                    .folderLabel(source.getFolderLabel())
                    .filterFrom(source.getFilterFrom())
                    .filterSubject(source.getFilterSubject())
                    .actionType(source.getActionType())
                    .targetFormId(targetFormId)
                    .targetBindingId(targetBindingId)
                    .systemInitiatorUserId(source.getSystemInitiatorUserId())
                    .extractionRules(deepCopyMap(source.getExtractionRules()))
                    .correlation(deepCopyMap(source.getCorrelation()))
                    .pollIntervalSeconds(source.getPollIntervalSeconds())
                    .reviewOnMissing(source.getReviewOnMissing())
                    .build();
            emailMonitorRuleRepository.save(cloned);
        }
    }

    private void cloneSubTableViewConfigIfPresent(FormTableBinding sourceBinding, FormTableBinding savedBinding) {
        subTableViewConfigRepository.findByBindingId(sourceBinding.getId()).ifPresent(sourceConfig -> {
            List<SubTableViewField> copiedFields = new ArrayList<>();
            if (sourceConfig.getViewFields() != null) {
                for (SubTableViewField sourceField : sourceConfig.getViewFields()) {
                    copiedFields.add(SubTableViewField.builder()
                            .fieldName(sourceField.getFieldName())
                            .displayLabel(sourceField.getDisplayLabel())
                            .columnWidth(sourceField.getColumnWidth())
                            .sortOrder(sourceField.getSortOrder())
                            .visible(sourceField.getVisible())
                            .build());
                }
            }
            SubTableViewConfig newConfig = SubTableViewConfig.builder()
                    .binding(savedBinding)
                    .viewFields(new ArrayList<>())
                    .build();
            SubTableViewConfig savedConfig = subTableViewConfigRepository.save(newConfig);
            for (SubTableViewField field : copiedFields) {
                field.setViewConfig(savedConfig);
            }
            savedConfig.setViewFields(copiedFields);
            savedConfig = subTableViewConfigRepository.save(savedConfig);
            savedBinding.setSubListViewId(savedConfig.getId());
            formTableBindingRepository.save(savedBinding);
        });
    }

    private Map<String, Object> deepCopyMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        try {
            return objectMapper.readValue(
                    objectMapper.writeValueAsString(source),
                    new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new DeveloperBusinessException("SYS_JSON_ERROR",
                    "Failed to deep copy JSON configuration during clone: " + e.getMessage());
        }
    }

    /**
     * Clone must carry the formula JSON. Copying {@code isComputed=true} with a null
     * {@code computed_field_json} would make the clone look computed while Portal has nothing
     * to evaluate — the same hole ImportWriter already rejects.
     */
    private Map<String, Object> copyComputedFieldJson(FieldDefinition sourceField) {
        if (!Boolean.TRUE.equals(sourceField.getIsComputed())) {
            return null;
        }
        Map<String, Object> copied = deepCopyMap(sourceField.getComputedFieldJson());
        if (copied == null || copied.isEmpty()) {
            throw new DeveloperBusinessException("COMPUTED_FIELD_CLONE_INVALID",
                    "Field '" + sourceField.getFieldName()
                            + "' is marked computed but has no usable formula JSON");
        }
        return copied;
    }

    private ActionDefinition cloneAction(ActionDefinition source, FunctionUnit target) {
        ActionDefinition cloned = ActionDefinition.builder()
                .functionUnit(target)
                .actionName(source.getActionName())
                .actionType(source.getActionType())
                .configJson(new HashMap<>(source.getConfigJson()))
                .icon(source.getIcon())
                .buttonColor(source.getButtonColor())
                .displayName(source.getDisplayName())
                .isDefault(source.getIsDefault())
                .build();
        return actionDefinitionRepository.save(cloned);
    }

    private void cloneDecision(DecisionDefinition source, FunctionUnit target) {
        DecisionDefinition cloned = DecisionDefinition.builder()
                .functionUnit(target)
                .decisionKey(source.getDecisionKey())
                .decisionName(source.getDecisionName())
                .dmnXml(source.getDmnXml())
                .hitPolicy(source.getHitPolicy())
                .description(source.getDescription())
                .build();
        decisionDefinitionRepository.save(cloned);
    }

    private static String remapRequiredConnectionUid(String sourceUid,
                                                     Map<String, String> connectionUidMapping,
                                                     String monitorName) {
        if (sourceUid == null) {
            return null;
        }
        String mapped = connectionUidMapping.get(sourceUid);
        if (mapped == null) {
            throw new DeveloperBusinessException("CLONE_EMAIL_MONITOR_UNMAPPED",
                    "Email monitor '" + monitorName
                            + "' references connectionUid that was not cloned: " + sourceUid);
        }
        return mapped;
    }

    private static Long remapOptionalFormId(Long sourceFormId,
                                            Map<Long, Long> formIdMapping,
                                            String monitorName) {
        if (sourceFormId == null) {
            return null;
        }
        Long mapped = formIdMapping.get(sourceFormId);
        if (mapped == null) {
            throw new DeveloperBusinessException("CLONE_EMAIL_MONITOR_UNMAPPED",
                    "Email monitor '" + monitorName
                            + "' references targetFormId that was not cloned: " + sourceFormId);
        }
        return mapped;
    }

    private static String remapOptionalBindingId(String targetBindingId,
                                                 Map<Long, Long> bindingIdMapping,
                                                 String monitorName) {
        if (targetBindingId == null) {
            return null;
        }
        try {
            long oldBindingId = Long.parseLong(targetBindingId);
            Long mappedBindingId = bindingIdMapping.get(oldBindingId);
            if (mappedBindingId == null) {
                throw new DeveloperBusinessException("CLONE_EMAIL_MONITOR_UNMAPPED",
                        "Email monitor '" + monitorName
                                + "' references targetBindingId that was not cloned: " + targetBindingId);
            }
            return String.valueOf(mappedBindingId);
        } catch (NumberFormatException e) {
            // Non-numeric binding ids are kept as-is (not remapped by Long maps).
            return targetBindingId;
        }
    }
}
