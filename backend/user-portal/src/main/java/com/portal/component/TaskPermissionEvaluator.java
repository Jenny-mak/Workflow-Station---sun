package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.dto.TaskInfo;
import com.portal.util.BuRolePoolTasks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Task permission rules: claim/process/view eligibility, portal-vs-engine identity matching,
 * empty-pool and initiator fallback handling.
 * Extracted from {@link TaskProcessComponent} (which keeps same-name public forwards).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskPermissionEvaluator {

    private final DelegationRuleMatcher delegationRuleMatcher;
    private final WorkflowEngineClient workflowEngineClient;
    private final WorkspaceTaskFilterComponent workspaceTaskFilterComponent;

    /**
     * Runtime empty pool: no assignee, candidates, groups, or assignmentTarget (matches Flowable identity links); do not tighten by assignmentType.
     */
    static boolean isEmptyAssignmentPool(TaskInfo task) {
        if (task == null) {
            return false;
        }
        boolean noAssignee = task.getAssignee() == null || task.getAssignee().isBlank();
        if (!noAssignee) {
            return false;
        }
        boolean noUsers = task.getCandidateUserIds() == null || task.getCandidateUserIds().isEmpty();
        boolean noGroups = task.getCandidateGroupIds() == null || task.getCandidateGroupIds().isEmpty();
        boolean noTarget = task.getAssignmentTarget() == null || task.getAssignmentTarget().isBlank();
        return noUsers && noGroups && noTarget;
    }

    private static boolean isInitiatorOfTask(TaskInfo task, String userId, String portalUsername) {
        if (task == null || userId == null) {
            return false;
        }
        if (samePortalUserId(userId, task.getInitiatorId())) {
            return true;
        }
        if (task.getVariables() != null) {
            Object iv = task.getVariables().get("initiator");
            if (iv != null) {
                return matchesPortalIdentity(iv.toString(), userId, portalUsername);
            }
        }
        return false;
    }

    /**
     * Compare JWT and engine user IDs with trim to avoid whitespace false negatives.
     */
    private static boolean samePortalUserId(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().equals(b.trim());
    }

    /**
     * Engine assignee/candidates may be username; JWT {@code userId} is primary-key UUID.
     */
    private static boolean matchesPortalIdentity(String engineSideRef, String portalUserId, String portalUsername) {
        if (engineSideRef == null || engineSideRef.isBlank() || portalUserId == null) {
            return false;
        }
        if (samePortalUserId(portalUserId, engineSideRef)) {
            return true;
        }
        if (portalUsername != null && !portalUsername.isBlank()
                && portalUsername.trim().equals(engineSideRef.trim())) {
            return true;
        }
        return false;
    }

    /**
     * Claim/unclaim must pass the same string as Flowable IdentityLink (candidates often username).
     */
    static String resolveEnginePrincipalForWorkflow(TaskInfo task, String portalUserId, String portalUsername) {
        if (portalUserId == null || portalUserId.isBlank()) {
            return portalUserId != null ? portalUserId.trim() : "";
        }
        String pu = portalUserId.trim();
        String puName = portalUsername != null ? portalUsername.trim() : "";

        String assignee = task.getAssignee();
        if (assignee != null && !assignee.isBlank() && matchesPortalIdentity(assignee, portalUserId, portalUsername)) {
            return assignee.trim();
        }
        List<String> candidates = task.getCandidateUserIds();
        if (candidates != null) {
            for (String c : candidates) {
                if (c == null || c.isBlank()) {
                    continue;
                }
                if (pu.equals(c.trim())) {
                    return c.trim();
                }
            }
            if (!puName.isEmpty()) {
                for (String c : candidates) {
                    if (c != null && puName.equals(c.trim())) {
                        return c.trim();
                    }
                }
            }
        }
        return pu;
    }

    private static boolean candidateUserIdsContain(List<String> candidateUserIds, String userId, String portalUsername) {
        if (candidateUserIds == null || userId == null) {
            return false;
        }
        for (String id : candidateUserIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            if (matchesPortalIdentity(id.trim(), userId, portalUsername)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the user may claim the task
     */
    public boolean canClaimTask(TaskInfo task, String userId) {
        return canClaimTask(task, userId, null);
    }

    public boolean canClaimTask(TaskInfo task, String userId, String portalUsername) {
        String assignmentType = task.getAssignmentType();
        String assignee = task.getAssignee();

        // A held BU Role request stays held until its claimer releases it; a second claim would
        // hand two members the same editable form.
        if (BuRolePoolTasks.isClaimPoolTask(task) && BuRolePoolTasks.isHeld(task)) {
            return false;
        }

        return switch (assignmentType != null ? assignmentType : "") {
            case "CANDIDATE_USERS" ->
                    candidateUserIdsContain(task.getCandidateUserIds(), userId, portalUsername);
            case "VIRTUAL_GROUP" -> {
                if (assignee != null && !assignee.isEmpty()) {
                    yield isUserInVirtualGroup(userId, assignee);
                }
                if (task.getCandidateGroupIds() != null) {
                    for (String g : task.getCandidateGroupIds()) {
                        if (isUserInVirtualGroup(userId, g)) {
                            yield true;
                        }
                    }
                }
                yield false;
            }
            default -> false;
        };
    }

    /**
     * Fills the claim flags the portal UI needs to decide between "Claim", "Unclaim" and read-only
     * for BU Role pool rows. Kept next to the permission rules so list, detail and claim endpoints
     * cannot drift apart on who holds a request.
     */
    public void annotateClaimState(TaskInfo task, String userId, String portalUsername) {
        if (task == null) {
            return;
        }
        boolean pool = BuRolePoolTasks.isClaimPoolTask(task);
        task.setClaimPoolTask(pool);
        if (!pool) {
            task.setClaimedByCurrentUser(false);
            task.setClaimable(false);
            return;
        }
        task.setClaimedByCurrentUser(isHeldByUser(task, userId, portalUsername));
        task.setClaimable(!BuRolePoolTasks.isHeld(task)
                && candidateUserIdsContain(task.getCandidateUserIds(), userId, portalUsername));
    }

    public void annotateClaimState(List<TaskInfo> tasks, String userId, String portalUsername) {
        if (tasks == null) {
            return;
        }
        for (TaskInfo task : tasks) {
            annotateClaimState(task, userId, portalUsername);
        }
    }

    public boolean isHeldByUser(TaskInfo task, String userId, String portalUsername) {
        return task != null
                && task.getAssignee() != null
                && matchesPortalIdentity(task.getAssignee(), userId, portalUsername);
    }

    /**
     * Whether the user may process the task
     */
    public boolean canProcessTask(TaskInfo task, String userId) {
        return canProcessTask(task, userId, null);
    }

    public boolean canProcessTask(TaskInfo task, String userId, String portalUsername) {
        String assignmentType = task.getAssignmentType();
        String assignee = task.getAssignee();

        // Allow when assignee matches current user (including after claim)
        if (assignee != null && matchesPortalIdentity(assignee, userId, portalUsername)) {
            return true;
        }

        if (isSingleTaskDelegatee(task, userId, portalUsername)) {
            return true;
        }
        if (isStandingDelegatee(task, userId, portalUsername)) {
            return true;
        }

        // BU Role claim pool: processing rights belong to the claimer only. The assignee match
        // above already let the holder through; everyone else in the role stays read-only whether
        // the request is free (must claim first) or held by a colleague.
        if (BuRolePoolTasks.isClaimPoolTask(task)) {
            return false;
        }

        // Direct user assignment
        if ("USER".equals(assignmentType) && assignee != null && matchesPortalIdentity(assignee, userId, portalUsername)) {
            return true;
        }

        // Delegated task
        if ("DELEGATED".equals(assignmentType) && assignee != null && matchesPortalIdentity(assignee, userId, portalUsername)) {
            return true;
        }

        // Flowable candidate pool: user must be in candidate list
        if ("CANDIDATE_USERS".equals(assignmentType)) {
            return candidateUserIdsContain(task.getCandidateUserIds(), userId, portalUsername);
        }

        // Entity manager task (ENTITY_MANAGER)
        if ("ENTITY_MANAGER".equals(assignmentType)) {
            log.info("Entity manager task {} for user {}, allowing process (permission verified by query)", task.getTaskId(), userId);
            return true;
        }

        // Virtual group: prove membership (assignee is group ID or engine candidateGroupIds)
        if ("VIRTUAL_GROUP".equals(assignmentType)) {
            if (assignee != null && !assignee.isEmpty() && isUserInVirtualGroup(userId, assignee)) {
                return true;
            }
            if (task.getCandidateGroupIds() != null) {
                for (String g : task.getCandidateGroupIds()) {
                    if (isUserInVirtualGroup(userId, g)) {
                        return true;
                    }
                }
            }
        }

        // Empty pool (no assignee/candidates/groups/target): allow initiator only when BPMN is initiator task;
        // BU_ROLE / HIERARCHY nodes that look empty must not appear on initiator todo.
        if (isEmptyAssignmentPool(task) && isInitiatorOfTask(task, userId, portalUsername)) {
            if (!allowsInitiatorEmptyPoolFallback(task.getBpmnAssigneeType())) {
                log.debug("canProcessTask: deny initiator empty-pool for BPMN assigneeType={} task={}",
                        task.getBpmnAssigneeType(), task.getTaskId());
                return false;
            }
            log.info("canProcessTask: allow process for initiator on empty-pool task {}", task.getTaskId());
            return true;
        }

        return false;
    }

    /**
     * Single-task USER or current-workspace BU+Role delegatee (not standing rules).
     */
    public boolean isSingleTaskDelegatee(TaskInfo task, String userId, String portalUsername) {
        if (task == null || userId == null || userId.isBlank()) {
            return false;
        }
        if (task.getDelegatedTo() != null && matchesPortalIdentity(task.getDelegatedTo(), userId, portalUsername)) {
            return true;
        }
        boolean buRole = "BU_ROLE".equalsIgnoreCase(blankToNull(task.getDelegatedTargetType()))
                || (task.getDelegatedTo() == null
                        && task.getDelegatedBuCode() != null && !task.getDelegatedBuCode().isBlank()
                        && task.getDelegatedRoleCode() != null && !task.getDelegatedRoleCode().isBlank());
        return buRole && workspaceTaskFilterComponent.workspacePairMatches(
                task.getDelegatedBuCode(), task.getDelegatedRoleCode(), userId);
    }

    /**
     * Standing-rule USER or current-workspace BU+Role delegatee (not single-task {@code delegated_*}).
     */
    public boolean isStandingDelegatee(TaskInfo task, String userId, String portalUsername) {
        return delegationRuleMatcher.matchesStanding(task, userId, portalUsername);
    }

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    /**
     * Empty-pool initiator fallback: only when BPMN explicitly marks initiator.
     * Without assigneeType or on later nodes (BU_ROLE), initiator must not see empty-pool tasks unless BPMN marks INITIATOR.
     */
    private static boolean allowsInitiatorEmptyPoolFallback(String bpmnAssigneeType) {
        if (bpmnAssigneeType == null || bpmnAssigneeType.isBlank()) {
            return false;
        }
        String u = bpmnAssigneeType.trim().toUpperCase(Locale.ROOT);
        return "INITIATOR".equals(u) || "PROCESS_INITIATOR".equals(u);
    }

    /**
     * Todo-list filter: hide initiator on empty pool when BPMN is not INITIATOR/PROCESS_INITIATOR (e.g. mis-shown BU_ROLE).
     * <p>Do not filter the whole list with {@link #canProcessTask}: engine already aggregates assignee/candidates/groups; re-filter risks candidate ID (UUID vs username)
     * JWT mismatch can hide valid processors (e.g. BU_ROLE pool members) from the todo list.</p>
     */
    public boolean shouldHideTaskInTodoList(TaskInfo task, String userId, String portalUsername) {
        if (!isEmptyAssignmentPool(task) || !isInitiatorOfTask(task, userId, portalUsername)) {
            return false;
        }
        return !allowsInitiatorEmptyPoolFallback(task.getBpmnAssigneeType());
    }

    /**
     * Whether task form is viewable (todo/done snapshot): processor rules + initiator + current assignee (including done tasks).
     */
    public boolean canViewTaskForm(TaskInfo task, String userId) {
        return canViewTaskForm(task, userId, null);
    }

    public boolean canViewTaskForm(TaskInfo task, String userId, String portalUsername) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        if (canProcessTask(task, userId, portalUsername)) {
            return true;
        }
        if (samePortalUserId(userId, task.getInitiatorId())) {
            return true;
        }
        if (task.getAssignee() != null && matchesPortalIdentity(task.getAssignee(), userId, portalUsername)) {
            return true;
        }
        // Claim pool members keep read access to the whole role's queue: they need to open a held
        // request to see its content and who is holding it, even though they cannot edit it.
        if (BuRolePoolTasks.isClaimPoolTask(task)
                && candidateUserIdsContain(task.getCandidateUserIds(), userId, portalUsername)) {
            return true;
        }
        return false;
    }

    /**
     * {@link TaskQueryComponent#getTaskById} resolves through workflow-engine {@code getTaskInfo}, which may return a
     * <strong>historic</strong> row when no runtime execution exists ({@code status=COMPLETED}). Those rows must not
     * be completed again — Flowable runtime complete would yield "Task not found".
     */
    static boolean isTaskAlreadyClosedInEngineView(TaskInfo task) {
        if (task == null) {
            return false;
        }
        if (task.getCompletedTime() != null) {
            return true;
        }
        String s = task.getStatus();
        if (s == null || s.isBlank()) {
            return false;
        }
        String u = s.trim().toUpperCase(Locale.ROOT);
        return "COMPLETED".equals(u) || "CANCELLED".equals(u) || "TERMINATED".equals(u);
    }

    static boolean isEngineTaskInactiveMessage(String engineMessage) {
        if (engineMessage == null) {
            return false;
        }
        String m = engineMessage.trim();
        if (m.isEmpty()) {
            return false;
        }
        String low = m.toLowerCase(Locale.ROOT);
        return low.contains("task not found") || low.contains("task already completed");
    }

    /**
     * Whether the user belongs to the virtual group
     * Verified via WorkflowEngineClient against workflow-engine-core
     */
    private boolean isUserInVirtualGroup(String userId, String groupId) {
        if (!workflowEngineClient.isAvailable()) {
            log.warn("Workflow engine not available, cannot verify virtual group membership");
            return false;
        }
        try {
            // checkTaskPermission first argument is taskId, not virtual group ID
            Optional<Map<String, Object>> permissions = workflowEngineClient.getUserTaskPermissions(userId);
            if (permissions.isPresent()) {
                @SuppressWarnings("unchecked")
                List<String> groupIds = (List<String>) permissions.get().get("virtualGroupIds");
                if (groupIds != null) {
                    return groupIds.contains(groupId);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check virtual group membership: {}", e.getMessage());
        }
        return false;
    }
}
