package com.admin.bi.dto.response;

import com.admin.bi.enums.DashboardStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Dashboard 注册表响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardRegistryResponse {

    private String id;
    private String dashboardTitle;
    private String description;
    private UUID embedId;
    private UUID supersetDashboardUuid;
    private Integer supersetDashboardId;
    private String tags;
    private Boolean isDefaultLanding;
    /** Superset role IDs granted on the dashboard (synced from dashboard_roles); empty = unrestricted. */
    private List<Integer> supersetRoleIds;
    /** Names of {@link #supersetRoleIds} as known by the local Superset role registry. */
    private List<String> supersetRoleNames;
    private DashboardStatus status;
    private LocalDateTime lastSyncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
