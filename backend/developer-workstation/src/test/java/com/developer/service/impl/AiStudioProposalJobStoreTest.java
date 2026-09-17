package com.developer.service.impl;

import com.developer.dto.AiStudioProposalJobResponse;
import com.developer.dto.AiStudioProposalJobResponse.Status;
import com.developer.dto.AiStudioProposalPreview;
import com.developer.entity.AiStudioProposalJob;
import com.developer.repository.AiStudioProposalJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 快照落库：去掉 PostgreSQL 不接受的 NUL、预览往返、只返回本人的作业、启动时判中断。 */
class AiStudioProposalJobStoreTest {

    private static final String NUL = String.valueOf((char) 0);

    private final AiStudioProposalJobRepository repository = mock(AiStudioProposalJobRepository.class);
    private final AiStudioProposalJobStore store = new AiStudioProposalJobStore(repository, new ObjectMapper());

    @Test
    void savedSnapshotsNeverCarryNulCharacters() {
        AiStudioProposalJobResponse snapshot = AiStudioProposalJobResponse.builder()
                .jobId("j1").functionUnitId(1L).phase("EMAIL_TEMPLATES").status(Status.SUCCEEDED)
                .message("add" + NUL + " one").reply("ok" + NUL)
                .proposal(Map.of("emailTemplates", List.of(Map.of("name", "T" + NUL))))
                .proposalScope("EMAIL_TEMPLATES")
                .preview(AiStudioProposalPreview.builder().checked(true).build())
                .submittedAt(Instant.now())
                .build();

        store.save(snapshot, "u1", "EMAIL_TEMPLATES" + NUL + "add");

        ArgumentCaptor<AiStudioProposalJob> saved = ArgumentCaptor.forClass(AiStudioProposalJob.class);
        verify(repository).save(saved.capture());
        AiStudioProposalJob row = saved.getValue();
        assertEquals("EMAIL_TEMPLATESadd", row.getRequestKey());
        assertEquals("add one", row.getMessage());
        assertEquals("ok", row.getReply());
        assertEquals(Map.of("emailTemplates", List.of(Map.of("name", "T"))), row.getProposal());
        assertEquals(true, row.getPreview().get("checked"));
        assertEquals("SUCCEEDED", row.getStatus());
    }

    @Test
    void findReturnsOnlyTheOwnersSnapshotWithItsPreview() {
        AiStudioProposalJob row = AiStudioProposalJob.builder()
                .jobId("j1").functionUnitId(1L).phase("VIEW_DESIGN").userId("u1").status("FAILED")
                .errorCode(AiStudioProposalJobStore.INTERRUPTED).message("again")
                .preview(Map.of("checked", true, "items", List.of()))
                .submittedAt(Instant.now()).build();
        when(repository.findById("j1")).thenReturn(Optional.of(row));

        AiStudioProposalJobResponse mine = store.find("j1", "u1").orElseThrow();
        assertEquals(Status.FAILED, mine.getStatus());
        assertEquals("again", mine.getMessage());
        assertTrue(mine.getPreview().isChecked());
        assertTrue(store.find("j1", "u2").isEmpty());
    }

    @Test
    void startupMarksUnfinishedJobsInterrupted() {
        when(repository.markUnfinishedAsInterrupted(eq(AiStudioProposalJobStore.INTERRUPTED), any(), any())).thenReturn(2);
        store.markInterruptedOnStartup();
        verify(repository).markUnfinishedAsInterrupted(eq(AiStudioProposalJobStore.INTERRUPTED), any(), any());
    }
}
