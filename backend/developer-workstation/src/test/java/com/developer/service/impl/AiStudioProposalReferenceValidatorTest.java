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
import com.developer.repository.TableDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** 邮件三阶段提案的 FU 感知引用校验：连接可用性、表单与主表字段引用、模板变量。 */
@ExtendWith(MockitoExtension.class)
class AiStudioProposalReferenceValidatorTest {

    @Mock
    private EmailConnectionRepository emailConnectionRepository;
    @Mock
    private FormDefinitionRepository formDefinitionRepository;
    @Mock
    private TableDefinitionRepository tableDefinitionRepository;
    @Mock
    private AiOrgCatalogReader orgCatalogReader;
    @Mock
    private com.developer.repository.ProcessDefinitionRepository processDefinitionRepository;
    @Mock
    private AiAutomationFlowCatalogReader automationFlowCatalogReader;

    private AiStudioProposalReferenceValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AiStudioProposalReferenceValidator(
                emailConnectionRepository, formDefinitionRepository, tableDefinitionRepository, orgCatalogReader,
                processDefinitionRepository, automationFlowCatalogReader);
        // 组织目录：BU sales 绑定 role approver；BU hr 无角色；role auditor 只绑在 finance 上
        lenient().when(orgCatalogReader.readActive()).thenReturn(List.of(
                new AiOrgCatalogReader.BusinessUnitEntry("bu-sales", "SALES", "Sales",
                        List.of(new AiOrgCatalogReader.RoleEntry("role-approver", "APPROVER", "Approver"))),
                new AiOrgCatalogReader.BusinessUnitEntry("bu-hr", "HR", "HR", List.of()),
                new AiOrgCatalogReader.BusinessUnitEntry("bu-finance", "FIN", "Finance",
                        List.of(new AiOrgCatalogReader.RoleEntry("role-auditor", "AUDITOR", "Auditor")))));

        FieldDefinition orderNo = new FieldDefinition();
        orderNo.setFieldName("order_no");
        FieldDefinition amount = new FieldDefinition();
        amount.setFieldName("amount");
        TableDefinition main = new TableDefinition();
        main.setTableName("orders");
        main.setTableType(TableType.MAIN);
        main.setFieldDefinitions(List.of(orderNo, amount));
        FieldDefinition subField = new FieldDefinition();
        subField.setFieldName("line_qty");
        TableDefinition sub = new TableDefinition();
        sub.setTableName("order_lines");
        sub.setTableType(TableType.SUB);
        sub.setFieldDefinitions(List.of(subField));
        lenient().when(tableDefinitionRepository.findByFunctionUnitIdWithFields(anyLong()))
                .thenReturn(List.of(main, sub));

