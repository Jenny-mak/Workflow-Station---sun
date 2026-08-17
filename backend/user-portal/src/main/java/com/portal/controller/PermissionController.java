package com.portal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.component.PermissionComponent;
import com.portal.component.RoleAccessComponent;
import com.platform.common.dto.ApiResponse;
import com.portal.security.CurrentUserId;
import com.portal.dto.PageResponse;
import com.portal.dto.PermissionRequestDto;
import com.portal.dto.PermissionRequestListItem;
import com.portal.entity.PermissionRequest;
import com.portal.enums.PermissionRequestStatus;
import com.portal.exception.PortalException;
import com.portal.util.PortalColumnFilterSupport;
import com.platform.common.i18n.I18nService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/permissions")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Permission Management", description = "Permission request and management APIs")
public class PermissionController {

    private static final int MAX_PAGE_SIZE = 200;

    private final PermissionComponent permissionComponent;
    private final RoleAccessComponent roleAccessComponent;
    private final I18nService i18nService;
    private final ObjectMapper objectMapper;

    // ==================== New API endpoints ====================

    @GetMapping("/available-roles")
    @Operation(summary = "Get available business roles", description = "Get the list of business roles the user can apply for (excluding already owned)")
    public ApiResponse<List<Map<String, Object>>> getAvailableRoles(
            @CurrentUserId String userId) {
        List<Map<String, Object>> roles = permissionComponent.getAvailableRoles(userId);
        return ApiResponse.success(roles);
    }

    @GetMapping("/available-virtual-groups")
    @Operation(summary = "Get available virtual groups", description = "Disabled: virtual groups are not available in User Portal")
    public ApiResponse<List<Map<String, Object>>> getAvailableVirtualGroups(
            @CurrentUserId String userId) {
        return ApiResponse.error("500", i18nService.getMessage("portal.virtual_group_not_in_portal"));
    }

    @GetMapping("/users/search")
    @Operation(summary = "Search active users", description = "Delegate user selection: returns only ACTIVE users (paginated)")
    public ApiResponse<PageResponse<Map<String, Object>>> searchUsersForDelegation(
            @CurrentUserId String userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(roleAccessComponent.searchActiveUsersForPortal(keyword, page, size));
    }

    @GetMapping("/available-business-units")
    @Operation(summary = "Get available business units", description = "Get the list of business units the user can join (excluding already joined)")
    public ApiResponse<List<Map<String, Object>>> getAvailableBusinessUnits(
            @CurrentUserId String userId) {
        List<Map<String, Object>> businessUnits = permissionComponent.getAvailableBusinessUnits(userId);
        return ApiResponse.success(businessUnits);
    }

    @GetMapping("/business-units")
    @Operation(summary = "Get business unit catalog", description = "Get platform business unit flat list (for member management dropdowns, etc.)")
    public ApiResponse<List<Map<String, Object>>> getBusinessUnitsCatalog(@CurrentUserId String userId) {
        log.debug("Business units catalog requested by user {}", userId);
        return ApiResponse.success(permissionComponent.getBusinessUnitsCatalog());
    }

    @GetMapping("/business-units/tree")
    @Operation(summary = "Get business unit tree", description = "Platform business unit tree (keeps children hierarchy, for cascader selectors)")
    public ApiResponse<List<Map<String, Object>>> getBusinessUnitsTree(@CurrentUserId String userId) {
        log.debug("Business unit tree requested by user {}", userId);
        return ApiResponse.success(permissionComponent.getBusinessUnitsTree());
    }

    @GetMapping("/business-units/{businessUnitId}/roles")
    @Operation(summary = "Get business unit bound roles", description = "Get the list of business roles bound to a business unit")
    public ApiResponse<List<Map<String, Object>>> getBusinessUnitRoles(
            @CurrentUserId String userId,
            @PathVariable String businessUnitId) {
        log.debug("Business unit roles requested by user {} for unit {}", userId, businessUnitId);
        return ApiResponse.success(permissionComponent.getBusinessUnitRoles(businessUnitId));
    }

