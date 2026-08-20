package com.portal.dto;

import java.util.List;

/**
 * One page of My Requests. {@code status} is the existing tab (RUNNING / COMPLETED / …);
 * it ANDs with the shared-header filters so a tab and a column filter never disagree.
 */
public record MyApplicationQueryRequest(
        int page,
        int size,
        String status,
        List<ListColumnFilter> filters,
        String sortField,
        String sortDirection,
        String groupBy) {

    public MyApplicationQueryRequest {
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
