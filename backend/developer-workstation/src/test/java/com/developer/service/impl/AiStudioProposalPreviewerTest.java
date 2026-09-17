package com.developer.service.impl;

import com.developer.dto.AiGeneratedData;
import com.developer.dto.AiStudioProposalPreview;
import com.developer.dto.AiStudioProposalPreview.Action;
import com.developer.dto.AiStudioProposalPreview.Severity;
import com.developer.dto.AiValidationResult;
import com.developer.entity.EmailConnection;
import com.developer.entity.EmailTemplate;
import com.developer.entity.FieldDefinition;
import com.developer.entity.MainTableViewConfig;
import com.developer.entity.ProcessDefinition;
import com.developer.entity.TableDefinition;
import com.developer.enums.EmailConnectionDirection;
import com.developer.repository.ActionDefinitionRepository;
import com.developer.repository.DecisionDefinitionRepository;
import com.developer.repository.EmailConnectionRepository;
import com.developer.repository.EmailMonitorRuleRepository;
import com.developer.repository.EmailTemplateRepository;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.MainTableViewConfigRepository;
import com.developer.repository.ProcessDefinitionRepository;
import com.developer.repository.TableDefinitionRepository;
import com.developer.repository.TableRelationRepository;
import com.developer.service.AiValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** 提案卡预览：新增/更新/替换计数与 Apply 同款预校验；预览失败不抛。 */
@ExtendWith(MockitoExtension.class)
class AiStudioProposalPreviewerTest {

    @Mock AiValidationService aiValidationService;
    @Mock AiStudioProposalReferenceValidator referenceValidator;
    @Mock TableDefinitionRepository tableDefinitionRepository;
    @Mock FormDefinitionRepository formDefinitionRepository;
    @Mock ActionDefinitionRepository actionDefinitionRepository;
    @Mock DecisionDefinitionRepository decisionDefinitionRepository;
    @Mock TableRelationRepository tableRelationRepository;
    @Mock ProcessDefinitionRepository processDefinitionRepository;
    @Mock EmailTemplateRepository emailTemplateRepository;
    @Mock EmailConnectionRepository emailConnectionRepository;
    @Mock EmailMonitorRuleRepository emailMonitorRuleRepository;
    @Mock MainTableViewConfigRepository mainTableViewConfigRepository;

    private AiStudioProposalPreviewer previewer;

    @BeforeEach
    void setUp() {
        previewer = new AiStudioProposalPreviewer(aiValidationService, referenceValidator,
                tableDefinitionRepository, formDefinitionRepository, actionDefinitionRepository,
                decisionDefinitionRepository, tableRelationRepository, processDefinitionRepository,
                emailTemplateRepository, emailConnectionRepository, emailMonitorRuleRepository,
                mainTableViewConfigRepository);
        AiValidationResult clean = AiValidationResult.builder().build();
        lenient().when(aiValidationService.validate(any(), any())).thenReturn(clean);
        lenient().when(referenceValidator.validate(anyLong(), any())).thenReturn(clean);
        lenient().when(tableDefinitionRepository.findByFunctionUnitIdWithFields(anyLong())).thenReturn(List.of());
        lenient().when(tableDefinitionRepository.findByFunctionUnitId(anyLong())).thenReturn(List.of());
        lenient().when(emailTemplateRepository.findByFunctionUnitIdOrderByNameAsc(anyLong())).thenReturn(List.of());
        lenient().when(emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(anyLong())).thenReturn(List.of());
        lenient().when(emailMonitorRuleRepository
                .findByFunctionUnitIdAndSourceRuleIdIsNullAndStartEventIdIsNullOrderByNameAsc(anyLong())).thenReturn(List.of());
        lenient().when(mainTableViewConfigRepository.findByFunctionUnitIdOrderByIsDefaultDescViewNameAsc(anyLong())).thenReturn(List.of());
        lenient().when(processDefinitionRepository.findByFunctionUnitId(anyLong())).thenReturn(Optional.empty());
    }

    private static EmailTemplate template(String name) {
        EmailTemplate t = new EmailTemplate();
        t.setName(name);
        return t;
    }

    private static Map<String, Action> byName(AiStudioProposalPreview p, String slice) {
        return p.getItems().stream().filter(i -> slice.equals(i.getSlice()))
                .collect(java.util.stream.Collectors.toMap(AiStudioProposalPreview.Item::getName, AiStudioProposalPreview.Item::getAction));
    }

