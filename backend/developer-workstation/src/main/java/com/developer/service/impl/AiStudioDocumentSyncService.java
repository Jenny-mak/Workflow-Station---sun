package com.developer.service.impl;

import com.developer.dto.AiStudioChatRequest;
import com.developer.dto.AiStudioThreadEvent;
import com.developer.dto.FunctionUnitContextDTO;
import com.developer.entity.AiDocument;
import com.developer.enums.AiDocumentType;
import com.developer.enums.AiStudioPhase;
import com.developer.exception.AiGenerationException;
import com.developer.exception.DeveloperBusinessException;
import com.developer.service.AiGenerationService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Studio 确认阶段后的文档同步：让模型对照当前设计核对并改写 Requirements / Function Unit Design，
 * 结果追加为新版本，并在共享线程里留一条结果消息。
 *
 * <p>作业在进程内后台跑，不落库（重启即丢，用户用"立即检查"补跑）。每个功能单元同时只跑一个；
 * 运行期间再提交的阶段合并成一次后续运行。写入前比对开始时的版本号，期间有人手动保存过就放弃
 * 该文档的结果（{@code SKIPPED}）——AI 永远不覆盖人的修改。任何失败都写成 FAILED 消息，不影响阶段进度。</p>
 *
 * <p>设计上下文、对话与作者在请求线程里准备（JPA 懒加载、SecurityContext 都绑在请求线程上）；
 * 作业线程里临时装上提交者的 SecurityContext，让 JPA 审计把版本记到提交者名下。</p>
 */
@Slf4j
@Service
public class AiStudioDocumentSyncService {

    public static final String STATUS_UPDATED = "UPDATED";
    public static final String STATUS_UNCHANGED = "UNCHANGED";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_FAILED = "FAILED";

    static final List<String> REQUIREMENTS_SECTIONS = List.of(
            "Background & Goals", "Roles", "Business Process", "Data Requirements", "Forms & Views",
            "Notifications & Integrations", "Business Rules & Decisions", "Non-functional Requirements",
            "Open Questions");

    /** 模型要整篇重写文档，超过这个长度就不交给它（显式失败，而不是截断后写回丢内容）。 */
    static final int MAX_DOCUMENT_CHARS_FOR_SYNC = 60_000;
    static final int DESIGN_LISTING_CHAR_BUDGET = 30_000;
    static final int CONVERSATION_CHAR_BUDGET = 12_000;

    private static final Map<AiDocumentType, Pattern> DOC_BLOCKS = Map.of(
            AiDocumentType.REQUIREMENTS,
            Pattern.compile("---REQUIREMENTS_DOC_START---(.*?)---REQUIREMENTS_DOC_END---", Pattern.DOTALL),
            AiDocumentType.DESIGN,
            Pattern.compile("---DESIGN_DOC_START---(.*?)---DESIGN_DOC_END---", Pattern.DOTALL));
    private static final Map<AiDocumentType, String> UNCHANGED_MARKERS = Map.of(
            AiDocumentType.REQUIREMENTS, "---REQUIREMENTS_UNCHANGED---",
            AiDocumentType.DESIGN, "---DESIGN_UNCHANGED---");
    private static final String CHANGE_SUMMARY_MARKER = "---CHANGE_SUMMARY---";

    private static final String SYSTEM_PROMPT = """
            You maintain two living Markdown documents for a Function Unit designed in the Workflow Station \
            Developer Workstation: the Requirements document and the Function Unit Design document. \
            The Function Unit is designed through these phases: %s.

            Rules:
            1. Fixed structure. Requirements uses these level-2 headings in this order: %s. \
            Function Unit Design uses one level-2 heading per phase, named exactly like the phases above and \
            in that order, followed by "## Change Log". A document that does not follow its structure yet must \
            be reorganised into it without losing any of its content.
            2. Only touch the sections related to the phases under "Phases to check" (a full check covers every \
            section). Copy every other section verbatim.
            3. Keep text written by people. Do not rewrite or delete it, except in a Design section where it \
            contradicts the current design.
            4. Requirements state the business intent and are NOT changed to match the design. When the design \
            does not satisfy a requirement, or the design does something no requirement asks for, add one item \
            under "Open Questions" (skip items that are already listed).
            5. Function Unit Design describes the design as it currently is. Ground every statement in the design \
            listing and never invent tables, fields, forms, nodes, views, actions or flows it does not list. The \
            listing is name-level only: do not guess details it does not carry. Append one line to "Change Log" \
            for this update: today's date, the phases, and what changed.
            6. Write in the language the existing document uses. If it is empty, use the language of the \
            conversation; if there is none, English.
            7. A document that needs no change is not output.

            Output exactly these blocks and nothing else:
            ---REQUIREMENTS_DOC_START---
            <the complete updated Requirements document>
            ---REQUIREMENTS_DOC_END---
            or, when it needs no change, the single line ---REQUIREMENTS_UNCHANGED---
            ---DESIGN_DOC_START---
            <the complete updated Function Unit Design document>
            ---DESIGN_DOC_END---
            or, when it needs no change, the single line ---DESIGN_UNCHANGED---
            ---CHANGE_SUMMARY---
            <one or two sentences on what changed, in the documents' language>""";

