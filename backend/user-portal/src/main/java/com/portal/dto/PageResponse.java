package com.portal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 分页响应DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    /** 数据列表 */
    private List<T> content;

    /** 当前页码 */
    private int page;

    /** 每页大小 */
    private int size;

    /** 总记录数 */
    private long totalElements;

    /** 总页数 */
    private int totalPages;

    /** 是否有下一页 */
    private boolean hasNext;

    /** 是否有上一页 */
    private boolean hasPrevious;

    /**
     * Optional full filtered-set group sizes when {@code groupBy} was requested (label → count).
     * Omitted from JSON when null.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Long> groupCounts;

    /**
     * When true, {@link #totalElements} may be incomplete (scan/fetch cap hit, or post-filter
     * removed rows from an engine window page). Clients should not treat the total as exact.
     * Omitted from JSON when null/false.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean truncated;

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        return PageResponse.<T>builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .hasNext(page < totalPages - 1)
                .hasPrevious(page > 0)
                .build();
    }

    public static <T> PageResponse<T> of(
            List<T> content, int page, int size, long totalElements, boolean truncated) {
        PageResponse<T> response = of(content, page, size, totalElements);
        if (truncated) {
            response.setTruncated(Boolean.TRUE);
        }
        return response;
    }

    public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> page) {
        return PageResponse.<T>builder()
                .content(page.getContent())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .build();
    }
}
