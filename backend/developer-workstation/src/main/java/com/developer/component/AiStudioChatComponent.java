package com.developer.component;

import com.developer.dto.AiStudioApplyRequest;
import com.developer.dto.AiStudioChatRequest;
import com.developer.dto.AiStudioChatResponse;
import com.developer.dto.AiStudioProposalJobResponse;

/**
 * AI Studio Copilot 组件接口。
 *
 * <p>与 {@link AiGenerationComponent} 分开而不是往里加方法：那个接口的实现类被多处单测以
 * 构造函数直接 new，扩参会连坐改一串测试；Copilot 编排也确实是另一件事（无会话、无锁的轻对话 +
 * 显式 propose/apply）。</p>
 */
public interface AiStudioChatComponent {

    /**
     * 单轮顾问式 Copilot 对话；amToken 透传给 AI gateway。
     * {@code propose=true} 在此入口以 {@code AI_STUDIO_PROPOSAL_USE_JOB} 拒绝——提案必须走 {@link #startProposal}。
     */
    AiStudioChatResponse chat(AiStudioChatRequest request, String userId, String amToken);

    /**
     * 发起改动提案作业：请求线程上准备上下文（阶段校验 + JPA 序列化），模型调用交后台线程。
     * 立即返回作业快照，前端按 jobId 轮询 {@link #getProposal}。amToken 只在作业存续期内驻留内存。
     */
    AiStudioProposalJobResponse startProposal(AiStudioChatRequest request, String userId, String amToken);

    /** 查询提案作业；仅作业发起者可见，其他情况一律 {@code AI_STUDIO_PROPOSAL_NOT_FOUND}。 */
    AiStudioProposalJobResponse getProposal(String jobId, String userId);

    /**
     * 应用改动提案：工作区访问校验 → 抢 AI 锁（与 AI Generate 同一把，冲突 409）→
     * 归一化 + 平台校验（失败 422，不落库）→ 按 scope 写入 → 释放锁。
     */
    void applyProposal(AiStudioApplyRequest request, String userId);
}