    @Test
    void upsertSlicesReportNewVersusUpdateAgainstExistingObjects() {
        when(emailTemplateRepository.findByFunctionUnitIdOrderByNameAsc(7L))
                .thenReturn(List.of(template("Order Approved")));
        EmailConnection out = EmailConnection.builder().name("notify@x.com").direction(EmailConnectionDirection.OUTBOUND).build();
        when(emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(7L)).thenReturn(List.of(out));
        TableDefinition orders = new TableDefinition();
        orders.setId(100L);
        orders.setTableName("orders");
        when(tableDefinitionRepository.findByFunctionUnitId(7L)).thenReturn(List.of(orders));
        MainTableViewConfig existingView = MainTableViewConfig.builder().mainTableId(100L).viewName("Pending").build();
        when(mainTableViewConfigRepository.findByFunctionUnitIdOrderByIsDefaultDescViewNameAsc(7L)).thenReturn(List.of(existingView));

        AiGeneratedData data = AiGeneratedData.builder()
                .emailTemplates(List.of(Map.of("name", "Order Approved"), Map.of("name", "Order Shipped")))
                .emailConnections(List.of(
                        Map.of("name", "notify@x.com", "direction", "OUTBOUND"),
                        Map.of("name", "notify@x.com", "direction", "INBOUND")))
                .mainTableViews(List.of(
                        Map.of("mainTableName", "orders", "viewName", "Pending"),
                        Map.of("mainTableName", "orders", "viewName", "High Value")))
                .build();

        AiStudioProposalPreview p = previewer.preview(7L, "EMAIL_TEMPLATES", data);

        assertTrue(p.isChecked());
        assertTrue(p.isUndoable(), "upsert scopes can be undone, so no pre-apply confirmation");
        assertEquals(Map.of("Order Approved", Action.UPDATE, "Order Shipped", Action.NEW), byName(p, "emailTemplates"));
        // 连接按 name+direction：同名不同方向是新增
        List<Action> conn = p.getItems().stream().filter(i -> "emailConnections".equals(i.getSlice())).map(AiStudioProposalPreview.Item::getAction).toList();
        assertEquals(List.of(Action.UPDATE, Action.NEW), conn);
        assertEquals(Map.of("Pending", Action.UPDATE, "High Value", Action.NEW), byName(p, "mainTableViews"));
        assertTrue(p.getReplacements().isEmpty());
        assertTrue(p.getIssues().isEmpty());
    }

    @Test
    void replaceSlicesListNamesAndCountExistingObjectsThatWillBeCleared() {
        TableDefinition a = new TableDefinition(); a.setTableName("a");
        TableDefinition b = new TableDefinition(); b.setTableName("b");
        TableDefinition c = new TableDefinition(); c.setTableName("c");
        when(tableDefinitionRepository.findByFunctionUnitId(7L)).thenReturn(List.of(a, b, c));
        ProcessDefinition pd = new ProcessDefinition();
        pd.setBpmnXml("<x/>");
        when(processDefinitionRepository.findByFunctionUnitId(7L)).thenReturn(Optional.of(pd));

        AiGeneratedData data = AiGeneratedData.builder()
                .tableDefinitions(List.of(Map.of("tableName", "orders"), Map.of("tableName", "order_lines")))
                .processDefinition(Map.of("bpmnXml", "<definitions/>"))
                .build();

        AiStudioProposalPreview p = previewer.preview(7L, "TABLES", data);

        assertEquals(Map.of("orders", Action.REPLACE, "order_lines", Action.REPLACE), byName(p, "tableDefinitions"));
        assertEquals(Action.REPLACE, byName(p, "processDefinition").get("processDefinition"));
        Map<String, Integer> replaces = p.getReplacements().stream()
                .collect(java.util.stream.Collectors.toMap(AiStudioProposalPreview.SliceReplacement::getSlice, AiStudioProposalPreview.SliceReplacement::getReplacesExisting));
        assertEquals(Map.of("tableDefinitions", 3, "processDefinition", 1), replaces);
        assertFalse(p.isUndoable(), "clear-and-rebuild scopes cannot be undone");
    }

