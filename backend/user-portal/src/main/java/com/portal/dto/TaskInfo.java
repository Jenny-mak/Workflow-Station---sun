package com.portal.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 任务信息DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskInfo {

    /** 任务ID */
    private String taskId;

    /** 任务名称 */
    private String taskName;

    /** 任务描述 */
    private String description;

    /** 流程实例ID */
    private String processInstanceId;

    /** 流程定义Key */
    private String processDefinitionKey;

    /** 流程定义名称 */
    private String processDefinitionName;

    /** 分配类型：USER, VIRTUAL_GROUP, DEPT_ROLE, DELEGATED */
    private String assignmentType;

    /**
     * BPMN 用户任务扩展 assigneeType（如 INITIATOR、PROCESS_INITIATOR），与运行时 assignmentType 分离。
     */
    private String bpmnAssigneeType;

    /**
     * BPMN 扩展 businessUnitId（如 FIXED_BU_ROLE 固定 BU），引擎任务列表/详情返回。
     */
    private String bpmnBusinessUnitId;

    /**
     * BPMN extension roleIds / roleId (claim-pool Leader matching).
     */
    private List<String> bpmnRoleIds;

    /**
     * MI 按角色分派信号（引擎从 ExtendedTaskInfo.extendedProperties 透出）：
     * miAssigneeMode=role 表示该 MI 子任务按角色分派（共享认领池），据此做 workspace 可见性收敛
     * （role 分派仅在用户切到该 role 的 workspace 才可见）；miRoleCode/miBusinessUnitCode 为分派角色/BU code。
     */
    private String miAssigneeMode;
    private String miRoleCode;
    private String miBusinessUnitCode;

    /**
     * 「当前步骤」名（MI 感知）：普通节点=任务名；多实例子任务=外层多实例 subProcess name（如 "multi"）。
     * 供 To Do/Completed 列表与详情展示，区别于 taskName（具体内层子任务名）。
     */
    private String currentStepName;

    /**
     * 引擎分配目标：USER 时为处理人 ID；CANDIDATE_USERS 时为候选人用户 ID 逗号拼接等（与 workflow-engine TaskListResult 一致）
     */
    private String assignmentTarget;

    /** 分配人/组ID */
    private String assignee;

    /** 分配人/组名称 */
    private String assigneeName;

    /** 委托人ID（如果是委托任务） */
    private String delegatorId;

    /** 委托人名称 */
    private String delegatorName;

    /** USER or BU_ROLE; null = not a single-task delegate overlay */
    private String delegatedTargetType;

    /** USER delegatee id; null for BU_ROLE */
    private String delegatedTo;

    private String delegatedBuCode;
    private String delegatedRoleCode;

    /** Engine flag: wf_extended_task_info is delegated */
    private Boolean delegated;

    /** 发起人ID */
    private String initiatorId;

    /** 发起人名称 */
    private String initiatorName;

    /** 优先级 */
    private String priority;

    /** 任务状态 */
    private String status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 到期时间 */
    private LocalDateTime dueDate;

    /** 是否逾期 */
    private Boolean isOverdue;

    /** 表单Key */
    private String formKey;

    /** 任务定义Key（BPMN元素ID，如 Activity_1abc） */
    private String taskDefinitionKey;

    /** 流程变量 */
    private Map<String, Object> variables;

    /**
     * Request ID:主表配置的有序字段 + 分隔符拼成的人类可读标识(如 HR-2026-001),
     * 由后端 RequestIdEnricher 填充;主表未配置时为 null(前端列表渲染 '-')。
     */
    private String requestId;

    /**
     * Function Unit code pinned at process start ({@code up_process_instance.function_unit_code}).
     */
    private String functionUnitCode;

    /**
     * Catalog display name from {@code sys_function_units.name}. Null when lookup misses;
     * the To Do cell shows {@code functionUnitName || functionUnitCode}.
     */
    private String functionUnitName;

    /** 完成时间（已处理任务） */
    private LocalDateTime completedTime;
    
    /** 处理时长（毫秒） */
    private Long durationInMillis;
    
    /** 操作类型（approved, rejected, transferred, delegated, completed） */
    private String action;
    
    /** 可用的操作列表 */
    private List<TaskActionInfo> actions;

    /** Flowable 候选人用户 ID（引擎返回，用于权限与认领判断） */
    private List<String> candidateUserIds;

    /** Flowable 候选组 ID */
    private List<String> candidateGroupIds;

    /** 是否为多实例子任务（前端据此隐藏 Action 列和 Detail 链接） */
    @Builder.Default
    private boolean multiInstanceSubTask = false;

    /**
     * BU Role 认领池任务（Tasks to Claim）：该 role 下成员需先 Claim 才能编辑，避免多人并行覆盖。
     * 由 {@code TaskPermissionEvaluator#annotateClaimState} 填充，不来自引擎。
     */
    @Builder.Default
    private boolean claimPoolTask = false;

    /** 认领池任务已被当前用户 Claim（可编辑、可 Unclaim）；被他人 Claim 时为 false 且只读。 */
    @Builder.Default
    private boolean claimedByCurrentUser = false;

    /** 当前用户可以认领（认领池任务 + 本人在候选池 + 尚未被任何人 Claim）。 */
    @Builder.Default
    private boolean claimable = false;

    /**
     * Leader / BU Approver / SYS_ADMIN may force-unclaim a hold that is not theirs.
     * Filled by {@code ClaimForceUnclaimAnnotator} after claim flags.
     */
    @Builder.Default
    private boolean canForceUnclaim = false;

    /**
     * Leader / BU Approver / SYS_ADMIN may assign this claim-pool task to a specified pool member.
     */
    @Builder.Default
    private boolean canReassign = false;
}
