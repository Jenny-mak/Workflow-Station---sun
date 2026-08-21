package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.dto.PageResponse;
import com.portal.dto.PortalListPage;
import com.portal.dto.CompletedTaskQueryRequest;
import com.portal.dto.TaskActionInfo;
import com.portal.dto.TaskInfo;
import com.portal.dto.TaskQueryRequest;
import com.portal.dto.TaskStatistics;
import com.portal.dto.TaskHistoryInfo;
import com.portal.util.RequestContextInheritanceUtils;
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
import java.util.concurrent.CompletableFuture;
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
     * <p>Default: fetch pending tasks from the engine using the requested {@code page}/{@code size},
     * in parallel with {@link #queryDelegatedTasks} (when applicable), then merge, filter by portal rules,
     * sort, and paginate the merged list. No hard 1000-row limit.
     * Keyword/priority filters need the full engine task list; fetch all engine pages by {@code size},
     * then merge with delegated tasks before filtering.</p>
     */
    public PageResponse<TaskInfo> queryTasks(TaskQueryRequest request) {
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

        if (isDelegatedOnlyAssignmentFilter(assignmentTypes)) {
            return queryDelegatedTasksOnlyPage(userId, request, page, size);
        }

        if (needsFullEngineScanBeforeFilters(request)) {
            return queryTasksFullEnginePagesThenDelegate(userId, request, assignmentTypes, page, size);
        }

        return queryTasksEngineWindowThenDelegate(userId, request, assignmentTypes, page, size);
    }

    private static boolean isDelegatedOnlyAssignmentFilter(List<String> assignmentTypes) {
        return assignmentTypes != null
                && assignmentTypes.size() == 1
                && "DELEGATED".equalsIgnoreCase(assignmentTypes.get(0));
    }

    /**
     * Keyword, priority, etc. must be filtered over the full engine task list;
     * fetch all pages from the engine with the requested page size (no row limit).
     */
    private boolean needsFullEngineScanBeforeFilters(TaskQueryRequest request) {
        if (request.getPriorities() != null && !request.getPriorities().isEmpty()) {
            return true;
        }
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            return true;
        }
        if (request.getProcessTypes() != null && !request.getProcessTypes().isEmpty()) {
            return true;
        }
        if (request.getStatuses() != null && !request.getStatuses().isEmpty()) {
            return true;
        }
        if (request.getStartTime() != null || request.getEndTime() != null) {
            return true;
        }
        if (Boolean.TRUE.equals(request.getIncludeOverdue())) {
            return true;
        }
        String sortBy = request.getSortBy();
        if (sortBy != null && !sortBy.isBlank() && !"createTime".equalsIgnoreCase(sortBy)) {
            return true;
        }
        String sortDir = request.getSortDirection();
        return sortDir != null && !sortDir.isBlank() && !"desc".equalsIgnoreCase(sortDir);
    }

    private PageResponse<TaskInfo> queryDelegatedTasksOnlyPage(
            String userId, TaskQueryRequest request, int page, int size) {
        List<TaskInfo> allTasks = new ArrayList<>(queryDelegatedTasks(userId));
        allTasks = applyPortalPostEngineFilters(userId, allTasks);
        allTasks = applyFilters(allTasks, request);
        allTasks = applySorting(allTasks, request);
        int start = page * size;
        int end = Math.min(start + size, allTasks.size());
        List<TaskInfo> pagedTasks = start < allTasks.size()
                ? allTasks.subList(start, end)
                : Collections.emptyList();
        requestIdEnricher.enrichTaskRequestIds(pagedTasks);
        EngineTaskMapper.clearTaskVariablesForList(pagedTasks);
        return PageResponse.of(pagedTasks, page, size, allTasks.size());
    }

    private PageResponse<TaskInfo> queryTasksFullEnginePagesThenDelegate(
            String userId,
            TaskQueryRequest request,
            List<String> assignmentTypes,
            int page,
            int size) {
        boolean includeDelegated = assignmentTypes == null || assignmentTypes.isEmpty()
                || assignmentTypes.contains("DELEGATED");

        SecurityContext ctx = SecurityContextHolder.getContext();
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        CompletableFuture<List<TaskInfo>> engineAllFuture = CompletableFuture.supplyAsync(() ->
                RequestContextInheritanceUtils.runWithInheritedRequestAndSecurity(ctx, attrs,
                        () -> fetchAllEngineTasksPaged(userId, assignmentTypes, size)), taskQueryExecutor);

        CompletableFuture<List<TaskInfo>> delegatedFuture = includeDelegated
                ? CompletableFuture.supplyAsync(() ->
                RequestContextInheritanceUtils.runWithInheritedRequestAndSecurity(ctx, attrs, () -> queryDelegatedTasks(userId)), taskQueryExecutor)
                : CompletableFuture.completedFuture(Collections.emptyList());

        long __tF0 = System.nanoTime();
        List<TaskInfo> engineTasks = engineAllFuture.join();
        List<TaskInfo> delegated = delegatedFuture.join();
        long __tFJoin = System.nanoTime();

        List<TaskInfo> allTasks = new ArrayList<>(engineTasks);
        allTasks.addAll(delegated);
        allTasks = applyPortalPostEngineFilters(userId, allTasks);
        long __tFFilter = System.nanoTime();
        log.info("[PERF] query.fullScan parallel(engineAllPages+delegated)={}ms postFilter={}ms engineTasks={} delegated={} merged={}",
                (__tFJoin - __tF0) / 1_000_000L, (__tFFilter - __tFJoin) / 1_000_000L,
                engineTasks.size(), delegated.size(), allTasks.size());
        allTasks = applyFilters(allTasks, request);
        allTasks = applySorting(allTasks, request);
        int start = page * size;
        int end = Math.min(start + size, allTasks.size());
        List<TaskInfo> pagedTasks = start < allTasks.size()
                ? allTasks.subList(start, end)
                : Collections.emptyList();
        requestIdEnricher.enrichTaskRequestIds(pagedTasks);
        EngineTaskMapper.clearTaskVariablesForList(pagedTasks);
        return PageResponse.of(pagedTasks, page, size, allTasks.size());
    }

    /**
     * Engine single-page fetch result (with totalCount, for pagination approximation).
     */
    private record EngineWindowResult(List<TaskInfo> tasks, long engineTotal) {
    }

    /**
     * Fetch one page of tasks from the engine using page/size; merge from the initiator's
     * RUNNING instances as a fallback path if the result is empty.
     */
    private EngineWindowResult fetchEngineTaskPageWindow(
            String userId, List<String> assignmentTypes, int page, int size) {
        List<TaskInfo> engineTasks = new ArrayList<>();
        long engineTotal = 0L;
        try {
            long __tw0 = System.nanoTime();
            List<String> groupIds = workspaceTaskFilter.getUserVirtualGroups(userId);
            groupIds = workspaceTaskFilter.filterVirtualGroupsForActiveWorkspace(userId, groupIds);
            long __twVg = System.nanoTime();
            boolean includeGroups = assignmentTypes == null || assignmentTypes.isEmpty()
                    || assignmentTypes.contains("VIRTUAL_GROUP");
            Optional<Map<String, Object>> result = includeGroups
                    ? workflowEngineClient.getUserAllVisibleTasks(userId, groupIds, Collections.emptyList(), page, size)
                    : workflowEngineClient.getUserTasks(userId, page, size);
            log.info("[PERF] engineWindow vgroups={}ms engineHttp={}ms groupCount={} includeGroups={}",
                    (__twVg - __tw0) / 1_000_000L, (System.nanoTime() - __twVg) / 1_000_000L,
                    groupIds != null ? groupIds.size() : 0, includeGroups);
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

        if (engineTasks.isEmpty()) {
            mergeTasksFromRunningProcessInstancesForUser(userId, engineTasks);
        }
        return new EngineWindowResult(engineTasks, engineTotal);
    }

    /**
     * Fetch engine tasks using the requested {@code page}/{@code size}, then synchronously fetch
     * delegated tasks; merge, sort, then slice (current page = page {@code page} of merged list).
     * Engine and delegated task fetches run in parallel when possible to reduce first-screen latency.
     */
    private PageResponse<TaskInfo> queryTasksEngineWindowThenDelegate(
            String userId,
            TaskQueryRequest request,
            List<String> assignmentTypes,
            int page,
            int size) {
        boolean includeDelegated = assignmentTypes == null || assignmentTypes.isEmpty()
                || assignmentTypes.contains("DELEGATED");

        SecurityContext ctx = SecurityContextHolder.getContext();
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        CompletableFuture<EngineWindowResult> engineFuture = CompletableFuture.supplyAsync(() ->
                RequestContextInheritanceUtils.runWithInheritedRequestAndSecurity(ctx, attrs,
                        () -> fetchEngineTaskPageWindow(userId, assignmentTypes, page, size)), taskQueryExecutor);

        CompletableFuture<List<TaskInfo>> delegatedFuture = includeDelegated
                ? CompletableFuture.supplyAsync(() ->
                RequestContextInheritanceUtils.runWithInheritedRequestAndSecurity(ctx, attrs, () -> queryDelegatedTasks(userId)), taskQueryExecutor)
                : CompletableFuture.completedFuture(Collections.emptyList());

        long __tQ0 = System.nanoTime();
        EngineWindowResult engineResult = engineFuture.join();
        List<TaskInfo> engineTasks = engineResult.tasks();
        long engineTotal = engineResult.engineTotal();
        List<TaskInfo> delegated = delegatedFuture.join();
        long __tQJoin = System.nanoTime();

        List<TaskInfo> allTasks = new ArrayList<>(engineTasks);
        allTasks.addAll(delegated);
        allTasks = applyPortalPostEngineFilters(userId, allTasks);
        long __tQFilter = System.nanoTime();
        log.info("[PERF] query.window parallel(engine+delegated)={}ms postFilter={}ms engineTasks={} delegated={} merged={}",
                (__tQJoin - __tQ0) / 1_000_000L, (__tQFilter - __tQJoin) / 1_000_000L,
                engineTasks.size(), delegated.size(), allTasks.size());
        allTasks = applySorting(allTasks, request);

        int start = page * size;
        int end = Math.min(start + size, allTasks.size());
        List<TaskInfo> pagedTasks = start < allTasks.size()
                ? allTasks.subList(start, end)
                : Collections.emptyList();

        boolean enginePageComplete = engineTasks.size() < size || engineTotal <= (long) (page + 1) * size;
        long totalElements = enginePageComplete
                ? allTasks.size()
                : Math.max(engineTotal + (long) delegated.size(), (long) allTasks.size());

        requestIdEnricher.enrichTaskRequestIds(pagedTasks);
        EngineTaskMapper.clearTaskVariablesForList(pagedTasks);
        return PageResponse.of(pagedTasks, page, size, totalElements);
    }

    private List<TaskInfo> fetchAllEngineTasksPaged(String userId, List<String> assignmentTypes, int pageSize) {
        List<String> groupIds = workspaceTaskFilter.getUserVirtualGroups(userId);
        groupIds = workspaceTaskFilter.filterVirtualGroupsForActiveWorkspace(userId, groupIds);
        boolean includeGroups = assignmentTypes == null || assignmentTypes.isEmpty()
                || assignmentTypes.contains("VIRTUAL_GROUP");
        List<TaskInfo> out = new ArrayList<>();
        for (int p = 0; ; p++) {
            Optional<Map<String, Object>> result = includeGroups
                    ? workflowEngineClient.getUserAllVisibleTasks(userId, groupIds, Collections.emptyList(), p, pageSize)
                    : workflowEngineClient.getUserTasks(userId, p, pageSize);
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
            if (tasks.size() < pageSize) {
                break;
            }
        }
        if (out.isEmpty()) {
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
     * Apply filter criteria.
     */
    private List<TaskInfo> applyFilters(List<TaskInfo> tasks, TaskQueryRequest request) {
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
                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * Apply sorting.
     */
    private List<TaskInfo> applySorting(List<TaskInfo> tasks, TaskQueryRequest request) {
        String sortBy = request.getSortBy() != null ? request.getSortBy() : "createTime";
        boolean ascending = "asc".equalsIgnoreCase(request.getSortDirection());

        Comparator<TaskInfo> comparator = switch (sortBy) {
            case "priority" -> Comparator.comparing(TaskInfo::getPriority, Comparator.nullsLast(Comparator.naturalOrder()));
            case "dueDate" -> Comparator.comparing(TaskInfo::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()));
            case "taskName" -> Comparator.comparing(TaskInfo::getTaskName, Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(TaskInfo::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder()));
        };

        if (!ascending) {
            comparator = comparator.reversed();
        }

        return tasks.stream().sorted(comparator).collect(Collectors.toList());
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
