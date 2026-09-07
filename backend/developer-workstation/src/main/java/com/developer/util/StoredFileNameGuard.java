package com.developer.util;

import com.developer.exception.DeveloperBusinessException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Rejects path-traversal tokens in upload stored names (same rule as FileUploadController).
 */
public final class StoredFileNameGuard {

    private StoredFileNameGuard() {
    }

    public static String requireSafe(String storedName) {
        if (storedName == null || storedName.isBlank()) {
            throw new DeveloperBusinessException("INVALID_STORED_NAME", "Stored file name is required");
        }
        String trimmed = storedName.trim();
        if (trimmed.length() > 150 || trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            throw new DeveloperBusinessException("INVALID_STORED_NAME", "Invalid stored file name");
        }
        return trimmed;
    }

    public static List<String> requireSafeDistinct(List<String> storedNames) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String name : storedNames) {
            unique.add(requireSafe(name));
        }
        return new ArrayList<>(unique);
    }
}
