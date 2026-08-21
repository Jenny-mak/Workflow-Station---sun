package com.portal.dto;

import java.util.List;
import java.util.Map;

/**
 * One page of relation-table rows plus the column declaration the shared list header renders from.
 * {@code columns} is authoritative — the frontend must not derive headers from row keys
 * (row-level {@code status} rides along for Active/Inactive but is not a data column).
 */
public record RelationTableDataPage(
        List<PortalListColumnMeta> columns,
        List<Map<String, Object>> content,
        int page,
        int size,
        long totalElements) {

    public RelationTableDataPage {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("columns declaration is required");
        }
        columns = List.copyOf(columns);
        content = content == null ? List.of() : List.copyOf(content);
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