    @Test
    void serviceTaskBindingsDistinguishBindFromRebind() {
        ProcessDefinition pd = new ProcessDefinition();
        pd.setBpmnXml("""
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:c="http://workflow.platform/schema/custom">
                  <process id="p">
                    <serviceTask id="svc_bound"><extensionElements><c:properties><c:property name="ap:flowKey" value="k"/></c:properties></extensionElements></serviceTask>
                    <serviceTask id="svc_bare"/>
                  </process>
                </definitions>
                """);
        when(processDefinitionRepository.findByFunctionUnitId(7L)).thenReturn(Optional.of(pd));

        AiGeneratedData data = AiGeneratedData.builder()
                .serviceTaskBindings(List.of(
                        Map.of("serviceTaskId", "svc_bound", "flowKey", "k2"),
                        Map.of("serviceTaskId", "svc_bare", "flowKey", "k2")))
                .build();

        AiStudioProposalPreview p = previewer.preview(7L, "SERVICE_TASK_BINDINGS", data);

        assertEquals(Map.of("svc_bound", Action.REBIND, "svc_bare", Action.BIND), byName(p, "serviceTaskBindings"));
    }

    @Test
    void issuesComeFromBothValidatorsWithSeverityAndExistingTablesAreHandedToStructureValidation() {
        FieldDefinition f = new FieldDefinition(); f.setFieldName("order_no");
        TableDefinition orders = new TableDefinition(); orders.setTableName("orders"); orders.setFieldDefinitions(List.of(f));
        when(tableDefinitionRepository.findByFunctionUnitIdWithFields(7L)).thenReturn(List.of(orders));
        AiValidationResult structure = AiValidationResult.builder().build();
        structure.addError("FIELD_CONSTRAINT", "emailTemplates[0].name", "name must not be empty");
        AiValidationResult reference = AiValidationResult.builder().build();
        reference.addWarning("REFERENCE_NOT_FOUND", "emailTemplates[0].subject", "Template variable ${x} does not match");
        when(aiValidationService.validate(any(), eq(Map.of("orders", Set.of("order_no"))))).thenReturn(structure);
        when(referenceValidator.validate(eq(7L), any())).thenReturn(reference);

        AiGeneratedData data = AiGeneratedData.builder()
                .emailTemplates(List.of(Map.of("name", "", "subject", "${x}")))
                .build();

        AiStudioProposalPreview p = previewer.preview(7L, "EMAIL_TEMPLATES", data);

        assertTrue(p.isChecked());
        assertEquals(2, p.getIssues().size());
        assertEquals(Severity.ERROR, p.getIssues().get(0).getSeverity());
        assertEquals("emailTemplates[0].name", p.getIssues().get(0).getFieldPath());
        assertEquals(Severity.WARNING, p.getIssues().get(1).getSeverity());
    }

    @Test
    void previewFailureNeverPropagatesAndIsFlaggedUnchecked() {
        when(emailTemplateRepository.findByFunctionUnitIdOrderByNameAsc(7L)).thenThrow(new IllegalStateException("db down"));
        AiGeneratedData data = AiGeneratedData.builder()
                .emailTemplates(List.of(Map.of("name", "x")))
                .build();

        AiStudioProposalPreview p = previewer.preview(7L, "EMAIL_TEMPLATES", data);

        assertFalse(p.isChecked());
        assertTrue(p.getIssues().isEmpty());
        assertTrue(p.isUndoable(), "undoability depends on the scope only, not on whether the check ran");
    }

    @Test
    void existingTableFieldsAreOnlyLookedUpWhenTheProposalHasNoTableDefinitions() {
        FieldDefinition f = new FieldDefinition(); f.setFieldName("id");
        TableDefinition t = new TableDefinition(); t.setTableName("orders"); t.setFieldDefinitions(List.of(f));
        when(tableDefinitionRepository.findByFunctionUnitIdWithFields(7L)).thenReturn(List.of(t));

        assertEquals(Map.of("orders", Set.of("id")),
                previewer.existingTableFieldsFor(7L, AiGeneratedData.builder().formDefinitions(List.of()).build()));
        assertEquals(Map.of(),
                previewer.existingTableFieldsFor(7L, AiGeneratedData.builder().tableDefinitions(List.of(Map.of("tableName", "x"))).build()));
    }
}
