package com.developer.enums;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI Studio 引导阶段（11 个），声明顺序 = 设计器 Tab 顺序，与前端
 * {@code utils/aiStudioDraft.ts} 的 {@code AI_STUDIO_PHASES} 一致。
 *
 * <p>常量名就是落库（dw_ai_studio_messages.phase、线程状态、提案作业）和 API 里的阶段 key，
 * 不能改名。与 AI Generate 的三阶段 {@link AiPhase} 无关。</p>
 */
public enum AiStudioPhase {

    PROCESS_DESIGN("Process Design", "PROCESS", "review the BPMN flow, roles and conditions"),
    TABLE_DESIGN("Table Design", "TABLES", "define main and sub tables, fields and keys"),
    FORM_DESIGN("Form Design", "FORMS", "bind forms to tables and lay out fields"),
    VIEW_DESIGN("View Design", "VIEWS", "configure main table views and access control"),
    ACTION_DESIGN("Action Design", "ACTIONS", "define actions triggered from views and forms"),
    AUTOMATION("Automation", "SERVICE_TASK_BINDINGS", "configure service tasks and automation flows"),
    CONNECTIONS("Connections", "CONNECTIONS", "manage external connections used by this unit"),
    EMAIL_TEMPLATES("Email Templates", "EMAIL_TEMPLATES", "author email templates for notifications"),
    EMAIL_MONITORS("Email Monitors", "EMAIL_MONITORS", "set up inbound email monitors"),
    DECISION_DESIGN("Decision Design", "DECISIONS", "model decision tables used by the process"),
    /** 校验门禁，没有 generatedData 切片，所以不支持提案 */
    VALIDATION("Validation", null, "run the final whole-design checks before deployment");

    /**
     * {@code @Pattern} 需要编译期常量，只能手写；{@code AiStudioPhaseTest} 断言它与 {@link #values()} 完全一致。
     */
    public static final String KEY_PATTERN = "PROCESS_DESIGN|TABLE_DESIGN|FORM_DESIGN|VIEW_DESIGN|ACTION_DESIGN"
            + "|AUTOMATION|CONNECTIONS|EMAIL_TEMPLATES|EMAIL_MONITORS|DECISION_DESIGN|VALIDATION";

    private static final Map<String, AiStudioPhase> BY_KEY = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

    private final String label;
    private final String proposalScope;
    private final String advisoryHint;

    AiStudioPhase(String label, String proposalScope, String advisoryHint) {
        this.label = label;
        this.proposalScope = proposalScope;
        this.advisoryHint = advisoryHint;
    }

    /** 英文名：模型提示词、Design 文档二级标题（不是 UI 文案） */
    public String label() {
        return label;
    }

    /** propose 轮次的 AiWriteService regenerateScope；不支持提案的阶段为空 */
    public Optional<String> proposalScope() {
        return Optional.ofNullable(proposalScope);
    }

    /** 顾问式对话 system prompt 里的一句话职责描述 */
    public String advisoryHint() {
        return advisoryHint;
    }

    /** 按落库/API key 查找；null 或未知 key 返回空（不抛异常，调用方决定怎么处理）。 */
    public static Optional<AiStudioPhase> fromKey(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(BY_KEY.get(key));
    }

    public static boolean isValid(String key) {
        return fromKey(key).isPresent();
    }
}
