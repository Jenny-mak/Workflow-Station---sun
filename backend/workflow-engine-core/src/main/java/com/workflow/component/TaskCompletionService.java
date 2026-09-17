package com.workflow.component;

import com.workflow.client.AdminCenterClient;
import com.workflow.dto.request.TaskReturnRequest;
import com.workflow.dto.response.TaskAssignmentResult;
import com.workflow.dto.response.TaskListResult;
import com.workflow.entity.ExtendedTaskInfo;
import com.workflow.enums.AssignmentType;
import com.workflow.exception.WorkflowBusinessException;
import com.workflow.exception.WorkflowValidationException;
import com.workflow.repository.ExtendedTaskInfoRepository;
import com.workflow.service.UserPermissionService;
import com.workflow.util.RollbackAssigneeFallbackSupport;

import com.platform.messaging.support.NotificationDispatchHelper;
import com.platform.common.i18n.I18nService;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Task completion and return (rollback) operations, plus shared permission helpers
 * used by TaskActionService and TaskCompletionService.
 * Extracted from TaskManagerComponent.
 */
@Slf4j
@Component
@Transactional
public class TaskCompletionService {

    @Autowired
    private TaskService taskService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private ExtendedTaskInfoRepository extendedTaskInfoRepository;

    @Autowired
    private UserPermissionService userPermissionService;

    @Autowired
    private AdminCenterClient adminCenterClient;

    @Autowired
    private MultiInstanceCanceller multiInstanceCanceller;

    @Autowired
    private NotificationDispatchHelper notificationDispatchHelper;

    @Autowired
    private I18nService i18nService;

    @Lazy
    @Autowired
    private TaskActionService taskActionService;

    @Lazy
    @Autowired
    private TaskMultiInstanceService taskMultiInstanceService;

    // ==================== Public Methods ====================

    public TaskAssignmentResult completeTask(String taskId, String userId,
                                             java.util.Map<String, Object> variables) {
        return completeTask(taskId, userId, variables, true);
    }

    public TaskAssignmentResult completeTask(String taskId, String userId,
                                             java.util.Map<String, Object> variables,
                                             boolean sendNotification) {
        return completeTask(taskId, userId, variables, sendNotification, null);
    }

