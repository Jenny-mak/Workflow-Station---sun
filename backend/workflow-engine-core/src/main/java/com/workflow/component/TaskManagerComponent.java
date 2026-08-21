package com.workflow.component;

import com.workflow.aspect.WorkflowAuditAspect.Auditable;
import com.workflow.dto.request.TaskAssignmentRequest;
import com.workflow.dto.request.TaskClaimRequest;
import com.workflow.dto.request.TaskDelegationRequest;
import com.workflow.dto.request.TaskReturnRequest;
import com.workflow.dto.response.TaskAssignmentResult;
import com.workflow.dto.response.TaskListResult;
import com.workflow.enums.AuditOperationType;
import com.workflow.enums.AuditResourceType;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Task Manager Component — facade.
 * All business logic lives in the Task* collaborator services; this class
 * preserves every original public-method signature verbatim and delegates.
 *
 * Collaborators (injected @Lazy to prevent circular-dependency issues):
 *   TaskQueryService      — query / orphan-repair / task-info building
 *   TaskActionService     — assign / delegate / claim / unclaim / transfer
 *   TaskCompletionService — completeTask / returnTask / getReturnableActivities
 *   TaskMultiInstanceService — MI detection / injection / write-back / WebSocket
 *   TaskStatsService      — count / overdue / high-priority stats
 */
@Slf4j
@Component
@Transactional
public class TaskManagerComponent {

    @Lazy @Autowired private TaskQueryService taskQueryService;
    @Lazy @Autowired private TaskActionService taskActionService;
    @Lazy @Autowired private TaskCompletionService taskCompletionService;
    @Lazy @Autowired private TaskStatsService taskStatsService;

    // ==================== Task Query ====================

    public TaskListResult getUserTasks(String userId, int page, int size) {
        return taskQueryService.getUserTasks(userId, page, size);
    }

    public TaskListResult getUserTasks(String userId, int page, int size, String activeBusinessUnitId) {
        return taskQueryService.getUserTasks(userId, page, size, activeBusinessUnitId);
    }

    public Map<String, String> resolveUserDisplayNames(java.util.Collection<String> userIds) {
        return taskQueryService.resolveUserDisplayNames(userIds);
    }

    public TaskListResult getTasksByProcessInstance(String processInstanceId, int page, int size) {
        return taskQueryService.getTasksByProcessInstance(processInstanceId, page, size);
    }

    public TaskListResult getUserAllVisibleTasks(String userId, List<String> groupIds,
                                                 List<String> deptRoles, int page, int size) {
        return taskQueryService.getUserAllVisibleTasks(userId, groupIds, deptRoles, page, size);
    }

    public TaskListResult getUserAllVisibleTasks(String userId, List<String> groupIds,
                                                 List<String> deptRoles, int page, int size,
                                                 String activeBusinessUnitId) {
        return taskQueryService.getUserAllVisibleTasks(userId, groupIds, deptRoles, page, size, activeBusinessUnitId);
    }

    public TaskListResult getUserAllVisibleTasks(String userId, List<String> groupIds,
                                                 List<String> deptRoles, int page, int size,
                                                 String activeBusinessUnitId,
                                                 com.workflow.dto.request.EngineTaskListCriteria criteria) {
        return taskQueryService.getUserAllVisibleTasks(
                userId, groupIds, deptRoles, page, size, activeBusinessUnitId, criteria);
    }

    public TaskListResult.TaskInfo getTaskInfo(String taskId) {
        return taskQueryService.getTaskInfo(taskId);
    }

    // ==================== Task Assignment, Delegation, Claim ====================

    @Auditable(
        operationType = AuditOperationType.ASSIGN_TASK,
        resourceType = AuditResourceType.TASK,
        description = "Assign task",
        captureArgs = true,
        captureResult = true
    )
    public TaskAssignmentResult assignTask(String taskId, TaskAssignmentRequest request) {
        return taskActionService.assignTask(taskId, request);
    }

    public TaskAssignmentResult delegateTask(String taskId, TaskDelegationRequest request) {
        return taskActionService.delegateTask(taskId, request);
    }

    public TaskAssignmentResult claimTask(String taskId, TaskClaimRequest request) {
        return taskActionService.claimTask(taskId, request);
    }

    public TaskAssignmentResult unclaimTask(String taskId, String userId) {
        return taskActionService.unclaimTask(taskId, userId);
    }

    public TaskAssignmentResult transferTask(String taskId, String fromUserId, String toUserId, String reason) {
        return taskActionService.transferTask(taskId, fromUserId, toUserId, reason);
    }

    // ==================== Task Completion and Rollback ====================

    public TaskAssignmentResult completeTask(String taskId, String userId,
                                             Map<String, Object> variables) {
        return taskCompletionService.completeTask(taskId, userId, variables);
    }

    public TaskAssignmentResult completeTask(String taskId, String userId,
                                             Map<String, Object> variables,
                                             boolean sendNotification) {
        return taskCompletionService.completeTask(taskId, userId, variables, sendNotification);
    }

    @Auditable(
        operationType = AuditOperationType.RETURN_TASK,
        resourceType = AuditResourceType.TASK,
        description = "Return task",
        captureArgs = true,
        captureResult = true
    )
    public TaskAssignmentResult returnTask(String taskId, TaskReturnRequest request) {
        return taskCompletionService.returnTask(taskId, request);
    }

    public List<TaskListResult.TaskInfo> getReturnableActivities(String taskId) {
        return taskCompletionService.getReturnableActivities(taskId);
    }

    // ==================== Statistical Queries ====================

    public long countUserTasks(String userId) {
        return taskStatsService.countUserTasks(userId);
    }

    public long countUserOverdueTasks(String userId) {
        return taskStatsService.countUserOverdueTasks(userId);
    }

    public List<TaskListResult.TaskInfo> getOverdueTasks() {
        return taskStatsService.getOverdueTasks();
    }

    public List<TaskListResult.TaskInfo> getHighPriorityTasks(int minPriority) {
        return taskStatsService.getHighPriorityTasks(minPriority);
    }
}
