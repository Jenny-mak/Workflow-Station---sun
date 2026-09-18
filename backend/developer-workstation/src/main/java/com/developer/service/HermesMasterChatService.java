package com.developer.service;

import com.developer.dto.HermesMasterChatRequest;

/**
 * Hermes Master（HM）对话服务：DW 全局助手的单轮顾问式对话。
 *
 * <p>模型链路与 AI Studio Copilot 同源（同一个 {@code AiGatewayClient} / {@code AiResponseParser}）。
 * HM 只回答与引导，不产出结构化提案、不写设计、不落库。</p>
 */
public interface HermesMasterChatService {

    /** @return 模型回复（Markdown）；网关失败沿用 {@code AiGenerationException} 的错误码 */
    String chat(HermesMasterChatRequest request, String amToken);
}