    /**
     * 一次同步的输入。{@code phases} 为空表示全量核对（"立即检查"）。
     *
     * @param messagePhase 结果消息写到哪个阶段的线程
     */
    public record Request(Long functionUnitId, List<String> phases, String messagePhase,
                          FunctionUnitContextDTO context,
                          Map<String, List<AiStudioChatRequest.HistoryMessage>> conversation,
                          String amToken, AiStudioThreadService.Author author, SecurityContext securityContext) {

        boolean fullCheck() {
            return phases.isEmpty();
        }

        /** 不带 amToken / 上下文：record 默认的 toString 会把令牌带进日志。 */
        @Override
        public String toString() {
            return "Request[functionUnitId=" + functionUnitId + ", phases=" + phases + "]";
        }

        /** 运行期间又来一次提交：阶段取并集（任一方是全量核对则全量），其余取较新的一方。 */
        Request mergedWith(Request newer) {
            List<String> merged;
            if (fullCheck() || newer.fullCheck()) {
                merged = List.of();
            } else {
                LinkedHashSet<String> union = new LinkedHashSet<>(phases);
                union.addAll(newer.phases);
                merged = List.copyOf(union);
            }
            Map<String, List<AiStudioChatRequest.HistoryMessage>> conv = new LinkedHashMap<>(conversation);
            conv.putAll(newer.conversation);
            return new Request(functionUnitId, merged, newer.messagePhase, newer.context, conv,
                    newer.amToken, newer.author, newer.securityContext);
        }
    }

    private final FunctionUnitDocumentService documentService;
    private final AiStudioThreadService threadService;
    private final AiGenerationService aiGenerationService;
    private final AiStudioContextDigest contextDigest;
    private final AiGatewayClient aiGatewayClient;
    private final AiResponseParser aiResponseParser;
    private final ApplicationEventPublisher events;
    private final ExecutorService executor;

    /** 功能单元 → 运行期间积压的下一次请求；键存在即表示该功能单元有作业在跑。 */
    private final Map<Long, Optional<Request>> running = new LinkedHashMap<>();

    @Autowired
    public AiStudioDocumentSyncService(
            FunctionUnitDocumentService documentService,
            AiStudioThreadService threadService,
            AiGenerationService aiGenerationService,
            AiStudioContextDigest contextDigest,
            AiGatewayClient aiGatewayClient,
            AiResponseParser aiResponseParser,
            ApplicationEventPublisher events,
            @Value("${ai-generation.studio.doc-sync-workers:2}") int workers,
            @Value("${ai-generation.studio.doc-sync-queue-size:32}") int queueSize) {
        this(documentService, threadService, aiGenerationService, contextDigest, aiGatewayClient,
                aiResponseParser, events,
                new ThreadPoolExecutor(workers, workers, 60, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(queueSize), r -> {
                            Thread t = new Thread(r, "ai-studio-doc-sync");
                            t.setDaemon(true);
                            return t;
                        }));
    }

