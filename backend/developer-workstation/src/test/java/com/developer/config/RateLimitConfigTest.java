package com.developer.config;

import com.platform.common.i18n.I18nService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RateLimitConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(I18nService.class, () -> mock(I18nService.class))
            .withUserConfiguration(RateLimitConfig.class);

    /**
     * 读真实的 application.yml：rate-limit 曾被缩进到 security: 下，过滤器一直静默用 60 的默认值。
     */
    @Test
    @DisplayName("application.yml exposes top-level rate-limit keys backed by DW_RATE_LIMIT_* env vars")
    void applicationYmlBindsTopLevelKeys() throws Exception {
        List<PropertySource<?>> yml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        StandardEnvironment defaults = environmentOf(yml, Map.of());
        assertThat(defaults.getProperty("rate-limit.enabled")).isEqualTo("false");
        assertThat(defaults.getProperty("rate-limit.requests-per-minute")).isEqualTo("600");
        assertThat(defaults.containsProperty("security.rate-limit.requests-per-minute")).isFalse();

        StandardEnvironment overridden = environmentOf(yml,
                Map.of("DW_RATE_LIMIT_ENABLED", "true", "DW_RATE_LIMIT_PER_MINUTE", "250"));
        assertThat(overridden.getProperty("rate-limit.enabled")).isEqualTo("true");
        assertThat(overridden.getProperty("rate-limit.requests-per-minute")).isEqualTo("250");
    }

    @Test
    @DisplayName("filter is not registered unless rate-limit.enabled=true")
    void disabledByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(RateLimitConfig.class));
        runner.withPropertyValues("rate-limit.enabled=false", "rate-limit.requests-per-minute=1")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(RateLimitConfig.class));
    }

    @Test
    @DisplayName("enabled filter uses the configured limit and ignores X-Forwarded-For")
    void enabledFilterEnforcesConfiguredLimitPerRemoteAddr() {
        runner.withPropertyValues("rate-limit.enabled=true", "rate-limit.requests-per-minute=3")
                .run(ctx -> {
                    RateLimitConfig filter = ctx.getBean(RateLimitConfig.class);
                    assertThat(filter.getRequestsPerMinute()).isEqualTo(3);

                    for (int i = 0; i < 3; i++) {
                        assertThat(call(filter, "10.0.0.1", "203.0.113." + i)).isEqualTo(200);
                    }
                    // 换 X-Forwarded-For 不能换来新桶
                    assertThat(call(filter, "10.0.0.1", "198.51.100.99")).isEqualTo(429);
                    // 其他来源地址有独立的桶
                    assertThat(call(filter, "10.0.0.2", null)).isEqualTo(200);
                });
    }

    private static StandardEnvironment environmentOf(List<PropertySource<?>> yml, Map<String, Object> env) {
        // 去掉真实的系统环境/属性，避免本机 DW_RATE_LIMIT_* 干扰断言
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("fakeEnv", env));
        yml.forEach(environment.getPropertySources()::addLast);
        return environment;
    }

    private static int call(RateLimitConfig filter, String remoteAddr, String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/function-units");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
