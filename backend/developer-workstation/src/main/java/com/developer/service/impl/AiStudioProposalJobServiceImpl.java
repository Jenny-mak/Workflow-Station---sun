package com.developer.service.impl;

import com.developer.dto.AiStudioProposalJobResponse;
import com.developer.dto.AiStudioProposalJobResponse.Status;
import com.developer.dto.AiStudioThreadEvent;
import com.developer.exception.AiGenerationException;
import com.developer.service.AiStudioChatService.StudioChatResult;
import com.developer.service.AiStudioProposalJobService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 进程内提案作业注册表 + 落库快照。
 *
 * <p>线程池独立于 AI Generate 的 {@code CompletableFuture.runAsync}（公共 ForkJoinPool）：
 * 提案一跑就是几分钟，占公共池会拖累其他异步任务；池满时显式以
 * {@code AI_STUDIO_PROPOSAL_QUEUE_FULL} 拒绝，不排无界队列。</p>
 *
 * <p>终态作业在内存里保留 {@code retention} 后清理，库里保留 {@code dbRetention}；跑超过
 * {@code maxRuntime} 的作业视为挂死，中断并记为 {@code AI_STUDIO_PROPOSAL_TIMED_OUT}，否则一次卡住的调用
 * 会让该用户永远拿不到新作业（提交去重按"未完成"判断）。</p>
 *
 * <p>每次状态变化写一次库（失败只 warn，不影响作业本身）；内存里找不到的作业回落到库，
 * 所以 DW 重启后轮询拿到的是终态或"已中断"，而不是 404。开始/结束时发出线程事件，
 * 队友据此显示"某某正在生成提案"。</p>
 */
@Slf4j
@Service
public class AiStudioProposalJobServiceImpl implements AiStudioProposalJobService {

    private static final Duration PURGE_INTERVAL = Duration.ofMinutes(10);

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final Duration retention;
    private final Duration maxRuntime;
    private final int maxJobs;
    private final AiStudioProposalJobStore store;
    private final ApplicationEventPublisher events;
    private final Duration dbRetention;
    private volatile Instant lastPurge = Instant.EPOCH;

    @Autowired
    public AiStudioProposalJobServiceImpl(
            @Value("${ai-generation.studio.proposal-workers:2}") int workers,
            @Value("${ai-generation.studio.proposal-queue-size:8}") int queueSize,
            @Value("${ai-generation.studio.proposal-retention-minutes:30}") long retentionMinutes,
            @Value("${ai-generation.studio.proposal-max-runtime-minutes:30}") long maxRuntimeMinutes,
            @Value("${ai-generation.studio.proposal-max-jobs:200}") int maxJobs,
            @Value("${ai-generation.studio.proposal-db-retention-hours:24}") long dbRetentionHours,
            AiStudioProposalJobStore store,
            ApplicationEventPublisher events) {
        this(new ThreadPoolExecutor(workers, workers, 60, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(queueSize), r -> {
                            Thread t = new Thread(r, "ai-studio-proposal");
                            t.setDaemon(true);
                            return t;
                        }),
                Duration.ofMinutes(retentionMinutes), Duration.ofMinutes(maxRuntimeMinutes), maxJobs,
                store, events, Duration.ofHours(dbRetentionHours));
    }

    /** 单测注入同步执行器用（不落库、不发事件）。 */
    AiStudioProposalJobServiceImpl(ExecutorService executor, Duration retention, Duration maxRuntime, int maxJobs) {
        this(executor, retention, maxRuntime, maxJobs, null, null, Duration.ofHours(24));
    }

