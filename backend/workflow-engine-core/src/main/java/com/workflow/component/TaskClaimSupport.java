package com.workflow.component;

import com.workflow.client.AdminCenterClient;
import com.workflow.dto.request.TaskClaimRequest;
import com.workflow.dto.response.TaskAssignmentResult;
import com.workflow.entity.ExtendedTaskInfo;
import com.workflow.enums.AssignmentType;
import com.workflow.exception.WorkflowBusinessException;
import com.workflow.exception.WorkflowValidationException;
import com.workflow.repository.ExtendedTaskInfoRepository;
import com.workflow.service.UserPermissionService;
import com.workflow.util.FlowableCandidateUsers;

import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Claim / unclaim write path, including stale USER vs Flowable candidate-pool repair.
 */
@Slf4j
@Component
@Transactional
public class TaskClaimSupport {

    @Autowired
    private TaskService taskService;

    @Autowired
    private ExtendedTaskInfoRepository extendedTaskInfoRepository;

    @Autowired
    private UserPermissionService userPermissionService;

    @Autowired
    private AdminCenterClient adminCenterClient;

    @Autowired
    private BpmnActionParser bpmnActionParser;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskOrphanRepairService taskOrphanRepairService;

    public TaskAssignmentResult claimTask(String taskId, TaskClaimRequest request) {
        try {
            Task flowableTask = requireTask(taskId);
            if (flowableTask.getAssignee() != null && !flowableTask.getAssignee().isEmpty()) {
                throw validation("taskId", "Task already claimed", taskId);
            }
            Optional<ExtendedTaskInfo> extendedOpt = extendedTaskInfoRepository
                    .findByTaskIdAndIsDeletedFalse(taskId);
            if (extendedOpt.isPresent()) {
                ExtendedTaskInfo extended = extendedOpt.get();
                validateClaimPermission(extended, request.getClaimedBy(), flowableTask);
                if (extended.isCompleted()) {
                    throw validation("taskId", "Task already completed, cannot claim", taskId);
                }
                extended.claimTask(request.getClaimedBy());
                extendedTaskInfoRepository.save(extended);
                log.info("Task claim event: taskId={}, claimedBy={}", taskId, request.getClaimedBy());
            }
            taskService.claim(taskId, request.getClaimedBy());
            return TaskAssignmentResult.success(
                    taskId, AssignmentType.USER, request.getClaimedBy(), request.getClaimedBy(),
                    "Task claimed successfully");
        } catch (WorkflowValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkflowBusinessException("TASK_CLAIM_ERROR",
                    "Task claim failed: " + e.getMessage(), e);
        }
    }

    public TaskAssignmentResult reassignClaim(String taskId, String operatorUserId, String targetUserId) {
        try {
            String target = requireText(targetUserId, "targetUserId", "Target user is required");
            Task flowableTask = requireTask(taskId);
            if (!actorMayForceUnclaim(flowableTask, operatorUserId)) {
                throw validation("userId", "Not allowed to reassign this task", operatorUserId);
            }
            List<String> candidates = FlowableCandidateUsers.userIds(taskService, taskId);
            validateReassignTarget(flowableTask, taskId, target, candidates);
            return applyReassignHold(taskId, operatorUserId, target, candidates);
        } catch (WorkflowValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkflowBusinessException("TASK_REASSIGN_ERROR",
                    "Task reassign failed: " + e.getMessage(), e);
        }
    }

