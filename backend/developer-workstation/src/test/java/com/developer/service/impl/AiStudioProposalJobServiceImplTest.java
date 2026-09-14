package com.developer.service.impl;

import com.developer.dto.AiStudioProposalJobResponse;
import com.developer.dto.AiStudioProposalJobResponse.Status;
import com.developer.exception.AiGenerationException;
import com.developer.service.AiStudioChatService.StudioChatResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiStudioProposalJobServiceImplTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AiStudioProposalJobServiceImpl service =
            new AiStudioProposalJobServiceImpl(executor, Duration.ofMinutes(30), Duration.ofMinutes(30), 200);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private AiStudioProposalJobResponse awaitTerminal(String jobId, String userId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            AiStudioProposalJobResponse snap = service.get(jobId, userId);
            if (snap.getStatus() == Status.SUCCEEDED || snap.getStatus() == Status.FAILED) return snap;
            Thread.sleep(10);
        }
        throw new AssertionError("job did not finish: " + jobId);
    }

    @Test
    void submitReturnsImmediatelyAndResultIsVisibleAfterCompletion() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AiStudioProposalJobResponse submitted = service.submit(1L, "TABLE_DESIGN", "u1", () -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new StudioChatResult("added table", Map.of("tableDefinitions", java.util.List.of()), "TABLES");
        });

        assertNotEquals(Status.SUCCEEDED, submitted.getStatus());
        assertNull(submitted.getProposal());
        release.countDown();

        AiStudioProposalJobResponse done = awaitTerminal(submitted.getJobId(), "u1");
        assertEquals(Status.SUCCEEDED, done.getStatus());
        assertEquals("added table", done.getReply());
        assertEquals("TABLES", done.getProposalScope());
        assertEquals(1L, done.getFunctionUnitId());
        assertEquals("TABLE_DESIGN", done.getPhase());
    }

    @Test
    void failureKeepsErrorCodeFromAiGenerationException() throws Exception {
        AiStudioProposalJobResponse submitted = service.submit(1L, "PROCESS_DESIGN", "u1", () -> {
            throw new AiGenerationException("AI_STUDIO_PROPOSAL_EMPTY", "nothing came back");
        });
        AiStudioProposalJobResponse done = awaitTerminal(submitted.getJobId(), "u1");
        assertEquals(Status.FAILED, done.getStatus());
        assertEquals("AI_STUDIO_PROPOSAL_EMPTY", done.getErrorCode());
        assertEquals("nothing came back", done.getErrorMessage());
    }

    @Test
    void unexpectedExceptionIsReportedAsGenericFailure() throws Exception {
        AiStudioProposalJobResponse submitted = service.submit(1L, "PROCESS_DESIGN", "u1", () -> {
            throw new IllegalStateException("boom");
        });
        AiStudioProposalJobResponse done = awaitTerminal(submitted.getJobId(), "u1");
        assertEquals(Status.FAILED, done.getStatus());
        assertEquals("AI_STUDIO_PROPOSAL_FAILED", done.getErrorCode());
    }

    @Test
    void resubmitWhileRunningReusesTheJobInsteadOfBurningTheModelTwice() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        java.util.function.Supplier<StudioChatResult> work = () -> {
            calls.incrementAndGet();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new StudioChatResult("r", null, null);
        };
        AiStudioProposalJobResponse first = service.submit(1L, "TABLE_DESIGN", "u1", work);
        AiStudioProposalJobResponse again = service.submit(1L, "TABLE_DESIGN", "u1", work);
        assertEquals(first.getJobId(), again.getJobId());

        // 另一个用户或另一个功能单元不共享作业
        AiStudioProposalJobResponse otherUser = service.submit(1L, "TABLE_DESIGN", "u2", work);
        AiStudioProposalJobResponse otherUnit = service.submit(2L, "TABLE_DESIGN", "u1", work);
        assertNotEquals(first.getJobId(), otherUser.getJobId());
        assertNotEquals(first.getJobId(), otherUnit.getJobId());

        release.countDown();
        awaitTerminal(first.getJobId(), "u1");
        awaitTerminal(otherUser.getJobId(), "u2");
        awaitTerminal(otherUnit.getJobId(), "u1");
        assertEquals(3, calls.get());

        // 终态之后再提交才是新作业
        AiStudioProposalJobResponse fresh = service.submit(1L, "TABLE_DESIGN", "u1", work);
        assertNotEquals(first.getJobId(), fresh.getJobId());
    }

    @Test
    void getRejectsUnknownJobsAndOtherUsersJobs() throws Exception {
        AiStudioProposalJobResponse submitted = service.submit(1L, "TABLE_DESIGN", "u1",
                () -> new StudioChatResult("r", null, null));
        awaitTerminal(submitted.getJobId(), "u1");

        AiGenerationException unknown = assertThrows(AiGenerationException.class,
                () -> service.get("nope", "u1"));
        assertEquals("AI_STUDIO_PROPOSAL_NOT_FOUND", unknown.getErrorCode());
        AiGenerationException foreign = assertThrows(AiGenerationException.class,
                () -> service.get(submitted.getJobId(), "u2"));
        assertEquals("AI_STUDIO_PROPOSAL_NOT_FOUND", foreign.getErrorCode());
    }

    @Test
    void terminalJobsExpireAfterRetention() throws Exception {
        AiStudioProposalJobServiceImpl shortLived =
                new AiStudioProposalJobServiceImpl(executor, Duration.ZERO, Duration.ofMinutes(30), 200);
        AiStudioProposalJobResponse submitted = shortLived.submit(1L, "TABLE_DESIGN", "u1",
                () -> new StudioChatResult("r", null, null));
        // 第一次 get 可能仍在跑；等到终态后，下一次 sweep 就会把它清掉
        for (int i = 0; i < 200; i++) {
            try {
                AiStudioProposalJobResponse snap = shortLived.get(submitted.getJobId(), "u1");
                if (snap.getStatus() == Status.SUCCEEDED) {
                    Thread.sleep(5);
                    continue;
                }
                Thread.sleep(10);
            } catch (AiGenerationException e) {
                assertEquals("AI_STUDIO_PROPOSAL_NOT_FOUND", e.getErrorCode());
                return;
            }
        }
        throw new AssertionError("expired job was never evicted");
    }

    @Test
    void runningJobPastMaxRuntimeIsFailedAndInterrupted() throws Exception {
        AiStudioProposalJobServiceImpl strict =
                new AiStudioProposalJobServiceImpl(executor, Duration.ofMinutes(30), Duration.ZERO, 200);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AiStudioProposalJobResponse submitted = strict.submit(1L, "TABLE_DESIGN", "u1", () -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return new StudioChatResult("late", null, null);
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        Thread.sleep(5);
        AiStudioProposalJobResponse snap = strict.get(submitted.getJobId(), "u1");
        assertEquals(Status.FAILED, snap.getStatus());
        assertEquals("AI_STUDIO_PROPOSAL_TIMED_OUT", snap.getErrorCode());
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        // 超时之后作业线程迟到的结果不能把 FAILED 翻回 SUCCEEDED
        Thread.sleep(20);
        assertEquals(Status.FAILED, strict.get(submitted.getJobId(), "u1").getStatus());
    }
}