        FormDefinition form = new FormDefinition();
        form.setFormName("order_form");
        lenient().when(formDefinitionRepository.findByFunctionUnitId(anyLong())).thenReturn(List.of(form));
    }

    private EmailConnection connection(String name, EmailConnectionDirection direction,
                                       boolean enabled, String credential) {
        EmailConnection c = new EmailConnection();
        c.setName(name);
        c.setDirection(direction);
        c.setEnabled(enabled);
        c.setCredentialEncrypted(credential);
        c.setConnectionUid("uid-" + name + "-" + direction);
        return c;
    }

    private Map<String, Object> monitor(String connectionName, String target) {
        Map<String, Object> rule = new HashMap<>();
        rule.put("name", "Invoice inbox");
        rule.put("connectionName", connectionName);
        rule.put("extractionRules", Map.of("fields", List.of(
                Map.of("target", target, "source", "SUBJECT", "type", "DIRECT"))));
        return rule;
    }

    @Test
    void monitorReferencingCredentialedInboundConnectionPasses() {
        when(emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(1L))
                .thenReturn(List.of(connection("inbox@x.com", EmailConnectionDirection.INBOUND, true, "enc")));
        AiGeneratedData data = AiGeneratedData.builder()
                .emailMonitorRules(List.of(monitor("inbox@x.com", "order_no")))
                .build();

        AiValidationResult result = validator.validate(1L, data);

        assertTrue(result.isValid(), () -> result.getErrors().toString());
    }

    @Test
    void monitorReferencingConnectionWithoutCredentialsIsRejected() {
        when(emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(1L))
                .thenReturn(List.of(connection("inbox@x.com", EmailConnectionDirection.INBOUND, true, null)));
        AiGeneratedData data = AiGeneratedData.builder()
                .emailMonitorRules(List.of(monitor("inbox@x.com", "order_no")))
                .build();

        AiValidationResult result = validator.validate(1L, data);

        assertFalse(result.isValid());
        assertEquals("emailMonitorRules[0].connectionName", result.getErrors().get(0).getFieldPath());
        assertTrue(result.getErrors().get(0).getDescription().contains("no credentials"));
    }

    @Test
    void monitorReferencingOutboundConnectionIsRejected() {
        when(emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(1L))
                .thenReturn(List.of(connection("notify@x.com", EmailConnectionDirection.OUTBOUND, true, "enc")));
        AiGeneratedData data = AiGeneratedData.builder()
                .emailMonitorRules(List.of(monitor("notify@x.com", "order_no")))
                .build();

        AiValidationResult result = validator.validate(1L, data);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).getDescription().contains("not INBOUND"));
    }

    @Test
    void monitorReferencingConnectionOnlyProposedInSameBatchIsRejectedWithGuidance() {
        when(emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(1L)).thenReturn(List.of());
        AiGeneratedData data = AiGeneratedData.builder()
                .emailConnections(List.of(Map.of("name", "inbox@x.com", "direction", "INBOUND")))
                .emailMonitorRules(List.of(monitor("inbox@x.com", "order_no")))
                .build();

        AiValidationResult result = validator.validate(1L, data);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).getDescription().contains("only proposed in this batch"));
    }

    @Test
    void extractionTargetMustBeMainTableField() {
        when(emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(1L))
                .thenReturn(List.of(connection("inbox@x.com", EmailConnectionDirection.INBOUND, true, "enc")));
        AiGeneratedData data = AiGeneratedData.builder()
                .emailMonitorRules(List.of(monitor("inbox@x.com", "line_qty")))
                .build();

        AiValidationResult result = validator.validate(1L, data);

        assertFalse(result.isValid());
        assertEquals("emailMonitorRules[0].extractionRules.fields[0].target",
                result.getErrors().get(0).getFieldPath());
    }

    @Test
    void unknownTargetFormIsRejected() {
        when(emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(1L))
                .thenReturn(List.of(connection("inbox@x.com", EmailConnectionDirection.INBOUND, true, "enc")));
        Map<String, Object> rule = monitor("inbox@x.com", "order_no");
        rule.put("targetFormName", "missing_form");
        AiGeneratedData data = AiGeneratedData.builder().emailMonitorRules(List.of(rule)).build();

        AiValidationResult result = validator.validate(1L, data);

        assertFalse(result.isValid());
        assertEquals("emailMonitorRules[0].targetFormName", result.getErrors().get(0).getFieldPath());
    }

    @Test
    void templateTokensNotMatchingMainFieldsOnlyWarn() {
        AiGeneratedData data = AiGeneratedData.builder()
                .emailTemplates(List.of(Map.of(
                        "name", "Approved",
                        "subject", "Order ${order_no} approved",
                        "bodyHtml", "<p>Total ${total_amount} — ${subTableField:12:qty}</p>")))
                .build();

        AiValidationResult result = validator.validate(1L, data);

        assertTrue(result.isValid());
        assertEquals(1, result.getWarnings().size());
        assertTrue(result.getWarnings().get(0).getDescription().contains("${total_amount}"));
    }

    // ---- VIEW_DESIGN ----

    private static Map<String, Object> view(String table, String name, Map<String, Object> extra) {
        Map<String, Object> v = new HashMap<>();
        v.put("mainTableName", table);
        v.put("viewName", name);
        v.putAll(extra);
        return v;
    }

    @Test
    void viewOnUnknownTableIsRejected() {
        AiGeneratedData data = AiGeneratedData.builder()
                .mainTableViews(List.of(view("ghost", "All", Map.of())))
                .build();

        AiValidationResult result = validator.validate(1L, data);

        assertFalse(result.isValid());
        assertEquals("mainTableViews[0].mainTableName", result.getErrors().get(0).getFieldPath());
    }

    @Test
    void viewFieldsSortAndFilterMustReferenceOwnTableOrMainSystemFields() {
        AiGeneratedData data = AiGeneratedData.builder()
                .mainTableViews(List.of(view("orders", "Pending", Map.of(
                        "fields", List.of(
                                Map.of("fieldName", "order_no"),
                                Map.of("fieldName", "process_status", "systemField", true),
                                Map.of("fieldName", "line_qty"),                       // 子表字段，不在 orders 上
                                Map.of("fieldName", "start_time")),                    // 系统字段漏标 systemField
                        "sortConfig", List.of(Map.of("fieldName", "nope", "direction", "DESC")),
                        "filterConfig", Map.of("conditions", List.of(
                                Map.of("fieldName", "amount", "operator", "gt", "value", "0", "systemField", true)))))))
                .build();

        AiValidationResult result = validator.validate(1L, data);

        assertFalse(result.isValid());
        List<String> paths = result.getErrors().stream().map(e -> e.getFieldPath()).toList();
        assertTrue(paths.contains("mainTableViews[0].fields[2].fieldName"));
        assertTrue(paths.contains("mainTableViews[0].fields[3].systemField"));
        assertTrue(paths.contains("mainTableViews[0].sortConfig[0].fieldName"));
        assertTrue(paths.contains("mainTableViews[0].filterConfig.conditions[0].systemField"));
        assertEquals(4, result.getErrors().size());
    }

    @Test
    void systemFieldOnSubTableViewAndDetailFormOnMainViewAreRejected() {
        AiGeneratedData data = AiGeneratedData.builder()
                .mainTableViews(List.of(
                        view("order_lines", "Lines", Map.of(
                                "fields", List.of(Map.of("fieldName", "process_status", "systemField", true)),
                                "detailFormName", "missing_form")),
                        view("orders", "Main", Map.of("detailFormName", "order_form"))))
                .build();

        AiValidationResult result = validator.validate(1L, data);

        assertFalse(result.isValid());
        List<String> paths = result.getErrors().stream().map(e -> e.getFieldPath()).toList();
        assertTrue(paths.contains("mainTableViews[0].fields[0].fieldName"));
        assertTrue(paths.contains("mainTableViews[0].detailFormName"));
        assertTrue(paths.contains("mainTableViews[1].detailFormName"));
        assertEquals(3, result.getErrors().size());
    }

    @Test
    void subTableViewWithExistingDetailFormIsAccepted() {
        AiGeneratedData data = AiGeneratedData.builder()
                .mainTableViews(List.of(view("order_lines", "Lines", Map.of(
                        "fields", List.of(Map.of("fieldName", "line_qty")),
                        "detailFormName", "order_form"))))
                .build();

        assertTrue(validator.validate(1L, data).isValid());
    }

    @Test
    void accessRulesMustUseCatalogIdsAndRolesBoundToChosenUnits() {
        AiGeneratedData data = AiGeneratedData.builder()
                .mainTableViews(List.of(view("orders", "Pending", Map.of(
                        "accessRules", List.of(
                                Map.of("targetType", "BUSINESS_UNIT", "targetId", "bu-sales"),
                                Map.of("targetType", "BUSINESS_UNIT", "targetId", "bu-ghost"),
                                Map.of("targetType", "ROLE", "targetId", "role-approver"),
                                Map.of("targetType", "ROLE", "targetId", "role-auditor"),   // 存在但只绑 finance
                                Map.of("targetType", "ROLE", "targetId", "role-ghost"))))))
                .build();

        AiValidationResult result = validator.validate(1L, data);

        assertFalse(result.isValid());
        List<String> paths = result.getErrors().stream().map(e -> e.getFieldPath()).toList();
        assertTrue(paths.contains("mainTableViews[0].accessRules[1].targetId"));
        assertTrue(paths.contains("mainTableViews[0].accessRules[3].targetId"));
        assertTrue(paths.contains("mainTableViews[0].accessRules[4].targetId"));
        assertEquals(3, result.getErrors().size());
        assertTrue(result.getErrors().stream()
                .filter(e -> e.getFieldPath().endsWith("[3].targetId"))
                .allMatch(e -> e.getDescription().contains("not bound to any of the chosen business units")));
    }

    @Test
    void wellFormedViewWithPairedAccessRulesIsAccepted() {
        AiGeneratedData data = AiGeneratedData.builder()
                .mainTableViews(List.of(view("orders", "Pending", Map.of(
                        "fields", List.of(Map.of("fieldName", "order_no"), Map.of("fieldName", "start_time", "systemField", true)),
                        "sortConfig", List.of(Map.of("fieldName", "start_time", "direction", "DESC", "systemField", true)),
                        "filterConfig", Map.of("logic", "and", "conditions", List.of(
                                Map.of("fieldName", "process_status", "operator", "eq", "value", "RUNNING", "systemField", true))),
                        "accessRules", List.of(
                                Map.of("targetType", "BUSINESS_UNIT", "targetId", "bu-sales"),
                                Map.of("targetType", "ROLE", "targetId", "role-approver"))))))
                .build();

        AiValidationResult result = validator.validate(1L, data);

        assertTrue(result.isValid(), () -> String.valueOf(result.getErrors()));
    }

    // ---- AUTOMATION ----

    private static final String BPMN_WITH_SERVICE_TASK = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <process id="p"><serviceTask id="svc_sync" name="Sync"/><userTask id="u1"/></process>
            </definitions>
            """;

    private void stubProcess(String bpmn) {
        com.developer.entity.ProcessDefinition pd = new com.developer.entity.ProcessDefinition();
        pd.setBpmnXml(bpmn);
        lenient().when(processDefinitionRepository.findByFunctionUnitId(anyLong()))
                .thenReturn(java.util.Optional.ofNullable(pd));
        lenient().when(automationFlowCatalogReader.workspaceExternalIds(anyLong()))
                .thenReturn(java.util.Set.of("hermes-main", "hermes-dg-vg-team-a"));
    }

    private static AiAutomationFlowCatalogReader.FlowEntry flow(String key, boolean published, String project) {
        return new AiAutomationFlowCatalogReader.FlowEntry("id-" + key, key, key + " flow", published, project, "ws");
    }

    private static AiGeneratedData bindings(Map<String, Object>... entries) {
        return AiGeneratedData.builder().serviceTaskBindings(List.of(entries)).build();
    }

    @Test
    void bindingWithoutProcessDefinitionIsRejected() {
        lenient().when(processDefinitionRepository.findByFunctionUnitId(anyLong())).thenReturn(java.util.Optional.empty());

        AiValidationResult result = validator.validate(1L, bindings(Map.of("serviceTaskId", "svc_sync", "flowKey", "k")));

        assertFalse(result.isValid());
        assertEquals("serviceTaskBindings", result.getErrors().get(0).getFieldPath());
    }

    @Test
    void bindingMustReferenceExistingServiceTaskAndKnownFlowKey() {
        stubProcess(BPMN_WITH_SERVICE_TASK);
        lenient().when(automationFlowCatalogReader.findByKey("invoice-sync"))
                .thenReturn(java.util.Optional.of(flow("invoice-sync", true, "hermes-main")));
        lenient().when(automationFlowCatalogReader.findByKey("ghost-key")).thenReturn(java.util.Optional.empty());

        AiValidationResult result = validator.validate(1L, bindings(
                Map.of("serviceTaskId", "u1", "flowKey", "invoice-sync"),          // 是 userTask 不是 serviceTask
                Map.of("serviceTaskId", "svc_sync", "flowKey", "ghost-key")));   // 键不存在

        assertFalse(result.isValid());
        List<String> paths = result.getErrors().stream().map(e -> e.getFieldPath()).toList();
        assertTrue(paths.contains("serviceTaskBindings[0].serviceTaskId"));
        assertTrue(paths.contains("serviceTaskBindings[1].flowKey"));
        assertEquals(2, result.getErrors().size());
    }

    @Test
    void otherWorkspaceOrUnpublishedFlowOnlyWarns() {
        stubProcess(BPMN_WITH_SERVICE_TASK);
        lenient().when(automationFlowCatalogReader.findByKey("draft-key"))
                .thenReturn(java.util.Optional.of(flow("draft-key", false, "hermes-dg-vg-other")));

        AiValidationResult result = validator.validate(1L, bindings(Map.of("serviceTaskId", "svc_sync", "flowKey", "draft-key")));

        assertTrue(result.isValid());
        assertEquals(2, result.getWarnings().size());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.getDescription().contains("another workspace")));
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.getDescription().contains("not published")));
    }

    @Test
    void wellFormedBindingIsAccepted() {
        stubProcess(BPMN_WITH_SERVICE_TASK);
        lenient().when(automationFlowCatalogReader.findByKey("invoice-sync"))
                .thenReturn(java.util.Optional.of(flow("invoice-sync", true, "hermes-dg-vg-team-a")));

        AiValidationResult result = validator.validate(1L, bindings(Map.of("serviceTaskId", "svc_sync", "flowKey", "invoice-sync")));

        assertTrue(result.isValid(), () -> String.valueOf(result.getErrors()));
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    void proposalWithoutEmailSlicesIsNoop() {
        AiGeneratedData data = AiGeneratedData.builder()
                .tableDefinitions(List.of(Map.of("tableName", "t")))
                .build();

        AiValidationResult result = validator.validate(1L, data);

        assertTrue(result.isValid());
        assertTrue(result.getWarnings().isEmpty());
    }
}
