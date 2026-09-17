package com.developer.service.impl;

import com.developer.dto.AiStudioChatRequest;
import com.developer.dto.FunctionUnitContextDTO;
import com.developer.enums.AiMode;
import com.developer.enums.AiPhase;
import com.developer.exception.AiGenerationException;
import com.developer.service.AiGenerationService;
import com.developer.service.AiStudioChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AI Studio Copilot 对话实现。
 *
 * <p>Prompt 不走 {@code AiPromptBuilder}（那套模板与 AI Generate 的三阶段/文档协议绑定），
 * 而是本类自带的顾问式 system prompt + 明文对话转写。响应仍交 {@link AiResponseParser}——
 * 它负责 OpenAI 兼容信封的拆取与空响应显式失败，Copilot 只取其中的 {@code reply}。</p>
 */
@Slf4j
@Service
public class AiStudioChatServiceImpl implements AiStudioChatService {

    /** 对话转写的字符预算：超出时从最旧的历史开始丢，永远保住最新一条用户消息。 */
    private static final int TRANSCRIPT_CHAR_BUDGET = 16000;

    /** 历史里单条提案 JSON 进转写的上限：够模型看清上一轮提了什么，又不至于把设计上下文挤出窗口。 */
    static final int HISTORY_PROPOSAL_CHAR_CAP = 6000;

    private static final com.fasterxml.jackson.databind.ObjectMapper HISTORY_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are AI Copilot inside AI Studio of Workflow Station, a low-code workflow platform \
            (Developer Workstation). The user designs a Function Unit through 11 guided phases: \
            Process Design (BPMN), Table Design, Form Design, View Design, Action Design, \
            Automation (service tasks), Connections, Email Templates, Email Monitors, \
            Decision Design (decision tables), Validation.
            Current phase: %s — %s
            You are advisory only: you cannot modify the design yourself; the user applies every \
            change in the designer on the left. Give concrete, actionable guidance in the \
            platform's own terms for the current phase. Answer in the same language as the \
            user's latest message. Be concise; prefer short lists over long prose.
            The user message may start with the function unit's current design for this phase. \
            When it does, ground every answer in those real names (tables, fields, forms, nodes, \
            flow keys) and never invent ones that are not listed. The listing is name-level only: \
            say so instead of guessing when a detail it does not carry is needed.""";

    /** 阶段 key → system prompt 里的一句话职责描述（模型上下文用，非 UI 文案）。 */
    private static final Map<String, String> PHASE_BLURBS = Map.ofEntries(
            Map.entry("PROCESS_DESIGN", "review the BPMN flow, roles and conditions"),
            Map.entry("TABLE_DESIGN", "define main and sub tables, fields and keys"),
            Map.entry("FORM_DESIGN", "bind forms to tables and lay out fields"),
            Map.entry("VIEW_DESIGN", "configure main table views and access control"),
            Map.entry("ACTION_DESIGN", "define actions triggered from views and forms"),
            Map.entry("AUTOMATION", "configure service tasks and automation flows"),
            Map.entry("CONNECTIONS", "manage external connections used by this unit"),
            Map.entry("EMAIL_TEMPLATES", "author email templates for notifications"),
            Map.entry("EMAIL_MONITORS", "set up inbound email monitors"),
            Map.entry("DECISION_DESIGN", "model decision tables used by the process"),
            Map.entry("VALIDATION", "run the final whole-design checks before deployment"));

    /**
     * propose 轮次的写入范围：AI Studio 阶段 → AiWriteService 的 regenerateScope。
     * 只有 Validation 阶段没有对应的 generatedData 切片（它是校验门禁，无设计产物）。
     * 邮件三阶段、视图与 service task 绑定的切片是 upsert 语义（见 {@code AiWriteServiceImpl}）。
     */
    private static final Map<String, String> PROPOSAL_SCOPE_BY_PHASE = Map.ofEntries(
            Map.entry("PROCESS_DESIGN", "PROCESS"),
            Map.entry("TABLE_DESIGN", "TABLES"),
            Map.entry("FORM_DESIGN", "FORMS"),
            Map.entry("ACTION_DESIGN", "ACTIONS"),
            Map.entry("DECISION_DESIGN", "DECISIONS"),
            Map.entry("EMAIL_TEMPLATES", "EMAIL_TEMPLATES"),
            Map.entry("CONNECTIONS", "CONNECTIONS"),
            Map.entry("EMAIL_MONITORS", "EMAIL_MONITORS"),
            Map.entry("VIEW_DESIGN", "VIEWS"),
            Map.entry("AUTOMATION", "SERVICE_TASK_BINDINGS"));

