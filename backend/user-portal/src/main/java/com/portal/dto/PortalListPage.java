package com.portal.dto;

import java.util.List;

/**
 * One page of a shared-list query: the column declaration the header renders from, the rows of
 * this page, and — when the caller grouped — counts over the whole matching set.
 */
public record PortalListPage<T>(
        List<PortalListColumnMeta> columns,
        List<T> content,
        List<PortalListGroup> groups,
        int page,
        int size,
        long totalElements) {

    public PortalListPage {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("columns declaration is required");
        }
        columns = List.copyOf(columns);
        content = content == null ? List.of() : List.copyOf(content);
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    public int totalPages() {
        return size < 1 ? 0 : (int) Math.ceil((double) totalElements / size);
    }

    public boolean hasNext() {
        return page < totalPages() - 1;
    }

    public boolean hasPrevious() {
        return page > 0;
    }
}
