package com.admin.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of validating an imported FunctionUnit package.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {
    
    /**
     * Overall pass/fail.
     */
    private boolean valid;
    
    /**
     * Package file format check.
     */
    private boolean fileFormatValid;
    
    /**
     * Integrity (checksum/manifest) check.
     */
    private boolean integrityValid;
    
    /**
     * Digital signature check.
     */
    private boolean signatureValid;
    
    /**
     * BPMN syntax check.
     */
    private boolean bpmnSyntaxValid;
    
    /**
     * Table design / relation metadata check.
     */
    private boolean dataTableValid;
    
    /**
     * Form configuration check.
     */
    private boolean formConfigValid;

    /**
     * Declared dependency resolution check.
     */
    @Builder.Default
    private boolean dependenciesValid = true;

    /**
     * Trial deploy against the Flowable engine.
     */
    @Builder.Default
    private boolean engineDeployValid = true;

    /**
     * FunctionUnit id being validated.
     */
    private String functionUnitId;

    /**
     * Resulting FunctionUnit status after validation ({@code DRAFT}, {@code VALIDATED}, or {@code DEPLOYED}).
     */
    private String status;
    
    /**
     * Blocking validation errors.
     */
    @Builder.Default
    private List<ImportResult.ValidationError> errors = new ArrayList<>();
    
    /**
     * Non-blocking warnings.
     */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
    
    /**
     * Builds an all-pass result.
     */
    public static ValidationResult success() {
        return ValidationResult.builder()
                .valid(true)
                .fileFormatValid(true)
                .integrityValid(true)
                .signatureValid(true)
                .bpmnSyntaxValid(true)
                .dataTableValid(true)
                .formConfigValid(true)
                .build();
    }
    
    /**
     * Builds a failed result with errors.
     */
    public static ValidationResult failure(List<ImportResult.ValidationError> errors) {
        return ValidationResult.builder()
                .valid(false)
                .errors(errors)
                .build();
    }
    
    /**
     * Append a structured error and mark {@code valid} false.
     */
    public void addError(String type, String field, String message) {
        if (errors == null) {
            errors = new ArrayList<>();
        }
        errors.add(ImportResult.ValidationError.builder()
                .type(type)
                .field(field)
                .message(message)
                .build());
        this.valid = false;
    }
    
    /**
     * Append a warning string.
     */
    public void addWarning(String warning) {
        if (warnings == null) {
            warnings = new ArrayList<>();
        }
        warnings.add(warning);
    }
}
