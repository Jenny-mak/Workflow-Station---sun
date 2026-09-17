package com.workflow.component;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Shared secret for portal→engine service calls that must query another user's assigned tasks.
 * Browsers never hold this token; {@code GET /api/v1/tasks} stays actor-scoped.
 */
@Component
public class PortalInternalTokenVerifier {

    private final String expectedToken;

    public PortalInternalTokenVerifier(
            @Value("${portal.internal.api-token:${PORTAL_INTERNAL_API_TOKEN:}}") String expectedToken) {
        this.expectedToken = expectedToken;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(expectedToken);
    }

    public boolean matches(String token) {
        if (!isConfigured() || token == null) {
            return false;
        }
        byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = token.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