    public TaskAssignmentResult unclaimTask(String taskId, String userId) {
        try {
            Task flowableTask = requireTask(taskId);
            String assignee = flowableTask.getAssignee();
            if (assignee == null || assignee.isEmpty()) {
                throw validation("taskId", "Task not claimed", taskId);
            }
            boolean matchesAssignee = engineActorMatchesPortalUser(assignee, userId);
            if (!matchesAssignee && !actorMayForceUnclaim(flowableTask, userId)) {
                throw validation("userId", "Only assignee can unclaim", userId);
            }
            Optional<ExtendedTaskInfo> extendedOpt = extendedTaskInfoRepository
                    .findByTaskIdAndIsDeletedFalse(taskId);
            AssignmentType resultType = AssignmentType.CANDIDATE_USERS;
            String resultTarget = null;
            ExtendedTaskInfo extended = null;
            if (extendedOpt.isPresent()) {
                extended = extendedOpt.get();
                if (extended.isCompleted()) {
                    throw validation("taskId", "Task already completed, cannot unclaim", taskId);
                }
                if (extended.isClaimed() && extended.getClaimedBy() != null
                        && !engineActorMatchesPortalUser(extended.getClaimedBy(), userId)
                        && matchesAssignee
                        && !actorMayForceUnclaim(flowableTask, userId)) {
                    throw validation("userId", "Only the claimer can unclaim", userId);
                }
                if (extended.isClaimed()) {
                    extended.unclaimTask();
                    extendedTaskInfoRepository.save(extended);
                }
                resultType = extended.getAssignmentType();
                resultTarget = extended.getAssignmentTarget();
            }
            taskService.unclaim(taskId);
            taskOrphanRepairService.restoreBuRoleClaimPool(flowableTask);
            if (extended != null) {
                String poolTarget = persistCandidatePoolFromFlowable(extended, taskId);
                if (poolTarget != null) {
                    resultType = AssignmentType.CANDIDATE_USERS;
                    resultTarget = poolTarget;
                }
            }
            return TaskAssignmentResult.success(
                    taskId, resultType, resultTarget, userId, "Task unclaimed successfully");
        } catch (WorkflowValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkflowBusinessException("TASK_UNCLAIM_ERROR",
                    "Task unclaim failed: " + e.getMessage(), e);
        }
    }

    boolean engineActorMatchesPortalUser(String engineSideActor, String portalUserId) {
        if (!StringUtils.hasText(engineSideActor) || !StringUtils.hasText(portalUserId)) {
            return false;
        }
        String a = engineSideActor.trim();
        String p = portalUserId.trim();
        if (a.equals(p)) {
            return true;
        }
        try {
            Map<String, Object> info = adminCenterClient.getUserInfo(p);
            if (info != null) {
                Object id = info.get("id");
                if (id != null && a.equals(id.toString().trim())) {
                    return true;
                }
                Object username = info.get("username");
                if (username != null && a.equals(username.toString().trim())) {
                    return true;
                }
            }
        } catch (Exception e) {
            // FALLBACK(external): 身份比对失败降级为不匹配（保守方向:拒绝而非放行）。
            log.debug("engineActorMatchesPortalUser: {}", e.getMessage());
        }
        return false;
    }

    private void validateClaimPermission(ExtendedTaskInfo task, String claimedBy, Task flowableTask) {
        List<String> candidates = FlowableCandidateUsers.userIds(taskService, flowableTask.getId());
        boolean unassignedPool = !StringUtils.hasText(flowableTask.getAssignee()) && !candidates.isEmpty();
        if (unassignedPool && task.getAssignmentType() != AssignmentType.VIRTUAL_GROUP) {
            if (!actorInCandidatePool(candidates, claimedBy)) {
                throw validation("claimedBy", "User does not have permission to claim this task", claimedBy);
            }
            rememberCandidatePool(task, candidates);
            return;
        }
        if (task.getAssignmentType() == AssignmentType.USER) {
            throw validation("taskId", "Directly assigned tasks cannot be claimed", task.getTaskId());
        }
        boolean hasPermission = userPermissionService.hasTaskPermission(
                claimedBy, task.getAssignmentType(), task.getAssignmentTarget());
        if (!hasPermission) {
            throw validation("claimedBy", "User does not have permission to claim this task", claimedBy);
        }
    }

    private boolean actorInCandidatePool(List<String> candidateUserIds, String actorId) {
        for (String candidateId : candidateUserIds) {
            if (engineActorMatchesPortalUser(candidateId, actorId)) {
                return true;
            }
        }
        return false;
    }

    private static void rememberCandidatePool(ExtendedTaskInfo task, List<String> candidateUserIds) {
        task.setAssignmentType(AssignmentType.CANDIDATE_USERS);
        task.setAssignmentTarget(String.join(",", candidateUserIds));
    }

    private TaskAssignmentResult applyReassignHold(String taskId, String operatorUserId, String target,
            List<String> candidates) {
        AssignmentType resultType = AssignmentType.CANDIDATE_USERS;
        String resultTarget = String.join(",", candidates);
        Optional<ExtendedTaskInfo> extendedOpt = extendedTaskInfoRepository.findByTaskIdAndIsDeletedFalse(taskId);
        if (extendedOpt.isPresent()) {
            ExtendedTaskInfo extended = extendedOpt.get();
            if (extended.isCompleted()) {
                throw validation("taskId", "Task already completed, cannot reassign", taskId);
            }
            rememberPoolUnlessVirtualGroup(extended, candidates);
            extended.claimTask(target);
            extendedTaskInfoRepository.save(extended);
            resultType = extended.getAssignmentType();
            resultTarget = extended.getAssignmentTarget();
        }
        taskService.setAssignee(taskId, target);
        log.info("Task reassign-claim: taskId={}, operator={}, target={}", taskId, operatorUserId, target);
        return TaskAssignmentResult.success(
                taskId, resultType, resultTarget, operatorUserId, "Task reassigned successfully");
    }

