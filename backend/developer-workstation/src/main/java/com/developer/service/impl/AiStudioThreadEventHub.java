package com.developer.service.impl;

import com.developer.dto.AiStudioThreadEvent;
import com.developer.service.AiStudioProposalJobService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AI Studio 共享线程的推送中心：按功能单元维护 SSE 订阅，把 {@link AiStudioThreadEvent} 在事务提交后推出去。
 *
 * <p>与 AI Generate 的 {@code AiSseEmitterManager} 分开：那边是锁/写入协议，这里只是线程变化通知。
 * 订阅注册表在进程内（DW 单实例）；每 20 秒发一次注释行心跳，防止 Kong/nginx 空闲断开；
 * 连接到期由客户端重连并按 afterId 补拉，所以断线期间的事件不会丢。</p>
 *
 * <p>下发的负载不含消息内容，也不含触发者 id——按订阅者换算成 {@code mine}。</p>
 */
@Slf4j
@Service
public class AiStudioThreadEventHub {

    private static final long HEARTBEAT_SECONDS = 20;

    private final Map<Long, CopyOnWriteArrayList<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final AiStudioProposalJobService jobService;
    private final long timeoutMillis;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ai-studio-thread-sse");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public AiStudioThreadEventHub(ObjectMapper objectMapper, AiStudioProposalJobService jobService,
                                  @Value("${ai-generation.studio.thread-events-timeout-seconds:300}") long timeoutSeconds) {
        this(objectMapper, jobService, timeoutSeconds, true);
    }

    AiStudioThreadEventHub(ObjectMapper objectMapper, AiStudioProposalJobService jobService,
                           long timeoutSeconds, boolean startHeartbeat) {
        this.objectMapper = objectMapper;
        this.jobService = jobService;
        this.timeoutMillis = timeoutSeconds * 1000;
        if (startHeartbeat) {
            heartbeat.scheduleAtFixedRate(this::sendHeartbeats, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
        subscribers.values().forEach(list -> list.forEach(s -> safeComplete(s.emitter)));
    }

    /** 订阅某功能单元（权限由调用方校验）。连上即补发一次"谁正在生成提案"。 */
    public SseEmitter subscribe(Long functionUnitId, String userId) {
        SseEmitter emitter = newEmitter(timeoutMillis);
        Subscriber subscriber = new Subscriber(userId, emitter);
        CopyOnWriteArrayList<Subscriber> list = subscribers.computeIfAbsent(functionUnitId, k -> new CopyOnWriteArrayList<>());
        list.add(subscriber);
        Runnable cleanup = () -> remove(functionUnitId, subscriber);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            safeComplete(emitter);
        });
        emitter.onError(e -> cleanup.run());

        send(functionUnitId, subscriber, "READY", Map.of());
        for (AiStudioProposalJobService.ActiveJob job : jobService.activeJobs(functionUnitId)) {
            send(functionUnitId, subscriber, AiStudioThreadEvent.PROPOSAL_STARTED,
                    payload(job.snapshot().getPhase(), null, job.snapshot().getJobId(),
                            job.snapshot().getAuthorName(), Objects.equals(job.userId(), userId)));
        }
        log.debug("AI Studio thread subscriber added: functionUnitId={}, userId={}, total={}",
                functionUnitId, userId, list.size());
        return emitter;
    }

    /** 事务提交后才推：否则订阅者按 id 来拉时可能还读不到。没有事务时（作业线程）立即推。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onThreadEvent(AiStudioThreadEvent event) {
        List<Subscriber> list = subscribers.get(event.functionUnitId());
        if (list == null || list.isEmpty()) return;
        for (Subscriber s : list) {
            send(event.functionUnitId(), s, event.type(), payload(event.phase(), event.messageId(), event.jobId(),
                    event.authorName(), Objects.equals(event.userId(), s.userId)));
        }
    }

    int subscriberCount(Long functionUnitId) {
        List<Subscriber> list = subscribers.get(functionUnitId);
        return list == null ? 0 : list.size();
    }

    /** 可被单测替换的工厂。 */
    SseEmitter newEmitter(long timeout) {
        return new SseEmitter(timeout);
    }

    private static Map<String, Object> payload(String phase, Long messageId, String jobId, String authorName,
                                               boolean mine) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (phase != null) m.put("phase", phase);
        if (messageId != null) m.put("messageId", messageId);
        if (jobId != null) m.put("jobId", jobId);
        if (authorName != null) m.put("authorName", authorName);
        m.put("mine", mine);
        return m;
    }

    private void send(Long functionUnitId, Subscriber s, String type, Map<String, Object> data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("AI Studio thread event not serializable: {}", e.getMessage());
            return;
        }
        synchronized (s) {
            try {
                s.emitter.send(SseEmitter.event().name(type).data(json));
            } catch (IOException | IllegalStateException e) {
                // 客户端已断开：移除，由它自己重连
                remove(functionUnitId, s);
                safeComplete(s.emitter);
            }
        }
    }

    private void sendHeartbeats() {
        subscribers.forEach((functionUnitId, list) -> {
            for (Subscriber s : list) {
                synchronized (s) {
                    try {
                        s.emitter.send(SseEmitter.event().comment("ping"));
                    } catch (IOException | IllegalStateException e) {
                        remove(functionUnitId, s);
                        safeComplete(s.emitter);
                    }
                }
            }
        });
    }

    private void remove(Long functionUnitId, Subscriber s) {
        subscribers.computeIfPresent(functionUnitId, (k, list) -> {
            list.remove(s);
            return list.isEmpty() ? null : list;
        });
    }

    private static void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // 已完成或已断开
        }
    }

    private record Subscriber(String userId, SseEmitter emitter) {
    }
}
