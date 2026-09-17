package com.developer.config;

import com.platform.common.i18n.I18nService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API限流过滤器（进程内兜底，默认关闭）。
 *
 * <p>平台限流的唯一权威是 Kong（Redis 共享计数，全局 600/分钟 + 登录 10/分钟），DW 的所有流量都经 Kong 进入。
 * 本过滤器仅供「DW 不经 Kong 直接暴露」的部署打开：{@code rate-limit.enabled=true}
 * （env {@code DW_RATE_LIMIT_ENABLED}），额度 {@code rate-limit.requests-per-minute}
 * （env {@code DW_RATE_LIMIT_PER_MINUTE}）。
 *
 * <p>按 {@code request.getRemoteAddr()} 分桶，不读 X-Forwarded-For：该头首段由客户端自填，
 * 每次换值即可拿到新桶。若在可信代理后启用，请配 {@code server.forward-headers-strategy=native}
 * 让 Tomcat 只按可信代理解析真实 IP。桶只存在本实例内存中，多副本时额度按副本数放大。
 */
@Component
@ConditionalOnProperty(prefix = "rate-limit", name = "enabled", havingValue = "true")
public class RateLimitConfig implements Filter {

    @Value("${rate-limit.requests-per-minute}")
    private int requestsPerMinute;

    @Autowired
    private I18nService i18nService;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        Bucket bucket = buckets.computeIfAbsent(httpRequest.getRemoteAddr(), this::createBucket);

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            httpResponse.setStatus(429);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"Too many requests\",\"message\":\"" + i18nService.getMessage("rate_limit.exceeded") + "\"}");
        }
    }

    int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    private Bucket createBucket(String clientId) {
        Bandwidth limit = Bandwidth.classic(requestsPerMinute,
                Refill.greedy(requestsPerMinute, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }
}
