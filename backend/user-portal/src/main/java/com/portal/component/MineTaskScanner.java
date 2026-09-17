package com.portal.component;

import com.portal.dto.TaskInfo;
import com.portal.dto.TaskQueryRequest;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.util.BuRolePoolTasks;
import com.portal.util.EngineTaskPushdown;
import com.portal.util.TaskInfoListOps;
import com.portal.util.TaskInfoQueryFilters;
import com.portal.util.WithdrawnProcessIds;
import com.platform.security.util.SecurityContextUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mine full-scan (cached) and portal post-filters applied after an engine window.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MineTaskScanner {

    private final MineTaskListCache mineTaskListCache;
    private final EngineVisibleTaskFetcher engineVisibleTaskFetcher;
    private final RequestIdEnricher requestIdEnricher;
    private final TaskPermissionEvaluator taskPermissionEvaluator;
    private final ClaimForceUnclaimAnnotator claimForceUnclaimAnnotator;
    private final WorkspaceTaskFilterComponent workspaceTaskFilter;
    private final ProcessInstanceRepository processInstanceRepository;

    @Lazy
    @Autowired
    private TaskProcessComponent taskProcessComponent;

    public List<TaskInfo> scanMineTasks(
            String userId, TaskQueryRequest request, List<String> assignmentTypes, int size) {
        EngineTaskPushdown.Criteria push = EngineTaskPushdown.from(request);
        String cacheKey = MineTaskListCache.key(userId, request, assignmentTypes, size);
        List<TaskInfo> cached = mineTaskListCache.get(cacheKey);
        if (cached != null) {
            log.info("[PERF] query.fullScan cacheHit=true engineTasks={}", cached.size());
            return cached;
        }
        long tFetch0 = System.nanoTime();
        List<TaskInfo> engineTasks = engineVisibleTaskFetcher.fetchAllWithRequestContext(
                userId, assignmentTypes, push);
        long tFetch1 = System.nanoTime();
        List<TaskInfo> allTasks = applyPortalPostEngineFilters(userId, new ArrayList<>(engineTasks));
        long tPost = System.nanoTime();
        if (TaskInfoQueryFilters.needsRequestIdEnrichment(request)) {
            requestIdEnricher.enrichTaskRequestIds(allTasks);
        }
        allTasks = TaskInfoQueryFilters.apply(allTasks, request);
        allTasks = TaskInfoListOps.applySorting(allTasks, request);
        long tMem = System.nanoTime();
        List<TaskInfo> filteredSorted = List.copyOf(allTasks);
        mineTaskListCache.put(cacheKey, filteredSorted);
        log.info("[PERF] query.fullScan engineAllPages={}ms postFilter={}ms memFilterSort={}ms "
                        + "engineTasks={} filtered={} pushNameLike={} pushNameExact={} pushSort={}/{}",
                (tFetch1 - tFetch0) / 1_000_000L,
                (tPost - tFetch1) / 1_000_000L,
                (tMem - tPost) / 1_000_000L,
                engineTasks.size(),
                filteredSorted.size(),
                push.taskNameLike(),
                push.taskNameExact(),
                push.sortBy(),
                push.sortDirection());
        return filteredSorted;
    }

    public List<TaskInfo> applyPortalPostEngineFilters(String userId, List<TaskInfo> allTasks) {
        List<TaskInfo> filtered = dropWithdrawnAndDedupe(allTasks);
        String portalUsername = SecurityContextUtils.getCurrentUsername().orElse(null);
        if (taskProcessComponent != null) {
            filtered = filtered.stream()
                    .filter(t -> !taskProcessComponent.shouldHideTaskInTodoList(t, userId, portalUsername))
                    .collect(Collectors.toList());
        }
        filtered = filtered.stream()
                .filter(BuRolePoolTasks::staysOnTodoList)
                .collect(Collectors.toList());
        taskPermissionEvaluator.annotateClaimState(filtered, userId, portalUsername);
        claimForceUnclaimAnnotator.annotate(filtered, userId);
        return workspaceTaskFilter.filterFixedBuRoleTasksForActiveWorkspace(filtered, userId);
    }

    /**
     * Standing/single-task delegated overlay is the delegator's held work, not the viewer's
     * workspace To Do. Viewer FIXED_BU_ROLE filtering would drop a manager-pool task the
     * delegatee can already complete on behalf of the assignee.
     */
    public List<TaskInfo> applyDelegatedOverlayPostFilters(List<TaskInfo> allTasks) {
        return dropWithdrawnAndDedupe(allTasks);
    }

    private List<TaskInfo> dropWithdrawnAndDedupe(List<TaskInfo> allTasks) {
        Set<String> processIds = allTasks.stream()
                .map(TaskInfo::getProcessInstanceId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Set<String> withdrawnProcessIds = WithdrawnProcessIds.of(processInstanceRepository, processIds);
        List<TaskInfo> filtered = allTasks.stream()
                .filter(t -> {
                    String pid = t.getProcessInstanceId();
                    return pid == null || pid.isBlank() || !withdrawnProcessIds.contains(pid);
                })
                .collect(Collectors.toCollection(ArrayList::new));
        return new ArrayList<>(filtered.stream()
                .collect(Collectors.toMap(TaskInfo::getTaskId, t -> t, (t1, t2) -> t1, LinkedHashMap::new))
                .values());
    }
}
