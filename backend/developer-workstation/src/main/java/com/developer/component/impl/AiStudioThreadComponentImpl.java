package com.developer.component.impl;

import com.developer.component.AiStudioThreadComponent;
import com.developer.dto.AiStudioThreadImportRequest;
import com.developer.dto.AiStudioThreadMessageDTO;
import com.developer.dto.AiStudioThreadResponse;
import com.developer.entity.AiStudioThreadState;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.security.WorkspaceAccessAction;
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

    public AiStudioThreadComponentImpl(AiStudioThreadService threadService,
                                       FunctionUnitWorkspaceAccessService accessService,
                                       AiStudioThreadEventHub eventHub) {
        this.threadService = threadService;
        this.accessService = accessService;
        this.eventHub = eventHub;
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
    public List<String> saveCompletedPhases(Long functionUnitId, List<String> completedPhases) {
        accessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.MODIFY);
        return threadService.saveCompletedPhases(functionUnitId, completedPhases, currentAuthor(null).userId());
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
