package com.workflow.component;

import com.workflow.dto.request.TaskAssignmentRequest;
import com.workflow.dto.request.TaskClaimRequest;
import com.workflow.dto.request.TaskDelegationRequest;
import com.workflow.dto.response.TaskAssignmentResult;
import com.workflow.entity.ExtendedTaskInfo;
import com.workflow.enums.AssignmentType;
import com.workflow.exception.WorkflowBusinessException;
import com.workflow.exception.WorkflowValidationException;
import com.workflow.repository.ExtendedTaskInfoRepository;
import com.workflow.service.UserPermissionService;

import com.platform.messaging.support.NotificationDispatchHelper;
import com.platform.common.i18n.I18nService;

import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.task.api.Task;
import org.flowable.engine.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Task action operations: assign, delegate, claim, unclaim, transfer.
 * Extracted from TaskManagerComponent.
 */
@Slf4j
@Component
@Transactional
public class TaskActionService {

    @Autowired
    private TaskService taskService;

    @Autowired
    private ExtendedTaskInfoRepository extendedTaskInfoRepository;

    @Autowired
    private UserPermissionService userPermissionService;

    @Autowired
    private NotificationDispatchHelper notificationDispatchHelper;

    @Autowired
    private I18nService i18nService;

    @Autowired
    private TaskClaimSupport taskClaimSupport;

    // ==================== Public Action Methods ====================

    public TaskAssignmentResult assignTask(String taskId, TaskAssignmentRequest request) {
        try {
            validateTaskAssignmentRequest(request);

            Task flowableTask = taskService.createTaskQuery()
                .taskId(taskId)
                .singleResult();

            if (flowableTask == null) {
                throw new WorkflowValidationException(Collections.singletonList(
                    new WorkflowValidationException.ValidationError(
                        "taskId", "Task not found", taskId)));
            }

            ExtendedTaskInfo extendedTaskInfo = extendedTaskInfoRepository
                .findByTaskIdAndIsDeletedFalse(taskId)
                .orElse(createExtendedTaskInfo(flowableTask, request));

            updateTaskAssignment(extendedTaskInfo, request);
            updateFlowableTaskAssignment(flowableTask, request);

            extendedTaskInfo = extendedTaskInfoRepository.save(extendedTaskInfo);

            publishTaskAssignmentEvent(extendedTaskInfo, request);

            return TaskAssignmentResult.success(
                taskId,
                request.getAssignmentType(),
                request.getAssignmentTarget(),
                request.getOperatorUserId(),
                "Task assigned successfully");

        } catch (WorkflowValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkflowBusinessException("TASK_ASSIGN_ERROR",
                "Task assignment failed: " + e.getMessage(), e);
        }
    }

    public TaskAssignmentResult delegateTask(String taskId, TaskDelegationRequest request) {
        try {
            validateTaskDelegationRequest(request);

            Task flowableTask = taskService.createTaskQuery()
                    .taskId(taskId)
                    .singleResult();
            if (flowableTask == null) {
                throw new WorkflowValidationException(Collections.singletonList(
                        new WorkflowValidationException.ValidationError(
                                "taskId", "Task not found", taskId)));
            }
            if (!StringUtils.hasText(flowableTask.getAssignee())) {
                throw new WorkflowValidationException(Collections.singletonList(
                        new WorkflowValidationException.ValidationError(
                                "taskId", "Unclaimed pool tasks cannot be delegated", taskId)));
            }

            ExtendedTaskInfo extendedTaskInfo = extendedTaskInfoRepository
                .findByTaskIdAndIsDeletedFalse(taskId)
                .orElseThrow(() -> new WorkflowValidationException(Collections.singletonList(
                    new WorkflowValidationException.ValidationError(
                        "taskId", "Task not found", taskId))));

            validateDelegationPermission(extendedTaskInfo, request.getDelegatedBy());

            if (extendedTaskInfo.isCompleted()) {
                throw new WorkflowValidationException(Collections.singletonList(
                    new WorkflowValidationException.ValidationError(
                        "taskId", "Task already completed, cannot delegate", taskId)));
            }

            applyDelegateTarget(extendedTaskInfo, request);
            extendedTaskInfo = extendedTaskInfoRepository.save(extendedTaskInfo);
            publishTaskDelegationEvent(extendedTaskInfo, request);

            String targetLabel = request.isBuRoleTarget()
                    ? request.getDelegatedBuCode() + "/" + request.getDelegatedRoleCode()
                    : request.getDelegatedTo();
            return TaskAssignmentResult.success(
                taskId,
                AssignmentType.USER,
                targetLabel,
                request.getDelegatedBy(),
                "Task delegated successfully");

        } catch (WorkflowValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkflowBusinessException("TASK_DELEGATION_ERROR",
                "Task delegation failed: " + e.getMessage(), e);
        }
    }