    public TaskAssignmentResult completeTask(String taskId, String userId,
                                             java.util.Map<String, Object> variables,
                                             boolean sendNotification,
                                             String onBehalfOfUserId) {
        try {
            validateUserId(userId);

            Task flowableTask = taskService.createTaskQuery()
                .taskId(taskId)
                .singleResult();

            if (flowableTask == null) {
                throw new WorkflowValidationException(Collections.singletonList(
                    new WorkflowValidationException.ValidationError(
                        "taskId", "Task not found", taskId)));
            }

            ensureAssigneeForOrphanInitiatorTaskIfNeeded(flowableTask, userId);
            flowableTask = taskService.createTaskQuery()
                    .taskId(taskId)
                    .singleResult();
            if (flowableTask == null) {
                throw new WorkflowValidationException(Collections.singletonList(
                    new WorkflowValidationException.ValidationError(
                            "taskId", "Task not found after assignee repair", taskId)));
            }

            ensureProcessInitiatorAssigneeFromBpmnIfNeeded(flowableTask, userId);
            flowableTask = taskService.createTaskQuery()
                    .taskId(taskId)
                    .singleResult();
            if (flowableTask == null) {
                throw new WorkflowValidationException(Collections.singletonList(
                    new WorkflowValidationException.ValidationError(
                            "taskId", "Task not found after initiator assignee repair", taskId)));
            }

            String taskDisplayName = flowableTask.getName() != null ? flowableTask.getName() : taskId;

            Optional<ExtendedTaskInfo> extendedTaskInfoOpt = extendedTaskInfoRepository
                .findByTaskIdAndIsDeletedFalse(taskId);

            if (extendedTaskInfoOpt.isPresent()) {
                ExtendedTaskInfo extendedTaskInfo = extendedTaskInfoOpt.get();

                if (!isCompleteAuthorized(flowableTask, extendedTaskInfo, userId, onBehalfOfUserId)) {
                    throw new WorkflowValidationException(Collections.singletonList(
                        new WorkflowValidationException.ValidationError(
                            "userId", "User does not have permission to complete this task", userId)));
                }

                if (extendedTaskInfo.isCompleted()) {
                    throw new WorkflowValidationException(Collections.singletonList(
                        new WorkflowValidationException.ValidationError(
                            "taskId", "Task already completed", taskId)));
                }

                if (taskMultiInstanceService.isMultiInstanceSubTask(extendedTaskInfo)) {
                    log.info("Detected multi-instance sub-task, preparing to write back to sub-table: taskId={}", taskId);
                    taskMultiInstanceService.handleMultiInstanceSubTaskCompletion(taskId, variables, extendedTaskInfo);
                }
            } else if (!isCompleteAuthorized(flowableTask, null, userId, onBehalfOfUserId)) {
                throw new WorkflowValidationException(Collections.singletonList(
                        new WorkflowValidationException.ValidationError(
                                "userId", "User does not have permission to complete this task", userId)));
            }

            String processInstanceId = flowableTask.getProcessInstanceId();
            String initiatorUserId = resolveInitiatorUserId(processInstanceId);
            if (processInstanceId != null) {
                runtimeService.setVariable(processInstanceId, "currentUserId", normalizePortalUserIdForVariable(userId));
            }

            if (variables != null && !variables.isEmpty() && processInstanceId != null) {
                Object existingInitiator = runtimeService.getVariable(processInstanceId, "initiator");
                if (existingInitiator != null
                        && (variables.get("initiator") == null
                        || variables.get("initiator").toString().isBlank())) {
                    variables.put("initiator", existingInitiator);
                }
            }

            if (variables != null && !variables.isEmpty()) {
                if (processInstanceId != null) {
                    log.debug("Setting {} variable keys on process instance {} before completing task {}",
                        variables.size(), processInstanceId, taskId);
                    runtimeService.setVariables(processInstanceId, variables);
                }
            } else {
                log.debug("No variables provided for task completion. TaskId: {}, UserId: {}", taskId, userId);
            }

            String processDefinitionId = flowableTask.getProcessDefinitionId();
            String taskDefinitionKey = flowableTask.getTaskDefinitionKey();

            taskMultiInstanceService.detectAndInjectMultiInstanceData(processInstanceId, processDefinitionId, taskDefinitionKey);

            if (variables != null) {
                Object approverComment = variables.get("approverComments");
                if (approverComment != null && !approverComment.toString().isBlank()) {
                    taskService.addComment(taskId, processInstanceId, approverComment.toString());
                }
            }
            if (StringUtils.hasText(onBehalfOfUserId)
                    && !taskActionService.engineActorMatchesPortalUser(onBehalfOfUserId, userId)) {
                taskService.addComment(taskId, processInstanceId,
                        "On behalf of " + onBehalfOfUserId.trim());
            }

            String previousActor = Authentication.getAuthenticatedUserId();
            try {
                Authentication.setAuthenticatedUserId(userId);
                if (variables != null && !variables.isEmpty()) {
                    log.info("Completing task {} with variables: {}", taskId, variables);
                    taskService.complete(taskId, variables);
                } else {
                    log.info("Completing task {} without variables", taskId);
                    taskService.complete(taskId);
                }
            } finally {
                Authentication.setAuthenticatedUserId(previousActor);
            }

            AssignmentType assignmentType = AssignmentType.USER;
            String currentAssignee = userId;

            if (extendedTaskInfoOpt.isPresent()) {
                ExtendedTaskInfo extendedTaskInfo = extendedTaskInfoOpt.get();
                extendedTaskInfo.completeTask(userId);
                extendedTaskInfoRepository.save(extendedTaskInfo);
                assignmentType = extendedTaskInfo.getAssignmentType();
                currentAssignee = extendedTaskInfo.getCurrentAssignee();

                publishTaskCompleteEvent(extendedTaskInfo, userId, variables, sendNotification,
                        taskDisplayName, initiatorUserId, processInstanceId, taskId);
            } else if (sendNotification) {
                publishTaskCompleteEvent(null, userId, variables, true,
                        taskDisplayName, initiatorUserId, processInstanceId, taskId);
            }

            return TaskAssignmentResult.success(
                taskId,
                assignmentType,
                currentAssignee,
                userId,
                "Task completed successfully");

        } catch (WorkflowValidationException e) {
            throw e;
        } catch (MultiInstanceDataResolver.OptimisticLockException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkflowBusinessException("TASK_COMPLETE_ERROR",
                "Task completion failed: " + e.getMessage(), e);
        }
    }

