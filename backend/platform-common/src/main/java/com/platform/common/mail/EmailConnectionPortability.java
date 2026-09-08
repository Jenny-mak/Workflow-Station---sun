package com.platform.common.mail;

import com.platform.common.util.StringUtils;

import java.util.Map;

/**
 * Shared helpers for Email Connection export/import/sync payloads.
 */
public final class EmailConnectionPortability {

    private EmailConnectionPortability() {
    }

    /**
     * Read AES ciphertext from connection JSON. Prefers {@code credentialEncrypted}; when that key
     * is absent or blank, falls back to legacy {@code passwordEncrypted} from pre-rename packages.
     */
    public static String readEncryptedCredential(Map<String, Object> connectionData) {
        if (connectionData == null) {
            return null;
        }
        Object credential = connectionData.get("credentialEncrypted");
        if (credential instanceof String credentialText && StringUtils.isNotBlank(credentialText)) {
            return credentialText;
        }
        Object legacy = connectionData.get("passwordEncrypted");
        return legacy instanceof String legacyText ? legacyText : null;
    }
}
