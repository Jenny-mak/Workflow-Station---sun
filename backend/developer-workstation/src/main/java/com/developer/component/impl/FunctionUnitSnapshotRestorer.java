package com.developer.component.impl;

import com.developer.dto.ValidationResult;
import com.developer.entity.ActionDefinition;
import com.developer.entity.DecisionDefinition;
import com.developer.entity.FieldDefinition;
import com.developer.entity.FormDefinition;
import com.developer.entity.FunctionUnit;
import com.developer.entity.ProcessDefinition;
import com.developer.entity.TableDefinition;
import com.developer.enums.ActionType;
import com.developer.enums.DataType;
import com.developer.enums.FormType;
import com.developer.enums.TableType;
import com.developer.exception.DeveloperBusinessException;
import com.developer.exception.ResourceNotFoundException;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.ProcessDefinitionRepository;
import com.developer.service.MainTableViewService;
import com.developer.util.BpmnIdRewriter;
import com.developer.util.BpmnLastTaskAssigneeTopologyValidator;
import com.developer.util.BpmnProcessIdRewriter;
import com.developer.util.DeveloperWorkstationSequenceSynchronizer;
import com.developer.util.XmlEncodingUtil;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Restores a Function Unit from {@code dw_versions.snapshot_data}.
 * Export-format snapshots (schema v2) reuse {@link FunctionUnitImportWriter} — same path as ZIP import.
 * Legacy snapshots (pre-v2 keys) are still supported for older version rows.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FunctionUnitSnapshotRestorer {

    private final FunctionUnitImportWriter importWriter;
    private final EmailMonitorRulePortability emailMonitorRulePortability;
    private final FormDefinitionRepository formDefinitionRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final ProcessBpmnStaleIdFixer staleIdFixer;
    private final RelationTableStructurePortability relationTablePortability;
    private final MainTableViewPortability mainTableViewPortability;
    private final MainTableViewService mainTableViewService;
    private final EntityManager entityManager;
    private final DeveloperWorkstationSequenceSynchronizer sequenceSynchronizer;
    private final FormTableBindingRestorer formTableBindingRestorer;

    public static boolean isExportFormatSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return false;
        }
        Object schema = snapshot.get("snapshotSchemaVersion");
        if (schema instanceof Number number && number.intValue() >= FunctionUnitExporter.VERSION_SNAPSHOT_SCHEMA) {
            return true;
        }
        return snapshot.containsKey("tables");
    }

    public void restore(FunctionUnit functionUnit, Map<String, Object> snapshot) {
        if (isExportFormatSnapshot(snapshot)) {
            restoreExportFormat(functionUnit, snapshot);
        } else {
            restoreLegacy(functionUnit, snapshot);
        }
    }

    @SuppressWarnings("unchecked")
    private void restoreExportFormat(FunctionUnit functionUnit, Map<String, Object> snapshot) {
        if (snapshot.containsKey("description")) {
            functionUnit.setDisplayName((String) snapshot.get("description"));
        }

        Map<Long, Long> tableIdMapping = new HashMap<>();
        Map<String, Long> importedTableNameToId = new HashMap<>();
        Map<String, Map<String, FieldDefinition>> importedFieldLookup = new HashMap<>();

        if (snapshot.containsKey("tables")) {
            List<Map<String, Object>> tables = (List<Map<String, Object>>) snapshot.get("tables");
            for (Map<String, Object> tableData : tables) {
                TableDefinition table = importWriter.importTable(functionUnit, tableData);
                importWriter.recordSourceIdMapping(tableData.get("tableId"), table.getId(), tableIdMapping);
                importedTableNameToId.put(table.getTableName(), table.getId());
                Map<String, FieldDefinition> fieldByName = new HashMap<>();
                for (FieldDefinition field : table.getFieldDefinitions()) {
                    fieldByName.put(field.getFieldName(), field);
                }
                importedFieldLookup.put(table.getTableName(), fieldByName);
            }
            importWriter.importForeignKeys(tables, importedTableNameToId, importedFieldLookup);
            importWriter.importFieldRefMetadata(tables, importedTableNameToId);
        }

        if (snapshot.containsKey("tableRelations")) {
            List<Map<String, Object>> tableRelations =
                    (List<Map<String, Object>>) snapshot.get("tableRelations");
            importWriter.importTableRelations(functionUnit, tableRelations, importedTableNameToId);
        }

        Map<Long, Long> relationTableIdMapping = importRelationTables(snapshot);

        Map<Long, Long> formIdMapping = new HashMap<>();
        Map<String, Long> importedFormNameToId = new HashMap<>();
        List<Map<String, Object>> formDataList = new ArrayList<>();
        if (snapshot.containsKey("forms")) {
            List<Map<String, Object>> forms = (List<Map<String, Object>>) snapshot.get("forms");
            formDataList.addAll(forms);
            for (Map<String, Object> formData : forms) {
                FormDefinition form = importWriter.importFormShell(functionUnit, formData, importedTableNameToId);
                importWriter.recordSourceIdMapping(formData.get("formId"), form.getId(), formIdMapping);
                importedFormNameToId.put(form.getFormName(), form.getId());
            }
        }

        // Runs after the form shells above because a view's detail form is carried by name and
        // can only be resolved once those forms exist (mirrors FunctionUnitImporter's ordering).
        if (snapshot.containsKey("mainTableViews")) {
            List<Map<String, Object>> mainTableViews =
                    (List<Map<String, Object>>) snapshot.get("mainTableViews");
            mainTableViewPortability.importAll(
                    mainTableViews, functionUnit, importedTableNameToId, importedFormNameToId);
        }
        mainTableViewService.seedDefaultViewsForFunctionUnit(functionUnit.getId());

        Map<Long, Long> componentIdMapping = importLinkFormComponents(functionUnit, snapshot,
                importedFormNameToId, formIdMapping);

        Map<Long, Long> bindingIdMapping = new HashMap<>();
        for (Map<String, Object> formData : formDataList) {
            if (!(formData.get("formId") instanceof Number sourceFormId)) {
                continue;
            }
            Long newFormId = formIdMapping.get(sourceFormId.longValue());
            if (newFormId == null) {
                continue;
            }
            FormDefinition form = formDefinitionRepository.findById(newFormId)
                    .orElseThrow(() -> new ResourceNotFoundException("FormDefinition", newFormId));
            Map<Long, Long> perFormBindings = importWriter.finalizeFormImport(
                    form, formData, importedTableNameToId, relationTableIdMapping, formIdMapping, componentIdMapping);
            bindingIdMapping.putAll(perFormBindings);
        }

        Map<Long, Long> actionIdMapping = importActions(functionUnit, snapshot);

        if (snapshot.containsKey("decisions")) {
            List<String> decisions = (List<String>) snapshot.get("decisions");
            for (String dmnXml : decisions) {
                importWriter.importDecision(functionUnit, dmnXml);
            }
        }

        Map<Long, Long> connectionIdMapping = new HashMap<>();
        Map<String, String> connectionUidMapping = new HashMap<>();
        if (snapshot.containsKey("connections")) {
            List<Map<String, Object>> connections = (List<Map<String, Object>>) snapshot.get("connections");
            for (Map<String, Object> connectionData : connections) {
                var connection = importWriter.importEmailConnection(functionUnit, connectionData);
                importWriter.recordSourceIdMapping(connectionData.get("connectionId"), connection.getId(),
                        connectionIdMapping);
                importWriter.recordConnectionUidMapping(connectionData.get("connectionUid"),
                        connection.getConnectionUid(), connectionUidMapping);
            }
        }

        Map<Long, Long> emailTemplateIdMapping = new HashMap<>();
        if (snapshot.containsKey("emailTemplates")) {
            List<Map<String, Object>> emailTemplates =
                    (List<Map<String, Object>>) snapshot.get("emailTemplates");
            for (Map<String, Object> templateData : emailTemplates) {
                var template = importWriter.importEmailTemplate(functionUnit, templateData);
                importWriter.recordSourceIdMapping(templateData.get("templateId"), template.getId(),
                        emailTemplateIdMapping);
            }
        }

        if (snapshot.containsKey("emailMonitors")) {
            List<Map<String, Object>> monitors = (List<Map<String, Object>>) snapshot.get("emailMonitors");
            emailMonitorRulePortability.importAll(
                    functionUnit,
                    monitors,
                    EmailMonitorRulePortability.MonitorImportMaps.of(
                            formIdMapping, bindingIdMapping, connectionUidMapping));
        }

        restoreProcess(functionUnit, snapshot, tableIdMapping, formIdMapping, actionIdMapping,
                importedTableNameToId, importedFormNameToId, connectionIdMapping, emailTemplateIdMapping,
                connectionUidMapping);

        formTableBindingRestorer.repairFunctionUnitForms(functionUnit.getId());

        entityManager.flush();
        sequenceSynchronizer.synchronizeAllInTransaction();
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Long> importRelationTables(Map<String, Object> snapshot) {
        Map<Long, Long> relationTableIdMapping = new HashMap<>();
        if (!snapshot.containsKey("relationTables")) {
            return relationTableIdMapping;
        }
        List<Map<String, Object>> relationTables =
                (List<Map<String, Object>>) snapshot.get("relationTables");
        Map<String, Long> rtNameToId = relationTablePortability.importAll(relationTables, currentOperator());
        for (Map<String, Object> rt : relationTables) {
            Object srcId = rt.get("relationTableId");
            String rtName = (String) rt.get("tableName");
            if (srcId instanceof Number srcNum && rtName != null && rtNameToId.containsKey(rtName)) {
                relationTableIdMapping.put(srcNum.longValue(), rtNameToId.get(rtName));
            }
        }
        return relationTableIdMapping;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Long> importLinkFormComponents(FunctionUnit functionUnit,
                                                     Map<String, Object> snapshot,
                                                     Map<String, Long> importedFormNameToId,
                                                     Map<Long, Long> formIdMapping) {
        Map<Long, Long> componentIdMapping = new HashMap<>();
        if (!snapshot.containsKey("linkFormComponents")) {
            return componentIdMapping;
        }
        List<Map<String, Object>> linkFormComponents =
                (List<Map<String, Object>>) snapshot.get("linkFormComponents");
        for (Map<String, Object> componentData : linkFormComponents) {
            var component = importWriter.importLinkFormComponent(
                    functionUnit, componentData, importedFormNameToId, formIdMapping);
            if (component != null) {
                importWriter.recordSourceIdMapping(
                        componentData.get("componentId"), component.getId(), componentIdMapping);
            }
        }
        return componentIdMapping;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Long> importActions(FunctionUnit functionUnit, Map<String, Object> snapshot) {
        Map<Long, Long> actionIdMapping = new HashMap<>();
        if (!snapshot.containsKey("actions")) {
            return actionIdMapping;
        }
        List<Map<String, Object>> actions = (List<Map<String, Object>>) snapshot.get("actions");
        for (Map<String, Object> actionData : actions) {
            var action = importWriter.importAction(functionUnit, actionData);
            importWriter.recordSourceIdMapping(actionData.get("actionId"), action.getId(), actionIdMapping);
        }
        return actionIdMapping;
    }

    private void restoreProcess(FunctionUnit functionUnit,
                                Map<String, Object> snapshot,
                                Map<Long, Long> tableIdMapping,
                                Map<Long, Long> formIdMapping,
                                Map<Long, Long> actionIdMapping,
                                Map<String, Long> importedTableNameToId,
                                Map<String, Long> importedFormNameToId,
                                Map<Long, Long> connectionIdMapping,
                                Map<Long, Long> emailTemplateIdMapping,
                                Map<String, String> connectionUidMapping) {
        String bpmnXml = resolveProcessXml(snapshot);
        if (bpmnXml == null || bpmnXml.isBlank()) {
            return;
        }
        String rewrittenBpmn = BpmnIdRewriter.rewrite(
                bpmnXml,
                tableIdMapping,
                formIdMapping,
                actionIdMapping,
                importedTableNameToId,
                importedFormNameToId,
                Map.of(),
                connectionIdMapping,
                emailTemplateIdMapping,
                connectionUidMapping);
        rewrittenBpmn = staleIdFixer.fixStaleIds(functionUnit.getId(), XmlEncodingUtil.smartDecode(rewrittenBpmn));
        rewrittenBpmn = BpmnProcessIdRewriter.rewriteToFunctionUnitCode(rewrittenBpmn, functionUnit.getCode());
        assertLastTaskAssigneeTopologyOrThrow(XmlEncodingUtil.smartDecode(rewrittenBpmn));

        ProcessDefinition existing = functionUnit.getProcessDefinition();
        if (existing != null) {
            existing.setBpmnXml(XmlEncodingUtil.encode(rewrittenBpmn));
            processDefinitionRepository.save(existing);
            return;
        }
        ProcessDefinition process = ProcessDefinition.builder()
                .functionUnit(functionUnit)
                .functionUnitVersionId(functionUnit.getId())
                .bpmnXml(XmlEncodingUtil.encode(rewrittenBpmn))
                .build();
        process = processDefinitionRepository.save(process);
        functionUnit.setProcessDefinition(process);
    }

    private static String resolveProcessXml(Map<String, Object> snapshot) {
        if (snapshot.get("process") instanceof String process) {
            return process;
        }
        if (snapshot.get("processXml") instanceof String processXml) {
            return XmlEncodingUtil.smartDecode(processXml);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void restoreLegacy(FunctionUnit functionUnit, Map<String, Object> snapshot) {
        if (snapshot.containsKey("description")) {
            functionUnit.setDisplayName((String) snapshot.get("description"));
        }

        if (snapshot.containsKey("processXml")) {
            String processXml = (String) snapshot.get("processXml");
            if (processXml != null && functionUnit.getProcessDefinition() != null) {
                functionUnit.getProcessDefinition().setBpmnXml(processXml);
            }
        }

        if (snapshot.containsKey("tableDefinitions")) {
            functionUnit.getTableDefinitions().clear();
            List<Map<String, Object>> tableSnapshots = (List<Map<String, Object>>) snapshot.get("tableDefinitions");
            if (tableSnapshots != null) {
                for (Map<String, Object> tableSnap : tableSnapshots) {
                    functionUnit.getTableDefinitions().add(buildLegacyTable(functionUnit, tableSnap));
                }
            }
        }

        if (snapshot.containsKey("formDefinitions")) {
            functionUnit.getFormDefinitions().clear();
            List<Map<String, Object>> formSnapshots = (List<Map<String, Object>>) snapshot.get("formDefinitions");
            if (formSnapshots != null) {
                for (Map<String, Object> formSnap : formSnapshots) {
                    functionUnit.getFormDefinitions().add(buildLegacyForm(functionUnit, formSnap));
                }
            }
        }

        if (snapshot.containsKey("actionDefinitions")) {
            functionUnit.getActionDefinitions().clear();
            List<Map<String, Object>> actionSnapshots =
                    (List<Map<String, Object>>) snapshot.get("actionDefinitions");
            if (actionSnapshots != null) {
                for (Map<String, Object> actionSnap : actionSnapshots) {
                    functionUnit.getActionDefinitions().add(buildLegacyAction(functionUnit, actionSnap));
                }
            }
        }

        if (snapshot.containsKey("decisionDefinitions")) {
            functionUnit.getDecisionDefinitions().clear();
            List<Map<String, Object>> decisionSnapshots =
                    (List<Map<String, Object>>) snapshot.get("decisionDefinitions");
            if (decisionSnapshots != null) {
                for (Map<String, Object> decisionSnap : decisionSnapshots) {
                    functionUnit.getDecisionDefinitions().add(buildLegacyDecision(functionUnit, decisionSnap));
                }
            }
        }

        if (functionUnit.getProcessDefinition() != null
                && functionUnit.getProcessDefinition().getBpmnXml() != null) {
            String decoded = XmlEncodingUtil.smartDecode(functionUnit.getProcessDefinition().getBpmnXml());
            String fixed = staleIdFixer.fixStaleIds(functionUnit.getId(), decoded);
            functionUnit.getProcessDefinition().setBpmnXml(XmlEncodingUtil.encode(fixed));
        }

        restoreLegacyMainTableViews(functionUnit, snapshot);
        formTableBindingRestorer.repairFunctionUnitForms(functionUnit.getId());
    }

    @SuppressWarnings("unchecked")
    private void restoreLegacyMainTableViews(FunctionUnit functionUnit, Map<String, Object> snapshot) {
        Object viewsObj = snapshot.get("mainTableViews");
        if (!(viewsObj instanceof List<?> views) || views.isEmpty()) {
            return;
        }
        Map<String, Long> nameToId = new HashMap<>();
        for (TableDefinition table : functionUnit.getTableDefinitions()) {
            nameToId.put(table.getTableName(), table.getId());
        }
        mainTableViewPortability.importAll((List<Map<String, Object>>) viewsObj, functionUnit, nameToId);
        mainTableViewService.seedDefaultViewsForFunctionUnit(functionUnit.getId());
    }

    @SuppressWarnings("unchecked")
    private static TableDefinition buildLegacyTable(FunctionUnit functionUnit, Map<String, Object> tableSnap) {
        TableDefinition table = TableDefinition.builder()
                .functionUnit(functionUnit)
                .tableName((String) tableSnap.get("tableName"))
                .tableType(tableSnap.get("tableType") != null
                        ? TableType.valueOf((String) tableSnap.get("tableType")) : null)
                .tableDisplayName((String) tableSnap.get("tableDisplayName"))
                .displayName((String) tableSnap.get("description"))
                .build();
        List<Map<String, Object>> fieldSnapshots = (List<Map<String, Object>>) tableSnap.get("fieldDefinitions");
        if (fieldSnapshots != null) {
            for (Map<String, Object> fieldSnap : fieldSnapshots) {
                table.getFieldDefinitions().add(buildLegacyField(table, fieldSnap));
            }
        }
        return table;
    }

    private static FieldDefinition buildLegacyField(TableDefinition table, Map<String, Object> fieldSnap) {
        rejectComputedOnLegacyPath(fieldSnap);
        return FieldDefinition.builder()
                .tableDefinition(table)
                .fieldName((String) fieldSnap.get("fieldName"))
                .dataType(fieldSnap.get("dataType") != null
                        ? DataType.valueOf((String) fieldSnap.get("dataType")) : null)
                .length(fieldSnap.get("length") != null ? ((Number) fieldSnap.get("length")).intValue() : null)
                .precision(fieldSnap.get("precision") != null
                        ? ((Number) fieldSnap.get("precision")).intValue() : null)
                .scale(fieldSnap.get("scale") != null ? ((Number) fieldSnap.get("scale")).intValue() : null)
                .nullable(fieldSnap.get("nullable") != null ? (Boolean) fieldSnap.get("nullable") : true)
                .defaultValue((String) fieldSnap.get("defaultValue"))
                .isPrimaryKey(fieldSnap.get("isPrimaryKey") != null
                        ? (Boolean) fieldSnap.get("isPrimaryKey") : false)
                .isUnique(fieldSnap.get("isUnique") != null ? (Boolean) fieldSnap.get("isUnique") : false)
                .displayName((String) fieldSnap.get("displayName"))
                .sortOrder(fieldSnap.get("sortOrder") != null ? ((Number) fieldSnap.get("sortOrder")).intValue() : 0)
                .build();
    }

    /**
     * Pre-v2 snapshots never carried computed fields. Restoring one that claims to must fail
     * rather than persist a formula through this incomplete path (no AST remap, no validator).
     * Re-publish the FU so rollback uses the export-format snapshot (schema v2 / {@code tables}).
     */
    static void rejectComputedOnLegacyPath(Map<String, Object> fieldSnap) {
        if (fieldSnap == null) {
            return;
        }
        if (Boolean.TRUE.equals(fieldSnap.get("isComputed")) || fieldSnap.get("computedField") != null) {
            throw new DeveloperBusinessException("COMPUTED_FIELD_LEGACY_PATH",
                    "Legacy snapshots cannot restore computed fields; publish or re-export the Function Unit first");
        }
    }

    @SuppressWarnings("unchecked")
    private static FormDefinition buildLegacyForm(FunctionUnit functionUnit, Map<String, Object> formSnap) {
        return FormDefinition.builder()
                .functionUnit(functionUnit)
                .formName((String) formSnap.get("formName"))
                .formType(formSnap.get("formType") != null
                        ? FormType.valueOf((String) formSnap.get("formType")) : null)
                .configJson(formSnap.get("configJson") != null
                        ? (Map<String, Object>) formSnap.get("configJson") : null)
                .displayName((String) formSnap.get("description"))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static ActionDefinition buildLegacyAction(FunctionUnit functionUnit, Map<String, Object> actionSnap) {
        return ActionDefinition.builder()
                .functionUnit(functionUnit)
                .actionName((String) actionSnap.get("actionName"))
                .actionType(actionSnap.get("actionType") != null
                        ? ActionType.valueOf((String) actionSnap.get("actionType")) : null)
                .configJson(actionSnap.get("configJson") != null
                        ? (Map<String, Object>) actionSnap.get("configJson") : null)
                .icon((String) actionSnap.get("icon"))
                .buttonColor((String) actionSnap.get("buttonColor"))
                .displayName((String) actionSnap.get("description"))
                .isDefault(actionSnap.get("isDefault") != null ? (Boolean) actionSnap.get("isDefault") : false)
                .build();
    }

    private static DecisionDefinition buildLegacyDecision(FunctionUnit functionUnit, Map<String, Object> decisionSnap) {
        return DecisionDefinition.builder()
                .functionUnit(functionUnit)
                .decisionKey((String) decisionSnap.get("decisionKey"))
                .decisionName((String) decisionSnap.get("decisionName"))
                .dmnXml((String) decisionSnap.get("dmnXml"))
                .hitPolicy((String) decisionSnap.get("hitPolicy"))
                .description((String) decisionSnap.get("description"))
                .build();
    }

    private void assertLastTaskAssigneeTopologyOrThrow(String bpmnXml) {
        if (bpmnXml == null || bpmnXml.isBlank()) {
            return;
        }
        ValidationResult topo = BpmnLastTaskAssigneeTopologyValidator.validate(bpmnXml);
        if (!topo.isValid()) {
            String detail = topo.getErrors().stream()
                    .map(ValidationResult.ValidationError::getMessage)
                    .collect(Collectors.joining("; "));
            throw new DeveloperBusinessException("LAST_TASK_ANCHOR_TOPOLOGY", detail);
        }
    }

    private String currentOperator() {
        try {
            return com.platform.security.util.SecurityContextUtils.getCurrentUsername().orElse("system");
        } catch (Exception e) {
            return "system";
        }
    }
}
