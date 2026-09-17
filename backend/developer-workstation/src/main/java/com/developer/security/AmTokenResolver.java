package com.developer.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 取该用户的 DSP AMToken：{@code X-AM-Token} 头 → AMToken cookie → null。
 * 与 AiGenerationController#resolveAmToken 同语义；token 只在内存流转，不落库、不进日志。
 * 两处都没有时由 {@code AiGatewayClient} 以 {@code AI_GATEWAY_TOKEN_MISSING} 显式失败。
 */
@Component
public class AmTokenResolver {

    /** 与 AiGenerationController 的同名常量保持一致（前端 aiGeneration / aiStudioThread api 透传用）。 */
    public static final String AM_TOKEN_HEADER = "X-AM-Token";

    /** AMToken 的 cookie 名，与 {@code ai-generation.gateway.am-token-name} 对齐。 */
    private final String cookieName;

    public AmTokenResolver(@Value("${ai-generation.gateway.am-token-name:AMToken}") String cookieName) {
        this.cookieName = cookieName;
    }

    public String resolve(HttpServletRequest request) {
        String header = request.getHeader(AM_TOKEN_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookieName.equalsIgnoreCase(cookie.getName())
                        && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return cookie.getValue().trim();
                }
            }
        }
        return null;
    }
}
