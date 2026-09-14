package com.developer.component.impl;

import com.developer.component.AiStudioChatComponent;
import com.developer.dto.AiGeneratedData;
import com.developer.dto.AiStudioApplyRequest;
import com.developer.dto.AiStudioChatRequest;
import com.developer.dto.AiStudioChatResponse;
import com.developer.dto.AiStudioProposalJobResponse;
import com.developer.dto.AiValidationResult;
import com.developer.exception.AiGenerationException;
import com.developer.exception.AiValidationFailedException;
import com.developer.service.impl.AiStudioChatServiceImpl;

import java.util.LinkedHashMap;
import java.util.Map;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.security.WorkspaceAccessAction;
import com.developer.service.AiLockService;
import com.developer.service.AiStudioChatService;
import com.developer.service.AiStudioProposalJobService;
import com.developer.service.AiValidationService;
import com.developer.service.AiWriteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI Studio Copilot 组件实现。
 *
 * <p>applyProposal 与 {@code AiGenerationComponentImpl#applyGeneratedData} 是同一条写入管线的
 * 精简版：同一把 AI 锁、同一套归一化（同包 static 直接复用）、同一个校验与写入服务；
 * 少的是会话状态推进与 30s undo 快照——Copilot 无会话，undo 留给后续增量。</p>
 */
@Slf4j
@Component
public class AiStudioChatComponentImpl implements AiStudioChatComponent {

    private final AiStudioChatService aiStudioChatService;
    private final AiStudioProposalJobService aiStudioProposalJobService;
    private final AiLockService aiLockService;
    private final AiValidationService aiValidationService;
    private final AiWriteService aiWriteService;
    private final FunctionUnitWorkspaceAccessService functionUnitWorkspaceAccessService;
    private final ObjectMapper objectMapper;

    public AiStudioChatComponentImpl(AiStudioChatService aiStudioChatService,
                                     AiStudioProposalJobService aiStudioProposalJobService,
                                     AiLockService aiLockService,
                                     AiValidationService aiValidationService,
                                     AiWriteService aiWriteService,
                                     FunctionUnitWorkspaceAccessService functionUnitWorkspaceAccessService,
                                     ObjectMapper objectMapper) {
        this.aiStudioChatService = aiStudioChatService;
        this.aiStudioProposalJobService = aiStudioProposalJobService;
        this.aiLockService = aiLockService;
        this.aiValidationService = aiValidationService;
        this.aiWriteService = aiWriteService;
        this.functionUnitWorkspaceAccessService = functionUnitWorkspaceAccessService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiStudioChatResponse chat(AiStudioChatRequest request, String userId, String amToken) {
        if (request.isPropose()) {
            // 同步提案会在网关 300s 读超时上被掐断而后端仍在烧模型：从入口拒绝，别留一条必然超时的路
            throw new AiGenerationException("AI_STUDIO_PROPOSAL_USE_JOB",
                    "Change proposals run asynchronously; POST /ai-generation/studio-chat/proposals instead");
        }
        log.info("AI Studio copilot chat: functionUnitId={}, phase={}, userId={}",
                request.getFunctionUnitId(), request.getPhase(), userId);
        AiStudioChatService.StudioChatResult result = aiStudioChatService.chat(request, amToken);
        return AiStudioChatResponse.builder()
                .reply(result.reply())
                .proposal(result.proposal())
                .proposalScope(result.proposalScope())
                .build();
    }

    @Override
    public AiStudioProposalJobResponse startProposal(AiStudioChatRequest request, String userId, String amToken) {
        log.info("AI Studio proposal requested: functionUnitId={}, phase={}, userId={}",
                request.getFunctionUnitId(), request.getPhase(), userId);
        // 第一步必须留在请求线程：阶段不支持要立刻 4xx，且上下文序列化依赖请求事务里的 JPA 懒加载
        AiStudioChatService.ProposalDraft draft = aiStudioChatService.prepareProposal(request);
        return aiStudioProposalJobService.submit(request.getFunctionUnitId(), request.getPhase(), userId,
                () -> aiStudioChatService.runProposal(draft, amToken));
    }

    @Override
    public AiStudioProposalJobResponse getProposal(String jobId, String userId) {
        return aiStudioProposalJobService.get(jobId, userId);
    }

    @Override
    public void applyProposal(AiStudioApplyRequest request, String userId) {
        Long functionUnitId = request.getFunctionUnitId();
        functionUnitWorkspaceAccessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.MODIFY);

        // 与 AI Generate 共用同一把锁：并发的 AI 面板 apply / 其他用户的提案在此互斥（冲突 → 409）
        aiLockService.tryAcquire(functionUnitId, userId);
        try {
            // 按 scope 裁剪切片（信任边界：body 由客户端回传）。范围外的切片写下去会撞
            // clearScopedData 没清理的存量数据（如 processDefinition 的每 FU 唯一约束）。
            Map<String, Object> sliced = new LinkedHashMap<>(request.getGeneratedData());
            sliced.keySet().retainAll(AiStudioChatServiceImpl.allowedSlices(request.getScope()));
            if (sliced.isEmpty()) {
                throw new AiGenerationException("AI_STUDIO_PROPOSAL_EMPTY",
                        "The proposal contains no data slices for scope " + request.getScope());
            }
            AiGeneratedData data = objectMapper.convertValue(sliced, AiGeneratedData.class);
            AiGenerationComponentImpl.normalizeTableRelations(data.getTableRelations());
            AiGenerationComponentImpl.normalizeCrossFieldRules(data.getFormDefinitions());

            AiValidationResult validationResult = aiValidationService.validate(data);
            if (!validationResult.isValid()) {
                throw new AiValidationFailedException(validationResult.getErrors());
            }

            aiWriteService.applyGeneratedData(functionUnitId, data, request.getScope());
            log.info("AI Studio proposal applied: functionUnitId={}, scope={}, userId={}",
                    functionUnitId, request.getScope(), userId);
        } finally {
            aiLockService.release(functionUnitId, userId);
        }
    }
}
