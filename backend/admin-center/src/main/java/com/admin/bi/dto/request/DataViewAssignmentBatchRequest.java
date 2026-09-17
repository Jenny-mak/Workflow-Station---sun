package com.admin.bi.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Creates several dashboard bindings for one Data View table in a single transaction. */
@Data
@NoArgsConstructor
public class DataViewAssignmentBatchRequest {

    @NotEmpty(message = "At least one Dashboard ID is required")
    private List<@NotBlank(message = "Dashboard ID is required") String> dashboardIds;

    @NotNull(message = "Target function unit is required")
    @Positive(message = "Target function unit ID must be positive")
    private Long functionUnitId;

    @NotNull(message = "Target table is required")
    @Positive(message = "Target table ID must be positive")
    private Long tableId;
}