    private void validateReassignTarget(Task flowableTask, String taskId, String target,
            List<String> candidates) {
        String current = flowableTask.getAssignee();
        if (engineActorMatchesPortalUser(current, target)) {
            throw validation("targetUserId", "Task already held by target", target);
        }
        if (!candidates.isEmpty()) {
            if (!actorInCandidatePool(candidates, target)) {
                throw validation("targetUserId", "Target is not in the claim pool", target);
            }
            return;
        }
        Optional<ExtendedTaskInfo> extendedOpt = extendedTaskInfoRepository.findByTaskIdAndIsDeletedFalse(taskId);
        if (extendedOpt.isPresent()
                && extendedOpt.get().getAssignmentType() == AssignmentType.VIRTUAL_GROUP
                && userPermissionService.hasTaskPermission(
                        target, AssignmentType.VIRTUAL_GROUP, extendedOpt.get().getAssignmentTarget())) {
            return;
        }
        throw validation("taskId", "Directly assigned tasks cannot be reassigned in the claim pool", taskId);
    }

    private void rememberPoolUnlessVirtualGroup(ExtendedTaskInfo task, List<String> candidates) {
        if (task.getAssignmentType() == AssignmentType.VIRTUAL_GROUP || candidates.isEmpty()) {
            return;
        }
        rememberCandidatePool(task, candidates);
    }

    private static String requireText(String value, String field, String message) {
        if (!StringUtils.hasText(value)) {
            throw validation(field, message, value);
        }
        return value.trim();
    }

    private String persistCandidatePoolFromFlowable(ExtendedTaskInfo task, String taskId) {
        if (task.getAssignmentType() == AssignmentType.VIRTUAL_GROUP) {
            return null;
        }
        List<String> candidates = FlowableCandidateUsers.userIds(taskService, taskId);
        if (candidates.isEmpty()) {
            return null;
        }
        rememberCandidatePool(task, candidates);
        extendedTaskInfoRepository.save(task);
        return task.getAssignmentTarget();
    }

    private boolean actorMayForceUnclaim(Task flowableTask, String userId) {
        String businessUnitId = null;
        List<String> roleIds = List.of();
        try {
            String pdId = flowableTask.getProcessDefinitionId();
            String defKey = flowableTask.getTaskDefinitionKey();
            if (pdId != null && defKey != null) {
                String rawBu = bpmnActionParser.getUserTaskExtensionPropertyValue(pdId, defKey, "businessUnitId");
                if (StringUtils.hasText(rawBu)) {
                    businessUnitId = rawBu.trim();
                }
                roleIds = com.workflow.util.AssigneeRoleIdsSupport.parseRoleIds(
                        bpmnActionParser.getUserTaskExtensionPropertyValue(pdId, defKey, "roleIds"),
                        bpmnActionParser.getUserTaskExtensionPropertyValue(pdId, defKey, "roleId"));
            }
            if (!StringUtils.hasText(businessUnitId) && flowableTask.getProcessInstanceId() != null) {
                Map<String, Object> vars = runtimeService.getVariables(flowableTask.getProcessInstanceId());
                Object bu = vars.get("businessUnitId");
                if (bu == null) {
                    bu = vars.get("activeBusinessUnitId");
                }
                if (bu != null && StringUtils.hasText(bu.toString())) {
                    businessUnitId = bu.toString().trim();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve claim-pool identity for force unclaim {}: {}",
                    flowableTask.getId(), e.getMessage());
        }
        return adminCenterClient.canForceUnclaim(userId, flowableTask.getId(), businessUnitId, roleIds);
    }

    private Task requireTask(String taskId) {
        Task flowableTask = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (flowableTask == null) {
            throw validation("taskId", "Task not found", taskId);
        }
        return flowableTask;
    }

    private static WorkflowValidationException validation(String field, String message, String rejected) {
        return new WorkflowValidationException(Collections.singletonList(
                new WorkflowValidationException.ValidationError(field, message, rejected)));
    }
}
