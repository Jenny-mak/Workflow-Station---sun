package com.portal.controller;

import com.portal.component.DelegationComponent;
import com.portal.component.DelegationListQueryComponent;
import com.platform.common.dto.ApiResponse;
import com.portal.security.CurrentUserId;
import com.portal.dto.DelegationListQueryRequest;
import com.portal.dto.DelegationRuleRequest;
import com.portal.dto.PageResponse;
import com.portal.dto.PortalListPage;
import com.portal.entity.DelegationAudit;
import com.portal.entity.DelegationRule;
import com.platform.common.i18n.I18nService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 委托管理API
 */
@Tag(name = "委托管理", description = "委托规则管理和代理任务处理")
@RestController
@RequestMapping("/delegations")
@RequiredArgsConstructor
public class DelegationController {

    private final DelegationComponent delegationComponent;
    private final DelegationListQueryComponent delegationListQueryComponent;
    private final I18nService i18nService;

    @Operation(summary = "获取委托规则列表")
    @GetMapping
    public ApiResponse<List<DelegationRule>> getDelegationRules(
            @CurrentUserId String userId) {
        List<DelegationRule> rules = delegationComponent.getDelegationRules(userId);
        return ApiResponse.success(rules);
    }

    @Operation(summary = "Query my delegation rules (shared list)")
    @PostMapping("/query")
    public ApiResponse<PortalListPage<DelegationRule>> queryDelegationRules(
            @CurrentUserId String userId,
            @RequestBody DelegationListQueryRequest request) {
        return ApiResponse.success(delegationListQueryComponent.queryRules(userId, request));
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

    @Operation(summary = "获取代理任务（委托给我的）")
    @GetMapping("/proxy-tasks")
    public ApiResponse<List<DelegationRule>> getProxyTasks(
            @CurrentUserId String userId) {
        List<DelegationRule> delegations = delegationComponent.getDelegationsForDelegate(userId);
        return ApiResponse.success(delegations);
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
            @RequestParam(defaultValue = "20") int size) {
        Page<DelegationAudit> auditPage = delegationComponent.getDelegationAuditRecords(
                userId, PageRequest.of(page, size));
        PageResponse<DelegationAudit> response = PageResponse.of(
                auditPage.getContent(), page, size, auditPage.getTotalElements());
        return ApiResponse.success(response);
    }

    @Operation(summary = "Query delegation audit records (shared list)")
    @PostMapping("/audit/query")
    public ApiResponse<PortalListPage<DelegationAudit>> queryDelegationAudit(
            @CurrentUserId String userId,
            @RequestBody DelegationListQueryRequest request) {
        return ApiResponse.success(delegationListQueryComponent.queryAudit(userId, request));
    }
}