    AiStudioDocumentSyncService(FunctionUnitDocumentService documentService, AiStudioThreadService threadService,
                                AiGenerationService aiGenerationService, AiStudioContextDigest contextDigest,
                                AiGatewayClient aiGatewayClient, AiResponseParser aiResponseParser,
                                ApplicationEventPublisher events, ExecutorService executor) {
        this.documentService = documentService;
        this.threadService = threadService;
        this.aiGenerationService = aiGenerationService;
        this.contextDigest = contextDigest;
        this.aiGatewayClient = aiGatewayClient;
        this.aiResponseParser = aiResponseParser;
        this.events = events;
        this.executor = executor;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public boolean isRunning(Long functionUnitId) {
        synchronized (running) {
            return running.containsKey(functionUnitId);
        }
    }

    /**
     * 在请求线程上准备输入并提交。准备失败（例如设计过大无法序列化）写一条 FAILED 消息后返回——
     * 文档同步失败不能让确认阶段本身失败。
     *
     * @param phases 本次新确认的阶段（按确认顺序）；空列表表示全量核对
     */
    public void submit(Long functionUnitId, List<String> phases, String messagePhase, String amToken,
                       AiStudioThreadService.Author author) {
        Request request;
        try {
            request = prepare(functionUnitId, phases, messagePhase, amToken, author);
        } catch (RuntimeException e) {
            log.error("AI Studio document sync could not be prepared: functionUnitId={}, phases={}",
                    functionUnitId, phases, e);
            recordFailure(functionUnitId, phases, messagePhase, author, e);
            return;
        }
        synchronized (running) {
            if (running.containsKey(functionUnitId)) {
                Request pending = running.get(functionUnitId).map(p -> p.mergedWith(request)).orElse(request);
                running.put(functionUnitId, Optional.of(pending));
                log.info("AI Studio document sync queued behind the running one: functionUnitId={}, phases={}",
                        functionUnitId, pending.phases());
                return;
            }
            running.put(functionUnitId, Optional.empty());
        }
        publish(AiStudioThreadEvent.DOC_SYNC_STARTED, request);
        try {
            executor.execute(() -> runAll(request));
        } catch (RejectedExecutionException e) {
            synchronized (running) {
                running.remove(functionUnitId);
            }
            log.error("AI Studio document sync rejected (queue full): functionUnitId={}", functionUnitId, e);
            recordFailure(functionUnitId, phases, messagePhase, author,
                    new AiGenerationException("AI_STUDIO_DOC_SYNC_QUEUE_FULL",
                            "Too many document updates are running; try again later"));
            publish(AiStudioThreadEvent.DOC_SYNC_FINISHED, request);
        }
    }

    private Request prepare(Long functionUnitId, List<String> phases, String messagePhase, String amToken,
                            AiStudioThreadService.Author author) {
        FunctionUnitContextDTO context = aiGenerationService.serializeFunctionUnitContext(functionUnitId);
        Map<String, List<AiStudioChatRequest.HistoryMessage>> conversation = new LinkedHashMap<>();
        for (String phase : phases.isEmpty() ? List.of(messagePhase) : phases) {
            conversation.put(phase, threadService.recentHistory(functionUnitId, phase));
        }
        // 复制一份：请求结束后原对象归还给请求线程的生命周期管理
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(SecurityContextHolder.getContext().getAuthentication());
        return new Request(functionUnitId, List.copyOf(phases), messagePhase, context, conversation, amToken,
                author, securityContext);
    }

    private void runAll(Request first) {
        Request request = first;
        try {
            while (request != null) {
                runOne(request);
                request = takePending(request.functionUnitId());
            }
        } finally {
            if (request != null) {
                // runOne 连失败消息都没写进去（例如库不可用）：别让这个功能单元永远停在"更新中"
                synchronized (running) {
                    running.remove(first.functionUnitId());
                }
            }
            publish(AiStudioThreadEvent.DOC_SYNC_FINISHED, first);
        }
    }

    /** 取出积压的下一次请求；没有则把该功能单元标记为空闲并返回 null。 */
    private Request takePending(Long functionUnitId) {
        synchronized (running) {
            Optional<Request> next = running.get(functionUnitId);
            if (next == null || next.isEmpty()) {
                running.remove(functionUnitId);
                return null;
            }
            running.put(functionUnitId, Optional.empty());
            return next.get();
        }
    }

    void runOne(Request request) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(request.securityContext());
        try {
            Map<AiDocumentType, Optional<AiDocument>> base = new EnumMap<>(AiDocumentType.class);
            for (AiDocumentType type : AiDocumentType.values()) {
                Optional<AiDocument> doc = documentService.latest(request.functionUnitId(), type);
                if (doc.isPresent() && doc.get().getContent().length() > MAX_DOCUMENT_CHARS_FOR_SYNC) {
                    throw new AiGenerationException("AI_STUDIO_DOC_SYNC_DOCUMENT_TOO_LARGE",
                            type + " document is longer than " + MAX_DOCUMENT_CHARS_FOR_SYNC
                                    + " characters and cannot be updated by AI");
                }
                base.put(type, doc);
            }
            ModelOutput output = callModel(request, base);
            record(request, base, output);
        } catch (RuntimeException e) {
            log.error("AI Studio document sync failed: functionUnitId={}, phases={}",
                    request.functionUnitId(), request.phases(), e);
            recordFailure(request.functionUnitId(), request.phases(), request.messagePhase(), request.author(), e);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    record ModelOutput(Map<AiDocumentType, String> documents, String changeSummary) {}

    /** 调一次模型；输出里两份文档既没有文档块也没有"不需要修改"标记时重试一次，仍不合格则失败。 */
    private ModelOutput callModel(Request request, Map<AiDocumentType, Optional<AiDocument>> base) {
        AiPromptBuilder.RenderedPrompt prompt = new AiPromptBuilder.RenderedPrompt(systemPrompt(),
                userMessage(request, base));
        for (int attempt = 1; ; attempt++) {
            String text = aiResponseParser.assistantText(aiGatewayClient.chat(prompt, request.amToken()));
            Optional<ModelOutput> output = parseOutput(text);
            if (output.isPresent()) {
                return output.get();
            }
            log.warn("AI Studio document sync output has no document blocks: functionUnitId={}, attempt={}, chars={}",
                    request.functionUnitId(), attempt, text.length());
            if (attempt == 2) {
                throw new AiGenerationException("AI_STUDIO_DOC_SYNC_BAD_OUTPUT",
                        "The model did not return the documents in the expected format");
            }
        }
    }

    /** 每份文档必须是"文档块"或"不需要修改"之一；返回的 map 只含有文档块的类型。 */
    static Optional<ModelOutput> parseOutput(String text) {
        Map<AiDocumentType, String> documents = new EnumMap<>(AiDocumentType.class);
        for (AiDocumentType type : AiDocumentType.values()) {
            Matcher m = DOC_BLOCKS.get(type).matcher(text);
            if (m.find()) {
                documents.put(type, m.group(1).strip());
            } else if (!text.contains(UNCHANGED_MARKERS.get(type))) {
                return Optional.empty();
            }
        }
        int summaryAt = text.lastIndexOf(CHANGE_SUMMARY_MARKER);
        String summary = summaryAt < 0 ? "" : text.substring(summaryAt + CHANGE_SUMMARY_MARKER.length()).strip();
        return Optional.of(new ModelOutput(documents, summary));
    }

    /** Design 文档二级标题 / 提示词里的阶段英文名（模型上下文，不是 UI 文案） */
    private static String phaseLabel(String phase) {
        return AiStudioPhase.fromKey(phase).map(AiStudioPhase::label).orElse(phase);
    }

    private static String systemPrompt() {
        List<String> phaseLabels = Arrays.stream(AiStudioPhase.values()).map(AiStudioPhase::label).toList();
        return SYSTEM_PROMPT.formatted(String.join(", ", phaseLabels),
                String.join(", ", REQUIREMENTS_SECTIONS));
    }

    private String userMessage(Request request, Map<AiDocumentType, Optional<AiDocument>> base) {
        StringBuilder sb = new StringBuilder();
        sb.append("Today: ").append(LocalDate.now()).append('\n');
        sb.append("Phases to check: ").append(request.fullCheck()
                ? "all phases (full check)"
                : String.join(", ", request.phases().stream().map(AiStudioDocumentSyncService::phaseLabel).toList())).append("\n\n");

        appendDocument(sb, "Requirements", base.get(AiDocumentType.REQUIREMENTS));
        appendDocument(sb, "Function Unit Design", base.get(AiDocumentType.DESIGN));

        sb.append("## Current design (name-level listing)\n");
        List<String> order = new ArrayList<>(request.phases());
        Arrays.stream(AiStudioPhase.values()).map(Enum::name).filter(p -> !order.contains(p)).forEach(order::add);
        int listingStart = sb.length();
        for (String phase : order) {
            String digest = contextDigest.digest(phase, request.context());
            String section = "### " + phaseLabel(phase) + "\n" + (digest.isEmpty() ? "(nothing yet)" : digest)
                    + "\n\n";
            if (sb.length() - listingStart + section.length() > DESIGN_LISTING_CHAR_BUDGET) {
                sb.append("…(remaining phases omitted for length)\n\n");
                break;
            }
            sb.append(section);
        }

        int conversationBudget = CONVERSATION_CHAR_BUDGET;
        for (Map.Entry<String, List<AiStudioChatRequest.HistoryMessage>> entry : request.conversation().entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            sb.append("## Recent conversation — ").append(phaseLabel(entry.getKey())).append('\n');
            for (AiStudioChatRequest.HistoryMessage m : entry.getValue()) {
                String line = m.getRole() + ": " + m.getContent() + "\n";
                if (line.length() > conversationBudget) {
                    sb.append("…(earlier conversation omitted)\n");
                    return sb.toString();
                }
                sb.append(line);
                conversationBudget -= line.length();
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void appendDocument(StringBuilder sb, String title, Optional<AiDocument> doc) {
        sb.append("## Current ").append(title).append(" document");
        if (doc.isPresent()) {
            sb.append(" (v").append(doc.get().getVersion()).append(")\n").append(doc.get().getContent());
        } else {
            sb.append("\n(empty)");
        }
        sb.append("\n\n");
    }

    private void record(Request request, Map<AiDocumentType, Optional<AiDocument>> base, ModelOutput output) {
        String versionSummary = versionSummary(request.phases());
        Map<String, Object> documents = new LinkedHashMap<>();
        boolean anyUpdated = false;
        boolean anyBlocked = false;
        for (AiDocumentType type : AiDocumentType.values()) {
            int baseVersion = base.get(type).map(AiDocument::getVersion).orElse(0);
            String baseContent = base.get(type).map(AiDocument::getContent).orElse("");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("fromVersion", baseVersion);
            result.put("toVersion", baseVersion);
            base.get(type).ifPresent(doc -> {
                result.put("fromLabel", FunctionUnitDocumentService.label(doc));
                result.put("toLabel", FunctionUnitDocumentService.label(doc));
            });
            String updated = output.documents().get(type);
            if (updated != null && !updated.equals(baseContent.strip())) {
                try {
                    AiDocument saved = documentService.append(request.functionUnitId(), type, updated, baseVersion,
                            versionSummary, request.author().userId());
                    result.put("toVersion", saved.getVersion());
                    result.put("toLabel", FunctionUnitDocumentService.label(saved));
                    anyUpdated = true;
                } catch (DeveloperBusinessException e) {
                    if (!"CONFLICT_DOCUMENT_VERSION".equals(e.getErrorCode())) throw e;
                    AiDocument latest = documentService.latest(request.functionUnitId(), type).orElseThrow();
                    result.put("toVersion", latest.getVersion());
                    result.put("toLabel", FunctionUnitDocumentService.label(latest));
                    result.put("blockedBy", latest.getCreatedBy());
                    anyBlocked = true;
                }
            }
            documents.put(type.name(), result);
        }
        String status = anyUpdated ? STATUS_UPDATED : anyBlocked ? STATUS_SKIPPED : STATUS_UNCHANGED;

        Map<String, Object> docSync = new LinkedHashMap<>();
        docSync.put("status", status);
        docSync.put("phases", request.phases());
        docSync.put("documents", documents);
        docSync.put("changeSummary", output.changeSummary());
        threadService.appendDocSyncMessage(request.functionUnitId(), request.messagePhase(),
                "Documents check (" + status + "): " + output.changeSummary(), docSync, request.author());
        log.info("AI Studio document sync done: functionUnitId={}, phases={}, status={}, documents={}",
                request.functionUnitId(), request.phases(), status, documents);
    }

    private void recordFailure(Long functionUnitId, List<String> phases, String messagePhase,
                               AiStudioThreadService.Author author, RuntimeException e) {
        String code = e instanceof AiGenerationException age && age.getErrorCode() != null
                ? age.getErrorCode()
                : e instanceof DeveloperBusinessException dbe && dbe.getErrorCode() != null
                ? dbe.getErrorCode()
                : "AI_STUDIO_DOC_SYNC_FAILED";
        String message = e.getMessage() != null ? e.getMessage() : e.toString();
        Map<String, Object> docSync = new LinkedHashMap<>();
        docSync.put("status", STATUS_FAILED);
        docSync.put("phases", phases);
        docSync.put("errorCode", code);
        docSync.put("errorMessage", message);
        threadService.appendDocSyncMessage(functionUnitId, messagePhase,
                "Documents check failed (" + code + "): " + message, docSync, author);
    }

    static String versionSummary(List<String> phases) {
        return FunctionUnitDocumentService.SUMMARY_AI_SYNC_PREFIX + String.join(",", phases);
    }

    private void publish(String type, Request request) {
        events.publishEvent(AiStudioThreadEvent.docSync(type, request.functionUnitId(), request.messagePhase(),
                request.author().userId(), request.author().name()));
    }
}
