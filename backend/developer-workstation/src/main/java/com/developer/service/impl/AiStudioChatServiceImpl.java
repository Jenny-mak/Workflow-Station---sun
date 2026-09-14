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
    private static final int TRANSCRIPT_CHAR_BUDGET = 8000;

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
            user's latest message. Be concise; prefer short lists over long prose.""";

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
     * 不在表里的阶段（View/Automation/Connections/Email/Validation）没有对应的
     * generatedData 切片，不支持结构化提案。
     */
    private static final Map<String, String> PROPOSAL_SCOPE_BY_PHASE = Map.of(
            "PROCESS_DESIGN", "PROCESS",
            "TABLE_DESIGN", "TABLES",
            "FORM_DESIGN", "FORMS",
            "ACTION_DESIGN", "ACTIONS",
            "DECISION_DESIGN", "DECISIONS");

    /**
     * scope → 允许写入的 generatedData 切片，与 {@code AiWriteServiceImpl#clearScopedData}
     * 的清理范围对齐（TABLES 清的是整个表图谱，所以连关系一起）。模型在 scoped 轮次里经常
     * 顺手带上范围外的切片（如 processDefinition）——写入层会照单全写，撞上"每 FU 一份流程
     * 定义"这类唯一约束，所以提案返回前与 Apply 落库前都必须按这张表裁剪。
     */
    private static final Map<String, Set<String>> SCOPE_SLICES = Map.of(
            "TABLES", Set.of("tableDefinitions", "tableRelations"),
            "TABLE_RELATIONS", Set.of("tableRelations"),
            "FORMS", Set.of("formDefinitions"),
            "ACTIONS", Set.of("actionDefinitions"),
            "DECISIONS", Set.of("decisionDefinitions"),
            "PROCESS", Set.of("processDefinition"));

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

    public AiStudioChatServiceImpl(AiGatewayClient aiGatewayClient, AiResponseParser aiResponseParser,
                                   AiGenerationService aiGenerationService) {
        this.aiGatewayClient = aiGatewayClient;
        this.aiResponseParser = aiResponseParser;
        this.aiGenerationService = aiGenerationService;
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
        return buildTranscript(request) + "\n\n"
                + "========== Scoped change request (system-provided, highest priority) ==========\n"
                + "Regenerate ONLY the '" + scope + "' slice of the design to fulfil the user's latest request.\n"
                + "The GENERATED_DATA block must contain exactly these keys and nothing else: "
                + allowedSlices(scope) + ".\n"
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
        String user = buildTranscript(request);

        Map<String, Object> httpResult = aiGatewayClient.chat(
                new AiPromptBuilder.RenderedPrompt(system, user), amToken);
        Map<String, Object> parsed = aiResponseParser.parse(httpResult);

        Object reply = parsed.get("reply");
        if (!(reply instanceof String text) || text.isBlank()) {
            // parse() 对空 choices 已显式失败；这里挡的是"内容全被文档块吃掉"的极端情况
            throw new AiGenerationException("AI_GATEWAY_EMPTY_RESPONSE",
                    "AI gateway returned no usable reply text");
        }
        log.info("AI Studio copilot replied: functionUnitId={}, phase={}, historySize={}, replyChars={}",
                request.getFunctionUnitId(), request.getPhase(),
                request.getHistory() == null ? 0 : request.getHistory().size(), text.length());
        return text.trim();
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
        while (start > 0) {
            AiStudioChatRequest.HistoryMessage m = history.get(start - 1);
            int cost = m.getContent().length() + 16;
            if (used + cost > budget) break;
            used += cost;
            start--;
        }
        transcript.append("Conversation so far:\n");
        for (int i = start; i < history.size(); i++) {
            AiStudioChatRequest.HistoryMessage m = history.get(i);
            transcript.append("USER".equals(m.getRole()) ? "User: " : "Assistant: ")
                    .append(m.getContent()).append('\n');
        }
        return transcript.append(tail).toString();
    }
}