    /** 按业务键 upsert 的 scope：提案只列新增/修改项，未提及的对象保持不变。 */
    static final Set<String> UPSERT_SCOPES = Set.of(
            "EMAIL_TEMPLATES", "CONNECTIONS", "EMAIL_MONITORS", "VIEWS", "SERVICE_TASK_BINDINGS");

    /**
     * scope → 允许写入的 generatedData 切片，与 {@code AiWriteServiceImpl#clearScopedData}
     * 的清理范围对齐（TABLES 清的是整个表图谱，所以连关系一起）。模型在 scoped 轮次里经常
     * 顺手带上范围外的切片（如 processDefinition）——写入层会照单全写，撞上"每 FU 一份流程
     * 定义"这类唯一约束，所以提案返回前与 Apply 落库前都必须按这张表裁剪。
     */
    private static final Map<String, Set<String>> SCOPE_SLICES = Map.ofEntries(
            Map.entry("TABLES", Set.of("tableDefinitions", "tableRelations")),
            Map.entry("TABLE_RELATIONS", Set.of("tableRelations")),
            Map.entry("FORMS", Set.of("formDefinitions")),
            Map.entry("ACTIONS", Set.of("actionDefinitions")),
            Map.entry("DECISIONS", Set.of("decisionDefinitions")),
            Map.entry("PROCESS", Set.of("processDefinition")),
            Map.entry("EMAIL_TEMPLATES", Set.of("emailTemplates")),
            Map.entry("CONNECTIONS", Set.of("emailConnections")),
            Map.entry("EMAIL_MONITORS", Set.of("emailMonitorRules")),
            Map.entry("VIEWS", Set.of("mainTableViews")),
            Map.entry("SERVICE_TASK_BINDINGS", Set.of("serviceTaskBindings")));

