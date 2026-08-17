package com.portal.controller;

import com.portal.component.FunctionUnitAccessComponent;
import com.portal.component.ProcessComponent;
import com.portal.component.TaskProcessComponent;
import com.portal.component.TaskQueryComponent;
import com.platform.security.util.SecurityContextUtils;
import com.platform.common.dto.ApiResponse;
import com.portal.dto.*;
import com.portal.exception.PortalException;
import com.portal.entity.ActionDefinition;
import com.portal.entity.ProcessDraft;
import com.portal.security.CurrentUserId;
import com.portal.util.ProcessApplicationListSpec;
import com.platform.common.i18n.I18nService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/processes")
@RequiredArgsConstructor
@Tag(name = "流程管理", description = "流程发起和跟踪相关接口")
public class ProcessController {

    private final ProcessComponent processComponent;
    private final I18nService i18nService;
    private final FunctionUnitAccessComponent functionUnitAccessComponent;
    private final com.portal.component.PortalPrimaryKeyAllocationComponent portalPrimaryKeyAllocationComponent;
    private final TaskQueryComponent taskQueryComponent;
    private final TaskProcessComponent taskProcessComponent;
    private final ObjectMapper objectMapper;

    @GetMapping("/definitions")
    @Operation(summary = "获取可发起的流程定义列表")
    public ApiResponse<List<ProcessDefinitionInfo>> getDefinitions(
            @CurrentUserId String userId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {
        List<ProcessDefinitionInfo> definitions = processComponent.getAvailableProcessDefinitions(userId, category, keyword);
        return ApiResponse.success(definitions);
    }
    
    @GetMapping("/startable")
    @Operation(summary = "获取可发起的流程列表", description = "获取所有已部署且启用的流程")
    public ApiResponse<List<ProcessDefinitionInfo>> getStartableProcesses(
            @CurrentUserId String userId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {
        String effectiveUserId = userId != null ? userId : "anonymous";
        List<ProcessDefinitionInfo> definitions = processComponent.getAvailableProcessDefinitions(effectiveUserId, category, keyword);
        return ApiResponse.success(definitions);
    }
    
    @GetMapping("/function-units/{functionUnitId}/content")
    @Operation(summary = "获取功能单元完整内容", description = "获取功能单元的 BPMN、表单等完整内容；需登录且满足功能单元业务角色访问策略（与可发起列表一致）；"
            + "任务参与人（处理人/候选人/发起人）可通过 taskId 放行，无需持有可发起角色")
    public ApiResponse<Map<String, Object>> getFunctionUnitContent(
            @CurrentUserId String userId,
            @PathVariable String functionUnitId,
            @RequestParam(required = false) String taskId) {
        requireFunctionUnitContentAccess(userId, functionUnitId, taskId);
        Map<String, Object> content = processComponent.getFunctionUnitContent(functionUnitId);
        return ApiResponse.success(content);
    }

    @GetMapping("/function-units/{functionUnitId}/contents")
    @Operation(summary = "获取功能单元特定类型的内容", description = "获取功能单元的特定类型内容（如表单、流程等），用于表单弹窗等场景")
    public ApiResponse<List<Map<String, Object>>> getFunctionUnitContentsByType(
            @CurrentUserId String userId,
            @PathVariable String functionUnitId,
            @RequestParam String contentType,
            @RequestParam(required = false) String taskId) {
        requireFunctionUnitContentAccess(userId, functionUnitId, taskId);
        List<Map<String, Object>> contents = processComponent.getFunctionUnitContents(functionUnitId, contentType);
        return ApiResponse.success(contents);
    }

    @PostMapping("/function-units/{functionUnitId}/tables/primary-keys/allocate")
    @Operation(summary = "Allocate primary key value(s) for a sub-table field",
            description = "Used by Portal sub-table add-row when PK strategy is not manual (PRD S5)")
    public ApiResponse<com.portal.dto.AllocatePrimaryKeyResponse> allocatePrimaryKeys(
            @CurrentUserId String userId,
            @PathVariable String functionUnitId,
            @RequestParam(required = false) String taskId,
            @Valid @RequestBody com.portal.dto.AllocatePrimaryKeyRequest request) {
        requireFunctionUnitContentAccess(userId, functionUnitId, taskId);
        return ApiResponse.success(portalPrimaryKeyAllocationComponent.allocate(functionUnitId, request));
    }
    
    /**
     * @deprecated Use GET /function-units/{functionUnitId}/contents instead
     */
    @Deprecated
    @GetMapping("/fu-data/{functionUnitId}")
    @Operation(summary = "获取功能单元特定类型的内容", description = "获取功能单元的特定类型内容（如表单、流程等），用于表单弹窗等场景")
    public ApiResponse<List<Map<String, Object>>> getFunctionUnitData(
            @CurrentUserId String userId,
            @PathVariable String functionUnitId,
            @RequestParam String contentType) {
        requireFunctionUnitContentAccess(userId, functionUnitId);
        List<Map<String, Object>> contents = processComponent.getFunctionUnitContents(functionUnitId, contentType);
        return ApiResponse.success(contents);
    }
    
    /**
     * @deprecated Use GET /function-units/{functionUnitId}/contents instead
     */
    @Deprecated
    @GetMapping("/function-unit-contents/{functionUnitId}")
    @Operation(summary = "获取功能单元特定类型的内容", description = "获取功能单元的特定类型内容（如表单、流程等），用于表单弹窗等场景")
    public ApiResponse<List<Map<String, Object>>> getFunctionUnitContents(
            @CurrentUserId String userId,
            @PathVariable String functionUnitId,
            @RequestParam String contentType) {
        requireFunctionUnitContentAccess(userId, functionUnitId);
        List<Map<String, Object>> contents = processComponent.getFunctionUnitContents(functionUnitId, contentType);
        return ApiResponse.success(contents);
    }

    private void requireFunctionUnitContentAccess(String userId, String functionUnitIdOrCode) {
        requireFunctionUnitContentAccess(userId, functionUnitIdOrCode, null);
    }

    private void requireFunctionUnitContentAccess(String userId, String functionUnitIdOrCode, String taskId) {
        if (userId == null || userId.isBlank()) {
            throw new FunctionUnitAccessComponent.FunctionUnitAccessDeniedException("Please login first before accessing function unit content");
        }
        // Task participants (assignee/candidate/initiator) may lack the FU's start-access roles
        // (e.g. a mid-flow approver role not in the FU access list) but still need BPMN + form
        // content to view and process their task.
        if (taskGrantsFunctionUnitContentAccess(userId, functionUnitIdOrCode, taskId)) {
            return;
        }
        functionUnitAccessComponent.checkFunctionUnitAccess(userId, functionUnitIdOrCode);
    }

    /**
     * Task-scoped grant for FU content: the task must belong to the requested function unit and the
     * user must pass {@code canViewTaskForm} for it. Only the role gate is bypassed — the disabled
     * gate still applies via {@link FunctionUnitAccessComponent#requireEnabledFunctionUnit}.
     */
    private boolean taskGrantsFunctionUnitContentAccess(String userId, String functionUnitIdOrCode, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        try {
            TaskInfo task = taskQueryComponent.getTaskById(taskId).orElse(null);
            if (task == null || !taskBelongsToFunctionUnit(task, functionUnitIdOrCode)) {
                return false;
            }
            functionUnitAccessComponent.requireEnabledFunctionUnit(userId, functionUnitIdOrCode);
            boolean allowed = taskProcessComponent.canViewTaskForm(task, userId,
                    SecurityContextUtils.getCurrentUsername().orElse(null));
            if (allowed) {
                log.info("Granting function unit {} content access to user {} via task {}",
                        functionUnitIdOrCode, userId, taskId);
            }
            return allowed;
        } catch (FunctionUnitAccessComponent.FunctionUnitDisabledException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Task-based function unit content access check failed (task {}): {}", taskId, e.getMessage());
            return false;
        }
    }

    private boolean taskBelongsToFunctionUnit(TaskInfo task, String functionUnitIdOrCode) {
        String taskProcessKey = task.getProcessDefinitionKey();
        if (taskProcessKey == null || taskProcessKey.isBlank()
                || functionUnitIdOrCode == null || functionUnitIdOrCode.isBlank()) {
            return false;
        }
        // Task detail passes the task's own processDefinitionKey, so a direct match is the hot path.
        if (taskProcessKey.trim().equals(functionUnitIdOrCode.trim())) {
            return true;
        }
        // Other callers may pass the FU catalog id/code — resolve both sides to catalog IDs.
        String requested = functionUnitAccessComponent.resolveFunctionUnitId(functionUnitIdOrCode.trim());
        String owning = functionUnitAccessComponent.resolveFunctionUnitId(taskProcessKey.trim());
        return requested != null && requested.equals(owning);
    }
    
    /**
     * 处理功能单元已禁用异常
     */
    @ExceptionHandler(FunctionUnitAccessComponent.FunctionUnitDisabledException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleFunctionUnitDisabled(FunctionUnitAccessComponent.FunctionUnitDisabledException e) {
        return ApiResponse.error("403", e.getMessage());
    }
    
    /**
     * 处理功能单元访问被拒绝异常
     */
    @ExceptionHandler(FunctionUnitAccessComponent.FunctionUnitAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleFunctionUnitAccessDenied(FunctionUnitAccessComponent.FunctionUnitAccessDeniedException e) {
        return ApiResponse.error("403", e.getMessage());
    }

    /**
     * 处理流程发起业务异常（如 BPMN 获取失败、引擎不可用等）
     */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleProcessException(RuntimeException e) {
        log.error("Process operation failed: {}", e.getMessage(), e);
        return ApiResponse.error("500", e.getMessage());
    }

    /**
     * 流程相关 PortalException（如发起前工作台校验失败）— 按 code 返回 4xx，便于前端展示明确提示
     */
    @ExceptionHandler(PortalException.class)
    public ApiResponse<Void> handlePortalException(PortalException e, jakarta.servlet.http.HttpServletResponse response) {
        int statusCode = switch (e.getCode()) {
            case "404" -> HttpStatus.NOT_FOUND.value();
            case "403" -> HttpStatus.FORBIDDEN.value();
            case "400" -> HttpStatus.BAD_REQUEST.value();
            default -> HttpStatus.INTERNAL_SERVER_ERROR.value();
        };
        response.setStatus(statusCode);
        log.warn("Process PortalException code={} message={}", e.getCode(), e.getMessage());
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    @GetMapping("/actions")
    @Operation(summary = "根据ID列表获取动作定义")
    public ApiResponse<List<ActionDefinition>> getActionsByIds(
            @RequestParam List<String> ids) {
        List<ActionDefinition> actions = processComponent.getActionsByIds(ids);
        return ApiResponse.success(actions);
    }

    @PostMapping("/{processKey}/start")
    @Operation(summary = "发起流程")
    public ApiResponse<ProcessInstanceInfo> startProcess(
            @CurrentUserId String userId,
            @PathVariable String processKey,
            @RequestBody @Valid ProcessStartRequest request) {
        if (!StringUtils.hasText(request.getProcessDefinitionKey())) {
            request.setProcessDefinitionKey(processKey);
        }
        ProcessInstanceInfo instance = processComponent.startProcess(userId, processKey, request);
        return ApiResponse.success(instance);
    }

    @GetMapping("/my-applications")
    @Operation(summary = "获取我的申请列表")
    public ApiResponse<PageResponse<ProcessInstanceInfo>> getMyApplications(
            @CurrentUserId String userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) String filters,
            @RequestParam(required = false) String groupBy,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (userId == null || userId.isBlank()) {
            throw new FunctionUnitAccessComponent.FunctionUnitAccessDeniedException("Please login first before viewing your applications");
        }
        int safePage = Math.max(0, page);
        int safeSize = size < 1 ? 20 : Math.min(size, 100);
        try {
            var result = processComponent.getMyApplications(
                    userId,
                    status,
                    keyword,
                    sortField,
                    sortDirection,
                    parseApplicationFilters(filters),
                    groupBy,
                    PageRequest.of(safePage, safeSize));
            return ApiResponse.success(result.toPageResponse());
        } catch (IllegalArgumentException ex) {
            throw new PortalException("400", ex.getMessage());
        }
    }

    @GetMapping("/my-applications/columns")
    @Operation(summary = "获取我的申请列表的列能力（类型 / 可用算子 / 枚举取值）")
    public ApiResponse<List<com.portal.util.PortalListColumnMeta>> getMyApplicationColumns() {
        return ApiResponse.success(processComponent.getApplicationColumns());
    }

    /**
     * Accepts map-shaped filters JSON {@code {field:{operator,value}}} (portal grid runtime)
     * or array {@code [{fieldName,operator,value}]}.
     */
    private Map<String, Map<String, Object>> parseApplicationFilters(String filtersJson) {
        if (filtersJson == null || filtersJson.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(filtersJson, Object.class);
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            if (parsed instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> map)) {
                        continue;
                    }
                    Object field = map.containsKey("fieldName") ? map.get("fieldName") : map.get("field");
                    if (field == null) {
                        continue;
                    }
                    Map<String, Object> body = new LinkedHashMap<>();
                    if (map.get("operator") != null) {
                        body.put("operator", map.get("operator"));
                    }
                    if (map.containsKey("value")) {
                        body.put("value", map.get("value"));
                    }
                    out.put(String.valueOf(field), body);
                }
                return out;
            }
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> loose = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() != null) {
                        loose.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
                return ProcessApplicationListSpec.coerceFilterMap(loose);
            }
            return Map.of();
        } catch (Exception ex) {
            throw new PortalException("400", "Invalid filters JSON: " + ex.getMessage());
        }
    }

