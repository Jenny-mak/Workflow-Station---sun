package com.developer.service.impl;

import com.developer.dto.AiStudioProposalJobResponse;
import com.developer.dto.AiStudioProposalJobResponse.Status;
import com.developer.exception.AiGenerationException;
import com.developer.service.AiStudioChatService.StudioChatResult;
import com.developer.service.AiStudioProposalJobService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 进程内提案作业注册表。
 *
 * <p>线程池独立于 AI Generate 的 {@code CompletableFuture.runAsync}（公共 ForkJoinPool）：
 * 提案一跑就是几分钟，占公共池会拖累其他异步任务；池满时显式以
 * {@code AI_STUDIO_PROPOSAL_QUEUE_FULL} 拒绝，不排无界队列。</p>
 *
 * <p>终态作业保留 {@code retention} 后清理；跑超过 {@code maxRuntime} 的作业视为挂死，
 * 中断并记为 {@code AI_STUDIO_PROPOSAL_TIMED_OUT}，否则一次卡住的调用会让该用户永远拿不到新作业
 * （提交去重按"未完成"判断）。</p>
 */
@Slf4j
@Service
public class AiStudioProposalJobServiceImpl implements AiStudioProposalJobService {

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final Duration retention;
    private final Duration maxRuntime;
    private final int maxJobs;

    @Autowired
    public AiStudioProposalJobServiceImpl(
            @Value("${ai-generation.studio.proposal-workers:2}") int workers,
            @Value("${ai-generation.studio.proposal-queue-size:8}") int queueSize,
            @Value("${ai-generation.studio.proposal-retention-minutes:30}") long retentionMinutes,
            @Value("${ai-generation.studio.proposal-max-runtime-minutes:30}") long maxRuntimeMinutes,
            @Value("${ai-generation.studio.proposal-max-jobs:200}") int maxJobs) {
        this(new ThreadPoolExecutor(workers, workers, 60, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(queueSize), r -> {
                            Thread t = new Thread(r, "ai-studio-proposal");
                            t.setDaemon(true);
                            return t;
                        }),
                Duration.ofMinutes(retentionMinutes), Duration.ofMinutes(maxRuntimeMinutes), maxJobs);
    }

