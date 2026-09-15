package com.developer.service.impl;

import com.developer.dto.AiStudioChatRequest;
import com.developer.dto.FunctionUnitContextDTO;
import com.developer.enums.AiMode;
import com.developer.enums.AiPhase;
import com.developer.exception.AiGenerationException;
import com.developer.service.AiGenerationService;
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

    private AiStudioChatServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiStudioChatServiceImpl(aiGatewayClient, aiResponseParser, aiGenerationService);
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
        verifyNoInteractions(aiGenerationService);

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
                eq(AiPhase.GENERATION), eq(AiMode.MODIFY), eq(context), eq(1L), eq(null),
                eq("TABLES"), eq("tok")))
                .thenReturn(Map.of("reply", "Added an audit sub table.", "generatedData", generated));

        StudioChatResult result = service.chat(req, "tok");

        assertEquals("Added an audit sub table.", result.reply());
        assertEquals(Map.of("tableDefinitions", tables), result.proposal());
        assertEquals("TABLES", result.proposalScope());
        verifyNoInteractions(aiGatewayClient);
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
                eq(AiPhase.GENERATION), eq(AiMode.MODIFY), eq(context), eq(1L), eq(null),
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
