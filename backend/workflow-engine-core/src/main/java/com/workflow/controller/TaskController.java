package com.workflow.controller;

import com.platform.common.config.ConfigurationManager;
import com.platform.common.config.WorkflowConfig;
import com.platform.common.security.SecurityIntegrationService;
import com.workflow.component.HistoryManagerComponent;
import com.workflow.component.TaskManagerComponent;
import com.workflow.dto.request.HistoryQueryRequest;
import com.workflow.dto.request.TaskAssignmentRequest;
import com.workflow.dto.request.TaskClaimRequest;
import com.workflow.dto.request.TaskDelegationRequest;
import com.workflow.dto.request.TaskReturnRequest;
import com.workflow.dto.request.AssignSubTableRowRequest;
import com.workflow.dto.response.ApiResponse;
import com.workflow.dto.response.HistoryQueryResult;
import com.workflow.dto.response.TaskAssignmentResult;
import com.workflow.dto.response.TaskListResult;
import com.workflow.dto.response.AssignSubTableRowResponse;
import com.workflow.component.SubTableAssignmentHandler;
import com.workflow.enums.AssignmentType;
import com.workflow.exception.WorkflowBusinessException;
import com.workflow.service.UserPermissionService;
import com.workflow.util.WorkflowActorResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Task management REST controller.
 *
 * Exposes task query, complete, delegate, transfer, and related APIs via TaskManagerComponent.
 * Integrates security validation, input validation, and centralized error handling.
 * 
 * **Validates: Requirements 4.2**
 * 
 * @author Workflow Engine
 * @version 1.0
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
    @Tag(name = "Task Management", description = "Workflow task management API")
public class TaskController {

    private final TaskManagerComponent taskManagerComponent;
    private final UserPermissionService userPermissionService;
    private final ConfigurationManager configurationManager;
    private final SecurityIntegrationService securityIntegrationService;
    private final com.workflow.client.AdminCenterClient adminCenterClient;
    private final SubTableAssignmentHandler subTableAssignmentHandler;
    private final com.workflow.component.MultiInstanceDataResolver multiInstanceDataResolver;
    private final TaskHistoryAssembler taskHistoryAssembler;