    /** scope 允许的切片 key；ALL 返回全部。供本类与 Apply 编排（component）共用。 */
    public static Set<String> allowedSlices(String scope) {
        if ("ALL".equalsIgnoreCase(scope)) {
            return SCOPE_SLICES.values().stream()
                    .flatMap(Set::stream)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        Set<String> slices = SCOPE_SLICES.get(scope);
        if (slices == null) {
            throw new AiGenerationException("AI_STUDIO_UNKNOWN_SCOPE", "Unknown proposal scope: " + scope);
        }
        return slices;
    }

    private final AiGatewayClient aiGatewayClient;
    private final AiResponseParser aiResponseParser;
    private final AiGenerationService aiGenerationService;
    private final AiStudioContextDigest contextDigest;

    public AiStudioChatServiceImpl(AiGatewayClient aiGatewayClient, AiResponseParser aiResponseParser,
                                   AiGenerationService aiGenerationService,
                                   AiStudioContextDigest contextDigest) {
        this.aiGatewayClient = aiGatewayClient;
        this.aiResponseParser = aiResponseParser;
        this.aiGenerationService = aiGenerationService;
        this.contextDigest = contextDigest;
    }

    @Override
    public StudioChatResult chat(AiStudioChatRequest request, String amToken) {
        if (request.isPropose()) {
            // 同步形态只留给单测与直接调用；HTTP 入口走 prepare + 后台 run（见 AiStudioProposalJobService）
            return runProposal(prepareProposal(request), amToken);
        }
        return new StudioChatResult(advisoryChat(request, amToken), null, null);
    }

    /**
     * 提案第一步：校验阶段、序列化上下文（JPA 懒加载，必须在请求线程）、拼 scoped 消息。
     */
    @Override
    public ProposalDraft prepareProposal(AiStudioChatRequest request) {
        String scope = PROPOSAL_SCOPE_BY_PHASE.get(request.getPhase());
        if (scope == null) {
            throw new AiGenerationException("AI_STUDIO_PROPOSAL_UNSUPPORTED_PHASE",
                    "Phase " + request.getPhase() + " has no structured proposal scope; "
                            + "proposals are supported for: " + PROPOSAL_SCOPE_BY_PHASE.keySet());
        }
        FunctionUnitContextDTO context =
                aiGenerationService.serializeFunctionUnitContext(request.getFunctionUnitId());
        AiMode mode = aiGenerationService.determineMode(request.getFunctionUnitId());
        return new ProposalDraft(request.getFunctionUnitId(), request.getPhase(), scope,
                buildProposalMessage(request, scope), context, mode);
    }

    /**
     * 提案第二步：复用 AI Generate 的 GENERATION 管线（{@code callAiModel} 自带上下文序列化时的
     * 提示词模板、schema 元数据、校验失败自动修复重试）。sessionId 用随机 UUID——
     * Copilot 无会话持久化，管线只拿它查历史（查不到即空），对话上下文已折进 draft.message。
     */
    @Override
    public StudioChatResult runProposal(ProposalDraft draft, String amToken) {
        String scope = draft.scope();
        Map<String, Object> parsed = aiGenerationService.callAiModel(
                UUID.randomUUID(), draft.message(), AiPhase.GENERATION, draft.mode(),
                draft.context(), draft.functionUnitId(), null, scope, amToken);

        Object reply = parsed.get("reply");
        Object generatedData = parsed.get("generatedData");
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = generatedData instanceof Map<?, ?> m
                ? new java.util.LinkedHashMap<>((Map<String, Object>) m)
                : null;
        if (proposal != null) {
            proposal.keySet().retainAll(allowedSlices(scope));
            if (proposal.isEmpty()) proposal = null;
        }
        if (proposal == null && (!(reply instanceof String r) || r.isBlank())) {
            // 既没有数据块也没有解释文本：显式失败，别让前端拿到一张空白卡
            throw new AiGenerationException("AI_STUDIO_PROPOSAL_EMPTY",
                    "The model returned neither a proposal data block nor an explanation");
        }
        log.info("AI Studio proposal round: functionUnitId={}, phase={}, scope={}, hasProposal={}, replyChars={}",
                draft.functionUnitId(), draft.phase(), scope, proposal != null,
                reply instanceof String r ? r.length() : 0);
        return new StudioChatResult(
                reply instanceof String r && !r.isBlank() ? r.trim() : null,
                proposal,
                proposal != null ? scope : null);
    }

    /**
     * propose 轮次的用户消息 = 对话转写 + 显式的 scope 限定指令。
     *
     * <p>GENERATION 提示词在 NEW 模式（空功能单元）下默认产出整套设计——包括一份大概率过不了
     * 平台校验的 BPMN，而校验失败会让整个提案轮次失败。这里从源头限定：只产出 scope 内的切片。
     * 即便模型仍旧多给，{@code allowedSlices} 的裁剪也会兜住，但少生成就少一次校验失败的机会。</p>
     */
    private String buildProposalMessage(AiStudioChatRequest request, String scope) {
        String upsertNote = UPSERT_SCOPES.contains(scope)
                ? "This slice is applied as an upsert keyed by name: list ONLY the items you add or change; "
                        + "every existing item you do not list stays untouched, nothing is deleted.\n"
                : "";
        return buildTranscript(request) + "\n\n"
                + "========== Scoped change request (system-provided, highest priority) ==========\n"
                + "Regenerate ONLY the '" + scope + "' slice of the design to fulfil the user's latest request.\n"
                + "The GENERATED_DATA block must contain exactly these keys and nothing else: "
                + allowedSlices(scope) + ".\n"
                + upsertNote
                + "Do NOT output any other slice (no process, forms, actions, decisions or tables outside the "
                + "scope), do NOT rename the function unit, and do NOT include an icon.\n"
                + "========== End of scoped change request ==========";
    }

    private String advisoryChat(AiStudioChatRequest request, String amToken) {
        String blurb = PHASE_BLURBS.get(request.getPhase());
        if (blurb == null) {
            // DTO 的 @Pattern 已挡住未知阶段；这里兜的是两处枚举日后失同步的编程错误
            throw new AiGenerationException("AI_STUDIO_UNKNOWN_PHASE",
                    "Unknown AI Studio phase: " + request.getPhase());
        }

        String system = SYSTEM_PROMPT.formatted(request.getPhase(), blurb);
        String digest = advisoryDigest(request);
        String user = digest.isEmpty() ? buildTranscript(request)
                : "## Current design (" + request.getPhase() + ")\n" + digest + "\n\n" + buildTranscript(request);

        Map<String, Object> httpResult = aiGatewayClient.chat(
                new AiPromptBuilder.RenderedPrompt(system, user), amToken);
        Map<String, Object> parsed = aiResponseParser.parse(httpResult);

        Object reply = parsed.get("reply");
        if (!(reply instanceof String text) || text.isBlank()) {
            // parse() 对空 choices 已显式失败；这里挡的是"内容全被文档块吃掉"的极端情况
            throw new AiGenerationException("AI_GATEWAY_EMPTY_RESPONSE",
                    "AI gateway returned no usable reply text");
        }
        log.info("AI Studio copilot replied: functionUnitId={}, phase={}, historySize={}, digestChars={}, replyChars={}",
                request.getFunctionUnitId(), request.getPhase(),
                request.getHistory() == null ? 0 : request.getHistory().size(), digest.length(), text.length());
        return text.trim();
    }

    /**
     * 顾问轮的"当前设计现状"：按阶段裁剪的名称级摘要（见 {@link AiStudioContextDigest}）。
     *
     * <p>失败一律降级为无上下文对话——顾问式回答没有上下文只是泛泛而谈，为此让整轮对话失败
     * 是更差的结果。超大功能单元的 {@code AI_CONTEXT_TOO_LARGE} 也走这条路。</p>
     */
    private String advisoryDigest(AiStudioChatRequest request) {
        try {
            return contextDigest.digest(request.getPhase(),
                    aiGenerationService.serializeFunctionUnitContext(request.getFunctionUnitId()));
        } catch (RuntimeException e) {
            log.warn("AI Studio copilot chat continues without the design digest (functionUnitId={}): {}",
                    request.getFunctionUnitId(), e.getMessage());
            return "";
        }
    }

    /**
     * 历史 + 本轮消息 → 明文转写。从最旧开始丢直到进预算，最新一条用户消息永不截断
     * （超长消息已被 DTO 的 @Size(max=4000) 挡在门外）。
     */
    private String buildTranscript(AiStudioChatRequest request) {
        StringBuilder tail = new StringBuilder("User: ").append(request.getMessage());

        List<AiStudioChatRequest.HistoryMessage> history = request.getHistory();
        if (history == null || history.isEmpty()) {
            return tail.toString();
        }

        StringBuilder transcript = new StringBuilder();
        int budget = TRANSCRIPT_CHAR_BUDGET - tail.length();
        // 从最新的历史往回收，收满预算为止，再按时间序拼出
        int start = history.size();
        int used = 0;
        String[] rendered = new String[history.size()];
        while (start > 0) {
            AiStudioChatRequest.HistoryMessage m = history.get(start - 1);
            rendered[start - 1] = renderHistoryEntry(m);
            int cost = rendered[start - 1].length() + 1;
            if (used + cost > budget) break;
            used += cost;
            start--;
        }
        transcript.append("Conversation so far:\n");
        for (int i = start; i < history.size(); i++) {
            transcript.append(rendered[i]).append('\n');
        }
        return transcript.append(tail).toString();
    }

    /**
     * 单条历史 → 转写行。ASSISTANT 条目若附带上一轮的结构化提案，把提案 JSON（裁到
     * {@link #HISTORY_PROPOSAL_CHAR_CAP}）也写进去——否则"把刚才那个模板改一下"这类二次修改，
     * 模型只看得到自己说过"Here is the proposed change"，不知道提了什么。
     */
    private static String renderHistoryEntry(AiStudioChatRequest.HistoryMessage m) {
        StringBuilder sb = new StringBuilder("USER".equals(m.getRole()) ? "User: " : "Assistant: ")
                .append(m.getContent());
        if (!"USER".equals(m.getRole()) && m.getProposal() != null && !m.getProposal().isEmpty()) {
            String json;
            try {
                json = HISTORY_JSON.writeValueAsString(m.getProposal());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                json = String.valueOf(m.getProposal());
            }
            if (json.length() > HISTORY_PROPOSAL_CHAR_CAP) {
                json = json.substring(0, HISTORY_PROPOSAL_CHAR_CAP) + " …(truncated)";
            }
            sb.append("\n[Proposed change").append(m.getProposalScope() != null ? ", scope=" + m.getProposalScope() : "")
                    .append("; not yet applied unless the current data already reflects it] ").append(json);
        }
        return sb.toString();
    }
}
