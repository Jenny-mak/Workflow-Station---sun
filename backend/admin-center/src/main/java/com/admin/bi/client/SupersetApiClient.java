package com.admin.bi.client;

import com.admin.bi.config.BiProperties;
import com.admin.exception.SupersetApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Superset REST API 客户端
 * 封装 Superset 登录和 Guest Token 获取逻辑
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupersetApiClient {

    private final RestTemplate restTemplate;
    private final BiProperties biProperties;

    /**
     * 调用 Superset /api/v1/security/login 获取 access token
     *
     * @return Superset access token
     * @throws SupersetApiException 登录失败或超时
     */
    public String login() {
        String url = biProperties.getSuperset().getHost() + "/api/v1/security/login";

        Map<String, Object> body = new HashMap<>();
        body.put("username", biProperties.getSuperset().getAdminUsername());
        body.put("password", biProperties.getSuperset().getAdminPassword());
        body.put("provider", "db");
        body.put("refresh", true);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            log.debug("Calling Superset login API: {}", url);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity,
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object accessToken = response.getBody().get("access_token");
                if (accessToken != null) {
                    log.debug("Superset login successful");
                    return accessToken.toString();
                }
            }

            throw new SupersetApiException("Superset login failed: no access_token in response");

        } catch (ResourceAccessException e) {
            log.error("Superset login timeout or connection error: {}", e.getMessage(), e);
            throw new SupersetApiException("Superset API timeout or connection error", e);
        } catch (RestClientException e) {
            log.error("Superset login failed: {}", e.getMessage(), e);
            throw new SupersetApiException("Superset API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 调用 Superset /api/v1/security/csrf_token/ 获取 CSRF token
     *
     * @param accessToken Superset access token
     * @return CSRF token 字符串
     * @throws SupersetApiException API 调用失败
     */
    /**
     * CSRF token + session cookie holder
     */
    private record CsrfResult(String csrfToken, String sessionCookie) {}

    /**
     * 调用 Superset /api/v1/security/csrf_token/ 获取 CSRF token 和 session cookie
     *
     * @param accessToken Superset access token
     * @return CsrfResult containing CSRF token and session cookie
     * @throws SupersetApiException API 调用失败
     */
    private CsrfResult getCsrfTokenAndCookie(String accessToken) {
        String url = biProperties.getSuperset().getHost() + "/api/v1/security/csrf_token/";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            log.debug("Fetching CSRF token from Superset");

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity,
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object csrfToken = response.getBody().get("result");
                if (csrfToken != null) {
                    // Extract session cookie from Set-Cookie header
                    List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
                    String sessionCookie = "";
                    if (cookies != null) {
                        sessionCookie = cookies.stream()
                                .map(c -> c.split(";")[0])  // take only name=value part
                                .reduce((a, b) -> a + "; " + b)
                                .orElse("");
                    }
                    log.debug("CSRF token and session cookie obtained successfully");
                    return new CsrfResult(csrfToken.toString(), sessionCookie);
                }
            }

            throw new SupersetApiException("Failed to obtain CSRF token: no result in response");

        } catch (RestClientException e) {
            log.error("Failed to fetch CSRF token: {}", e.getMessage(), e);
            throw new SupersetApiException("Failed to fetch CSRF token: " + e.getMessage(), e);
        }
    }

    /**
     * 调用 Superset /api/v1/security/guest_token/ 获取 Guest Token
     *
     * @param dashboardEmbedId Dashboard 的 Embed ID (UUID)
     * @param supersetRoleIds  用户对应的 Superset Role ID 列表
     * @return Guest Token 字符串
     * @throws SupersetApiException API 调用失败或超时
     */
    /**
     * 调用 Superset /api/v1/security/guest_token/ 获取 Guest Token。
     * <p>
     * 该接口不接受角色参数：guest 身份固定为 Superset 配置的 GUEST_ROLE_NAME，仅被授权访问
     * {@code resources} 中列出的这一个 dashboard。用户级的角色控制在 admin-center 侧完成
     * （BiDashboardAssignmentService 按 RBAC 映射过滤可见 dashboard）。
     *
     * @param dashboardEmbedId Dashboard 的 Embed ID (UUID)
     * @return Guest Token 字符串
     * @throws SupersetApiException API 调用失败或超时
     */
    public String getGuestToken(String dashboardEmbedId) {
        String accessToken = login();
        CsrfResult csrf = getCsrfTokenAndCookie(accessToken);

        String url = biProperties.getSuperset().getHost() + "/api/v1/security/guest_token/";

        Map<String, Object> user = new HashMap<>();
        user.put("username", "guest");
        user.put("first_name", "Guest");
        user.put("last_name", "User");

        Map<String, Object> resource = new HashMap<>();
        resource.put("type", "dashboard");
        resource.put("id", dashboardEmbedId);

        Map<String, Object> rlsRule = new HashMap<>();
        rlsRule.put("clause", "1=1");

        Map<String, Object> body = new HashMap<>();
        body.put("user", user);
        body.put("resources", List.of(resource));
        body.put("rls", List.of(rlsRule));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        headers.set("X-CSRFToken", csrf.csrfToken());
        headers.set("Referer", biProperties.getSuperset().getHost() + "/");
        // Forward session cookie from CSRF token response
        if (!csrf.sessionCookie().isEmpty()) {
            headers.set(HttpHeaders.COOKIE, csrf.sessionCookie());
        }
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            log.debug("Calling Superset guest token API for dashboard: {}", dashboardEmbedId);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity,
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object token = response.getBody().get("token");
                if (token != null) {
                    log.debug("Guest token obtained successfully for dashboard: {}", dashboardEmbedId);
                    return token.toString();
                }
            }

            throw new SupersetApiException("Failed to obtain guest token: no token in response");

        } catch (ResourceAccessException e) {
            log.error("Superset guest token API timeout or connection error: {}", e.getMessage(), e);
            throw new SupersetApiException("Superset API timeout or connection error", e);
        } catch (RestClientException e) {
            log.error("Superset guest token API failed: {}", e.getMessage(), e);
            throw new SupersetApiException("Superset API call failed: " + e.getMessage(), e);
        }
    }
}
