package com.developer.controller;

import com.platform.common.dto.ApiResponse;
import com.platform.common.exception.ErrorResponse;
import com.developer.dto.ValidationResult;
import com.developer.exception.DeveloperBusinessException;
import com.developer.exception.ResourceNotFoundException;
import com.developer.validation.InputValidator;
import com.developer.validation.SecurityInputValidator;
import com.platform.common.exception.GlobalExceptionHandler;
import com.platform.common.security.SecurityIntegrationService;
import com.platform.common.security.SecurityAuditLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Enhanced base controller class that provides common functionality for all API controllers
 * in the developer-workstation module.
 *
 * <p>Uses platform-common's {@code ApiResponse}/{@code ErrorResponse} (the module-local
 * duplicates were removed in the code-quality remediation).
 *
 * <p><b>Validates: Requirements 1.1, 3.1, 4.2, 5.1, 7.5</b>
 */
@Slf4j
public abstract class BaseController {
    
    @Autowired
    protected SecurityInputValidator securityValidator;
    
    @Autowired(required = false)
    protected SecurityIntegrationService securityIntegrationService;
    
    @Autowired(required = false)
    protected SecurityAuditLogger securityAuditLogger;
    
    @Autowired(required = false)
    protected GlobalExceptionHandler globalExceptionHandler;
    
    /**
     * Handles a request with automatic input validation and error handling
     * 
     * @param processor The request processing logic
     * @param <T> The response type
     * @return ResponseEntity with the processed result or error response
     */
    protected <T> ResponseEntity<ApiResponse<T>> handleRequest(RequestProcessor<T> processor) {
        try {
            T result = processor.process();
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("Request processing failed", e);
            return handleError(e);
        }
    }
    
    /**
     * Handles a request with input validation using the enhanced security framework
     * 
     * @param input The input to validate
     * @param processor The request processing logic
     * @param <T> The response type
     * @return ResponseEntity with the processed result or validation error
     */
    protected <T> ResponseEntity<ApiResponse<T>> handleRequestWithValidation(
            String input, RequestProcessor<T> processor) {
        
        // Use security integration service if available, otherwise fall back to direct validation
        if (securityIntegrationService != null && input != null) {
            try {
                securityIntegrationService.validateAndAuditInput("request_input", input, "api_request");
            } catch (Exception e) {
                log.warn("Security validation failed for input: {}", e.getMessage());
                return createSecurityErrorResponse("Input validation failed due to security concerns");
            }
        } else {
            // Fallback to direct validation
            ValidationResult validationResult = securityValidator.validate(input);
            if (!validationResult.isValid()) {
                return createValidationErrorResponse(validationResult);
            }
        }
        
        return handleRequest(processor);
    }
    
    /**
     * Handles a request with multiple input validation using the enhanced security framework
     * 
     * @param inputs The inputs to validate
     * @param processor The request processing logic
     * @param <T> The response type
     * @return ResponseEntity with the processed result or validation error
     */
    protected <T> ResponseEntity<ApiResponse<T>> handleRequestWithValidation(
            List<String> inputs, RequestProcessor<T> processor) {
        
        // Use security integration service if available
        if (securityIntegrationService != null) {
            try {
                for (String input : inputs) {
                    if (StringUtils.hasText(input)) {
                        securityIntegrationService.validateAndAuditInput("request_input", input, "api_request");
                    }
                }
            } catch (Exception e) {
                log.warn("Security validation failed for inputs: {}", e.getMessage());
                return createSecurityErrorResponse("Input validation failed due to security concerns");
            }
        } else {
            // Fallback to direct validation
            for (String input : inputs) {
                if (StringUtils.hasText(input)) {
                    ValidationResult validationResult = securityValidator.validate(input);
                    if (!validationResult.isValid()) {
                        return createValidationErrorResponse(validationResult);
                    }
                }
            }
        }
        
        return handleRequest(processor);
    }
    
    /**
     * Enhanced request handling with operation context for better audit logging
     * 
     * @param inputs The inputs to validate
     * @param operation The operation being performed (for audit logging)
     * @param processor The request processing logic
     * @param <T> The response type
     * @return ResponseEntity with the processed result or validation error
     */
    protected <T> ResponseEntity<ApiResponse<T>> handleRequestWithValidation(
            List<String> inputs, String operation, RequestProcessor<T> processor) {
        
        // Use security integration service with operation context
        if (securityIntegrationService != null) {
            try {
                for (String input : inputs) {
                    if (StringUtils.hasText(input)) {
                        securityIntegrationService.validateAndAuditInput("request_input", input, operation);
                    }
                }
            } catch (Exception e) {
                log.warn("Security validation failed for operation {}: {}", operation, e.getMessage());
                return createSecurityErrorResponse("Input validation failed due to security concerns");
            }
        } else {
            // Fallback to direct validation with audit logging
            for (String input : inputs) {
                if (StringUtils.hasText(input)) {
                    ValidationResult validationResult = securityValidator.validate(input);
                    if (!validationResult.isValid()) {
                        // Log security audit event if available
                        if (securityAuditLogger != null) {
                            Map<String, Object> auditMetadata = Map.of(
                                    "operation", operation, 
                                    "errors", validationResult.getErrors().size()
                            );
                            logSecurityEvent("VALIDATION_FAILED", 
                                    "Input validation failed for operation: " + operation, 
                                    auditMetadata);
                        }
                        return createValidationErrorResponse(validationResult);
                    }
                }
            }
        }
        
        return handleRequest(processor);
    }
    
