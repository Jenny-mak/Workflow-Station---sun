package com.portal.dto;

import java.util.List;

/**
 * Shared-list query for permission my-requests and approval tabs.
 * {@code scope} selects the visibility/status predicate; filters AND on top.
 */
public record PermissionListQueryRequest(
        int page,
        int size,
        List<ListColumnFilter> filters,
        String sortField,
        String sortDirection,
        String groupBy,
        String scope) {

    public enum Scope {
        MY_PENDING,
        MY_COMPLETED,
        APPROVALS_PENDING,
        APPROVALS_HISTORY
    }

    public PermissionListQueryRequest {
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
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("scope is required");
        }
        Scope.valueOf(scope);
    }

    public Scope scopeEnum() {
        return Scope.valueOf(scope);
    }
}
