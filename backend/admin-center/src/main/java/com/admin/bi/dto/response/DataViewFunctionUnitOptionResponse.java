package com.admin.bi.dto.response;

import lombok.Builder;

@Builder
public record DataViewFunctionUnitOptionResponse(Long id, String code, String name) {
}