    /**
     * Query task list.
     */
    @GetMapping
    @Operation(summary = "Query Task List", description = "Query tasks by criteria")
    public ResponseEntity<ApiResponse<TaskListResult>> getTasks(
            @Parameter(description = "User ID")
            @RequestParam(value = "userId", required = false) String userId,
            @Parameter(description = "Process instance ID")
            @RequestParam(value = "processInstanceId", required = false) String processInstanceId,
            @Parameter(description = "Page number")
            @RequestParam(value = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size")
            @RequestParam(value = "size", required = false) Integer size,
            @Parameter(description = "Virtual group ID list")
            @RequestParam(value = "groupIds", required = false) List<String> groupIds,
            @Parameter(description = "Department role list")
            @RequestParam(value = "deptRoles", required = false) List<String> deptRoles,
            @Parameter(description = "Portal current workspace business unit (optional; filters pending tasks where FIXED_BU_ROLE is inconsistent with JWT)")
            @RequestParam(value = "activeBusinessUnitId", required = false) String activeBusinessUnitId,
            @Parameter(description = "Optional Flowable taskName LIKE fragment (no %; engine wraps)")
            @RequestParam(value = "taskNameLike", required = false) String taskNameLike,
            @Parameter(description = "How to wrap taskNameLike: contains|startsWith|endsWith")
            @RequestParam(value = "taskNameLikeMode", required = false) String taskNameLikeMode,
            @Parameter(description = "Optional Flowable taskName exact match")
            @RequestParam(value = "taskNameExact", required = false) String taskNameExact,
            @Parameter(description = "Optional Flowable priority")
            @RequestParam(value = "priority", required = false) Integer priority,
            @Parameter(description = "Optional sort field pushed from portal")
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @Parameter(description = "Optional sort direction pushed from portal")
            @RequestParam(value = "sortDirection", required = false) String sortDirection) {
        
        // Validate and sanitize inputs using security integration service
        if (userId != null) {
            securityIntegrationService.validateAndAuditInput("userId", userId, "task_query");
        }
        if (processInstanceId != null) {
            securityIntegrationService.validateAndAuditInput("processInstanceId", processInstanceId, "task_query");
        }
        
        // Use externalized configuration for default page size
        WorkflowConfig workflowConfig = configurationManager.getConfiguration(WorkflowConfig.class);
        int pageSize = size != null ? size : workflowConfig.getDefaultPageSize();
        
        // Enforce maximum page size limit
        if (pageSize > workflowConfig.getMaxPageSize()) {
            pageSize = workflowConfig.getMaxPageSize();
        }

        Optional<String> actor = WorkflowActorResolver.currentUserId();
        if (actor.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Authentication required"));
        }
        if (userId != null && !userId.isEmpty() && !actor.get().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("FORBIDDEN", "userId must match the authenticated user"));
        }
        if (userId == null || userId.isEmpty()) {
            userId = actor.get();
        }
        
        log.info("Querying tasks for user: {}, processInstanceId: {}, page: {}, size: {}", userId, processInstanceId, page, pageSize);
        
        TaskListResult result;
        if (processInstanceId != null && !processInstanceId.isEmpty()) {
            // Query tasks by process instance ID
            result = taskManagerComponent.getTasksByProcessInstance(processInstanceId, page, pageSize);
        } else {
            // Always use getUserAllVisibleTasks (includes repairOrphanBuRolePoolTasks).
            // When groupIds is absent (portal filterVirtualGroupsForActiveWorkspace removed all VGs),
            // getUserTasks would skip BU_ROLE orphan-pool repair and todos may be empty.
            List<String> gids = groupIds != null ? groupIds : Collections.emptyList();
            List<String> droles = deptRoles != null ? deptRoles : Collections.emptyList();
            com.workflow.dto.request.EngineTaskListCriteria criteria =
                    new com.workflow.dto.request.EngineTaskListCriteria(
                            taskNameLike, taskNameExact, taskNameLikeMode, priority, sortBy, sortDirection);
            result = taskManagerComponent.getUserAllVisibleTasks(userId, gids, droles, page, pageSize,
                    activeBusinessUnitId, criteria);
        }
        
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Get task details.
     */
    @GetMapping("/{taskId}")
    @Operation(summary = "Get Task Details", description = "Get task details by ID")
    public ResponseEntity<ApiResponse<TaskListResult.TaskInfo>> getTask(
            @Parameter(description = "Task ID", required = true)
            @PathVariable String taskId) {
        
        log.info("Getting task details: {}", taskId);
        TaskListResult.TaskInfo taskInfo = taskManagerComponent.getTaskInfo(taskId);
        return ResponseEntity.ok(ApiResponse.success(taskInfo));
    }
    
    /**
     * Get task transition history.
     */
    @GetMapping("/{taskId}/history")
    @Operation(summary = "Get Task Flow History", description = "Get the flow history of a task's process instance")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTaskHistory(
            @Parameter(description = "Task ID", required = true)
            @PathVariable String taskId) {
        
        log.info("Getting task history for task: {}", taskId);
        
        // Resolve process instance ID from the task first
        TaskListResult.TaskInfo taskInfo = taskManagerComponent.getTaskInfo(taskId);
        String processInstanceId = taskInfo.getProcessInstanceId();
        
        return getProcessInstanceHistory(processInstanceId);
    }
    
    /**
     * Get process instance activity history by process instance ID.
     */
    @GetMapping("/process/{processInstanceId}/history")
    @Operation(summary = "Get Process Instance Flow History", description = "Get the complete flow history of a process instance with user name resolution")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getProcessInstanceHistory(
            @Parameter(description = "Process instance ID", required = true)
            @PathVariable String processInstanceId) {

        List<Map<String, Object>> historyList =
                taskHistoryAssembler.assembleProcessInstanceHistory(processInstanceId);
        return ResponseEntity.ok(ApiResponse.success(historyList));
    }

    /**
     * Assign task.
     */
    @PostMapping("/{taskId}/assign")
    @Operation(summary = "Assign Task", description = "Assign task to a user or group")
    public ResponseEntity<ApiResponse<TaskAssignmentResult>> assignTask(
            @Parameter(description = "Task ID", required = true)
            @PathVariable String taskId,
            @RequestBody @Valid TaskAssignmentRequest request) {

        Optional<String> actor = WorkflowActorResolver.currentUserId();
        if (actor.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Authentication required"));
        }
        request.setOperatorUserId(actor.get());

        log.info("Assigning task: {} to {} (type: {})", taskId, request.getAssignmentTarget(), request.getAssignmentType());
        try {
            TaskAssignmentResult result = taskManagerComponent.assignTask(taskId, request);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (WorkflowBusinessException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getErrorCode(), e.getMessage()));
        }
    }

