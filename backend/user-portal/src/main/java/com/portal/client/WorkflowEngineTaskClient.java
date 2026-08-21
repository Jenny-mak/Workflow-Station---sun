package com.portal.client;

import com.platform.common.util.ApiResponseBodyUnwrap;
import com.platform.common.util.SafeUrlInput;
import com.platform.security.util.SecurityContextUtils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任务相关调用协作类：任务查询 + 任务操作（完成/认领/委派/转办/退回/子表行分派）。
 *
 * <p>底层 HTTP 调用、URL、headers、payload 构造逐字保留；探活/鉴权/错误解析等公共能力
 * 委托回门面 {@link WorkflowEngineClient}（{@code @Lazy} 破除构造期循环依赖）。
 */
@Slf4j
@Component
public class WorkflowEngineTaskClient {

    private final WorkflowEngineClient engine;

    public WorkflowEngineTaskClient(@Lazy @Autowired WorkflowEngineClient engine) {
        this.engine = engine;
    }

    // ==================== Task queries ====================

    /**
     * Queries user todo tasks
     */
    public Optional<Map<String, Object>> getUserTasks(String userId, int page, int size) {
        if (!engine.isAvailable()) {
            return Optional.empty();
        }
        try {
            UriComponentsBuilder ub = UriComponentsBuilder.fromHttpUrl(engine.engineUrl() + "/api/v1/tasks")
                    .queryParam("userId", userId)
                    .queryParam("page", page)
                    .queryParam("size", size);
            SecurityContextUtils.getCurrentActiveBusinessUnitId()
                    .filter(id -> id != null && !id.isBlank())
                    .ifPresent(bu -> ub.queryParam("activeBusinessUnitId", bu));
            String url = ub.encode().build().toUriString();

            ResponseEntity<Map<String, Object>> response = engine.restTemplate().exchange(
                url, HttpMethod.GET, engine.authorizedGetEntity(),
                new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(ApiResponseBodyUnwrap.unwrapDataMap(response.getBody()));
            }
        } catch (Exception e) {
            log.warn("Failed to get user tasks from workflow engine: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Queries tasks for process instance
     */
    public Optional<Map<String, Object>> getProcessInstanceTasks(String processInstanceId) {
        if (!engine.isAvailable()) {
            return Optional.empty();
        }
        try {
            String url = UriComponentsBuilder.fromHttpUrl(engine.engineUrl() + "/api/v1/tasks")
                    .queryParam("processInstanceId", processInstanceId)
                    .queryParam("page", 0)
                    .queryParam("size", 100)
                    .encode()
                    .build()
                    .toUriString();

            ResponseEntity<Map<String, Object>> response = engine.restTemplate().exchange(
                url, HttpMethod.GET, engine.authorizedGetEntity(),
                new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(ApiResponseBodyUnwrap.unwrapDataMap(response.getBody()));
            }
        } catch (Exception e) {
            log.warn("Failed to get process instance tasks from workflow engine: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Queries all tasks visible to user (virtual groups and department roles)
     */
    public Optional<Map<String, Object>> getUserAllVisibleTasks(String userId, List<String> groupIds,
                                                                 List<String> deptRoles, int page, int size) {
        return getUserAllVisibleTasks(userId, groupIds, deptRoles, page, size,
                com.portal.util.EngineTaskPushdown.Criteria.empty());
    }

    /**
     * Same as {@link #getUserAllVisibleTasks(String, List, List, int, int)} with optional engine pushdown criteria.
     */
    public Optional<Map<String, Object>> getUserAllVisibleTasks(String userId, List<String> groupIds,
                                                                 List<String> deptRoles, int page, int size,
                                                                 com.portal.util.EngineTaskPushdown.Criteria criteria) {
        if (!engine.isAvailable()) {
            return Optional.empty();
        }
        try {
            UriComponentsBuilder ub = UriComponentsBuilder.fromHttpUrl(engine.engineUrl() + "/api/v1/tasks")
                    .queryParam("userId", userId)
                    .queryParam("page", page)
                    .queryParam("size", size);
            if (groupIds != null) {
                for (String groupId : groupIds) {
                    ub.queryParam("groupIds", groupId);
                }
            }
            if (deptRoles != null) {
                for (String deptRole : deptRoles) {
                    ub.queryParam("deptRoles", deptRole);
                }
            }
            SecurityContextUtils.getCurrentActiveBusinessUnitId()
                    .filter(id -> id != null && !id.isBlank())
                    .ifPresent(bu -> ub.queryParam("activeBusinessUnitId", bu));
            if (criteria != null) {
                if (criteria.taskNameLike() != null && !criteria.taskNameLike().isBlank()) {
                    ub.queryParam("taskNameLike", criteria.taskNameLike());
                }
                if (criteria.taskNameLikeMode() != null && !criteria.taskNameLikeMode().isBlank()) {
                    ub.queryParam("taskNameLikeMode", criteria.taskNameLikeMode());
                }
                if (criteria.taskNameExact() != null && !criteria.taskNameExact().isBlank()) {
                    ub.queryParam("taskNameExact", criteria.taskNameExact());
                }
                if (criteria.priority() != null) {
                    ub.queryParam("priority", criteria.priority());
                }
                if (criteria.sortBy() != null && !criteria.sortBy().isBlank()) {
                    ub.queryParam("sortBy", criteria.sortBy());
                }
                if (criteria.sortDirection() != null && !criteria.sortDirection().isBlank()) {
                    ub.queryParam("sortDirection", criteria.sortDirection());
                }
            }
            String url = ub.encode().build().toUriString();

            ResponseEntity<Map<String, Object>> response = engine.restTemplate().exchange(
                url, HttpMethod.GET, engine.authorizedGetEntity(),
                new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(ApiResponseBodyUnwrap.unwrapDataMap(response.getBody()));
            }
        } catch (Exception e) {
            log.warn("Failed to get user all visible tasks from workflow engine: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Returns task detail
     */
    public Optional<Map<String, Object>> getTaskById(String taskId) {
        if (!engine.isAvailable()) {
            return Optional.empty();
        }
        try {
            String url = engine.engineUrl() + "/api/v1/tasks/" + SafeUrlInput.requirePathToken(taskId);

            ResponseEntity<Map<String, Object>> response = engine.restTemplate().exchange(
                url, HttpMethod.GET, engine.authorizedGetEntity(),
                new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(ApiResponseBodyUnwrap.unwrapDataMap(response.getBody()));
            }
        } catch (Exception e) {
            log.warn("Failed to get task by id from workflow engine: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Queries parent task sub-table data (backfill rowId / live sync before assign).
     */
    public Optional<Map<String, Object>> getSubTableDataAll(String taskId) {
        if (!engine.isAvailable()) {
            return Optional.empty();
        }
        try {
            String url = engine.engineUrl() + "/api/v1/workflow/multi-instance/tasks/" + SafeUrlInput.requirePathToken(taskId) + "/sub-table-data/all";
            ResponseEntity<Map<String, Object>> response = engine.restTemplate().exchange(
                    url, HttpMethod.GET, engine.authorizedGetEntity(),
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(ApiResponseBodyUnwrap.unwrapDataMap(response.getBody()));
            }
        } catch (Exception e) {
            log.warn("Failed to get sub-table-data/all from workflow engine: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Counts user tasks
     */
    public Optional<Map<String, Object>> countUserTasks() {
        if (!engine.isAvailable()) {
            return Optional.empty();
        }
        try {
            String url = engine.engineUrl() + "/api/v1/tasks/count";

            ResponseEntity<Map<String, Object>> response = engine.restTemplate().exchange(
                url, HttpMethod.GET, engine.authorizedGetEntity(),
                new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(ApiResponseBodyUnwrap.unwrapDataMap(response.getBody()));
            }
        } catch (Exception e) {
            log.warn("Failed to count user tasks from workflow engine: {}", e.getMessage());
        }
        return Optional.empty();
    }

    // ==================== Task operations (complete, claim, delegate, transfer, return) ====================

    /**
     * Completes task
     */
    public Optional<Map<String, Object>> completeTask(String taskId, String userId,
                                                       String action, Map<String, Object> variables) {
        if (!engine.isAvailable()) {
            return Optional.empty();
        }
        try {
            String url = engine.engineUrl() + "/api/v1/tasks/" + SafeUrlInput.requirePathToken(taskId) + "/complete";

            Map<String, Object> request = new HashMap<>();
            request.put("userId", userId);
            request.put("action", action);
            request.put("variables", variables);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            engine.forwardInboundAuthorization(headers);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
            ResponseEntity<Map<String, Object>> response = engine.restTemplate().exchange(
                url, HttpMethod.POST, entity,
                new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(ApiResponseBodyUnwrap.unwrapDataMap(response.getBody()));
            }
            return Optional.empty();
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            String msg = engine.parseWorkflowEngineErrorMessage(e.getResponseBodyAsString());
            log.warn("Failed to complete task in workflow engine: status={}, message={}", e.getStatusCode(), msg);
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", msg != null ? msg : e.getMessage());
            return Optional.of(err);
        } catch (Exception e) {
            log.warn("Failed to complete task in workflow engine: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Claims task
     */
    public Optional<Map<String, Object>> claimTask(String taskId, String userId) {
        if (!engine.isAvailable()) {
            return Optional.empty();
        }
        try {
            String url = engine.engineUrl() + "/api/v1/tasks/" + SafeUrlInput.requirePathToken(taskId) + "/claim";

            Map<String, Object> request = new HashMap<>();
            request.put("claimedBy", userId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            engine.forwardInboundAuthorization(headers);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map<String, Object>> response = engine.restTemplate().exchange(
                url, HttpMethod.POST, entity,
                new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(ApiResponseBodyUnwrap.unwrapDataMap(response.getBody()));
            }
        } catch (Exception e) {
            log.warn("Failed to claim task in workflow engine: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Delegates task
     */
    public Optional<Map<String, Object>> delegateTask(String taskId, String delegatorId,
                                                       String delegateId, String reason) {
        if (!engine.isAvailable()) {
            return Optional.empty();
        }
        try {
            String url = engine.engineUrl() + "/api/v1/tasks/" + SafeUrlInput.requirePathToken(taskId) + "/delegate";

            Map<String, Object> request = new HashMap<>();
            request.put("delegatedBy", delegatorId);
            request.put("delegatedTo", delegateId);
            request.put("reason", reason);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            engine.forwardInboundAuthorization(headers);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map<String, Object>> response = engine.restTemplate().exchange(
                url, HttpMethod.POST, entity,
                new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(ApiResponseBodyUnwrap.unwrapDataMap(response.getBody()));
            }
        } catch (Exception e) {
            log.warn("Failed to delegate task in workflow engine: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Unclaims task
     */
    public Optional<Map<String, Object>> unclaimTask(String taskId, String userId) {
        if (!engine.isAvailable()) {
            return Optional.empty();
        }
        try {
            String url = engine.engineUrl() + "/api/v1/tasks/" + SafeUrlInput.requirePathToken(taskId) + "/unclaim";

            Map<String, Object> request = new HashMap<>();
            request.put("userId", userId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            engine.forwardInboundAuthorization(headers);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map<String, Object>> response = engine.restTemplate().exchange(
                url, HttpMethod.POST, entity,
                new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(ApiResponseBodyUnwrap.unwrapDataMap(response.getBody()));
            }
        } catch (Exception e) {
            log.warn("Failed to unclaim task in workflow engine: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Transfers task
     */
    public Optional<Map<String, Object>> transferTask(String taskId, String fromUserId,
                                                       String toUserId, String reason) {
        if (!engine.isAvailable()) {
            return Optional.empty();
        }
        try {
            String url = engine.engineUrl() + "/api/v1/tasks/" + SafeUrlInput.requirePathToken(taskId) + "/transfer";

            Map<String, Object> request = new HashMap<>();
            request.put("fromUserId", fromUserId);
            request.put("toUserId", toUserId);
            request.put("reason", reason);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            engine.forwardInboundAuthorization(headers);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map<String, Object>> response = engine.restTemplate().exchange(
                url, HttpMethod.POST, entity,
                new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(ApiResponseBodyUnwrap.unwrapDataMap(response.getBody()));
            }
        } catch (Exception e) {
            log.warn("Failed to transfer task in workflow engine: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Assigns sub-table row assignee (MI sub-process prerequisite task)
     */
    public Optional<Map<String, Object>> assignSubTableRow(String taskId, long rowId, String assigneeId) {
        return assignSubTableRow(taskId, rowId, assigneeId, null);
    }

    /**
     * @param rowKey required for composite PK (path rowId may be placeholder; engine uses body)
     */
    public Optional<Map<String, Object>> assignSubTableRow(String taskId, long rowId, String assigneeId,
                                                           Map<String, Object> rowKey) {
        if (!engine.isAvailable()) {
            return Optional.empty();
        }
        try {
            String url = engine.engineUrl() + "/api/v1/tasks/" + SafeUrlInput.requirePathToken(taskId) + "/sub-table-rows/" + rowId + "/assign";

            Map<String, Object> request = new HashMap<>();
            request.put("assigneeId", assigneeId);
            if (rowKey != null && !rowKey.isEmpty()) {
                request.put("rowKey", rowKey);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            engine.forwardInboundAuthorization(headers);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map<String, Object>> response = engine.restTemplate().exchange(
                    url, HttpMethod.POST, entity,
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> unwrapped = ApiResponseBodyUnwrap.unwrapDataMap(response.getBody());
                return Optional.of(unwrapped);
            }
        } catch (HttpClientErrorException e) {
            String raw = e.getResponseBodyAsString();
            String msg = engine.parseWorkflowEngineErrorMessage(raw);
            log.warn("assignSubTableRow client error: status={}, message={}", e.getStatusCode(), msg);
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", msg != null ? msg : "Assignment failed");
            return Optional.of(err);
        } catch (HttpServerErrorException e) {
            String raw = e.getResponseBodyAsString();
            String msg = engine.parseWorkflowEngineErrorMessage(raw);
            log.warn("assignSubTableRow server error: status={}, message={}", e.getStatusCode(), msg);
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", msg != null ? msg : "Assignment failed");
            return Optional.of(err);
        } catch (Exception e) {
            log.warn("Failed to assign sub-table row in workflow engine: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Returns task to specified historic activity
     */
    public Optional<Map<String, Object>> returnTask(String taskId, String targetActivityId,
                                                     String userId, String reason) {
        return returnTask(taskId, targetActivityId, userId, reason, null);
    }

    /**
     * Returns task to specified historic activity.
     * @param returnKind {@code DRAFT} for return-to-first-step revision, {@code RETURN} or null for rollback
     */
    public Optional<Map<String, Object>> returnTask(String taskId, String targetActivityId,
                                                     String userId, String reason, String returnKind) {
        if (!engine.isAvailable()) {
            return Optional.empty();
        }
        try {
            String url = engine.engineUrl() + "/api/v1/tasks/" + SafeUrlInput.requirePathToken(taskId) + "/return";

            Map<String, Object> request = new HashMap<>();
            // Engine validates @NotBlank taskId on the request body before binding @PathVariable,
            // so taskId must be present in the payload too (path value alone is not enough).
            request.put("taskId", taskId);
            request.put("targetActivityId", targetActivityId);
            request.put("userId", userId);
            request.put("reason", reason);
            if (returnKind != null && !returnKind.isBlank()) {
                request.put("returnKind", returnKind);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            engine.forwardInboundAuthorization(headers);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map<String, Object>> response = engine.restTemplate().exchange(
                url, HttpMethod.POST, entity,
                new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(ApiResponseBodyUnwrap.unwrapDataMap(response.getBody()));
            }
        } catch (Exception e) {
            log.warn("Failed to return task in workflow engine: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Returns list of returnable historic activity nodes
     */
    public Optional<List<Map<String, Object>>> getReturnableActivities(String taskId) {
        if (!engine.isAvailable()) {
            return Optional.empty();
        }
        try {
            String url = engine.engineUrl() + "/api/v1/tasks/" + SafeUrlInput.requirePathToken(taskId) + "/returnable-activities";

            ResponseEntity<Map<String, Object>> response = engine.restTemplate().exchange(
                url, HttpMethod.GET, engine.authorizedGetEntity(),
                new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> activities = ApiResponseBodyUnwrap.normalizeToListOfMaps(response.getBody());
                return Optional.of(activities);
            }
        } catch (Exception e) {
            log.warn("Failed to get returnable activities from workflow engine: {}", e.getMessage());
        }
        return Optional.empty();
    }
}
