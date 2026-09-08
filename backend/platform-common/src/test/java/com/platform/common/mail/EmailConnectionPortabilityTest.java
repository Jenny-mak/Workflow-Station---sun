package com.platform.common.mail;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmailConnectionPortabilityTest {

    @Test
    void readEncryptedCredential_prefersNewKey() {
        Map<String, Object> data = Map.of(
                "credentialEncrypted", "enc-new",
                "passwordEncrypted", "enc-legacy");

        assertEquals("enc-new", EmailConnectionPortability.readEncryptedCredential(data));
    }

    @Test
    void readEncryptedCredential_fallsBackWhenNewKeyBlank() {
        Map<String, Object> data = new HashMap<>();
        data.put("credentialEncrypted", null);
        data.put("passwordEncrypted", "enc-legacy");

        assertEquals("enc-legacy", EmailConnectionPortability.readEncryptedCredential(data));
    }

    @Test
    void readEncryptedCredential_legacyOnly() {
        assertEquals("enc-legacy", EmailConnectionPortability.readEncryptedCredential(
                Map.of("passwordEncrypted", "enc-legacy")));
    }
}
