package com.developer.component;

import com.developer.dto.AiStudioOneClickRequest;
import com.developer.dto.AiStudioProposalJobResponse;

/**
 * AI Studio 一键生成：一次模型调用产出整套核心设计（流程 / 表 / 关系 / 表单 / 动作 / 决策），
 * 预校验无 ERROR 时由后端直接写入。
 */
public interface AiStudioOneClickComponent {

    /**
     * 提交一键生成作业。作业与 Copilot 提案共用注册表：轮询、取消、换浏览器续等都走
     * {@code /ai-generation/studio-chat/proposals/*}，结果卡片落在 Process Design 阶段线程。
     *
     * @param amToken 该用户的 DSP AMToken，透传给 AI gateway
     */
    AiStudioProposalJobResponse start(AiStudioOneClickRequest request, String userId, String amToken);
}
