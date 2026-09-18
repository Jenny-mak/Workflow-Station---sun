package com.developer.service.impl;

import com.developer.dto.FunctionUnitContextDTO;
import com.developer.dto.HermesMasterChatRequest;
import com.developer.exception.AiGenerationException;
import com.developer.service.AiGenerationService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HermesMasterChatServiceImplTest {

    @Mock
    private AiGatewayClient aiGatewayClient;

    @Mock
    private AiResponseParser aiResponseParser;

    @Mock
    private AiGenerationService aiGenerationService;

    private HermesMasterChatServiceImpl service;

    @BeforeEach
    void setUp() {
        // 摘要器是纯函数，用真实实现
        service = new HermesMasterChatServiceImpl(aiGatewayClient, aiResponseParser, aiGenerationService,
                new AiStudioContextDigest());
    }

    private HermesMasterChatRequest request(String message, Long functionUnitId) {
        HermesMasterChatRequest req = new HermesMasterChatRequest();
        req.setMessage(message);
        req.setFunctionUnitId(functionUnitId);
        return req;
    }

    private HermesMasterChatRequest.HistoryMessage historyMessage(String role, String content) {
        HermesMasterChatRequest.HistoryMessage m = new HermesMasterChatRequest.HistoryMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private AiPromptBuilder.RenderedPrompt capturedPrompt(String amToken) {
        ArgumentCaptor<AiPromptBuilder.RenderedPrompt> prompt =
                ArgumentCaptor.forClass(AiPromptBuilder.RenderedPrompt.class);
        verify(aiGatewayClient).chat(prompt.capture(), eq(amToken));
        return prompt.getValue();
    }

    @Test
    void chatWithoutFunctionUnitSkipsTheDesignDigestAndCarriesPageAndHistory() {
        Map<String, Object> httpResult = Map.of("status", 200);
        when(aiGatewayClient.chat(any(), eq("tok"))).thenReturn(httpResult);
        when(aiResponseParser.parse(httpResult)).thenReturn(Map.of("reply", "  Open Function Units first.  "));

        HermesMasterChatRequest req = request("How do I create a function unit?", null);
        req.setPage("FunctionUnits");
        req.setHistory(List.of(historyMessage("USER", "hi"), historyMessage("ASSISTANT", "hello")));

        assertEquals("Open Function Units first.", service.chat(req, "tok"));
        verifyNoInteractions(aiGenerationService);

        AiPromptBuilder.RenderedPrompt prompt = capturedPrompt("tok");
        assertTrue(prompt.system().contains("Hermes Master"));
        assertTrue(prompt.user().contains("DW page: FunctionUnits"));
        assertFalse(prompt.user().contains("Current function unit design"));
        assertTrue(prompt.user().contains("User: hi"));
        assertTrue(prompt.user().contains("Assistant: hello"));
        assertTrue(prompt.user().endsWith("User: How do I create a function unit?"));
    }

    @Test
    void chatOnAFunctionUnitGroundsThePromptInItsRealTableNames() {
        FunctionUnitContextDTO context = new FunctionUnitContextDTO();
        context.setTableDefinitions(List.of(Map.of("tableName", "purchase_order", "tableType", "MAIN")));
        when(aiGenerationService.serializeFunctionUnitContext(7L)).thenReturn(context);
        Map<String, Object> httpResult = Map.of("status", 200);
        when(aiGatewayClient.chat(any(), eq("tok"))).thenReturn(httpResult);
        when(aiResponseParser.parse(httpResult)).thenReturn(Map.of("reply", "ok"));

        service.chat(request("Review my tables", 7L), "tok");

        AiPromptBuilder.RenderedPrompt prompt = capturedPrompt("tok");
        assertTrue(prompt.user().contains("Current function unit design"));
        assertTrue(prompt.user().contains("purchase_order"));
    }

    @Test
    void chatContinuesWithoutTheDigestWhenTheContextCannotBeSerialized() {
        when(aiGenerationService.serializeFunctionUnitContext(7L))
                .thenThrow(new AiGenerationException("AI_CONTEXT_TOO_LARGE", "too large"));
        Map<String, Object> httpResult = Map.of("status", 200);
        when(aiGatewayClient.chat(any(), eq("tok"))).thenReturn(httpResult);
        when(aiResponseParser.parse(httpResult)).thenReturn(Map.of("reply", "ok"));

        assertEquals("ok", service.chat(request("Review my tables", 7L), "tok"));
        assertFalse(capturedPrompt("tok").user().contains("Current function unit design"));
    }

    @Test
    void chatDropsTheOldestHistoryFirstAndAlwaysKeepsTheLatestMessage() {
        Map<String, Object> httpResult = Map.of("status", 200);
        when(aiGatewayClient.chat(any(), eq("tok"))).thenReturn(httpResult);
        when(aiResponseParser.parse(httpResult)).thenReturn(Map.of("reply", "ok"));

        HermesMasterChatRequest req = request("latest question", null);
        req.setHistory(List.of(
                historyMessage("USER", "OLDEST " + "x".repeat(3990)),
                historyMessage("ASSISTANT", "y".repeat(4000)),
                historyMessage("USER", "z".repeat(4000)),
                historyMessage("ASSISTANT", "NEWEST " + "w".repeat(3990))));

        service.chat(req, "tok");

        String user = capturedPrompt("tok").user();
        assertFalse(user.contains("OLDEST"));
        assertTrue(user.contains("NEWEST"));
        assertTrue(user.endsWith("User: latest question"));
    }

    @Test
    void chatFailsExplicitlyWhenTheGatewayReturnsNoReplyText() {
        Map<String, Object> httpResult = Map.of("status", 200);
        when(aiGatewayClient.chat(any(), eq("tok"))).thenReturn(httpResult);
        when(aiResponseParser.parse(httpResult)).thenReturn(Map.of("reply", "   "));

        AiGenerationException e = assertThrows(AiGenerationException.class,
                () -> service.chat(request("hello", null), "tok"));
        assertEquals("AI_GATEWAY_EMPTY_RESPONSE", e.getErrorCode());
    }
}
