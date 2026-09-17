package com.portal.component;

import com.portal.dto.TaskInfo;
import com.portal.entity.DelegationRule;
import com.portal.enums.DelegationStatus;
import com.portal.enums.DelegationType;
import com.portal.repository.DelegationRuleRepository;
import com.portal.util.BuRolePoolTasks;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Shared standing-rule matchers. Query, canProcess, and complete-prep must all use this class.
 */
@Component
@RequiredArgsConstructor
public class DelegationRuleMatcher {

    static final List<String> URGENT_PRIORITIES = List.of("URGENT", "CRITICAL");

    private final DelegationRuleRepository delegationRuleRepository;
    private final WorkspaceTaskFilterComponent workspaceTaskFilterComponent;

    public boolean ruleMatches(TaskInfo task, DelegationRule rule) {
        return ruleMatches(task, rule, LocalDateTime.now());
    }

    public boolean ruleMatches(TaskInfo task, DelegationRule rule, LocalDateTime now) {
        if (task == null || rule == null || now == null) {
            return false;
        }
        if (rule.getStatus() != DelegationStatus.ACTIVE) {
            return false;
        }
        if (rule.getStartTime() != null && now.isBefore(rule.getStartTime())) {
            return false;
        }
        if (rule.getEndTime() != null && now.isAfter(rule.getEndTime())) {
            return false;
        }
        return typeGate(task, rule) && priorityFilterGate(task, rule);
    }

    public boolean actorMatchesRule(String actorId, String portalUsername, DelegationRule rule) {
        if (rule == null || actorId == null || actorId.isBlank()) {
            return false;
        }
        if (rule.isBuRoleTarget()) {
            return workspaceTaskFilterComponent.workspacePairMatches(
                    rule.getDelegateBuCode(), rule.getDelegateRoleCode(), actorId);
        }
        return matchesPortalIdentity(rule.getDelegateId(), actorId, portalUsername);
    }

    public boolean matchesStanding(TaskInfo task, String userId, String portalUsername) {
        if (task == null || userId == null || userId.isBlank()) {
            return false;
        }
        if (!isAssignedDelegatableTask(task)) {
            return false;
        }
        for (DelegationRule rule : listActiveRulesForActor(userId, portalUsername)) {
            if (matchesPortalIdentity(task.getAssignee(), rule.getDelegatorId(), null)
                    && ruleMatches(task, rule)
                    && actorMatchesRule(userId, portalUsername, rule)) {
                return true;
            }
        }
        return false;
    }

    public List<DelegationRule> listActiveRulesForActor(String userId, String portalUsername) {
        LocalDateTime now = LocalDateTime.now();
        List<DelegationRule> out = new ArrayList<>(
                delegationRuleRepository.findActiveDelegationsForDelegate(userId, now));
        if (portalUsername != null && !portalUsername.isBlank()
                && !portalUsername.trim().equals(userId.trim())) {
            out.addAll(delegationRuleRepository.findActiveDelegationsForDelegate(portalUsername.trim(), now));
        }
        Optional<String> buCode = workspaceTaskFilterComponent.activeWorkspaceBuCode();
        Optional<String> roleCode = workspaceTaskFilterComponent.activeWorkspaceRoleCode(userId);
        if (buCode != null && roleCode != null && buCode.isPresent() && roleCode.isPresent()) {
            out.addAll(delegationRuleRepository.findActiveDelegationsForBuRole(
                    buCode.get(), roleCode.get(), now));
        }
        return out;
    }

    public boolean isAssignedDelegatableTask(TaskInfo task) {
        if (task == null || task.getAssignee() == null || task.getAssignee().isBlank()) {
            return false;
        }
        if (TaskPermissionEvaluator.isEmptyAssignmentPool(task)) {
            return false;
        }
        return !BuRolePoolTasks.isClaimPoolTask(task) || BuRolePoolTasks.isHeld(task);
    }

    static boolean matchesPortalIdentity(String engineSideRef, String portalUserId, String portalUsername) {
        if (engineSideRef == null || engineSideRef.isBlank() || portalUserId == null) {
            return false;
        }
        if (engineSideRef.trim().equals(portalUserId.trim())) {
            return true;
        }
        return portalUsername != null && !portalUsername.isBlank()
                && portalUsername.trim().equals(engineSideRef.trim());
    }

    private static boolean typeGate(TaskInfo task, DelegationRule rule) {
        DelegationType type = rule.getDelegationType();
        if (type == null || type == DelegationType.ALL || type == DelegationType.TEMPORARY) {
            return true;
        }
        if (type == DelegationType.PARTIAL) {
            return processTypeMatches(task, rule.getProcessTypes());
        }
        if (type == DelegationType.URGENT) {
            return isUrgentPriority(task.getPriority());
        }
        return true;
    }

    private static boolean priorityFilterGate(TaskInfo task, DelegationRule rule) {
        List<String> filter = rule.getPriorityFilter();
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        String priority = task.getPriority();
        if (priority == null || priority.isBlank()) {
            return false;
        }
        String needle = priority.trim().toUpperCase(Locale.ROOT);
        for (String allowed : filter) {
            if (allowed != null && needle.equals(allowed.trim().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * New rules store portal Function Unit code (startable {@code key}). Older rules may store
     * the BPMN process id. Match either field on the task.
     */
    private static boolean processTypeMatches(TaskInfo task, List<String> processTypes) {
        if (task == null || processTypes == null || processTypes.isEmpty()) {
            return false;
        }
        return tokenInList(task.getFunctionUnitCode(), processTypes)
                || tokenInList(variableFunctionUnitCode(task), processTypes)
                || tokenInList(task.getProcessDefinitionKey(), processTypes);
    }

    /**
     * Standing overlay maps engine list payloads; {@code functionUnitCode} often lives only in
     * process variables, not on {@link TaskInfo#getFunctionUnitCode()}.
     */
    private static String variableFunctionUnitCode(TaskInfo task) {
        Map<String, Object> variables = task.getVariables();
        if (variables == null) {
            return null;
        }
        Object raw = variables.get("functionUnitCode");
        return raw == null ? null : String.valueOf(raw);
    }

    private static boolean tokenInList(String candidate, List<String> allowed) {
        if (candidate == null || candidate.isBlank() || allowed == null) {
            return false;
        }
        String key = candidate.trim();
        for (String item : allowed) {
            if (item != null && key.equals(item.trim())) {
                return true;
            }
        }
        return false;
    }

    static boolean isUrgentPriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return false;
        }
        String band = priority.trim().toUpperCase(Locale.ROOT);
        return URGENT_PRIORITIES.contains(band);
    }
}
