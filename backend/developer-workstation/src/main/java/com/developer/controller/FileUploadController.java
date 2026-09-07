package com.developer.controller;

import com.developer.component.FileUploadComponent;
import com.developer.security.RequireDeveloperPermission;
import com.platform.common.dto.ApiResponse;
import com.platform.common.exception.ErrorResponse;
import com.developer.entity.UploadedFile;
import com.developer.exception.DeveloperBusinessException;
import com.developer.exception.ResourceNotFoundException;
import com.platform.security.util.SecurityContextUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * File upload controller.
 * Accepts any file type (max 50MB); only inline-safe types preview in the browser.
 */
@RestController
@RequestMapping("/upload")
@Slf4j
@RequiredArgsConstructor
@Tag(name = "File Upload", description = "File upload and download operations")
public class FileUploadController {

    private final FileUploadComponent fileUploadComponent;

    /**
     * Upload a single file. Any authenticated caller may upload — this endpoint backs
     * ordinary form/sub-table Attachment fields used by portal end users, not just the
     * developer workstation, so it is intentionally NOT gated by a developer permission.
     * Anonymous callers receive 401 (SecurityConfig is permitAll; this check is the gate).
     */
    @PostMapping
    @Operation(summary = "Upload file", description = "Any file type, max 50MB; login required")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            @RequestParam("file") MultipartFile file) {
        if (!SecurityContextUtils.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorResponse("UNAUTHENTICATED", "Authentication required"));
        }
        try {
            return ResponseEntity.ok(ApiResponse.success(fileUploadComponent.upload(file)));
        } catch (DeveloperBusinessException e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getErrorCode(), e.getMessage()));
        } catch (IOException e) {
            log.error("File upload failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(errorResponse("UPLOAD_FAILED", "File upload failed, please retry"));
        }
    }

    /**
     * Content types safe to render inline in the browser. Anything else (html/svg/xml/js…)
     * is served as a download with a generic content type — user uploads must never execute
     * in the platform origin (stored-XSS guard now that any file type can be uploaded).
     */
    private static final java.util.Set<String> INLINE_SAFE_CONTENT_TYPES = java.util.Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp",
            "application/pdf", "text/plain");

    /**
     * Access an uploaded file (inline preview for safe types, download otherwise).
     */
    @GetMapping("/files/{filename}")
    @Operation(summary = "Get file", description = "Access uploaded file by filename; images/PDF/plain text preview inline, other types download")
    public ResponseEntity<Resource> getFile(
            @PathVariable String filename) {

        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            return ResponseEntity.badRequest().build();
        }

        try {
            UploadedFile file = fileUploadComponent.getFile(filename);
            String rawType = file.getContentType();
            String baseType = rawType == null ? "" : rawType.split(";")[0].trim().toLowerCase();
            boolean inlineSafe = INLINE_SAFE_CONTENT_TYPES.contains(baseType);

            String contentType = inlineSafe ? rawType : "application/octet-stream";
            String disposition = (inlineSafe ? "inline" : "attachment")
                    + "; filename=\"" + filename.replace("\"", "") + "\"";

            return ResponseEntity.ok()
                    .header("Content-Type", contentType)
                    .header("Content-Disposition", disposition)
                    .header("X-Content-Type-Options", "nosniff")
                    .body(new ByteArrayResource(file.getContent()));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Delete an uploaded file.
     */
    @DeleteMapping("/files/{filename}")
    @Operation(summary = "Delete file", description = "Delete an uploaded file")
    @RequireDeveloperPermission("FUNCTION_UNIT_UPDATE")
    public ResponseEntity<ApiResponse<Void>> deleteFile(@PathVariable String filename) {
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            return ResponseEntity.badRequest().build();
        }
        try {
            fileUploadComponent.deleteFile(filename);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private <T> ApiResponse<T> errorResponse(String code, String message) {
        return ApiResponse.error(ErrorResponse.builder()
                .code(code)
                .message(message)
                .timestamp(Instant.now())
                .build());
    }
}