    @PostMapping("/request-role")
    @Operation(summary = "Request role", description = "Apply for a business role in an organization unit (auto-approved)")
    public ApiResponse<PermissionRequest> requestRole(
            @CurrentUserId String userId,
            @RequestBody Map<String, String> body) {
        String roleId = body.get("roleId");
        String organizationUnitId = body.get("organizationUnitId");
        String reason = body.get("reason");
        
        if (roleId == null || roleId.isEmpty()) {
            return ApiResponse.error("500", i18nService.getMessage("validation.role_id_required"));
        }
        if (organizationUnitId == null || organizationUnitId.isEmpty()) {
            return ApiResponse.error("500", i18nService.getMessage("portal.org_unit_id_required"));
        }
        if (reason == null || reason.isEmpty()) {
            return ApiResponse.error("500", i18nService.getMessage("portal.reason_required"));
        }
        
        try {
            PermissionRequest request = permissionComponent.requestRoleAssignment(userId, roleId, organizationUnitId, reason);
            return ApiResponse.success(request);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("500", e.getMessage());
        }
    }

    @PostMapping("/request-virtual-group")
    @Operation(summary = "Request virtual group join", description = "Disabled: virtual groups are not available in User Portal")
    public ApiResponse<PermissionRequest> requestVirtualGroup(
            @CurrentUserId String userId,
            @RequestBody Map<String, String> body) {
        return ApiResponse.error("500", i18nService.getMessage("portal.virtual_group_not_in_portal"));
    }

    @PostMapping("/request-business-unit")
    @Operation(summary = "Request business unit join", description = "Can apply on behalf of others (beneficiaryUserId, optional)")
    public ApiResponse<PermissionRequest> requestBusinessUnit(
            @CurrentUserId String userId,
            @RequestBody Map<String, Object> body) {
        String businessUnitId = body.get("businessUnitId") != null ? body.get("businessUnitId").toString() : null;
        String reason = body.get("reason") != null ? body.get("reason").toString() : null;
        String beneficiaryUserId = beneficiaryUserIdFromBody(body, userId);

        if (businessUnitId == null || businessUnitId.isEmpty()) {
            return ApiResponse.error("500", i18nService.getMessage("portal.bu_id_required"));
        }
        if (reason == null || reason.isEmpty()) {
            return ApiResponse.error("500", i18nService.getMessage("portal.reason_required"));
        }

        try {
            PermissionRequest request = permissionComponent.requestBusinessUnitJoin(userId, beneficiaryUserId, businessUnitId, reason);
            return ApiResponse.success(request);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("500", e.getMessage());
        }
    }

    @PostMapping("/request-business-unit-role")
    @Operation(summary = "Request business unit join with role", description = "Apply to join a business unit and select an Eligible Role under it")
    public ApiResponse<PermissionRequest> requestBusinessUnitWithRole(
            @CurrentUserId String userId,
            @RequestBody Map<String, Object> body) {
        Object buIdObj = body.get("businessUnitId");
        String businessUnitId = buIdObj != null ? buIdObj.toString() : null;
        String reason = body.get("reason") != null ? body.get("reason").toString() : null;
        String beneficiaryUserId = beneficiaryUserIdFromBody(body, userId);

        String roleId = null;
        Object roleIdsObj = body.get("roleIds");
        if (roleIdsObj instanceof List<?> list && !list.isEmpty()) {
            roleId = Objects.toString(list.get(0), null);
        }
        if (roleId == null || roleId.isBlank()) {
            Object single = body.get("roleId");
            if (single != null) {
                roleId = single.toString();
            }
        }

        if (businessUnitId == null || businessUnitId.isEmpty()) {
            return ApiResponse.error("500", i18nService.getMessage("portal.bu_id_required"));
        }
        if (reason == null || reason.isEmpty()) {
            return ApiResponse.error("500", i18nService.getMessage("portal.reason_required"));
        }
        if (roleId == null || roleId.isBlank()) {
            return ApiResponse.error("500", i18nService.getMessage("validation.role_id_required"));
        }

        try {
            PermissionRequest request = permissionComponent.requestBusinessUnitJoinWithRole(userId, beneficiaryUserId, businessUnitId, roleId, reason);
            return ApiResponse.success(request);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("500", e.getMessage());
        }
    }

