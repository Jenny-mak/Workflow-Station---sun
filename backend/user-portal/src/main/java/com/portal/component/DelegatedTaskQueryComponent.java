package com.portal.component;

import com.platform.security.util.SecurityContextUtils;
import com.portal.client.WorkflowEngineClient;
import com.portal.dto.TaskInfo;
import com.portal.entity.DelegationRule;
import com.portal.util.RequestContextInheritanceUtils;
import com.portal.util.WorkflowEnginePayloadHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Queries tasks delegated to a user: standing rules plus engine single-task overlay
 * (USER {@code delegated_to} or current workspace BU+Role). Does not rewrite assignee.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelegatedTaskQueryComponent {

    private static final int DELEGATOR_ENGINE_PAGE_SIZE = 200;

    private final WorkflowEngineClient workflowEngineClient;
    private final DelegationRuleMatcher delegationRuleMatcher;
    private final RequestIdEnricher requestIdEnricher;

    public List<TaskInfo> queryDelegatedTasks(String userId) {
        if (!workflowEngineClient.isAvailable()) {
            throw new IllegalStateException("Flowable engine unavailable, please check if workflow-engine-core service is running");
        }

        LinkedHashMap<String, TaskInfo> byId = new LinkedHashMap<>();
        for (TaskInfo overlay : loadEngineRuntimeOverlay(userId)) {
            if (overlay.getTaskId() != null) {
                byId.putIfAbsent(overlay.getTaskId(), overlay);
            }
        }
        for (TaskInfo standing : loadStandingRuleDelegatedTasks(userId)) {
            if (standing.getTaskId() != null) {
                byId.putIfAbsent(standing.getTaskId(), standing);
            }
        }
        return new ArrayList<>(byId.values());
    }

    private List<TaskInfo> loadEngineRuntimeOverlay(String userId) {
        String buId = SecurityContextUtils.getCurrentActiveBusinessUnitId().orElse(null);
        String roleId = SecurityContextUtils.getCurrentActiveRoleId().orElse(null);
        Optional<Map<String, Object>> result = workflowEngineClient.getDelegatedRuntimeTasks(buId, roleId);
        if (result.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> tasks = WorkflowEnginePayloadHelper.taskListFromPayload(result.get());
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }
        List<TaskInfo> out = new ArrayList<>();
        for (Map<String, Object> taskMap : tasks) {
            TaskInfo mapped = EngineTaskMapper.convertMapToTaskInfo(taskMap);
            mapped.setAssignmentType("DELEGATED");
            mapped.setDelegatorId(mapped.getDelegatorId() != null ? mapped.getDelegatorId() : mapped.getAssignee());
            mapped.setDelegatorName(mapped.getDelegatorName() != null ? mapped.getDelegatorName() : mapped.getAssigneeName());
            out.add(mapped);
        }
        return out;
    }

    private List<TaskInfo> loadStandingRuleDelegatedTasks(String userId) {
        String username = SecurityContextUtils.getCurrentUsername().orElse(null);
        List<DelegationRule> rules = delegationRuleMatcher.listActiveRulesForActor(userId, username);
        if (rules.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, List<DelegationRule>> byDelegator = rules.stream()
                .collect(Collectors.groupingBy(DelegationRule::getDelegatorId, LinkedHashMap::new, Collectors.toList()));

        SecurityContext ctx = SecurityContextHolder.getContext();
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        List<CompletableFuture<List<TaskInfo>>> futures = byDelegator.entrySet().stream()
                .map(entry -> CompletableFuture.supplyAsync(() -> RequestContextInheritanceUtils.runWithInheritedRequestAndSecurity(
                        ctx, attrs, () -> loadDelegatedTasksForDelegator(entry.getKey(), entry.getValue()))))
                .toList();

        List<TaskInfo> delegatedTasks = new ArrayList<>();
        for (CompletableFuture<List<TaskInfo>> f : futures) {
            try {
                delegatedTasks.addAll(f.join());
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw e;
            }
        }
        return delegatedTasks;
    }

    private List<TaskInfo> loadDelegatedTasksForDelegator(String delegatorId, List<DelegationRule> rules) {
        List<TaskInfo> delegatedTasks = new ArrayList<>();
        for (int p = 0; ; p++) {
            Map<String, Object> payload =
                    workflowEngineClient.getDelegatorAssignedTasks(delegatorId, p, DELEGATOR_ENGINE_PAGE_SIZE);
            List<Map<String, Object>> tasks = WorkflowEnginePayloadHelper.taskListFromPayload(payload);
            if (tasks == null || tasks.isEmpty()) {
                break;
            }
            delegatedTasks.addAll(matchStandingPage(delegatorId, rules, tasks));
            if (tasks.size() < DELEGATOR_ENGINE_PAGE_SIZE) {
                break;
            }
        }
        return delegatedTasks;
    }

    /**
     * Assigned-to list omits Flowable variables. Fill {@code functionUnitCode} from
     * {@code up_process_instance} before PARTIAL matching (same batch as To Do).
     */
    private List<TaskInfo> matchStandingPage(
            String delegatorId, List<DelegationRule> rules, List<Map<String, Object>> tasks) {
        List<TaskInfo> mapped = new ArrayList<>(tasks.size());
        for (Map<String, Object> taskMap : tasks) {
            mapped.add(EngineTaskMapper.convertMapToTaskInfo(taskMap));
        }
        requestIdEnricher.enrichTaskRequestIds(mapped);
        List<TaskInfo> matched = new ArrayList<>();
        for (TaskInfo taskInfo : mapped) {
            if (!standingTaskMatches(taskInfo, delegatorId, rules)) {
                continue;
            }
            stampStandingOverlay(taskInfo, delegatorId);
            matched.add(taskInfo);
        }
        return matched;
    }

    private static void stampStandingOverlay(TaskInfo taskInfo, String delegatorId) {
        taskInfo.setAssignmentType("DELEGATED");
        taskInfo.setDelegatorId(delegatorId);
        if (taskInfo.getDelegatorName() == null || taskInfo.getDelegatorName().isBlank()) {
            taskInfo.setDelegatorName(taskInfo.getAssigneeName() != null
                    ? taskInfo.getAssigneeName() : delegatorId);
        }
    }

    private boolean standingTaskMatches(TaskInfo task, String delegatorId, List<DelegationRule> rules) {
        if (!delegationRuleMatcher.isAssignedDelegatableTask(task)) {
            return false;
        }
        if (!DelegationRuleMatcher.matchesPortalIdentity(task.getAssignee(), delegatorId, null)) {
            return false;
        }
        for (DelegationRule rule : rules) {
            if (delegationRuleMatcher.ruleMatches(task, rule)) {
                return true;
            }
        }
        return false;
    }
}
