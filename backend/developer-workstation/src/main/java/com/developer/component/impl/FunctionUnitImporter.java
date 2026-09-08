package com.developer.component.impl;

import com.developer.client.AdminCenterAutomationFlowClient;
import com.developer.component.VersionComponent;
import com.developer.dto.ValidationResult;
import com.developer.entity.FieldDefinition;
import com.developer.entity.FormDefinition;
import com.developer.entity.FunctionUnit;
import com.developer.entity.ProcessDefinition;
import com.developer.entity.TableDefinition;
import com.developer.exception.DeveloperBusinessException;
import com.developer.exception.ResourceNotFoundException;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.FunctionUnitRepository;
import com.developer.repository.ProcessDefinitionRepository;
import com.developer.service.MainTableViewService;
import com.developer.util.BpmnIdRewriter;
import com.developer.util.BpmnLastTaskAssigneeTopologyValidator;
import com.developer.util.BpmnProcessIdRewriter;
import com.developer.util.BpmnServiceTaskFlowRefs;
import com.developer.util.DeveloperWorkstationSequenceSynchronizer;
import com.developer.util.XmlEncodingUtil;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.security.SecureRandom;

/**
 * 功能单元导入编排协作类。
 * 负责导入主流程编排（创建功能单元、落库各组件、重写 BPMN、序列同步），
 * 以及命名/编码冲突解析、唯一编码生成、最后任务负责人拓扑校验。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FunctionUnitImporter {

    private final FunctionUnitRepository functionUnitRepository;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final FormDefinitionRepository formDefinitionRepository;
    private final EntityManager entityManager;
    private final DeveloperWorkstationSequenceSynchronizer sequenceSynchronizer;
    private final ExportImportPackageParser packageParser;
    private final FunctionUnitImportWriter importWriter;
    private final EmailMonitorRulePortability emailMonitorRulePortability;
    private final ProcessBpmnStaleIdFixer staleIdFixer;
    private final VersionComponent versionComponent;
    private final RelationTableStructurePortability relationTablePortability;
    private final MainTableViewPortability mainTableViewPortability;
    private final MainTableViewService mainTableViewService;
    private final AdminCenterAutomationFlowClient automationFlowClient;

    /**
     * 导入功能单元。无冲突策略选项：
     * <ul>
     *   <li>同名功能单元不存在 → 新建一个功能单元。</li>
     *   <li>同名功能单元已存在 → 在其之上「加一个版本」：先把现有内容快照存入 dw_versions，
     *       再用导入包内容替换现有功能单元的子内容，currentVersion 递增。</li>
     * </ul>
     *
     * @param file      导入包
     * @param changeLog 同名加版本时写入版本记录的变更说明，可空
     */
    @Transactional
    public Map<String, Object> importFunctionUnit(MultipartFile file, String changeLog) {
        sequenceSynchronizer.synchronizeAll();
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> packageData = packageParser.parseImportPackage(file);

        // Prefer manifest.json; fall back to legacy metadata.json
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = packageData.containsKey("manifest") ?
                (Map<String, Object>) packageData.get("manifest") :
                (Map<String, Object>) packageData.get("metadata");

        String name = (String) manifest.get("name");
        String code = (String) manifest.get("code");
        String version = (String) manifest.get("version");
        String description = (String) manifest.get("description");

        // FR-B12: FU packages no longer carry Automation flow bodies. Legacy packages exported
        // before the decoupling are still accepted — their automation-flows/ entries are ignored
        // (flows migrate via the DW Automation page's migration channel).
        if (packageData.containsKey("legacyAutomationFlowFiles")) {
            log.info("Import package carries legacy automation flow entries {}; ignored — flows "
                            + "migrate via the Automation migration channel, not inside FU packages (FR-B12)",
                    packageData.get("legacyAutomationFlowFiles"));
        }

        // FR-B13: validate FIRST that every automation flow the BPMN references resolves in this
        // environment (business key ap:flowKey or legacy ap:flowId, via admin-center /resolve).
        // A missing flow must abort the import before any function unit content is touched —
        // importing anyway would land a half-working unit whose service tasks fail at runtime.
        assertReferencedAutomationFlowsResolvable((String) packageData.get("process"));

        // Name exists → replace the existing unit's content with the imported package (snapshotting the
        // old content for rollback); the version number is NOT changed here — deploy/publish owns version
        // increments. Otherwise create a new function unit.
        FunctionUnit existing = functionUnitRepository.findByName(name).orElse(null);
        final boolean versioned = existing != null;
        FunctionUnit functionUnit;
        if (versioned) {
            functionUnit = existing;
            // Snapshot current content into dw_versions and clear it; currentVersion stays unchanged.
            versionComponent.snapshotAndClearForReimport(functionUnit, changeLog);
            functionUnit.setDisplayName(description);
            functionUnit = functionUnitRepository.save(functionUnit);
            // Re-sync sequences after the snapshot/clear writes before rebuilding content.
            sequenceSynchronizer.synchronizeAll();
            version = functionUnit.getCurrentVersion();
        } else {
            functionUnit = FunctionUnit.builder()
                    .name(name)
                    .code(resolveNewImportCode(name, code))
                    .displayName(description)
                    .currentVersion(version)
                    .deployedAt(Instant.now()) // Set deployed_at to avoid null constraint violation
                    .build();
            functionUnit = functionUnitRepository.save(functionUnit);
        }

        Map<Long, Long> tableIdMapping = new HashMap<>();
        Map<String, Long> importedTableNameToId = new HashMap<>();
        Map<String, Map<String, FieldDefinition>> importedFieldLookup = new HashMap<>();
        if (packageData.containsKey("tables")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tables = (List<Map<String, Object>>) packageData.get("tables");
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

        if (packageData.containsKey("tableRelations")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tableRelations = (List<Map<String, Object>>) packageData.get("tableRelations");
            importWriter.importTableRelations(functionUnit, tableRelations, importedTableNameToId);
        }

        // Import relation-table (rt_) structures and build source-rt-id → new-rt-id so RELATED bindings remap.
        Map<Long, Long> relationTableIdMapping = new HashMap<>();
        if (packageData.containsKey("relationTables")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> relationTables =
                    (List<Map<String, Object>>) packageData.get("relationTables");
            Map<String, Long> rtNameToId = relationTablePortability.importAll(relationTables, currentOperator());
            for (Map<String, Object> rt : relationTables) {
                Object srcId = rt.get("relationTableId");
                String rtName = (String) rt.get("tableName");
                if (srcId instanceof Number srcNum && rtName != null && rtNameToId.containsKey(rtName)) {
                    relationTableIdMapping.put(srcNum.longValue(), rtNameToId.get(rtName));
                }
            }
        }

        Map<Long, Long> formIdMapping = new HashMap<>();
        Map<String, Long> importedFormNameToId = new HashMap<>();
        List<Map<String, Object>> formDataList = new ArrayList<>();
        if (packageData.containsKey("forms")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> forms = (List<Map<String, Object>>) packageData.get("forms");
            formDataList.addAll(forms);
            for (Map<String, Object> formData : forms) {
                FormDefinition form = importWriter.importFormShell(functionUnit, formData, importedTableNameToId);
                importWriter.recordSourceIdMapping(formData.get("formId"), form.getId(), formIdMapping);
                importedFormNameToId.put(form.getFormName(), form.getId());
            }
        }

        // Import "View Design": recreate Main Table views, remapping mainTableName → new table id.
        // Runs after the form shells above because a view's detail form is carried by name and
        // can only be resolved once those forms exist.
        if (packageData.containsKey("mainTableViews")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mainTableViews =
                    (List<Map<String, Object>>) packageData.get("mainTableViews");
            mainTableViewPortability.importAll(
                    mainTableViews, functionUnit, importedTableNameToId, importedFormNameToId);
        }
        // Backfill per-table default views (MAIN + SUB). Older packages only carried the single MAIN
        // default (or none at all); this is idempotent and never overwrites an imported default.
        mainTableViewService.seedDefaultViewsForFunctionUnit(functionUnit.getId());

        // Import link form components between form shells (need form ids) and form finalization
        // (whose configJson remap consumes the componentId mapping for linkForm column references).
        Map<Long, Long> componentIdMapping = new HashMap<>();
        if (packageData.containsKey("linkFormComponents")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> linkFormComponents =
                    (List<Map<String, Object>>) packageData.get("linkFormComponents");
            for (Map<String, Object> componentData : linkFormComponents) {
                var component = importWriter.importLinkFormComponent(
                        functionUnit, componentData, importedFormNameToId, formIdMapping);
                if (component != null) {
                    importWriter.recordSourceIdMapping(componentData.get("componentId"), component.getId(), componentIdMapping);
                }
            }
        }

        Map<Long, Long> bindingIdMapping = new HashMap<>();
        for (Map<String, Object> formData : formDataList) {
            Object sourceFormIdObj = formData.get("formId");
            if (!(sourceFormIdObj instanceof Number sourceFormId)) {
                continue;
            }
            Long newFormId = formIdMapping.get(sourceFormId.longValue());
            if (newFormId == null) {
                continue;
            }
            FormDefinition form = formDefinitionRepository.findById(newFormId)
                    .orElseThrow(() -> new ResourceNotFoundException("FormDefinition", newFormId));
            bindingIdMapping.putAll(importWriter.finalizeFormImport(form, formData, importedTableNameToId,
                    relationTableIdMapping, formIdMapping, componentIdMapping));
        }

        Map<Long, Long> actionIdMapping = new HashMap<>();
        if (packageData.containsKey("actions")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> actions = (List<Map<String, Object>>) packageData.get("actions");
            for (Map<String, Object> actionData : actions) {
                var action = importWriter.importAction(functionUnit, actionData);
                importWriter.recordSourceIdMapping(actionData.get("actionId"), action.getId(), actionIdMapping);
            }
        }

        if (packageData.containsKey("decisions")) {
            @SuppressWarnings("unchecked")
            List<String> decisions = (List<String>) packageData.get("decisions");
            for (String dmnXml : decisions) {
                importWriter.importDecision(functionUnit, dmnXml);
            }
        }

        Map<Long, Long> connectionIdMapping = new HashMap<>();
        Map<String, String> connectionUidMapping = new HashMap<>();
        if (packageData.containsKey("connections")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> connections = (List<Map<String, Object>>) packageData.get("connections");
            for (Map<String, Object> connectionData : connections) {
                var connection = importWriter.importEmailConnection(functionUnit, connectionData);
                importWriter.recordSourceIdMapping(connectionData.get("connectionId"), connection.getId(),
                        connectionIdMapping);
                importWriter.recordConnectionUidMapping(connectionData.get("connectionUid"),
                        connection.getConnectionUid(), connectionUidMapping);
            }
        }

        Map<Long, Long> emailTemplateIdMapping = new HashMap<>();
        if (packageData.containsKey("emailTemplates")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> emailTemplates =
                    (List<Map<String, Object>>) packageData.get("emailTemplates");
            for (Map<String, Object> templateData : emailTemplates) {
                var template = importWriter.importEmailTemplate(functionUnit, templateData);
                importWriter.recordSourceIdMapping(templateData.get("templateId"), template.getId(),
                        emailTemplateIdMapping);
            }
        }

        if (packageData.containsKey("emailMonitors")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> monitors = (List<Map<String, Object>>) packageData.get("emailMonitors");
            emailMonitorRulePortability.importAll(
                    functionUnit,
                    monitors,
                    EmailMonitorRulePortability.MonitorImportMaps.of(
                            formIdMapping, bindingIdMapping, connectionUidMapping));
        }

        // Write process after tables/forms/actions/email import; rewrite old BPMN IDs (same as clone)
        if (packageData.containsKey("process")) {
            String bpmnXml = (String) packageData.get("process");
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
            // Name-based repair pass: actionIds / globalActionIds (and formId/subTableId) that were
            // ALREADY stale in the source package miss the id mapping above and would stay dangling —
            // the designer then shows Bind to Node with no node (global) or the raw id (node actions).
            // Re-resolve them by name against this unit.
            rewrittenBpmn = staleIdFixer.fixStaleIds(
                    functionUnit.getId(), XmlEncodingUtil.smartDecode(rewrittenBpmn));
            rewrittenBpmn = BpmnProcessIdRewriter.rewriteToFunctionUnitCode(rewrittenBpmn, functionUnit.getCode());
            assertLastTaskAssigneeTopologyOrThrow(XmlEncodingUtil.smartDecode(rewrittenBpmn));
            ProcessDefinition process = ProcessDefinition.builder()
                    .functionUnit(functionUnit)
                    .functionUnitVersionId(functionUnit.getId())
                    .bpmnXml(XmlEncodingUtil.encode(rewrittenBpmn))
                    .build();
            processDefinitionRepository.save(process);
        }

        // Flush this transaction's inserts first, then sync sequences IN-TRANSACTION so the
        // synchronizer sees the freshly-inserted rows. (synchronizeAll runs NOT_SUPPORTED on a
        // separate connection and cannot see uncommitted rows, which would leave sequences lagging
        // MAX(id) and cause PK conflicts on the next IDENTITY insert — e.g. adding a form binding.)
        entityManager.flush();
        sequenceSynchronizer.synchronizeAllInTransaction();

        result.put("status", "SUCCESS");
        result.put("functionUnitId", functionUnit.getId());
        result.put("name", functionUnit.getName());
        result.put("version", functionUnit.getCurrentVersion());
        result.put("versioned", versioned);
        return result;
    }

    /**
     * FR-B13 导入前校验：BPMN 引用的每个 Automation flow（{@code ap:flowKey} 业务键或
     * legacy {@code ap:flowId}）必须能经 admin-center {@code /resolve} 在本环境解析
     * （且已发布，FR-C05）。任一解析不到 → 显式失败并列出缺失清单；解析通道未配置
     * 而 BPMN 确有引用 → 同样显式失败（NFR-304 不静默）。
     */
    private void assertReferencedAutomationFlowsResolvable(String bpmnXml) {
        List<String> flowRefs = BpmnServiceTaskFlowRefs.extract(bpmnXml);
        if (flowRefs.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String flowRef : flowRefs) {
            // Channel unavailable / admin-center unreachable throws its own explicit
            // DeveloperBusinessException here — never silently skip the check.
            if (automationFlowClient.resolveFlow(flowRef).isEmpty()) {
                missing.add(flowRef);
            }
        }
        if (!missing.isEmpty()) {
            throw new DeveloperBusinessException("AP_FLOW_REF_MISSING",
                    "The package's BPMN references automation flow(s) that do not resolve in this "
                            + "environment (missing or unpublished): " + missing
                            + ". Import and publish the flow(s) via the Automation page's migration "
                            + "channel first, then re-import this function unit.");
        }
    }

    /**
     * Import/preview matches save/deploy: LAST_TASK_ASSIGNEE anchor requires single incoming flow.
     */
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

    /** Current operator for audit fields; falls back to "system" when unavailable. */
    private String currentOperator() {
        try {
            return com.platform.security.util.SecurityContextUtils.getCurrentUsername().orElse("system");
        } catch (Exception e) {
            return "system";
        }
    }

    /**
     * Resolve the code for a brand-new imported function unit (name is known not to exist).
     * Reuse the manifest code when it is free; otherwise generate a unique one from the name.
     */
    private String resolveNewImportCode(String name, String manifestCode) {
        String normalized = manifestCode != null && !manifestCode.isBlank() ? manifestCode : null;
        if (normalized != null && !functionUnitRepository.existsByCode(normalized)) {
            return normalized;
        }
        return generateImportCode(name);
    }

    /**
     * Generate unique code on import
     */
    private String generateImportCode(String functionUnitName) {
        String prefix = normalizeCodePrefix(functionUnitName);
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        SecureRandom random = new SecureRandom();
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";

        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder randomPart = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                randomPart.append(chars.charAt(random.nextInt(chars.length())));
            }
            String code = prefix + "-" + datePart + "-" + randomPart;
            if (!functionUnitRepository.existsByCode(code)) {
                return code;
            }
        }
        return prefix + "-" + datePart + "-" + (System.currentTimeMillis() % 1000000);
    }

    /**
     * Same prefix sanitization as FunctionUnit create (Flowable/BPMN XML Name + total length limits).
     */
    private String normalizeCodePrefix(String name) {
        final int maxPrefixLen = 34;

        if (name == null) {
            return "fu";
        }
        String raw = name.trim();
        if (raw.isEmpty()) {
            return "fu";
        }

        StringBuilder out = new StringBuilder();
        char prev = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            char mapped;
            if (Character.isLetterOrDigit(c)) {
                mapped = Character.toLowerCase(c);
            } else if (c == '_' || c == '-' || c == '.') {
                mapped = c;
            } else if (Character.isWhitespace(c)) {
                mapped = '-';
            } else {
                continue;
            }
            if ((mapped == '-' || mapped == '_' || mapped == '.') && mapped == prev) {
                continue;
            }
            out.append(mapped);
            prev = mapped;
            if (out.length() >= maxPrefixLen + 8) {
                break;
            }
        }

        String s = out.toString();
        s = s.replaceAll("^[-_.]+", "").replaceAll("[-_.]+$", "");
        if (s.isEmpty()) {
            return "fu";
        }

        char first = s.charAt(0);
        boolean firstOk = (first >= 'a' && first <= 'z') || first == '_';
        if (!firstOk) {
            s = "fu-" + s;
        }
        s = s.replaceAll("^[-_.]+", "");
        if (s.isEmpty()) {
            return "fu";
        }

        if (s.length() > maxPrefixLen) {
            s = s.substring(0, maxPrefixLen);
            s = s.replaceAll("[-_.]+$", "");
            if (s.isEmpty()) {
                return "fu";
            }
        }

        if (s.length() >= 3 && s.regionMatches(true, 0, "xml", 0, 3)) {
            s = "fu-" + s;
            if (s.length() > maxPrefixLen) {
                s = s.substring(0, maxPrefixLen).replaceAll("[-_.]+$", "");
                if (s.isEmpty()) {
                    return "fu";
                }
            }
        }

        return s;
    }
}
