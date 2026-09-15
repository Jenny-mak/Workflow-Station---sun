package com.portal.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Dashboard概览数据DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardOverview {

    /** 任务概览 */
    private TaskOverview taskOverview;

    /** 流程概览 */
    private ProcessOverview processOverview;

    /** 个人绩效 */
    private PerformanceOverview performanceOverview;

    /** 最近任务列表 */
    private List<TaskInfo> recentTasks;

    /** 最近流程列表 */
    private List<ProcessInfo> recentProcesses;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskOverview {
        /** 待办任务数 */
        private Long pendingCount;
        /** 当前用户可认领的任务数 */
        private Long claimableCount;
        /** 已直接分配或由当前用户认领的待办数 */
        private Long todoCount;
        /** 逾期任务数 */
        private Long overdueCount;
        /** 今日完成数 */
        private Long completedTodayCount;
        /** 平均处理时长（小时） */
        private Double avgProcessingHours;
        /** 紧急任务数 */
        private Long urgentCount;
        /** 高优先级任务数 */
        private Long highPriorityCount;
        /** 团队待办任务数 */
        private Long teamPendingCount;
        /** 团队逾期任务数 */
        private Long teamOverdueCount;
        /** 团队今日完成数 */
        private Long teamCompletedTodayCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessOverview {
        /** 发起的流程数 */
        private Long initiatedCount;
        /** 进行中流程数 */
        private Long inProgressCount;
        /** 本月完成数 */
        private Long completedThisMonthCount;
        /** 草稿箱数量（portal 侧 up_process_drafts，与引擎统计无关） */
        private Long draftCount;
        /** 审批通过率 */
        private Double approvalRate;
        /** 流程类型分布 */
        private Map<String, Long> typeDistribution;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceOverview {
        /** 效率评分 */
        private Double efficiencyScore;
        /** 质量评分 */
        private Double qualityScore;
        /** 协作评分 */
        private Double collaborationScore;
        /** 月度排名 */
        private Integer monthlyRank;
        /** 总人数 */
        private Integer totalUsers;
    }
}
