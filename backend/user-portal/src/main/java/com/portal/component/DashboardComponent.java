package com.portal.component;

import com.platform.security.entity.BusinessUnit;
import com.platform.security.entity.UserBusinessUnit;
import com.portal.client.WorkflowEngineClient;
import com.portal.dto.DashboardOverview;
import com.portal.dto.PageResponse;
import com.portal.dto.ProcessInfo;
import com.portal.dto.TaskInfo;
import com.portal.dto.TaskQueryRequest;
import com.portal.dto.TeamRequestsResponse;
import com.portal.entity.ProcessInstance;
import com.portal.repository.BusinessUnitRepository;
import com.portal.repository.ProcessDraftRepository;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.repository.UserBusinessUnitRepository;
import com.portal.service.UserDisplayNameResolver;
import com.portal.util.RequestContextInheritanceUtils;
import com.platform.security.util.SecurityContextUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Dashboard aggregation component.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardComponent {

    private final TaskQueryComponent taskQueryComponent;
    private final WorkflowEngineClient workflowEngineClient;
    private final BusinessUnitRepository businessUnitRepository;
    private final UserBusinessUnitRepository userBusinessUnitRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ProcessDraftRepository processDraftRepository;
    private final UserDisplayNameResolver userDisplayNameResolver;
    private final RequestIdEnricher requestIdEnricher;

    /**
     * 聚合扇出线程池（独立于 taskQueryExecutor）。团队聚合分支在任务体内再调 {@code queryTasks}
     * （→ taskQueryExecutor），必须分池以免同池自等待死锁；其余分支为 engine HTTP，移出 commonPool。
     * 见 {@link com.portal.config.PortalAsyncConfig}。
     */
    @Autowired
    @Qualifier(com.portal.config.PortalAsyncConfig.AGGREGATION_EXECUTOR)
    private java.util.concurrent.Executor aggregationExecutor;

    /**
     * When true, home "team task overview" calls queryTasks per BU member (very slow; can overload engine with many members).
     * Default false: team counts match personal todo (no aggregation); enable via env when team rollup is needed in production.
     */
    @Value("${portal.dashboard.team-task-aggregation-enabled:false}")
    private boolean teamTaskAggregationEnabled;

    @Value("${portal.dashboard.overview-cache-ttl-seconds:0}")
    private int overviewCacheTtlSeconds;

    @Value("${portal.dashboard.skip-avg-processing-hours:true}")
    private boolean skipAvgProcessingHoursForOverview;

    private final Map<String, OverviewCacheEntry> overviewCache = new ConcurrentHashMap<>();

    private static final class OverviewCacheEntry {
        final DashboardOverview overview;
        final long expiresAtMillis;

        OverviewCacheEntry(DashboardOverview overview, long expiresAtMillis) {
            this.overview = overview;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private String overviewCacheKey(String userId) {
        String bu = SecurityContextUtils.getCurrentActiveBusinessUnitId().orElse("");
        return userId + "|" + bu;
    }

    /**
     * Fan out onto {@link #aggregationExecutor} carrying the caller's request + security context.
     *
     * <p>Plain {@code supplyAsync} loses them: the worker thread has an empty
     * {@code RequestContextHolder}, so {@link WorkflowEngineClient} finds no inbound Authorization to
     * forward and workflow-engine answers 403. Every branch below swallows that into a zero-valued
     * result, so the failure is invisible in the UI — the Home "Process Overview" card showed 0/0/0
     * for every user while the engine logged one 403 per dashboard load.</p>
     */
    private <T> CompletableFuture<T> supplyWithRequestContext(Supplier<T> supplier) {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        ServletRequestAttributes requestAttributes =
                RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra ? sra : null;
        return CompletableFuture.supplyAsync(
                () -> RequestContextInheritanceUtils.runWithInheritedRequestAndSecurity(
                        securityContext, requestAttributes, supplier::get),
                aggregationExecutor);
    }

    /**
     * Returns dashboard overview data
     */
    public DashboardOverview getDashboardOverview(String userId) {
        if (overviewCacheTtlSeconds > 0) {
            String ck = overviewCacheKey(userId);
            OverviewCacheEntry hit = overviewCache.get(ck);
            if (hit != null && hit.expiresAtMillis > System.currentTimeMillis()) {
                return hit.overview;
            }
        }

        // Fetch process stats in parallel with the exact Mine ∪ claim-pool set shown on To Do.
        // Dashboard task rows must carry task ids from that list; process-instance ids belong to
        // My Requests and would open the wrong form.
        CompletableFuture<DashboardOverview.ProcessOverview> processOverviewFuture =
                supplyWithRequestContext(() -> getProcessOverview(userId));

        List<TaskInfo> dashboardTasks = new ArrayList<>(taskQueryComponent.listMergedTodoTasks(userId));
        requestIdEnricher.enrichTaskRequestIds(dashboardTasks);
        dashboardTasks.sort(Comparator.comparing(
                TaskInfo::getCreateTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        PageResponse<TaskInfo> taskPage = PageResponse.of(
                dashboardTasks, 0, Math.max(1, dashboardTasks.size()), dashboardTasks.size());

        // Overlaps with history inside buildTaskOverviewFromPage (local CPU or light logic)
        CompletableFuture<DashboardOverview.PerformanceOverview> performanceFuture =
                supplyWithRequestContext(() -> getPerformanceOverview(userId));

        DashboardOverview.TaskOverview taskOverview = buildTaskOverviewFromPage(userId, taskPage);
        List<TaskInfo> recentTasks = taskPage.getContent().stream().limit(5).toList();

        CompletableFuture.allOf(processOverviewFuture, performanceFuture).join();
        DashboardOverview.ProcessOverview processOverview = processOverviewFuture.join();
        DashboardOverview.PerformanceOverview performanceOverview = performanceFuture.join();

        DashboardOverview built = DashboardOverview.builder()
                .taskOverview(taskOverview)
                .processOverview(processOverview)
                .performanceOverview(performanceOverview)
                .recentTasks(recentTasks)
                .recentProcesses(getRecentProcesses(userId, 5))
                .build();

        if (overviewCacheTtlSeconds > 0) {
            overviewCache.put(
                    overviewCacheKey(userId),
                    new OverviewCacheEntry(built, System.currentTimeMillis() + overviewCacheTtlSeconds * 1000L));
        }
        return built;
    }

    /**
     * Returns task overview
     */
    public DashboardOverview.TaskOverview getTaskOverview(String userId) {
        TaskQueryRequest request = TaskQueryRequest.builder()
                .userId(userId)
                .page(0)
                .size(1000)
                .build();
        PageResponse<TaskInfo> taskPage = taskQueryComponent.queryTasks(request);
        return buildTaskOverviewFromPage(userId, taskPage);
    }

    private DashboardOverview.TaskOverview buildTaskOverviewFromPage(String userId, PageResponse<TaskInfo> taskPage) {
        List<TaskInfo> allTasks = taskPage.getContent();
        long pendingCount = taskPage.getTotalElements();
        long claimableCount = 0;
        long todoCount = 0;
        long overdueCount = 0;
        long urgentCount = 0;
        long highPriorityCount = 0;
        for (TaskInfo t : allTasks) {
            if (t == null) {
                continue;
            }
            if (Boolean.TRUE.equals(t.getIsOverdue())) {
                overdueCount++;
            }
            if (t.isClaimable()) {
                claimableCount++;
            }
            if (!t.isClaimPoolTask() || t.isClaimedByCurrentUser()) {
                todoCount++;
            }
            String p = t.getPriority();
            if (p != null) {
                if ("URGENT".equals(p) || "CRITICAL".equals(p)) {
                    urgentCount++;
                } else if ("HIGH".equals(p)) {
                    highPriorityCount++;
                }
            }
        }

        String completedRangeStart = LocalDate.now().atStartOfDay().toString();
        String completedRangeEnd = LocalDateTime.now().toString();

        long completedTodayCount;
        double avgProcessingHours;
        if (skipAvgProcessingHoursForOverview) {
            completedTodayCount = fetchCompletedTasksTotalInRange(
                    userId, completedRangeStart, completedRangeEnd);
            avgProcessingHours = 2.5;
        } else {
            CompletableFuture<Long> completedTodayFuture = supplyWithRequestContext(() ->
                    fetchCompletedTasksTotalInRange(userId, completedRangeStart, completedRangeEnd));
            CompletableFuture<Double> avgHoursFuture = supplyWithRequestContext(() ->
                    estimateAvgProcessingHoursFromRecentCompletions(userId));
            completedTodayCount = completedTodayFuture.join();
            avgProcessingHours = avgHoursFuture.join();
        }

        // Team aggregation logic
        long teamPendingCount = pendingCount;
        long teamOverdueCount = overdueCount;
        long teamCompletedTodayCount = completedTodayCount;

        final int MAX_TEAM_MEMBERS = 20;

        if (teamTaskAggregationEnabled) {
            try {
                Optional<String> activeBuOpt = SecurityContextUtils.getCurrentActiveBusinessUnitId();
            String activeBuId = activeBuOpt.orElse(null);
            if (activeBuId != null) {
                Set<String> allBuIds = new HashSet<>();
                allBuIds.add(activeBuId);
                collectChildBuIds(activeBuId, allBuIds);

                List<UserBusinessUnit> allMembers = userBusinessUnitRepository
                        .findByBusinessUnitIdIn(new ArrayList<>(allBuIds));
                List<String> teamMemberIds = allMembers.stream()
                        .map(UserBusinessUnit::getUserId)
                        .distinct()
                        .limit(MAX_TEAM_MEMBERS)
                        .toList();

                String todayStart = LocalDate.now().atStartOfDay().toString();
                String now = LocalDateTime.now().toString();

                List<CompletableFuture<long[]>> futures = teamMemberIds.stream()
                        .map(memberId -> supplyWithRequestContext(() -> {  // aggregationExecutor: 体内调 queryTasks(→taskQueryExecutor)，分池避免自等待死锁
                            long memberPending = 0;
                            long memberOverdue = 0;
                            long memberCompleted = 0;
                            try {
                                TaskQueryRequest memberRequest = TaskQueryRequest.builder()
                                        .userId(memberId)
                                        .page(0)
                                        .size(1000)
                                        .build();
                                PageResponse<TaskInfo> memberPage = taskQueryComponent.queryTasks(memberRequest);
                                memberPending = memberPage.getTotalElements();
                                memberOverdue = memberPage.getContent().stream()
                                        .filter(t -> Boolean.TRUE.equals(t.getIsOverdue()))
                                        .count();
                            } catch (Exception e) {
                                log.warn("Failed to get tasks for team member {}: {}", memberId, e.getMessage());
                            }
                            try {
                                memberCompleted = fetchCompletedTasksTotalInRange(memberId, todayStart, now);
                            } catch (Exception e) {
                                log.warn("Failed to get completed tasks for team member {}: {}", memberId, e.getMessage());
                            }
                            return new long[]{memberPending, memberOverdue, memberCompleted};
                        }))
                        .toList();

                long aggregatedPending = 0;
                long aggregatedOverdue = 0;
                long aggregatedCompletedToday = 0;
                for (CompletableFuture<long[]> f : futures) {
                    try {
                        long[] counts = f.join();
                        aggregatedPending += counts[0];
                        aggregatedOverdue += counts[1];
                        aggregatedCompletedToday += counts[2];
                    } catch (Exception e) {
                        log.warn("Failed to aggregate team member metrics: {}", e.getMessage());
                    }
                }

                teamPendingCount = aggregatedPending;
                teamOverdueCount = aggregatedOverdue;
                teamCompletedTodayCount = aggregatedCompletedToday;
            }
        } catch (Exception e) {
            log.warn("Failed to aggregate team metrics, falling back to personal values: {}", e.getMessage());
            teamPendingCount = pendingCount;
            teamOverdueCount = overdueCount;
            teamCompletedTodayCount = completedTodayCount;
        }
        }

        return DashboardOverview.TaskOverview.builder()
                .pendingCount(pendingCount)
                .claimableCount(claimableCount)
                .todoCount(todoCount)
                .overdueCount(overdueCount)
                .completedTodayCount(completedTodayCount)
                .avgProcessingHours(Math.round(avgProcessingHours * 10) / 10.0)
                .urgentCount(urgentCount)
                .highPriorityCount(highPriorityCount)
                .teamPendingCount(teamPendingCount)
                .teamOverdueCount(teamOverdueCount)
                .teamCompletedTodayCount(teamCompletedTodayCount)
                .build();
    }

    /**
     * Only {@code totalElements} is read here, and the engine derives it from {@code query.count()}
     * independently of the page size — so ask for a single row instead of a thousand. The rows we
     * used to request were all built into response maps and then thrown away.
     */
    private long fetchCompletedTasksTotalInRange(String userId, String startIso, String endIso) {
        try {
            Optional<Map<String, Object>> result = workflowEngineClient.getCompletedTasks(
                    userId, 0, 1, null, startIso, endIso);
            if (result.isPresent()) {
                Object totalElements = result.get().get("totalElements");
                if (totalElements instanceof Number) {
                    return ((Number) totalElements).longValue();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get completed tasks count: {}", e.getMessage());
        }
        return 0L;
    }

    /** Estimates average handling time (hours) from recent completed tasks; returns 2.5 on failure */
    private double estimateAvgProcessingHoursFromRecentCompletions(String userId) {
        try {
            Optional<Map<String, Object>> result = workflowEngineClient.getCompletedTasks(
                    userId, 0, 100, null, null, null);
            if (result.isPresent()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> content = (List<Map<String, Object>>) result.get().get("content");
                if (content != null && !content.isEmpty()) {
                    double totalDuration = content.stream()
                            .filter(t -> t.get("durationInMillis") != null)
                            .mapToLong(t -> ((Number) t.get("durationInMillis")).longValue())
                            .average()
                            .orElse(0);
                    return totalDuration / (1000 * 60 * 60);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to calculate avg processing hours: {}", e.getMessage());
        }
        return 2.5;
    }

    /**
     * Recursively collects all child BU IDs
     */
    private void collectChildBuIds(String parentBuId, Set<String> allBuIds) {
        List<BusinessUnit> children = businessUnitRepository.findByParentIdAndStatus(parentBuId, "ACTIVE");
        for (BusinessUnit child : children) {
            if (allBuIds.add(child.getId())) {
                collectChildBuIds(child.getId(), allBuIds);
            }
        }
    }

    /**
     * Returns team applications (process instances started by members of user's BU and child BUs)
     */
    public TeamRequestsResponse getTeamRequests(String userId, String status, int page, int size) {
        Set<String> teamMemberIds = resolveTeamMemberIds(userId);
        if (teamMemberIds.isEmpty()) {
            return TeamRequestsResponse.builder()
                    .content(List.of())
                    .build();
        }

        long overallCount = processInstanceRepository.countByStartUserIdIn(teamMemberIds);
        long runningCount = processInstanceRepository.countByStartUserIdInAndStatus(teamMemberIds, "RUNNING");
        long completedCount = processInstanceRepository.countByStartUserIdInAndStatus(teamMemberIds, "COMPLETED");
        long withdrawnCount = processInstanceRepository.countByStartUserIdInAndStatus(teamMemberIds, "WITHDRAWN");

        Pageable pageable = PageRequest.of(page, size);
        Page<ProcessInstance> resultPage;
        if (status == null || status.isBlank()) {
            resultPage = processInstanceRepository.findByStartUserIdInOrderByStartTimeDesc(teamMemberIds, pageable);
        } else {
            resultPage = processInstanceRepository.findByStartUserIdInAndStatusOrderByStartTimeDesc(teamMemberIds, status, pageable);
        }

        List<ProcessInstance> pageContent = resultPage.getContent();
        Set<String> assigneeKeys = pageContent.stream()
                .flatMap(pi -> userDisplayNameResolver.collectAssigneeUserKeys(
                        pi.getCurrentAssignee(), pi.getCandidateUsers()).stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> assigneeNames = userDisplayNameResolver.resolveBatch(assigneeKeys);

        // Request ID 与 My Requests 走同一条路：本页的功能单元编码先批量解析一次主表配置，
        // 再逐行拼接。实例已整条加载，variables 就在内存里，这里不额外查库。
        Set<String> functionUnitCodes = pageContent.stream()
                .map(ProcessInstance::getFunctionUnitCode)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        RequestIdEnricher.SpecCache requestIdSpecs = requestIdEnricher.resolveSpecs(functionUnitCodes);

        List<TeamRequestsResponse.TeamRequestItem> items = pageContent.stream()
                .map(pi -> {
                    String displayAssignee = userDisplayNameResolver.resolveCurrentAssigneeDisplay(
                            pi.getCurrentAssignee(), pi.getCandidateUsers(), assigneeNames);
                    return TeamRequestsResponse.TeamRequestItem.builder()
                        .id(pi.getId())
                        .requestId(requestIdEnricher.buildRequestId(
                                requestIdSpecs, pi.getFunctionUnitCode(), pi.getVariables()))
                        .processDefinitionName(pi.getProcessDefinitionName())
                        .businessKey(pi.getBusinessKey())
                        .startUserName(pi.getStartUserName())
                        .status(pi.getStatus())
                        .currentNode("COMPLETED".equals(pi.getStatus()) ? null : pi.getCurrentNode())
                        .currentAssignee(displayAssignee)
                        .startTime(pi.getStartTime())
                        .completedAt(pi.getCompletedAt())
                        .build();
                })
                .toList();

        return TeamRequestsResponse.builder()
                .overallCount(overallCount)
                .runningCount(runningCount)
                .completedCount(completedCount)
                .withdrawnCount(withdrawnCount)
                .content(items)
                .totalElements(resultPage.getTotalElements())
                .totalPages(resultPage.getTotalPages())
                .page(resultPage.getNumber())
                .size(resultPage.getSize())
                .build();
    }

    /**
     * Resolves all member IDs for active BU and child BUs (JWT activeBusinessUnitId only)
     */
    private Set<String> resolveTeamMemberIds(String userId) {
        Optional<String> activeBuOpt = SecurityContextUtils.getCurrentActiveBusinessUnitId();
        if (activeBuOpt.isEmpty()) {
            return Set.of();
        }
        String activeBuId = activeBuOpt.get();
        Set<String> allBuIds = new HashSet<>();
        allBuIds.add(activeBuId);
        collectChildBuIds(activeBuId, allBuIds);
        List<UserBusinessUnit> allMembers = userBusinessUnitRepository
                .findByBusinessUnitIdIn(new ArrayList<>(allBuIds));
        Set<String> memberIds = new HashSet<>();
        for (UserBusinessUnit m : allMembers) {
            memberIds.add(m.getUserId());
        }
        return memberIds;
    }

    /**
     * Returns process overview.
     * <p>Draft count is portal-local (up_process_drafts) — the engine knows nothing about
     * drafts, so it is counted here and survives an engine statistics failure.
     */
    @SuppressWarnings("unchecked")
    public DashboardOverview.ProcessOverview getProcessOverview(String userId) {
        long draftCount = processDraftRepository.countByUserId(userId);
        // Load real data from workflow-engine-core
        try {
            Optional<Map<String, Object>> result = workflowEngineClient.getProcessStatistics(userId);
            if (result.isPresent()) {
                Map<String, Object> data = result.get();
                
                long initiatedCount = data.get("initiatedCount") != null 
                    ? ((Number) data.get("initiatedCount")).longValue() : 0;
                long inProgressCount = data.get("inProgressCount") != null 
                    ? ((Number) data.get("inProgressCount")).longValue() : 0;
                long completedThisMonthCount = data.get("completedThisMonthCount") != null 
                    ? ((Number) data.get("completedThisMonthCount")).longValue() : 0;
                double approvalRate = data.get("approvalRate") != null 
                    ? ((Number) data.get("approvalRate")).doubleValue() : 1.0;
                
                Map<String, Long> typeDistribution = new HashMap<>();
                Object typeDistObj = data.get("typeDistribution");
                if (typeDistObj instanceof Map) {
                    Map<String, Object> typeDist = (Map<String, Object>) typeDistObj;
                    for (Map.Entry<String, Object> entry : typeDist.entrySet()) {
                        if (entry.getValue() instanceof Number) {
                            typeDistribution.put(entry.getKey(), ((Number) entry.getValue()).longValue());
                        }
                    }
                }
                
                return DashboardOverview.ProcessOverview.builder()
                        .initiatedCount(initiatedCount)
                        .inProgressCount(inProgressCount)
                        .completedThisMonthCount(completedThisMonthCount)
                        .draftCount(draftCount)
                        .approvalRate(approvalRate)
                        .typeDistribution(typeDistribution)
                        .build();
            }
        } catch (Exception e) {
            log.warn("Failed to get process statistics from workflow engine: {}", e.getMessage());
        }
        
        // Return empty data on failure
        return DashboardOverview.ProcessOverview.builder()
                .initiatedCount(0L)
                .inProgressCount(0L)
                .completedThisMonthCount(0L)
                .draftCount(draftCount)
                .approvalRate(1.0)
                .typeDistribution(new HashMap<>())
                .build();
    }

    /**
     * Returns personal performance metrics
     */
    public DashboardOverview.PerformanceOverview getPerformanceOverview(String userId) {
        // Mock data; should come from statistics service in production
        Random random = new Random();

        return DashboardOverview.PerformanceOverview.builder()
                .efficiencyScore(80 + random.nextDouble() * 15)
                .qualityScore(85 + random.nextDouble() * 10)
                .collaborationScore(75 + random.nextDouble() * 20)
                .monthlyRank(1 + random.nextInt(50))
                .totalUsers(100)
                .build();
    }

    /**
     * Returns recent tasks
     */
    public List<TaskInfo> getRecentTasks(String userId, int limit) {
        TaskQueryRequest request = TaskQueryRequest.builder()
                .userId(userId)
                .sortBy("createTime")
                .sortDirection("desc")
                .size(limit)
                .build();
        
        return taskQueryComponent.queryTasks(request).getContent();
    }

    /**
     * Returns recent processes
     */
    public List<ProcessInfo> getRecentProcesses(String userId, int limit) {
        // Mock data; should come from workflow-engine-core in production
        List<ProcessInfo> processes = new ArrayList<>();
        
        String[] processNames = {"Leave Request", "Expense Report", "Purchase Request", "Travel Request", "Overtime Request"};
        String[] statuses = {"RUNNING", "COMPLETED", "RUNNING", "COMPLETED", "RUNNING"};
        
        for (int i = 0; i < Math.min(limit, processNames.length); i++) {
            processes.add(ProcessInfo.builder()
                    .processInstanceId("PI_" + UUID.randomUUID().toString().substring(0, 8))
                    .processDefinitionKey("process_" + (i + 1))
                    .processDefinitionName(processNames[i])
                    .status(statuses[i])
                    .initiatorId(userId)
                    .initiatorName("Current User")
                    .startTime(LocalDateTime.now().minusDays(i))
                    .build());
        }
        
        return processes;
    }

    /**
     * Returns task trend data
     */
    public Map<String, Object> getTaskTrendData(String userId, int days) {
        Map<String, Object> result = new HashMap<>();
        
        List<String> dates = new ArrayList<>();
        List<Long> completedCounts = new ArrayList<>();
        List<Long> receivedCounts = new ArrayList<>();
        
        Random random = new Random();
        LocalDate today = LocalDate.now();
        
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            dates.add(date.toString());
            completedCounts.add((long) (5 + random.nextInt(10)));
            receivedCounts.add((long) (3 + random.nextInt(12)));
        }
        
        result.put("dates", dates);
        result.put("completed", completedCounts);
        result.put("received", receivedCounts);
        
        return result;
    }

    /**
     * Returns process statistics
     */
    public Map<String, Object> getProcessStatisticsData(String userId) {
        Map<String, Object> result = new HashMap<>();
        
        // Process type distribution
        Map<String, Long> typeDistribution = new LinkedHashMap<>();
        typeDistribution.put("Leave Request", 25L);
        typeDistribution.put("Expense Report", 18L);
        typeDistribution.put("Purchase Request", 12L);
        typeDistribution.put("Travel Request", 8L);
        typeDistribution.put("Other", 5L);
        
        // Monthly stats
        List<Map<String, Object>> monthlyStats = new ArrayList<>();
        String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun"};
        Random random = new Random();
        for (String month : months) {
            Map<String, Object> stat = new HashMap<>();
            stat.put("month", month);
            stat.put("initiated", 10 + random.nextInt(20));
            stat.put("completed", 8 + random.nextInt(18));
            monthlyStats.add(stat);
        }
        
        result.put("typeDistribution", typeDistribution);
        result.put("monthlyStats", monthlyStats);
        
        return result;
    }
}