    @GetMapping("/{processId}")
    @Operation(summary = "获取流程详情")
    public ApiResponse<ProcessInstanceInfo> getProcessDetail(
            @CurrentUserId String userId,
            @PathVariable String processId) {
        if (userId == null || userId.isBlank()) {
            throw new FunctionUnitAccessComponent.FunctionUnitAccessDeniedException(
                    "Please login first before viewing process details");
        }
        ProcessInstanceInfo detail = processComponent.getProcessDetail(processId);
        if (detail != null && !processComponent.canAccessProcessDetail(userId, detail)) {
            log.warn("User {} attempted to access process {} without detail access", userId, processId);
            return ApiResponse.error("403", i18nService.getMessage("portal.process_detail_access_denied"));
        }
        return ApiResponse.success(detail);
    }

    @PostMapping("/{processId}/withdraw")
    @Operation(summary = "撤回流程")
    public ApiResponse<Void> withdrawProcess(
            @CurrentUserId String userId,
            @PathVariable String processId,
            @RequestBody Map<String, String> body) {
        String reason = body.get("reason");
        boolean success = processComponent.withdrawProcess(userId, processId, reason);
        if (success) {
            return ApiResponse.success(null);
        }
        return ApiResponse.error("500", i18nService.getMessage("portal.withdraw_failed"));
    }

