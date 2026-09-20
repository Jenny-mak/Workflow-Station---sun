package com.developer.service.impl;

import com.developer.dto.AiStudioProposalJobResponse;
import com.developer.dto.AiStudioProposalJobResponse.Status;
import com.developer.dto.AiStudioThreadEvent;
import com.developer.exception.AiGenerationException;
import com.developer.service.AiStudioChatService.StudioChatResult;
import com.developer.service.AiStudioProposalJobService;
import com.developer.service.AiStudioProposalJobService.JobRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 作业快照落库、库回落查询、开始/结束事件；落库失败不影响作业本身。 */
class AiStudioProposalJobPersistenceTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AiStudioProposalJobStore store = mock(AiStudioProposalJobStore.class);
    private final List<AiStudioThreadEvent> events = new CopyOnWriteArrayList<>();
    private AiStudioProposalJobServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiStudioProposalJobServiceImpl(executor, Duration.ofMinutes(30), Duration.ofMinutes(30), 200,
                store, e -> events.add((AiStudioThreadEvent) e), Duration.ofHours(24));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static JobRequest request(Long fu, String key) {
        return new JobRequest(fu, "EMAIL_TEMPLATES", "u1", "Alice", key, "add");
    }

    private static StudioChatResult blockUntil(CountDownLatch release) {
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new StudioChatResult("late", null, null);
    }

    private AiStudioProposalJobResponse await(String jobId) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            AiStudioProposalJobResponse s = service.get(jobId, "u1");
            if (s.getStatus() != Status.PENDING && s.getStatus() != Status.RUNNING) {
                Thread.sleep(50); // 终态之后的落库、回调与事件在同一线程紧接着发生
                return s;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("job did not finish");
    }

    @Test
    void everyTransitionIsPersistedWithTheOriginalMessage() throws Exception {
        AiStudioProposalJobResponse submitted = service.submit(request(1L, "k-add"),
                () -> new StudioChatResult("done", Map.of("emailTemplates", List.of()), "EMAIL_TEMPLATES"), null);
        AiStudioProposalJobResponse done = await(submitted.getJobId());
        assertEquals(Status.SUCCEEDED, done.getStatus());
        assertEquals("add", done.getMessage());
        assertEquals("Alice", done.getAuthorName());

        ArgumentCaptor<AiStudioProposalJobResponse> saved = ArgumentCaptor.forClass(AiStudioProposalJobResponse.class);
        verify(store, atLeast(3)).save(saved.capture(), eq("u1"), eq("k-add"));
        assertEquals(List.of(Status.PENDING, Status.RUNNING, Status.SUCCEEDED),
                saved.getAllValues().stream().map(AiStudioProposalJobResponse::getStatus).toList());
        assertEquals("done", saved.getAllValues().get(2).getReply());
    }

    @Test
    void startAndFinishEventsBracketTheJobAndTheCardLandsFirst() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        AiStudioProposalJobResponse ok = service.submit(request(1L, "k1"),
                () -> new StudioChatResult("r", Map.of("x", 1), "EMAIL_TEMPLATES"),
                r -> order.add("hook after " + events.size() + " event(s)"));
        await(ok.getJobId());
        AiStudioProposalJobResponse failed = service.submit(request(2L, "k2"),
                () -> { throw new AiGenerationException("X", "no"); }, null);
        await(failed.getJobId());

        assertEquals(List.of("hook after 1 event(s)"), order, "completion hook runs before the FINISHED event");
        assertEquals(List.of(
                AiStudioThreadEvent.proposal(AiStudioThreadEvent.PROPOSAL_STARTED, 1L, "EMAIL_TEMPLATES", ok.getJobId(), "u1", "Alice"),
                AiStudioThreadEvent.proposal(AiStudioThreadEvent.PROPOSAL_FINISHED, 1L, "EMAIL_TEMPLATES", ok.getJobId(), "u1", "Alice"),
                AiStudioThreadEvent.proposal(AiStudioThreadEvent.PROPOSAL_STARTED, 2L, "EMAIL_TEMPLATES", failed.getJobId(), "u1", "Alice"),
                AiStudioThreadEvent.proposal(AiStudioThreadEvent.PROPOSAL_FINISHED, 2L, "EMAIL_TEMPLATES", failed.getJobId(), "u1", "Alice")),
                events);
    }

    @Test
    void cancelAndSupersedeAlsoAnnounceTheEndOnce() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AiStudioProposalJobResponse first = service.submit(request(1L, "k1"), () -> blockUntil(release), null);
        AiStudioProposalJobResponse second = service.submit(request(1L, "k2"), () -> blockUntil(release), null);
        service.cancel(second.getJobId(), "u1");
        service.cancel(second.getJobId(), "u1");
        release.countDown();
        Thread.sleep(150);

        for (String jobId : List.of(first.getJobId(), second.getJobId())) {
            long finished = events.stream().filter(e -> e.type().equals(AiStudioThreadEvent.PROPOSAL_FINISHED)
                    && e.jobId().equals(jobId)).count();
            assertEquals(1, finished, "job " + jobId + " announces its end exactly once");
        }
    }

    @Test
    void jobsUnknownInMemoryFallBackToTheStoreForTheOwnerOnly() {
        AiStudioProposalJobResponse interrupted = AiStudioProposalJobResponse.builder()
                .jobId("old").functionUnitId(1L).status(Status.FAILED)
                .errorCode(AiStudioProposalJobStore.INTERRUPTED).message("add").build();
        when(store.find("old", "u1")).thenReturn(Optional.of(interrupted));
        when(store.find("old", "u2")).thenReturn(Optional.empty());

        assertSame(interrupted, service.get("old", "u1"));
        assertSame(interrupted, service.cancel("old", "u1"), "cancelling a job from a previous process is idempotent");
        assertEquals("AI_STUDIO_PROPOSAL_NOT_FOUND",
                assertThrows(AiGenerationException.class, () -> service.get("old", "u2")).getErrorCode());

        when(store.find("broken", "u1")).thenThrow(new IllegalStateException("db down"));
        assertEquals("AI_STUDIO_PROPOSAL_NOT_FOUND",
                assertThrows(AiGenerationException.class, () -> service.get("broken", "u1")).getErrorCode());
    }

    @Test
    void activeJobsComeFromMemoryFirstThenTheStore() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AiStudioProposalJobResponse running = service.submit(request(1L, "k1"), () -> blockUntil(release), null);
        assertEquals(running.getJobId(), service.findActive(1L, "u1").orElseThrow().getJobId());
        assertTrue(service.findActive(1L, "u2").isEmpty());
        List<AiStudioProposalJobService.ActiveJob> active = service.activeJobs(1L);
        assertEquals(1, active.size());
        assertEquals("u1", active.get(0).userId());
        assertEquals("Alice", active.get(0).snapshot().getAuthorName());
        release.countDown();
        await(running.getJobId());
        assertTrue(service.activeJobs(1L).isEmpty());

        AiStudioProposalJobResponse elsewhere = AiStudioProposalJobResponse.builder().jobId("db").status(Status.RUNNING).build();
        when(store.findUnfinished(9L, "u1")).thenReturn(Optional.of(elsewhere));
        assertSame(elsewhere, service.findActive(9L, "u1").orElseThrow());
    }

    @Test
    void storeFailuresNeverBreakTheJob() throws Exception {
        doThrow(new IllegalStateException("db down")).when(store).save(any(), anyString(), anyString());
        AiStudioProposalJobResponse s = service.submit(request(1L, "k1"), () -> new StudioChatResult("ok", null, null), null);
        assertEquals(Status.SUCCEEDED, await(s.getJobId()).getStatus());
    }

    /**
     * 库里的 save 对自带 id 的实体是"先查后插"。提交线程与工作线程同时首写同一个作业时，
     * 两边都查不到行、都去插，后到的撞 {@code dw_ai_studio_proposal_jobs_pkey}。
     */
    @Test
    void concurrentFirstSavesOfOneJobNeverCollideOnThePrimaryKey() throws Exception {
        Map<String, Status> rows = new ConcurrentHashMap<>();
        List<String> failures = new CopyOnWriteArrayList<>();
        CountDownLatch twoWritersInside = new CountDownLatch(2);
        doAnswer(inv -> {
            AiStudioProposalJobResponse snapshot = inv.getArgument(0);
            boolean exists = rows.containsKey(snapshot.getJobId());
            twoWritersInside.countDown();
            if (!exists) {
                // 查完还没插：给另一个写入方留出同样"查不到"的窗口
                twoWritersInside.await(300, TimeUnit.MILLISECONDS);
                if (rows.putIfAbsent(snapshot.getJobId(), snapshot.getStatus()) != null) {
                    failures.add("duplicate key on " + snapshot.getStatus());
                    throw new DataIntegrityViolationException("duplicate key value violates unique constraint");
                }
                return null;
            }
            rows.put(snapshot.getJobId(), snapshot.getStatus());
            return null;
        }).when(store).save(any(), anyString(), anyString());

        AiStudioProposalJobResponse submitted = service.submit(request(1L, "k1"),
                () -> new StudioChatResult("ok", null, null), null);
        await(submitted.getJobId());

        assertEquals(List.of(), failures, "the first insert and the next update never run side by side");
        assertEquals(Status.SUCCEEDED, rows.get(submitted.getJobId()));
    }

    /** 早拍的快照晚写完，不能把更新的状态盖回去：否则库里留下一条永远"未完成"的幽灵作业。 */
    @Test
    void aSlowEarlierSaveNeverOverwritesANewerSnapshot() throws Exception {
        Map<String, Status> rows = new ConcurrentHashMap<>();
        CountDownLatch terminalSaved = new CountDownLatch(1);
        doAnswer(inv -> {
            AiStudioProposalJobResponse snapshot = inv.getArgument(0);
            if (snapshot.getStatus() == Status.PENDING) {
                terminalSaved.await(300, TimeUnit.MILLISECONDS); // 提交线程的这次写库特别慢
            }
            rows.put(snapshot.getJobId(), snapshot.getStatus());
            if (snapshot.getStatus() == Status.SUCCEEDED) terminalSaved.countDown();
            return null;
        }).when(store).save(any(), anyString(), anyString());

        AiStudioProposalJobResponse submitted = service.submit(request(1L, "k1"),
                () -> new StudioChatResult("ok", null, null), null);
        await(submitted.getJobId());

        assertEquals(Status.SUCCEEDED, rows.get(submitted.getJobId()), "the row ends on the newest state");
    }
}
