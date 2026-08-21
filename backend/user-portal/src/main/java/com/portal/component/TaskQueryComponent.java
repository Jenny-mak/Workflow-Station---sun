package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.dto.PageResponse;
import com.portal.dto.PortalListGroup;
import com.portal.dto.PortalListPage;
import com.portal.dto.CompletedTaskQueryRequest;
import com.portal.dto.ListColumnFilter;
import com.portal.dto.TaskActionInfo;
import com.portal.dto.TaskInfo;
import com.portal.dto.TaskQueryRequest;
import com.portal.dto.TaskStatistics;
import com.portal.dto.TaskHistoryInfo;
import com.portal.dto.TodoTaskQueryRequest;
import com.portal.util.EngineTaskPushdown;
import com.portal.util.RequestContextInheritanceUtils;
import com.portal.util.TaskInfoListOps;
import com.portal.util.TaskQueryColumnFilters;
import com.portal.util.TodoTaskColumnSpec;
import com.portal.util.WorkflowEnginePayloadHelper;
import com.platform.security.util.SecurityContextUtils;
import com.portal.entity.ProcessInstance;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.service.TaskActionService;
import com.portal.exception.PortalException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Task query component.
 * Supports multi-dimensional task queries: direct assignment, virtual groups, department roles, delegated tasks.
 *
 * Primary path queries the Flowable engine, then drops only {@link TaskProcessComponent#shouldHideTaskInTodoList}
 * (initiator incorrectly occupying a downstream empty pool), then
 * {@link WorkspaceTaskFilterComponent#filterFixedBuRoleTasksForActiveWorkspace} for FIXED_BU_ROLE vs JWT active BU.
 * Full {@link TaskProcessComponent#canProcessTask} is not applied to the list — engine
 * membership is authoritative; complete/claim still enforce canProcessTask.
 * When the engine returns an empty list, {@link #mergeTasksFromRunningProcessInstancesForUser} merges from
 * RUNNING instances with {@code canProcessTask} (initiator fallback path only).
 *
 * <p>Acts as a facade: delegated-task querying lives in {@link DelegatedTaskQueryComponent}, workspace/BU
 * scoping in {@link WorkspaceTaskFilterComponent}, participant sub-table enrichment in
 * {@link MiParticipantEnrichmentComponent}, history/statistics/completed queries in {@link TaskHistoryComponent},
 * and engine payload mapping in {@link EngineTaskMapper}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskQueryComponent {

    private final ProcessInstanceRepository processInstanceRepository;
    private final WorkflowEngineClient workflowEngineClient;
    private final EngineSubTableHydrator engineSubTableHydrator;
    private final TaskActionService taskActionService;
    private final DelegatedTaskQueryComponent delegatedTaskQueryComponent;
    private final WorkspaceTaskFilterComponent workspaceTaskFilter;
    private final MiParticipantEnrichmentComponent miParticipantEnricher;
    private final TaskHistoryComponent taskHistoryComponent;
    private final RequestIdEnricher requestIdEnricher;

    /** Lazy: breaks cycle with {@link TaskProcessComponent} which depends on this component. */
    @Lazy
    @Autowired
    private TaskProcessComponent taskProcessComponent;

    /** Lazy: merge physical relation-table rows into task variables (same as process detail). */
    @Lazy
    @Autowired
    private ProcessComponent processComponent;

    @Autowired
    private CompletedTaskListQueryComponent completedTaskListQueryComponent;

    /**
     * 有界扇出线程池，替代 commonPool。每个扇出任务运行时各借一条 Hikari 连接，
     * 用有界池 + CallerRunsPolicy 背压，避免高并发下连接池被瞬时扇出打空。见 {@link com.portal.config.PortalAsyncConfig}。
     */
    @Autowired
    @Qualifier(com.portal.config.PortalAsyncConfig.TASK_QUERY_EXECUTOR)
    private java.util.concurrent.Executor taskQueryExecutor;

    @PostConstruct
    public void init() {
        log.info("TaskQueryComponent initialized, workflow engine available: {}", workflowEngineClient.isAvailable());
    }

    /**
     * Query pending tasks for a user.
     * <p>Default (Mine): engine window page. When chrome is only pushable
     * ({@code taskName} + createTime/dueDate/priority/name sort), criteria are pushed to the engine.
     * Non-pushable filters / grouping full-scan engine pages for an exact filtered total.
     * Standing-rule proxy tasks are <strong>not</strong> merged into Mine — use
     * {@code assignmentTypes=[DELEGATED]} or the Proxy Tasks menu.</p>
     */
    public PageResponse<TaskInfo> queryTasks(TaskQueryRequest request) {
        return queryTasks(request, null);
    }

    private PageResponse<TaskInfo> queryTasks(TaskQueryRequest request, List<PortalListGroup> groupsOut) {
        if (!workflowEngineClient.isAvailable()) {
            throw new IllegalStateException("Flowable engine unavailable, please check if workflow-engine-core service is running");
        }

        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new PortalException("401", "Authenticated user id is required for task query");
        }
        List<String> assignmentTypes = request.getAssignmentTypes();
        int page = request.getPage() != null ? request.getPage() : 0;
        int size = request.getSize() != null ? request.getSize() : 20;
        EngineTaskPushdown.Criteria push = EngineTaskPushdown.from(request);

        if (isDelegatedOnlyAssignmentFilter(assignmentTypes)) {
            return queryDelegatedTasksOnlyPage(userId, request, page, size, groupsOut);
        }

        return EngineTaskPushdown.canFullyPush(request)
                ? queryTasksEngineWindowMine(userId, request, assignmentTypes, page, size, push)
                : queryTasksFullEnginePagesMine(userId, request, assignmentTypes, page, size, push, groupsOut);
    }

    /**
     * Shared-list To Do page: same Mine semantics as {@link #queryTasks}, plus column declaration
     * and in-memory group counts when {@code groupBy} is set.
     */
    public PortalListPage<TaskInfo> queryTodoList(String userId, TodoTaskQueryRequest request) {
        TaskQueryRequest adapted = TaskQueryRequest.builder()
                .userId(userId)
                .page(request.page())
                .size(request.size())
                .filters(request.filters())
                .sortBy(request.sortField())
                .sortDirection(request.sortDirection())
                .groupBy(request.groupBy())
                .assignmentTypes(request.assignmentTypes().isEmpty() ? null : request.assignmentTypes())
                .build();
        List<PortalListGroup> groups = new ArrayList<>();
        PageResponse<TaskInfo> page = queryTasks(adapted, groups);
        if (request.groupBy() != null && !request.groupBy().isBlank()
                && page.getTotalElements() > 0 && groups.isEmpty()) {
            throw new IllegalStateException("GROUP BY returned no groups for a non-empty todo result");
        }
        return new PortalListPage<>(TodoTaskColumnSpec.columns(), page.getContent(), List.copyOf(groups),
                request.page(), request.size(), page.getTotalElements());
    }

    private static boolean isDelegatedOnlyAssignmentFilter(List<String> assignmentTypes) {
        return assignmentTypes != null
                && assignmentTypes.size() == 1
                && "DELEGATED".equalsIgnoreCase(assignmentTypes.get(0));
    }

    private PageResponse<TaskInfo> queryDelegatedTasksOnlyPage(
            String userId, TaskQueryRequest request, int page, int size, List<PortalListGroup> groupsOut) {
        List<TaskInfo> allTasks = new ArrayList<>(queryDelegatedTasks(userId));
        allTasks = applyPortalPostEngineFilters(userId, allTasks);
        maybeEnrichRequestIdsForColumnFilter(allTasks, request);
        allTasks = applyFilters(allTasks, request);
        allTasks = TaskInfoListOps.applySorting(allTasks, request);
        if (groupsOut != null && request.getGroupBy() != null && !request.getGroupBy().isBlank()) {
            groupsOut.addAll(TaskInfoListOps.groupsOf(allTasks, request.getGroupBy()));
        }
        List<TaskInfo> pagedTasks = TaskInfoListOps.pageOf(allTasks, page, size);
        requestIdEnricher.enrichTaskRequestIds(pagedTasks);
        EngineTaskMapper.clearTaskVariablesForList(pagedTasks);
        return PageResponse.of(pagedTasks, page, size, allTasks.size());
    }

    private PageResponse<TaskInfo> queryTasksFullEnginePagesMine(
            String userId,
            TaskQueryRequest request,
            List<String> assignmentTypes,
            int page,
            int size,
            EngineTaskPushdown.Criteria push,
            List<PortalListGroup> groupsOut) {
        SecurityContext ctx = SecurityContextHolder.getContext();
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        String cacheKey = fullScanCacheKey(userId, request, assignmentTypes, size);
        FullScanCacheEntry cached = getCachedFullScanEntry(cacheKey);
        List<TaskInfo> filteredSorted;
        if (cached != null) {
            filteredSorted = cached.tasks();
            log.info("[PERF] query.fullScan cacheHit=true engineTasks={}", filteredSorted.size());
        } else {
            long __tFetch0 = System.nanoTime();
            List<TaskInfo> engineTasks = RequestContextInheritanceUtils.runWithInheritedRequestAndSecurity(ctx, attrs,
                    () -> fetchAllEngineTasksPaged(userId, assignmentTypes, size, push));
            long __tFetch1 = System.nanoTime();

            List<TaskInfo> allTasks = applyPortalPostEngineFilters(userId, new ArrayList<>(engineTasks));
            long __tPost = System.nanoTime();
            maybeEnrichRequestIdsForColumnFilter(allTasks, request);
            allTasks = applyFilters(allTasks, request);
            allTasks = TaskInfoListOps.applySorting(allTasks, request);
            long __tMem = System.nanoTime();
            filteredSorted = List.copyOf(allTasks);
            putCachedFullScan(cacheKey, filteredSorted);
            log.info("[PERF] query.fullScan engineAllPages={}ms postFilter={}ms memFilterSort={}ms "
                            + "engineTasks={} filtered={} pushNameLike={} pushNameExact={} pushSort={}/{}",
                    (__tFetch1 - __tFetch0) / 1_000_000L,
                    (__tPost - __tFetch1) / 1_000_000L,
                    (__tMem - __tPost) / 1_000_000L,
                    engineTasks.size(),
                    filteredSorted.size(),
                    push != null ? push.taskNameLike() : null,
                    push != null ? push.taskNameExact() : null,
                    push != null ? push.sortBy() : null,
                    push != null ? push.sortDirection() : null);
        }

        if (groupsOut != null && request.getGroupBy() != null && !request.getGroupBy().isBlank()) {
            groupsOut.addAll(TaskInfoListOps.groupsOf(filteredSorted, request.getGroupBy()));
        }
        List<TaskInfo> pagedTasks = new ArrayList<>(TaskInfoListOps.pageOf(filteredSorted, page, size));
        requestIdEnricher.enrichTaskRequestIds(pagedTasks);
        EngineTaskMapper.clearTaskVariablesForList(pagedTasks);
        return PageResponse.of(pagedTasks, page, size, filteredSorted.size());
    }

    private static final int FULL_SCAN_CACHE_TTL_MS = 15_000;
    private static final int FULL_SCAN_CACHE_MAX = 64;
    private final Map<String, FullScanCacheEntry> fullScanCache = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, FullScanCacheEntry> eldest) {
                    return size() > FULL_SCAN_CACHE_MAX;
                }
            });

    private record FullScanCacheEntry(List<TaskInfo> tasks, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > FULL_SCAN_CACHE_TTL_MS;
        }
    }

    private FullScanCacheEntry getCachedFullScanEntry(String key) {
        FullScanCacheEntry e = fullScanCache.get(key);
        if (e == null || e.isExpired()) {
            if (e != null) {
                fullScanCache.remove(key);
            }
            return null;
        }
        return e;
    }

    private void putCachedFullScan(String key, List<TaskInfo> tasks) {
        fullScanCache.put(key, new FullScanCacheEntry(tasks, System.currentTimeMillis()));
    }

    private static String fullScanCacheKey(
            String userId, TaskQueryRequest request, List<String> assignmentTypes, int size) {
        return userId + '|'
                + SecurityContextUtils.getCurrentActiveBusinessUnitId().orElse("-") + '|'
                + SecurityContextUtils.getCurrentActiveRoleId().orElse("-") + '|'
                + String.valueOf(assignmentTypes) + '|'
                + size + '|'
                + String.valueOf(request.getFilters()) + '|'
                + String.valueOf(request.getSortBy()) + '|'
                + String.valueOf(request.getSortDirection()) + '|'
                + String.valueOf(request.getGroupBy()) + '|'
                + String.valueOf(request.getKeyword()) + '|'
                + String.valueOf(request.getPriorities()) + '|'
                + String.valueOf(request.getProcessTypes()) + '|'
                + String.valueOf(request.getStatuses()) + '|'
                + String.valueOf(request.getStartTime()) + '|'
                + String.valueOf(request.getEndTime()) + '|'
                + String.valueOf(request.getIncludeOverdue());
    }

    private void maybeEnrichRequestIdsForColumnFilter(List<TaskInfo> tasks, TaskQueryRequest request) {
        var filters = TaskQueryColumnFilters.normalize(request.getFilters());
        boolean needsRequestId = filters.stream().anyMatch(f -> "requestId".equals(f.field()));
        if (needsRequestId) {
            requestIdEnricher.enrichTaskRequestIds(tasks);
        }
    }

    /**
     * Engine single-page fetch result (with totalCount, for pagination approximation).
     */
    private record EngineWindowResult(List<TaskInfo> tasks, long engineTotal) {
    }

    private EngineWindowResult fetchEngineTaskPageWindow(
            String userId, List<String> assignmentTypes, int page, int size,
            EngineTaskPushdown.Criteria push) {
        List<TaskInfo> engineTasks = new ArrayList<>();
        long engineTotal = 0L;
        EngineTaskPushdown.Criteria safePush = push != null ? push : EngineTaskPushdown.Criteria.empty();
        try {
            long __tw0 = System.nanoTime();
            List<String> groupIds = workspaceTaskFilter.getUserVirtualGroups(userId);
            groupIds = workspaceTaskFilter.filterVirtualGroupsForActiveWorkspace(userId, groupIds);
            long __twVg = System.nanoTime();
            boolean includeGroups = assignmentTypes == null || assignmentTypes.isEmpty()
                    || assignmentTypes.contains("VIRTUAL_GROUP");
            Optional<Map<String, Object>> result = includeGroups
                    ? workflowEngineClient.getUserAllVisibleTasks(
                            userId, groupIds, Collections.emptyList(), page, size, safePush)
                    : workflowEngineClient.getUserAllVisibleTasks(
                            userId, Collections.emptyList(), Collections.emptyList(), page, size, safePush);
            log.info("[PERF] engineWindow vgroups={}ms engineHttp={}ms groupCount={} includeGroups={} push={}",
                    (__twVg - __tw0) / 1_000_000L, (System.nanoTime() - __twVg) / 1_000_000L,
                    groupIds != null ? groupIds.size() : 0, includeGroups, safePush.hasAny());
            if (result.isPresent()) {
                Map<String, Object> responseBody = result.get();
                engineTotal = EngineTaskMapper.extractEngineTotalCount(responseBody);
                List<Map<String, Object>> tasks = WorkflowEnginePayloadHelper.taskListFromPayload(responseBody);
                if (tasks != null) {
                    for (Map<String, Object> taskMap : tasks) {
                        engineTasks.add(EngineTaskMapper.convertMapToTaskInfo(taskMap));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to query tasks from Flowable: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to query tasks from Flowable: " + e.getMessage(), e);
        }

        // Pushdown empty means "no match", not "engine unavailable". The initiator merge
        // walks every RUNNING PI (N+1) and would dump hundreds of unfiltered rows.
        // Sort-only pushdown must still allow initiator orphan merge; filter pushdown must not.
        if (engineTasks.isEmpty() && !safePush.hasFilterFragments()) {
            mergeTasksFromRunningProcessInstancesForUser(userId, engineTasks);
        }
        return new EngineWindowResult(engineTasks, engineTotal);
    }

    /**
     * Mine: trust engine {@code page}/{@code size}/{@code total}; do not merge standing-rule proxy tasks.
     * Hard-cap the page to {@code size} so a malformed engine payload cannot break true pagination.
     */
    private PageResponse<TaskInfo> queryTasksEngineWindowMine(
            String userId,
            TaskQueryRequest request,
            List<String> assignmentTypes,
            int page,
            int size,
            EngineTaskPushdown.Criteria push) {
        SecurityContext ctx = SecurityContextHolder.getContext();
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        long __tQ0 = System.nanoTime();
        EngineWindowResult engineResult = RequestContextInheritanceUtils.runWithInheritedRequestAndSecurity(ctx, attrs,
                () -> fetchEngineTaskPageWindow(userId, assignmentTypes, page, size, push));
        List<TaskInfo> engineTasks = engineResult.tasks();
        long engineTotal = engineResult.engineTotal();
        long __tQJoin = System.nanoTime();

        List<TaskInfo> pageTasks = applyPortalPostEngineFilters(userId, new ArrayList<>(engineTasks));
        if (pageTasks.size() > size) {
            log.warn("[PERF] engine window returned {} rows for size={}; truncating to protect true pagination",
                    pageTasks.size(), size);
            pageTasks = new ArrayList<>(pageTasks.subList(0, size));
        }
        long __tQFilter = System.nanoTime();
        log.info("[PERF] query.window mine engine={}ms postFilter={}ms engineTasks={} total={} push={}",
                (__tQJoin - __tQ0) / 1_000_000L, (__tQFilter - __tQJoin) / 1_000_000L,
                engineTasks.size(), engineTotal, push != null && push.hasAny());

        requestIdEnricher.enrichTaskRequestIds(pageTasks);
        EngineTaskMapper.clearTaskVariablesForList(pageTasks);
        long totalElements = engineTotal > 0 ? engineTotal : pageTasks.size();
        return PageResponse.of(pageTasks, page, size, totalElements);
    }

    private List<TaskInfo> fetchAllEngineTasksPaged(
            String userId, List<String> assignmentTypes, int pageSize, EngineTaskPushdown.Criteria push) {
        List<String> groupIds = workspaceTaskFilter.getUserVirtualGroups(userId);
        groupIds = workspaceTaskFilter.filterVirtualGroupsForActiveWorkspace(userId, groupIds);
        boolean includeGroups = assignmentTypes == null || assignmentTypes.isEmpty()
                || assignmentTypes.contains("VIRTUAL_GROUP");
        EngineTaskPushdown.Criteria safePush = push != null ? push : EngineTaskPushdown.Criteria.empty();
        List<TaskInfo> out = new ArrayList<>();
        // Engine getUserAllVisibleTasks uses fetchLimit=(page+1)*size then subList — walking
        // page=1,2,… re-reads a growing prefix (O(n²)). One large page-0 window loads Mine once.
        final int batch = 500;
        for (int p = 0; ; p++) {
            Optional<Map<String, Object>> result = includeGroups
                    ? workflowEngineClient.getUserAllVisibleTasks(
                            userId, groupIds, Collections.emptyList(), p, batch, safePush)
                    : workflowEngineClient.getUserAllVisibleTasks(
                            userId, Collections.emptyList(), Collections.emptyList(), p, batch, safePush);
            if (result.isEmpty()) {
                break;
            }
            List<Map<String, Object>> tasks = WorkflowEnginePayloadHelper.taskListFromPayload(result.get());
            if (tasks == null || tasks.isEmpty()) {
                break;
            }
            for (Map<String, Object> taskMap : tasks) {
                out.add(EngineTaskMapper.convertMapToTaskInfo(taskMap));
            }
            if (tasks.size() < batch) {
                break;
            }
            // Extremely large Mine sets: continue, but prefer larger batch over UI page size.
        }
        if (out.isEmpty() && !safePush.hasAny()) {
            mergeTasksFromRunningProcessInstancesForUser(userId, out);
        }
        return out;
    }

    private List<TaskInfo> applyPortalPostEngineFilters(String userId, List<TaskInfo> allTasks) {
        Set<String> processIds = allTasks.stream()
                .map(TaskInfo::getProcessInstanceId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Set<String> withdrawnProcessIds = findWithdrawnProcessInstanceIds(processIds);
        List<TaskInfo> filtered = allTasks.stream()
                .filter(t -> {
                    String pid = t.getProcessInstanceId();
                    return pid == null || pid.isBlank() || !withdrawnProcessIds.contains(pid);
                })
                .collect(Collectors.toCollection(ArrayList::new));
        filtered = filtered.stream()
                .collect(Collectors.toMap(TaskInfo::getTaskId, t -> t, (t1, t2) -> t1))
                .values()
                .stream()
                .collect(Collectors.toList());
        String portalUsername = SecurityContextUtils.getCurrentUsername().orElse(null);
        if (taskProcessComponent != null) {
            filtered = filtered.stream()
                    .filter(t -> !taskProcessComponent.shouldHideTaskInTodoList(t, userId, portalUsername))
                    .collect(Collectors.toList());
        }
        return workspaceTaskFilter.filterFixedBuRoleTasksForActiveWorkspace(filtered, userId);
    }

    /**
     * When /api/v1/tasks?userId= aggregation is empty, re-query engine tasks by processInstanceId
     * for RUNNING instances where the current user is the initiator in the portal DB.
     * Covers cases where assignee is not written, userId differs from engine, or RestTemplate silently fails.
     * <p>Only merges tasks the current user <strong>can process</strong> (consistent with engine assignee/candidate
     * semantics), preventing next-step tasks (e.g. BU_ROLE) from being incorrectly pushed to initiator.</p>
     */
    private void mergeTasksFromRunningProcessInstancesForUser(String userId, List<TaskInfo> allTasks) {
        String portalUsername = SecurityContextUtils.getCurrentUsername().orElse(null);
        List<ProcessInstance> running = processInstanceRepository.findByStartUserIdAndStatus(userId, "RUNNING");
        if (running.isEmpty()) {
            return;
        }
        log.info("Flowable user-task query was empty; merging from {} RUNNING process instance(s) for user {}",
                running.size(), userId);
        Set<String> seen = allTasks.stream()
                .map(TaskInfo::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        for (ProcessInstance pi : running) {
            String pid = pi.getId();
            if (pid == null || pid.isBlank()) {
                continue;
            }
            try {
                Optional<Map<String, Object>> tr = workflowEngineClient.getProcessInstanceTasks(pid);
                if (tr.isEmpty()) {
                    continue;
                }
                List<Map<String, Object>> tasks = WorkflowEnginePayloadHelper.taskListFromPayload(tr.get());
                if (tasks == null || tasks.isEmpty()) {
                    continue;
                }
                for (Map<String, Object> taskMap : tasks) {
                    TaskInfo taskInfo = EngineTaskMapper.convertMapToTaskInfo(taskMap);
                    if (taskInfo.getTaskId() == null || seen.contains(taskInfo.getTaskId())) {
                        continue;
                    }
                    if (taskProcessComponent != null && !taskProcessComponent.canProcessTask(taskInfo, userId, portalUsername)) {
                        log.debug("Fallback merge: skip task {} for user {} (not assignee/candidate/delegation pool)",
                                taskInfo.getTaskId(), userId);
                        continue;
                    }
                    seen.add(taskInfo.getTaskId());
                    allTasks.add(taskInfo);
                }
            } catch (Exception e) {
                log.warn("Fallback task query failed for processInstanceId={}: {}", pid, e.getMessage());
            }
        }
    }

    /**
     * Single round-trip to local portal DB: which of these process instances are withdrawn.
     * (Avoids N+1 {@code findById} per task in the hot path.)
     */
    private Set<String> findWithdrawnProcessInstanceIds(Collection<String> processInstanceIds) {
        if (processInstanceIds == null || processInstanceIds.isEmpty()) {
            return Collections.emptySet();
        }
        return processInstanceRepository.findAllById(processInstanceIds).stream()
                .filter(pi -> "WITHDRAWN".equals(pi.getStatus()))
                .map(ProcessInstance::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Query tasks delegated to a user.
     *
     * Delegation info is stored in the local database and combined with Flowable task info.
     */
    public List<TaskInfo> queryDelegatedTasks(String userId) {
        return delegatedTaskQueryComponent.queryDelegatedTasks(userId);
    }

    /**
     * Get task details by ID.
     */
    /**
     * Fills {@code merged} sub-table slices ({@code __subTables__}) that exist only in the live Flowable
     * engine (a service task's output the portal store never received) and persists them onto the store row
     * so a subsequent task completion carries the rows. Fill-only: existing non-empty slices win. Best-effort.
     */
    @SuppressWarnings("unchecked")
    private void hydrateEngineSubTablesIntoMerged(String processInstanceId, ProcessInstance pi, Map<String, Object> merged) {
        if (workflowEngineClient == null || !workflowEngineClient.isAvailable()) {
            return; // engine known-down: skip the read-path round-trip
        }
        Map<String, Object> current = merged.get("__subTables__") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        engineSubTableHydrator.mergeFromEngine(processInstanceId, current).ifPresent(result -> {
            Map<String, Object> cur = result.mergedSubTables();
            merged.put("__subTables__", cur);
            if (result.rowCount() != null && merged.get("rowCount") == null) {
                merged.put("rowCount", result.rowCount());
            }
            // Persist only the __subTables__ addition onto the store row (not the enriched merged view).
            Map<String, Object> storeVars = pi.getVariables() != null
                    ? new HashMap<>(pi.getVariables()) : new HashMap<>();
            storeVars.put("__subTables__", cur);
            pi.setVariables(storeVars);
            processInstanceRepository.save(pi);
        });
    }

    public Optional<TaskInfo> getTaskById(String taskId) {
        log.debug("getTaskById called with taskId: {}", taskId);

        // Check if the Flowable engine is available
        if (!workflowEngineClient.isAvailable()) {
            throw new IllegalStateException("Flowable engine unavailable, please check if workflow-engine-core service is running");
        }

        log.debug("Workflow engine is available, calling getTaskById");

        try {
            long __tEngine = System.nanoTime();
            Optional<Map<String, Object>> result = workflowEngineClient.getTaskById(taskId);
            log.info("[PERF] detail.getTaskById(engine) took {} ms", (System.nanoTime() - __tEngine) / 1_000_000L);
            log.debug("Got result from workflow engine: {}", result.isPresent());

            if (result.isPresent()) {
                Map<String, Object> responseBody = result.get();
                Map<String, Object> data = WorkflowEnginePayloadHelper.singleTaskFromPayload(responseBody);
                if (data != null) {
                    log.debug("Converting task data to TaskInfo");
                    TaskInfo taskInfo = EngineTaskMapper.convertMapToTaskInfo(data);

                    // Supplement variables from local ProcessInstance (Flowable may lose data when serializing complex nested objects)
                    String processInstanceId = taskInfo.getProcessInstanceId();
                    if (processInstanceId != null) {
                        processInstanceRepository.findById(processInstanceId).ifPresent(pi -> {
                            if (taskInfo.getInitiatorId() == null || taskInfo.getInitiatorId().isBlank()) {
                                if (pi.getInitiatorId() != null && !pi.getInitiatorId().isBlank()) {
                                    taskInfo.setInitiatorId(pi.getInitiatorId().trim());
                                } else if (pi.getStartUserId() != null && !pi.getStartUserId().isBlank()) {
                                    taskInfo.setInitiatorId(pi.getStartUserId().trim());
                                }
                            }
                            if (taskInfo.getInitiatorName() == null || taskInfo.getInitiatorName().isBlank()) {
                                if (pi.getStartUserName() != null && !pi.getStartUserName().isBlank()) {
                                    taskInfo.setInitiatorName(pi.getStartUserName().trim());
                                }
                            }
                            if (pi.getVariables() != null) {
                                Map<String, Object> merged = new java.util.HashMap<>();
                                // Flowable first, then portal snapshot — but do not squash MI element item from engine.
                                EngineTaskMapper.mergePortalProcessVariablesPreferringFlowableMiElementItem(
                                        merged, taskInfo.getVariables(), pi.getVariables());
                                miParticipantEnricher.enrichMissingParticipantRowIdsInSubTables(merged);
                                long __tEnrich = System.nanoTime();
                                processComponent.enrichSubTablesVariablesFromPhysicalTables(processInstanceId, merged);
                                log.info("[PERF] detail.enrichSubTables took {} ms", (System.nanoTime() - __tEnrich) / 1_000_000L);
                                long __tPart = System.nanoTime();
                                miParticipantEnricher.enrichParticipantAssignmentData(merged);
                                log.info("[PERF] detail.enrichParticipantAssignmentData took {} ms", (System.nanoTime() - __tPart) / 1_000_000L);
                                // Service-task outputs (e.g. an Activepieces task's __subTables__) live only in the
                                // Flowable engine and are absent from the portal store until a form submission writes
                                // them. The To-Do task detail grid reads these variables, so fill missing/empty
                                // sub-table slices from the live engine and persist them (so completion carries the rows).
                                hydrateEngineSubTablesIntoMerged(processInstanceId, pi, merged);
                                taskInfo.setVariables(merged);
                                log.debug("Merged variables from local DB for process {}, keys: {}",
                                    processInstanceId, merged.keySet());
                            }
                        });
                    }

                    // Get available task actions: only query DB and set actions when the engine returns actionIds (including empty array);
                    // if the engine did not return actionIds (node has no Actions configured), keep actions=null so the frontend does not show default Approve/Reject.
                    Object rawActionIds = data.get("actionIds");
                    if (rawActionIds != null) {
                        try {
                            long __tActions = System.nanoTime();
                            List<TaskActionInfo> actions = taskActionService.getTaskActions(taskId);
                            log.info("[PERF] detail.getTaskActions took {} ms", (System.nanoTime() - __tActions) / 1_000_000L);
                            log.debug("Got {} actions from TaskActionService", actions != null ? actions.size() : 0);
                            taskInfo.setActions(actions != null ? actions : Collections.emptyList());
                        } catch (Exception e) {
                            log.warn("Failed to get actions for task {}: {}", taskId, e.getMessage(), e);
                            taskInfo.setActions(Collections.emptyList());
                        }
                    }
                    // When rawActionIds == null, do not set actions; keep null to indicate no Actions configured on this node

                    // Request ID for the detail Basic Info (same derivation as the list); runs after
                    // variables are merged above so the configured fields are available.
                    requestIdEnricher.enrichTaskRequestIds(java.util.List.of(taskInfo));

                    return Optional.of(taskInfo);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get task by id {} from Flowable: {}", taskId, e.getMessage());
        }

        return Optional.empty();
    }

    /**
     * Apply filter criteria (legacy keyword/priority lists AND shared-list column filters).
     */
    private List<TaskInfo> applyFilters(List<TaskInfo> tasks, TaskQueryRequest request) {
        List<ListColumnFilter> columnFilters = TaskQueryColumnFilters.normalize(request.getFilters());
        return tasks.stream()
                .filter(t -> {
                    // Priority filter
                    if (request.getPriorities() != null && !request.getPriorities().isEmpty()) {
                        if (!request.getPriorities().contains(t.getPriority())) {
                            return false;
                        }
                    }
                    // Process type filter
                    if (request.getProcessTypes() != null && !request.getProcessTypes().isEmpty()) {
                        if (!request.getProcessTypes().contains(t.getProcessDefinitionKey())) {
                            return false;
                        }
                    }
                    // Status filter
                    if (request.getStatuses() != null && !request.getStatuses().isEmpty()) {
                        if (!request.getStatuses().contains(t.getStatus())) {
                            return false;
                        }
                    }
                    // Time range filter
                    if (request.getStartTime() != null && t.getCreateTime() != null) {
                        if (t.getCreateTime().isBefore(request.getStartTime())) {
                            return false;
                        }
                    }
                    if (request.getEndTime() != null && t.getCreateTime() != null) {
                        if (t.getCreateTime().isAfter(request.getEndTime())) {
                            return false;
                        }
                    }
                    // Overdue filter
                    if (Boolean.TRUE.equals(request.getIncludeOverdue())) {
                        // Only include overdue tasks
                        if (!Boolean.TRUE.equals(t.getIsOverdue())) {
                            return false;
                        }
                    }
                    // Keyword search (including initiator name)
                    if (request.getKeyword() != null && !request.getKeyword().isEmpty()) {
                        String keyword = request.getKeyword().toLowerCase();
                        boolean matches = (t.getTaskName() != null && t.getTaskName().toLowerCase().contains(keyword))
                                || (t.getDescription() != null && t.getDescription().toLowerCase().contains(keyword))
                                || (t.getProcessDefinitionName() != null && t.getProcessDefinitionName().toLowerCase().contains(keyword))
                                || (t.getInitiatorName() != null && t.getInitiatorName().toLowerCase().contains(keyword));
                        if (!matches) {
                            return false;
                        }
                    }
                    if (!columnFilters.isEmpty() && !TaskQueryColumnFilters.matches(t, columnFilters)) {
                        return false;
                    }
                    // Optional assignmentTypes (Mine) — DELEGATED-only is handled earlier.
                    if (request.getAssignmentTypes() != null && !request.getAssignmentTypes().isEmpty()) {
                        List<String> types = request.getAssignmentTypes().stream()
                                .filter(s -> s != null && !"DELEGATED".equalsIgnoreCase(s))
                                .toList();
                        if (!types.isEmpty()) {
                            String at = t.getAssignmentType();
                            if (at == null || types.stream().noneMatch(x -> x.equalsIgnoreCase(at))) {
                                return false;
                            }
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * Get task statistics.
     */
    public TaskStatistics getTaskStatistics(String userId) {
        return taskHistoryComponent.getTaskStatistics(userId);
    }

    /**
     * Get task flow history.
     */
    public List<TaskHistoryInfo> getTaskHistory(String taskId) {
        return taskHistoryComponent.getTaskHistory(taskId);
    }

    /**
     * Get task flow history. When {@code processInstanceId} is known (e.g. from task detail), skip an extra
     * workflow-engine {@code getTaskInfo} round-trip inside {@code GET /tasks/{taskId}/history}.
     */
    public List<TaskHistoryInfo> getTaskHistory(String taskId, String processInstanceId) {
        return taskHistoryComponent.getTaskHistory(taskId, processInstanceId);
    }

    /**
     * Query tasks completed by a user.
     */
    public PortalListPage<TaskInfo> queryCompletedTasks(String userId, CompletedTaskQueryRequest request) {
        return completedTaskListQueryComponent.query(userId, request);
    }
}