    /** 单测注入同步执行器用。 */
    AiStudioProposalJobServiceImpl(ExecutorService executor, Duration retention, Duration maxRuntime, int maxJobs) {
        this.executor = executor;
        this.retention = retention;
        this.maxRuntime = maxRuntime;
        this.maxJobs = maxJobs;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public AiStudioProposalJobResponse submit(Long functionUnitId, String phase, String userId,
                                              Supplier<StudioChatResult> work) {
        sweep();
        Job existing = jobs.values().stream()
                .filter(j -> j.functionUnitId.equals(functionUnitId) && j.userId.equals(userId) && !j.isTerminal())
                .findFirst().orElse(null);
        if (existing != null) {
            log.info("AI Studio proposal job reused: jobId={}, functionUnitId={}, userId={}",
                    existing.jobId, functionUnitId, userId);
            return existing.snapshot();
        }
        if (jobs.size() >= maxJobs) {
            throw new AiGenerationException("AI_STUDIO_PROPOSAL_QUEUE_FULL",
                    "Too many proposal jobs are registered; retry later");
        }

        Job job = new Job(UUID.randomUUID().toString(), functionUnitId, phase, userId);
        jobs.put(job.jobId, job);
        try {
            job.future = executor.submit(() -> run(job, work));
        } catch (RejectedExecutionException e) {
            jobs.remove(job.jobId);
            throw new AiGenerationException("AI_STUDIO_PROPOSAL_QUEUE_FULL",
                    "Proposal workers are busy; retry later");
        }
        log.info("AI Studio proposal job submitted: jobId={}, functionUnitId={}, phase={}, userId={}",
                job.jobId, functionUnitId, phase, userId);
        return job.snapshot();
    }

    private void run(Job job, Supplier<StudioChatResult> work) {
        synchronized (job) {
            if (job.isTerminal()) return; // 已被判超时/清理
            job.status = Status.RUNNING;
            job.startedAt = Instant.now();
        }
        try {
            StudioChatResult result = work.get();
            synchronized (job) {
                if (job.isTerminal()) return;
                job.result = result;
                job.status = Status.SUCCEEDED;
                job.finishedAt = Instant.now();
            }
            log.info("AI Studio proposal job succeeded: jobId={}, functionUnitId={}, hasProposal={}, tookSeconds={}",
                    job.jobId, job.functionUnitId, result.proposal() != null,
                    Duration.between(job.startedAt, job.finishedAt).toSeconds());
        } catch (AiGenerationException e) {
            fail(job, e.getErrorCode() != null ? e.getErrorCode() : "AI_STUDIO_PROPOSAL_FAILED", e.getMessage(), e);
        } catch (Exception e) {
            fail(job, "AI_STUDIO_PROPOSAL_FAILED", e.getMessage() != null ? e.getMessage() : e.toString(), e);
        }
    }

    private void fail(Job job, String code, String message, Exception e) {
        synchronized (job) {
            if (job.isTerminal()) return;
            job.status = Status.FAILED;
            job.errorCode = code;
            job.errorMessage = message;
            job.finishedAt = Instant.now();
        }
        log.warn("AI Studio proposal job failed: jobId={}, functionUnitId={}, code={}: {}",
                job.jobId, job.functionUnitId, code, message, e);
    }

    @Override
    public AiStudioProposalJobResponse get(String jobId, String userId) {
        sweep();
        Job job = jobId == null ? null : jobs.get(jobId);
        if (job == null || !job.userId.equals(userId)) {
            throw new AiGenerationException("AI_STUDIO_PROPOSAL_NOT_FOUND",
                    "Proposal job not found or no longer available: " + jobId);
        }
        return job.snapshot();
    }

    /** 清理过期终态作业；把跑超时的作业判失败并中断。每次 submit/get 顺手做，不另起线程。 */
    private void sweep() {
        Instant now = Instant.now();
        for (Job job : jobs.values()) {
            synchronized (job) {
                if (!job.isTerminal() && job.startedAt != null
                        && Duration.between(job.startedAt, now).compareTo(maxRuntime) > 0) {
                    job.status = Status.FAILED;
                    job.errorCode = "AI_STUDIO_PROPOSAL_TIMED_OUT";
                    job.errorMessage = "Proposal job exceeded " + maxRuntime.toMinutes() + " minutes";
                    job.finishedAt = now;
                    if (job.future != null) job.future.cancel(true);
                    log.warn("AI Studio proposal job timed out: jobId={}, functionUnitId={}", job.jobId, job.functionUnitId);
                }
                if (job.isTerminal() && job.finishedAt != null
                        && Duration.between(job.finishedAt, now).compareTo(retention) > 0) {
                    jobs.remove(job.jobId, job);
                }
            }
        }
        // 容量兜底：终态作业按完成时间从旧到新腾位，绝不淘汰未完成的
        if (jobs.size() >= maxJobs) {
            jobs.values().stream()
                    .filter(Job::isTerminal)
                    .sorted(Comparator.comparing(j -> j.finishedAt))
                    .limit(Math.max(0, jobs.size() - maxJobs + 1))
                    .forEach(j -> jobs.remove(j.jobId, j));
        }
    }

    private static final class Job {
        final String jobId;
        final Long functionUnitId;
        final String phase;
        final String userId;
        final Instant submittedAt = Instant.now();
        volatile Status status = Status.PENDING;
        Instant startedAt;
        Instant finishedAt;
        StudioChatResult result;
        String errorCode;
        String errorMessage;
        Future<?> future;

        Job(String jobId, Long functionUnitId, String phase, String userId) {
            this.jobId = jobId;
            this.functionUnitId = functionUnitId;
            this.phase = phase;
            this.userId = userId;
        }

        boolean isTerminal() {
            return status == Status.SUCCEEDED || status == Status.FAILED;
        }

        synchronized AiStudioProposalJobResponse snapshot() {
            return AiStudioProposalJobResponse.builder()
                    .jobId(jobId)
                    .functionUnitId(functionUnitId)
                    .phase(phase)
                    .status(status)
                    .submittedAt(submittedAt)
                    .finishedAt(finishedAt)
                    .reply(result != null ? result.reply() : null)
                    .proposal(result != null ? result.proposal() : null)
                    .proposalScope(result != null ? result.proposalScope() : null)
                    .errorCode(errorCode)
                    .errorMessage(errorMessage)
                    .build();
        }
    }
}
