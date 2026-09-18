package com.developer.component.impl;

import com.developer.component.HermesMasterChatComponent;
import com.developer.dto.HermesMasterChatRequest;
import com.developer.dto.HermesMasterChatResponse;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.security.WorkspaceAccessAction;
import com.developer.service.HermesMasterChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Hermes Master 组件实现：访问校验 + 转交对话服务。对话不落库、不进 AI Studio 共享线程。
 */
@Slf4j
@Component
public class HermesMasterChatComponentImpl implements HermesMasterChatComponent {

    private final HermesMasterChatService hermesMasterChatService;
    private final FunctionUnitWorkspaceAccessService functionUnitWorkspaceAccessService;

    public HermesMasterChatComponentImpl(HermesMasterChatService hermesMasterChatService,
                                         FunctionUnitWorkspaceAccessService functionUnitWorkspaceAccessService) {
        this.hermesMasterChatService = hermesMasterChatService;
        this.functionUnitWorkspaceAccessService = functionUnitWorkspaceAccessService;
    }

    @Override
    public HermesMasterChatResponse chat(HermesMasterChatRequest request, String userId, String amToken) {
        if (request.getFunctionUnitId() != null) {
            // 设计摘要会进提示词：functionUnitId 由客户端给出，必须按工作区校验后才能读
            functionUnitWorkspaceAccessService.assertCanAccess(request.getFunctionUnitId(), WorkspaceAccessAction.VIEW);
        }
        log.info("Hermes Master chat: functionUnitId={}, page={}, userId={}",
                request.getFunctionUnitId(), request.getPage(), userId);
        return HermesMasterChatResponse.builder()
                .reply(hermesMasterChatService.chat(request, amToken))
                .build();
    }
}
