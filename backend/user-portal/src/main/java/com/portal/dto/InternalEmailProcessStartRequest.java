package com.portal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * Internal (X-Internal-Token) payload for email-monitor process start.
 */
@Data
public class InternalEmailProcessStartRequest {

    @NotBlank
    private String startUserId;

    /** Developer Workstation function unit code; preferred process-key for catalog pin. */
    private String functionUnitCode;

    /** Flowable process definition key; used when {@link #functionUnitCode} is absent. */
    private String processDefinitionKey;

    private String businessKey;

    private Map<String, Object> variables;
}
