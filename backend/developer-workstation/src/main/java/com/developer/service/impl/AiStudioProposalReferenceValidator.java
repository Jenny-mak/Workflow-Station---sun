package com.developer.service.impl;

import com.developer.dto.AiGeneratedData;
import com.developer.dto.AiValidationResult;
import com.developer.entity.EmailConnection;
import com.developer.entity.FieldDefinition;
import com.developer.entity.FormDefinition;
import com.developer.entity.TableDefinition;
import com.developer.enums.EmailConnectionDirection;
import com.developer.enums.TableType;
import com.developer.repository.EmailConnectionRepository;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.ProcessDefinitionRepository;
import com.developer.repository.TableDefinitionRepository;
import com.developer.util.BpmnServiceTaskScanner;
import com.developer.service.impl.AiOrgCatalogReader.BusinessUnitEntry;
import com.developer.service.impl.AiOrgCatalogReader.RoleEntry;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Studio 提案的功能单元感知引用校验。
 *
 * <p>{@code AiValidationService#validate(AiGeneratedData)} 只看提案自身，拿不到 FU；邮件三阶段
 * 的提案是 scoped 的，引用对象（连接、表单、主表字段）都在库里而不在提案里，所以在 Apply 前
 * 单独按 FU 校一遍。规则与设计器 Component 的写入规则一致，只是提前到写库前一次性报全。</p>
 */
@Component
public class AiStudioProposalReferenceValidator {

    /** 模板变量：{@code ${fieldName}}；带冒号的是子表/lookup 语法（bindingId 是库 id，模型不可知），不校验。 */
    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\$\\{([^}:]+)}");

    /** 只存在于 MAIN 表视图上的运行时系统字段（与前端 SYSTEM_VIEW_FIELDS 一致）。 */
    static final Set<String> SYSTEM_VIEW_FIELDS = Set.of("process_status", "start_time", "initiator", "current_step");

    private final EmailConnectionRepository emailConnectionRepository;
    private final FormDefinitionRepository formDefinitionRepository;
    private final TableDefinitionRepository tableDefinitionRepository;
    private final AiOrgCatalogReader orgCatalogReader;
    private final ProcessDefinitionRepository processDefinitionRepository;
    private final AiAutomationFlowCatalogReader automationFlowCatalogReader;

    public AiStudioProposalReferenceValidator(EmailConnectionRepository emailConnectionRepository,
                                              FormDefinitionRepository formDefinitionRepository,
                                              TableDefinitionRepository tableDefinitionRepository,
                                              AiOrgCatalogReader orgCatalogReader,
                                              ProcessDefinitionRepository processDefinitionRepository,
                                              AiAutomationFlowCatalogReader automationFlowCatalogReader) {
        this.emailConnectionRepository = emailConnectionRepository;
        this.formDefinitionRepository = formDefinitionRepository;
        this.tableDefinitionRepository = tableDefinitionRepository;
        this.orgCatalogReader = orgCatalogReader;
        this.processDefinitionRepository = processDefinitionRepository;
        this.automationFlowCatalogReader = automationFlowCatalogReader;
    }

    public AiValidationResult validate(Long functionUnitId, AiGeneratedData data) {
        AiValidationResult result = AiValidationResult.builder().build();
        if (data == null) return result;
        boolean hasMonitors = data.getEmailMonitorRules() != null && !data.getEmailMonitorRules().isEmpty();
        boolean hasTemplates = data.getEmailTemplates() != null && !data.getEmailTemplates().isEmpty();
        boolean hasViews = data.getMainTableViews() != null && !data.getMainTableViews().isEmpty();
        boolean hasBindings = data.getServiceTaskBindings() != null && !data.getServiceTaskBindings().isEmpty();
        if (!hasMonitors && !hasTemplates && !hasViews && !hasBindings) return result;

        Set<String> mainFields = hasMonitors || hasTemplates ? mainTableFieldNames(functionUnitId) : Set.of();
        if (hasMonitors) validateMonitorRules(functionUnitId, data, mainFields, result);
        if (hasTemplates) validateTemplateTokens(data, mainFields, result);
        if (hasViews) validateViews(functionUnitId, data, result);
        if (hasBindings) validateServiceTaskBindings(functionUnitId, data, result);
        return result;
    }

    /**
     * 绑定提案的 FU 感知校验：FU 必须已有流程定义；任务 id 必须在 BPMN 里；flowKey 在本环境必须存在
     * （跨 project 全局查，与引擎部署期解析同尺度）；键属于其它 workspace 或 flow 未发布只记 warning
     * （与设计器面板的"其它工作区 / 未发布"提示同语义）。
     */
    private void validateServiceTaskBindings(Long functionUnitId, AiGeneratedData data, AiValidationResult result) {
        List<Map<String, Object>> bindings = data.getServiceTaskBindings();
        var pd = processDefinitionRepository.findByFunctionUnitId(functionUnitId).orElse(null);
        if (pd == null || pd.getBpmnXml() == null || pd.getBpmnXml().isBlank()) {
            result.addError("REFERENCE_NOT_FOUND", "serviceTaskBindings",
                    "The function unit has no process definition; design the process before binding service tasks");
            return;
        }
        Set<String> taskIds = new HashSet<>();
        try {
            for (BpmnServiceTaskScanner.ServiceTaskInfo t : BpmnServiceTaskScanner.scan(pd.getBpmnXml())) {
                taskIds.add(t.id());
            }
        } catch (RuntimeException e) {
            result.addError("REFERENCE_INVALID", "serviceTaskBindings",
                    "The existing process definition could not be parsed: " + e.getMessage());
            return;
        }
        Set<String> workspaces = automationFlowCatalogReader.workspaceExternalIds(functionUnitId);
        for (int i = 0; i < bindings.size(); i++) {
            Map<String, Object> b = bindings.get(i);
            String path = "serviceTaskBindings[" + i + "]";
            String taskId = b.get("serviceTaskId") instanceof String s ? s.trim() : "";
            if (!taskId.isEmpty() && !taskIds.contains(taskId)) {
                result.addError("REFERENCE_NOT_FOUND", path + ".serviceTaskId",
                        "No bpmn:serviceTask with id '" + taskId + "' in the process; existing service tasks: " + taskIds);
            }
            String flowKey = b.get("flowKey") instanceof String s ? s.trim() : "";
            if (flowKey.isEmpty()) continue;
            var flow = automationFlowCatalogReader.findByKey(flowKey).orElse(null);
            if (flow == null) {
                result.addError("REFERENCE_NOT_FOUND", path + ".flowKey",
                        "No automation flow in this environment carries the business key '" + flowKey + "'");
                continue;
            }
            if (!workspaces.contains(flow.projectExternalId())) {
                result.addWarning("REFERENCE_INVALID", path + ".flowKey",
                        "Flow '" + flowKey + "' belongs to another workspace (" + flow.workspace()
                                + "); deployment still resolves it, but it cannot be edited from this unit's Automation page");
            }
            if (!flow.published()) {
                result.addWarning("REFERENCE_INVALID", path + ".flowKey",
                        "Flow '" + flowKey + "' is not published yet; deploying the process will fail until it is");
            }
        }
    }

    /**
     * 视图提案的 FU 感知校验：表存在且可建视图、字段/排序/过滤只引用该表字段（MAIN 表再加系统字段）、
     * 详情表单只允许 SUB 表且存在、访问规则的 BU/Role 存在且 Role 绑定在所选 BU 之一（设计器"准入角色"）。
     */
    @SuppressWarnings("unchecked")
    private void validateViews(Long functionUnitId, AiGeneratedData data, AiValidationResult result) {
        Map<String, TableDefinition> tablesByName = new HashMap<>();
        for (TableDefinition t : tableDefinitionRepository.findByFunctionUnitIdWithFields(functionUnitId)) {
            tablesByName.put(t.getTableName(), t);
        }
        Set<String> formNames = new HashSet<>();
        for (FormDefinition f : formDefinitionRepository.findByFunctionUnitId(functionUnitId)) {
            formNames.add(f.getFormName());
        }
        List<BusinessUnitEntry> catalog = null; // 只有出现访问规则才读目录

        List<Map<String, Object>> views = data.getMainTableViews();
        for (int i = 0; i < views.size(); i++) {
            Map<String, Object> v = views.get(i);
            String path = "mainTableViews[" + i + "]";
            String mainTableName = v.get("mainTableName") instanceof String s ? s.trim() : "";
            TableDefinition table = tablesByName.get(mainTableName);
            if (table == null) {
                result.addError("REFERENCE_NOT_FOUND", path + ".mainTableName",
                        "Table not found in this function unit: " + mainTableName);
                continue;
            }
            boolean isMain = table.getTableType() == TableType.MAIN;
            if (!isMain && table.getTableType() != TableType.SUB) {
                result.addError("REFERENCE_INVALID", path + ".mainTableName",
                        "Views can only be created for MAIN or SUB tables: " + mainTableName);
                continue;
            }
            Set<String> tableFields = new HashSet<>();
            if (table.getFieldDefinitions() != null) {
                for (FieldDefinition f : table.getFieldDefinitions()) {
                    if (f.getFieldName() != null) tableFields.add(f.getFieldName());
                }
            }

            if (v.get("fields") instanceof List<?> fields) {
                for (int j = 0; j < fields.size(); j++) {
                    if (fields.get(j) instanceof Map<?, ?> f) {
                        checkViewFieldRef(f.get("fieldName"), f.get("systemField"), isMain, tableFields,
                                mainTableName, path + ".fields[" + j + "]", result);
                    }
                }
            }
            if (v.get("sortConfig") instanceof List<?> sorts) {
                for (int j = 0; j < sorts.size(); j++) {
                    if (sorts.get(j) instanceof Map<?, ?> s) {
                        checkViewFieldRef(s.get("fieldName"), s.get("systemField"), isMain, tableFields,
                                mainTableName, path + ".sortConfig[" + j + "]", result);
                    }
                }
            }
            if (v.get("filterConfig") instanceof Map<?, ?> filter) {
                checkFilterRefs(filter, isMain, tableFields, mainTableName, path + ".filterConfig", result);
            }

            if (v.get("detailFormName") instanceof String formName && !formName.isBlank()) {
                if (isMain) {
                    result.addError("REFERENCE_INVALID", path + ".detailFormName",
                            "MAIN-table views open the request detail page and cannot bind a detail form");
                } else if (!formNames.contains(formName.trim())) {
                    result.addError("REFERENCE_NOT_FOUND", path + ".detailFormName",
                            "Form not found in this function unit: " + formName);
                }
            }

            if (v.get("accessRules") instanceof List<?> rules && !rules.isEmpty()) {
                if (catalog == null) catalog = orgCatalogReader.readActive();
                checkAccessRuleRefs(rules, catalog, path + ".accessRules", result);
            }
        }
    }

    private void checkViewFieldRef(Object fieldNameObj, Object systemFlagObj, boolean isMain, Set<String> tableFields,
                                   String mainTableName, String path, AiValidationResult result) {
        if (!(fieldNameObj instanceof String fieldName) || fieldName.isBlank()) return;
        String fn = fieldName.trim();
        boolean systemFlag = Boolean.TRUE.equals(systemFlagObj);
        boolean isSystem = SYSTEM_VIEW_FIELDS.contains(fn);
        if (isSystem) {
            if (!isMain) {
                result.addError("REFERENCE_INVALID", path + ".fieldName",
                        "System field '" + fn + "' exists on MAIN-table views only; '" + mainTableName + "' is a SUB table");
            } else if (!systemFlag) {
                result.addError("REFERENCE_INVALID", path + ".systemField",
                        "System field '" + fn + "' must be flagged systemField: true");
            }
            return;
        }
        if (systemFlag) {
            result.addError("REFERENCE_INVALID", path + ".systemField",
                    "'" + fn + "' is not a system field; systemField must be false");
        }
        if (!tableFields.contains(fn)) {
            result.addError("REFERENCE_NOT_FOUND", path + ".fieldName",
                    "Field '" + fn + "' does not exist on table '" + mainTableName + "'");
        }
    }

    private void checkFilterRefs(Map<?, ?> filter, boolean isMain, Set<String> tableFields, String mainTableName,
                                 String path, AiValidationResult result) {
        if (filter.get("conditions") instanceof List<?> conds) {
            for (int j = 0; j < conds.size(); j++) {
                if (conds.get(j) instanceof Map<?, ?> c) {
                    checkViewFieldRef(c.get("fieldName"), c.get("systemField"), isMain, tableFields,
                            mainTableName, path + ".conditions[" + j + "]", result);
                }
            }
        }
        if (filter.get("groups") instanceof List<?> groups) {
            for (int j = 0; j < groups.size(); j++) {
                if (groups.get(j) instanceof Map<?, ?> g) {
                    checkFilterRefs(g, isMain, tableFields, mainTableName, path + ".groups[" + j + "]", result);
                }
            }
        }
    }

    private void checkAccessRuleRefs(List<?> rules, List<BusinessUnitEntry> catalog, String path,
                                     AiValidationResult result) {
        Map<String, BusinessUnitEntry> buById = new HashMap<>();
        Set<String> allRoleIds = new HashSet<>();
        for (BusinessUnitEntry bu : catalog) {
            buById.put(bu.id(), bu);
            for (RoleEntry r : bu.roles()) allRoleIds.add(r.id());
        }
        List<String> chosenBuIds = new java.util.ArrayList<>();
        for (Object o : rules) {
            if (o instanceof Map<?, ?> r && "BUSINESS_UNIT".equalsIgnoreCase(String.valueOf(r.get("targetType")))
                    && r.get("targetId") instanceof String id && !id.isBlank()) {
                chosenBuIds.add(id.trim());
            }
        }
        Set<String> eligibleRoleIds = new HashSet<>();
        for (String buId : chosenBuIds) {
            BusinessUnitEntry bu = buById.get(buId);
            if (bu != null) {
                for (RoleEntry r : bu.roles()) eligibleRoleIds.add(r.id());
            }
        }
        for (int j = 0; j < rules.size(); j++) {
            if (!(rules.get(j) instanceof Map<?, ?> r)) continue;
            String rp = path + "[" + j + "]";
            String type = String.valueOf(r.get("targetType")).toUpperCase(java.util.Locale.ROOT);
            String id = r.get("targetId") instanceof String s ? s.trim() : "";
            if (id.isEmpty()) continue;
            if ("BUSINESS_UNIT".equals(type)) {
                if (!buById.containsKey(id)) {
                    result.addError("REFERENCE_NOT_FOUND", rp + ".targetId",
                            "Business unit id not found in the active organization catalog: " + id);
                }
            } else if ("ROLE".equals(type)) {
                if (!allRoleIds.contains(id)) {
                    result.addError("REFERENCE_NOT_FOUND", rp + ".targetId",
                            "Role id is not an active role bound to any business unit: " + id);
                } else if (!eligibleRoleIds.contains(id)) {
                    result.addError("REFERENCE_INVALID", rp + ".targetId",
                            "Role '" + id + "' is not bound to any of the chosen business units; "
                                    + "views may only use roles eligible for the selected units");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateMonitorRules(Long functionUnitId, AiGeneratedData data,
                                      Set<String> mainFields, AiValidationResult result) {
        // 连接名 → 该名下的连接（入站优先），与写入器的解析顺序一致
        Map<String, EmailConnection> byName = new HashMap<>();
        for (EmailConnection c : emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(functionUnitId)) {
            if (c.getDirection() == EmailConnectionDirection.INBOUND || !byName.containsKey(c.getName())) {
                byName.put(c.getName(), c);
            }
        }
        // 同批提案里的连接名也算"存在"——但它们没有凭证，仍会被下面的凭证规则拒绝，错误信息要说清楚
        Set<String> proposedConnections = new HashSet<>();
        if (data.getEmailConnections() != null) {
            for (Map<String, Object> c : data.getEmailConnections()) {
                if (c.get("name") instanceof String n) proposedConnections.add(n.trim());
            }
        }
        Set<String> formNames = new HashSet<>();
        for (FormDefinition f : formDefinitionRepository.findByFunctionUnitId(functionUnitId)) {
            formNames.add(f.getFormName());
        }

        List<Map<String, Object>> rules = data.getEmailMonitorRules();
        for (int i = 0; i < rules.size(); i++) {
            Map<String, Object> r = rules.get(i);
            String path = "emailMonitorRules[" + i + "]";
            String connectionName = r.get("connectionName") instanceof String s ? s.trim() : "";
            if (!connectionName.isEmpty()) {
                EmailConnection c = byName.get(connectionName);
                if (c == null) {
                    result.addError("REFERENCE_NOT_FOUND", path + ".connectionName",
                            proposedConnections.contains(connectionName)
                                    ? "Connection '" + connectionName + "' is only proposed in this batch; a monitor "
                                            + "needs an existing INBOUND connection with credentials — apply the connection, "
                                            + "fill in its credentials in the Connection designer, then propose the monitor"
                                    : "Connection not found in this function unit: " + connectionName);
                } else {
                    if (Boolean.FALSE.equals(c.getEnabled())) {
                        result.addError("REFERENCE_INVALID", path + ".connectionName",
                                "Connection '" + connectionName + "' is disabled");
                    }
                    if (c.getDirection() != EmailConnectionDirection.INBOUND) {
                        result.addError("REFERENCE_INVALID", path + ".connectionName",
                                "Connection '" + connectionName + "' is not INBOUND; monitors need an inbound connection");
                    }
                    if (c.getCredentialEncrypted() == null || c.getCredentialEncrypted().isBlank()) {
                        result.addError("REFERENCE_INVALID", path + ".connectionName",
                                "Connection '" + connectionName + "' has no credentials yet; fill them in the Connection designer first");
                    }
                }
            }
            if (r.get("targetFormName") instanceof String formName && !formName.isBlank()
                    && !formNames.contains(formName.trim())) {
                result.addError("REFERENCE_NOT_FOUND", path + ".targetFormName",
                        "Form not found in this function unit: " + formName);
            }
            if (r.get("extractionRules") instanceof Map<?, ?> er && er.get("fields") instanceof List<?> fields) {
                for (int j = 0; j < fields.size(); j++) {
                    if (fields.get(j) instanceof Map<?, ?> f && f.get("target") instanceof String target
                            && !target.isBlank() && !mainFields.contains(target.trim())) {
                        result.addError("REFERENCE_NOT_FOUND", path + ".extractionRules.fields[" + j + "].target",
                                "Extraction target is not a MAIN table field: " + target);
                    }
                }
            }
        }
    }

    /** 模板里的 {@code ${fieldName}} 若不是主表字段，只记 warning：正文里可能出现字面上的 ${...} 示例。 */
    private void validateTemplateTokens(AiGeneratedData data, Set<String> mainFields, AiValidationResult result) {
        List<Map<String, Object>> templates = data.getEmailTemplates();
        for (int i = 0; i < templates.size(); i++) {
            Map<String, Object> t = templates.get(i);
            String path = "emailTemplates[" + i + "]";
            checkTokens(t.get("subject"), path + ".subject", mainFields, result);
            checkTokens(t.get("bodyHtml"), path + ".bodyHtml", mainFields, result);
        }
    }

    private void checkTokens(Object text, String path, Set<String> mainFields, AiValidationResult result) {
        if (!(text instanceof String s) || s.isBlank()) return;
        Matcher m = TEMPLATE_TOKEN.matcher(s);
        while (m.find()) {
            String token = m.group(1).trim();
            if (!mainFields.contains(token)) {
                result.addWarning("REFERENCE_NOT_FOUND", path,
                        "Template variable ${" + token + "} does not match any MAIN table field");
            }
        }
    }

    private Set<String> mainTableFieldNames(Long functionUnitId) {
        Set<String> names = new HashSet<>();
        for (TableDefinition table : tableDefinitionRepository.findByFunctionUnitIdWithFields(functionUnitId)) {
            if (table.getTableType() != TableType.MAIN || table.getFieldDefinitions() == null) continue;
            for (FieldDefinition field : table.getFieldDefinitions()) {
                if (field.getFieldName() != null) names.add(field.getFieldName());
            }
        }
        return names;
    }
}
