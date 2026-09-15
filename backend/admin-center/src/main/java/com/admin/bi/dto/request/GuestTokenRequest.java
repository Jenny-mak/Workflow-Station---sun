package com.admin.bi.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Guest Token 获取请求
 */
@Data
@NoArgsConstructor
public class GuestTokenRequest {

    /** Dashboard ID */
    @NotBlank(message = "Dashboard ID is required")
    private String dashboardId;

    /**
     * Optional User Portal Data View context. When present, authorization is based on the
     * dashboard-to-table binding and the current user's access to this published view.
     * When absent, the legacy Audience Assignment authorization path is retained.
     */
    @Positive(message = "Data View ID must be positive")
    private Long dataViewId;
}