    @PostMapping("/request-business-unit-exit")
    @Operation(summary = "Request business unit exit", description = "Exit membership (executes after approval); can proxy via beneficiaryUserId")
    public ApiResponse<PermissionRequest> requestBusinessUnitExit(
            @CurrentUserId String userId,
            @RequestBody Map<String, Object> body) {
        String businessUnitId = body.get("businessUnitId") != null ? body.get("businessUnitId").toString() : null;
        String reason = body.get("reason") != null ? body.get("reason").toString() : null;
        String beneficiaryUserId = beneficiaryUserIdFromBody(body, userId);
        if (businessUnitId == null || businessUnitId.isEmpty()) {
            return ApiResponse.error("500", i18nService.getMessage("portal.bu_id_required"));
        }
        if (reason == null || reason.isEmpty()) {
            return ApiResponse.error("500", i18nService.getMessage("portal.reason_required"));
        }
        try {
            PermissionRequest request = permissionComponent.requestBusinessUnitExit(userId, beneficiaryUserId, businessUnitId, reason);
            return ApiResponse.success(request);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("500", e.getMessage());
        }
    }

    @GetMapping("/removal-options-by-function-unit")
    @Operation(summary = "View removable business unit roles by function unit", description = "Aggregated by beneficiary current BU roles and deployed function unit access config (role thresholds), for multi-select removal in portal.")
    public ApiResponse<Map<String, Object>> getRemovalOptionsByFunctionUnit(
            @CurrentUserId String userId,
            @RequestParam(required = false) String beneficiaryUserId) {
        String beneficiary = (beneficiaryUserId == null || beneficiaryUserId.isBlank())
                ? userId
                : beneficiaryUserId.trim();
        try {
            return ApiResponse.success(permissionComponent.buildRoleRemovalOptionsByFunctionUnit(beneficiary));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("500", e.getMessage());
        }
    }

    @PostMapping("/request-business-unit-role-removal")
    @Operation(summary = "Request removal of business role under business unit", description = "Submit a request to remove the role binding after BU approver approval")
    public ApiResponse<PermissionRequest> requestBusinessUnitRoleRemoval(
            @CurrentUserId String userId,
            @RequestBody Map<String, Object> body) {
        Object buIdObj = body.get("businessUnitId");
        String businessUnitId = buIdObj != null ? buIdObj.toString() : null;
        Object roleIdObj = body.get("roleId");
        String roleId = roleIdObj != null ? roleIdObj.toString() : null;
        String reason = body.get("reason") != null ? body.get("reason").toString() : null;
        String beneficiaryUserId = beneficiaryUserIdFromBody(body, userId);

        if (businessUnitId == null || businessUnitId.isEmpty()) {
            return ApiResponse.error("500", i18nService.getMessage("portal.bu_id_required"));
        }
        if (roleId == null || roleId.isBlank()) {
            return ApiResponse.error("500", i18nService.getMessage("validation.role_id_required"));
        }
        if (reason == null || reason.isEmpty()) {
            return ApiResponse.error("500", i18nService.getMessage("portal.reason_required"));
        }

        try {
            PermissionRequest request = permissionComponent.requestBusinessUnitRoleRemoval(userId, beneficiaryUserId, businessUnitId, roleId, reason);
            return ApiResponse.success(request);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("500", e.getMessage());
        }
    }

    @GetMapping("/my-roles")
    @Operation(summary = "Get my roles", description = "Get the list of business roles currently owned by the user")
    public ApiResponse<List<Map<String, Object>>> getMyRoles(
            @CurrentUserId String userId) {
        List<Map<String, Object>> roles = permissionComponent.getUserCurrentRoles(userId);
        return ApiResponse.success(roles);
    }

    @GetMapping("/my-virtual-groups")
    @Operation(summary = "Get my virtual groups", description = "Disabled: virtual groups are not available in User Portal")
    public ApiResponse<List<Map<String, Object>>> getMyVirtualGroups(
            @CurrentUserId String userId) {
        return ApiResponse.error("500", i18nService.getMessage("portal.virtual_group_not_in_portal"));
    }

