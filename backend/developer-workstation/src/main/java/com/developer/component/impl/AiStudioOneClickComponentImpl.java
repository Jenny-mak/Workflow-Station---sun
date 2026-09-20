package com.developer.component.impl;

import com.developer.component.AiStudioChatComponent;
import com.developer.component.AiStudioOneClickComponent;
import com.developer.dto.AiStudioApplyRequest;
import com.developer.dto.AiStudioChatRequest;
import com.developer.dto.AiStudioOneClickRequest;
import com.developer.dto.AiStudioProposalJobResponse;
import com.developer.dto.AiStudioProposalPreview;
import com.developer.entity.AiDocument;
import com.developer.entity.AiStudioMessage;
import com.developer.enums.AiDocumentType;
import com.developer.enums.AiStudioPhase;
import com.developer.exception.AiGenerationException;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.security.WorkspaceAccessAction;
import com.developer.service.AiStudioChatService;
import com.developer.service.AiStudioChatService.StudioChatResult;
import com.developer.service.AiStudioProposalJobService;
import com.developer.service.impl.AiStudioChatServiceImpl;
import com.developer.service.impl.AiStudioProposalPreviewer;
import com.developer.service.impl.AiStudioThreadService;
import com.developer.service.impl.FunctionUnitDocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一键生成编排。模型调用、预览、作业生命周期全部复用 Copilot 提案那一套；多出来的只有两步：
 * 预校验无 ERROR 时在作业落定前自动 Apply（{@link AiStudioChatComponent#applyProposal} 同一条写入链路：
 * 同一把锁、同两道校验），以及把本次的需求描述记进 Requirements 文档。
 *
 * <p>自动 Apply 失败不让整个作业失败：模型跑了十分钟的结果仍作为未应用的卡片落进线程，
 * 卡上带一条 WARNING 说明原因，用户可以手动 Apply。</p>
 */
@Slf4j
@Component
public class AiStudioOneClickComponentImpl implements AiStudioOneClickComponent {

    /** 结果卡片与提问落在第一个阶段的线程里：一键生成之后用户就是从这里开始逐阶段复核 */
    static final String THREAD_PHASE = AiStudioPhase.PROCESS_DESIGN.name();

    /** Requirements 文档版本来源代码，前端 utils/functionUnitDocumentSource.ts 翻译 */
    public static final String SUMMARY_ONE_CLICK = "AI_ONE_CLICK";

    static final String DEFAULT_MESSAGE = "Generate the function unit from the Requirements document.";
    static final String AUTO_APPLY_FAILED = "ONE_CLICK_AUTO_APPLY_FAILED";

    private final AiStudioChatService chatService;
    private final AiStudioChatComponent chatComponent;
    private final AiStudioProposalJobService jobService;
    private final AiStudioProposalPreviewer previewer;
    private final AiStudioThreadService threadService;
    private final FunctionUnitDocumentService documentService;
    private final FunctionUnitWorkspaceAccessService accessService;
    private final ObjectMapper objectMapper;

    public AiStudioOneClickComponentImpl(AiStudioChatService chatService,
                                         AiStudioChatComponent chatComponent,
                                         AiStudioProposalJobService jobService,
                                         AiStudioProposalPreviewer previewer,
                                         AiStudioThreadService threadService,
                                         FunctionUnitDocumentService documentService,
                                         FunctionUnitWorkspaceAccessService accessService,
                                         ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.chatComponent = chatComponent;
        this.jobService = jobService;
        this.previewer = previewer;
        this.threadService = threadService;
        this.documentService = documentService;
        this.accessService = accessService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiStudioProposalJobResponse start(AiStudioOneClickRequest request, String userId, String amToken) {
        Long functionUnitId = request.getFunctionUnitId();
        accessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.MODIFY);

        String requirements = request.getRequirements() != null ? request.getRequirements().trim() : "";
        if (requirements.isEmpty() && documentService.latest(functionUnitId, AiDocumentType.REQUIREMENTS).isEmpty()) {
            throw new AiGenerationException("AI_STUDIO_ONE_CLICK_NO_INPUT",
                    "One-click generation needs a requirements description or a Requirements document");
        }

        // 作者与安全上下文必须在请求线程上取：作业线程里两者都没有，而 Apply 要做权限检查、JPA 要记审计字段
        AiStudioThreadService.Author author = AiStudioThreadComponentImpl.currentAuthor(userId);
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(SecurityContextHolder.getContext().getAuthentication());

        AiStudioChatRequest chatRequest = new AiStudioChatRequest();
        chatRequest.setFunctionUnitId(functionUnitId);
        chatRequest.setPhase(THREAD_PHASE);
        chatRequest.setMessage(requirements.isEmpty() ? DEFAULT_MESSAGE : requirements);
        chatRequest.setPropose(true);
        try {
            // "让 AI 修正"会带着上一轮的结果再来一次：线程历史里有那张卡
            chatRequest.setHistory(threadService.recentHistory(functionUnitId, THREAD_PHASE));
        } catch (RuntimeException e) {
            log.warn("AI Studio one-click: shared history unavailable (functionUnitId={}): {}",
                    functionUnitId, e.getMessage());
        }
        // 上下文序列化依赖请求事务里的 JPA 懒加载，留在请求线程
        AiStudioChatService.ProposalDraft draft = chatService.prepareOneClick(chatRequest);
        log.info("AI Studio one-click generation requested: functionUnitId={}, userId={}, requirementChars={}, mode={}",
                functionUnitId, userId, requirements.length(), draft.mode());

        AtomicBoolean applied = new AtomicBoolean();
        AiStudioProposalJobResponse job = jobService.submit(
                new AiStudioProposalJobService.JobRequest(functionUnitId, THREAD_PHASE, userId, author.name(),
                        "ONE_CLICK\n" + requirements, chatRequest.getMessage()),
                () -> {
                    StudioChatResult result = chatService.runProposal(draft, amToken);
                    if (result.proposal() == null) return result;
                    return result.withPreview(previewer.preview(functionUnitId, result.proposalScope(),
                            AiStudioChatComponentImpl.normalizedData(objectMapper, result.proposal())));
                },
                result -> {
                    if (!canAutoApply(result)) {
                        log.info("AI Studio one-click: not auto-applying (functionUnitId={}, hasProposal={}, checked={})",
                                functionUnitId, result.proposal() != null,
                                result.preview() != null && result.preview().isChecked());
                        return result;
                    }
                    Optional<String> failure = tryApply(result, functionUnitId, userId, securityContext);
                    if (failure.isPresent()) {
                        return result.withPreview(withAutoApplyWarning(result.preview(), failure.get()));
                    }
                    applied.set(true);
                    if (!request.isFollowUp()) {
                        recordRequirements(functionUnitId, requirements, userId, securityContext);
                    }
                    return result;
                },
                result -> {
                    AiStudioMessage message = threadService.appendAssistantMessage(functionUnitId, THREAD_PHASE,
                            result.reply(), result.proposalScope(), result.proposal(), result.preview(), author);
                    if (applied.get()) {
                        threadService.markApplied(functionUnitId, message.getId(), true, author);
                    }
                });
        try {
            threadService.appendUserMessage(functionUnitId, THREAD_PHASE, chatRequest.getMessage(), author);
        } catch (RuntimeException e) {
            log.warn("AI Studio one-click: shared thread write failed (functionUnitId={}): {}",
                    functionUnitId, e.getMessage());
        }
        return job;
    }

    /** 有数据、预览跑成功且没有 ERROR 才自动写入；预览没跑成（checked=false）交给用户在卡片上手动 Apply。 */
    static boolean canAutoApply(StudioChatResult result) {
        AiStudioProposalPreview preview = result.preview();
        return result.proposal() != null && preview != null && preview.isChecked()
                && preview.getIssues().stream().noneMatch(i -> i.getSeverity() == AiStudioProposalPreview.Severity.ERROR);
    }

    /** 写入成功返回空；失败返回原因（不抛出：模型结果要留给用户手动 Apply）。 */
    private Optional<String> tryApply(StudioChatResult result, Long functionUnitId, String userId,
                                      SecurityContext securityContext) {
        AiStudioApplyRequest apply = new AiStudioApplyRequest();
        apply.setFunctionUnitId(functionUnitId);
        apply.setScope(AiStudioChatServiceImpl.SCOPE_ALL);
        apply.setGeneratedData(result.proposal());
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(securityContext);
        try {
            chatComponent.applyProposal(apply, userId);
            log.info("AI Studio one-click: design applied (functionUnitId={}, userId={})", functionUnitId, userId);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("AI Studio one-click: auto-apply failed, leaving the proposal for a manual apply "
                    + "(functionUnitId={}): {}", functionUnitId, e.getMessage(), e);
            return Optional.of(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    /** 预览的副本 + 一条 WARNING（不是 ERROR：ERROR 会让卡片禁用 Apply，而这里正需要用户手动再试）。 */
    private AiStudioProposalPreview withAutoApplyWarning(AiStudioProposalPreview preview, String reason) {
        AiStudioProposalPreview copy = objectMapper.convertValue(preview, AiStudioProposalPreview.class);
        copy.getIssues().add(AiStudioProposalPreview.Issue.builder()
                .severity(AiStudioProposalPreview.Severity.WARNING)
                .errorType(AUTO_APPLY_FAILED)
                .fieldPath("")
                .description("The design was generated but could not be applied automatically: " + reason)
                .build());
        return copy;
    }

    /**
     * 把本次的需求描述记进 Requirements 文档：没有文档时它就是第一版，已有文档时追加一节（旧内容一字不动）。
     * 失败只告警——设计已经写进去了，文档可以事后在 Settings 里补。
     */
    private void recordRequirements(Long functionUnitId, String requirements, String userId,
                                    SecurityContext securityContext) {
        if (requirements.isEmpty()) return;
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(securityContext);
        try {
            Optional<AiDocument> latest = documentService.latest(functionUnitId, AiDocumentType.REQUIREMENTS);
            String content = latest
                    .map(d -> d.getContent().stripTrailing() + "\n\n## Additional requirements (one-click generation, "
                            + LocalDate.now() + ")\n\n" + requirements + "\n")
                    .orElse(requirements + "\n");
            documentService.append(functionUnitId, AiDocumentType.REQUIREMENTS, content,
                    latest.map(AiDocument::getVersion).orElse(0), SUMMARY_ONE_CLICK, userId);
        } catch (RuntimeException e) {
            log.warn("AI Studio one-click: requirements not recorded in the Requirements document "
                    + "(functionUnitId={}): {}", functionUnitId, e.getMessage());
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
