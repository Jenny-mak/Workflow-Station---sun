package com.portal.properties;

import com.portal.client.WorkflowEngineClient;
import com.portal.component.DashboardComponent;
import com.portal.component.TaskQueryComponent;
import com.portal.dto.DashboardOverview;
import com.portal.dto.PageResponse;
import com.portal.dto.TaskInfo;
import com.portal.dto.TaskQueryRequest;
import com.portal.repository.BusinessUnitRepository;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.repository.UserBusinessUnitRepository;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Preservation Property Test — 个人指标和其他模块不受影响
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3</b></p>
 *
 * <p>This test captures the baseline behavior of the unfixed code for:
 * - Personal-level pendingCount, overdueCount, completedTodayCount
 * - ProcessOverview data
 * - PerformanceOverview structural properties
 * - Recent tasks list
 *
 * <p>These tests should PASS on unfixed code, confirming baseline behavior is captured.
 * After the fix, they should still PASS, confirming no regression.</p>
 */
class DashboardPreservationProperties {

    // ========================================================================
    // Property 2a: Personal metrics preservation
    // ========================================================================

    /**
     * Property 2a: Personal pendingCount, overdueCount, completedTodayCount
     * should be consistent with the computation logic on unfixed code.
     *
     * <p><b>Validates: Requirements 3.1, 3.2</b></p>
     *
     * <p>For any user ID and any set of tasks, the personal metrics should be:
     * - pendingCount = total number of tasks returned by queryTasks
     * - overdueCount = count of tasks where isOverdue == true
     * - completedTodayCount = totalElements from Flowable completed tasks query
     */
    @Property(tries = 50)
    void personalMetricsShouldMatchComputationLogic(
            @ForAll("userIds") String userId,
            @ForAll @IntRange(min = 0, max = 20) int taskCount,
            @ForAll @IntRange(min = 0, max = 100) int completedTodayFromFlowable
    ) {
        // Arrange: create mocked dependencies
        TaskQueryComponent taskQueryComponent = Mockito.mock(TaskQueryComponent.class);
        WorkflowEngineClient workflowEngineClient = Mockito.mock(WorkflowEngineClient.class);
        BusinessUnitRepository businessUnitRepository = Mockito.mock(BusinessUnitRepository.class);
        UserBusinessUnitRepository userBusinessUnitRepository = Mockito.mock(UserBusinessUnitRepository.class);
        ProcessInstanceRepository processInstanceRepository = Mockito.mock(ProcessInstanceRepository.class);
        DashboardComponent dashboardComponent = new DashboardComponent(taskQueryComponent, workflowEngineClient,
                businessUnitRepository, userBusinessUnitRepository, processInstanceRepository,
                Mockito.mock(com.portal.repository.ProcessDraftRepository.class),
                Mockito.mock(com.portal.service.UserDisplayNameResolver.class),
                Mockito.mock(com.portal.component.RequestIdEnricher.class));

        ReflectionTestUtils.setField(dashboardComponent, "aggregationExecutor",
                (java.util.concurrent.Executor) Runnable::run);
                
        // Generate tasks with random overdue flags
        Random rng = new Random(userId.hashCode() + taskCount);
        List<TaskInfo> tasks = new ArrayList<>();
        int expectedOverdueCount = 0;
        for (int i = 0; i < taskCount; i++) {
            boolean isOverdue = rng.nextBoolean();
            if (isOverdue) expectedOverdueCount++;
            tasks.add(TaskInfo.builder()
                    .taskId("task_" + i)
                    .taskName("Task " + i)
                    .assignee(userId)
                    .assignmentType("USER")
                    .priority(rng.nextBoolean() ? "NORMAL" : "HIGH")
                    .isOverdue(isOverdue)
                    .createTime(LocalDateTime.now().minusHours(i + 1))
                    .build());
        }

        when(taskQueryComponent.queryTasks(any(TaskQueryRequest.class)))
                .thenReturn(PageResponse.<TaskInfo>builder()
                        .content(tasks)
                        .page(0)
                        .size(20)
                        .totalElements(taskCount)
                        .build());

        // Mock Flowable completed tasks
        Map<String, Object> completedResult = new HashMap<>();
        completedResult.put("totalElements", completedTodayFromFlowable);
        completedResult.put("content", Collections.emptyList());
        when(workflowEngineClient.getCompletedTasks(
                eq(userId), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(Optional.of(completedResult));

        // Act
        DashboardOverview.TaskOverview taskOverview = dashboardComponent.getTaskOverview(userId);

        // Assert: personal metrics match expected computation
        assertNotNull(taskOverview, "TaskOverview should not be null");
        assertEquals((long) taskCount, taskOverview.getPendingCount(),
                "pendingCount should equal total task count");
        assertEquals(0L, taskOverview.getClaimableCount(),
                "direct tasks should not be counted as claimable");
        assertEquals((long) taskCount, taskOverview.getTodoCount(),
                "direct tasks should be counted as personal To Do");
        assertEquals((long) expectedOverdueCount, taskOverview.getOverdueCount(),
                "overdueCount should equal count of overdue tasks");
        assertEquals((long) completedTodayFromFlowable, taskOverview.getCompletedTodayCount(),
                "completedTodayCount should equal Flowable totalElements");
    }

    // ========================================================================
    // Property 2b: ProcessOverview preservation
    // ========================================================================

    /**
     * Property 2b: ProcessOverview data should be exactly the same as pre-fix.
     *
     * <p><b>Validates: Requirements 3.1, 3.2</b></p>
     *
     * <p>For any user ID, when the workflow engine returns process statistics,
     * getProcessOverview should faithfully return those values. When the engine
     * is unavailable, it should return zero-value defaults.</p>
     */
    @Property(tries = 50)
    void processOverviewShouldPreserveWorkflowEngineData(
            @ForAll("userIds") String userId,
            @ForAll @IntRange(min = 0, max = 500) int initiatedCount,
            @ForAll @IntRange(min = 0, max = 500) int inProgressCount,
            @ForAll @IntRange(min = 0, max = 500) int completedThisMonthCount
    ) {
        // Arrange
        TaskQueryComponent taskQueryComponent = Mockito.mock(TaskQueryComponent.class);
        WorkflowEngineClient workflowEngineClient = Mockito.mock(WorkflowEngineClient.class);
        BusinessUnitRepository businessUnitRepository = Mockito.mock(BusinessUnitRepository.class);
        UserBusinessUnitRepository userBusinessUnitRepository = Mockito.mock(UserBusinessUnitRepository.class);
        ProcessInstanceRepository processInstanceRepository = Mockito.mock(ProcessInstanceRepository.class);
        DashboardComponent dashboardComponent = new DashboardComponent(taskQueryComponent, workflowEngineClient,
                businessUnitRepository, userBusinessUnitRepository, processInstanceRepository,
                Mockito.mock(com.portal.repository.ProcessDraftRepository.class),
                Mockito.mock(com.portal.service.UserDisplayNameResolver.class),
                Mockito.mock(com.portal.component.RequestIdEnricher.class));

        Map<String, Object> statsData = new HashMap<>();
        statsData.put("initiatedCount", initiatedCount);
        statsData.put("inProgressCount", inProgressCount);
        statsData.put("completedThisMonthCount", completedThisMonthCount);
        statsData.put("approvalRate", 0.85);
        Map<String, Object> typeDist = new HashMap<>();
        typeDist.put("leave", 10L);
        typeDist.put("expense", 5L);
        statsData.put("typeDistribution", typeDist);

        when(workflowEngineClient.getProcessStatistics(eq(userId)))
                .thenReturn(Optional.of(statsData));

        // Act
        DashboardOverview.ProcessOverview processOverview = dashboardComponent.getProcessOverview(userId);

        // Assert: ProcessOverview should faithfully reflect workflow engine data
        assertNotNull(processOverview, "ProcessOverview should not be null");
        assertEquals((long) initiatedCount, processOverview.getInitiatedCount(),
                "initiatedCount should match workflow engine data");
        assertEquals((long) inProgressCount, processOverview.getInProgressCount(),
                "inProgressCount should match workflow engine data");
        assertEquals((long) completedThisMonthCount, processOverview.getCompletedThisMonthCount(),
                "completedThisMonthCount should match workflow engine data");
        assertEquals(0.85, processOverview.getApprovalRate(), 0.001,
                "approvalRate should match workflow engine data");
        assertNotNull(processOverview.getTypeDistribution(),
                "typeDistribution should not be null");
        assertEquals(2, processOverview.getTypeDistribution().size(),
                "typeDistribution should have correct number of entries");
    }

    /**
     * Property 2b-fallback: ProcessOverview should return zero defaults when engine unavailable.
     *
     * <p><b>Validates: Requirements 3.1, 3.3</b></p>
     */
    @Property(tries = 20)
    void processOverviewShouldReturnDefaultsWhenEngineUnavailable(
            @ForAll("userIds") String userId
    ) {
        // Arrange
        TaskQueryComponent taskQueryComponent = Mockito.mock(TaskQueryComponent.class);
        WorkflowEngineClient workflowEngineClient = Mockito.mock(WorkflowEngineClient.class);
        BusinessUnitRepository businessUnitRepository1 = Mockito.mock(BusinessUnitRepository.class);
        UserBusinessUnitRepository userBusinessUnitRepository1 = Mockito.mock(UserBusinessUnitRepository.class);
        ProcessInstanceRepository processInstanceRepository1 = Mockito.mock(ProcessInstanceRepository.class);
        DashboardComponent dashboardComponent = new DashboardComponent(taskQueryComponent, workflowEngineClient,
                businessUnitRepository1, userBusinessUnitRepository1, processInstanceRepository1,
                Mockito.mock(com.portal.repository.ProcessDraftRepository.class),
                Mockito.mock(com.portal.service.UserDisplayNameResolver.class),
                Mockito.mock(com.portal.component.RequestIdEnricher.class));

        when(workflowEngineClient.getProcessStatistics(eq(userId)))
                .thenThrow(new RuntimeException("Engine unavailable"));

        // Act
        DashboardOverview.ProcessOverview processOverview = dashboardComponent.getProcessOverview(userId);

        // Assert: should return zero defaults, not crash
        assertNotNull(processOverview, "ProcessOverview should not be null even when engine fails");
        assertEquals(0L, processOverview.getInitiatedCount());
        assertEquals(0L, processOverview.getInProgressCount());
        assertEquals(0L, processOverview.getCompletedThisMonthCount());
        assertEquals(1.0, processOverview.getApprovalRate(), 0.001);
        assertNotNull(processOverview.getTypeDistribution());
        assertTrue(processOverview.getTypeDistribution().isEmpty());
    }

    // ========================================================================
    // Property 2c: PerformanceOverview preservation
    // ========================================================================

    /**
     * Property 2c: PerformanceOverview structural properties should be preserved.
     *
     * <p><b>Validates: Requirements 3.1, 3.2</b></p>
     *
     * <p>Since getPerformanceOverview uses Random (mock data), we verify structural
     * properties: all fields non-null, scores within expected ranges, totalUsers = 100.</p>
     */
    @Property(tries = 50)
    void performanceOverviewShouldPreserveStructuralProperties(
            @ForAll("userIds") String userId
    ) {
        // Arrange
        TaskQueryComponent taskQueryComponent = Mockito.mock(TaskQueryComponent.class);
        WorkflowEngineClient workflowEngineClient = Mockito.mock(WorkflowEngineClient.class);
        BusinessUnitRepository businessUnitRepository2 = Mockito.mock(BusinessUnitRepository.class);
        UserBusinessUnitRepository userBusinessUnitRepository2 = Mockito.mock(UserBusinessUnitRepository.class);
        ProcessInstanceRepository processInstanceRepository2 = Mockito.mock(ProcessInstanceRepository.class);
        DashboardComponent dashboardComponent = new DashboardComponent(taskQueryComponent, workflowEngineClient,
                businessUnitRepository2, userBusinessUnitRepository2, processInstanceRepository2,
                Mockito.mock(com.portal.repository.ProcessDraftRepository.class),
                Mockito.mock(com.portal.service.UserDisplayNameResolver.class),
                Mockito.mock(com.portal.component.RequestIdEnricher.class));

        // Act
        DashboardOverview.PerformanceOverview perfOverview = dashboardComponent.getPerformanceOverview(userId);

        // Assert: structural properties
        assertNotNull(perfOverview, "PerformanceOverview should not be null");
        assertNotNull(perfOverview.getEfficiencyScore(), "efficiencyScore should not be null");
        assertNotNull(perfOverview.getQualityScore(), "qualityScore should not be null");
        assertNotNull(perfOverview.getCollaborationScore(), "collaborationScore should not be null");
        assertNotNull(perfOverview.getMonthlyRank(), "monthlyRank should not be null");
        assertNotNull(perfOverview.getTotalUsers(), "totalUsers should not be null");

        // Verify ranges based on current implementation: 80 + random*15 => [80, 95)
        assertTrue(perfOverview.getEfficiencyScore() >= 80.0 && perfOverview.getEfficiencyScore() < 95.0,
                "efficiencyScore should be in [80, 95), got: " + perfOverview.getEfficiencyScore());
        // qualityScore: 85 + random*10 => [85, 95)
        assertTrue(perfOverview.getQualityScore() >= 85.0 && perfOverview.getQualityScore() < 95.0,
                "qualityScore should be in [85, 95), got: " + perfOverview.getQualityScore());
        // collaborationScore: 75 + random*20 => [75, 95)
        assertTrue(perfOverview.getCollaborationScore() >= 75.0 && perfOverview.getCollaborationScore() < 95.0,
                "collaborationScore should be in [75, 95), got: " + perfOverview.getCollaborationScore());
        // monthlyRank: 1 + random.nextInt(50) => [1, 50]
        assertTrue(perfOverview.getMonthlyRank() >= 1 && perfOverview.getMonthlyRank() <= 50,
                "monthlyRank should be in [1, 50], got: " + perfOverview.getMonthlyRank());
        // totalUsers is hardcoded to 100
        assertEquals(100, perfOverview.getTotalUsers(), "totalUsers should be 100");
    }

    // ========================================================================
    // Property 2d: Recent tasks list preservation
    // ========================================================================

    /**
     * Property 2d: Recent tasks list should be exactly the same as pre-fix.
     *
     * <p><b>Validates: Requirements 3.1, 3.2</b></p>
     *
     * <p>For any user ID and limit, getRecentTasks should return exactly the tasks
     * from taskQueryComponent.queryTasks with the correct sort parameters.</p>
     */
    @Property(tries = 50)
    void recentTasksShouldPreserveQueryResults(
            @ForAll("userIds") String userId,
            @ForAll @IntRange(min = 1, max = 10) int limit
    ) {
        // Arrange
        TaskQueryComponent taskQueryComponent = Mockito.mock(TaskQueryComponent.class);
        WorkflowEngineClient workflowEngineClient = Mockito.mock(WorkflowEngineClient.class);
        BusinessUnitRepository businessUnitRepository3 = Mockito.mock(BusinessUnitRepository.class);
        UserBusinessUnitRepository userBusinessUnitRepository3 = Mockito.mock(UserBusinessUnitRepository.class);
        ProcessInstanceRepository processInstanceRepository3 = Mockito.mock(ProcessInstanceRepository.class);
        DashboardComponent dashboardComponent = new DashboardComponent(taskQueryComponent, workflowEngineClient,
                businessUnitRepository3, userBusinessUnitRepository3, processInstanceRepository3,
                Mockito.mock(com.portal.repository.ProcessDraftRepository.class),
                Mockito.mock(com.portal.service.UserDisplayNameResolver.class),
                Mockito.mock(com.portal.component.RequestIdEnricher.class));

        // Generate expected tasks
        List<TaskInfo> expectedTasks = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            expectedTasks.add(TaskInfo.builder()
                    .taskId("recent_task_" + i)
                    .taskName("Recent Task " + i)
                    .assignee(userId)
                    .assignmentType("USER")
                    .priority("NORMAL")
                    .isOverdue(false)
                    .createTime(LocalDateTime.now().minusHours(i + 1))
                    .build());
        }

        when(taskQueryComponent.queryTasks(argThat(req ->
                req != null
                && userId.equals(req.getUserId())
                && "createTime".equals(req.getSortBy())
                && "desc".equals(req.getSortDirection())
                && req.getSize() == limit
        ))).thenReturn(PageResponse.<TaskInfo>builder()
                .content(expectedTasks)
                .page(0)
                .size(limit)
                .totalElements(limit)
                .build());

        // Act
        List<TaskInfo> recentTasks = dashboardComponent.getRecentTasks(userId, limit);

        // Assert: recent tasks should be exactly what queryTasks returned
        assertNotNull(recentTasks, "Recent tasks should not be null");
        assertEquals(expectedTasks.size(), recentTasks.size(),
                "Recent tasks count should match");
        for (int i = 0; i < expectedTasks.size(); i++) {
            assertEquals(expectedTasks.get(i).getTaskId(), recentTasks.get(i).getTaskId(),
                    "Task ID at index " + i + " should match");
            assertEquals(expectedTasks.get(i).getTaskName(), recentTasks.get(i).getTaskName(),
                    "Task name at index " + i + " should match");
        }
    }

    // ========================================================================
    // Generators
    // ========================================================================

    @Example
    void taskOverviewShouldSeparateClaimableFromPersonalTodo() {
        String userId = "user_dashboard";
        TaskQueryComponent taskQueryComponent = Mockito.mock(TaskQueryComponent.class);
        WorkflowEngineClient workflowEngineClient = Mockito.mock(WorkflowEngineClient.class);
        DashboardComponent dashboardComponent = new DashboardComponent(
                taskQueryComponent,
                workflowEngineClient,
                Mockito.mock(BusinessUnitRepository.class),
                Mockito.mock(UserBusinessUnitRepository.class),
                Mockito.mock(ProcessInstanceRepository.class),
                Mockito.mock(com.portal.repository.ProcessDraftRepository.class),
                Mockito.mock(com.portal.service.UserDisplayNameResolver.class),
                Mockito.mock(com.portal.component.RequestIdEnricher.class));
        ReflectionTestUtils.setField(dashboardComponent, "aggregationExecutor",
                (java.util.concurrent.Executor) Runnable::run);

        List<TaskInfo> tasks = List.of(
                TaskInfo.builder().taskId("free-pool").claimPoolTask(true).claimable(true).build(),
                TaskInfo.builder().taskId("my-pool").claimPoolTask(true).claimedByCurrentUser(true).build(),
                TaskInfo.builder().taskId("other-pool").claimPoolTask(true).build(),
                TaskInfo.builder().taskId("direct").assignmentType("USER").build());
        when(taskQueryComponent.queryTasks(any(TaskQueryRequest.class)))
                .thenReturn(PageResponse.of(tasks, 0, tasks.size(), tasks.size()));
        when(workflowEngineClient.getCompletedTasks(
                eq(userId), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(Optional.of(Map.of("totalElements", 0)));

        DashboardOverview.TaskOverview overview = dashboardComponent.getTaskOverview(userId);

        assertEquals(4L, overview.getPendingCount());
        assertEquals(1L, overview.getClaimableCount());
        assertEquals(2L, overview.getTodoCount());
    }

    @Provide
    Arbitrary<String> userIds() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(s -> "user_" + s);
    }
}
