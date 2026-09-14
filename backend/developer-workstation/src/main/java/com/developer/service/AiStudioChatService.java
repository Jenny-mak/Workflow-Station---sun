package com.developer.service;

import com.developer.dto.AiStudioChatRequest;
import com.developer.dto.FunctionUnitContextDTO;
import com.developer.enums.AiMode;

/**
 * AI Studio Copilot 对话服务。
 *
 * <p>模型链路与 AI Generate 完全同源：同一个 {@code AiGatewayClient}（集团 gateway +
 * 每用户 AMToken / dev 静态 key）、同一个 {@code AiResponseParser}。区别只在编排：
 * 无会话持久化、无锁、无文档与生成数据产出，单轮请求带历史直出回复。</p>
 */
public interface AiStudioChatService {

    /**
     * 单轮结果。普通对话只有 reply；propose 轮次可能附带改动提案
     * （{@code AiGeneratedData} 同构的 Map）与其写入范围。
     */
    record StudioChatResult(String reply, java.util.Map<String, Object> proposal, String proposalScope) {
    }

    /**
     * 发起一轮 Copilot 对话。
     *
     * <p>{@code request.propose=true} 时切换到 AI Generate 的 GENERATION 管线：
     * 带全量设计上下文与 schema 元数据，按当前阶段限定 regenerateScope，
     * 产出可 Apply 的结构化提案。</p>
     *
     * @param amToken 该用户的 DSP AMToken，透传给 AI gateway 作 Bearer 凭证；
     *                缺失时以 {@code AI_GATEWAY_TOKEN_MISSING} 显式失败，不做匿名调用
     */
    StudioChatResult chat(AiStudioChatRequest request, String amToken);

    /**
     * 已在请求线程上准备好的提案输入：scope、按 scope 限定的用户消息、序列化后的功能单元上下文与模式。
     *
     * <p>上下文序列化走 JPA 懒加载，必须在带事务的请求线程完成（与 AiGenerationComponentImpl#chatStream
     * 的注释同一条理由）；之后的模型调用可以脱离请求线程。</p>
     */
    record ProposalDraft(Long functionUnitId, String phase, String scope, String message,
                         FunctionUnitContextDTO context, AiMode mode) {
    }

    /**
     * 提案第一步（请求线程）：校验阶段是否支持结构化提案、序列化上下文、拼装 scoped 消息。
     * 不支持的阶段以 {@code AI_STUDIO_PROPOSAL_UNSUPPORTED_PHASE} 显式失败。
     */
    ProposalDraft prepareProposal(AiStudioChatRequest request);

    /**
     * 提案第二步（可在后台线程）：走 AI Generate 的 GENERATION 管线调模型并裁剪出 scope 内切片。
     * 推理模型跑一份 PROCESS 切片实测 7 分钟上下，所以调用方不应在 HTTP 请求线程上同步等它。
     */
    StudioChatResult runProposal(ProposalDraft draft, String amToken);
}
