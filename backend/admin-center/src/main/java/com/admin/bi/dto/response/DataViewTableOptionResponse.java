package com.admin.bi.dto.response;

import lombok.Builder;

@Builder
public record DataViewTableOptionResponse(
        Long id,
        Long functionUnitId,
        String tableName,
        String tableDisplayName,
        String tableType) {
}