    /**
     * Claim task.
     */
    @PostMapping("/{taskId}/claim")
    @Operation(summary = "Claim Task", description = "Claim a virtual group or department role task")
    public ResponseEntity<ApiResponse<TaskAssignmentResult>> claimTask(
            @Parameter(description = "Task ID", required = true)
            @PathVariable String taskId,
            @RequestBody(required = false) TaskClaimRequest request) {
        
        if (request == null) {
            request = new TaskClaimRequest();
        }
        // Set taskId from path; avoid @Valid on body or validation runs before setTaskId
        request.setTaskId(taskId);

        Optional<String> actor = WorkflowActorResolver.currentUserId();
        if (actor.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Authentication required"));
        }
        request.setClaimedBy(actor.get());
        
        log.info("Claiming task: {} by user: {}", taskId, request.getClaimedBy());
        TaskAssignmentResult result = taskManagerComponent.claimTask(taskId, request);
        
        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.error("CLAIM_FAILED", result.getMessage()));
        }
    }

    /**
     * Delegate task.
     */
    @PostMapping("/{taskId}/delegate")
    @Operation(summary = "Delegate Task", description = "Delegate task to another user")
    public ResponseEntity<ApiResponse<TaskAssignmentResult>> delegateTask(
            @Parameter(description = "Task ID", required = true)
            @PathVariable String taskId,
            @RequestBody @Valid TaskDelegationRequest request) {
        
        Optional<String> actor = WorkflowActorResolver.currentUserId();
        if (actor.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Authentication required"));
        }
        request.setDelegatedBy(actor.get());

        log.info("Delegating task: {} from {} to {}", taskId, request.getDelegatedBy(), request.getDelegatedTo());
        TaskAssignmentResult result = taskManagerComponent.delegateTask(taskId, request);
        
        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.error("DELEGATE_FAILED", result.getMessage()));
        }
    }
    
    /**
     * Unclaim task.
     */
    @PostMapping("/{taskId}/unclaim")
    @Operation(summary = "Unclaim Task", description = "Unclaim a previously claimed task")
    public ResponseEntity<ApiResponse<TaskAssignmentResult>> unclaimTask(
            @Parameter(description = "Task ID", required = true)
            @PathVariable String taskId,
            @RequestBody Map<String, Object> request) {
        
        Optional<String> actor = WorkflowActorResolver.currentUserId();
        if (actor.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Authentication required"));
        }
        log.info("Unclaiming task: {} by user: {}", taskId, actor.get());
        TaskAssignmentResult result = taskManagerComponent.unclaimTask(taskId, actor.get());
        
        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.error("UNCLAIM_FAILED", result.getMessage()));
        }
    }
    
    /**
     * Transfer task.
     */
    @PostMapping("/{taskId}/transfer")
    @Operation(summary = "Transfer Task", description = "Transfer task to another user")
    public ResponseEntity<ApiResponse<TaskAssignmentResult>> transferTask(
            @Parameter(description = "Task ID", required = true)
            @PathVariable String taskId,
            @RequestBody Map<String, Object> request) {
        
        Optional<String> actor = WorkflowActorResolver.currentUserId();
        if (actor.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Authentication required"));
        }
        String fromUserId = actor.get();
        String toUserId = (String) request.get("toUserId");
        String reason = (String) request.get("reason");
        
        log.info("Transferring task: {} from {} to {}", taskId, fromUserId, toUserId);
        TaskAssignmentResult result = taskManagerComponent.transferTask(taskId, fromUserId, toUserId, reason);
        
        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.error("TRANSFER_FAILED", result.getMessage()));
        }
    }

    /**
     * Complete task.
     */
    @PostMapping("/{taskId}/complete")
    @Operation(summary = "Complete Task", description = "Complete the specified task")
    public ResponseEntity<ApiResponse<TaskAssignmentResult>> completeTask(
            @Parameter(description = "Task ID", required = true)
            @PathVariable String taskId,
            @RequestBody Map<String, Object> request) {
        
        Optional<String> actor = WorkflowActorResolver.currentUserId();
        if (actor.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Authentication required"));
        }
        String userId = actor.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = (Map<String, Object>) request.get("variables");
        boolean sendNotification = true;
        if (request.containsKey("sendNotification") && request.get("sendNotification") instanceof Boolean b) {
            sendNotification = b;
        }
        
        log.info("Completing task: {} by user: {}", taskId, userId);
        log.debug("Variables received (keys only): {}",
                variables != null ? variables.keySet() : null);
        
        TaskAssignmentResult result = taskManagerComponent.completeTask(taskId, userId, variables, sendNotification);
        
        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.error("COMPLETE_FAILED", result.getMessage()));
        }
    }
    
    /**
     * Return task to a previous activity.
     */
    @PostMapping("/{taskId}/return")
    @Operation(summary = "Return Task", description = "Return the task to a specified historical node")
    public ResponseEntity<ApiResponse<TaskAssignmentResult>> returnTask(
            @Parameter(description = "Task ID", required = true)
            @PathVariable String taskId,
            @RequestBody @Valid TaskReturnRequest request) {
        
        request.setTaskId(taskId);

        Optional<String> actor = WorkflowActorResolver.currentUserId();
        if (actor.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Authentication required"));
        }
        request.setUserId(actor.get());

        log.info("Returning task: {} to activity: {} by user: {} (returnKind={})",
                taskId, request.getTargetActivityId(), request.getUserId(), request.getReturnKind());
        
        TaskAssignmentResult result = taskManagerComponent.returnTask(taskId, request);
        
        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.error("RETURN_FAILED", result.getMessage()));
        }
    }
    
    /**
     * List historic activities available for return.
     */
    @GetMapping("/{taskId}/returnable-activities")
    @Operation(summary = "Get Returnable Activities", description = "Get the list of historical nodes the task can be returned to")
    public ResponseEntity<ApiResponse<List<TaskListResult.TaskInfo>>> getReturnableActivities(
            @Parameter(description = "Task ID", required = true)
            @PathVariable String taskId) {
        
        log.info("Getting returnable activities for task: {}", taskId);
        List<TaskListResult.TaskInfo> activities = taskManagerComponent.getReturnableActivities(taskId);
        return ResponseEntity.ok(ApiResponse.success(activities));
    }

    /**
     * Batch complete tasks.
     */
    @PostMapping("/batch/complete")
    @Operation(summary = "Batch Complete Tasks", description = "Complete multiple tasks in batch")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchCompleteTasks(
            @RequestBody Map<String, Object> request) {
        
        Optional<String> actor = WorkflowActorResolver.currentUserId();
        if (actor.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Authentication required"));
        }
        String userId = actor.get();
        @SuppressWarnings("unchecked")
        List<String> taskIds = (List<String>) request.get("taskIds");
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = (Map<String, Object>) request.get("variables");
        boolean sendNotification = true;
        if (request.containsKey("sendNotification") && request.get("sendNotification") instanceof Boolean b) {
            sendNotification = b;
        }

        if (taskIds == null || taskIds.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_REQUEST", "taskIds is required"));
        }
        
        log.info("Batch completing {} tasks by user: {}", taskIds.size(), userId);
        
        List<String> successIds = new ArrayList<>();
        List<String> failedIds = new ArrayList<>();
        
        for (String taskId : taskIds) {
            try {
                TaskAssignmentResult result = taskManagerComponent.completeTask(taskId, userId, variables, sendNotification);
                if (result.isSuccess()) {
                    successIds.add(taskId);
                } else {
                    failedIds.add(taskId);
                }
            } catch (Exception e) {
                log.warn("Failed to complete task {}: {}", taskId, e.getMessage());
                failedIds.add(taskId);
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("successIds", successIds);
        result.put("failedIds", failedIds);
        result.put("completed", successIds.size());
        result.put("failed", failedIds.size());
        
        return ResponseEntity.ok(ApiResponse.success(result));
    }
    
    /**
     * Count tasks for a user.
     */
    @GetMapping("/count")
    @Operation(summary = "Count User Tasks", description = "Count pending task count for the current authenticated user (userId query param is not trusted)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> countTasks() {

        Optional<String> actor = WorkflowActorResolver.currentUserId();
        if (actor.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Authentication required"));
        }
        String userId = actor.get();

        log.info("Counting tasks for user: {}", userId);

        long totalCount = taskManagerComponent.countUserTasks(userId);
        long overdueCount = taskManagerComponent.countUserOverdueTasks(userId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("totalCount", totalCount);
        result.put("overdueCount", overdueCount);
        
        return ResponseEntity.ok(ApiResponse.success(result));
    }
    
    /**
     * Get task permission context for a user (virtual group IDs and department roles for queries).
     */
    @GetMapping("/user-permissions")
    @Operation(summary = "Get User Task Permissions", description = "Get user's virtual group and role information for task queries")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUserTaskPermissions(
            @Parameter(description = "User ID", required = true)
            @RequestParam("userId") String userId,
            @Parameter(description = "Include the user's role codes. Set false to skip a second admin-center round-trip "
                    + "when only virtualGroupIds are needed.")
            @RequestParam(value = "includeRoles", defaultValue = "true") boolean includeRoles) {

        Optional<String> actor = WorkflowActorResolver.currentUserId();
        if (actor.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Authentication required"));
        }
        if (!actor.get().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("FORBIDDEN", "Can only query permissions for the authenticated user"));
        }

        log.info("Getting task permissions for user: {} (includeRoles={})", userId, includeRoles);

        List<String> virtualGroupIds = userPermissionService.getUserVirtualGroupIds(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("virtualGroupIds", virtualGroupIds);
        // Each lookup is its own serial admin-center call, and this endpoint sits on the To Do /
        // dashboard cold path. Callers that never read "roles" can drop half the latency.
        if (includeRoles) {
            result.put("roles", userPermissionService.getUserRoles(userId));
        }

        return ResponseEntity.ok(ApiResponse.success(result));
    }
    
    /**
     * Check whether the user may perform an operation on the task.
     */
    @GetMapping("/{taskId}/check-permission")
    @Operation(summary = "Check Task Permission", description = "Check if user has permission to operate on the specified task")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkTaskPermission(
            @Parameter(description = "Task ID", required = true)
            @PathVariable String taskId,
            @Parameter(description = "User ID", required = true)
            @RequestParam("userId") String userId) {
        
        Optional<String> actor = WorkflowActorResolver.currentUserId();
        if (actor.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Authentication required"));
        }
        if (!actor.get().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("FORBIDDEN", "Can only check permissions for the authenticated user"));
        }

        log.info("Checking task permission for user: {} on task: {}", userId, taskId);
        
        TaskListResult.TaskInfo taskInfo = taskManagerComponent.getTaskInfo(taskId);
        
        boolean hasPermission = userPermissionService.hasTaskPermission(
                userId, 
                taskInfo.getAssignmentType(), 
                taskInfo.getAssignmentTarget());
        
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("userId", userId);
        result.put("hasPermission", hasPermission);
        result.put("assignmentType", taskInfo.getAssignmentType());
        result.put("assignmentTarget", taskInfo.getAssignmentTarget());
        
        return ResponseEntity.ok(ApiResponse.success(result));
    }
    
    /**
     * Assign sub-table row assignee (MI pre-task Assign action).
     */
    @PostMapping("/{taskId}/sub-table-rows/{rowId}/assign")
    @Operation(summary = "Assign Sub-Table Row Handler", description = "Assign handler for a sub-table row in a multi-instance sub-process")
    public ResponseEntity<ApiResponse<AssignSubTableRowResponse>> assignSubTableRow(
            @Parameter(description = "Task ID", required = true)
            @PathVariable String taskId,
            @Parameter(description = "Sub-table row ID", required = true)
            @PathVariable Long rowId,
            @RequestBody @Valid AssignSubTableRowRequest request) {
        
        log.info("Assigning sub-table row handler: taskId={}, rowId={}, assigneeId={}", 
            taskId, rowId, request.getAssigneeId());
        
        try {
            SubTableAssignmentHandler.AssignmentResponse handlerResponse =
                subTableAssignmentHandler.assign(taskId, rowId, request.getRowKey(), request.getAssigneeId());
            
            AssignSubTableRowResponse response = AssignSubTableRowResponse.builder()
                .success(handlerResponse.isSuccess())
                .rowId(handlerResponse.getRowId())
                .assigneeId(handlerResponse.getAssigneeId())
                .assigneeName(handlerResponse.getAssigneeName())
                .build();
            
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("Failed to assign sub-table row handler: taskId={}, rowId={}", taskId, rowId, e);
            
            return ResponseEntity.badRequest().body(
                ApiResponse.error("ASSIGN_SUBTABLE_ROW_FAILED", e.getMessage())
            );
        }
    }
    
}