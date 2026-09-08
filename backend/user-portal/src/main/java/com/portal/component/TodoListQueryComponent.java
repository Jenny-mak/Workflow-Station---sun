package com.portal.component;

import com.platform.common.list.ListColumnFilter;
import com.portal.client.WorkflowEngineClient;
import com.portal.dto.MyTaskRef;
import com.portal.dto.PageResponse;
import com.portal.dto.PortalListPage;
import com.portal.dto.TaskInfo;
import com.portal.dto.TaskQueryRequest;
import com.portal.dto.TodoTaskQueryRequest;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.util.BuRolePoolTasks;
import com.portal.util.ListQuerySupport;
import com.portal.util.TaskInfoListOps;
import com.portal.util.TaskInfoQueryFilters;
import com.portal.util.TodoListUnion;
import com.portal.util.TodoTaskColumnSpec;
import com.portal.util.WithdrawnProcessIds;
import com.portal.util.WorkflowEnginePayloadHelper;
import com.platform.security.util.SecurityContextUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared-list To Do, plus Claim All / Unclaim All pool scans.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TodoListQueryComponent {

    private final WorkflowEngineClient workflowEngineClient;
    private final ProcessInstanceRepository processInstanceRepository;
    private final WorkspaceTaskFilterComponent workspaceTaskFilter;
    private final RequestIdEnricher requestIdEnricher;
    private final TaskPermissionEvaluator taskPermissionEvaluator;
    private final ClaimForceUnclaimAnnotator claimForceUnclaimAnnotator;
    private final MineTaskScanner mineTaskScanner;

    public PortalListPage<TaskInfo> queryTodoList(String userId, TodoTaskQueryRequest request) {
        long started = System.nanoTime();
        TaskQueryRequest adapted = toTaskQuery(userId, request);
        PageResponse<TaskInfo> page = queryMergedTodoPage(userId, adapted, request.page(), request.size());
        long total = page.getTotalElements();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        ListQuerySupport.logIfSlow(log, TaskQueryComponent.TODO_LIST_KEY, request.page(), request.size(), total, started);
        ListQuerySupport.logIfOverSla(log, TaskQueryComponent.TODO_LIST_KEY, request.page(), request.size(), total,
                elapsedMs, elapsedMs, 0L);
        return new PortalListPage<>(TodoTaskColumnSpec.columns(), page.getContent(),
                request.page(), request.size(), total);
    }

    public List<TaskInfo> listClaimPoolTasks(String userId) {
        return fetchAllClaimPoolTasksPaged(userId);
    }

    /**
     * Same Mine ∪ claim-pool set as the To Do page. Unclaim All must use this: sole-assignee
     * BU Role holds have no candidate link, so they appear on To Do via Mine and are invisible
     * to {@link #listClaimPoolTasks}.
     */
    public List<TaskInfo> listMergedTodoTasks(String userId) {
        TaskQueryRequest mineFetch = TaskQueryRequest.builder()
                .userId(userId)
                .page(0)
                .size(500)
                .build();
        List<TaskInfo> merged = TodoListUnion.merge(
                mineTaskScanner.scanMineTasks(userId, mineFetch, null, 500),
                fetchAllClaimPoolTasksPaged(userId));
        String portalUsername = SecurityContextUtils.getCurrentUsername().orElse(null);
        taskPermissionEvaluator.annotateClaimState(merged, userId, portalUsername);
        return merged;
    }

    /**
     * The To Do tasks the user holds on each of the given process instances.
     *
     * <p>Answers the Views grid's per-row task marker for a whole page in one call — one row at a
     * time would be one engine round trip per row. It reuses {@link #listMergedTodoTasks} rather
     * than querying the engine by process instance directly: "is this task mine" is the union of
     * Mine and the claim pool minus withdrawn, hidden and out-of-workspace tasks, and a second
     * implementation of that rule would drift from the To Do list the marker promises to open.
     * The Mine full scan behind it is cached for 15s, so a page load costs about what opening
     * To Do costs.
     *
     * @return process instance id → its tasks, in To Do order; instances with none are absent
     */
    public Map<String, List<MyTaskRef>> findMyTaskRefsByProcessInstance(
            String userId, Collection<String> processInstanceIds) {
        if (userId == null || userId.isBlank() || processInstanceIds == null || processInstanceIds.isEmpty()) {
            return Map.of();
        }
        Set<String> wanted = processInstanceIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<String, List<MyTaskRef>> byInstance = new LinkedHashMap<>();
        for (TaskInfo task : listMergedTodoTasks(userId)) {
            String processInstanceId = task.getProcessInstanceId();
            if (processInstanceId == null || !wanted.contains(processInstanceId)) {
                continue;
            }
            if (task.getTaskId() == null || task.getTaskId().isBlank()) {
                continue;
            }
            byInstance.computeIfAbsent(processInstanceId, k -> new ArrayList<>())
                    .add(new MyTaskRef(task.getTaskId(), task.getTaskName()));
        }
        return byInstance;
    }

    static TaskQueryRequest toTaskQuery(String userId, TodoTaskQueryRequest request) {
        List<ListColumnFilter> filters = new ArrayList<>(request.filters());
        if (!request.priorities().isEmpty()) {
            filters.add(new ListColumnFilter("priority", "in", String.join(",", request.priorities()), null));
        }
        return TaskQueryRequest.builder()
                .userId(userId)
                .page(request.page())
                .size(request.size())
                .filters(filters)
                .sortBy(request.sortField())
                .sortDirection(request.sortDirection())
                .keyword(request.keyword())
                .assignmentTypes(request.assignmentTypes().isEmpty() ? null : request.assignmentTypes())
                .build();
    }

    private PageResponse<TaskInfo> queryMergedTodoPage(
            String userId, TaskQueryRequest adapted, int page, int size) {
        TaskQueryRequest mineFetch = TaskQueryRequest.builder()
                .userId(userId)
                .page(0)
                .size(size)
                .build();
        List<TaskInfo> mine = mineTaskScanner.scanMineTasks(userId, mineFetch, null, size);
        List<TaskInfo> merged = TodoListUnion.merge(mine, fetchAllClaimPoolTasksPaged(userId));
        maybeEnrichRequestIds(merged, adapted);
        List<TaskInfo> filtered = TaskInfoQueryFilters.apply(merged, adapted);
        filtered = TaskInfoListOps.applySorting(filtered, adapted);
        List<TaskInfo> paged = new ArrayList<>(TaskInfoListOps.pageOf(filtered, page, size));
        requestIdEnricher.enrichTaskRequestIds(paged);
        EngineTaskMapper.clearTaskVariablesForList(paged);
        return PageResponse.of(paged, page, size, filtered.size());
    }

    private List<TaskInfo> fetchAllClaimPoolTasksPaged(String userId) {
        String portalUsername = SecurityContextUtils.getCurrentUsername().orElse(null);
        List<TaskInfo> out = new ArrayList<>();
        final int batch = 500;
        for (int p = 0; ; p++) {
            Map<String, Object> page = BuRolePoolTasks.requireEnginePage(
                    workflowEngineClient.getUserClaimPoolTasks(userId, p, batch), p);
            List<Map<String, Object>> tasks = WorkflowEnginePayloadHelper.taskListFromPayload(page);
            if (tasks == null || tasks.isEmpty()) {
                break;
            }
            List<TaskInfo> converted = new ArrayList<>(tasks.size());
            for (Map<String, Object> taskMap : tasks) {
                converted.add(EngineTaskMapper.convertMapToTaskInfo(taskMap));
            }
            out.addAll(BuRolePoolTasks.retainClaimPoolTasks(converted));
            if (tasks.size() < batch) {
                break;
            }
        }
        Set<String> withdrawn = WithdrawnProcessIds.of(processInstanceRepository, out.stream()
                .map(TaskInfo::getProcessInstanceId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet()));
        List<TaskInfo> visible = out.stream()
                .filter(t -> t.getProcessInstanceId() == null || !withdrawn.contains(t.getProcessInstanceId()))
                .collect(Collectors.toCollection(ArrayList::new));
        visible = workspaceTaskFilter.filterFixedBuRoleTasksForActiveWorkspace(visible, userId);
        taskPermissionEvaluator.annotateClaimState(visible, userId, portalUsername);
        claimForceUnclaimAnnotator.annotate(visible, userId);
        return visible;
    }

    private void maybeEnrichRequestIds(List<TaskInfo> tasks, TaskQueryRequest request) {
        if (TaskInfoQueryFilters.needsRequestIdEnrichment(request)) {
            requestIdEnricher.enrichTaskRequestIds(tasks);
        }
    }
}