    /**
     * Sanitizes input using the security validator
     * 
     * @param input The input to sanitize
     * @return The sanitized input
     */
    protected String sanitizeInput(String input) {
        if (!StringUtils.hasText(input)) {
            return input;
        }
        return securityValidator.sanitize(input);
    }
    
    /**
     * Validates input and returns the validation result
     * 
     * @param input The input to validate
     * @return ValidationResult containing validation status and errors
     */
    protected ValidationResult validateInput(String input) {
        return securityValidator.validate(input);
    }
    
    /**
     * Checks if input is safe (passes security validation)
     * 
     * @param input The input to check
     * @return true if input is safe, false otherwise
     */
    protected boolean isInputSafe(String input) {
        return securityValidator.isValid(input);
    }
    
    /**
     * Enhanced audit logging for security events
     * 
     * @param eventType The type of security event
     * @param description Description of the event
     * @param metadata Additional metadata for the event
     */
    protected void logSecurityEvent(String eventType, String description, Map<String, Object> metadata) {
        if (securityAuditLogger != null) {
            // Convert Map<String, Object> to Map<String, String> for SecurityAuditLogger
            Map<String, String> auditMetadata = new HashMap<>();
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                auditMetadata.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            securityAuditLogger.logSecurityEvent(eventType, description, auditMetadata);
        } else {
            log.warn("Security audit logger not available, logging to standard log: {} - {}", eventType, description);
        }
    }
    
    /**
     * Enhanced error handling using the global exception handler if available
     * 
     * @param e The exception to handle
     * @param <T> The response type
     * @return ResponseEntity with appropriate error response
     */
    protected <T> ResponseEntity<ApiResponse<T>> handleError(Exception e) {
        // Forward DeveloperBusinessException with its actual message, code, and suggestion
        // so the frontend can display meaningful errors instead of "Request processing failed"
        if (e instanceof DeveloperBusinessException dbe) {
            ErrorResponse.ErrorResponseBuilder builder = ErrorResponse.builder()
                    .code(dbe.getErrorCode() != null ? dbe.getErrorCode() : "BIZ_ERROR")
                    .message(dbe.getMessage())
                    .suggestion(dbe.getSuggestion())
                    .timestamp(Instant.now())
                    .traceId(UUID.randomUUID().toString());
            // AI 校验失败带着逐条错误：不放进 details 前端只能看到一句"validation failed"，用户无从下手
            if (e instanceof com.developer.exception.AiValidationFailedException ave && ave.getErrors() != null) {
                List<Map<String, String>> errors = ave.getErrors().stream()
                        .map(err -> {
                            Map<String, String> m = new HashMap<>();
                            m.put("errorType", err.getErrorType());
                            m.put("fieldPath", err.getFieldPath());
                            m.put("description", err.getDescription());
                            return m;
                        })
                        .toList();
                builder.details(Map.of("errors", errors));
            }
            ErrorResponse error = builder.build();
            String code = dbe.getErrorCode();
            HttpStatus httpStatus = HttpStatus.BAD_REQUEST;
            if (code != null && code.startsWith("CONFLICT_")) {
                httpStatus = HttpStatus.CONFLICT;
            }
            log.warn("Business exception [{}]: {}", code, dbe.getMessage());
            return ResponseEntity.status(httpStatus).body(ApiResponse.error(error));
        }
        
        // 工作区隔离拒绝：与 WorkspaceExceptionHandler 同语义的 403（在 handleRequest 内抛出时不会走到那边）
        if (e instanceof com.developer.security.FunctionUnitWorkspaceAccessDeniedException denied) {
            ErrorResponse error = ErrorResponse.builder()
                    .code("WORKSPACE_FORBIDDEN")
                    .message(denied.getMessage())
                    .timestamp(Instant.now())
                    .traceId(UUID.randomUUID().toString())
                    .build();
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(error));
        }

