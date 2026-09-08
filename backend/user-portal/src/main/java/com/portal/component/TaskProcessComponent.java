package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.dto.TaskCompleteRequest;
import com.portal.dto.TaskDelegateRequest;
import com.portal.dto.TaskInfo;
import com.portal.entity.DelegationAudit;
import com.portal.enums.ChangeType;
import com.portal.exception.PortalException;
import com.portal.repository.DelegationAuditRepository;
import com.portal.service.ProcessAssigneeSnapshot;
import com.portal.util.BuRolePoolTasks;
import com.platform.security.util.SecurityContextUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Task processing component
 * Supports claim, complete, transfer, delegate, and related operations
 *
 * Uses WorkflowEngineClient to call Flowable engine
 *
 * <p>Acts as a facade: permission rules live in {@link TaskPermissionEvaluator}, sub-table row
 * assignment in {@link SubTableRowAssignmentComponent}, approval completion (including MI collection
 * injection via {@link MiCollectionVariableBuilder}) in {@link TaskApprovalCompletionComponent}, and
 * portal ProcessInstance synchronization in {@link ProcessInstanceSyncComponent}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskProcessComponent {

    private final TaskQueryComponent taskQueryComponent;
    private final DelegationAuditRepository delegationAuditRepository;
    private final WorkflowEngineClient workflowEngineClient;
    private final TaskPermissionEvaluator taskPermissionEvaluator;
    private final SubTableRowAssignmentComponent subTableRowAssignmentComponent;
    private final TaskApprovalCompletionComponent taskApprovalCompletionComponent;
    private final ProcessInstanceSyncComponent processInstanceSyncComponent;
    private final MiOverlayComponent miOverlayComponent;
    private final ClaimForceUnclaimAnnotator claimForceUnclaimAnnotator;
    private final TaskAssignmentHistoryRecorder taskAssignmentHistoryRecorder;

    /**
     * Claims task
     * Via WorkflowEngineClient calling Flowable engine
     */
    @Transactional
    public TaskInfo claimTask(String taskId, String userId) {
        return claimTask(taskId, userId, null);
    }

    /**
     * Claims task when JWT userId differs from Flowable assignee/candidate username.
     */
    @Transactional
    public TaskInfo claimTask(String taskId, String userId, String portalUsername) {
        if (!workflowEngineClient.isAvailable()) {
            throw new IllegalStateException("Flowable engine unavailable, please check if workflow-engine-core service is running");
        }

        TaskInfo taskBefore = getTaskOrThrow(taskId);
        // Hold is enforced portal-side for the BU Role pool: Flowable's claim overwrites the assignee,
        // so without this a second member would silently take a request a colleague is editing.
        // Other pool types keep the engine as the sole authority, as before.
        if (BuRolePoolTasks.isClaimPoolTask(taskBefore)
                && !taskPermissionEvaluator.canClaimTask(taskBefore, userId, portalUsername)) {
            throw new PortalException("403", "You are not allowed to claim this task");
        }
        String enginePrincipal = TaskPermissionEvaluator.resolveEnginePrincipalForWorkflow(taskBefore, userId, portalUsername);

        log.info("Using Flowable engine to claim task: {} by engine principal: {} (portal userId: {})", taskId, enginePrincipal, userId);
        Optional<Map<String, Object>> result = workflowEngineClient.claimTask(taskId, enginePrincipal);

        if (result.isEmpty()) {
            throw new PortalException("500", "Failed to claim task: " + taskId);
        }

        Map<String, Object> data = result.get();
        if (!Boolean.TRUE.equals(data.get("success"))) {
            String message = data.get("message") != null ? (String) data.get("message") : "Failed to claim task";
            throw new PortalException("500", message);
        }

        // Task updated in Flowable; reload latest state
        TaskInfo task = getTaskOrThrow(taskId);

        // Update process instance current assignee (portal stores JWT userId)
        processInstanceSyncComponent.updateProcessInstanceAssignee(task.getProcessInstanceId(), userId, null, task.getTaskName());

        taskQueryComponent.invalidateMineTaskListCache();
        taskAssignmentHistoryRecorder.record(taskBefore, userId, ChangeType.CLAIM, userId);
        log.info("Task {} claimed via Flowable by user {}", taskId, userId);
        return task;
    }

    /**
     * Unclaims task
     * Via WorkflowEngineClient calling Flowable engine
     */
    @Transactional
    public TaskInfo unclaimTask(String taskId, String userId, String originalAssignmentType, String originalAssignee) {
        return unclaimTask(taskId, userId, originalAssignmentType, originalAssignee, null);
    }

    @Transactional
    public TaskInfo unclaimTask(String taskId, String userId, String originalAssignmentType, String originalAssignee,
                                String portalUsername) {
        if (!workflowEngineClient.isAvailable()) {
            throw new IllegalStateException("Flowable engine unavailable, please check if workflow-engine-core service is running");
        }

        TaskInfo taskBefore = getTaskOrThrow(taskId);
        boolean holder = taskPermissionEvaluator.isHeldByUser(taskBefore, userId, portalUsername);
        if (BuRolePoolTasks.isClaimPoolTask(taskBefore) && !holder
                && !claimForceUnclaimAnnotator.canForceUnclaim(taskBefore, userId)) {
            throw new PortalException("403", "Only the user holding this task can unclaim it");
        }
        if (BuRolePoolTasks.isClaimPoolTask(taskBefore) && !holder) {
            log.info("Force unclaim of task {} by {} (holder was {})",
                    taskId, userId, taskBefore.getAssignee());
        }
        String enginePrincipal = TaskPermissionEvaluator.resolveEnginePrincipalForWorkflow(taskBefore, userId, portalUsername);

        log.info("Using Flowable engine to unclaim task: {} by engine principal: {} (portal userId: {})", taskId, enginePrincipal, userId);
        Optional<Map<String, Object>> result = workflowEngineClient.unclaimTask(taskId, enginePrincipal);

        if (result.isEmpty()) {
            throw new PortalException("500", "Failed to unclaim task: " + taskId);
        }

        Map<String, Object> data = result.get();
        if (!Boolean.TRUE.equals(data.get("success"))) {
            String message = data.get("message") != null ? (String) data.get("message") : "Failed to unclaim task";
            throw new PortalException("500", message);
        }

        // Task updated in Flowable; reload latest state
        TaskInfo task = getTaskOrThrow(taskId);

        // After unclaim, restore process instance assignee from task snapshot
        ProcessAssigneeSnapshot snapshot = ProcessAssigneeSnapshot.fromTaskInfo(task);
        processInstanceSyncComponent.updateProcessInstanceAssignee(
                task.getProcessInstanceId(),
                snapshot.getAssigneeUserId(),
                snapshot.getCandidateUserIds(),
                task.getTaskName());

        taskQueryComponent.invalidateMineTaskListCache();
        boolean force = BuRolePoolTasks.isClaimPoolTask(taskBefore) && !holder;
        taskAssignmentHistoryRecorder.record(taskBefore, userId,
                force ? ChangeType.FORCE_UNCLAIM : ChangeType.UNCLAIM, null);
        log.info("Task {} unclaimed via Flowable by user {}", taskId, userId);
        return task;
    }

    /**
     * Completes task
     */
    @Transactional
    public void completeTask(TaskCompleteRequest request, String userId) {
        completeTask(request, userId, null);
    }

    @Transactional
    public void completeTask(TaskCompleteRequest request, String userId, String portalUsername) {
        String taskId = request.getTaskId();
        TaskInfo task = getTaskOrThrow(taskId);
        if (TaskPermissionEvaluator.isTaskAlreadyClosedInEngineView(task)) {
            throw new PortalException("409",
                    "This task is no longer active (it may already be completed). Please refresh your todo list.");
        }

        // Verify user may process task
        if (!canProcessTask(task, userId, portalUsername)) {
            throw new PortalException("403", "You do not have permission to process this task");
        }

        // Auto-claim: virtual group or candidate pool without assignee (skip empty pool; claim fails without identity links).
        // BU Role pools are excluded on purpose: claiming there is an explicit user action, and
        // auto-claiming on submit would let a second member overwrite the holder's work.
        boolean poolStyle = !BuRolePoolTasks.isClaimPoolTask(task)
                && ("VIRTUAL_GROUP".equals(task.getAssignmentType()) || "CANDIDATE_USERS".equals(task.getAssignmentType())
                || "DEPT_ROLE".equals(task.getAssignmentType()));
        boolean noAssignee = task.getAssignee() == null || task.getAssignee().isEmpty();
        boolean poolAutoClaimed = false;
        if (poolStyle && noAssignee && !TaskPermissionEvaluator.isEmptyAssignmentPool(task)) {
            log.info("Auto-claiming pool task {} (type {}) for user {}", taskId, task.getAssignmentType(), userId);
            poolAutoClaimed = true;
            claimTask(taskId, userId, portalUsername);
            task = getTaskOrThrow(taskId); // Refresh task after claim
        } else if (poolStyle && noAssignee && TaskPermissionEvaluator.isEmptyAssignmentPool(task)) {
            log.info("Skipping auto-claim for empty-pool task {} (no assignee/target/candidates); completing without claim", taskId);
        }

        String action = request.getAction();
        switch (action) {
            case "APPROVE", "REJECT" ->
                    taskApprovalCompletionComponent.handleApproval(task, request, userId, portalUsername);
            case "TRANSFER" -> handleTransfer(task, request, userId);
            case "DELEGATE" -> handleDelegate(task, request, userId);
            case "RETURN" -> handleReturn(task, request, userId, "RETURN");
            case "DRAFT" -> handleReturn(task, request, userId, "DRAFT");
            default -> throw new PortalException("400", "Unsupported action type: " + action);
        }

        // Completing/transferring/returning a task changes sub-task task_status; drop the MI status
        // cache so the next My Request / process detail load reflects it immediately (otherwise the
        // 5s TTL serves a stale snapshot and the page needs two refreshes to update).
        miOverlayComponent.invalidate(task.getProcessInstanceId());
    }

    /**
     * Delegates task
     * Via WorkflowEngineClient calling Flowable engine
     */
    @Transactional
    public void delegateTask(String taskId, String delegatorId, String delegateId, String reason) {
        TaskDelegateRequest body = TaskDelegateRequest.builder()
                .delegatedTargetType("USER")
                .delegatedTo(delegateId)
                .reason(reason)
                .build();
        delegateTask(taskId, delegatorId, body);
    }

    @Transactional
    public void delegateTask(String taskId, String delegatorId, TaskDelegateRequest body) {
        if (!workflowEngineClient.isAvailable()) {
            throw new IllegalStateException("Flowable engine unavailable, please check if workflow-engine-core service is running");
        }
        if (body == null) {
            throw new PortalException("400", "Delegate request cannot be empty");
        }
        Map<String, Object> engineBody = new java.util.HashMap<>();
        engineBody.put("delegatedBy", delegatorId);
        engineBody.put("taskId", taskId);
        engineBody.put("delegationReason", body.getReason());
        String type = body.getDelegatedTargetType() != null ? body.getDelegatedTargetType().trim() : "USER";
        engineBody.put("delegatedTargetType", type);
        if ("BU_ROLE".equalsIgnoreCase(type)) {
            if (body.getDelegatedBuCode() == null || body.getDelegatedBuCode().isBlank()
                    || body.getDelegatedRoleCode() == null || body.getDelegatedRoleCode().isBlank()) {
                throw new PortalException("400", "Business unit and role are both required");
            }
            engineBody.put("delegatedBuCode", body.getDelegatedBuCode().trim());
            engineBody.put("delegatedRoleCode", body.getDelegatedRoleCode().trim());
        } else {
            if (body.getDelegatedTo() == null || body.getDelegatedTo().isBlank()) {
                throw new PortalException("400", "Delegate target user cannot be empty");
            }
            if (body.getDelegatedTo().trim().equals(delegatorId)) {
                throw new PortalException("400", "Cannot delegate to yourself");
            }
            engineBody.put("delegatedTo", body.getDelegatedTo().trim());
        }

        log.info("Using Flowable engine to delegate task: {} by {}", taskId, delegatorId);
        Optional<Map<String, Object>> result = workflowEngineClient.delegateTask(taskId, engineBody);

        if (result.isEmpty()) {
            throw new PortalException("500", "Failed to delegate task: " + taskId);
        }

        Map<String, Object> data = result.get();
        if (!Boolean.TRUE.equals(data.get("success"))) {
            String message = data.get("message") != null ? (String) data.get("message") : "Failed to delegate task";
            throw new PortalException("500", message);
        }

        DelegationAudit audit = DelegationAudit.builder()
                .delegatorId(delegatorId)
                .delegateId("BU_ROLE".equalsIgnoreCase(type)
                        ? type + ":" + body.getDelegatedBuCode() + "/" + body.getDelegatedRoleCode()
                        : body.getDelegatedTo())
                .taskId(taskId)
                .operationType("DELEGATE_TASK")
                .operationResult("SUCCESS")
                .operationDetail(body.getReason())
                .build();
        delegationAuditRepository.save(audit);

        log.info("Task {} delegated via Flowable by {} (type={})", taskId, delegatorId, type);
    }

    /**
     * Transfers task
     * Via WorkflowEngineClient calling Flowable engine
     */
    @Transactional
    public void transferTask(String taskId, String fromUserId, String toUserId, String reason) {
        if (!workflowEngineClient.isAvailable()) {
            throw new IllegalStateException("Flowable engine unavailable, please check if workflow-engine-core service is running");
        }

        log.info("Using Flowable engine to transfer task: {} from {} to {}", taskId, fromUserId, toUserId);
        Optional<Map<String, Object>> result = workflowEngineClient.transferTask(taskId, fromUserId, toUserId, reason);

        if (result.isEmpty()) {
            throw new PortalException("500", "Failed to transfer task: " + taskId);
        }

        Map<String, Object> data = result.get();
        if (!Boolean.TRUE.equals(data.get("success"))) {
            String message = data.get("message") != null ? (String) data.get("message") : "Failed to transfer task";
            throw new PortalException("500", message);
        }

        // Update process instance current assignee
        TaskInfo task = getTaskOrThrow(taskId);
        processInstanceSyncComponent.updateProcessInstanceAssignee(task.getProcessInstanceId(), toUserId, null, task.getTaskName());

        // Record audit log
        DelegationAudit audit = DelegationAudit.builder()
                .delegatorId(fromUserId)
                .delegateId(toUserId)
                .taskId(taskId)
                .operationType("TRANSFER_TASK")
                .operationResult("SUCCESS")
                .operationDetail(reason)
                .build();
        delegationAuditRepository.save(audit);

        log.info("Task {} transferred via Flowable from {} to {}", taskId, fromUserId, toUserId);
    }

    /**
     * Assigns sub-table row processor (MI sub-process prerequisite) via {@link WorkflowEngineClient}.
     * Forwards to {@link SubTableRowAssignmentComponent}.
     */
    @Transactional
    public Map<String, Object> assignSubTableRow(String taskId, Long rowId, String assigneeId, String userId) {
        return subTableRowAssignmentComponent.assignSubTableRow(taskId, rowId, assigneeId, userId);
    }

    @Transactional
    public Map<String, Object> assignSubTableRow(String taskId, Long rowId, String assigneeId, String userId,
                                                 String portalUsername) {
        return subTableRowAssignmentComponent.assignSubTableRow(taskId, rowId, assigneeId, userId, portalUsername);
    }

    @Transactional
    public Map<String, Object> assignSubTableRow(String taskId, Long rowId, Map<String, Object> rowKey, String assigneeId,
                                                 String userId, String portalUsername) {
        return subTableRowAssignmentComponent.assignSubTableRow(taskId, rowId, rowKey, assigneeId, userId, portalUsername);
    }

    /**
     * Whether the user may claim the task
     * Forwards to {@link TaskPermissionEvaluator}.
     */
    public boolean canClaimTask(TaskInfo task, String userId) {
        return taskPermissionEvaluator.canClaimTask(task, userId);
    }

    public boolean canClaimTask(TaskInfo task, String userId, String portalUsername) {
        return taskPermissionEvaluator.canClaimTask(task, userId, portalUsername);
    }

    /**
     * Whether the user may process the task
     * Forwards to {@link TaskPermissionEvaluator}.
     */
    public boolean canProcessTask(TaskInfo task, String userId) {
        return taskPermissionEvaluator.canProcessTask(task, userId);
    }

    public boolean canProcessTask(TaskInfo task, String userId, String portalUsername) {
        return taskPermissionEvaluator.canProcessTask(task, userId, portalUsername);
    }

    /**
     * Todo-list filter: hide initiator on empty pool when BPMN is not INITIATOR/PROCESS_INITIATOR.
     * Forwards to {@link TaskPermissionEvaluator#shouldHideTaskInTodoList}.
     */
    public boolean shouldHideTaskInTodoList(TaskInfo task, String userId, String portalUsername) {
        return taskPermissionEvaluator.shouldHideTaskInTodoList(task, userId, portalUsername);
    }

    /**
     * Whether task form is viewable (todo/done snapshot): processor rules + initiator + current assignee (including done tasks).
     * Forwards to {@link TaskPermissionEvaluator}.
     */
    public boolean canViewTaskForm(TaskInfo task, String userId) {
        return taskPermissionEvaluator.canViewTaskForm(task, userId);
    }

    public boolean canViewTaskForm(TaskInfo task, String userId, String portalUsername) {
        return taskPermissionEvaluator.canViewTaskForm(task, userId, portalUsername);
    }

    /**
     * Fills the BU Role claim flags on a task.
     * Forwards to {@link TaskPermissionEvaluator#annotateClaimState(TaskInfo, String, String)}.
     */
    public void annotateClaimState(TaskInfo task, String userId, String portalUsername) {
        taskPermissionEvaluator.annotateClaimState(task, userId, portalUsername);
        claimForceUnclaimAnnotator.annotate(task, userId);
    }

    /**
     * Loads task or throws.
     */
    TaskInfo getTaskOrThrow(String taskId) {
        Optional<TaskInfo> first = taskQueryComponent.getTaskById(taskId);
        if (first.isPresent()) {
            return first.get();
        }
        Optional<TaskInfo> second = taskQueryComponent.getTaskById(taskId);
        if (second.isPresent()) {
            return second.get();
        }
        throw new PortalException("404", "Task not found: " + taskId);
    }

    /**
     * Handles transfer action
     */
    private void handleTransfer(TaskInfo task, TaskCompleteRequest request, String userId) {
        String targetUserId = request.getTargetUserId();
        if (targetUserId == null || targetUserId.isEmpty()) {
            throw new PortalException("400", "Transfer target user cannot be empty");
        }
        transferTask(task.getTaskId(), userId, targetUserId, request.getComment());
    }

    /**
     * Handles delegate action
     */
    private void handleDelegate(TaskInfo task, TaskCompleteRequest request, String userId) {
        String targetUserId = request.getTargetUserId();
        if (targetUserId == null || targetUserId.isEmpty()) {
            throw new PortalException("400", "Delegate target user cannot be empty");
        }
        delegateTask(task.getTaskId(), userId, targetUserId, request.getComment());
    }

    /**
     * Handles return (rollback) action
     * Via WorkflowEngineClient calling Flowable engine
     */
    private void handleReturn(TaskInfo task, TaskCompleteRequest request, String userId, String returnKind) {
        String taskId = task.getTaskId();
        String targetActivityId = request.getReturnActivityId();

        if (targetActivityId == null || targetActivityId.isEmpty()) {
            throw new PortalException("400", "Return target activity cannot be empty");
        }

        if (!workflowEngineClient.isAvailable()) {
            throw new IllegalStateException("Flowable engine unavailable, please check if workflow-engine-core service is running");
        }
        log.info("Using Flowable engine to return task: {} to activity: {} (kind={})", taskId, targetActivityId, returnKind);
        Optional<Map<String, Object>> result = workflowEngineClient.returnTask(
            taskId, targetActivityId, userId, request.getComment(), returnKind);
        if (result.isEmpty()) {
            throw new PortalException("500", "Failed to return task: " + taskId);
        }

        Map<String, Object> data = result.get();
        if (!Boolean.TRUE.equals(data.get("success"))) {
            String message = data.get("message") != null ? (String) data.get("message") : "Failed to return task";
            throw new PortalException("500", message);
        }

        // Record audit log
        String auditOp = "DRAFT".equals(returnKind) ? "DRAFT_TASK" : "RETURN_TASK";
        DelegationAudit audit = DelegationAudit.builder()
                .delegatorId(userId)
                .delegateId(targetActivityId)
                .taskId(taskId)
                .operationType(auditOp)
                .operationResult("SUCCESS")
                .operationDetail(request.getComment())
                .build();
        delegationAuditRepository.save(audit);

        log.info("Task {} returned via Flowable to activity {} (kind={}) by user {}", taskId, targetActivityId, returnKind, userId);
    }

    /**
     * Urges a task
     */
    @Transactional
    public void urgeTask(String taskId, String urgerId, String message) {
        TaskInfo task = getTaskOrThrow(taskId);

        // Verify urger permission (usually initiator or admin)
        if (!canUrgeTask(task, urgerId)) {
            throw new PortalException("403", "You do not have permission to urge this task");
        }

        // Resolve task assignee
        String assignee = task.getAssignee();
        String assigneeName = task.getAssigneeName();

        // Send urge notification (should call messaging service)
        String urgeMessage = message != null ? message : "Please process the task as soon as possible: " + task.getTaskName();
        sendUrgeNotification(taskId, assignee, urgerId, urgeMessage);

        // Record urge audit log
        DelegationAudit audit = DelegationAudit.builder()
                .delegatorId(urgerId)
                .delegateId(assignee)
                .taskId(taskId)
                .operationType("URGE_TASK")
                .operationResult("SUCCESS")
                .operationDetail(urgeMessage)
                .build();
        delegationAuditRepository.save(audit);

        log.info("User {} urged task {}, assignee: {}", urgerId, taskId, assignee);
    }

    /**
     * Batch urge tasks
     */
    @Transactional
    public void batchUrgeTasks(List<String> taskIds, String urgerId, String message) {
        for (String taskId : taskIds) {
            try {
                urgeTask(taskId, urgerId, message);
            } catch (Exception e) {
                log.warn("Failed to urge task {}: {}", taskId, e.getMessage());
            }
        }
    }

    /**
     * Whether the user may urge the task
     */
    private boolean canUrgeTask(TaskInfo task, String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        if (task.getInitiatorId() != null && userId.equals(task.getInitiatorId())) {
            return true;
        }
        return SecurityContextUtils.isSuperAdmin();
    }

    /**
     * Sends urge notification
     */
    private void sendUrgeNotification(String taskId, String assignee, String urgerId, String message) {
        // Should invoke messaging service in production
        // Log only for now
        log.info("Sending urge notification: task={}, assignee={}, urger={}, message={}", taskId, assignee, urgerId, message);
    }
}
