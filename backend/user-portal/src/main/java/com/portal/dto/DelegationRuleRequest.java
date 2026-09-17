package com.portal.dto;

import com.portal.enums.DelegateTargetType;
import com.portal.enums.DelegationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Standing delegation rule request. Target is a user or a paired BU+Role.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DelegationRuleRequest {

    /** USER target. Required when type is USER (default). */
    private String delegateId;

    /** USER (default) or BU_ROLE. */
    private DelegateTargetType delegateTargetType;

    private String delegateBuCode;

    private String delegateRoleCode;

    @NotNull(message = "{validation.delegation_type_required}")
    private DelegationType delegationType;

    /** Process definition keys when type is PARTIAL. */
    private List<String> processTypes;

    private List<String> priorityFilter;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private String reason;

    public DelegateTargetType effectiveTargetType() {
        return delegateTargetType != null ? delegateTargetType : DelegateTargetType.USER;
    }

    public boolean isBuRoleTarget() {
        return effectiveTargetType() == DelegateTargetType.BU_ROLE;
    }
}
