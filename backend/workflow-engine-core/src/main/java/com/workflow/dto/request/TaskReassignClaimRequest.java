package com.workflow.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Leader / Approver / SYS_ADMIN reassignment of a claim-pool hold to another pool member.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskReassignClaimRequest {

    @NotBlank(message = "Target user is required")
    private String targetUserId;
}
