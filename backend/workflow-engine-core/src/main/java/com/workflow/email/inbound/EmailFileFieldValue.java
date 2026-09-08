package com.workflow.email.inbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors frontend {@code persistUploadValue}: 0 → {@code ""}; 1 → URL string;
 * 2+ → JSON array of {@code {url,name}}.
 */
public final class EmailFileFieldValue {

    public static final int DEFAULT_MAX_FILES = 10;
    public static final int ABSOLUTE_MAX_FILES = 50;
    public static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EmailFileFieldValue() {
    }

    public static String persist(List<StoredUpload> files, int maxFiles) {
        int cap = Math.min(Math.max(maxFiles, 1), ABSOLUTE_MAX_FILES);
        List<StoredUpload> trimmed = new ArrayList<>();
        if (files != null) {
            for (StoredUpload file : files) {
                if (file == null || file.url() == null || file.url().isBlank()) {
                    continue;
                }
                trimmed.add(file);
                if (trimmed.size() >= cap) {
                    break;
                }
            }
        }
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.size() == 1) {
            return trimmed.get(0).url();
        }
        return toJsonArray(trimmed);
    }

    private static String toJsonArray(List<StoredUpload> files) {
        ArrayNode array = MAPPER.createArrayNode();
        for (StoredUpload file : files) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("url", file.url().trim());
            String name = file.name() == null || file.name().isBlank() ? file.url() : file.name().trim();
            node.put("name", name);
            array.add(node);
        }
        try {
            return MAPPER.writeValueAsString(array);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize FILE field value", e);
        }
    }

    public record StoredUpload(String url, String name) {
    }
}
