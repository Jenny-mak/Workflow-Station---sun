package com.workflow.dto.request;

/**
 * Optional list criteria pushed from Portal into Flowable {@code TaskQuery}
 * so filtered/sorted pages need not be full-scanned in the portal.
 *
 * <p>{@code taskNameLike} is the raw fragment (no {@code %}); {@code taskNameLikeMode}
 * tells the engine how to wrap it ({@code contains}/{@code startsWith}/{@code endsWith}).
 */
public record EngineTaskListCriteria(
        String taskNameLike,
        String taskNameExact,
        String taskNameLikeMode,
        Integer priority,
        String sortBy,
        String sortDirection
) {
    public static EngineTaskListCriteria empty() {
        return new EngineTaskListCriteria(null, null, null, null, null, null);
    }

    public boolean hasAny() {
        return (taskNameLike != null && !taskNameLike.isBlank())
                || (taskNameExact != null && !taskNameExact.isBlank())
                || priority != null
                || (sortBy != null && !sortBy.isBlank())
                || (sortDirection != null && !sortDirection.isBlank());
    }
}
