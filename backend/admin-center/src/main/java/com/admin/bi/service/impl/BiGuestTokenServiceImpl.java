package com.admin.bi.service.impl;

import com.admin.bi.client.SupersetApiClient;
import com.admin.bi.config.BiProperties;
import com.admin.bi.dto.request.GuestTokenRequest;
import com.admin.bi.dto.response.GuestTokenResponse;
import com.admin.bi.dto.response.UserDashboardResponse;
import com.admin.bi.entity.BiDashboardRegistry;
import com.admin.bi.enums.DashboardStatus;
import com.admin.bi.repository.BiDashboardRegistryRepository;
import com.admin.bi.service.BiDashboardAssignmentService;
import com.admin.bi.service.BiDataViewAssignmentService;
import com.admin.bi.service.BiGuestTokenService;
import com.admin.exception.DashboardInactiveException;
import com.admin.exception.DashboardNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Guest Token Service 实现
 * 验证用户 Dashboard 分配权限，合并 RBAC 映射，调用 Superset API 获取 Guest Token
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BiGuestTokenServiceImpl implements BiGuestTokenService {

    private final BiDashboardRegistryRepository dashboardRegistryRepository;
    private final BiDashboardAssignmentService assignmentService;
    private final SupersetApiClient supersetApiClient;
    private final BiProperties biProperties;

    /** Field injection keeps the long-standing constructor stable for property tests. */
    @Lazy
    @Autowired
    private BiDataViewAssignmentService dataViewAssignmentService;

    @Override
    @Transactional(readOnly = true)
    public GuestTokenResponse getGuestToken(String userId, GuestTokenRequest request) {
        String dashboardId = request.getDashboardId();

        // 1. Verify dashboard exists
        BiDashboardRegistry dashboard = dashboardRegistryRepository.findById(dashboardId)
                .orElseThrow(() -> new DashboardNotFoundException(dashboardId));
        if (dashboard.getStatus() != DashboardStatus.ACTIVE) {
            throw new DashboardInactiveException(dashboardId);
        }

        // 2. Verify the appropriate assignment context. Landing-page callers omit dataViewId and go
        //    through getUserDashboards, which already applies the RBAC-mapping role gate (Superset
        //    dashboard_roles vs. the user's mapped Superset roles), so a role-restricted dashboard the
        //    user cannot see is rejected here as well. Data -> Views callers must prove both the table
        //    binding and access to the concrete published view (same gate, applied in that service).
        boolean isAssigned;
        if (request.getDataViewId() != null) {
            isAssigned = dataViewAssignmentService != null
                    && dataViewAssignmentService.canAccessDashboardForView(
                    userId, dashboardId, request.getDataViewId());
        } else {
            List<UserDashboardResponse> userDashboards = assignmentService.getUserDashboards(userId, null);
            isAssigned = userDashboards.stream()
                    .anyMatch(d -> dashboardId.equals(d.getDashboardId()));
        }

        if (!isAssigned) {
            log.warn("User {} attempted to access unassigned dashboard {}", userId, dashboardId);
            throw new AccessDeniedException("Dashboard not assigned to user");
        }

        // 3. Call Superset API to get Guest Token. Superset's guest_token API takes no role list:
        //    the guest always runs as GUEST_ROLE_NAME, scoped to this one dashboard resource.
        String embedId = dashboard.getEmbedId().toString();
        String token = supersetApiClient.getGuestToken(embedId);

        log.debug("Guest token obtained for user {} on dashboard {}", userId, dashboardId);

        // 4. Return response
        String publicSupersetHost = StringUtils.hasText(biProperties.getSuperset().getPublicHost())
                ? biProperties.getSuperset().getPublicHost()
                : biProperties.getSuperset().getHost();

        return GuestTokenResponse.builder()
                .token(token)
                .dashboardEmbedId(embedId)
                .supersetDomain(publicSupersetHost)
                .build();
    }
}
