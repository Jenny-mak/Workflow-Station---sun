package com.developer.component.impl;

import com.developer.entity.AiStudioThreadState;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.security.WorkspaceAccessAction;
import com.developer.service.impl.AiStudioDocumentSyncService;
import com.developer.service.impl.AiStudioThreadEventHub;
import com.developer.service.impl.AiStudioThreadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 确认阶段触发文档同步：只有新确认的阶段才触发，进度减少或不变不触发。
 */
class AiStudioThreadComponentImplTest {

    private final AiStudioThreadService threads = mock(AiStudioThreadService.class);
    private final FunctionUnitWorkspaceAccessService access = mock(FunctionUnitWorkspaceAccessService.class);
    private final AiStudioDocumentSyncService sync = mock(AiStudioDocumentSyncService.class);
    private AiStudioThreadComponentImpl component;

    @BeforeEach
    void setUp() {
        component = new AiStudioThreadComponentImpl(threads, access, mock(AiStudioThreadEventHub.class), sync);
    }

    private void givenConfirmed(List<String> before, List<String> after) {
        when(threads.state(1L)).thenReturn(Optional.of(
                AiStudioThreadState.builder().functionUnitId(1L).completedPhases(before).build()));
        when(threads.saveCompletedPhases(eq(1L), anyList(), any())).thenReturn(after);
    }

    @Test
    void newlyConfirmedPhasesStartADocumentSync() {
        givenConfirmed(List.of("PROCESS_DESIGN"), List.of("PROCESS_DESIGN", "TABLE_DESIGN"));

        component.saveCompletedPhases(1L, List.of("PROCESS_DESIGN", "TABLE_DESIGN"), "tok");

        verify(access).assertCanAccess(1L, WorkspaceAccessAction.MODIFY);
        verify(sync).submit(eq(1L), eq(List.of("TABLE_DESIGN")), eq("TABLE_DESIGN"), eq("tok"), any());
    }

    @Test
    void firstProgressCountsEveryPhaseAsNew() {
        when(threads.state(1L)).thenReturn(Optional.empty());
        when(threads.saveCompletedPhases(eq(1L), anyList(), any())).thenReturn(List.of("PROCESS_DESIGN"));

        component.saveCompletedPhases(1L, List.of("PROCESS_DESIGN"), null);

        verify(sync).submit(eq(1L), eq(List.of("PROCESS_DESIGN")), eq("PROCESS_DESIGN"), eq(null), any());
    }

    @Test
    void resetOrUnchangedProgressDoesNotStartASync() {
        givenConfirmed(List.of("PROCESS_DESIGN", "TABLE_DESIGN"), List.of("PROCESS_DESIGN"));
        component.saveCompletedPhases(1L, List.of("PROCESS_DESIGN"), "tok");

        givenConfirmed(List.of("PROCESS_DESIGN"), List.of("PROCESS_DESIGN"));
        component.saveCompletedPhases(1L, List.of("PROCESS_DESIGN"), "tok");

        verify(sync, never()).submit(any(), anyList(), anyString(), any(), any());
    }

    @Test
    void checkNowIsAFullCheckIntoTheCallersPhase() {
        component.checkDocuments(1L, "FORM_DESIGN", "tok");

        verify(access).assertCanAccess(1L, WorkspaceAccessAction.MODIFY);
        verify(sync).submit(eq(1L), eq(List.of()), eq("FORM_DESIGN"), eq("tok"), any());
    }
}
