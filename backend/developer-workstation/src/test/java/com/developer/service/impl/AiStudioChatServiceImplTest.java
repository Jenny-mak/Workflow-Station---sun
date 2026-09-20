package com.developer.service.impl;

import com.developer.dto.AiStudioChatRequest;
import com.developer.dto.FunctionUnitContextDTO;
import com.developer.enums.AiMode;
import com.developer.enums.AiPhase;
import com.developer.exception.AiGenerationException;
import com.developer.service.AiGenerationService;
import com.developer.service.AiStudioChatService;
import com.developer.service.AiStudioChatService.StudioChatResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiStudioChatServiceImplTest {

    @Mock
    private AiGatewayClient aiGatewayClient;

    @Mock
    private AiResponseParser aiResponseParser;

    @Mock
    private AiGenerationService aiGenerationService;

    @Mock
    private FunctionUnitDocumentService documentService;

    private AiStudioChatServiceImpl service;

    @BeforeEach
    void setUp() {
        // 摘要器是纯函数，用真实实现：顺带覆盖"设计现状进 prompt"这条链路
        service = new AiStudioChatServiceImpl(aiGatewayClient, aiResponseParser, aiGenerationService,
                new AiStudioContextDigest(), documentService);
    }

    private AiStudioChatRequest request(String phase, String message,
                                        List<AiStudioChatRequest.HistoryMessage> history) {
        AiStudioChatRequest req = new AiStudioChatRequest();
        req.setFunctionUnitId(1L);
        req.setPhase(phase);
        req.setMessage(message);
        req.setHistory(history);
        return req;
    }

    private AiStudioChatRequest.HistoryMessage historyMessage(String role, String content) {
        AiStudioChatRequest.HistoryMessage m = new AiStudioChatRequest.HistoryMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    void chatBuildsPhasePromptAndTranscriptAndReturnsTrimmedReply() {
        Map<String, Object> httpResult = Map.of("status", 200);
        when(aiGatewayClient.chat(any(), eq("am-token"))).thenReturn(httpResult);
        when(aiResponseParser.parse(httpResult)).thenReturn(Map.of("reply", "  add a foreign key  "));

        StudioChatResult result = service.chat(request("TABLE_DESIGN", "How do I link the sub table?",
                List.of(historyMessage("USER", "hi"), historyMessage("ASSISTANT", "hello"))), "am-token");

        assertEquals("add a foreign key", result.reply());
        assertNull(result.proposal());
        // 顾问轮读上下文只为拼"当前设计现状"摘要；GENERATION 管线（callAiModel）依旧不碰
        verify(aiGenerationService).serializeFunctionUnitContext(1L);
        verify(aiGenerationService, never()).callAiModel(any(), any(), any(), any(), any(), any(), any(), any(), any());

        ArgumentCaptor<AiPromptBuilder.RenderedPrompt> prompt =
                ArgumentCaptor.forClass(AiPromptBuilder.RenderedPrompt.class);
        verify(aiGatewayClient).chat(prompt.capture(), eq("am-token"));
        assertTrue(prompt.getValue().system().contains("TABLE_DESIGN"));
        assertTrue(prompt.getValue().system().contains("define main and sub tables"));
        assertTrue(prompt.getValue().user().contains("User: hi"));
        assertTrue(prompt.getValue().user().contains("Assistant: hello"));
        assertTrue(prompt.getValue().user().endsWith("User: How do I link the sub table?"));
    }

    @Test
    void advisoryChatCarriesRequirementsAndTheCurrentPhaseDesignSection() {
        Map<String, Object> httpResult = Map.of("status", 200);
        when(aiGatewayClient.chat(any(), eq("tok"))).thenReturn(httpResult);
        when(aiResponseParser.parse(httpResult)).thenReturn(Map.of("reply", "ok"));
        java.util.Map<com.developer.enums.AiDocumentType, String> docs =
                new java.util.EnumMap<>(com.developer.enums.AiDocumentType.class);
        docs.put(com.developer.enums.AiDocumentType.REQUIREMENTS, "## Data Requirements\nAmounts keep 4 decimals.");
        docs.put(com.developer.enums.AiDocumentType.DESIGN,
                "## Process Design\nApprove node.\n\n## Table Design\nTable orders.\n\n## Form Design\nOrder form.");
        when(documentService.latestContents(1L)).thenReturn(docs);

        service.chat(request("TABLE_DESIGN", "What precision?", null), "tok");

        ArgumentCaptor<AiPromptBuilder.RenderedPrompt> prompt =
                ArgumentCaptor.forClass(AiPromptBuilder.RenderedPrompt.class);
        verify(aiGatewayClient).chat(prompt.capture(), eq("tok"));
        String user = prompt.getValue().user();
        assertTrue(user.contains("Amounts keep 4 decimals."));
        assertTrue(user.contains("Function Unit Design document — Table Design section\nTable orders."));
        assertFalse(user.contains("Approve node."));
        assertFalse(user.contains("Order form."));
        assertTrue(user.endsWith("User: What precision?"));
    }

    @Test
    void advisoryChatFallsBackToTheWholeDesignWhenItHasNoPhaseSections() {
        Map<String, Object> httpResult = Map.of("status", 200);
        when(aiGatewayClient.chat(any(), eq("tok"))).thenReturn(httpResult);
        when(aiResponseParser.parse(httpResult)).thenReturn(Map.of("reply", "ok"));
        when(documentService.latestContents(1L)).thenReturn(
                Map.of(com.developer.enums.AiDocumentType.DESIGN, "# Old design\nFree text."));

        service.chat(request("TABLE_DESIGN", "hi", null), "tok");

        ArgumentCaptor<AiPromptBuilder.RenderedPrompt> prompt =
                ArgumentCaptor.forClass(AiPromptBuilder.RenderedPrompt.class);
        verify(aiGatewayClient).chat(prompt.capture(), eq("tok"));
        assertTrue(prompt.getValue().user().contains("## Function Unit Design document\n# Old design\nFree text."));
        assertFalse(prompt.getValue().user().contains("Requirements document"));
    }

    @Test
    void proposalPassesTheDocumentsToTheGenerationPipeline() {
        AiStudioChatRequest req = request("TABLE_DESIGN", "add amount", null);
        req.setPropose(true);
        when(documentService.latestContents(1L)).thenReturn(
                Map.of(com.developer.enums.AiDocumentType.REQUIREMENTS, "Amounts keep 4 decimals."));

        AiStudioChatService.ProposalDraft draft = service.prepareProposal(req);

        assertEquals(List.of(Map.of("documentType", "REQUIREMENTS", "content", "Amounts keep 4 decimals.")),
                draft.documents());
    }

    @Test
    void advisoryChatGroundsThePromptInTheCurrentDesignWithoutDumpingBpmn() {
        com.developer.dto.FunctionUnitContextDTO context = new com.developer.dto.FunctionUnitContextDTO();
        context.setTableDefinitions(List.of(Map.of(
                "tableName", "orders", "tableType", "MAIN", "tableDisplayName", "Orders",
                "fieldDefinitions", List.of(
                        Map.of("fieldName", "id", "dataType", "BIGINT", "isPrimaryKey", true),
                        Map.of("fieldName", "order_no", "dataType", "VARCHAR")),
                "foreignKeys", List.of())));
        context.setProcessDefinition(Map.of("bpmnXml",
                "<bpmn:definitions xmlns:bpmn=\"x\"><bpmn:process id=\"p\"><bpmn:userTask id=\"u1\" name=\"Review\"/></bpmn:process></bpmn:definitions>"));
        when(aiGenerationService.serializeFunctionUnitContext(1L)).thenReturn(context);
        when(aiGatewayClient.chat(any(), any())).thenReturn(Map.of("ok", true));
        when(aiResponseParser.parse(any())).thenReturn(Map.of("reply", "looks fine"));

        service.chat(request("TABLE_DESIGN", "what is wrong with my tables?", null), "t");

        ArgumentCaptor<AiPromptBuilder.RenderedPrompt> prompt = ArgumentCaptor.forClass(AiPromptBuilder.RenderedPrompt.class);
        verify(aiGatewayClient).chat(prompt.capture(), any());
        String user = prompt.getValue().user();
        assertTrue(user.startsWith("## Current design (TABLE_DESIGN)"));
        assertTrue(user.contains("orders (MAIN) \"Orders\": id:BIGINT [PK], order_no:VARCHAR"));
        // 表设计阶段不带流程；任何阶段都不把 BPMN 原文塞进对话
        assertFalse(user.contains("<bpmn:"));
        assertFalse(user.contains("Process nodes"));
        assertTrue(user.endsWith("User: what is wrong with my tables?"));
    }

    @Test
    void advisoryChatFallsBackToNoDigestWhenTheContextCannotBeSerialized() {
        when(aiGenerationService.serializeFunctionUnitContext(1L))
                .thenThrow(new AiGenerationException("AI_CONTEXT_TOO_LARGE", "too big"));
        when(aiGatewayClient.chat(any(), any())).thenReturn(Map.of("ok", true));
        when(aiResponseParser.parse(any())).thenReturn(Map.of("reply", "still answering"));

        StudioChatResult result = service.chat(request("TABLE_DESIGN", "hello", null), "t");

        assertEquals("still answering", result.reply());
        ArgumentCaptor<AiPromptBuilder.RenderedPrompt> prompt = ArgumentCaptor.forClass(AiPromptBuilder.RenderedPrompt.class);
        verify(aiGatewayClient).chat(prompt.capture(), any());
        assertFalse(prompt.getValue().user().contains("## Current design"));
        assertTrue(prompt.getValue().user().startsWith("User: hello"));
    }

    @Test
    void historyEntryWithProposalIsRenderedIntoTranscriptAndCapped() {
        AiStudioChatRequest.HistoryMessage proposed = historyMessage("ASSISTANT", "Here is the proposed change.");
        proposed.setProposalScope("EMAIL_TEMPLATES");
        // 有序 Map：name 必须排在超长 subject 前面，才不会被截断掉（Map.of 的迭代顺序不固定）
        Map<String, Object> template = new java.util.LinkedHashMap<>();
        template.put("name", "Order Shipped");
        template.put("subject", "x".repeat(7000));
        proposed.setProposal(Map.of("emailTemplates", List.of(template)));
        when(aiGatewayClient.chat(any(), any())).thenReturn(Map.of("ok", true));
        when(aiResponseParser.parse(any())).thenReturn(Map.of("reply", "sure"));

        service.chat(request("EMAIL_TEMPLATES", "change its subject",
                List.of(historyMessage("USER", "add a template"), proposed)), "t");

        ArgumentCaptor<AiPromptBuilder.RenderedPrompt> prompt = ArgumentCaptor.forClass(AiPromptBuilder.RenderedPrompt.class);
        verify(aiGatewayClient).chat(prompt.capture(), any());
        String user = prompt.getValue().user();
        assertTrue(user.contains("[Proposed change, scope=EMAIL_TEMPLATES"));
        assertTrue(user.contains("\"name\":\"Order Shipped\""));
        assertTrue(user.contains("…(truncated)"));
        assertTrue(user.length() < AiStudioChatServiceImpl.HISTORY_PROPOSAL_CHAR_CAP + 1500,
                "proposal JSON is capped, not dumped in full");
        assertTrue(user.endsWith("User: change its subject"));
    }

    @Test
    void chatWithoutHistorySendsBareMessage() {
        when(aiGatewayClient.chat(any(), any())).thenReturn(Map.of());
        when(aiResponseParser.parse(any())).thenReturn(Map.of("reply", "ok"));

        service.chat(request("PROCESS_DESIGN", "explain gateways", null), "t");

        ArgumentCaptor<AiPromptBuilder.RenderedPrompt> prompt =
                ArgumentCaptor.forClass(AiPromptBuilder.RenderedPrompt.class);
        verify(aiGatewayClient).chat(prompt.capture(), eq("t"));
        assertEquals("User: explain gateways", prompt.getValue().user());
    }

    @Test
    void blankReplyFailsExplicitly() {
        when(aiGatewayClient.chat(any(), any())).thenReturn(Map.of());
        when(aiResponseParser.parse(any())).thenReturn(Map.of("reply", "   "));

        AiGenerationException ex = assertThrows(AiGenerationException.class,
                () -> service.chat(request("VALIDATION", "ready?", null), "t"));
        assertEquals("AI_GATEWAY_EMPTY_RESPONSE", ex.getErrorCode());
    }

    @Test
    void unknownPhaseFailsExplicitly() {
        AiGenerationException ex = assertThrows(AiGenerationException.class,
                () -> service.chat(request("NOT_A_PHASE", "hi", null), "t"));
        assertEquals("AI_STUDIO_UNKNOWN_PHASE", ex.getErrorCode());
    }

    @Test
    void proposeRunsGenerationPipelineWithPhaseScope() {
        AiStudioChatRequest req = request("TABLE_DESIGN", "add an audit sub table", null);
        req.setPropose(true);

        FunctionUnitContextDTO context = new FunctionUnitContextDTO();
        List<Map<String, Object>> tables = List.of(Map.of("tableName", "audit"));
        // 模型在 scoped 轮次里顺手带上的范围外切片必须被裁掉，否则 Apply 会撞唯一约束
        Map<String, Object> generated = Map.of(
                "tableDefinitions", tables,
                "processDefinition", Map.of("bpmnXml", "should-be-stripped"));
        when(aiGenerationService.serializeFunctionUnitContext(1L)).thenReturn(context);
        when(aiGenerationService.determineMode(1L)).thenReturn(AiMode.MODIFY);
        when(aiGenerationService.callAiModel(any(),
                org.mockito.ArgumentMatchers.argThat((String msg) -> msg != null
                        && msg.startsWith("User: add an audit sub table")
                        && msg.contains("Regenerate ONLY the 'TABLES' slice")),
                eq(AiPhase.GENERATION), eq(AiMode.MODIFY), eq(context), eq(1L), eq(List.of()),
                eq("TABLES"), eq("tok")))
                .thenReturn(Map.of("reply", "Added an audit sub table.", "generatedData", generated));

        StudioChatResult result = service.chat(req, "tok");

        assertEquals("Added an audit sub table.", result.reply());
        assertEquals(Map.of("tableDefinitions", tables), result.proposal());
        assertEquals("TABLES", result.proposalScope());
        verifyNoInteractions(aiGatewayClient);
    }

    @Test
    void oneClickGeneratesTheWholeCoreDesignAndDropsTheSlicesItMustNotProduce() {
        AiStudioChatRequest req = request("PROCESS_DESIGN", "leave request with manager approval", null);
        FunctionUnitContextDTO context = new FunctionUnitContextDTO();
        List<Map<String, Object>> tables = List.of(Map.of("tableName", "leave_request"));
        Map<String, Object> process = Map.of("bpmnXml", "<bpmn/>");
        Map<String, Object> generated = Map.of(
                "name", "Renamed by the model",
                "tableDefinitions", tables,
                "processDefinition", process,
                "emailConnections", List.of(Map.of("name", "hr@example.com")),
                "mainTableViews", List.of(Map.of("viewName", "All")));
        when(aiGenerationService.serializeFunctionUnitContext(1L)).thenReturn(context);
        when(aiGenerationService.determineMode(1L)).thenReturn(AiMode.NEW);
        when(documentService.latestContents(1L)).thenReturn(Map.of());
        when(aiGenerationService.callAiModel(any(),
                org.mockito.ArgumentMatchers.argThat((String msg) -> msg != null
                        && msg.startsWith("User: leave request with manager approval")
                        && msg.contains("Generate the COMPLETE function unit design")
                        && msg.contains("processDefinition")),
                eq(AiPhase.GENERATION), eq(AiMode.NEW), eq(context), eq(1L), eq(List.of()),
                eq("ALL"), eq("tok")))
                .thenReturn(Map.of("reply", "Generated.", "generatedData", generated));

        AiStudioChatService.ProposalDraft draft = service.prepareOneClick(req);
        StudioChatResult result = service.runProposal(draft, "tok");

        assertEquals("ALL", draft.scope());
        assertEquals("PROCESS_DESIGN", draft.phase());
        assertEquals(Map.of("tableDefinitions", tables, "processDefinition", process), result.proposal());
        assertEquals("ALL", result.proposalScope());
    }

    @Test
    void proposeOnEmailPhaseUsesUpsertScopeAndKeepsOnlyItsSlice() {
        AiStudioChatRequest req = request("EMAIL_TEMPLATES", "add an approval notification", null);
        req.setPropose(true);

        FunctionUnitContextDTO context = new FunctionUnitContextDTO();
        List<Map<String, Object>> templates = List.of(Map.of("name", "Approved", "subject", "s", "bodyHtml", "<p/>"));
        Map<String, Object> generated = Map.of(
                "emailTemplates", templates,
                "emailConnections", List.of(Map.of("name", "should-be-stripped@x.com")));
        when(aiGenerationService.serializeFunctionUnitContext(1L)).thenReturn(context);
        when(aiGenerationService.determineMode(1L)).thenReturn(AiMode.MODIFY);
        when(aiGenerationService.callAiModel(any(),
                org.mockito.ArgumentMatchers.argThat((String msg) -> msg != null
                        && msg.contains("Regenerate ONLY the 'EMAIL_TEMPLATES' slice")
                        && msg.contains("applied as an upsert keyed by name")),
                eq(AiPhase.GENERATION), eq(AiMode.MODIFY), eq(context), eq(1L), eq(List.of()),
                eq("EMAIL_TEMPLATES"), eq("tok")))
                .thenReturn(Map.of("reply", "Added a template.", "generatedData", generated));

        StudioChatResult result = service.chat(req, "tok");

        assertEquals(Map.of("emailTemplates", templates), result.proposal());
        assertEquals("EMAIL_TEMPLATES", result.proposalScope());
    }

    @Test
    void connectionsAndMonitorsPhasesMapToTheirOwnScopes() {
        assertEquals(java.util.Set.of("emailConnections"), AiStudioChatServiceImpl.allowedSlices("CONNECTIONS"));
        assertEquals(java.util.Set.of("emailMonitorRules"), AiStudioChatServiceImpl.allowedSlices("EMAIL_MONITORS"));
        assertTrue(AiStudioChatServiceImpl.allowedSlices("ALL").contains("emailTemplates"));
        assertEquals(java.util.Set.of("mainTableViews"), AiStudioChatServiceImpl.allowedSlices("VIEWS"));
        assertTrue(AiStudioChatServiceImpl.UPSERT_SCOPES.contains("VIEWS"));
        assertEquals(java.util.Set.of("serviceTaskBindings"), AiStudioChatServiceImpl.allowedSlices("SERVICE_TASK_BINDINGS"));
        assertTrue(AiStudioChatServiceImpl.UPSERT_SCOPES.contains("SERVICE_TASK_BINDINGS"));
        // 非 upsert scope 的提案消息不带 upsert 提示
        assertFalse(AiStudioChatServiceImpl.UPSERT_SCOPES.contains("TABLES"));
    }

    @Test
    void proposeOnUnsupportedPhaseFailsExplicitly() {
        AiStudioChatRequest req = request("VALIDATION", "change something", null);
        req.setPropose(true);

        AiGenerationException ex = assertThrows(AiGenerationException.class, () -> service.chat(req, "t"));
        assertEquals("AI_STUDIO_PROPOSAL_UNSUPPORTED_PHASE", ex.getErrorCode());
    }

    @Test
    void proposeWithNeitherReplyNorDataFailsExplicitly() {
        AiStudioChatRequest req = request("PROCESS_DESIGN", "change flow", null);
        req.setPropose(true);

        when(aiGenerationService.serializeFunctionUnitContext(1L)).thenReturn(new FunctionUnitContextDTO());
        when(aiGenerationService.determineMode(1L)).thenReturn(AiMode.NEW);
        when(aiGenerationService.callAiModel(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of());

        AiGenerationException ex = assertThrows(AiGenerationException.class, () -> service.chat(req, "t"));
        assertEquals("AI_STUDIO_PROPOSAL_EMPTY", ex.getErrorCode());
    }
}
