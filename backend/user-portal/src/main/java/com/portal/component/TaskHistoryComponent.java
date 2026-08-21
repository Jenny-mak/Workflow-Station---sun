package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.dto.PageResponse;
import com.portal.dto.TaskHistoryInfo;
import com.portal.dto.TaskInfo;
import com.portal.dto.TaskQueryRequest;
import com.portal.dto.TaskStatistics;
import com.portal.entity.ProcessHistory;
import com.portal.repository.ProcessHistoryRepository;
import com.portal.util.WorkflowEnginePayloadHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Task history, completed-task and statistics queries against the workflow engine,
 * with local-database fallbacks. Extracted from {@link TaskQueryComponent}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskHistoryComponent {

    private final WorkflowEngineClient workflowEngineClient;
    private final ProcessHistoryRepository processHistoryRepository;

    /** Lazy: breaks cycle with {@link TaskQueryComponent} which delegates to this component. */
    @Lazy
    @Autowired
    private TaskQueryComponent taskQueryComponent;

    /**
     * Get task statistics.
     */
    public TaskStatistics getTaskStatistics(String userId) {
        // Check if the Flowable engine is available
        if (!workflowEngineClient.isAvailable()) {
            throw new IllegalStateException("Flowable engine unavailable, please check if workflow-engine-core service is running");
        }

        // Get task statistics from Flowable
        Optional<Map<String, Object>> countResult = workflowEngineClient.countUserTasks();

        long totalCount = 0;
        long overdueCount = 0;

        if (countResult.isPresent()) {
            Map<String, Object> data = WorkflowEnginePayloadHelper.taskCountFromPayload(countResult.get());
            if (data != null) {
                totalCount = data.get("totalCount") != null ? ((Number) data.get("totalCount")).longValue() : 0;
                overdueCount = data.get("overdueCount") != null ? ((Number) data.get("overdueCount")).longValue() : 0;
            }
        }

        // Query all tasks for detailed statistics
        TaskQueryRequest request = TaskQueryRequest.builder()
                .userId(userId)
                .page(0)
                .size(10_000)
                .build();

        PageResponse<TaskInfo> tasksResponse = taskQueryComponent.queryTasks(request);
        List<TaskInfo> allTasks = tasksResponse.getContent();
        long totalTodo = tasksResponse.getTotalElements();

        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        long todayCompletedTasks = countCompletedTasksInRange(
                userId, todayStart.toString(), LocalDateTime.now().toString());

        return TaskStatistics.builder()
                .totalTasks(totalTodo)
                .directTasks(allTasks.stream().filter(t -> "USER".equals(t.getAssignmentType())).count())
                .groupTasks(allTasks.stream().filter(t -> "VIRTUAL_GROUP".equals(t.getAssignmentType())).count())
                .deptRoleTasks(allTasks.stream().filter(t -> "DEPT_ROLE".equals(t.getAssignmentType())).count())
                .delegatedTasks(allTasks.stream().filter(t -> "DELEGATED".equals(t.getAssignmentType())).count())
                .overdueTasks(overdueCount > 0 ? overdueCount : allTasks.stream().filter(t -> Boolean.TRUE.equals(t.getIsOverdue())).count())
                .urgentTasks(allTasks.stream().filter(t -> "URGENT".equals(t.getPriority())).count())
                .highPriorityTasks(allTasks.stream().filter(t -> "HIGH".equals(t.getPriority())).count())
                .todayNewTasks(allTasks.stream()
                        .filter(t -> t.getCreateTime() != null && t.getCreateTime().isAfter(todayStart))
                        .count())
                .todayCompletedTasks(todayCompletedTasks)
                .build();
    }

    /**
     * Count tasks the user completed within a time range (via workflow-engine history API).
     * Aligns with {@code DashboardComponent} completedTodayCount aggregation.
     */
    private long countCompletedTasksInRange(String userId, String startIso, String endIso) {
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
            log.warn("Failed to count completed tasks for user {} in range {} - {}: {}",
                    userId, startIso, endIso, e.getMessage());
        }
        return 0L;
    }

    /**
     * Get task flow history.
     */
    public List<TaskHistoryInfo> getTaskHistory(String taskId) {
        return getTaskHistory(taskId, null);
    }

    /**
     * Get task flow history. When {@code processInstanceId} is known (e.g. from task detail), skip an extra
     * workflow-engine {@code getTaskInfo} round-trip inside {@code GET /tasks/{taskId}/history}.
     */
    public List<TaskHistoryInfo> getTaskHistory(String taskId, String processInstanceId) {
        // Check if the Flowable engine is available
        if (!workflowEngineClient.isAvailable()) {
            throw new IllegalStateException("Flowable engine unavailable, please check if workflow-engine-core service is running");
        }

        List<TaskHistoryInfo> history = new ArrayList<>();

        // Get task history from Flowable (includes user name resolution)
        Optional<List<Map<String, Object>>> historyResult;
        if (processInstanceId != null && !processInstanceId.isBlank()) {
            historyResult = workflowEngineClient.getProcessInstanceHistory(processInstanceId.trim());
        } else {
            historyResult = workflowEngineClient.getTaskHistoryByTaskId(taskId);
        }
        if (historyResult.isPresent()) {
            List<Map<String, Object>> historyList = historyResult.get();
            for (int i = 0; i < historyList.size(); i++) {
                Map<String, Object> historyMap = historyList.get(i);
                Long duration = null;
                if (i > 0) {
                    // Calculate duration
                    LocalDateTime prevTime = EngineTaskMapper.parseDateTime(historyList.get(i - 1).get("operationTime"));
                    LocalDateTime currTime = EngineTaskMapper.parseDateTime(historyMap.get("operationTime"));
                    if (prevTime != null && currTime != null) {
                        duration = java.time.Duration.between(prevTime, currTime).toMillis();
                    }
                }

                history.add(TaskHistoryInfo.builder()
                        .id((String) historyMap.get("id"))
                        .taskId((String) historyMap.get("taskId"))
                        .taskName((String) historyMap.get("taskName"))
                        .activityId((String) historyMap.get("activityId"))
                        .activityName((String) historyMap.get("activityName"))
                        .activityType((String) historyMap.get("activityType"))
                        .operationType((String) historyMap.get("operationType"))
                        .operatorId((String) historyMap.get("operatorId"))
                        .operatorName((String) historyMap.get("operatorName"))
                        .operationTime(EngineTaskMapper.parseDateTime(historyMap.get("operationTime")))
                        .comment((String) historyMap.get("comment"))
                        .duration(duration)
                        .build());
            }
            return history;
        }

        // If Flowable has no history records, try fetching from the local database
        try {
            String resolvedProcessInstanceId = processInstanceId;
            if (resolvedProcessInstanceId == null || resolvedProcessInstanceId.isBlank()) {
                Optional<TaskInfo> taskInfoOpt = taskQueryComponent.getTaskById(taskId);
                if (taskInfoOpt.isPresent()) {
                    resolvedProcessInstanceId = taskInfoOpt.get().getProcessInstanceId();
                }
            }
            if (resolvedProcessInstanceId != null && !resolvedProcessInstanceId.isBlank()) {
                // Try to get history from the local database
                List<ProcessHistory> dbHistory = processHistoryRepository
                        .findByProcessInstanceIdOrderByOperationTimeAsc(resolvedProcessInstanceId);

                for (int i = 0; i < dbHistory.size(); i++) {
                    ProcessHistory ph = dbHistory.get(i);
                    Long duration = null;
                    if (i > 0 && ph.getOperationTime() != null && dbHistory.get(i - 1).getOperationTime() != null) {
                        duration = java.time.Duration.between(
                                dbHistory.get(i - 1).getOperationTime(),
                                ph.getOperationTime()
                        ).toMillis();
                    }

                    history.add(TaskHistoryInfo.builder()
                            .id("history_" + ph.getId())
                            .taskId(ph.getTaskId())
                            .taskName(ph.getActivityName())
                            .activityId(ph.getActivityId())
                            .activityName(ph.getActivityName())
                            .activityType(ph.getActivityType())
                            .operationType(ph.getOperationType())
                            .operatorId(ph.getOperatorId())
                            .operatorName(ph.getOperatorName())
                            .operationTime(ph.getOperationTime())
                            .comment(ph.getComment())
                            .duration(duration)
                            .build());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get process history from database: {}", e.getMessage());
        }

        return history;
    }
}