    @PostMapping("/{processId}/urge")
    @Operation(summary = "催办流程")
    public ApiResponse<Void> urgeProcess(
            @CurrentUserId String userId,
            @PathVariable String processId) {
        boolean success = processComponent.urgeProcess(userId, processId);
        if (success) {
            return ApiResponse.success(null);
        }
        return ApiResponse.error("500", i18nService.getMessage("portal.urge_failed"));
    }

    @PostMapping("/{processId}/return-to-first")
    @Operation(summary = "退回第一个用户任务节点（起草）")
    public ApiResponse<Void> returnProcessToFirstStep(
            @CurrentUserId String userId,
            @PathVariable String processId,
            @RequestBody(required = false) Map<String, String> body) {
        String comment = body != null ? body.get("comment") : null;
        boolean success = processComponent.returnProcessToFirstStep(userId, processId, comment);
        if (success) {
            return ApiResponse.success(null);
        }
        return ApiResponse.error("500", i18nService.getMessage("portal.return_to_first_failed"));
    }

    @PostMapping("/{processKey}/favorite")
    @Operation(summary = "切换收藏状态")
    public ApiResponse<Boolean> toggleFavorite(
            @CurrentUserId String userId,
            @PathVariable String processKey) {
        boolean isFavorite = processComponent.toggleFavorite(userId, processKey);
        return ApiResponse.success(isFavorite);
    }