    AiStudioProposalJobServiceImpl(ExecutorService executor, Duration retention, Duration maxRuntime, int maxJobs,
                                   AiStudioProposalJobStore store, ApplicationEventPublisher events,
                                   Duration dbRetention) {
        this.executor = executor;
        this.retention = retention;
        this.maxRuntime = maxRuntime;
        this.maxJobs = maxJobs;
        this.store = store;
        this.events = events;
        this.dbRetention = dbRetention;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public AiStudioProposalJobResponse submit(JobRequest request,
                                              Supplier<StudioChatResult> work,
                                              UnaryOperator<StudioChatResult> commit,
                                              Consumer<StudioChatResult> onSucceeded) {
        sweep();
        Long functionUnitId = request.functionUnitId();
        String userId = request.userId();
        Job existing = jobs.values().stream()
                .filter(j -> j.functionUnitId.equals(functionUnitId) && j.userId.equals(userId) && !j.isTerminal())
                .findFirst().orElse(null);
        if (existing != null) {
            if (java.util.Objects.equals(existing.requestKey, request.requestKey())) {
                log.info("AI Studio proposal job reused: jobId={}, functionUnitId={}, userId={}",
                        existing.jobId, functionUnitId, userId);
                return existing.snapshot();
            }
            // 同一个人带着不同的请求再来：旧作业的结果已经没人要了，别让它占着"每人每 FU 一个"的名额
            log.info("AI Studio proposal job superseded by a new request: oldJobId={}, functionUnitId={}, userId={}",
                    existing.jobId, functionUnitId, userId);
            if (cancelJob(existing, "AI_STUDIO_PROPOSAL_SUPERSEDED", "Superseded by a newer proposal request")) {
                finished(existing);
            }
        }
        if (jobs.size() >= maxJobs) {
            throw new AiGenerationException("AI_STUDIO_PROPOSAL_QUEUE_FULL",
                    "Too many proposal jobs are registered; retry later");
        }

        Job job = new Job(UUID.randomUUID().toString(), request);
        jobs.put(job.jobId, job);
        // 首次落库与交给线程池在同一把落库锁内：工作线程的 RUNNING 快照只能排在这次插入之后
        synchronized (job.persistLock) {
            try {
                job.future = executor.submit(() -> run(job, work, commit, onSucceeded));
            } catch (RejectedExecutionException e) {
                jobs.remove(job.jobId);
                throw new AiGenerationException("AI_STUDIO_PROPOSAL_QUEUE_FULL",
                        "Proposal workers are busy; retry later");
            }
            persist(job);
        }
        publish(job, AiStudioThreadEvent.PROPOSAL_STARTED);
        log.info("AI Studio proposal job submitted: jobId={}, functionUnitId={}, phase={}, userId={}",
                job.jobId, functionUnitId, job.phase, userId);
        return job.snapshot();
    }

    private void run(Job job, Supplier<StudioChatResult> work, UnaryOperator<StudioChatResult> commit,
                     Consumer<StudioChatResult> onSucceeded) {
        synchronized (job) {
            if (job.isTerminal()) return; // 已被判超时/清理
            job.status = Status.RUNNING;
            job.startedAt = Instant.now();
        }
        persist(job);
        try {
            StudioChatResult result = work.get();
            synchronized (job) {
                if (job.isTerminal()) return;
                // commit 在作业锁内执行：cancelJob 也要这把锁，所以"已取消"与"已写入"不会同时成立
                if (commit != null) result = commit.apply(result);
                job.result = result;
                job.status = Status.SUCCEEDED;
                job.finishedAt = Instant.now();
            }
            log.info("AI Studio proposal job succeeded: jobId={}, functionUnitId={}, hasProposal={}, tookSeconds={}",
                    job.jobId, job.functionUnitId, result.proposal() != null,
                    Duration.between(job.startedAt, job.finishedAt).toSeconds());
        } catch (AiGenerationException e) {
            fail(job, e.getErrorCode() != null ? e.getErrorCode() : "AI_STUDIO_PROPOSAL_FAILED", e.getMessage(), e);
            return;
        } catch (Exception e) {
            fail(job, "AI_STUDIO_PROPOSAL_FAILED", e.getMessage() != null ? e.getMessage() : e.toString(), e);
            return;
        }
        persist(job);
        // 先让结果落进共享线程，再宣布"生成结束"：队友收到结束事件时卡片已经在了
        if (onSucceeded != null) {
            try {
                onSucceeded.accept(job.result);
            } catch (RuntimeException e) {
                log.warn("AI Studio proposal job succeeded but its completion hook failed: jobId={}, functionUnitId={}: {}",
                        job.jobId, job.functionUnitId, e.getMessage());
            }
        }
        publish(job, AiStudioThreadEvent.PROPOSAL_FINISHED);
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
        finished(job);
    }

    /** 非成功的终态：落库并通知队友结束。 */
    private void finished(Job job) {
        persist(job);
        publish(job, AiStudioThreadEvent.PROPOSAL_FINISHED);
    }

    @Override
    public AiStudioProposalJobResponse get(String jobId, String userId) {
        sweep();
        Job job = ownJob(jobId, userId);
        if (job != null) return job.snapshot();
        return fromStore(jobId, userId).orElseThrow(() -> notFound(jobId));
    }

    @Override
    public AiStudioProposalJobResponse cancel(String jobId, String userId) {
        sweep();
        Job job = ownJob(jobId, userId);
        if (job == null) {
            // 内存里没有 = 上个进程的作业，库里必然已是终态（启动时判中断），幂等返回
            return fromStore(jobId, userId).orElseThrow(() -> notFound(jobId));
        }
        if (cancelJob(job, "AI_STUDIO_PROPOSAL_CANCELLED", "Cancelled by the user")) {
            log.info("AI Studio proposal job cancelled: jobId={}, functionUnitId={}, userId={}",
                    job.jobId, job.functionUnitId, userId);
            finished(job);
        }
        return job.snapshot();
    }

    @Override
    public Optional<AiStudioProposalJobResponse> findActive(Long functionUnitId, String userId) {
        sweep();
        Optional<AiStudioProposalJobResponse> inMemory = jobs.values().stream()
                .filter(j -> j.functionUnitId.equals(functionUnitId) && j.userId.equals(userId) && !j.isTerminal())
                .max(Comparator.comparing(j -> j.submittedAt))
                .map(Job::snapshot);
        if (inMemory.isPresent() || store == null) return inMemory;
        try {
            return store.findUnfinished(functionUnitId, userId);
        } catch (RuntimeException e) {
            log.warn("AI Studio proposal job lookup fell back to memory only: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<ActiveJob> activeJobs(Long functionUnitId) {
        return jobs.values().stream()
                .filter(j -> j.functionUnitId.equals(functionUnitId) && !j.isTerminal())
                .sorted(Comparator.comparing(j -> j.submittedAt))
                .map(j -> new ActiveJob(j.snapshot(), j.userId))
                .toList();
    }

    private Job ownJob(String jobId, String userId) {
        Job job = jobId == null ? null : jobs.get(jobId);
        return job != null && job.userId.equals(userId) ? job : null;
    }

    private Optional<AiStudioProposalJobResponse> fromStore(String jobId, String userId) {
        if (store == null || jobId == null) return Optional.empty();
        try {
            return store.find(jobId, userId);
        } catch (RuntimeException e) {
            log.warn("AI Studio proposal job store unavailable for jobId={}: {}", jobId, e.getMessage());
            return Optional.empty();
        }
    }

    private static AiGenerationException notFound(String jobId) {
        return new AiGenerationException("AI_STUDIO_PROPOSAL_NOT_FOUND",
                "Proposal job not found or no longer available: " + jobId);
    }

    /** 置 CANCELLED 并中断后台线程；已终态返回 false（幂等）。run() 里的 isTerminal 守卫会丢掉迟到的结果。 */
    private static boolean cancelJob(Job job, String code, String message) {
        synchronized (job) {
            if (job.isTerminal()) return false;
            job.status = Status.CANCELLED;
            job.errorCode = code;
            job.errorMessage = message;
            job.finishedAt = Instant.now();
            if (job.future != null) job.future.cancel(true);
            return true;
        }
    }

    /**
     * 同一作业的落库逐个进行，快照在锁内现拍：库里的 save 对自带 id 的实体是先查后插，两个线程同时首写会撞主键；
     * 后写的一定不比先写的旧，所以慢的一次写库不会把更新的状态盖回去。
     * 用独立的锁而不是作业锁：写库期间轮询（snapshot）与取消不必等。调用方不得持有作业锁。
     */
    private void persist(Job job) {
        if (store == null) return;
        synchronized (job.persistLock) {
            try {
                store.save(job.snapshot(), job.userId, job.requestKey);
            } catch (RuntimeException e) {
                log.warn("AI Studio proposal job snapshot not persisted: jobId={}: {}", job.jobId, e.getMessage());
            }
        }
    }

    private void publish(Job job, String type) {
        if (events == null) return;
        try {
            events.publishEvent(AiStudioThreadEvent.proposal(type, job.functionUnitId, job.phase, job.jobId,
                    job.userId, job.authorName));
        } catch (RuntimeException e) {
            log.warn("AI Studio proposal job event not delivered: jobId={}: {}", job.jobId, e.getMessage());
        }
    }

    /** 清理过期终态作业；把跑超时的作业判失败并中断。每次 submit/get 顺手做，不另起线程。 */
    private void sweep() {
        Instant now = Instant.now();
        for (Job job : jobs.values()) {
            boolean timedOut = false;
            synchronized (job) {
                if (!job.isTerminal() && job.startedAt != null
                        && Duration.between(job.startedAt, now).compareTo(maxRuntime) > 0) {
                    job.status = Status.FAILED;
                    job.errorCode = "AI_STUDIO_PROPOSAL_TIMED_OUT";
                    job.errorMessage = "Proposal job exceeded " + maxRuntime.toMinutes() + " minutes";
                    job.finishedAt = now;
                    if (job.future != null) job.future.cancel(true);
                    timedOut = true;
                    log.warn("AI Studio proposal job timed out: jobId={}, functionUnitId={}", job.jobId, job.functionUnitId);
                }
                if (job.isTerminal() && job.finishedAt != null
                        && Duration.between(job.finishedAt, now).compareTo(retention) > 0) {
                    jobs.remove(job.jobId, job);
                }
            }
            if (timedOut) finished(job);
        }
        // 容量兜底：终态作业按完成时间从旧到新腾位，绝不淘汰未完成的
        if (jobs.size() >= maxJobs) {
            jobs.values().stream()
                    .filter(Job::isTerminal)
                    .sorted(Comparator.comparing(j -> j.finishedAt))
                    .limit(Math.max(0, jobs.size() - maxJobs + 1))
                    .forEach(j -> jobs.remove(j.jobId, j));
        }
        if (store != null && Duration.between(lastPurge, now).compareTo(PURGE_INTERVAL) > 0) {
            lastPurge = now;
            try {
                int purged = store.purgeFinishedBefore(now.minus(dbRetention));
                if (purged > 0) log.info("AI Studio proposal job snapshots purged: {}", purged);
            } catch (RuntimeException e) {
                log.warn("AI Studio proposal job purge failed: {}", e.getMessage());
            }
        }
    }

    private static final class Job {
        final String jobId;
        final Long functionUnitId;
        final String phase;
        final String userId;
        final String authorName;
        /** 请求内容指纹：同人同 FU 重复提交同一请求时复用作业，不同请求时替换 */
        final String requestKey;
        final String message;
        final Instant submittedAt = Instant.now();
        /** 串行化本作业的落库，见 {@code persist} */
        final Object persistLock = new Object();
        volatile Status status = Status.PENDING;
        Instant startedAt;
        Instant finishedAt;
        StudioChatResult result;
        String errorCode;
        String errorMessage;
        Future<?> future;

        Job(String jobId, JobRequest request) {
            this.jobId = jobId;
            this.functionUnitId = request.functionUnitId();
            this.phase = request.phase();
            this.userId = request.userId();
            this.authorName = request.authorName();
            this.requestKey = request.requestKey();
            this.message = request.message();
        }

        boolean isTerminal() {
            return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELLED;
        }

        synchronized AiStudioProposalJobResponse snapshot() {
            return AiStudioProposalJobResponse.builder()
                    .jobId(jobId)
                    .functionUnitId(functionUnitId)
                    .phase(phase)
                    .message(message)
                    .authorName(authorName)
                    .status(status)
                    .submittedAt(submittedAt)
                    .startedAt(startedAt)
                    .finishedAt(finishedAt)
                    .reply(result != null ? result.reply() : null)
                    .proposal(result != null ? result.proposal() : null)
                    .proposalScope(result != null ? result.proposalScope() : null)
                    .preview(result != null ? result.preview() : null)
                    .errorCode(errorCode)
                    .errorMessage(errorMessage)
                    .build();
        }
    }
}