    public TaskAssignmentResult returnTask(String taskId, TaskReturnRequest request) {
        try {
            validateTaskReturnRequest(request);

            Task currentTask = taskService.createTaskQuery()
                .taskId(taskId)
                .singleResult();

            if (currentTask == null) {
                throw new WorkflowValidationException(Collections.singletonList(
                    new WorkflowValidationException.ValidationError(
                        "taskId", "Task not found", taskId)));
            }

            String processInstanceId = currentTask.getProcessInstanceId();
            String currentActivityId = currentTask.getTaskDefinitionKey();
            String targetActivityId = request.getTargetActivityId();

            List<HistoricActivityInstance> historicActivities = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .activityId(targetActivityId)
                .finished()
                .orderByHistoricActivityInstanceEndTime()
                .desc()
                .list();

            if (historicActivities.isEmpty()) {
                throw new WorkflowValidationException(Collections.singletonList(
                    new WorkflowValidationException.ValidationError(
                        "targetActivityId", "Target activity is not a valid historic activity", targetActivityId)));
            }

            if (isReturnTargetBeforeMultiInstance(processInstanceId, currentActivityId, targetActivityId)) {
                log.info("Rollback target is before multi-instance sub-process, starting cascade cancel: processInstanceId={}, targetActivityId={}",
                    processInstanceId, targetActivityId);
                multiInstanceCanceller.cancelMultiInstanceTasks(processInstanceId);
            }

            recordReturnTaskComment(taskId, processInstanceId, currentTask, targetActivityId, request);

            runtimeService.setVariable(processInstanceId, RollbackAssigneeFallbackSupport.VAR_FALLBACK_ACTIVE, Boolean.TRUE);
            runtimeService.setVariable(processInstanceId, RollbackAssigneeFallbackSupport.VAR_TARGET_ACTIVITY_ID, targetActivityId);

            runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstanceId)
                .moveActivityIdTo(currentActivityId, targetActivityId)
                .changeState();

            ExtendedTaskInfo extendedTaskInfo = extendedTaskInfoRepository
                .findByTaskIdAndIsDeletedFalse(taskId)
                .orElse(null);

            if (extendedTaskInfo != null) {
                extendedTaskInfo.updateStatus("RETURNED", request.getUserId());
                extendedTaskInfo.setIsDeleted(true);
                extendedTaskInfoRepository.save(extendedTaskInfo);
            }

            publishTaskReturnEvent(taskId, processInstanceId, currentActivityId, targetActivityId, request);

            return TaskAssignmentResult.success(
                taskId,
                AssignmentType.USER,
                targetActivityId,
                request.getUserId(),
                "Task returned successfully, returned to activity: " + targetActivityId);

        } catch (WorkflowValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkflowBusinessException("TASK_RETURN_ERROR",
                "Task return failed: " + e.getMessage(), e);
        }
    }

    public List<TaskListResult.TaskInfo> getReturnableActivities(String taskId) {
        try {
            Task currentTask = taskService.createTaskQuery()
                .taskId(taskId)
                .singleResult();

            if (currentTask == null) {
                throw new WorkflowValidationException(Collections.singletonList(
                    new WorkflowValidationException.ValidationError(
                        "taskId", "Task not found", taskId)));
            }

            String processInstanceId = currentTask.getProcessInstanceId();

            List<HistoricActivityInstance> historicActivities = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .activityType("userTask")
                .finished()
                .orderByHistoricActivityInstanceEndTime()
                .desc()
                .list();

            List<TaskListResult.TaskInfo> returnableActivities = new ArrayList<>();
            java.util.Set<String> seenActivityIds = new java.util.HashSet<>();

            for (HistoricActivityInstance activity : historicActivities) {
                if (!seenActivityIds.contains(activity.getActivityId())) {
                    seenActivityIds.add(activity.getActivityId());

                    TaskListResult.TaskInfo taskInfo = TaskListResult.TaskInfo.builder()
                        .taskId(activity.getActivityId())
                        .taskName(activity.getActivityName())
                        .processInstanceId(processInstanceId)
                        .status("COMPLETED")
                        .build();

                    returnableActivities.add(taskInfo);
                }
            }

            return returnableActivities;

        } catch (Exception e) {
            throw new WorkflowBusinessException("TASK_QUERY_ERROR",
                "Failed to query returnable activities: " + e.getMessage(), e);
        }
    }

    // ==================== Private Helpers ====================

    private boolean isReturnTargetBeforeMultiInstance(String processInstanceId,
                                                      String currentActivityId,
                                                      String targetActivityId) {
        try {
            List<ExtendedTaskInfo> activeMultiInstanceTasks = extendedTaskInfoRepository
                .findByProcessInstanceIdAndIsDeletedFalse(processInstanceId)
                .stream()
                .filter(taskMultiInstanceService::isMultiInstanceTask)
                .filter(task -> !"COMPLETED".equals(task.getStatus()) && !"CANCELLED".equals(task.getStatus()))
                .toList();

            if (activeMultiInstanceTasks.isEmpty()) {
                log.debug("No active multi-instance sub-tasks in process instance {}", processInstanceId);
                return false;
            }

            LocalDateTime earliestMultiInstanceTaskTime = activeMultiInstanceTasks.stream()
                .map(ExtendedTaskInfo::getCreatedTime)
                .min(LocalDateTime::compareTo)
                .orElse(null);

            if (earliestMultiInstanceTaskTime == null) {
                log.warn("Cannot get creation time of multi-instance sub-task");
                return false;
            }

            List<HistoricActivityInstance> targetActivities = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .activityId(targetActivityId)
                .finished()
                .orderByHistoricActivityInstanceEndTime()
                .desc()
                .list();

            if (targetActivities.isEmpty()) {
                log.warn("No history record found for target activity: {}", targetActivityId);
                return false;
            }

            java.util.Date targetEndDate = targetActivities.get(0).getEndTime();
            if (targetEndDate == null) {
                log.warn("Target activity {} has no completion time", targetActivityId);
                return false;
            }

            LocalDateTime targetEndTime = LocalDateTime.ofInstant(
                targetEndDate.toInstant(),
                java.time.ZoneId.systemDefault()
            );

            boolean isBeforeMultiInstance = targetEndTime.isBefore(earliestMultiInstanceTaskTime);

            if (isBeforeMultiInstance) {
                log.info("Detected rollback target {} (completed: {}) before multi-instance sub-process (created: {})",
                    targetActivityId, targetEndTime, earliestMultiInstanceTaskTime);
            }

            return isBeforeMultiInstance;

        } catch (Exception e) {
            log.error("Exception checking whether rollback target is before multi-instance sub-process: processInstanceId={}", processInstanceId, e);
            return false;
        }
    }

    private void ensureProcessInitiatorAssigneeFromBpmnIfNeeded(Task task, String portalUserId) {
        if (task == null || !StringUtils.hasText(portalUserId)) {
            return;
        }
        if (StringUtils.hasText(task.getAssignee())) {
            return;
        }
        String pdId = task.getProcessDefinitionId();
        String defKey = task.getTaskDefinitionKey();
        if (!StringUtils.hasText(pdId) || !StringUtils.hasText(defKey)) {
            return;
        }
        // Lazy injection prevents circular dep; BpmnActionParser has no back-reference to TaskCompletionService
        String bpmnAt = null;
        try {
            bpmnAt = bpmnActionParser().getUserTaskExtensionPropertyValue(pdId, defKey, "assigneeType");
        } catch (Exception e) {
            log.debug("ensureProcessInitiatorAssignee: read assigneeType: {}", e.getMessage());
        }
        if (!TaskQueryService.isBpmnProcessInitiatorType(bpmnAt)) {
            return;
        }
        String piid = task.getProcessInstanceId();
        if (!StringUtils.hasText(piid)) {
            return;
        }
        String initiatorId = resolveInitiatorUserId(piid);
        if (!StringUtils.hasText(initiatorId) || !taskActionService.engineActorMatchesPortalUser(initiatorId, portalUserId.trim())) {
            return;
        }
        try {
            taskService.claim(task.getId(), portalUserId.trim());
            log.info("Claimed BPMN initiator task {} for user {} before complete", task.getId(), portalUserId);
        } catch (Exception e) {
            log.debug("Claim initiator task {} failed ({}), trying setAssignee", task.getId(), e.getMessage());
            try {
                taskService.setAssignee(task.getId(), portalUserId.trim());
                log.info("Set assignee on BPMN initiator task {} for user {} before complete", task.getId(), portalUserId);
            } catch (Exception e2) {
                log.warn("Could not claim/setAssignee initiator task {} for user {}: {}",
                        task.getId(), portalUserId, e2.getMessage());
            }
        }
    }

    private void ensureAssigneeForOrphanInitiatorTaskIfNeeded(Task task, String portalUserId) {
        if (task == null || !StringUtils.hasText(portalUserId)) {
            return;
        }
        if (StringUtils.hasText(task.getAssignee())) {
            return;
        }
        long candidateLinks = taskService.getIdentityLinksForTask(task.getId()).stream()
                .filter(l -> "candidate".equals(l.getType()))
                .count();
        if (candidateLinks > 0) {
            return;
        }
        String piid = task.getProcessInstanceId();
        if (!StringUtils.hasText(piid)) {
            return;
        }
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(piid)
                .singleResult();
        if (pi == null) {
            return;
        }
        String startUser = pi.getStartUserId();
        if (!StringUtils.hasText(startUser) || !taskActionService.engineActorMatchesPortalUser(startUser, portalUserId.trim())) {
            return;
        }
        log.warn("Task {} has no assignee and no candidate links; setting assignee to portal user {} (process initiator) before complete",
                task.getId(), portalUserId.trim());
        taskService.setAssignee(task.getId(), portalUserId.trim());
    }

    boolean isCompleteAuthorized(Task task, ExtendedTaskInfo extended, String actorUserId,
                                 String onBehalfOfUserId) {
        if (flowableRuntimeAuthorizesComplete(task, actorUserId)) {
            return true;
        }
        if (!StringUtils.hasText(onBehalfOfUserId) || task == null) {
            if (extended != null) {
                try {
                    taskActionService.validateCompletePermission(extended, actorUserId);
                    return true;
                } catch (WorkflowValidationException e) {
                    return false;
                }
            }
            return false;
        }
        String assignee = task.getAssignee();
        if (!StringUtils.hasText(assignee)
                || !taskActionService.engineActorMatchesPortalUser(assignee, onBehalfOfUserId.trim())) {
            return false;
        }
        return true;
    }

    boolean actorMatchesSingleTaskDelegatee(ExtendedTaskInfo extended, String actorUserId) {
        if (extended == null || !StringUtils.hasText(actorUserId) || !extended.isDelegated()) {
            return false;
        }
        if (StringUtils.hasText(extended.getDelegatedTo())
                && taskActionService.engineActorMatchesPortalUser(extended.getDelegatedTo(), actorUserId)) {
            return true;
        }
        if (!extended.isBuRoleDelegated()) {
            return false;
        }
        String buCode = resolveActiveWorkspaceBuCode();
        String roleCode = resolveActiveWorkspaceRoleCode();
        if (!StringUtils.hasText(buCode) || !StringUtils.hasText(roleCode)) {
            return false;
        }
        return buCode.equalsIgnoreCase(extended.getDelegatedBuCode().trim())
                && roleCode.equalsIgnoreCase(extended.getDelegatedRoleCode().trim());
    }

    private String resolveActiveWorkspaceBuCode() {
        return com.workflow.util.WorkflowActorResolver.currentActiveBusinessUnitId()
                .map(id -> adminCenterClient.getBusinessUnitCodeById(id.trim()))
                .filter(StringUtils::hasText)
                .orElse(null);
    }

    private String resolveActiveWorkspaceRoleCode() {
        return com.workflow.util.WorkflowActorResolver.currentActiveRoleId()
                .map(id -> adminCenterClient.getRoleCodeById(id.trim()))
                .filter(StringUtils::hasText)
                .orElse(null);
    }

    boolean flowableRuntimeAuthorizesComplete(Task task, String portalUserId) {
        if (task == null || !StringUtils.hasText(portalUserId)) {
            return false;
        }
        String uid = portalUserId.trim();
        String assignee = task.getAssignee();
        if (StringUtils.hasText(assignee) && taskActionService.engineActorMatchesPortalUser(assignee, uid)) {
            return true;
        }
        for (IdentityLink link : taskService.getIdentityLinksForTask(task.getId())) {
            if (!"candidate".equals(link.getType())) {
                continue;
            }
            if (link.getUserId() != null && StringUtils.hasText(link.getUserId())
                    && taskActionService.engineActorMatchesPortalUser(link.getUserId(), uid)) {
                return true;
            }
            if (link.getGroupId() != null && StringUtils.hasText(link.getGroupId())
                    && userPermissionService.isUserInVirtualGroup(uid, link.getGroupId().trim())) {
                return true;
            }
        }
        return false;
    }

    String resolveInitiatorUserId(String processInstanceId) {
        if (processInstanceId == null) {
            return null;
        }
        try {
            Object v = runtimeService.getVariable(processInstanceId, "initiator");
            if (v != null && StringUtils.hasText(v.toString())) {
                return v.toString().trim();
            }
        } catch (Exception e) {
            log.debug("Could not read initiator variable for process {}: {}", processInstanceId, e.getMessage());
        }
        try {
            ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .singleResult();
            if (pi != null && StringUtils.hasText(pi.getStartUserId())) {
                return pi.getStartUserId().trim();
            }
        } catch (Exception e) {
            log.debug("Could not read startUserId for process {}: {}", processInstanceId, e.getMessage());
        }
        return null;
    }

    private String normalizePortalUserIdForVariable(String actor) {
        if (!StringUtils.hasText(actor)) {
            return actor;
        }
        try {
            Map<String, Object> info = adminCenterClient.getUserInfo(actor.trim());
            if (info != null && info.get("id") != null) {
                return info.get("id").toString().trim();
            }
        } catch (Exception e) {
            // FALLBACK(external): id 规范化失败沿用原始 actor 值（含 AdminCenterUnavailableException），
            // 变量消费方已兼容两种形态。
            log.debug("normalizePortalUserIdForVariable: {}", e.getMessage());
        }
        return actor.trim();
    }

    private void recordReturnTaskComment(String taskId, String processInstanceId, Task currentTask,
                                         String targetActivityId, TaskReturnRequest request) {
        String targetLabel = resolveActivityDisplayName(currentTask.getProcessDefinitionId(), targetActivityId);
        boolean draft = request.isDraftReturn();
        StringBuilder msg = new StringBuilder();
        msg.append(draft ? "Drafted to " : "Returned to ").append(targetLabel);
        if (request.getReason() != null && !request.getReason().isBlank()) {
            msg.append(": ").append(request.getReason().trim());
        }
        String commentType = draft ? "draft" : "return";
        String previousActor = Authentication.getAuthenticatedUserId();
        try {
            Authentication.setAuthenticatedUserId(request.getUserId());
            taskService.addComment(taskId, processInstanceId, commentType, msg.toString());
        } catch (Exception e) {
            log.warn("Failed to record {} comment on task {}: {}", commentType, taskId, e.getMessage());
        } finally {
            Authentication.setAuthenticatedUserId(previousActor);
        }
    }

    private String resolveActivityDisplayName(String processDefinitionId, String activityId) {
        if (!StringUtils.hasText(activityId)) {
            return "previous step";
        }
        try {
            if (StringUtils.hasText(processDefinitionId)) {
                BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
                if (model != null) {
                    FlowElement el = model.getFlowElement(activityId.trim());
                    if (el != null && StringUtils.hasText(el.getName())) {
                        return el.getName().trim();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve activity display name for {}: {}", activityId, e.getMessage());
        }
        return activityId.trim();
    }

    private void publishTaskCompleteEvent(ExtendedTaskInfo task, String userId,
                                          java.util.Map<String, Object> variables,
                                          boolean sendNotification,
                                          String taskDisplayName,
                                          String initiatorUserId,
                                          String processInstanceId,
                                          String flowableTaskId) {
        String tid = task != null ? task.getTaskId() : flowableTaskId;
        log.info("Task completion event: taskId={}, completedBy={}", tid, userId);
        if (!sendNotification || !StringUtils.hasText(initiatorUserId) || initiatorUserId.equals(userId)) {
            return;
        }
        String label = taskDisplayName != null ? taskDisplayName : tid;
        String link = StringUtils.hasText(tid) ? TaskActionService.taskLink(tid) : "/tasks";
        notificationDispatchHelper.publishToUserAfterCommit(
                initiatorUserId,
                "TASK",
                i18nService.getMessage("workflow.notification.completed_title"),
                i18nService.getMessage("workflow.notification.completed_body", userId, label),
                link,
                "workflow-engine");
    }

    private void publishTaskReturnEvent(String taskId, String processInstanceId,
                                        String fromActivityId, String toActivityId,
                                        TaskReturnRequest request) {
        log.info("Task return event: taskId={}, from={}, to={}, userId={}, reason={}",
                taskId, fromActivityId, toActivityId, request.getUserId(), request.getReason());
        if (!request.shouldSendNotification()) {
            return;
        }
        String initiator = resolveInitiatorUserId(processInstanceId);
        if (!StringUtils.hasText(initiator)) {
            return;
        }
        notificationDispatchHelper.publishToUserAfterCommit(
                initiator,
                "PROCESS",
                i18nService.getMessage("workflow.notification.rollback_title"),
                i18nService.getMessage("workflow.notification.rollback_body",
                        processInstanceId,
                        request.getUserId(),
                        fromActivityId,
                        toActivityId,
                        request.getReason() != null ? " " + i18nService.getMessage("workflow.notification.rollback_reason", request.getReason()) : "").trim(),
                "/tasks",
                "workflow-engine");
    }

    private void validateTaskReturnRequest(TaskReturnRequest request) {
        if (request == null) {
            throw new WorkflowValidationException(Collections.singletonList(
                new WorkflowValidationException.ValidationError(
                    "request", "Request parameters cannot be empty", null)));
        }
        if (!StringUtils.hasText(request.getTargetActivityId())) {
            throw new WorkflowValidationException(Collections.singletonList(
                new WorkflowValidationException.ValidationError(
                    "targetActivityId", "Target activity ID cannot be empty", null)));
        }
        if (!StringUtils.hasText(request.getUserId())) {
            throw new WorkflowValidationException(Collections.singletonList(
                new WorkflowValidationException.ValidationError(
                    "userId", "User ID cannot be empty", null)));
        }
    }

    private void validateUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new WorkflowValidationException(Collections.singletonList(
                new WorkflowValidationException.ValidationError(
                    "userId", "User ID cannot be empty", userId)));
        }
    }

    // BpmnActionParser injected lazily to avoid potential circular path through TaskAssignmentListener
    @Lazy
    @Autowired
    private BpmnActionParser _bpmnActionParser;

    private BpmnActionParser bpmnActionParser() {
        return _bpmnActionParser;
    }
}
