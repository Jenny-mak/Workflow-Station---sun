package com.developer.service;

import com.developer.dto.AiStudioChatRequest;
import com.developer.dto.FunctionUnitContextDTO;
import com.developer.enums.AiMode;

import java.util.List;
import java.util.Map;

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
    record StudioChatResult(String reply, java.util.Map<String, Object> proposal, String proposalScope,
                            com.developer.dto.AiStudioProposalPreview preview) {
        public StudioChatResult(String reply, java.util.Map<String, Object> proposal, String proposalScope) {
            this(reply, proposal, proposalScope, null);
        }

        public StudioChatResult withPreview(com.developer.dto.AiStudioProposalPreview p) {
            return new StudioChatResult(reply, proposal, proposalScope, p);
        }
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
                         FunctionUnitContextDTO context, AiMode mode,
                         List<Map<String, String>> documents) {
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

    /**
     * 一键生成第一步（请求线程）：与 {@link #prepareProposal} 同样序列化上下文，但写入范围是整套核心设计
     * （scope {@code ALL}），消息尾追加"一次产出全部核心切片"的指令。{@code request.phase} 只决定结果落在
     * 哪个阶段线程，不限定范围。第二步直接用 {@link #runProposal}。
     */
    ProposalDraft prepareOneClick(AiStudioChatRequest request);
}
