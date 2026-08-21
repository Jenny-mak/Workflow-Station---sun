package com.portal.client;

import com.platform.common.constant.PlatformConstants;
import com.platform.common.i18n.I18nService;
import com.platform.common.util.SafeUrlInput;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.security.config.JwtProperties;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Workflow Engine Core client
 * Client for workflow-engine-core module APIs
 *
 * Note: workflow-engine-core APIs are not fully implemented yet;
 * Provides fallback to local implementation when workflow-engine-core is unavailable
 *
 * <p>门面（facade）：被各 component 广泛注入。public 方法签名逐字不变；具体调用逻辑按职责委托给
 * 同包协作类 {@link WorkflowEngineTaskClient}（任务查询/操作/历史/权限）、
 * {@link WorkflowEngineProcessClient}（流程部署/状态/历史/BPMN）。
 * 探活与鉴权（{@link #isAvailable()} / {@link #forwardInboundAuthorization} / {@link #authorizedGetEntity}）
 * 及底层 {@link RestTemplate}、引擎 URL 等公共能力保留在本类，供协作类调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowEngineClient {

    private final RestTemplate restTemplate;
    private final JwtProperties jwtProperties;
    private final I18nService i18nService;

    // 协作类用字段注入；@Lazy 破除门面 ↔ 协作类的构造期循环依赖
    @Lazy
    @Autowired
    private WorkflowEngineTaskClient taskClient;

    @Lazy
    @Autowired
    private WorkflowEngineProcessClient processClient;

    @Lazy
    @Autowired
    private WorkflowEngineTaskHistoryClient taskHistoryClient;

    @Value("${workflow-engine.url:http://localhost:8081}")
    private String workflowEngineUrl;

    /** Matches application.yml default so missing merged config does not silently disable engine integration */
    @Value("${workflow-engine.enabled:true}")
    private boolean workflowEngineEnabled;

    private static final long HEALTH_CHECK_CACHE_TTL_MS = 30_000;
    private volatile boolean cachedAvailable = false;
    private volatile long lastHealthCheckTime = 0;

    // ==================== 公共能力（探活 / 鉴权 / 错误解析），供门面与协作类共用 ====================

    /**
     * Checks whether workflow-engine-core is available (30s cache)
     */
    public boolean isAvailable() {
        if (!workflowEngineEnabled) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastHealthCheckTime < HEALTH_CHECK_CACHE_TTL_MS) {
            return cachedAvailable;
        }
        try {
            String healthUrl = workflowEngineUrl + "/actuator/health";
            ResponseEntity<Map> response = restTemplate.getForEntity(healthUrl, Map.class);
            cachedAvailable = response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("Workflow engine not available: {}", e.getMessage());
            cachedAvailable = false;
        }
        lastHealthCheckTime = now;
        return cachedAvailable;
    }

    /** Engine base URL（协作类构造完整 URL 时使用） */
    String engineUrl() {
        return workflowEngineUrl;
    }

    /** Shared RestTemplate（协作类执行 HTTP 调用时使用） */
    RestTemplate restTemplate() {
        return restTemplate;
    }

    /**
     * workflow-engine requires authenticated JWT for /api/v1/** (same {@code JWT_SECRET} as portal).
     *
     * <p>Resolution order matches {@link com.platform.security.filter.JwtAuthenticationFilter#extractToken}:
     * Prefer {@code Authorization} header; else fall back to {@code platform.security.jwt.cookie-names}
     * configured httpOnly cookies (user-portal writes {@code up_access_token}),
     * and sends {@code Authorization: Bearer <token>} to workflow-engine (cross-service calls do not forward cookies).
     *
     * <p>No request context (e.g. scheduled job): no header — protected APIs return 403; caller handles.
     */
    void forwardInboundAuthorization(HttpHeaders headers) {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
            return;
        }
        HttpServletRequest request = servletAttrs.getRequest();
        String auth = request.getHeader(PlatformConstants.HEADER_AUTHORIZATION);
        if (auth != null && !auth.isBlank()) {
            headers.set(PlatformConstants.HEADER_AUTHORIZATION, auth.trim());
            return;
        }
        String cookieToken = extractAccessTokenFromCookie(request);
        if (cookieToken != null && !cookieToken.isBlank()) {
            headers.set(PlatformConstants.HEADER_AUTHORIZATION,
                    PlatformConstants.HEADER_BEARER_PREFIX + cookieToken.trim());
        }
    }

    private String extractAccessTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        List<String> names = jwtProperties.getCookieNames();
        if (names == null || names.isEmpty()) {
            names = List.of("access_token");
        }
        for (String name : names) {
            for (Cookie cookie : cookies) {
                if (cookie != null
                        && name.equals(cookie.getName())
                        && cookie.getValue() != null
                        && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    /** Extracts business message field from engine JSON response body */
    String extractMessage(String body) {
        if (body == null || body.isBlank()) return "Unknown error";
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(body);
            // Supports { "message": "..." }, { "data": { "message": "..." } }, or { "error": "..." }
            JsonNode msg = node.path("message");
            if (msg.isMissingNode()) msg = node.path("data").path("message");
            if (msg.isMissingNode()) msg = node.path("error");
            if (!msg.isMissingNode() && !msg.isNull()) return msg.asText();
        } catch (Exception ignored) { }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    HttpEntity<Void> authorizedGetEntity() {
        HttpHeaders headers = new HttpHeaders();
        forwardInboundAuthorization(headers);
        return new HttpEntity<>(headers);
    }

    private static final ObjectMapper ENGINE_ERROR_JSON = new ObjectMapper();

    /**
     * Parses human-readable message from workflow-engine ApiResponse error body.
     * Supports top-level {@code message}, {@code error.message} / {@code error.detail}, {@code errorMessage} (sub-table assign DTO).
     */
    String parseWorkflowEngineErrorMessage(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = ENGINE_ERROR_JSON.readTree(json);
            if (root.hasNonNull("message")) {
                String m = root.get("message").asText("");
                if (!m.isBlank()) {
                    return m;
                }
            }
            JsonNode err = root.get("error");
            if (err != null && err.isObject()) {
                if (err.hasNonNull("message")) {
                    String m = err.get("message").asText("");
                    if (!m.isBlank()) {
                        return m;
                    }
                }
                if (err.hasNonNull("detail")) {
                    String m = err.get("detail").asText("");
                    if (!m.isBlank()) {
                        return m;
                    }
                }
                if (err.hasNonNull("code")) {
                    String c = err.get("code").asText("");
                    if (!c.isBlank()) {
                        return c;
                    }
                }
            }
            if (root.hasNonNull("errorMessage")) {
                String m = root.get("errorMessage").asText("");
                if (!m.isBlank()) {
                    return m;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    // ==================== Process deploy and start（委托 processClient） ====================

    /**
     * Deploys process definition
     */
    public Optional<Map<String, Object>> deployProcess(String processKey, String bpmnXml, String name) {
        return processClient.deployProcess(processKey, bpmnXml, name);
    }

    /**
     * Starts process instance.
     * Throws IllegalStateException with engine business message on failure; callers need not check empty.
     */
    public Map<String, Object> startProcess(String processDefinitionKey, String businessKey,
                                                       String startUserId, Map<String, Object> variables) {
        return processClient.startProcess(processDefinitionKey, businessKey, startUserId, variables);
    }

    /**
     * Deletes runtime and historic process instances on engine (internal purge; permitAll, no JWT)
     */
    public boolean purgeProcessInstance(String processInstanceId) {
        return processClient.purgeProcessInstance(processInstanceId);
    }

    // ==================== Task queries（委托 taskClient） ====================

    /**
     * Queries user todo tasks
     */
    public Optional<Map<String, Object>> getUserTasks(String userId, int page, int size) {
        return taskClient.getUserTasks(userId, page, size);
    }

    /**
     * Queries tasks for process instance
     */
    public Optional<Map<String, Object>> getProcessInstanceTasks(String processInstanceId) {
        return taskClient.getProcessInstanceTasks(processInstanceId);
    }

    /**
     * Queries all tasks visible to user (virtual groups and department roles)
     */
    public Optional<Map<String, Object>> getUserAllVisibleTasks(String userId, List<String> groupIds,
                                                                 List<String> deptRoles, int page, int size) {
        return taskClient.getUserAllVisibleTasks(userId, groupIds, deptRoles, page, size);
    }

    /**
     * Same with optional Flowable pushdown criteria (taskName / sort).
     */
    public Optional<Map<String, Object>> getUserAllVisibleTasks(String userId, List<String> groupIds,
                                                                 List<String> deptRoles, int page, int size,
                                                                 com.portal.util.EngineTaskPushdown.Criteria criteria) {
        return taskClient.getUserAllVisibleTasks(userId, groupIds, deptRoles, page, size, criteria);
    }

    /**
     * Returns task detail
     */
    public Optional<Map<String, Object>> getTaskById(String taskId) {
        return taskClient.getTaskById(taskId);
    }

    /**
     * Queries parent task sub-table data (backfill rowId / live sync before assign).
     */
    public Optional<Map<String, Object>> getSubTableDataAll(String taskId) {
        return taskClient.getSubTableDataAll(taskId);
    }

    /**
     * Counts user tasks
     */
    public Optional<Map<String, Object>> countUserTasks() {
        return taskClient.countUserTasks();
    }

    // ==================== Task operations (complete, claim, delegate, transfer, return)（委托 taskClient） ====================

    /**
     * Completes task
     */
    public Optional<Map<String, Object>> completeTask(String taskId, String userId,
                                                       String action, Map<String, Object> variables) {
        return taskClient.completeTask(taskId, userId, action, variables);
    }

    /**
     * Claims task
     */
    public Optional<Map<String, Object>> claimTask(String taskId, String userId) {
        return taskClient.claimTask(taskId, userId);
    }

    /**
     * Delegates task
     */
    public Optional<Map<String, Object>> delegateTask(String taskId, String delegatorId,
                                                       String delegateId, String reason) {
        return taskClient.delegateTask(taskId, delegatorId, delegateId, reason);
    }

    /**
     * Unclaims task
     */
    public Optional<Map<String, Object>> unclaimTask(String taskId, String userId) {
        return taskClient.unclaimTask(taskId, userId);
    }

    /**
     * Transfers task
     */
    public Optional<Map<String, Object>> transferTask(String taskId, String fromUserId,
                                                       String toUserId, String reason) {
        return taskClient.transferTask(taskId, fromUserId, toUserId, reason);
    }

    /**
     * Assigns sub-table row assignee (MI sub-process prerequisite task)
     */
    public Optional<Map<String, Object>> assignSubTableRow(String taskId, long rowId, String assigneeId) {
        return taskClient.assignSubTableRow(taskId, rowId, assigneeId);
    }

    /**
     * @param rowKey required for composite PK (path rowId may be placeholder; engine uses body)
     */
    public Optional<Map<String, Object>> assignSubTableRow(String taskId, long rowId, String assigneeId,
                                                           Map<String, Object> rowKey) {
        return taskClient.assignSubTableRow(taskId, rowId, assigneeId, rowKey);
    }

    /**
     * Returns task to specified historic activity
     */
    public Optional<Map<String, Object>> returnTask(String taskId, String targetActivityId,
                                                     String userId, String reason) {
        return taskClient.returnTask(taskId, targetActivityId, userId, reason);
    }

    /**
     * Returns task to specified historic activity.
     * @param returnKind {@code DRAFT} for return-to-first-step revision, {@code RETURN} or null for rollback
     */
    public Optional<Map<String, Object>> returnTask(String taskId, String targetActivityId,
                                                     String userId, String reason, String returnKind) {
        return taskClient.returnTask(taskId, targetActivityId, userId, reason, returnKind);
    }

    /**
     * Returns list of returnable historic activity nodes
     */
    public Optional<List<Map<String, Object>>> getReturnableActivities(String taskId) {
        return taskClient.getReturnableActivities(taskId);
    }

    // ==================== Process status and history（委托 processClient） ====================

    /**
     * Returns process instance detail (includes variables when available).
     */
    public Optional<Map<String, Object>> getProcessInstance(String processInstanceId) {
        return processClient.getProcessInstance(processInstanceId);
    }

    /**
     * Returns process instance status
     * Checks whether process completed and returns last activity node
     */
    public Optional<Map<String, Object>> getProcessInstanceStatus(String processInstanceId) {
        return processClient.getProcessInstanceStatus(processInstanceId);
    }

    /**
     * Returns current activity node for process instance
     */
    public Optional<Map<String, Object>> getCurrentActivity(String processInstanceId) {
        return processClient.getCurrentActivity(processInstanceId);
    }

    /**
     * Returns process history
     */
    public Optional<Map<String, Object>> getProcessHistory(String processInstanceId) {
        return processClient.getProcessHistory(processInstanceId);
    }

    /**
     * Returns multi-instance sub-process status (aggregated by sub-table row)
     */
    public Optional<Map<String, Object>> getMultiInstanceStatus(String processInstanceId) {
        return processClient.getMultiInstanceStatus(processInstanceId);
    }

    /**
     * Returns task history by process instance ID
     */
    public Optional<List<Map<String, Object>>> getTaskHistory(String processInstanceId) {
        return taskHistoryClient.getTaskHistory(processInstanceId);
    }

    /**
     * Returns process instance flow history with resolved display names
     */
    public Optional<List<Map<String, Object>>> getProcessInstanceHistory(String processInstanceId) {
        return taskHistoryClient.getProcessInstanceHistory(processInstanceId);
    }

    /**
     * Returns task flow history by task ID with resolved display names
     */
    public Optional<List<Map<String, Object>>> getTaskHistoryByTaskId(String taskId) {
        return taskHistoryClient.getTaskHistoryByTaskId(taskId);
    }

    // ==================== User permissions（委托 taskClient） ====================

    /**
     * Returns user task permissions (virtual groups and department roles)
     */
    public Optional<Map<String, Object>> getUserTaskPermissions(String userId) {
        return taskHistoryClient.getUserTaskPermissions(userId);
    }

    /**
     * Checks whether user may perform task operation
     */
    public Optional<Boolean> checkTaskPermission(String taskId, String userId) {
        return taskHistoryClient.checkTaskPermission(taskId, userId);
    }

    /**
     * Returns user's completed task list
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> getCompletedTasks(String userId, int page, int size,
                                                           String keyword, String startTime, String endTime) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        try {
            StringBuilder urlBuilder = new StringBuilder(workflowEngineUrl)
                .append("/api/v1/history/completed-tasks?userId=").append(userId)
                .append("&page=").append(page)
                .append("&size=").append(size);

            if (keyword != null && !keyword.isEmpty()) {
                urlBuilder.append("&keyword=").append(keyword);
            }
            if (startTime != null && !startTime.isEmpty()) {
                urlBuilder.append("&startTime=").append(startTime);
            }
            if (endTime != null && !endTime.isEmpty()) {
                urlBuilder.append("&endTime=").append(endTime);
            }

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                urlBuilder.toString(), HttpMethod.GET, authorizedGetEntity(),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(com.platform.common.util.ApiResponseBodyUnwrap.unwrapDataMap(response.getBody()));
            }
        } catch (Exception e) {
            log.warn("Failed to get completed tasks from workflow engine: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Returns user process statistics
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> getProcessStatistics(String userId) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        try {
            String url = workflowEngineUrl + "/api/v1/history/process-statistics?userId=" + SafeUrlInput.encodeQueryValue(userId);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, HttpMethod.GET, authorizedGetEntity(),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(com.platform.common.util.ApiResponseBodyUnwrap.unwrapDataMap(response.getBody()));
            }
        } catch (Exception e) {
            log.warn("Failed to get process statistics from workflow engine: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Cancels (terminates) process instance
     */
    public Optional<Map<String, Object>> cancelProcessInstance(String processInstanceId, String reason) {
        return processClient.cancelProcessInstance(processInstanceId, reason);
    }

    /**
     * Fetches BPMN XML by process definition key
     * @param processDefinitionKey process definition key
     * @return BPMN XML string, or Optional.empty() on failure
     */
    public Optional<String> getBpmnXml(String processDefinitionKey) {
        return processClient.getBpmnXml(processDefinitionKey);
    }

}