    private void applyDelegateTarget(ExtendedTaskInfo extendedTaskInfo, TaskDelegationRequest request) {
        String reason = request.getEffectiveDelegationReason();
        if (request.isBuRoleTarget()) {
            extendedTaskInfo.delegateTaskToBuRole(
                    request.getDelegatedBuCode().trim(),
                    request.getDelegatedRoleCode().trim(),
                    request.getDelegatedBy(),
                    reason);
            return;
        }
        extendedTaskInfo.delegateTask(
                request.getDelegatedTo(),
                request.getDelegatedBy(),
                reason);
    }

    public TaskAssignmentResult claimTask(String taskId, TaskClaimRequest request) {
        validateTaskClaimRequest(request);
        return taskClaimSupport.claimTask(taskId, request);
    }

    public TaskAssignmentResult unclaimTask(String taskId, String userId) {
        validateUserId(userId);
        return taskClaimSupport.unclaimTask(taskId, userId);
    }

    public TaskAssignmentResult reassignClaim(String taskId, String operatorUserId, String targetUserId) {
        validateUserId(operatorUserId);
        validateUserId(targetUserId);
        return taskClaimSupport.reassignClaim(taskId, operatorUserId, targetUserId);
    }

    public TaskAssignmentResult transferTask(String taskId, String fromUserId, String toUserId, String reason) {
        try {
            validateUserId(fromUserId);
            validateUserId(toUserId);

            Task flowableTask = taskService.createTaskQuery()
                .taskId(taskId)
                .singleResult();

            if (flowableTask == null) {
                throw new WorkflowValidationException(Collections.singletonList(
                    new WorkflowValidationException.ValidationError(
                        "taskId", "Task not found", taskId)));
            }

            Optional<ExtendedTaskInfo> extendedTaskInfoOpt = extendedTaskInfoRepository
                .findByTaskIdAndIsDeletedFalse(taskId);

            if (extendedTaskInfoOpt.isPresent()) {
                ExtendedTaskInfo extendedTaskInfo = extendedTaskInfoOpt.get();

                if (extendedTaskInfo.isCompleted()) {
                    throw new WorkflowValidationException(Collections.singletonList(
                        new WorkflowValidationException.ValidationError(
                            "taskId", "Task already completed, cannot transfer", taskId)));
                }

                validateCompletePermission(extendedTaskInfo, fromUserId);

                extendedTaskInfo.setAssignmentType(AssignmentType.USER);
                extendedTaskInfo.setAssignmentTarget(toUserId);
                extendedTaskInfo.setClaimedBy(null);
                extendedTaskInfo.setClaimedTime(null);
                extendedTaskInfo.clearDelegation();
                extendedTaskInfo.updateStatus("ASSIGNED", fromUserId);
                extendedTaskInfoRepository.save(extendedTaskInfo);
            }

            String processInstanceId = flowableTask.getProcessInstanceId();
            String previousActor = Authentication.getAuthenticatedUserId();
            try {
                Authentication.setAuthenticatedUserId(fromUserId);
                taskService.setAssignee(taskId, toUserId);
                taskService.addComment(taskId, processInstanceId, "transfer",
                        reason != null && !reason.isBlank() ? reason : "");
            } finally {
                Authentication.setAuthenticatedUserId(previousActor);
            }

            String taskLabel = flowableTask.getName() != null ? flowableTask.getName() : taskId;
            String reasonText = reason != null && !reason.isBlank()
                    ? i18nService.getMessage("workflow.notification.transfer_reason", reason)
                    : "";
            notificationDispatchHelper.publishToUserAfterCommit(
                    toUserId,
                    "TASK",
                    i18nService.getMessage("workflow.notification.transferred_title"),
                    i18nService.getMessage("workflow.notification.transferred_body",
                            fromUserId, taskLabel, reasonText).trim(),
                    taskLink(taskId),
                    "workflow-engine");

            return TaskAssignmentResult.success(
                taskId,
                AssignmentType.USER,
                toUserId,
                fromUserId,
                "Task transferred successfully");

        } catch (WorkflowValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkflowBusinessException("TASK_TRANSFER_ERROR",
                "Task transfer failed: " + e.getMessage(), e);
        }
    }

