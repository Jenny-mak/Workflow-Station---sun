package com.developer.component;

import com.developer.dto.HermesMasterChatRequest;
import com.developer.dto.HermesMasterChatResponse;

/**
 * Hermes Master（HM，DW 全局助手）组件接口。
 */
public interface HermesMasterChatComponent {

    /**
     * 单轮顾问式对话；amToken 透传给 AI gateway。
     * 请求带 functionUnitId 时先做工作区 VIEW 校验，再把该功能单元的设计摘要带给模型。
     */
    HermesMasterChatResponse chat(HermesMasterChatRequest request, String userId, String amToken);
}