    @PostMapping("/{processKey}/draft")
    @Operation(summary = "保存草稿")
    public ApiResponse<ProcessDraft> saveDraft(
            @CurrentUserId String userId,
            @PathVariable String processKey,
            @RequestBody Map<String, Object> formData) {
        ProcessDraft draft = processComponent.saveDraft(userId, processKey, formData);
        return ApiResponse.success(draft);
    }

    @GetMapping("/{processKey}/draft")
    @Operation(summary = "获取草稿")
    public ApiResponse<ProcessDraft> getDraft(
            @CurrentUserId String userId,
            @PathVariable String processKey) {
        return processComponent.getDraft(userId, processKey)
                .map(ApiResponse::success)
                .orElse(ApiResponse.success(null));
    }

    @DeleteMapping("/{processKey}/draft")
    @Operation(summary = "删除草稿")
    public ApiResponse<Void> deleteDraft(
            @CurrentUserId String userId,
            @PathVariable String processKey) {
        processComponent.deleteDraft(userId, processKey);
        return ApiResponse.success(null);
    }
    
    @GetMapping("/drafts")
    @Operation(summary = "获取草稿列表（传 page 时返回 PageResponse；省略 page 时返回全量 List）")
    public ApiResponse<?> getDraftList(
            @CurrentUserId String userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) String filters,
            @RequestParam(required = false) String groupBy) {
        if (page != null) {
            int safePage = Math.max(0, page);
            int safeSize = size == null || size < 1 ? 20 : Math.min(size, 200);
            try {
                return ApiResponse.success(processComponent.getDraftPage(
                        userId,
                        safePage,
                        safeSize,
                        sortField,
                        sortDirection,
                        parseApplicationFilters(filters),
                        groupBy));
            } catch (IllegalArgumentException ex) {
                throw new PortalException("400", ex.getMessage());
            }
        }
        return ApiResponse.success(processComponent.getDraftList(userId));
    }

    @GetMapping("/drafts/columns")
    @Operation(summary = "获取草稿列表的列能力（类型 / 可用算子）")
    public ApiResponse<List<com.portal.util.PortalListColumnMeta>> getDraftColumns() {
        return ApiResponse.success(processComponent.getDraftColumns());
    }
    
    @DeleteMapping("/drafts/{draftId}")
    @Operation(summary = "根据ID删除草稿")
    public ApiResponse<Void> deleteDraftById(
            @CurrentUserId String userId,
            @PathVariable Long draftId) {
        processComponent.deleteDraftById(userId, draftId);
        return ApiResponse.success(null);
    }
    
    @GetMapping("/{processId}/history")
    @Operation(summary = "获取流程历史记录")
    public ApiResponse<List<Map<String, Object>>> getProcessHistory(
            @CurrentUserId String userId,
            @PathVariable String processId) {
        if (userId == null || userId.isBlank()) {
            throw new FunctionUnitAccessComponent.FunctionUnitAccessDeniedException(
                    "Please login first before viewing process history");
        }
        ProcessInstanceInfo detail = processComponent.getProcessDetail(processId);
        if (detail != null && !processComponent.canAccessProcessDetail(userId, detail)) {
            log.warn("User {} attempted to access process history {} without detail access", userId, processId);
            return ApiResponse.error("403", i18nService.getMessage("portal.process_detail_access_denied"));
        }
        log.debug("ProcessController.getProcessHistory called with processId: {}", processId);
        List<Map<String, Object>> history = processComponent.getProcessHistory(processId);
        log.debug("ProcessController.getProcessHistory returning {} records", history.size());
        return ApiResponse.success(history);
    }
    
    @PostMapping("/{processId}/complete")
    @Operation(summary = "流程完成通知", description = "由 workflow-engine 调用，通知流程已完成")
    public ApiResponse<Void> processCompleted(
            @PathVariable String processId,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String serviceToken,
            @RequestBody Map<String, Object> request) {
        if (serviceToken == null || serviceToken.isBlank()) {
            log.warn("processCompleted called without X-Internal-Service-Token for processId: {}", processId);
            return ApiResponse.error("403", "Forbidden: missing internal service token");
        }
        log.debug("ProcessController.processCompleted called for processId: {}", processId);
        String lastActivityName = (String) request.get("lastActivityName");
        processComponent.markProcessAsCompleted(processId, lastActivityName);
        return ApiResponse.success(null);
    }
}