    // ==================== Private Helper Methods ====================

    private ExtendedTaskInfo createExtendedTaskInfo(Task flowableTask, TaskAssignmentRequest request) {
        return ExtendedTaskInfo.builder()
            .taskId(flowableTask.getId())
            .processInstanceId(flowableTask.getProcessInstanceId())
            .processDefinitionId(flowableTask.getProcessDefinitionId())
            .taskDefinitionKey(flowableTask.getTaskDefinitionKey())
            .taskName(flowableTask.getName())
            .taskDescription(flowableTask.getDescription())
            .assignmentType(request.getAssignmentType())
            .assignmentTarget(request.getAssignmentTarget())
            .priority(request.getEffectivePriority())
            .dueDate(request.getDueDate())
            .formKey(flowableTask.getFormKey())
            .status("ASSIGNED")
            .createdTime(LocalDateTime.now())
            .createdBy(request.getOperatorUserId())
            .tenantId(request.getTenantId())
            .isDeleted(false)
            .version(0L)
            .build();
    }

    private void updateTaskAssignment(ExtendedTaskInfo extendedTaskInfo, TaskAssignmentRequest request) {
        extendedTaskInfo.setAssignmentType(request.getAssignmentType());
        extendedTaskInfo.setAssignmentTarget(request.getAssignmentTarget());
        extendedTaskInfo.setPriority(request.getEffectivePriority());
        extendedTaskInfo.setDueDate(request.getDueDate());
        extendedTaskInfo.updateStatus("ASSIGNED", request.getOperatorUserId());
        extendedTaskInfo.clearDelegation();
        extendedTaskInfo.setClaimedBy(null);
        extendedTaskInfo.setClaimedTime(null);
    }

    private void updateFlowableTaskAssignment(Task flowableTask, TaskAssignmentRequest request) {
        switch (request.getAssignmentType()) {
            case USER:
                taskService.setAssignee(flowableTask.getId(), request.getAssignmentTarget());
                break;
            case VIRTUAL_GROUP:
            case CANDIDATE_USERS:
                taskService.setAssignee(flowableTask.getId(), null);
                break;
        }

        if (request.getPriority() != null) {
            taskService.setPriority(flowableTask.getId(), request.getPriority());
        }
        if (request.getDueDate() != null) {
            taskService.setDueDate(flowableTask.getId(),
                java.sql.Timestamp.valueOf(request.getDueDate()));
        }
    }

    private void validateDelegationPermission(ExtendedTaskInfo task, String delegatedBy) {
        boolean hasPermission = userPermissionService.hasTaskPermission(
                delegatedBy,
                task.getAssignmentType(),
                task.getAssignmentTarget());

        if (!hasPermission) {
            throw new WorkflowValidationException(Collections.singletonList(
                new WorkflowValidationException.ValidationError(
                    "delegatedBy", "User does not have permission to delegate this task", delegatedBy)));
        }
    }

    void validateCompletePermission(ExtendedTaskInfo task, String userId) {
        String currentAssignee = task.getCurrentAssignee();

        if (currentAssignee != null && !currentAssignee.isBlank()) {
            if (!engineActorMatchesPortalUser(currentAssignee, userId)) {
                throw new WorkflowValidationException(Collections.singletonList(
                    new WorkflowValidationException.ValidationError(
                        "userId", "User does not have permission to complete this task", userId)));
            }
            return;
        }

        boolean hasPermission = userPermissionService.hasTaskPermission(
                userId,
                task.getAssignmentType(),
                task.getAssignmentTarget());

        if (!hasPermission) {
            throw new WorkflowValidationException(Collections.singletonList(
                new WorkflowValidationException.ValidationError(
                    "userId", "User does not have permission to complete this task", userId)));
        }
    }

