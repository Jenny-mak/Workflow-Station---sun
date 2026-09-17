package com.developer.component.impl;

import com.developer.dto.AiStudioChatRequest;
import com.developer.dto.AiStudioChatResponse;
import com.developer.dto.AiStudioProposalJobResponse;
import com.developer.dto.AiStudioProposalPreview;
import com.developer.entity.AiStudioMessage;
import com.developer.exception.AiGenerationException;
import com.developer.security.FunctionUnitWorkspaceAccessDeniedException;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.security.WorkspaceAccessAction;
import com.developer.service.AiLockService;
import com.developer.service.AiStudioChatService;
import com.developer.service.AiStudioChatService.StudioChatResult;
import com.developer.service.AiStudioProposalJobService;
import com.developer.service.AiValidationService;
import com.developer.service.AiWriteService;
import com.developer.service.impl.AiStudioProposalPreviewer;
import com.developer.service.impl.AiStudioProposalReferenceValidator;
import com.developer.service.impl.AiStudioThreadService;
import com.developer.service.impl.AiStudioUndoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 共享线程接线：对话轮/提案作业完成时由后端落库，模型历史取自共享线程，只读成员不能发言。 */
@ExtendWith(MockitoExtension.class)
class AiStudioChatComponentImplTest {

    @Mock AiStudioChatService chatService;
    @Mock AiStudioProposalJobService jobService;
    @Mock AiLockService lockService;
    @Mock AiValidationService validationService;
    @Mock AiWriteService writeService;
    @Mock FunctionUnitWorkspaceAccessService accessService;
    @Mock AiStudioProposalReferenceValidator referenceValidator;
    @Mock AiStudioProposalPreviewer previewer;
    @Mock AiStudioUndoService undoService;
    @Mock AiStudioThreadService threadService;

    private AiStudioChatComponentImpl component;

    @BeforeEach
    void setUp() {
        component = new AiStudioChatComponentImpl(chatService, jobService, lockService, validationService,
                writeService, accessService, referenceValidator, previewer, undoService, threadService,
                new ObjectMapper());
    }

    private static AiStudioChatRequest request(String message) {
        AiStudioChatRequest r = new AiStudioChatRequest();
        r.setFunctionUnitId(7L);
        r.setPhase("TABLE_DESIGN");
        r.setMessage(message);
        AiStudioChatRequest.HistoryMessage local = new AiStudioChatRequest.HistoryMessage();
        local.setRole("USER");
        local.setContent("from the browser");
        r.setHistory(List.of(local));
        return r;
    }

    private static AiStudioChatRequest.HistoryMessage shared(String content) {
        AiStudioChatRequest.HistoryMessage h = new AiStudioChatRequest.HistoryMessage();
        h.setRole("ASSISTANT");
        h.setContent(content);
        return h;
    }

    @Test
    void chatUsesTheSharedHistoryAndRecordsBothMessagesAfterTheReply() {
        AiStudioChatRequest req = request("how many tables?");
        when(threadService.recentHistory(7L, "TABLE_DESIGN")).thenReturn(List.of(shared("teammate discussion")));
        when(chatService.chat(any(), eq("am"))).thenAnswer(inv -> {
            AiStudioChatRequest seen = inv.getArgument(0);
            assertEquals("teammate discussion", seen.getHistory().get(0).getContent());
            return new StudioChatResult("two", null, null);
        });

        AiStudioChatResponse res = component.chat(req, "u1", "am");

        assertEquals("two", res.getReply());
        InOrder order = inOrder(accessService, chatService, threadService);
        order.verify(accessService).assertCanAccess(7L, WorkspaceAccessAction.MODIFY);
        order.verify(chatService).chat(any(), eq("am"));
        order.verify(threadService).appendUserMessage(eq(7L), eq("TABLE_DESIGN"), eq("how many tables?"), any());
        order.verify(threadService).appendAssistantMessage(eq(7L), eq("TABLE_DESIGN"), eq("two"),
                isNull(), isNull(), isNull(), any());
    }

