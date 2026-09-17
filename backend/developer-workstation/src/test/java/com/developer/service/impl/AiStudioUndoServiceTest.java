package com.developer.service.impl;

import com.developer.component.EmailConnectionComponent;
import com.developer.component.EmailMonitorRuleComponent;
import com.developer.component.EmailTemplateComponent;
import com.developer.dto.AiGeneratedData;
import com.developer.dto.AiStudioApplyResponse.UndoNote;
import com.developer.dto.AiStudioApplyResponse.UndoOutcome;
import com.developer.dto.EmailTemplateRequest;
import com.developer.dto.MainTableViewDtos.MainTableViewDTO;
import com.developer.entity.EmailConnection;
import com.developer.entity.EmailMonitorRule;
import com.developer.entity.EmailTemplate;
import com.developer.entity.MainTableViewConfig;
import com.developer.entity.ProcessDefinition;
import com.developer.entity.TableDefinition;
import com.developer.enums.EmailConnectionDirection;
import com.developer.exception.AiGenerationException;
import com.developer.repository.EmailConnectionRepository;
import com.developer.repository.EmailMonitorRuleRepository;
import com.developer.repository.EmailTemplateRepository;
import com.developer.repository.MainTableViewConfigRepository;
import com.developer.repository.ProcessDefinitionRepository;
import com.developer.repository.TableDefinitionRepository;
import com.developer.repository.TableRelationRepository;
import com.developer.service.AiWriteService;
import com.developer.service.MainTableViewService;
import com.developer.util.BpmnServiceTaskScanner;
import com.developer.util.XmlEncodingUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Apply 撤销：新增的删掉、更新的还原、不可撤销的 scope 不发令牌、令牌一次性。 */
@ExtendWith(MockitoExtension.class)
class AiStudioUndoServiceTest {

    @Mock EmailTemplateComponent emailTemplateComponent;
    @Mock EmailConnectionComponent emailConnectionComponent;
    @Mock EmailMonitorRuleComponent emailMonitorRuleComponent;
    @Mock MainTableViewService mainTableViewService;
    @Mock AiWriteService aiWriteService;
    @Mock EmailTemplateRepository emailTemplateRepository;
    @Mock EmailConnectionRepository emailConnectionRepository;
    @Mock EmailMonitorRuleRepository emailMonitorRuleRepository;
    @Mock MainTableViewConfigRepository mainTableViewConfigRepository;
    @Mock TableDefinitionRepository tableDefinitionRepository;
    @Mock TableRelationRepository tableRelationRepository;
    @Mock ProcessDefinitionRepository processDefinitionRepository;

    private AiStudioUndoService undo;

