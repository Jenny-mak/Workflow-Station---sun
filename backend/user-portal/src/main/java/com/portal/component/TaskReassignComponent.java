package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.dto.TaskInfo;
import com.portal.enums.ChangeType;
import com.portal.exception.PortalException;
import com.portal.service.ProcessAssigneeSnapshot;
import com.portal.util.BuRolePoolTasks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Leader / Approver / SYS_ADMIN reassignment of a BU Role claim-pool hold.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskReassignComponent {

    private final TaskQueryComponent taskQueryComponent;
    private final WorkflowEngineClient workflowEngineClient;
    private final TaskPermissionEvaluator taskPermissionEvaluator;
    private final ClaimForceUnclaimAnnotator claimForceUnclaimAnnotator;
    private final ProcessInstanceSyncComponent processInstanceSyncComponent;
    private final TaskAssignmentHistoryRecorder taskAssignmentHistoryRecorder;

    @Transactional
    public TaskInfo reassign(String taskId, String userId, String targetUserId, String portalUsername) {
        if (!workflowEngineClient.isAvailable()) {
            throw new IllegalStateException(
                    "Flowable engine unavailable, please check if workflow-engine-core service is running");
        }
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new PortalException("400", "Target user is required");
        }
        TaskInfo taskBefore = taskQueryComponent.getTaskById(taskId)
                .orElseThrow(() -> new PortalException("404", "Task not found: " + taskId));
        if (!BuRolePoolTasks.isClaimPoolTask(taskBefore)) {
            throw new PortalException("400", "Only business-unit role requests can be reassigned");
        }
        if (!claimForceUnclaimAnnotator.canReassign(taskBefore, userId)) {
            throw new PortalException("403", "You are not allowed to reassign this task");
        }
        String target = targetUserId.trim();
        if (taskPermissionEvaluator.isHeldByUser(taskBefore, target, null)) {
            throw new PortalException("400", "Task is already held by the selected user");
        }
        String enginePrincipal = TaskPermissionEvaluator.resolveEnginePrincipalForWorkflow(
                taskBefore, userId, portalUsername);
        Optional<Map<String, Object>> result = workflowEngineClient.reassignClaim(taskId, enginePrincipal, target);
        if (result.isEmpty()) {
            throw new PortalException("500", "Failed to reassign task: " + taskId);
        }
        Map<String, Object> data = result.get();
        if (!Boolean.TRUE.equals(data.get("success"))) {
            String message = data.get("message") != null ? (String) data.get("message") : "Failed to reassign task";
            throw new PortalException("400", message);
        }
        TaskInfo task = taskQueryComponent.getTaskById(taskId)
                .orElseThrow(() -> new PortalException("404", "Task not found: " + taskId));
        processInstanceSyncComponent.updateProcessInstanceAssignee(task.getProcessInstanceId(), target, null,
                task.getTaskName(), taskScopedCurrentItem(task));
        taskQueryComponent.invalidateMineTaskListCache();
        taskAssignmentHistoryRecorder.record(taskBefore, userId, ChangeType.REASSIGN, target);
        log.info("Task {} reassigned by {} to {}", taskId, userId, target);
        return task;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> taskScopedCurrentItem(TaskInfo task) {
        Object item = OwnerFieldComponent.taskScopedCurrentItem(task == null ? null : task.getVariables());
        return item instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
