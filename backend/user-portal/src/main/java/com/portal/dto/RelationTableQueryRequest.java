package com.portal.dto;

import java.util.List;

/**
 * One page request for a Relation Table: paging, toolbar keyword, column filters and sort.
 * Grouping is intentionally rejected — the relation-table endpoint never executes GROUP BY,
 * and {@link com.portal.util.RelationTableColumnSpec} declares every column {@code groupable = false}.
 * Clients that send a non-blank {@code groupBy} get 400 (design §10 positive #9).
 */
public record RelationTableQueryRequest(
        int page,
        int size,
        String search,
        List<ListColumnFilter> filters,
        String sortField,
        String sortDirection,
        String groupBy) {

    public RelationTableQueryRequest {
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
        if (groupBy != null && !groupBy.isBlank()) {
            throw new IllegalArgumentException(
                    "Relation Tables do not support grouping (groupBy must be omitted)");
        }
        groupBy = null;
    }

    /** Convenience for callers that never send groupBy. */
    public static RelationTableQueryRequest of(
            int page,
            int size,
            String search,
            List<ListColumnFilter> filters,
            String sortField,
            String sortDirection) {
        return new RelationTableQueryRequest(page, size, search, filters, sortField, sortDirection, null);
    }
}