    // ==================== Approval API endpoints ====================

    @GetMapping("/approvals/pending")
    @Operation(summary = "Get pending approvals", description = "Get permission requests the current user can approve. "
            + "Supports filters/sortField/sortDirection/groupBy with SQL true pagination (no fetch cap).")
    public ApiResponse<PageResponse<PermissionRequest>> getPendingApprovals(
            @CurrentUserId String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) String filters,
            @RequestParam(required = false) String groupBy) {
        if (!permissionComponent.isApprover(userId)) {
            return ApiResponse.error("500", i18nService.getMessage("portal.no_approval_permission"));
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        return ApiResponse.success(permissionComponent.pagePendingApprovals(
                userId, safePage, safeSize, parseFilters(filters), sortField, sortDirection, groupBy));
    }

    @PostMapping("/approvals/{requestId}/approve")
    @Operation(summary = "Approve request", description = "Approve a permission request")
    public ApiResponse<PermissionRequest> approveRequest(
            @CurrentUserId String userId,
            @PathVariable Long requestId,
            @RequestBody(required = false) Map<String, String> body) {
        // Check approval permission
        if (!permissionComponent.isApprover(userId)) {
            return ApiResponse.error("500", i18nService.getMessage("portal.no_approval_permission"));
        }
        
        String comment = body != null ? body.get("comment") : null;
        
        try {
            PermissionRequest request = permissionComponent.approveRequest(requestId, userId, comment);
            return ApiResponse.success(request);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("500", e.getMessage());
        }
    }

    @PostMapping("/approvals/{requestId}/reject")
    @Operation(summary = "Reject request", description = "Reject a permission request (reason required)")
    public ApiResponse<PermissionRequest> rejectRequest(
            @CurrentUserId String userId,
            @PathVariable Long requestId,
            @RequestBody Map<String, String> body) {
        // Check approval permission
        if (!permissionComponent.isApprover(userId)) {
            return ApiResponse.error("500", i18nService.getMessage("portal.no_approval_permission"));
        }
        
        String comment = body != null ? body.get("comment") : null;
        if (comment == null || comment.trim().isEmpty()) {
            return ApiResponse.error("500", i18nService.getMessage("portal.reject_reason_required"));
        }
        
        try {
            PermissionRequest request = permissionComponent.rejectRequest(requestId, userId, comment);
            return ApiResponse.success(request);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("500", e.getMessage());
        }
    }

    @GetMapping("/approvals/is-approver")
    @Operation(summary = "Check approval permission", description = "Check if the current user has approval permission")
    public ApiResponse<Map<String, Object>> isApprover(
            @CurrentUserId String userId) {
        boolean isApprover = permissionComponent.isApprover(userId);
        Map<String, Object> result = new HashMap<>();
        result.put("isApprover", isApprover);
        return ApiResponse.success(result);
    }

    @GetMapping("/approvals/history")
    @Operation(summary = "Get approval history", description = "Get the current user's approval history records. "
            + "Supports filters/sortField/sortDirection/groupBy with SQL true pagination (no fetch cap).")
    public ApiResponse<PageResponse<PermissionRequest>> getApprovalHistory(
            @CurrentUserId String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) String filters,
            @RequestParam(required = false) String groupBy) {
        if (!permissionComponent.isApprover(userId)) {
            return ApiResponse.error("500", i18nService.getMessage("portal.no_approval_permission"));
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        return ApiResponse.success(permissionComponent.pageApprovalHistory(
                userId, safePage, safeSize, parseFilters(filters), sortField, sortDirection, groupBy));
    }

    // ==================== Legacy API endpoints (kept for compatibility) ====================

    @GetMapping("/my")
    @Operation(summary = "Get my permission list")
    @Deprecated
    public ApiResponse<List<Map<String, Object>>> getMyPermissions(
            @CurrentUserId String userId) {
        List<Map<String, Object>> permissions = permissionComponent.getUserPermissions(userId);
        return ApiResponse.success(permissions);
    }

    @PostMapping("/request")
    @Operation(summary = "Submit permission request")
    @Deprecated
    public ApiResponse<PermissionRequest> submitRequest(
            @CurrentUserId String userId,
            @RequestBody @Valid PermissionRequestDto dto) {
        PermissionRequest request = permissionComponent.submitRequest(userId, dto);
        return ApiResponse.success(request);
    }

    @GetMapping("/requests/columns")
    @Operation(summary = "Get permission-request list column capabilities (kind / operators / enum codes)")
    public ApiResponse<List<com.portal.util.PortalListColumnMeta>> getPermissionRequestColumns() {
        return ApiResponse.success(permissionComponent.getPermissionRequestColumns());
    }

    @GetMapping("/requests")
    @Operation(summary = "Get my request records",
            description = "Optional status filter. For completed history (non-PENDING), pass excludePending=true "
                    + "so page total excludes PENDING. When status is set, status wins and excludePending is ignored. "
                    + "Supports filters/sortField/sortDirection/groupBy (SQL whitelist).")
    public ApiResponse<PageResponse<PermissionRequestListItem>> getMyRequests(
            @CurrentUserId String userId,
            @RequestParam(required = false) PermissionRequestStatus status,
            @RequestParam(required = false, defaultValue = "false") boolean excludePending,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) String filters,
            @RequestParam(required = false) String groupBy) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        var result = permissionComponent.getMyRequests(
                userId,
                status,
                excludePending,
                PageRequest.of(safePage, safeSize),
                parseFilters(filters),
                sortField,
                sortDirection,
                groupBy);
        return ApiResponse.success(result.toPageResponse());
    }

    @GetMapping("/requests/{requestId}")
    @Operation(summary = "Get request details")
    public ApiResponse<PermissionRequest> getRequestDetail(
            @CurrentUserId String userId,
            @PathVariable Long requestId) {
        return permissionComponent.getRequestDetailForViewer(requestId, userId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("500", i18nService.getMessage("portal.request_not_found_or_forbidden")));
    }

    @DeleteMapping("/requests/{requestId}")
    @Operation(summary = "Cancel request")
    public ApiResponse<Void> cancelRequest(
            @CurrentUserId String userId,
            @PathVariable Long requestId) {
        boolean success = permissionComponent.cancelRequest(userId, requestId);
        if (success) {
            return ApiResponse.success(null);
        }
        return ApiResponse.error("500", i18nService.getMessage("portal.cancel_request_failed"));
    }

    @PostMapping("/renew")
    @Operation(summary = "Renew request")
    @Deprecated
    public ApiResponse<PermissionRequest> renewPermission(
            @CurrentUserId String userId,
            @RequestBody Map<String, Object> body) {
        String permissionId = (String) body.get("permissionId");
        String validToStr = (String) body.get("validTo");
        String reason = (String) body.get("reason");
        
        LocalDateTime newValidTo = LocalDateTime.parse(validToStr);
        PermissionRequest request = permissionComponent.renewPermission(userId, permissionId, newValidTo, reason);
        return ApiResponse.success(request);
    }

    @GetMapping("/expiring")
    @Operation(summary = "Get expiring permissions")
    @Deprecated
    public ApiResponse<List<Map<String, Object>>> getExpiringPermissions(
            @CurrentUserId String userId,
            @RequestParam(defaultValue = "30") int days) {
        List<Map<String, Object>> expiring = permissionComponent.getExpiringPermissions(userId, days);
        return ApiResponse.success(expiring);
    }

    private static String beneficiaryUserIdFromBody(Map<String, Object> body, String currentUserId) {
        if (body == null) {
            return currentUserId;
        }
        Object o = body.get("beneficiaryUserId");
        if (o == null || o.toString().isBlank()) {
            return currentUserId;
        }
        return o.toString().trim();
    }

    private Map<String, Map<String, Object>> parseFilters(String filtersJson) {
        try {
            return PortalColumnFilterSupport.parseFiltersJson(filtersJson, objectMapper);
        } catch (IllegalArgumentException ex) {
            throw new PortalException("400", ex.getMessage());
        }
    }
}
