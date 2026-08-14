package com.portal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.component.DelegationComponent;
import com.platform.common.dto.ApiResponse;
import com.portal.security.CurrentUserId;
import com.portal.dto.DelegationRuleRequest;
import com.portal.dto.PageResponse;
import com.portal.entity.DelegationAudit;
import com.portal.entity.DelegationRule;
import com.portal.exception.PortalException;
import com.portal.util.PortalColumnFilterSupport;
import com.portal.util.PortalListColumnMeta;
import com.platform.common.i18n.I18nService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 委托管理API
 */
@Tag(name = "委托管理", description = "委托规则管理与审计")
@RestController
@RequestMapping("/delegations")
@RequiredArgsConstructor
public class DelegationController {

    private final DelegationComponent delegationComponent;
    private final I18nService i18nService;
    private final ObjectMapper objectMapper;

    @Operation(summary = "获取委托规则列表（传 page 时返回 PageResponse；省略 page 时返回全量 List）")
    @GetMapping
    public ApiResponse<?> getDelegationRules(
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
            var result = delegationComponent.getDelegationRules(
                    userId,
                    PageRequest.of(safePage, safeSize),
                    parseFilters(filters),
                    sortField,
                    sortDirection,
                    groupBy);
            return ApiResponse.success(result.toPageResponse());
        }
        return ApiResponse.success(delegationComponent.getDelegationRules(userId));
    }

    @Operation(summary = "获取委托规则列表的列能力（类型 / 可用算子 / 枚举取值）")
    @GetMapping("/columns")
    public ApiResponse<List<PortalListColumnMeta>> getDelegationRuleColumns() {
        return ApiResponse.success(delegationComponent.getDelegationRuleColumns());
    }

    @Operation(summary = "获取委托审计列表的列能力（类型 / 可用算子）")
    @GetMapping("/audit/columns")
    public ApiResponse<List<PortalListColumnMeta>> getDelegationAuditColumns() {
        return ApiResponse.success(delegationComponent.getDelegationAuditColumns());
    }

    @Operation(summary = "获取有效委托规则")
    @GetMapping("/active")
    public ApiResponse<List<DelegationRule>> getActiveDelegationRules(
            @CurrentUserId String userId) {
        List<DelegationRule> rules = delegationComponent.getActiveDelegationRules(userId);
        return ApiResponse.success(rules);
    }

    @Operation(summary = "创建委托规则")
    @PostMapping
    public ApiResponse<DelegationRule> createDelegationRule(
            @CurrentUserId String userId,
            @Valid @RequestBody DelegationRuleRequest request) {
        DelegationRule rule = delegationComponent.createDelegationRule(userId, request);
        return ApiResponse.success(rule);
    }

    @Operation(summary = "更新委托规则")
    @PutMapping("/{ruleId}")
    public ApiResponse<DelegationRule> updateDelegationRule(
            @PathVariable Long ruleId,
            @CurrentUserId String userId,
            @Valid @RequestBody DelegationRuleRequest request) {
        DelegationRule rule = delegationComponent.updateDelegationRule(ruleId, userId, request);
        return ApiResponse.success(rule);
    }

    @Operation(summary = "删除委托规则")
    @DeleteMapping("/{ruleId}")
    public ApiResponse<Void> deleteDelegationRule(
            @PathVariable Long ruleId,
            @CurrentUserId String userId) {
        delegationComponent.deleteDelegationRule(ruleId, userId);
        return ApiResponse.success();
    }

    @Operation(summary = "暂停委托规则")
    @PostMapping("/{ruleId}/suspend")
    public ApiResponse<DelegationRule> suspendDelegationRule(
            @PathVariable Long ruleId,
            @CurrentUserId String userId) {
        DelegationRule rule = delegationComponent.suspendDelegationRule(ruleId, userId);
        return ApiResponse.success(rule);
    }

    @Operation(summary = "恢复委托规则")
    @PostMapping("/{ruleId}/resume")
    public ApiResponse<DelegationRule> resumeDelegationRule(
            @PathVariable Long ruleId,
            @CurrentUserId String userId) {
        DelegationRule rule = delegationComponent.resumeDelegationRule(ruleId, userId);
        return ApiResponse.success(rule);
    }

    @Operation(summary = "获取委托人ID列表")
    @GetMapping("/delegators")
    public ApiResponse<List<String>> getDelegatorIds(
            @CurrentUserId String userId) {
        List<String> delegatorIds = delegationComponent.getDelegatorIds(userId);
        return ApiResponse.success(delegatorIds);
    }

    @Operation(summary = "获取委托审计记录")
    @GetMapping("/audit")
    public ApiResponse<PageResponse<DelegationAudit>> getDelegationAuditRecords(
            @CurrentUserId String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) String filters,
            @RequestParam(required = false) String groupBy) {
        int safePage = Math.max(0, page);
        int safeSize = size < 1 ? 20 : Math.min(size, 200);
        var result = delegationComponent.getDelegationAuditRecords(
                userId,
                PageRequest.of(safePage, safeSize),
                parseFilters(filters),
                sortField,
                sortDirection,
                groupBy);
        return ApiResponse.success(result.toPageResponse());
    }

    private Map<String, Map<String, Object>> parseFilters(String filtersJson) {
        try {
            return PortalColumnFilterSupport.parseFiltersJson(filtersJson, objectMapper);
        } catch (IllegalArgumentException ex) {
            throw new PortalException("400", ex.getMessage());
        }
    }
}
