package com.admin.bi.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DataViewAssignmentRequest {

    @NotBlank(message = "Dashboard ID is required")
    private String dashboardId;

    @NotNull(message = "Target function unit is required")
    @Positive(message = "Target function unit ID must be positive")
    private Long functionUnitId;

    @NotNull(message = "Target table is required")
    @Positive(message = "Target table ID must be positive")
    private Long tableId;
}
