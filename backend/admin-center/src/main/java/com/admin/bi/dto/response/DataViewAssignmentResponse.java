package com.admin.bi.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataViewAssignmentResponse {
    private String id;
    private String dashboardId;
    private String dashboardTitle;
    private Long functionUnitId;
    private String functionUnitCode;
    private String functionUnitName;
    private Long tableId;
    private String tableName;
    private String tableDisplayName;
    private String tableType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
