package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.dto.CompletedTaskQueryRequest;
import com.portal.dto.MyTaskRef;
import com.portal.dto.PageResponse;
import com.portal.dto.PortalListPage;
import com.portal.dto.TaskHistoryInfo;
import com.portal.dto.TaskInfo;
import com.portal.dto.TaskQueryRequest;
import com.portal.dto.TaskStatistics;
import com.portal.dto.TodoTaskQueryRequest;
import com.portal.exception.PortalException;
import com.portal.util.EngineTaskPushdown;
import com.portal.util.ListQuerySupport;
import com.portal.util.TaskInfoListOps;
import com.portal.util.TaskInfoQueryFilters;
import com.portal.util.TodoTaskColumnSpec;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mine task query facade. To Do lives in {@link TodoListQueryComponent};
 * detail in {@link TaskDetailQueryComponent}; engine pages in {@link EngineVisibleTaskFetcher}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskQueryComponent {

    static final String TODO_LIST_KEY = "todo-tasks";

    private final WorkflowEngineClient workflowEngineClient;
    private final DelegatedTaskQueryComponent delegatedTaskQueryComponent;
    private final TaskHistoryComponent taskHistoryComponent;
    private final RequestIdEnricher requestIdEnricher;
    private final CompletedTaskListQueryComponent completedTaskListQueryComponent;
    private final MineTaskScanner mineTaskScanner;
    private final EngineVisibleTaskFetcher engineVisibleTaskFetcher;
    private final TaskDetailQueryComponent taskDetailQueryComponent;
    private final TodoListQueryComponent todoListQueryComponent;
    private final MineTaskListCache mineTaskListCache;

    @PostConstruct
    public void init() {
        log.info("TaskQueryComponent initialized, workflow engine available: {}", workflowEngineClient.isAvailable());
    }

    public PageResponse<TaskInfo> queryTasks(TaskQueryRequest request) {
        if (!workflowEngineClient.isAvailable()) {
            throw new IllegalStateException(
                    "Flowable engine unavailable, please check if workflow-engine-core service is running");
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
            return queryDelegatedTasksOnlyPage(userId, request, page, size);
        }
        return EngineTaskPushdown.canFullyPush(request)
                ? queryTasksEngineWindowMine(userId, request, assignmentTypes, page, size, push)
                : queryTasksFullEnginePagesMine(userId, request, assignmentTypes, page, size);
    }

    public PortalListPage<TaskInfo> queryTodoList(String userId, TodoTaskQueryRequest request) {
        long started = System.nanoTime();
        TaskQueryRequest adapted = TodoListQueryComponent.toTaskQuery(userId, request);
        if (!isDelegatedOnlyAssignmentFilter(adapted.getAssignmentTypes())) {
            return todoListQueryComponent.queryTodoList(userId, request);
        }
        PageResponse<TaskInfo> page = queryTasks(adapted);
        long total = page.getTotalElements();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        ListQuerySupport.logIfSlow(log, TODO_LIST_KEY, request.page(), request.size(), total, started);
        ListQuerySupport.logIfOverSla(log, TODO_LIST_KEY, request.page(), request.size(), total,
                elapsedMs, elapsedMs, 0L);
        return new PortalListPage<>(TodoTaskColumnSpec.columns(), page.getContent(),
                request.page(), request.size(), total);
    }

    public List<TaskInfo> listClaimPoolTasks(String userId) {
        return todoListQueryComponent.listClaimPoolTasks(userId);
    }

    public List<TaskInfo> listMergedTodoTasks(String userId) {
        return todoListQueryComponent.listMergedTodoTasks(userId);
    }

    /**
     * To Do tasks the user holds on each of the given process instances, for the Views grid's
     * per-row task marker. Delegates to
     * {@link TodoListQueryComponent#findMyTaskRefsByProcessInstance}.
     */
    public Map<String, List<MyTaskRef>> findMyTaskRefsByProcessInstance(
            String userId, Collection<String> processInstanceIds) {
        return todoListQueryComponent.findMyTaskRefsByProcessInstance(userId, processInstanceIds);
    }

    public void invalidateMineTaskListCache() {
        mineTaskListCache.invalidate();
    }

    public Optional<TaskInfo> getTaskById(String taskId) {
        return taskDetailQueryComponent.getTaskById(taskId);
    }

    public List<TaskInfo> queryDelegatedTasks(String userId) {
        return delegatedTaskQueryComponent.queryDelegatedTasks(userId);
    }

    public TaskStatistics getTaskStatistics(String userId) {
        return taskHistoryComponent.getTaskStatistics(userId);
    }

    public List<TaskHistoryInfo> getTaskHistory(String taskId) {
        return taskHistoryComponent.getTaskHistory(taskId);
    }

    public List<TaskHistoryInfo> getTaskHistory(String taskId, String processInstanceId) {
        return taskHistoryComponent.getTaskHistory(taskId, processInstanceId);
    }

    public PortalListPage<TaskInfo> queryCompletedTasks(String userId, CompletedTaskQueryRequest request) {
        return completedTaskListQueryComponent.query(userId, request);
    }

    static boolean isDelegatedOnlyAssignmentFilter(List<String> assignmentTypes) {
        return assignmentTypes != null
                && assignmentTypes.size() == 1
                && "DELEGATED".equalsIgnoreCase(assignmentTypes.get(0));
    }

    private PageResponse<TaskInfo> queryDelegatedTasksOnlyPage(
            String userId, TaskQueryRequest request, int page, int size) {
        List<TaskInfo> allTasks = new ArrayList<>(queryDelegatedTasks(userId));
        allTasks = mineTaskScanner.applyPortalPostEngineFilters(userId, allTasks);
        if (TaskInfoQueryFilters.needsRequestIdEnrichment(request)) {
            requestIdEnricher.enrichTaskRequestIds(allTasks);
        }
        allTasks = TaskInfoQueryFilters.apply(allTasks, request);
        allTasks = TaskInfoListOps.applySorting(allTasks, request);
        List<TaskInfo> pagedTasks = TaskInfoListOps.pageOf(allTasks, page, size);
        requestIdEnricher.enrichTaskRequestIds(pagedTasks);
        EngineTaskMapper.clearTaskVariablesForList(pagedTasks);
        return PageResponse.of(pagedTasks, page, size, allTasks.size());
    }

    private PageResponse<TaskInfo> queryTasksFullEnginePagesMine(
            String userId, TaskQueryRequest request, List<String> assignmentTypes, int page, int size) {
        List<TaskInfo> filteredSorted = mineTaskScanner.scanMineTasks(userId, request, assignmentTypes, size);
        List<TaskInfo> pagedTasks = new ArrayList<>(TaskInfoListOps.pageOf(filteredSorted, page, size));
        requestIdEnricher.enrichTaskRequestIds(pagedTasks);
        EngineTaskMapper.clearTaskVariablesForList(pagedTasks);
        return PageResponse.of(pagedTasks, page, size, filteredSorted.size());
    }

    private PageResponse<TaskInfo> queryTasksEngineWindowMine(
            String userId, TaskQueryRequest request, List<String> assignmentTypes, int page, int size,
            EngineTaskPushdown.Criteria push) {
        long t0 = System.nanoTime();
        var engineResult = engineVisibleTaskFetcher.fetchWindowWithRequestContext(
                userId, assignmentTypes, page, size, push);
        List<TaskInfo> engineTasks = engineResult.tasks();
        long engineTotal = engineResult.engineTotal();
        long tJoin = System.nanoTime();
        List<TaskInfo> pageTasks = mineTaskScanner.applyPortalPostEngineFilters(userId, new ArrayList<>(engineTasks));
        if (pageTasks.size() != engineTasks.size() || engineTasks.size() > size) {
            log.info("[PERF] engine window unusable after post-filter (engine={} filtered={} size={}); fullScan",
                    engineTasks.size(), pageTasks.size(), size);
            return queryTasksFullEnginePagesMine(userId, request, assignmentTypes, page, size);
        }
        log.info("[PERF] query.window mine engine={}ms postFilter={}ms engineTasks={} total={} push={}",
                (tJoin - t0) / 1_000_000L, (System.nanoTime() - tJoin) / 1_000_000L,
                engineTasks.size(), engineTotal, push != null && push.hasAny());
        requestIdEnricher.enrichTaskRequestIds(pageTasks);
        EngineTaskMapper.clearTaskVariablesForList(pageTasks);
        long totalElements = engineTotal > 0 ? engineTotal : pageTasks.size();
        return PageResponse.of(pageTasks, page, size, totalElements);
    }
}
