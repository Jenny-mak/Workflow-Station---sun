package com.workflow.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PortalInternalTokenVerifier")
class PortalInternalTokenVerifierTest {

    @Test
    void blankConfiguredTokenRejectsEveryCaller() {
        PortalInternalTokenVerifier verifier = new PortalInternalTokenVerifier("  ");
        assertThat(verifier.isConfigured()).isFalse();
        assertThat(verifier.matches("any")).isFalse();
        assertThat(verifier.matches(null)).isFalse();
    }

    @Test
    void matchingTokenIsAccepted() {
        PortalInternalTokenVerifier verifier = new PortalInternalTokenVerifier("dev-portal-internal-token-change-me");
        assertThat(verifier.isConfigured()).isTrue();
        assertThat(verifier.matches("dev-portal-internal-token-change-me")).isTrue();
    }

    @Test
    void wrongOrMissingTokenIsRejected() {
        PortalInternalTokenVerifier verifier = new PortalInternalTokenVerifier("secret");
        assertThat(verifier.matches("other")).isFalse();
        assertThat(verifier.matches("")).isFalse();
        assertThat(verifier.matches(null)).isFalse();
    }
}
