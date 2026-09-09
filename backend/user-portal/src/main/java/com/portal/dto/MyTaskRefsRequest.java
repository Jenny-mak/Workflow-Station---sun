package com.portal.dto;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * The process instances one grid page is showing, asked about in a single call. Rows are looked up
 * by process instance id — the platform's own key — never by a designed business field such as
 * "case number", whose name each function unit chooses for itself.
 */
public record MyTaskRefsRequest(List<String> processInstanceIds) {

    /** Matches the 200-row ceiling shared by the portal list requests. */
    public static final int MAX_IDS = 200;

    public MyTaskRefsRequest {
        List<String> cleaned = processInstanceIds == null ? List.of() : processInstanceIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
        if (cleaned.size() > MAX_IDS) {
            throw new IllegalArgumentException("processInstanceIds must not exceed " + MAX_IDS);
        }
        processInstanceIds = cleaned;
    }
}
