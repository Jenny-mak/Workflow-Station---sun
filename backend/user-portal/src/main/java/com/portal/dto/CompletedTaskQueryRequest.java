package com.portal.dto;

import java.util.List;

/**
 * One page of Completed Tasks: paging plus the shared-header filters, sort and grouping.
 * {@code keyword} / {@code startTime} / {@code endTime} remain so older clients keep working;
 * they AND with {@code filters} and compile to the same SQL predicate.
 */
public record CompletedTaskQueryRequest(
        int page,
        int size,
        List<ListColumnFilter> filters,
        String sortField,
        String sortDirection,
        String groupBy,
        String keyword,
        String startTime,
        String endTime) {

    public CompletedTaskQueryRequest {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("size must be between 1 and 200");
        }
        filters = filters == null ? List.of() : List.copyOf(filters);
        if (sortDirection != null && !"ASC".equalsIgnoreCase(sortDirection)
                && !"DESC".equalsIgnoreCase(sortDirection)) {
            throw new IllegalArgumentException("sortDirection must be ASC or DESC");
        }
        if (sortField != null && sortDirection == null) {
            throw new IllegalArgumentException("sortDirection is required when sortField is set");
        }
    }
}