    boolean engineActorMatchesPortalUser(String engineSideActor, String portalUserId) {
        return taskClaimSupport.engineActorMatchesPortalUser(engineSideActor, portalUserId);
    }

    private void validateTaskAssignmentRequest(TaskAssignmentRequest request) {
        if (request == null) {
            throw new WorkflowValidationException(Collections.singletonList(
                new WorkflowValidationException.ValidationError(
                    "request", "Request parameters cannot be empty", null)));
        }
        if (!request.isValid()) {
            throw new WorkflowValidationException(Collections.singletonList(
                new WorkflowValidationException.ValidationError(
                    "request", request.getValidationError(), null)));
        }
    }

    private void validateTaskDelegationRequest(TaskDelegationRequest request) {
        if (request == null) {
            throw new WorkflowValidationException(Collections.singletonList(
                new WorkflowValidationException.ValidationError(
                    "request", "Request parameters cannot be empty", null)));
        }
        if (!request.isValid()) {
            throw new WorkflowValidationException(Collections.singletonList(
                new WorkflowValidationException.ValidationError(
                    "request", request.getValidationError(), null)));
        }
    }

    private void validateTaskClaimRequest(TaskClaimRequest request) {
        if (request == null) {
            throw new WorkflowValidationException(Collections.singletonList(
                new WorkflowValidationException.ValidationError(
                    "request", "Request parameters cannot be empty", null)));
        }
        if (!request.isValid()) {
            throw new WorkflowValidationException(Collections.singletonList(
                new WorkflowValidationException.ValidationError(
                    "request", request.getValidationError(), null)));
        }
    }

    private void validateUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new WorkflowValidationException(Collections.singletonList(
                new WorkflowValidationException.ValidationError(
                    "userId", "User ID cannot be empty", userId)));
        }
    }

    // ==================== Event Publishing ====================

    private void publishTaskAssignmentEvent(ExtendedTaskInfo task, TaskAssignmentRequest request) {
        log.info("Task assignment event: taskId={}, assignmentTarget={}, assignmentType={}",
                task.getTaskId(), request.getAssignmentTarget(), request.getAssignmentType());
        if (!request.shouldSendNotification()) {
            return;
        }
        if (request.getAssignmentType() != AssignmentType.USER) {
            return;
        }
        String targetUser = request.getAssignmentTarget();
        if (!StringUtils.hasText(targetUser)) {
            return;
        }
        String label = task.getTaskName() != null ? task.getTaskName() : task.getTaskId();
        notificationDispatchHelper.publishToUserAfterCommit(
                targetUser.trim(),
                "TASK",
                i18nService.getMessage("workflow.notification.assigned_title"),
                i18nService.getMessage("workflow.notification.assigned_body", label, request.getOperatorUserId()),
                taskLink(task.getTaskId()),
                "workflow-engine");
    }

    private void publishTaskDelegationEvent(ExtendedTaskInfo task, TaskDelegationRequest request) {
        log.info("Task delegation event: taskId={}, delegatedTo={}, delegatedBy={}",
                task.getTaskId(), request.getDelegatedTo(), request.getDelegatedBy());
        if (!request.shouldSendNotification()) {
            return;
        }
        String label = task.getTaskName() != null ? task.getTaskName() : task.getTaskId();
        notificationDispatchHelper.publishToUserAfterCommit(
                request.getDelegatedTo(),
                "TASK",
                i18nService.getMessage("workflow.notification.delegated_title"),
                i18nService.getMessage("workflow.notification.delegated_body",
                        request.getDelegatedBy(),
                        label,
                        request.getEffectiveDelegationReason() != null
                                ? " " + i18nService.getMessage("workflow.notification.delegation_reason", request.getEffectiveDelegationReason())
                                : "").trim(),
                taskLink(task.getTaskId()),
                "workflow-engine");
    }

    static String taskLink(String taskId) {
        return "/tasks/" + taskId;
    }
}
