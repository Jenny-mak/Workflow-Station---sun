package com.admin.bi.dto.response;

import lombok.Builder;

import java.util.UUID;

@Builder
public record DataViewDashboardResponse(
        String dashboardId,
        String dashboardTitle,
        String description,
        UUID embedId) {
}
