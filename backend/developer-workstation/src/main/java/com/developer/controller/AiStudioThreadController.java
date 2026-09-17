package com.developer.controller;

import com.developer.component.AiStudioThreadComponent;
import com.developer.dto.AiStudioThreadImportRequest;
import com.developer.dto.AiStudioThreadMessageDTO;
import com.developer.dto.AiStudioThreadResponse;
import com.developer.enums.AiStudioPhase;
import com.developer.security.AmTokenResolver;
import com.developer.security.RequireDeveloperPermission;
import com.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI Studio 共享线程与进度：同一功能单元的成员看到同一份 Copilot 讨论与已确认阶段。
 *
 * <p>对话消息只由 {@link AiStudioChatController} 那一侧的后端逻辑写入；这里只读线程，
 * 并接受两类状态变更（提案卡已应用、已确认阶段）与首次迁移。与 Copilot 同一把开关。</p>
 */
@RestController
@RequestMapping("/ai-generation/studio-thread/{functionUnitId}")
@ConditionalOnProperty(prefix = "ai-generation", name = "enabled", havingValue = "true")
@Tag(name = "AI Studio Thread", description = "Shared AI Studio copilot threads and progress")
public class AiStudioThreadController extends BaseController {

    private final AiStudioThreadComponent threadComponent;
    private final AmTokenResolver amTokenResolver;

    public AiStudioThreadController(AiStudioThreadComponent threadComponent, AmTokenResolver amTokenResolver) {
        this.threadComponent = threadComponent;
        this.amTokenResolver = amTokenResolver;
    }

    @GetMapping
    @Operation(summary = "Shared progress and per-phase message counts")
    @RequireDeveloperPermission("FUNCTION_UNIT_VIEW")
    public ResponseEntity<ApiResponse<AiStudioThreadResponse>> getThread(@PathVariable Long functionUnitId) {
        return handleRequest(() -> threadComponent.getThread(functionUnitId));
    }

    @GetMapping("/messages")
    @Operation(summary = "Messages of one phase thread (oldest first, at most 50); afterId returns only newer ones")
    @RequireDeveloperPermission("FUNCTION_UNIT_VIEW")
    public ResponseEntity<ApiResponse<List<AiStudioThreadMessageDTO>>> getMessages(
            @PathVariable Long functionUnitId, @RequestParam String phase,
            @RequestParam(required = false) Long afterId) {
        return handleRequest(() -> threadComponent.getMessages(functionUnitId, phase, afterId));
    }

    @GetMapping("/messages/{messageId}")
    @Operation(summary = "One thread message (refresh after its applied state changed)")
    @RequireDeveloperPermission("FUNCTION_UNIT_VIEW")
    public ResponseEntity<ApiResponse<AiStudioThreadMessageDTO>> getMessage(
            @PathVariable Long functionUnitId, @PathVariable Long messageId) {
        return handleRequest(() -> threadComponent.getMessage(functionUnitId, messageId));
    }

    /**
     * 线程变化推送（SSE）。事件只带 id，客户端按 id 增量拉取；连接到期后客户端重连。
     * 越权在建立流之前抛出，由 WorkspaceExceptionHandler 映射为 403。
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Server-sent change notifications for this function unit's AI Studio threads")
    @RequireDeveloperPermission("FUNCTION_UNIT_VIEW")
    public SseEmitter events(@PathVariable Long functionUnitId) {
        return threadComponent.subscribe(functionUnitId);
    }

    @PutMapping("/completed-phases")
    @Operation(summary = "Replace the confirmed phases; newly confirmed phases start a background document update")
    @RequireDeveloperPermission("FUNCTION_UNIT_UPDATE")
    public ResponseEntity<ApiResponse<List<String>>> saveCompletedPhases(
            @PathVariable Long functionUnitId, @Valid @RequestBody CompletedPhasesRequest request,
            HttpServletRequest httpRequest) {
        String amToken = amTokenResolver.resolve(httpRequest);
        return handleRequest(() ->
                threadComponent.saveCompletedPhases(functionUnitId, request.getCompletedPhases(), amToken));
    }

    @PostMapping("/documents/check")
    @Operation(summary = "Check both documents against the whole design now (background)")
    @RequireDeveloperPermission("FUNCTION_UNIT_UPDATE")
    public ResponseEntity<ApiResponse<Void>> checkDocuments(
            @PathVariable Long functionUnitId, @Valid @RequestBody CheckDocumentsRequest request,
            HttpServletRequest httpRequest) {
        String amToken = amTokenResolver.resolve(httpRequest);
        return handleRequest(() -> {
            threadComponent.checkDocuments(functionUnitId, request.getPhase(), amToken);
            return null;
        });
    }

    @PatchMapping("/messages/{messageId}/applied")
    @Operation(summary = "Mark a proposal message as applied (or revert after undo)")
    @RequireDeveloperPermission("FUNCTION_UNIT_UPDATE")
    public ResponseEntity<ApiResponse<AiStudioThreadMessageDTO>> markApplied(
            @PathVariable Long functionUnitId, @PathVariable Long messageId,
            @Valid @RequestBody AppliedRequest request) {
        return handleRequest(() -> threadComponent.markApplied(functionUnitId, messageId, request.getApplied()));
    }

    @PostMapping("/import")
    @Operation(summary = "One-time import of browser-local threads into phases that are still empty")
    @RequireDeveloperPermission("FUNCTION_UNIT_UPDATE")
    public ResponseEntity<ApiResponse<List<String>>> importThreads(
            @PathVariable Long functionUnitId, @Valid @RequestBody AiStudioThreadImportRequest request) {
        return handleRequest(() -> threadComponent.importThreads(functionUnitId, request));
    }

    @Data
    public static class CompletedPhasesRequest {
        @NotNull
        @Size(max = 11)
        private List<String> completedPhases;
    }

    @Data
    public static class CheckDocumentsRequest {
        /** 结果消息写到哪个阶段的线程（发起人当前所在阶段） */
        @NotBlank
        @Pattern(regexp = AiStudioPhase.KEY_PATTERN)
        private String phase;
    }

    @Data
    public static class AppliedRequest {
        @NotNull
        private Boolean applied;
    }
}