    private static final String BPMN = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:c="http://workflow.platform/schema/custom">
              <process id="p">
                <serviceTask id="svc_bound"><extensionElements><c:properties>
                  <c:property name="serviceType" value="ap"/><c:property name="ap:flowKey" value="old-key"/>
                </c:properties></extensionElements></serviceTask>
                <serviceTask id="svc_bare"/>
              </process>
            </definitions>
            """;

    @BeforeEach
    void setUp() {
        undo = new AiStudioUndoService(10L, emailTemplateComponent, emailConnectionComponent,
                emailMonitorRuleComponent, mainTableViewService, aiWriteService,
                emailTemplateRepository, emailConnectionRepository, emailMonitorRuleRepository,
                mainTableViewConfigRepository, tableDefinitionRepository, tableRelationRepository,
                processDefinitionRepository);
        lenient().when(emailTemplateRepository.findByFunctionUnitIdOrderByNameAsc(anyLong())).thenReturn(List.of());
        lenient().when(emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(anyLong())).thenReturn(List.of());
        lenient().when(emailMonitorRuleRepository
                .findByFunctionUnitIdAndSourceRuleIdIsNullAndStartEventIdIsNullOrderByNameAsc(anyLong())).thenReturn(List.of());
        lenient().when(emailMonitorRuleRepository.findByFunctionUnitIdOrderByNameAsc(anyLong())).thenReturn(List.of());
        lenient().when(mainTableViewConfigRepository.findByFunctionUnitIdWithFields(anyLong())).thenReturn(List.of());
        lenient().when(tableDefinitionRepository.findByFunctionUnitId(anyLong())).thenReturn(List.of());
        lenient().when(processDefinitionRepository.findByFunctionUnitId(anyLong())).thenReturn(Optional.empty());
    }

    private static EmailTemplate template(long id, String name, String subject) {
        EmailTemplate t = new EmailTemplate();
        t.setId(id);
        t.setName(name);
        t.setSubject(subject);
        t.setBodyHtml("<p>old</p>");
        t.setEnabled(true);
        return t;
    }

    @Test
    void replacingScopesGetNoTokenAtAll() {
        for (String scope : List.of("TABLES", "FORMS", "ACTIONS", "DECISIONS", "ALL")) {
            assertFalse(AiStudioUndoService.isUndoable(scope), scope);
            assertNull(undo.capture(7L, "u1", scope,
                    AiGeneratedData.builder().tableDefinitions(List.of(Map.of("tableName", "t"))).build()));
        }
        assertTrue(AiStudioUndoService.isUndoable("EMAIL_TEMPLATES"));
        assertTrue(AiStudioUndoService.isUndoable("PROCESS"));
    }

    @Test
    void newTemplateIsDeletedAndUpdatedOneIsRestored() {
        EmailTemplate existing = template(11L, "Order Approved", "old subject");
        when(emailTemplateRepository.findByFunctionUnitIdOrderByNameAsc(7L)).thenReturn(List.of(existing));
        AiGeneratedData data = AiGeneratedData.builder()
                .emailTemplates(List.of(
                        Map.of("name", "Order Approved", "subject", "new subject"),
                        Map.of("name", "Order Shipped", "subject", "brand new")))
                .build();

        String token = undo.capture(7L, "u1", "EMAIL_TEMPLATES", data);
        assertNotNull(token);
        assertNotNull(undo.expiryOf(token));

        // 撤销时库里两条都在（Apply 已经跑过）
        when(emailTemplateRepository.findByFunctionUnitIdOrderByNameAsc(7L))
                .thenReturn(List.of(template(11L, "Order Approved", "new subject"), template(12L, "Order Shipped", "brand new")));

        List<UndoNote> notes = undo.undo(token, "u1");

        ArgumentCaptor<EmailTemplateRequest> restored = ArgumentCaptor.forClass(EmailTemplateRequest.class);
        verify(emailTemplateComponent).update(eq(7L), eq(11L), restored.capture());
        assertEquals("old subject", restored.getValue().getSubject(), "the pre-apply subject is restored");
        verify(emailTemplateComponent).delete(7L, 12L);
        assertEquals(2, notes.size());
        assertEquals(new UndoNote("emailTemplates", "Order Approved", UndoOutcome.RESTORED, null), notes.get(0));
        assertEquals(new UndoNote("emailTemplates", "Order Shipped", UndoOutcome.DELETED, null), notes.get(1));
    }

    @Test
    void tokenIsSingleUseAndScopedToItsOwner() {
        when(emailTemplateRepository.findByFunctionUnitIdOrderByNameAsc(7L)).thenReturn(List.of());
        String token = undo.capture(7L, "u1", "EMAIL_TEMPLATES",
                AiGeneratedData.builder().emailTemplates(List.of(Map.of("name", "X"))).build());

        assertThrows(AiGenerationException.class, () -> undo.undo(token, "u2"), "another user cannot undo");
        assertThrows(AiGenerationException.class, () -> undo.functionUnitOf(token, "u2"));
        assertEquals(7L, undo.functionUnitOf(token, "u1"));

        undo.undo(token, "u1");
        AiGenerationException second = assertThrows(AiGenerationException.class, () -> undo.undo(token, "u1"));
        assertEquals("AI_STUDIO_UNDO_EXPIRED", second.getErrorCode());
    }

    @Test
    void discardDropsTheSnapshotWhenTheApplyFailed() {
        String token = undo.capture(7L, "u1", "EMAIL_TEMPLATES",
                AiGeneratedData.builder().emailTemplates(List.of(Map.of("name", "X"))).build());
        undo.discard(token);
        assertThrows(AiGenerationException.class, () -> undo.undo(token, "u1"));
    }

    @Test
    void connectionCreatedByTheApplyIsKeptWhenAMonitorNowReferencesIt() {
        EmailConnection created = EmailConnection.builder()
                .id(5L).name("inbox@x.com").direction(EmailConnectionDirection.INBOUND).connectionUid("uid-1").build();
        AiGeneratedData data = AiGeneratedData.builder()
                .emailConnections(List.of(Map.of("name", "inbox@x.com", "direction", "INBOUND")))
                .build();
        String token = undo.capture(7L, "u1", "CONNECTIONS", data);

        when(emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(7L)).thenReturn(List.of(created));
        EmailMonitorRule rule = EmailMonitorRule.builder().id(9L).name("Inbox").connectionUid("uid-1").build();
        when(emailMonitorRuleRepository.findByFunctionUnitIdOrderByNameAsc(7L)).thenReturn(List.of(rule));

        List<UndoNote> notes = undo.undo(token, "u1");

        verify(emailConnectionComponent, never()).delete(anyLong(), anyLong());
        assertEquals(UndoOutcome.KEPT_REFERENCED, notes.get(0).getOutcome());
        assertEquals("inbox@x.com", notes.get(0).getName(), "the internal name|direction key is not shown");
    }

    @Test
    void viewCreatedByTheApplyIsDeletedAndAnUpdatedOneIsRestored() {
        TableDefinition orders = new TableDefinition();
        orders.setId(100L);
        orders.setTableName("orders");
        when(tableDefinitionRepository.findByFunctionUnitId(7L)).thenReturn(List.of(orders));
        MainTableViewConfig existing = MainTableViewConfig.builder().id(21L).mainTableId(100L).viewName("Pending").build();
        when(mainTableViewConfigRepository.findByFunctionUnitIdWithFields(7L)).thenReturn(List.of(existing));
        when(mainTableViewService.getView(7L, 21L)).thenReturn(MainTableViewDTO.builder()
                .id(21L).viewName("Pending").restrictToInvolvedUsers(false).fields(List.of()).accessRules(List.of()).build());

        AiGeneratedData data = AiGeneratedData.builder()
                .mainTableViews(List.of(
                        Map.of("mainTableName", "orders", "viewName", "Pending"),
                        Map.of("mainTableName", "orders", "viewName", "High Value")))
                .build();
        String token = undo.capture(7L, "u1", "VIEWS", data);

        MainTableViewConfig created = MainTableViewConfig.builder().id(22L).mainTableId(100L).viewName("High Value").build();
        when(mainTableViewConfigRepository.findByFunctionUnitIdWithFields(7L)).thenReturn(List.of(existing, created));

        List<UndoNote> notes = undo.undo(token, "u1");

        verify(mainTableViewService).updateView(eq(7L), eq(21L), any());
        verify(mainTableViewService).deleteView(7L, 22L);
        assertEquals(2, notes.size());
        assertEquals("orders / Pending", notes.get(0).getName());
        assertEquals(UndoOutcome.RESTORED, notes.get(0).getOutcome());
        assertEquals(UndoOutcome.DELETED, notes.get(1).getOutcome());
    }

    @Test
    void bindingGoesBackToItsPreviousKeyOrToUnbound() {
        ProcessDefinition pd = new ProcessDefinition();
        pd.setBpmnXml(BPMN);
        when(processDefinitionRepository.findByFunctionUnitId(7L)).thenReturn(Optional.of(pd));

        AiGeneratedData data = AiGeneratedData.builder()
                .serviceTaskBindings(List.of(
                        Map.of("serviceTaskId", "svc_bound", "flowKey", "new-key"),
                        Map.of("serviceTaskId", "svc_bare", "flowKey", "new-key")))
                .build();
        String token = undo.capture(7L, "u1", "SERVICE_TASK_BINDINGS", data);

        // Apply 之后：两个任务都绑到了 new-key
        pd.setBpmnXml(XmlEncodingUtil.encode(
                com.developer.util.BpmnServiceTaskBindingPatcher.bind(BPMN,
                        Map.of("svc_bound", "new-key", "svc_bare", "new-key"))));

        List<UndoNote> notes = undo.undo(token, "u1");
        assertEquals(new UndoNote("serviceTaskBindings", "svc_bound", UndoOutcome.REBOUND, "old-key"), notes.get(0));
        assertEquals(new UndoNote("serviceTaskBindings", "svc_bare", UndoOutcome.UNBOUND, null), notes.get(1));

        String restored = XmlEncodingUtil.smartDecode(pd.getBpmnXml());
        Map<String, BpmnServiceTaskScanner.ServiceTaskInfo> byId = new java.util.HashMap<>();
        for (BpmnServiceTaskScanner.ServiceTaskInfo t : BpmnServiceTaskScanner.scan(restored)) {
            byId.put(t.id(), t);
        }
        assertEquals("old-key", byId.get("svc_bound").flowKey(), "the task that had a key goes back to it");
        assertNull(byId.get("svc_bare").flowKey(), "the task that had none is unbound again");
        assertNull(byId.get("svc_bare").serviceType(), "serviceType is cleared too, not left half-configured");
    }

    @Test
    void processScopeIsRestoredThroughTheNormalWritePath() {
        ProcessDefinition pd = new ProcessDefinition();
        pd.setBpmnXml(XmlEncodingUtil.encode(BPMN));
        when(processDefinitionRepository.findByFunctionUnitId(7L)).thenReturn(Optional.of(pd));

        String token = undo.capture(7L, "u1", "PROCESS",
                AiGeneratedData.builder().processDefinition(Map.of("bpmnXml", "<new/>")).build());

        List<UndoNote> notes = undo.undo(token, "u1");
        assertEquals(List.of(new UndoNote("processDefinition", null, UndoOutcome.RESTORED, null)), notes);

        ArgumentCaptor<AiGeneratedData> restored = ArgumentCaptor.forClass(AiGeneratedData.class);
        verify(aiWriteService).applyGeneratedData(eq(7L), restored.capture(), eq("PROCESS"));
        assertTrue(String.valueOf(restored.getValue().getProcessDefinition().get("bpmnXml")).contains("svc_bound"),
                "the pre-apply BPMN is written back, decoded");
    }
}
