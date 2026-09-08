package com.developer.component.impl;

import com.developer.dto.ExportManifest;
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
import com.developer.entity.ProcessDefinition;
import com.developer.entity.SubTableViewConfig;
import com.developer.entity.SubTableViewField;
import com.developer.entity.TableDefinition;
import com.developer.entity.TableRelation;
import com.developer.exception.DeveloperBusinessException;
import com.developer.exception.ResourceNotFoundException;
import com.developer.repository.ActionDefinitionRepository;
import com.developer.repository.DecisionDefinitionRepository;
import com.developer.repository.EmailConnectionRepository;
import com.developer.repository.EmailMonitorRuleRepository;
import com.developer.repository.EmailTemplateRepository;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.FormStageBindingRepository;
import com.developer.repository.FunctionUnitRepository;
import com.developer.repository.LinkFormComponentRepository;
import com.developer.repository.SubTableViewConfigRepository;
import com.developer.enums.FormScene;
import com.developer.repository.TableDefinitionRepository;
import com.developer.repository.TableRelationRepository;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.security.WorkspaceAccessAction;
import com.developer.util.XmlEncodingUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.platform.security.util.SecurityContextUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 功能单元导出协作类。
 * 负责把功能单元及其表/表关系/表单/动作/决策/流程序列化为 ZIP 包，并生成校验和。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FunctionUnitExporter {

    private final FunctionUnitRepository functionUnitRepository;
    private final TableDefinitionRepository tableDefinitionRepository;
    private final FormDefinitionRepository formDefinitionRepository;
    private final ActionDefinitionRepository actionDefinitionRepository;
    private final DecisionDefinitionRepository decisionDefinitionRepository;
    private final EmailConnectionRepository emailConnectionRepository;
    private final EmailMonitorRuleRepository emailMonitorRuleRepository;
    private final EmailTemplateRepository emailTemplateRepository;
    private final FormStageBindingRepository formStageBindingRepository;
    private final TableRelationRepository tableRelationRepository;
    private final SubTableViewConfigRepository subTableViewConfigRepository;
    private final LinkFormComponentRepository linkFormComponentRepository;
    private final RelationTableStructurePortability relationTablePortability;
    private final MainTableViewPortability mainTableViewPortability;
    private final FunctionUnitWorkspaceAccessService functionUnitWorkspaceAccessService;
    private final ObjectMapper objectMapper;

    @Value("${platform.version:1.0.0}")
    private String platformVersion;

    /**
     * Returns the current operator.
     * Prefer Spring Security Context; return "system" when unavailable.
     *
     * Cases that yield "system":
     * - No authentication (not logged in)
     * - Anonymous user
     * - System background job
     * - Exception while resolving operator
     *
     * @return current operator username, or "system" when unavailable
     */
    String getCurrentOperator() {
        try {
            return SecurityContextUtils.getCurrentUsername().orElse("system");
        } catch (Exception e) {
            log.debug("Failed to get current operator from security context: {}", e.getMessage());
        }
        return "system";
    }

    /** Snapshot schema version written to {@code dw_versions.snapshot_data} (export-format parity). */
    public static final int VERSION_SNAPSHOT_SCHEMA = 2;

    /**
     * Builds a version rollback snapshot payload using the same serializers as ZIP export.
     * Internal callers (Version Management / Publish) do not re-check workspace access.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildVersionSnapshotPayload(Long functionUnitId) {
        FunctionUnit functionUnit = functionUnitRepository.findById(functionUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("FunctionUnit", functionUnitId));

        List<TableDefinition> tables = tableDefinitionRepository.findByFunctionUnitIdWithFields(functionUnitId);
        List<FormDefinition> forms = formDefinitionRepository.findByFunctionUnitIdWithBindings(functionUnitId);
        List<ActionDefinition> actions = actionDefinitionRepository.findByFunctionUnitId(functionUnitId);
        List<DecisionDefinition> decisions = decisionDefinitionRepository.findByFunctionUnitId(functionUnitId);
        List<TableRelation> tableRelations = tableRelationRepository.findByFunctionUnitId(functionUnitId);
        Map<Long, String> tableIdToName = tables.stream()
                .collect(Collectors.toMap(TableDefinition::getId, TableDefinition::getTableName));
        Map<Long, List<FormStageBinding>> stageBindingsByFormId = new HashMap<>();
        for (FormDefinition form : forms) {
            stageBindingsByFormId.put(form.getId(), formStageBindingRepository.findByFormId(form.getId()));
        }
        ProcessDefinition processDefinition = functionUnit.getProcessDefinition();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshotSchemaVersion", VERSION_SNAPSHOT_SCHEMA);
        payload.put("name", functionUnit.getName());
        payload.put("code", functionUnit.getCode());
        payload.put("description", functionUnit.getDisplayName());
        payload.put("status", functionUnit.getStatus() != null ? functionUnit.getStatus().name() : null);

        if (processDefinition != null) {
            payload.put("process", XmlEncodingUtil.smartDecode(processDefinition.getBpmnXml()));
        }
        // Automation flows are intentionally NOT snapshotted: the flow body is an environment-level
        // resource with its own versioning in AP, and a same-database rollback leaves the BPMN's
        // ap:flowKey/ap:flowId pointing at the very same flow. Cross-environment portability is
        // served by the dedicated Automation flow migration channel (FR-B12) — FU packages no
        // longer carry flow bodies either.

        payload.put("tables", tables.stream()
                .map(table -> serializeTable(table, tableIdToName))
                .toList());

        if (!tableRelations.isEmpty()) {
            payload.put("tableRelations", tableRelations.stream()
                    .map(rel -> serializeTableRelation(rel, tableIdToName))
                    .toList());
        }

        payload.put("forms", forms.stream()
                .map(form -> serializeForm(form, stageBindingsByFormId.getOrDefault(form.getId(), List.of())))
                .toList());

        List<Long> relationTableIds = forms.stream()
                .filter(f -> f.getTableBindings() != null)
                .flatMap(f -> f.getTableBindings().stream())
                .map(FormTableBinding::getRelationTableId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        List<Map<String, Object>> relationTableStructures = relationTablePortability.exportByIds(relationTableIds);
        if (!relationTableStructures.isEmpty()) {
            payload.put("relationTables", relationTableStructures);
        }

        // Shared by views (detail form) and link form components: both reference forms
        // by name so they survive form-id remapping on import.
        Map<Long, String> formIdToName = forms.stream()
                .collect(Collectors.toMap(FormDefinition::getId, FormDefinition::getFormName));

        List<Map<String, Object>> mainTableViews =
                mainTableViewPortability.export(functionUnitId, tableIdToName, formIdToName);
        if (!mainTableViews.isEmpty()) {
            payload.put("mainTableViews", mainTableViews);
        }

        List<LinkFormComponent> linkFormComponents =
                linkFormComponentRepository.findByFunctionUnitIdOrderBySortOrderAsc(functionUnitId);
        if (!linkFormComponents.isEmpty()) {
            payload.put("linkFormComponents", linkFormComponents.stream()
                    .map(c -> serializeLinkFormComponent(c, formIdToName))
                    .toList());
        }

        payload.put("actions", actions.stream().map(this::serializeAction).toList());

        List<Map<String, Object>> connections = emailConnectionRepository
                .findByFunctionUnitIdOrderByNameAsc(functionUnitId).stream()
                .map(this::serializeConnection)
                .toList();
        if (!connections.isEmpty()) {
            payload.put("connections", connections);
        }

        List<Map<String, Object>> monitors = emailMonitorRuleRepository
                .findByFunctionUnitIdAndStartEventIdIsNotNull(functionUnitId).stream()
                .map(this::serializeMonitorRule)
                .toList();
        if (!monitors.isEmpty()) {
            payload.put("emailMonitors", monitors);
        }

        // Always write the key (even []) so rollback can distinguish "explicitly empty"
        // from legacy snapshots that omit the field entirely.
        List<Map<String, Object>> emailTemplates = emailTemplateRepository
                .findByFunctionUnitIdOrderByNameAsc(functionUnitId).stream()
                .map(this::serializeEmailTemplate)
                .toList();
        payload.put("emailTemplates", emailTemplates);

        payload.put("decisions", decisions.stream()
                .map(DecisionDefinition::getDmnXml)
                .filter(xml -> xml != null && !xml.isBlank())
                .toList());

        return payload;
    }

    @Transactional(readOnly = true)
    public byte[] exportFunctionUnit(Long functionUnitId) {
        functionUnitWorkspaceAccessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.VIEW);
        FunctionUnit functionUnit = functionUnitRepository.findById(functionUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("FunctionUnit", functionUnitId));

        List<TableDefinition> tables = tableDefinitionRepository.findByFunctionUnitIdWithFields(functionUnitId);
        List<FormDefinition> forms = formDefinitionRepository.findByFunctionUnitIdWithBindings(functionUnitId);
        List<ActionDefinition> actions = actionDefinitionRepository.findByFunctionUnitId(functionUnitId);
        List<DecisionDefinition> decisions = decisionDefinitionRepository.findByFunctionUnitId(functionUnitId);
        List<TableRelation> tableRelations = tableRelationRepository.findByFunctionUnitId(functionUnitId);
        Map<Long, String> tableIdToName = tables.stream()
                .collect(Collectors.toMap(TableDefinition::getId, TableDefinition::getTableName));
        Map<Long, List<FormStageBinding>> stageBindingsByFormId = new HashMap<>();
        for (FormDefinition form : forms) {
            stageBindingsByFormId.put(form.getId(), formStageBindingRepository.findByFormId(form.getId()));
        }
        ProcessDefinition processDefinition = functionUnit.getProcessDefinition();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            // Content used for checksum
            Map<String, byte[]> fileContents = new LinkedHashMap<>();

            // Build component manifest
            List<String> tableFiles = new ArrayList<>();
            List<String> formFiles = new ArrayList<>();
            List<String> actionFiles = new ArrayList<>();
            List<String> connectionFiles = new ArrayList<>();
            List<String> monitorFiles = new ArrayList<>();
            List<String> emailTemplateFiles = new ArrayList<>();

            // Export process definition — decode Base64 to raw XML
            String processFile = null;
            if (processDefinition != null) {
                String decodedBpmn = XmlEncodingUtil.smartDecode(processDefinition.getBpmnXml());
                processFile = "process/process.bpmn";
                byte[] processData = decodedBpmn.getBytes(StandardCharsets.UTF_8);
                fileContents.put(processFile, processData);
                addZipEntry(zos, processFile, processData);
            }

            // FR-B12: Automation (Activepieces) flows are NOT packaged any more. The BPMN carries
            // portable ap:flowKey references; the flow bodies migrate through the dedicated
            // Automation migration channel, and import validates the references resolve (FR-B13).

            // Export table definitions
            int tableIndex = 0;
            for (TableDefinition table : tables) {
                String fileName = "tables/table_" + tableIndex + ".json";
                byte[] data = objectMapper.writeValueAsBytes(serializeTable(table, tableIdToName));
                fileContents.put(fileName, data);
                addZipEntry(zos, fileName, data);
                tableFiles.add(fileName);
                tableIndex++;
            }

            // Export table relations
            if (!tableRelations.isEmpty()) {
                String relationsFile = "relations/table_relations.json";
                List<Map<String, Object>> relationPayload = tableRelations.stream()
                        .map(rel -> serializeTableRelation(rel, tableIdToName))
                        .toList();
                byte[] relationsData = objectMapper.writeValueAsBytes(relationPayload);
                fileContents.put(relationsFile, relationsData);
                addZipEntry(zos, relationsFile, relationsData);
            }

            // Export form definitions
            int formIndex = 0;
            for (FormDefinition form : forms) {
                String fileName = "forms/form_" + formIndex + ".json";
                byte[] data = objectMapper.writeValueAsBytes(
                        serializeForm(form, stageBindingsByFormId.getOrDefault(form.getId(), List.of())));
                fileContents.put(fileName, data);
                addZipEntry(zos, fileName, data);
                formFiles.add(fileName);
                formIndex++;
            }

            // Export referenced relation-table (rt_) structures: collect relationTableId from RELATED bindings,
            // serialize their structure by table_name so it can be re-created/remapped on import.
            List<Long> relationTableIds = forms.stream()
                    .filter(f -> f.getTableBindings() != null)
                    .flatMap(f -> f.getTableBindings().stream())
                    .map(FormTableBinding::getRelationTableId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            List<Map<String, Object>> relationTableStructures = relationTablePortability.exportByIds(relationTableIds);
            if (!relationTableStructures.isEmpty()) {
                String relTablesFile = "relation-tables/relation_tables.json";
                byte[] relTablesData = objectMapper.writeValueAsBytes(relationTableStructures);
                fileContents.put(relTablesFile, relTablesData);
                addZipEntry(zos, relTablesFile, relTablesData);
            }

            // Export "View Design": Main Table view configs (by table NAME so they survive id remap).
            String viewsFile = null;
            Map<Long, String> formIdToName = forms.stream()
                    .collect(Collectors.toMap(FormDefinition::getId, FormDefinition::getFormName));
            List<Map<String, Object>> mainTableViews =
                    mainTableViewPortability.export(functionUnitId, tableIdToName, formIdToName);
            if (!mainTableViews.isEmpty()) {
                viewsFile = "views/main_table_views.json";
                byte[] viewsData = objectMapper.writeValueAsBytes(mainTableViews);
                fileContents.put(viewsFile, viewsData);
                addZipEntry(zos, viewsFile, viewsData);
            }

            // Export link form components (referenced by sub-list-view linkForm columns via componentId;
            // linkedForm carried by NAME so it survives form id remap on import).
            List<LinkFormComponent> linkFormComponents =
                    linkFormComponentRepository.findByFunctionUnitIdOrderBySortOrderAsc(functionUnitId);
            if (!linkFormComponents.isEmpty()) {
                List<Map<String, Object>> lfcPayload = linkFormComponents.stream()
                        .map(c -> serializeLinkFormComponent(c, formIdToName))
                        .toList();
                String lfcFile = "link-form-components/link_form_components.json";
                byte[] lfcData = objectMapper.writeValueAsBytes(lfcPayload);
                fileContents.put(lfcFile, lfcData);
                addZipEntry(zos, lfcFile, lfcData);
            }

            // Export action definitions
            int actionIndex = 0;
            for (ActionDefinition action : actions) {
                String fileName = "actions/action_" + actionIndex + ".json";
                byte[] data = objectMapper.writeValueAsBytes(serializeAction(action));
                fileContents.put(fileName, data);
                addZipEntry(zos, fileName, data);
                actionFiles.add(fileName);
                actionIndex++;
            }

            // Export email connections
            int connectionIndex = 0;
            for (EmailConnection connection : emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(functionUnitId)) {
                String fileName = "connections/connection_" + connectionIndex + ".json";
                byte[] data = objectMapper.writeValueAsBytes(serializeConnection(connection));
                fileContents.put(fileName, data);
                addZipEntry(zos, fileName, data);
                connectionFiles.add(fileName);
                connectionIndex++;
            }

            // Export runtime email monitor bindings only (Start Event triggers; templates stay in DW).
            int monitorIndex = 0;
            for (EmailMonitorRule rule : emailMonitorRuleRepository
                    .findByFunctionUnitIdAndStartEventIdIsNotNull(functionUnitId)) {
                String fileName = "email-monitors/monitor_" + monitorIndex + ".json";
                byte[] data = objectMapper.writeValueAsBytes(serializeMonitorRule(rule));
                fileContents.put(fileName, data);
                addZipEntry(zos, fileName, data);
                monitorFiles.add(fileName);
                monitorIndex++;
            }

            // Export Send Email templates (BPMN emailTemplateId remaps via templateId)
            int templateIndex = 0;
            for (EmailTemplate template : emailTemplateRepository.findByFunctionUnitIdOrderByNameAsc(functionUnitId)) {
                String fileName = "email-templates/template_" + templateIndex + ".json";
                byte[] data = objectMapper.writeValueAsBytes(serializeEmailTemplate(template));
                fileContents.put(fileName, data);
                addZipEntry(zos, fileName, data);
                emailTemplateFiles.add(fileName);
                templateIndex++;
            }

            // Export decision definitions (DMN XML)
            List<String> decisionFiles = new ArrayList<>();
            int decisionIndex = 0;
            for (DecisionDefinition decision : decisions) {
                String fileName = "decisions/decision_" + decisionIndex + ".dmn";
                byte[] data = decision.getDmnXml() != null ?
                        decision.getDmnXml().getBytes(StandardCharsets.UTF_8) : new byte[0];
                fileContents.put(fileName, data);
                addZipEntry(zos, fileName, data);
                decisionFiles.add(fileName);
                decisionIndex++;
            }

            // Build manifest
            ExportManifest.IconInfo iconInfo = null;
            if (functionUnit.getIcon() != null) {
                iconInfo = ExportManifest.IconInfo.builder()
                        .name(functionUnit.getIcon().getName())
                        .category(functionUnit.getIcon().getCategory() != null ?
                                functionUnit.getIcon().getCategory().name() : null)
                        .color(null)
                        .svgContent(functionUnit.getIcon().getSvgContent())
                        .build();
            }

            ExportManifest manifest = ExportManifest.builder()
                    .name(functionUnit.getName())
                    .code(functionUnit.getCode()) // Use actual code field
                    .version(functionUnit.getCurrentVersion())
                    .description(functionUnit.getDisplayName())
                    .exportedAt(LocalDateTime.now())
                    .exportedBy(getCurrentOperator())
                    .platformVersion(platformVersion)
                    .minPlatformVersion("1.0.0")
                    .components(ExportManifest.Components.builder()
                            .process(processFile)
                            .tables(tableFiles)
                            .forms(formFiles)
                            .actions(actionFiles)
                            .decisions(decisionFiles)
                            .connections(connectionFiles)
                            .emailMonitors(monitorFiles)
                            .emailTemplates(emailTemplateFiles)
                            .mainTableViews(viewsFile)
                            .build())
                    .dependencies(new ArrayList<>())
                    .icon(iconInfo)
                    .build();

            ObjectMapper manifestMapper = new ObjectMapper();
            manifestMapper.registerModule(new JavaTimeModule());
            manifestMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            manifestMapper.enable(SerializationFeature.INDENT_OUTPUT);

            byte[] manifestData = manifestMapper.writeValueAsBytes(manifest);
            fileContents.put("manifest.json", manifestData);
            addZipEntry(zos, "manifest.json", manifestData);

            // Generate checksum
            String checksum = generateChecksum(fileContents);
            byte[] checksumData = checksum.getBytes(StandardCharsets.UTF_8);
            addZipEntry(zos, "checksum.sha256", checksumData);

            zos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new DeveloperBusinessException("SYS_EXPORT_ERROR", "Failed to export function unit: " + e.getMessage());
        }
    }

    /**
     * SHA-256 checksum of file content
     */
    private String generateChecksum(Map<String, byte[]> fileContents) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder checksumBuilder = new StringBuilder();

            for (Map.Entry<String, byte[]> entry : fileContents.entrySet()) {
                byte[] hash = digest.digest(entry.getValue());
                String hashHex = bytesToHex(hash);
                checksumBuilder.append(hashHex).append("  ").append(entry.getKey()).append("\n");
            }

            return checksumBuilder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new DeveloperBusinessException("SYS_CHECKSUM_ERROR", "Failed to generate checksum: " + e.getMessage());
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private void addZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private Map<String, Object> serializeTable(TableDefinition table, Map<Long, String> tableIdToName) {
        Map<String, Object> map = new HashMap<>();
        map.put("tableId", table.getId());
        map.put("tableName", table.getTableName());
        map.put("tableDisplayName", table.getTableDisplayName());
        map.put("tableType", table.getTableType().name());
        map.put("description", table.getDisplayName());
        if (table.getRequestIdConfig() != null) {
            map.put("requestIdConfig", table.getRequestIdConfig());
        }
        map.put("fields", table.getFieldDefinitions().stream()
                .map(field -> serializeField(field, tableIdToName))
                .toList());
        if (table.getForeignKeys() != null && !table.getForeignKeys().isEmpty()) {
            map.put("foreignKeys", table.getForeignKeys().stream()
                    .map(fk -> serializeForeignKey(fk, tableIdToName))
                    .toList());
        }
        return map;
    }

    private Map<String, Object> serializeForeignKey(ForeignKey foreignKey, Map<Long, String> tableIdToName) {
        Map<String, Object> map = new HashMap<>();
        map.put("fieldName", foreignKey.getFieldDefinition().getFieldName());
        Long refTableId = foreignKey.getRefTableDefinition() != null
                ? foreignKey.getRefTableDefinition().getId() : null;
        map.put("refTableName", refTableId != null ? tableIdToName.get(refTableId) : null);
        map.put("refFieldName", foreignKey.getRefFieldDefinition().getFieldName());
        map.put("onDelete", foreignKey.getOnDelete());
        map.put("onUpdate", foreignKey.getOnUpdate());
        return map;
    }

    private Map<String, Object> serializeTableRelation(TableRelation relation, Map<Long, String> tableIdToName) {
        Map<String, Object> map = new HashMap<>();
        map.put("sourceTableName", tableIdToName.get(relation.getSourceTableId()));
        map.put("sourceFieldName", relation.getSourceFieldName());
        map.put("relationType", relation.getRelationType());
        map.put("targetTableName", tableIdToName.get(relation.getTargetTableId()));
        map.put("targetFieldName", relation.getTargetFieldName());
        return map;
    }

    private Map<String, Object> serializeField(FieldDefinition field, Map<Long, String> tableIdToName) {
        Map<String, Object> map = new HashMap<>();
        map.put("fieldName", field.getFieldName());
        map.put("dataType", field.getDataType().name());
        map.put("length", field.getLength());
        map.put("precision", field.getPrecision());
        map.put("scale", field.getScale());
        map.put("nullable", field.getNullable());
        map.put("defaultValue", field.getDefaultValue());
        map.put("isPrimaryKey", field.getIsPrimaryKey());
        map.put("isUnique", field.getIsUnique());
        map.put("displayName", field.getDisplayName());
        map.put("sortOrder", field.getSortOrder());
        // FK/PK runtime metadata — refTableId exported by name so it survives id remap on import
        map.put("isForeignKey", field.getIsForeignKey());
        map.put("refTableName", field.getRefTableId() != null ? tableIdToName.get(field.getRefTableId()) : null);
        map.put("refPrimaryKeyFields", field.getRefPrimaryKeyFields());
        map.put("pkGenerationJson", field.getPkGenerationJson());
        map.put("fkDisplayMode", field.getFkDisplayMode());
        map.put("relationCardinality", field.getRelationCardinality());
        boolean computed = Boolean.TRUE.equals(field.getIsComputed());
        map.put("isComputed", computed);
        if (computed) {
            Map<String, Object> formula = field.getComputedFieldJson();
            if (formula == null || formula.isEmpty()) {
                throw new DeveloperBusinessException("COMPUTED_FIELD_EXPORT_INVALID",
                        "Field '" + field.getFieldName()
                                + "' is marked computed but has no usable formula JSON");
            }
            map.put("computedField", formula);
        } else {
            map.put("computedField", null);
        }
        return map;
    }

    private Map<String, Object> serializeForm(FormDefinition form, List<FormStageBinding> stageBindings) {
        Map<String, Object> map = new HashMap<>();
        map.put("formId", form.getId());
        map.put("formName", form.getFormName());
        map.put("formType", form.getFormType().name());
        map.put("scene", form.getScene() != null ? form.getScene().name() : FormScene.TASK.name());
        map.put("description", form.getDisplayName());
        map.put("boundTableName", form.getBoundTableName());
        map.put("showLiveValues", form.getShowLiveValues());
        map.put("fieldPermissions", form.getFieldPermissions());
        map.put("configJson", form.getConfigJson());
        if (form.getTableBindings() != null && !form.getTableBindings().isEmpty()) {
            map.put("tableBindings", form.getTableBindings().stream()
                    .map(this::serializeFormTableBinding)
                    .toList());
        }
        if (stageBindings != null && !stageBindings.isEmpty()) {
            map.put("stageBindings", stageBindings.stream().map(this::serializeFormStageBinding).toList());
        }
        return map;
    }

    private Map<String, Object> serializeFormTableBinding(FormTableBinding binding) {
        Map<String, Object> map = new HashMap<>();
        map.put("bindingId", binding.getId());
        map.put("bindingType", binding.getBindingType().name());
        map.put("bindingMode", binding.getBindingMode().name());
        map.put("tableName", binding.getTableName());
        map.put("relationTableId", binding.getRelationTableId());
        map.put("foreignKeyField", binding.getForeignKeyField());
        map.put("sortOrder", binding.getSortOrder());
        if (binding.getBindingLinkMode() != null) {
            map.put("bindingLinkMode", binding.getBindingLinkMode().name());
        }
        if (binding.getSubMode() != null) {
            map.put("subMode", binding.getSubMode().name());
        }
        // Sub-table list view config (FULL mode); subListViewId is re-resolved on import, so export the config inline
        if (binding.getSubListViewId() != null) {
            subTableViewConfigRepository.findByBindingId(binding.getId())
                    .ifPresent(config -> map.put("subTableViewConfig", serializeSubTableViewConfig(config)));
        }
        return map;
    }

    private Map<String, Object> serializeSubTableViewConfig(SubTableViewConfig config) {
        Map<String, Object> map = new HashMap<>();
        List<Map<String, Object>> fields = config.getViewFields() == null ? List.of()
                : config.getViewFields().stream().map(this::serializeSubTableViewField).toList();
        map.put("viewFields", fields);
        return map;
    }

    private Map<String, Object> serializeSubTableViewField(SubTableViewField field) {
        Map<String, Object> map = new HashMap<>();
        map.put("fieldName", field.getFieldName());
        map.put("displayLabel", field.getDisplayLabel());
        map.put("columnWidth", field.getColumnWidth());
        map.put("sortOrder", field.getSortOrder());
        map.put("visible", field.getVisible());
        return map;
    }

    private Map<String, Object> serializeFormStageBinding(FormStageBinding stageBinding) {
        Map<String, Object> map = new HashMap<>();
        map.put("stageId", stageBinding.getStageId());
        map.put("stageName", stageBinding.getStageName());
        map.put("readOnly", stageBinding.getReadOnly());
        // Without the scene the imported unit renders the To Do design in My Requests,
        // or nothing at all once the REQUEST binding is expected.
        map.put("scene", stageBinding.getScene() != null ? stageBinding.getScene().name() : FormScene.TASK.name());
        return map;
    }

    private Map<String, Object> serializeLinkFormComponent(LinkFormComponent component,
                                                           Map<Long, String> formIdToName) {
        Map<String, Object> map = new HashMap<>();
        map.put("componentId", component.getId());
        map.put("componentName", component.getComponentName());
        map.put("linkedFormId", component.getLinkedFormId());
        map.put("linkedFormName", component.getLinkedFormId() != null
                ? formIdToName.get(component.getLinkedFormId()) : null);
        map.put("displayField", component.getDisplayField());
        map.put("linkText", component.getLinkText());
        map.put("columnLabel", component.getColumnLabel());
        map.put("sortOrder", component.getSortOrder());
        map.put("configJson", component.getConfigJson());
        return map;
    }

    private Map<String, Object> serializeAction(ActionDefinition action) {
        Map<String, Object> map = new HashMap<>();
        map.put("actionId", action.getId());
        map.put("actionName", action.getActionName());
        map.put("actionType", action.getActionType().name());
        map.put("configJson", action.getConfigJson());
        map.put("icon", action.getIcon());
        map.put("buttonColor", action.getButtonColor());
        map.put("description", action.getDisplayName());
        map.put("isDefault", action.getIsDefault());
        return map;
    }

    private Map<String, Object> serializeConnection(EmailConnection connection) {
        Map<String, Object> map = new HashMap<>();
        map.put("connectionId", connection.getId());
        map.put("connectionUid", connection.getConnectionUid());
        map.put("name", connection.getName());
        map.put("connectionType", connection.getConnectionType().name());
        map.put("host", connection.getHost());
        map.put("port", connection.getPort());
        map.put("username", connection.getUsername());
        map.put("credentialEncrypted", connection.getCredentialEncrypted());
        map.put("fromEmail", connection.getFromEmail());
        map.put("fromName", connection.getFromName());
        map.put("useTls", connection.getUseTls());
        map.put("enabled", connection.getEnabled());
        map.put("direction", connection.getDirection() != null ? connection.getDirection().name() : "OUTBOUND");
        map.put("oauthProvider", connection.getOauthProvider() != null ? connection.getOauthProvider().name() : null);
        map.put("oauthRefreshTokenEncrypted", connection.getOauthRefreshTokenEncrypted());
        map.put("oauthAccessTokenEncrypted", connection.getOauthAccessTokenEncrypted());
        map.put("tokenExpiresAt", connection.getTokenExpiresAt() != null ? connection.getTokenExpiresAt().toString() : null);
        map.put("mailboxAddress", connection.getMailboxAddress());
        map.put("imapHost", connection.getImapHost());
        map.put("imapPort", connection.getImapPort());
        map.put("imapUseSsl", connection.getImapUseSsl());
        map.put("oauthScopes", connection.getOauthScopes());
        return map;
    }

    private Map<String, Object> serializeEmailTemplate(EmailTemplate template) {
        Map<String, Object> map = new HashMap<>();
        map.put("templateId", template.getId());
        map.put("name", template.getName());
        map.put("subject", template.getSubject());
        map.put("bodyHtml", template.getBodyHtml());
        map.put("enabled", template.getEnabled());
        return map;
    }

    private Map<String, Object> serializeMonitorRule(EmailMonitorRule rule) {
        Map<String, Object> map = new HashMap<>();
        map.put("ruleUid", rule.getRuleUid());
        map.put("name", rule.getName());
        map.put("enabled", rule.getEnabled());
        map.put("connectionUid", rule.getConnectionUid());
        map.put("sourceRuleId", rule.getSourceRuleId());
        map.put("processDefinitionKey", rule.getProcessDefinitionKey());
        map.put("startEventId", rule.getStartEventId());
        map.put("folderLabel", rule.getFolderLabel());
        map.put("filterFrom", rule.getFilterFrom());
        map.put("filterSubject", rule.getFilterSubject());
        map.put("actionType", rule.getActionType() != null ? rule.getActionType().name() : "START_PROCESS");
        map.put("targetFormId", rule.getTargetFormId());
        map.put("targetBindingId", rule.getTargetBindingId());
        map.put("systemInitiatorUserId", rule.getSystemInitiatorUserId());
        map.put("extractionRules", rule.getExtractionRules());
        map.put("correlation", rule.getCorrelation());
        map.put("pollIntervalSeconds", rule.getPollIntervalSeconds());
        map.put("reviewOnMissing", rule.getReviewOnMissing());
        return map;
    }
}
