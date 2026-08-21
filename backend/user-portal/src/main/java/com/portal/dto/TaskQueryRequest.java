package com.portal.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务查询请求DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskQueryRequest {

    /** 用户ID */
    private String userId;

    /** 分配类型筛选：USER, VIRTUAL_GROUP, DEPT_ROLE, DELEGATED */
    private List<String> assignmentTypes;

    /** 优先级筛选 */
    private List<String> priorities;

    /** 流程类型筛选 */
    private List<String> processTypes;

    /** 任务状态筛选 */
    private List<String> statuses;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 是否包含逾期任务 */
    private Boolean includeOverdue;

    /** 搜索关键词 */
    private String keyword;

    /** Shared-list column filters (AND). */
    private List<ListColumnFilter> filters;

    /** Shared-list group-by field (forces portal fullScan). */
    private String groupBy;

    /** 排序字段 */
    private String sortBy;

    /** 排序方向 */
    private String sortDirection;

    /** 页码 */
    @Builder.Default
    private Integer page = 0;

    /** 每页大小 */
    @Builder.Default
    private Integer size = 20;
}
