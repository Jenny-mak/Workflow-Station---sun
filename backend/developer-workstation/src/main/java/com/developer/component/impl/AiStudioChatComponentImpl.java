package com.developer.component.impl;

import com.developer.component.AiStudioChatComponent;
import com.developer.dto.AiGeneratedData;
import com.developer.dto.AiStudioApplyRequest;
import com.developer.dto.AiStudioApplyResponse;
import com.developer.dto.AiStudioChatRequest;
import com.developer.dto.AiStudioChatResponse;
import com.developer.dto.AiStudioProposalJobResponse;
import com.developer.dto.AiValidationResult;
import com.developer.exception.AiGenerationException;
import com.developer.exception.AiValidationFailedException;
import com.developer.service.impl.AiStudioChatServiceImpl;
import com.developer.service.impl.AiStudioProposalReferenceValidator;
import com.developer.service.impl.AiStudioThreadService;

import java.util.LinkedHashMap;
import java.util.List;
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
    private final AiStudioProposalReferenceValidator referenceValidator;
    private final com.developer.service.impl.AiStudioProposalPreviewer previewer;
    private final com.developer.service.impl.AiStudioUndoService undoService;
    private final AiStudioThreadService threadService;
    private final ObjectMapper objectMapper;

    public AiStudioChatComponentImpl(AiStudioChatService aiStudioChatService,
                                     AiStudioProposalJobService aiStudioProposalJobService,
                                     AiLockService aiLockService,
                                     AiValidationService aiValidationService,
                                     AiWriteService aiWriteService,
                                     FunctionUnitWorkspaceAccessService functionUnitWorkspaceAccessService,
                                     AiStudioProposalReferenceValidator referenceValidator,
                                     com.developer.service.impl.AiStudioProposalPreviewer previewer,
                                     com.developer.service.impl.AiStudioUndoService undoService,
                                     AiStudioThreadService threadService,
                                     ObjectMapper objectMapper) {
        this.aiStudioChatService = aiStudioChatService;
        this.aiStudioProposalJobService = aiStudioProposalJobService;
        this.aiLockService = aiLockService;
        this.aiValidationService = aiValidationService;
        this.aiWriteService = aiWriteService;
        this.functionUnitWorkspaceAccessService = functionUnitWorkspaceAccessService;
        this.referenceValidator = referenceValidator;
        this.previewer = previewer;
        this.undoService = undoService;
        this.threadService = threadService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiStudioChatResponse chat(AiStudioChatRequest request, String userId, String amToken) {
        if (request.isPropose()) {
            // 同步提案会在网关 300s 读超时上被掐断而后端仍在烧模型：从入口拒绝，别留一条必然超时的路
            throw new AiGenerationException("AI_STUDIO_PROPOSAL_USE_JOB",
                    "Change proposals run asynchronously; POST /ai-generation/studio-chat/proposals instead");
        }
        // 线程按功能单元共享：发言即写进大家都能看到的线程，只读成员不能发
        functionUnitWorkspaceAccessService.assertCanAccess(request.getFunctionUnitId(), WorkspaceAccessAction.MODIFY);
        AiStudioThreadService.Author author = AiStudioThreadComponentImpl.currentAuthor(userId);
        log.info("AI Studio copilot chat: functionUnitId={}, phase={}, userId={}",
                request.getFunctionUnitId(), request.getPhase(), userId);
        useSharedHistory(request);
        AiStudioChatService.StudioChatResult result = aiStudioChatService.chat(request, amToken);
        // 对话轮成功才落库：失败的轮次只在发起人本地留一个错误气泡
        recordThread(request, () -> {
            threadService.appendUserMessage(request.getFunctionUnitId(), request.getPhase(), request.getMessage(), author);
            threadService.appendAssistantMessage(request.getFunctionUnitId(), request.getPhase(), result.reply(),
                    null, null, null, author);
        });
        return AiStudioChatResponse.builder()
                .reply(result.reply())
                .proposal(result.proposal())
                .proposalScope(result.proposalScope())
                .preview(result.preview())
                .build();
    }

    /**
     * 模型历史改由共享线程提供（含队友的讨论与提案）；线程为空或读库失败时沿用前端带来的历史。
     */
    private void useSharedHistory(AiStudioChatRequest request) {
        try {
            List<AiStudioChatRequest.HistoryMessage> shared =
                    threadService.recentHistory(request.getFunctionUnitId(), request.getPhase());
            if (!shared.isEmpty()) {
                request.setHistory(shared);
            }
        } catch (RuntimeException e) {
            log.warn("AI Studio shared history unavailable, using the client history (functionUnitId={}): {}",
                    request.getFunctionUnitId(), e.getMessage());
        }
    }

    /** 线程落库失败不影响本轮结果：前端拿得到回复，只是队友暂时看不到。 */
    private void recordThread(AiStudioChatRequest request, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            log.warn("AI Studio shared thread write failed (functionUnitId={}, phase={}): {}",
                    request.getFunctionUnitId(), request.getPhase(), e.getMessage());
        }
    }

    @Override
    public AiStudioProposalJobResponse startProposal(AiStudioChatRequest request, String userId, String amToken) {
        functionUnitWorkspaceAccessService.assertCanAccess(request.getFunctionUnitId(), WorkspaceAccessAction.MODIFY);
        // 作者身份必须在请求线程上取：作业线程里没有安全上下文
        AiStudioThreadService.Author author = AiStudioThreadComponentImpl.currentAuthor(userId);
        log.info("AI Studio proposal requested: functionUnitId={}, phase={}, userId={}",
                request.getFunctionUnitId(), request.getPhase(), userId);
        useSharedHistory(request);
        // 第一步必须留在请求线程：阶段不支持要立刻 4xx，且上下文序列化依赖请求事务里的 JPA 懒加载
        AiStudioChatService.ProposalDraft draft = aiStudioChatService.prepareProposal(request);
        // 指纹 = 阶段 + 本轮消息：刷新/双击的重复提交复用作业，改了主意的新请求替换旧作业
        // 分隔符用换行：阶段名里不会出现，且指纹会落库（PostgreSQL 文本不接受 NUL）
        String requestKey = request.getPhase() + "\n" + (request.getMessage() != null ? request.getMessage().trim() : "");
        // 模型跑完立刻算预览（会改什么 + Apply 会不会被拒），与提案一起进作业快照；预览失败不影响提案本体
        // 作业成功时由后端把回复写进共享线程：发起人关掉浏览器，结果也不会丢
        AiStudioProposalJobResponse job = aiStudioProposalJobService.submit(
                new AiStudioProposalJobService.JobRequest(request.getFunctionUnitId(), request.getPhase(), userId,
                        author.name(), requestKey, request.getMessage()),
                () -> {
                    AiStudioChatService.StudioChatResult result = aiStudioChatService.runProposal(draft, amToken);
                    if (result.proposal() == null) return result;
                    return result.withPreview(previewer.preview(draft.functionUnitId(), result.proposalScope(),
                            normalizedData(result.proposal())));
                },
                result -> threadService.appendAssistantMessage(request.getFunctionUnitId(), request.getPhase(),
                        result.reply(), result.proposalScope(), result.proposal(), result.preview(), author));
        // 提交成功才记用户消息；重复提交复用作业时由线程服务去重
        recordThread(request, () -> threadService.appendUserMessage(
                request.getFunctionUnitId(), request.getPhase(), request.getMessage(), author));
        return job;
    }

    /** 与 Apply 同款的 convert + normalize：预览与真正写入看到的是同一份数据。 */
    private AiGeneratedData normalizedData(Map<String, Object> proposal) {
        return normalizedData(objectMapper, proposal);
    }

    /** 一键生成（{@link AiStudioOneClickComponentImpl}）的预览也走这一份，别另写一套归一化。 */
    static AiGeneratedData normalizedData(ObjectMapper objectMapper, Map<String, Object> proposal) {
        AiGeneratedData data = objectMapper.convertValue(proposal, AiGeneratedData.class);
        AiGenerationComponentImpl.normalizeTableRelations(data.getTableRelations());
        AiGenerationComponentImpl.normalizeCrossFieldRules(data.getFormDefinitions());
        return data;
    }

    @Override
    public AiStudioProposalJobResponse getProposal(String jobId, String userId) {
        return aiStudioProposalJobService.get(jobId, userId);
    }

    @Override
    public AiStudioProposalJobResponse getActiveProposal(Long functionUnitId, String userId) {
        functionUnitWorkspaceAccessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.VIEW);
        return aiStudioProposalJobService.findActive(functionUnitId, userId).orElse(null);
    }

    @Override
    public AiStudioProposalJobResponse cancelProposal(String jobId, String userId) {
        log.info("AI Studio proposal cancel requested: jobId={}, userId={}", jobId, userId);
        return aiStudioProposalJobService.cancel(jobId, userId);
    }

    @Override
    public AiStudioApplyResponse applyProposal(AiStudioApplyRequest request, String userId) {
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
            AiGeneratedData data = normalizedData(sliced);

            // scoped 提案不带 tableDefinitions（allowedSlices 也会裁掉模型回传的表定义），
            // 表单绑定/关系引用的是库里的表：把本 FU 的表名→字段名交给校验器当兜底目录
            AiValidationResult validationResult = aiValidationService.validate(data,
                    previewer.existingTableFieldsFor(functionUnitId, data));
            if (!validationResult.isValid()) {
                throw new AiValidationFailedException(validationResult.getErrors());
            }
            // 邮件三阶段的引用（连接、表单、主表字段）都在库里而不在提案里，按 FU 再校一遍
            AiValidationResult referenceResult = referenceValidator.validate(functionUnitId, data);
            if (!referenceResult.isValid()) {
                throw new AiValidationFailedException(referenceResult.getErrors());
            }
            if (!referenceResult.getWarnings().isEmpty()) {
                log.info("AI Studio proposal reference warnings: functionUnitId={}, warnings={}",
                        functionUnitId, referenceResult.getWarnings());
            }

            // 撤销快照必须在写入之前拍：要读的是"被改之前"的原样
            String undoToken = undoService.capture(functionUnitId, userId, request.getScope(), data);
            try {
                aiWriteService.applyGeneratedData(functionUnitId, data, request.getScope());
            } catch (RuntimeException e) {
                undoService.discard(undoToken);
                throw e;
            }
            log.info("AI Studio proposal applied: functionUnitId={}, scope={}, userId={}, undoable={}",
                    functionUnitId, request.getScope(), userId, undoToken != null);
            return AiStudioApplyResponse.builder()
                    .undoToken(undoToken)
                    .undoableUntil(undoService.expiryOf(undoToken))
                    .build();
        } finally {
            aiLockService.release(functionUnitId, userId);
        }
    }

    @Override
    public AiStudioApplyResponse undoApply(String undoToken, String userId) {
        Long functionUnitId = undoService.functionUnitOf(undoToken, userId);
        functionUnitWorkspaceAccessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.MODIFY);
        // 与 Apply 共用同一把锁：撤销期间不许别的写入插进来
        aiLockService.tryAcquire(functionUnitId, userId);
        try {
            List<AiStudioApplyResponse.UndoNote> notes = undoService.undo(undoToken, userId);
            log.info("AI Studio proposal undone: functionUnitId={}, userId={}, notes={}",
                    functionUnitId, userId, notes.size());
            return AiStudioApplyResponse.builder().undoNotes(notes).build();
        } finally {
            aiLockService.release(functionUnitId, userId);
        }
    }
}
