package com.developer.dto;

import com.developer.enums.AiStudioPhase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * AI Studio Copilot 对话请求。
 *
 * <p>与 AI Generate 的 {@link AiChatRequest} 刻意分开：Copilot 是无会话、无锁、无文档产出的
 * 轻量顾问式对话，历史由前端随请求带上（不落库），phase 是 AI Studio 的 11 阶段而不是
 * {@code AiPhase} 三阶段。</p>
 */
@Data
public class AiStudioChatRequest {

    @NotNull
    private Long functionUnitId;

    /** AI Studio 阶段 key，见 {@link AiStudioPhase}。 */
    @NotBlank
    @Pattern(regexp = AiStudioPhase.KEY_PATTERN)
    private String phase;

    @NotBlank
    @Size(max = 4000)
    private String message;

    /**
     * true = 本轮要求产出结构化改动提案（走 AI Generate 的 GENERATION 管线，带全量设计上下文，
     * 耗时可达分钟级）；false = 普通顾问式对话。
     */
    private boolean propose;

    /** 近期对话历史（旧→新），服务端还会按字符预算再截断一次。 */
    @Valid
    @Size(max = 20)
    private List<HistoryMessage> history;

    @Data
    public static class HistoryMessage {

        @NotBlank
        @Pattern(regexp = "USER|ASSISTANT")
        private String role;

        @NotBlank
        @Size(max = 4000)
        private String content;

        /** 该条 ASSISTANT 消息附带的结构化提案（AiGeneratedData 同构），供二次修改时模型参考；可空 */
        private java.util.Map<String, Object> proposal;

        /** 提案的写入范围；可空 */
        private String proposalScope;
    }
}
