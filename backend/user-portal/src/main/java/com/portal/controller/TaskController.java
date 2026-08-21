package com.portal.controller;

import com.portal.component.TaskProcessComponent;
import com.portal.client.WorkflowEngineClient;
import com.platform.common.util.ApiResponseBodyUnwrap;
import com.platform.common.util.SafeUrlInput;
import com.portal.component.TaskQueryComponent;
import com.platform.common.dto.ApiResponse;
import com.portal.dto.*;
import com.portal.exception.PortalException;
import com.portal.security.CurrentUserId;
import com.platform.common.i18n.I18nService;
import com.platform.security.util.SecurityContextUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Task Management API
 */
@Tag(name = "Task Management", description = "Task query, process, delegate and other operations")
@Slf4j
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskQueryComponent taskQueryComponent;
    private final TaskProcessComponent taskProcessComponent;
    private final WorkflowEngineClient workflowEngineClient;
    private final I18nService i18nService;
    private final RestTemplate restTemplate;

    @Value("${admin-center.url:http://localhost:8090}")
    private String adminCenterUrl;

    @Operation(summary = "Query pending task list")
    @PostMapping("/query")
    public ApiResponse<PageResponse<TaskInfo>> queryTasks(
            @CurrentUserId String userId,
            @RequestBody @Valid TaskQueryRequest request) {
        // Enforce current user, forbid body-spoofed userId (consistent with @CurrentUserId)
        request.setUserId(userId);
        PageResponse<TaskInfo> result = taskQueryComponent.queryTasks(request);
        return ApiResponse.success(result);
    }

    @Operation(summary = "Query To Do list (shared list chrome)")
    @PostMapping("/todo/query")
    public ApiResponse<PortalListPage<TaskInfo>> queryTodoTasks(
            @CurrentUserId String userId,
            @RequestBody TodoTaskQueryRequest request) {
        return ApiResponse.success(taskQueryComponent.queryTodoList(userId, request));
    }

    @Operation(summary = "Get task detail")
    @GetMapping("/{taskId}")
    public ApiResponse<TaskInfo> getTaskDetail(
            @CurrentUserId String userId,
            @PathVariable String taskId) {
        TaskInfo task = taskQueryComponent.getTaskById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        // Consistent with TaskFormController: canView = canViewTaskForm (initiator + assignee), not just canProcessTask
        if (userId != null && !taskProcessComponent.canViewTaskForm(task, userId,
                SecurityContextUtils.getCurrentUsername().orElse(null))) {
            log.warn("User {} denied access to task {} (assignee={}, assignmentType={}, initiatorId={})",
                    userId, taskId, task.getAssignee(), task.getAssignmentType(), task.getInitiatorId());
            return ApiResponse.error("403", "You do not have permission to access this task");
        }
        return ApiResponse.success(task);
    }

    @Operation(summary = "Get task history")
    @GetMapping("/{taskId}/history")
    public ApiResponse<List<TaskHistoryInfo>> getTaskHistory(
            @PathVariable String taskId,
            @RequestParam(value = "processInstanceId", required = false) String processInstanceId) {
        List<TaskHistoryInfo> history = taskQueryComponent.getTaskHistory(taskId, processInstanceId);
        return ApiResponse.success(history);
    }

    @Operation(summary = "Claim task")
    @PostMapping("/{taskId}/claim")
    public ApiResponse<TaskInfo> claimTask(
            @PathVariable String taskId,
            @CurrentUserId String userId) {
        TaskInfo task = taskProcessComponent.claimTask(taskId, userId,
                SecurityContextUtils.getCurrentUsername().orElse(null));
        return ApiResponse.success(task);
    }

    @Operation(summary = "Unclaim task")
    @PostMapping("/{taskId}/unclaim")
    public ApiResponse<TaskInfo> unclaimTask(
            @PathVariable String taskId,
            @CurrentUserId String userId,
            @RequestParam String originalAssignmentType,
            @RequestParam String originalAssignee) {
        TaskInfo task = taskProcessComponent.unclaimTask(taskId, userId, originalAssignmentType, originalAssignee,
                SecurityContextUtils.getCurrentUsername().orElse(null));
        return ApiResponse.success(task);
    }

    @Operation(summary = "List historic activities available for rollback/return")
    @GetMapping("/{taskId}/returnable-activities")
    public ApiResponse<List<Map<String, Object>>> getReturnableActivities(
            @PathVariable String taskId,
            @CurrentUserId String userId) {
        if (userId == null || userId.isBlank()) {
            throw new PortalException("401", "Authentication required");
        }
        TaskInfo task = taskQueryComponent.getTaskById(taskId)
                .orElseThrow(() -> new PortalException("404", "Task not found: " + taskId));
        if (!taskProcessComponent.canViewTaskForm(task, userId,
                SecurityContextUtils.getCurrentUsername().orElse(null))) {
            throw new PortalException("403", "You do not have permission to access this task");
        }
        List<Map<String, Object>> activities = workflowEngineClient.getReturnableActivities(taskId)
                .orElse(Collections.emptyList());
        return ApiResponse.success(activities);
    }

    @Operation(summary = "Complete task")
    @PostMapping("/{taskId}/complete")
    public ApiResponse<Void> completeTask(
            @PathVariable String taskId,
            @CurrentUserId String userId,
            @Valid @RequestBody TaskCompleteRequest request) {
        if (userId == null || userId.isBlank()) {
            throw new PortalException("401", "Authentication required");
        }
        request.setTaskId(taskId);
        taskProcessComponent.completeTask(request, userId,
                SecurityContextUtils.getCurrentUsername().orElse(null));
        return ApiResponse.success();
    }

    @Operation(summary = "Delegate task")
    @PostMapping("/{taskId}/delegate")
    public ApiResponse<Void> delegateTask(
            @PathVariable String taskId,
            @CurrentUserId String userId,
            @RequestParam String delegateId,
            @RequestParam(required = false) String reason) {
        taskProcessComponent.delegateTask(taskId, userId, delegateId, reason);
        return ApiResponse.success();
    }

    @Operation(summary = "Transfer task")
    @PostMapping("/{taskId}/transfer")
    public ApiResponse<Void> transferTask(
            @PathVariable String taskId,
            @CurrentUserId String userId,
            @RequestParam String toUserId,
            @RequestParam(required = false) String reason) {
        taskProcessComponent.transferTask(taskId, userId, toUserId, reason);
        return ApiResponse.success();
    }

    @Operation(summary = "Urge task")
    @PostMapping("/{taskId}/urge")
    public ApiResponse<Void> urgeTask(
            @PathVariable String taskId,
            @CurrentUserId String userId,
            @RequestParam(required = false) String message) {
        taskProcessComponent.urgeTask(taskId, userId, message);
        return ApiResponse.success();
    }

    @Operation(summary = "Batch urge tasks")
    @PostMapping("/batch/urge")
    public ApiResponse<Void> batchUrgeTasks(
            @CurrentUserId String userId,
            @RequestBody @Valid TaskBatchUrgeRequest request) {
        taskProcessComponent.batchUrgeTasks(request.getTaskIds(), userId, request.getMessage());
        return ApiResponse.success();
    }

    @Operation(summary = "Get task statistics")
    @GetMapping("/statistics")
    public ApiResponse<TaskStatistics> getTaskStatistics(
            @CurrentUserId String userId) {
        TaskStatistics statistics = taskQueryComponent.getTaskStatistics(userId);
        return ApiResponse.success(statistics);
    }
    
    @Operation(summary = "Query completed task list")
    @PostMapping("/completed/query")
    public ApiResponse<PortalListPage<TaskInfo>> queryCompletedTasks(
            @CurrentUserId String userId,
            @RequestBody CompletedTaskQueryRequest request) {
        return ApiResponse.success(taskQueryComponent.queryCompletedTasks(userId, request));
    }

    @Operation(summary = "Search users (for transfer/delegate)")
    @GetMapping("/users/search")
    @SuppressWarnings("unchecked")
    public ApiResponse<List<Map<String, Object>>> searchUsers(
            @RequestParam(required = false, defaultValue = "") String keyword) {
        try {
            // URI.create keeps the already-encoded keyword. RestTemplate.exchange(String)
            // treats the URL as a template and percent-encodes `%` again, so 李 becomes a
            // search for the literal "%E6%9D%8E" and the people picker shows No data.
            String url = adminCenterUrl + "/api/v1/admin/users?keyword=" + SafeUrlInput.encodeQueryValue(keyword) + "&size=20";
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    URI.create(url), HttpMethod.GET, null,
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            Map<String, Object> body = response.getBody();
            List<Map<String, Object>> users = body != null
                    ? ApiResponseBodyUnwrap.normalizeToListOfMaps(body)
                    : Collections.emptyList();
            return ApiResponse.success(users);
        } catch (Exception e) {
            log.error("Failed to search users from admin-center: {}", e.getMessage());
            return ApiResponse.success(Collections.emptyList());
        }
    }

    @Operation(summary = "Assign sub-table row handler", description = "Multi-instance sub-process pre-task: assign a handler to a sub-table row (forwarded to workflow-engine)")
    @PostMapping("/{taskId}/sub-table-rows/{rowId}/assign")
    public ApiResponse<Map<String, Object>> assignSubTableRow(
            @PathVariable String taskId,
            @PathVariable Long rowId,
            @RequestBody @Valid SubTableRowAssignRequest request,
            @CurrentUserId String userId) {
        Map<String, Object> data = taskProcessComponent.assignSubTableRow(taskId, rowId, request.getRowKey(),
                request.getAssigneeId(), userId,
                SecurityContextUtils.getCurrentUsername().orElse(null));
        return ApiResponse.success(data);
    }

    @Operation(summary = "Query main task sub-table data (proxy workflow-engine)")
    @GetMapping("/{taskId}/sub-table-data/all")
    public ApiResponse<Map<String, Object>> getSubTableDataAll(@PathVariable String taskId) {
        return workflowEngineClient.getSubTableDataAll(taskId)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error("502", "Failed to fetch sub-table data from workflow engine"));
    }

    /**
     * Consistent with TaskFormController: return HTTP status by code so the frontend can display clear hints.
     */
    @ExceptionHandler(PortalException.class)
    public ApiResponse<Void> handlePortalException(PortalException e, HttpServletResponse response) {
        int statusCode = switch (e.getCode()) {
            case "404" -> HttpStatus.NOT_FOUND.value();
            case "403" -> HttpStatus.FORBIDDEN.value();
            case "400" -> HttpStatus.BAD_REQUEST.value();
            case "503" -> HttpStatus.SERVICE_UNAVAILABLE.value();
            default -> HttpStatus.INTERNAL_SERVER_ERROR.value();
        };
        response.setStatus(statusCode);
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

}
