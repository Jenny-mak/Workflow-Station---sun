package com.developer.controller;

import com.developer.component.HermesMasterChatComponent;
import com.developer.dto.HermesMasterChatRequest;
import com.developer.dto.HermesMasterChatResponse;
import com.developer.security.AmTokenResolver;
import com.developer.security.RequireDeveloperPermission;
import com.platform.common.dto.ApiResponse;
import com.platform.common.i18n.I18nService;
import com.platform.security.util.SecurityContextUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hermes Master（HM）对话入口：常驻 DW 每个页面的助手。
 *
 * <p>模型接入方式与 {@link AiStudioChatController} 同源：同一把开关 {@code ai-generation.enabled}、
 * 同一条集团 AI gateway 链路、同一套 AMToken 凭证解析。</p>
 */
@RestController
@RequestMapping("/ai-generation/hermes-master")
@ConditionalOnProperty(prefix = "ai-generation", name = "enabled", havingValue = "true")
@Slf4j
@Tag(name = "Hermes Master", description = "DW-wide assistant chat API")
public class HermesMasterChatController extends BaseController {

    private final HermesMasterChatComponent hermesMasterChatComponent;
    private final I18nService i18nService;
    private final AmTokenResolver amTokenResolver;

    public HermesMasterChatController(HermesMasterChatComponent hermesMasterChatComponent, I18nService i18nService,
                                      AmTokenResolver amTokenResolver) {
        this.hermesMasterChatComponent = hermesMasterChatComponent;
        this.i18nService = i18nService;
        this.amTokenResolver = amTokenResolver;
    }

    @PostMapping("/chat")
    @Operation(summary = "Hermes Master chat (single advisory turn, nothing is persisted)")
    @RequireDeveloperPermission("FUNCTION_UNIT_VIEW")
    public ResponseEntity<ApiResponse<HermesMasterChatResponse>> chat(
            @Valid @RequestBody HermesMasterChatRequest request, HttpServletRequest httpRequest) {
        String userId = SecurityContextUtils.getCurrentUserId()
                .orElseThrow(() -> new RuntimeException(i18nService.getMessage("auth.unauthenticated_user")));
        String amToken = amTokenResolver.resolve(httpRequest);
        return handleRequest(() -> hermesMasterChatComponent.chat(request, userId, amToken));
    }
}
