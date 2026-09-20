package com.developer.component.impl;

import com.developer.component.AiStudioChatComponent;
import com.developer.dto.AiStudioApplyRequest;
import com.developer.dto.AiStudioChatRequest;
import com.developer.dto.AiStudioOneClickRequest;
import com.developer.dto.AiStudioProposalJobResponse;
import com.developer.dto.AiStudioProposalPreview;
import com.developer.entity.AiDocument;
import com.developer.entity.AiStudioMessage;
import com.developer.enums.AiDocumentType;
import com.developer.enums.AiMode;
import com.developer.exception.AiGenerationException;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.security.WorkspaceAccessAction;
import com.developer.service.AiStudioChatService;
import com.developer.service.AiStudioChatService.StudioChatResult;
import com.developer.service.AiStudioProposalJobService;
import com.developer.service.impl.AiStudioProposalPreviewer;
import com.developer.service.impl.AiStudioThreadService;
import com.developer.service.impl.FunctionUnitDocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiStudioOneClickComponentImplTest {

    private static final Map<String, Object> PROPOSAL =
            Map.of("tableDefinitions", List.of(Map.of("tableName", "leave_request")));

    @Mock AiStudioChatService chatService;
    @Mock AiStudioChatComponent chatComponent;
    @Mock AiStudioProposalJobService jobService;
    @Mock AiStudioProposalPreviewer previewer;
    @Mock AiStudioThreadService threadService;
    @Mock FunctionUnitDocumentService documentService;
    @Mock FunctionUnitWorkspaceAccessService accessService;

    private AiStudioOneClickComponentImpl component;

    @BeforeEach
    void setUp() {
        component = new AiStudioOneClickComponentImpl(chatService, chatComponent, jobService, previewer,
                threadService, documentService, accessService, new ObjectMapper());
    }

    private static AiStudioOneClickRequest request(String requirements) {
        AiStudioOneClickRequest request = new AiStudioOneClickRequest();
        request.setFunctionUnitId(7L);
        request.setRequirements(requirements);
        return request;
    }

    private static AiStudioProposalPreview preview(AiStudioProposalPreview.Severity... issues) {
        AiStudioProposalPreview preview = AiStudioProposalPreview.builder().build();
        preview.setChecked(true);
        for (AiStudioProposalPreview.Severity severity : issues) {
            preview.getIssues().add(AiStudioProposalPreview.Issue.builder()
                    .severity(severity).errorType("X").fieldPath("p").description("d").build());
        }
        return preview;
    }

    /** 提交后的三个步骤，供测试按作业线程的顺序手动驱动。 */
    private record Steps(Supplier<StudioChatResult> work, UnaryOperator<StudioChatResult> commit,
                         Consumer<StudioChatResult> onSucceeded) {
    }

    private Steps start(String requirements) {
        return start(request(requirements));
    }

    @SuppressWarnings("unchecked")
    private Steps start(AiStudioOneClickRequest request) {
        AiStudioChatService.ProposalDraft draft = new AiStudioChatService.ProposalDraft(
                7L, "PROCESS_DESIGN", "ALL", "msg", null, AiMode.NEW, List.of());
        when(chatService.prepareOneClick(any())).thenReturn(draft);
        when(jobService.submit(any(AiStudioProposalJobService.JobRequest.class), any(Supplier.class),
                any(UnaryOperator.class), any(Consumer.class))).thenReturn(new AiStudioProposalJobResponse());

        component.start(request, "u1", "tok");

        ArgumentCaptor<Supplier<StudioChatResult>> work = ArgumentCaptor.forClass(Supplier.class);
        ArgumentCaptor<UnaryOperator<StudioChatResult>> commit = ArgumentCaptor.forClass(UnaryOperator.class);
        ArgumentCaptor<Consumer<StudioChatResult>> hook = ArgumentCaptor.forClass(Consumer.class);
        verify(jobService).submit(any(AiStudioProposalJobService.JobRequest.class), work.capture(),
                commit.capture(), hook.capture());
        return new Steps(work.getValue(), commit.getValue(), hook.getValue());
    }

    private static AiStudioMessage message(long id) {
        AiStudioMessage message = new AiStudioMessage();
        message.setId(id);
        return message;
    }

    @Test
    void refusesToStartWithNeitherADescriptionNorARequirementsDocument() {
        when(documentService.latest(7L, AiDocumentType.REQUIREMENTS)).thenReturn(Optional.empty());

        AiGenerationException e = assertThrows(AiGenerationException.class,
                () -> component.start(request("  "), "u1", "tok"));

        assertEquals("AI_STUDIO_ONE_CLICK_NO_INPUT", e.getErrorCode());
        verify(accessService).assertCanAccess(7L, WorkspaceAccessAction.MODIFY);
        verifyNoInteractions(jobService, chatService);
    }

    @Test
    void cleanResultIsAppliedMarkedAppliedAndRecordedAsTheFirstRequirementsVersion() {
        Steps steps = start("leave request with manager approval");
        when(chatService.runProposal(any(), eq("tok"))).thenReturn(new StudioChatResult("Generated.", PROPOSAL, "ALL"));
        when(previewer.preview(eq(7L), eq("ALL"), any())).thenReturn(preview(AiStudioProposalPreview.Severity.WARNING));
        when(documentService.latest(7L, AiDocumentType.REQUIREMENTS)).thenReturn(Optional.empty());
        when(threadService.appendAssistantMessage(eq(7L), eq("PROCESS_DESIGN"), any(), any(), any(), any(), any()))
                .thenReturn(message(42L));

        StudioChatResult generated = steps.work().get();
        StudioChatResult committed = steps.commit().apply(generated);
        steps.onSucceeded().accept(committed);

        ArgumentCaptor<AiStudioApplyRequest> apply = ArgumentCaptor.forClass(AiStudioApplyRequest.class);
        verify(chatComponent).applyProposal(apply.capture(), eq("u1"));
        assertEquals("ALL", apply.getValue().getScope());
        assertEquals(PROPOSAL, apply.getValue().getGeneratedData());
        assertSame(generated, committed);
        verify(documentService).append(7L, AiDocumentType.REQUIREMENTS, "leave request with manager approval\n", 0,
                "AI_ONE_CLICK", "u1");
        verify(threadService).markApplied(eq(7L), eq(42L), eq(true), any());

        ArgumentCaptor<AiStudioChatRequest> chat = ArgumentCaptor.forClass(AiStudioChatRequest.class);
        verify(chatService).prepareOneClick(chat.capture());
        assertEquals("PROCESS_DESIGN", chat.getValue().getPhase());
        assertEquals("leave request with manager approval", chat.getValue().getMessage());
    }

    @Test
    void anExistingRequirementsDocumentIsExtendedNotReplaced() {
        AiDocument existing = new AiDocument();
        existing.setVersion(3);
        existing.setContent("# Requirements\n\nOriginal text\n");
        when(documentService.latest(7L, AiDocumentType.REQUIREMENTS)).thenReturn(Optional.of(existing));
        Steps steps = start("also add a cancel action");
        when(chatService.runProposal(any(), any())).thenReturn(new StudioChatResult("Generated.", PROPOSAL, "ALL"));
        when(previewer.preview(anyLong(), anyString(), any())).thenReturn(preview());

        steps.commit().apply(steps.work().get());

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(documentService).append(eq(7L), eq(AiDocumentType.REQUIREMENTS), content.capture(), eq(3),
                eq("AI_ONE_CLICK"), eq("u1"));
        assertTrue(content.getValue().startsWith("# Requirements\n\nOriginal text\n\n## Additional requirements"));
        assertTrue(content.getValue().endsWith("\n\nalso add a cancel action\n"));
    }

    @Test
    void aFollowUpRoundAppliesButKeepsItsFixInstructionsOutOfTheRequirementsDocument() {
        AiStudioOneClickRequest followUp = request("Fix these problems: forms[0] binds an unknown table");
        followUp.setFollowUp(true);
        Steps steps = start(followUp);
        when(chatService.runProposal(any(), any())).thenReturn(new StudioChatResult("Fixed.", PROPOSAL, "ALL"));
        when(previewer.preview(anyLong(), anyString(), any())).thenReturn(preview());

        steps.commit().apply(steps.work().get());

        verify(chatComponent).applyProposal(any(), eq("u1"));
        verify(documentService, never()).append(anyLong(), any(), anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void aResultWithValidationErrorsIsLeftUnapplied() {
        Steps steps = start("leave request");
        when(chatService.runProposal(any(), any())).thenReturn(new StudioChatResult("Generated.", PROPOSAL, "ALL"));
        when(previewer.preview(anyLong(), anyString(), any())).thenReturn(preview(AiStudioProposalPreview.Severity.ERROR));
        when(threadService.appendAssistantMessage(anyLong(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(message(42L));

        StudioChatResult committed = steps.commit().apply(steps.work().get());
        steps.onSucceeded().accept(committed);

        verify(chatComponent, never()).applyProposal(any(), any());
        verify(documentService, never()).append(anyLong(), any(), anyString(), anyInt(), anyString(), anyString());
        verify(threadService, never()).markApplied(anyLong(), anyLong(), eq(true), any());
    }

    @Test
    void aFailedAutoApplyKeepsTheProposalWithAWarningInsteadOfFailingTheJob() {
        Steps steps = start("leave request");
        when(chatService.runProposal(any(), any())).thenReturn(new StudioChatResult("Generated.", PROPOSAL, "ALL"));
        when(previewer.preview(anyLong(), anyString(), any())).thenReturn(preview());
        doThrow(new AiGenerationException("AI_LOCK_CONFLICT", "locked by bob"))
                .when(chatComponent).applyProposal(any(), any());
        when(threadService.appendAssistantMessage(anyLong(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(message(42L));

        StudioChatResult committed = steps.commit().apply(steps.work().get());
        steps.onSucceeded().accept(committed);

        assertEquals(PROPOSAL, committed.proposal());
        AiStudioProposalPreview.Issue issue = committed.preview().getIssues().get(0);
        assertEquals(AiStudioProposalPreview.Severity.WARNING, issue.getSeverity());
        assertEquals("ONE_CLICK_AUTO_APPLY_FAILED", issue.getErrorType());
        assertTrue(issue.getDescription().contains("locked by bob"));
        verify(documentService, never()).append(anyLong(), any(), anyString(), anyInt(), anyString(), anyString());
        verify(threadService, never()).markApplied(anyLong(), anyLong(), eq(true), any());
    }
}
