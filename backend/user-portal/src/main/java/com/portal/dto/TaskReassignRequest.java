package com.portal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TaskReassignRequest(
        @NotBlank @Size(max = 64) String targetUserId) {
}