        // Forward ResourceNotFoundException with a proper 404
        if (e instanceof ResourceNotFoundException rnfe) {
            ErrorResponse error = ErrorResponse.builder()
                    .code(rnfe.getErrorCode() != null ? rnfe.getErrorCode() : "RESOURCE_NOT_FOUND")
                    .message(rnfe.getMessage())
                    .timestamp(Instant.now())
                    .traceId(UUID.randomUUID().toString())
                    .build();
            log.warn("Resource not found: {}", rnfe.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(error));
        }
        
        // Use global exception handler if available
        if (globalExceptionHandler != null) {
            try {
                log.debug("Global exception handler available but interface may need updating");
            } catch (Exception handlerException) {
                log.error("Global exception handler failed", handlerException);
            }
        }
        
        // Fallback to local error handling
        return createGenericErrorResponse(e);
    }
    
    /**
     * Creates a validation error response from validation result
     */
    private <T> ResponseEntity<ApiResponse<T>> createValidationErrorResponse(ValidationResult validationResult) {
        List<Map<String, String>> details = validationResult.getErrors().stream()
                .map(error -> Map.of(
                        "field", error.getElementId(),
                        "code", error.getCode(),
                        "message", error.getMessage()
                ))
                .toList();
        
        ErrorResponse error = ErrorResponse.builder()
                .code("VAL_SECURITY_VIOLATION")
                .message("Input validation failed due to security concerns")
                .details(details)
                .suggestion("Please review your input and remove any potentially harmful content")
                .timestamp(Instant.now())
                .traceId(UUID.randomUUID().toString())
                .build();
        
        // Log security validation failure for monitoring
        log.warn("Security validation failed: {} errors detected", validationResult.getErrors().size());
        logSecurityEvent("VALIDATION_FAILED", "Input validation failed", 
                Map.of("errorCount", (Object) validationResult.getErrors().size()));
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(error));
    }
    
    /**
     * Creates a security error response for integration service failures
     */
    private <T> ResponseEntity<ApiResponse<T>> createSecurityErrorResponse(String message) {
        ErrorResponse error = ErrorResponse.builder()
                .code("SEC_VALIDATION_FAILED")
                .message(message)
                .suggestion("Please review your input and ensure it meets security requirements")
                .timestamp(Instant.now())
                .traceId(UUID.randomUUID().toString())
                .build();
        
        // Log security event
        logSecurityEvent("SECURITY_VALIDATION_FAILED", message, Map.of("timestamp", (Object) Instant.now()));
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(error));
    }
    
    /**
     * Creates a generic error response for unhandled exceptions
     */
    private <T> ResponseEntity<ApiResponse<T>> createGenericErrorResponse(Exception e) {
        String traceId = UUID.randomUUID().toString();
        
        ErrorResponse error = ErrorResponse.builder()
                .code("SYS_REQUEST_PROCESSING_ERROR")
                .message("Request processing failed")
                .suggestion("Please try again or contact support if the problem persists")
                .timestamp(Instant.now())
                .traceId(traceId)
                .build();
        
        // Log error with trace ID for debugging
        log.error("Request processing failed with trace ID: {}", traceId, e);
        logSecurityEvent("REQUEST_PROCESSING_ERROR", "Unhandled exception occurred", 
                Map.of("traceId", (Object) traceId, "exceptionType", (Object) e.getClass().getSimpleName()));
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(error));
    }
    
    /**
     * Exception handler for validation exceptions at the controller level
     */
    @ExceptionHandler(IllegalArgumentException.class)
    protected ResponseEntity<ApiResponse<Object>> handleValidationException(IllegalArgumentException e) {
        log.warn("Validation exception: {}", e.getMessage());
        
        ErrorResponse error = ErrorResponse.builder()
                .code("VAL_INVALID_INPUT")
                .message("Invalid input provided")
                .suggestion("Please check your input and try again")
                .timestamp(Instant.now())
                .traceId(UUID.randomUUID().toString())
                .build();
        
        logSecurityEvent("VALIDATION_EXCEPTION", e.getMessage(), 
                Map.of("exceptionType", (Object) "IllegalArgumentException"));
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(error));
    }
    
    /**
     * Exception handler for security exceptions at the controller level
     */
    @ExceptionHandler(SecurityException.class)
    protected ResponseEntity<ApiResponse<Object>> handleSecurityException(SecurityException e) {
        log.warn("Security exception: {}", e.getMessage());
        
        ErrorResponse error = ErrorResponse.builder()
                .code("SEC_ACCESS_DENIED")
                .message("Access denied due to security policy")
                .suggestion("Please ensure you have proper authorization")
                .timestamp(Instant.now())
                .traceId(UUID.randomUUID().toString())
                .build();
        
        logSecurityEvent("SECURITY_EXCEPTION", e.getMessage(), 
                Map.of("exceptionType", (Object) "SecurityException"));
        
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(error));
    }
    
    /**
     * Functional interface for request processing logic
     */
    @FunctionalInterface
    protected interface RequestProcessor<T> {
        T process() throws Exception;
    }
}