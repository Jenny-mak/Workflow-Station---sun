package com.developer.component.impl;

import com.developer.component.AiStudioThreadComponent;
import com.developer.dto.AiStudioThreadImportRequest;
import com.developer.dto.AiStudioThreadMessageDTO;
import com.developer.dto.AiStudioThreadResponse;
import com.developer.entity.AiStudioThreadState;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.security.WorkspaceAccessAction;
import com.developer.service.impl.AiStudioDocumentSyncService;
import com.developer.service.impl.AiStudioThreadEventHub;
import com.developer.service.impl.AiStudioThreadService;
import com.platform.common.dto.UserPrincipal;
import com.platform.security.util.SecurityContextUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;

@Component
public class AiStudioThreadComponentImpl implements AiStudioThreadComponent {

    private final AiStudioThreadService threadService;
    private final FunctionUnitWorkspaceAccessService accessService;
    private final AiStudioThreadEventHub eventHub;
    private final AiStudioDocumentSyncService documentSyncService;

    public AiStudioThreadComponentImpl(AiStudioThreadService threadService,
                                       FunctionUnitWorkspaceAccessService accessService,
                                       AiStudioThreadEventHub eventHub,
                                       AiStudioDocumentSyncService documentSyncService) {
        this.threadService = threadService;
        this.accessService = accessService;
        this.eventHub = eventHub;
        this.documentSyncService = documentSyncService;
    }

    /** 当前请求者：展示名优先用 displayName，其次登录名，最后用户 id。必须在请求线程上取。 */
    static AiStudioThreadService.Author currentAuthor(String fallbackUserId) {
        Optional<UserPrincipal> user = SecurityContextUtils.getCurrentUser();
        String userId = user.map(UserPrincipal::getUserId).orElse(fallbackUserId);
        String name = user.map(u -> firstNonBlank(u.getDisplayName(), u.getUsername())).orElse(null);
        return new AiStudioThreadService.Author(userId, truncate(firstNonBlank(name, userId), 100));
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    @Override
    public AiStudioThreadResponse getThread(Long functionUnitId) {
        accessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.VIEW);
        Optional<AiStudioThreadState> state = threadService.state(functionUnitId);
        return AiStudioThreadResponse.builder()
                .completedPhases(state.map(AiStudioThreadState::getCompletedPhases).orElse(null))
                .updatedBy(state.map(AiStudioThreadState::getUpdatedBy).orElse(null))
                .updatedAt(state.map(AiStudioThreadState::getUpdatedAt).orElse(null))
                .messageCounts(threadService.messageCounts(functionUnitId))
                .canModify(accessService.canAccess(functionUnitId, WorkspaceAccessAction.MODIFY))
                .documentSyncRunning(documentSyncService.isRunning(functionUnitId))
                .build();
    }

    @Override
    public List<AiStudioThreadMessageDTO> getMessages(Long functionUnitId, String phase, Long afterId) {
        accessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.VIEW);
        String viewer = currentAuthor(null).userId();
        return afterId == null
                ? threadService.messages(functionUnitId, phase, viewer)
                : threadService.messagesAfter(functionUnitId, phase, afterId, viewer);
    }

    @Override
    public AiStudioThreadMessageDTO getMessage(Long functionUnitId, Long messageId) {
        accessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.VIEW);
        return threadService.message(functionUnitId, messageId, currentAuthor(null).userId());
    }

    @Override
    public SseEmitter subscribe(Long functionUnitId) {
        accessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.VIEW);
        return eventHub.subscribe(functionUnitId, currentAuthor(null).userId());
    }

    @Override
    public List<String> saveCompletedPhases(Long functionUnitId, List<String> completedPhases, String amToken) {
        accessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.MODIFY);
        AiStudioThreadService.Author author = currentAuthor(null);
        List<String> before = threadService.state(functionUnitId)
                .map(AiStudioThreadState::getCompletedPhases)
                .orElse(List.of());
        List<String> saved = threadService.saveCompletedPhases(functionUnitId, completedPhases, author.userId());
        // 只有新确认的阶段才触发；进度被重置/减少不触发。importThreads 不走这里，批量导入旧进度也不触发。
        List<String> added = saved.stream().filter(phase -> !before.contains(phase)).toList();
        if (!added.isEmpty()) {
            documentSyncService.submit(functionUnitId, added, added.get(added.size() - 1), amToken, author);
        }
        return saved;
    }

    @Override
    public void checkDocuments(Long functionUnitId, String phase, String amToken) {
        accessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.MODIFY);
        documentSyncService.submit(functionUnitId, List.of(), phase, amToken, currentAuthor(null));
    }

    @Override
    public AiStudioThreadMessageDTO markApplied(Long functionUnitId, Long messageId, boolean applied) {
        accessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.MODIFY);
        return threadService.markApplied(functionUnitId, messageId, applied, currentAuthor(null));
    }

    @Override
    public List<String> importThreads(Long functionUnitId, AiStudioThreadImportRequest request) {
        accessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.MODIFY);
        return threadService.importThreads(functionUnitId, request, currentAuthor(null));
    }
}
