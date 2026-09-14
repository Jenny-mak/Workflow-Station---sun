package com.developer.controller;

import com.developer.component.AiStudioChatComponent;
import com.developer.dto.AiStudioApplyRequest;
import com.developer.dto.AiStudioChatRequest;
import com.developer.dto.AiStudioChatResponse;
import com.developer.dto.AiStudioProposalJobResponse;
import com.developer.security.RequireDeveloperPermission;
import com.platform.common.dto.ApiResponse;
import com.platform.common.i18n.I18nService;
import com.platform.security.util.SecurityContextUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI Studio Copilot 对话入口。
 *
 * <p>模型接入方式与 {@link AiGenerationController} 完全同源：同一把开关
 * {@code ai-generation.enabled}（环境变量 {@code AI_GENERATION_ENABLED}，关掉则本控制器同样 404）、
 * 同一条集团 AI gateway 链路、同一套 AMToken 凭证解析（{@code X-AM-Token} 头 → AMToken cookie，
 * 两处都没有则由 {@code AiGatewayClient} 以 {@code AI_GATEWAY_TOKEN_MISSING} 显式失败）。
 * 单独立控制器而不是往 AiGenerationController 加端点，是为了不动那边被单测直构的类。</p>
 */
@RestController
@RequestMapping("/ai-generation/studio-chat")
@ConditionalOnProperty(prefix = "ai-generation", name = "enabled", havingValue = "true")
@Slf4j
@Tag(name = "AI Studio Copilot", description = "AI Studio phase copilot chat API")
public class AiStudioChatController extends BaseController {

    /** 与 AiGenerationController 的同名常量保持一致（前端 useAiChat / aiGeneration api 透传用）。 */
    private static final String AM_TOKEN_HEADER = "X-AM-Token";

    private final AiStudioChatComponent aiStudioChatComponent;
    private final I18nService i18nService;

    /** AMToken 的 cookie 名，与 {@code ai-generation.gateway.am-token-name} 对齐。 */
    @Value("${ai-generation.gateway.am-token-name:AMToken}")
    private String amTokenCookieName = "AMToken";

    public AiStudioChatController(AiStudioChatComponent aiStudioChatComponent, I18nService i18nService) {
        this.aiStudioChatComponent = aiStudioChatComponent;
        this.i18nService = i18nService;
    }

    @PostMapping
    @Operation(summary = "AI Studio copilot chat (single advisory turn; propose=true is rejected, use /proposals)")
    @RequireDeveloperPermission("FUNCTION_UNIT_VIEW")
    public ResponseEntity<ApiResponse<AiStudioChatResponse>> chat(
            @Valid @RequestBody AiStudioChatRequest request, HttpServletRequest httpRequest) {
        String userId = SecurityContextUtils.getCurrentUserId()
                .orElseThrow(() -> new RuntimeException(i18nService.getMessage("auth.unauthenticated_user")));
        String amToken = resolveAmToken(httpRequest);
        return handleRequest(() -> aiStudioChatComponent.chat(request, userId, amToken));
    }

    @PostMapping("/proposals")
    @Operation(summary = "Start a change-proposal job (async; poll GET /proposals/{jobId})")
    @RequireDeveloperPermission("FUNCTION_UNIT_VIEW")
    public ResponseEntity<ApiResponse<AiStudioProposalJobResponse>> startProposal(
            @Valid @RequestBody AiStudioChatRequest request, HttpServletRequest httpRequest) {
        String userId = SecurityContextUtils.getCurrentUserId()
                .orElseThrow(() -> new RuntimeException(i18nService.getMessage("auth.unauthenticated_user")));
        String amToken = resolveAmToken(httpRequest);
        request.setPropose(true);
        return handleRequest(() -> aiStudioChatComponent.startProposal(request, userId, amToken));
    }

    @GetMapping("/proposals/{jobId}")
    @Operation(summary = "Poll a change-proposal job")
    @RequireDeveloperPermission("FUNCTION_UNIT_VIEW")
    public ResponseEntity<ApiResponse<AiStudioProposalJobResponse>> getProposal(@PathVariable String jobId) {
        String userId = SecurityContextUtils.getCurrentUserId()
                .orElseThrow(() -> new RuntimeException(i18nService.getMessage("auth.unauthenticated_user")));
        return handleRequest(() -> aiStudioChatComponent.getProposal(jobId, userId));
    }

    @PostMapping("/apply")
    @Operation(summary = "Apply a copilot change proposal to the function unit design")
    @RequireDeveloperPermission("FUNCTION_UNIT_UPDATE")
    public ResponseEntity<ApiResponse<Void>> applyProposal(@Valid @RequestBody AiStudioApplyRequest request) {
        String userId = SecurityContextUtils.getCurrentUserId()
                .orElseThrow(() -> new RuntimeException(i18nService.getMessage("auth.unauthenticated_user")));
        return handleRequest(() -> {
            aiStudioChatComponent.applyProposal(request, userId);
            return null;
        });
    }

    /**
     * 取该用户的 DSP AMToken：{@code X-AM-Token} 头 → AMToken cookie → null。
     * 与 AiGenerationController#resolveAmToken 逐字同语义；token 只在内存流转，不落库、不进日志。
     */
    private String resolveAmToken(HttpServletRequest httpRequest) {
        String header = httpRequest.getHeader(AM_TOKEN_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        Cookie[] cookies = httpRequest.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (amTokenCookieName.equalsIgnoreCase(cookie.getName())
                        && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return cookie.getValue().trim();
                }
            }
        }
        return null;
    }
}