    @Test
    void emptyOrBrokenSharedThreadFallsBackToTheClientHistoryAndNeverFailsTheRound() {
        AiStudioChatRequest req = request("hi");
        when(threadService.recentHistory(7L, "TABLE_DESIGN")).thenThrow(new IllegalStateException("table missing"));
        when(chatService.chat(any(), anyString())).thenAnswer(inv -> {
            assertEquals("from the browser", inv.<AiStudioChatRequest>getArgument(0).getHistory().get(0).getContent());
            return new StudioChatResult("hello", null, null);
        });
        doThrow(new IllegalStateException("table missing"))
                .when(threadService).appendUserMessage(any(), any(), any(), any());

        assertEquals("hello", component.chat(req, "u1", "am").getReply());

        AiStudioChatRequest empty = request("again");
        org.mockito.Mockito.doReturn(List.of()).when(threadService).recentHistory(7L, "TABLE_DESIGN");
        component.chat(empty, "u1", "am");
        assertEquals("from the browser", empty.getHistory().get(0).getContent());
    }

    @Test
    void failedRoundLeavesTheSharedThreadUntouched() {
        when(threadService.recentHistory(7L, "TABLE_DESIGN")).thenReturn(List.of());
        when(chatService.chat(any(), anyString())).thenThrow(new AiGenerationException("AI_GATEWAY_EMPTY_RESPONSE", "empty"));

        assertThrows(AiGenerationException.class, () -> component.chat(request("hi"), "u1", "am"));
        verify(threadService, never()).appendUserMessage(any(), any(), any(), any());
        verify(threadService, never()).appendAssistantMessage(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void readOnlyMembersCannotPostIntoTheSharedThread() {
        doThrow(new FunctionUnitWorkspaceAccessDeniedException("read only"))
                .when(accessService).assertCanAccess(7L, WorkspaceAccessAction.MODIFY);

        assertThrows(FunctionUnitWorkspaceAccessDeniedException.class, () -> component.chat(request("hi"), "u1", "am"));
        assertThrows(FunctionUnitWorkspaceAccessDeniedException.class,
                () -> component.startProposal(request("propose"), "u1", "am"));
        verifyNoInteractions(chatService, jobService, threadService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void proposalRecordsTheQuestionOnSubmitAndTheCardWhenTheJobSucceeds() {
        AiStudioChatRequest req = request("add orders");
        when(threadService.recentHistory(7L, "TABLE_DESIGN")).thenReturn(List.of());
        AiStudioChatService.ProposalDraft draft = new AiStudioChatService.ProposalDraft(
                7L, "TABLE_DESIGN", "TABLES", "msg", null, null, List.of());
        when(chatService.prepareProposal(req)).thenReturn(draft);
        AiStudioProposalJobResponse snapshot = AiStudioProposalJobResponse.builder().jobId("j1").build();
        ArgumentCaptor<Consumer<StudioChatResult>> hook = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<AiStudioProposalJobService.JobRequest> submitted =
                ArgumentCaptor.forClass(AiStudioProposalJobService.JobRequest.class);
        when(jobService.submit(submitted.capture(), any(Supplier.class), hook.capture())).thenReturn(snapshot);

        assertSame(snapshot, component.startProposal(req, "u1", "am"));
        // 原始消息与作者随作业落库：重启后"重新发起"、队友的"正在生成"提示都靠它
        assertEquals(new AiStudioProposalJobService.JobRequest(7L, "TABLE_DESIGN", "u1", "u1",
                "TABLE_DESIGN\nadd orders", "add orders"), submitted.getValue());
        verify(accessService).assertCanAccess(7L, WorkspaceAccessAction.MODIFY);
        verify(threadService).appendUserMessage(eq(7L), eq("TABLE_DESIGN"), eq("add orders"),
                eq(new AiStudioThreadService.Author("u1", "u1")));
        verify(threadService, never()).appendAssistantMessage(any(), any(), any(), any(), any(), any(), any());

        // 作业在后台成功：回调把提案卡写进共享线程（作者仍是发起人）
        Map<String, Object> data = Map.of("tableDefinitions", List.of());
        AiStudioProposalPreview preview = AiStudioProposalPreview.builder().checked(true).build();
        when(threadService.appendAssistantMessage(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiStudioMessage());
        hook.getValue().accept(new StudioChatResult("Here you go", data, "TABLES", preview));
        verify(threadService).appendAssistantMessage(7L, "TABLE_DESIGN", "Here you go", "TABLES", data, preview,
                new AiStudioThreadService.Author("u1", "u1"));
    }
}
