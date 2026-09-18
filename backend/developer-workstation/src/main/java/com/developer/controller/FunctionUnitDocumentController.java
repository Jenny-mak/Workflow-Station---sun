package com.developer.controller;

import com.developer.component.FunctionUnitDocumentComponent;
import com.developer.dto.FunctionUnitDocumentDTO;
import com.developer.dto.SaveFunctionUnitDocumentRequest;
import com.developer.enums.AiDocumentType;
import com.developer.exception.DeveloperBusinessException;
import com.developer.security.RequireDeveloperPermission;
import com.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 功能单元的 Requirements / Function Unit Design 文档（Function Unit Settings 里手动编辑，
 * AI Studio 确认阶段后自动同步）。不挂 {@code ai-generation.enabled}：文档是设计资料，
 * AI 关掉也要能读写。
 */
@RestController
@RequestMapping("/function-units/{functionUnitId}/documents")
@Tag(name = "Function Unit Documents", description = "Requirements and design documents of a function unit")
public class FunctionUnitDocumentController extends BaseController {

    private final FunctionUnitDocumentComponent documentComponent;

    public FunctionUnitDocumentController(FunctionUnitDocumentComponent documentComponent) {
        this.documentComponent = documentComponent;
    }

    @GetMapping
    @Operation(summary = "Latest version of each document (null when none yet)")
    @RequireDeveloperPermission("FUNCTION_UNIT_VIEW")
    public ResponseEntity<ApiResponse<Map<AiDocumentType, FunctionUnitDocumentDTO>>> current(
            @PathVariable Long functionUnitId) {
        return handleRequest(() -> documentComponent.current(functionUnitId));
    }

    @GetMapping("/{type}/versions")
    @Operation(summary = "Version history, newest first, without content")
    @RequireDeveloperPermission("FUNCTION_UNIT_VIEW")
    public ResponseEntity<ApiResponse<List<FunctionUnitDocumentDTO>>> history(
            @PathVariable Long functionUnitId, @PathVariable AiDocumentType type) {
        return handleRequest(() -> documentComponent.history(functionUnitId, type));
    }

    @GetMapping("/{type}/versions/{version}")
    @Operation(summary = "One document version with content")
    @RequireDeveloperPermission("FUNCTION_UNIT_VIEW")
    public ResponseEntity<ApiResponse<FunctionUnitDocumentDTO>> version(
            @PathVariable Long functionUnitId, @PathVariable AiDocumentType type, @PathVariable int version) {
        return handleRequest(() -> documentComponent.version(functionUnitId, type, version));
    }

    @PutMapping("/{type}")
    @Operation(summary = "Save a new version; 409 when baseVersion is no longer the latest")
    @RequireDeveloperPermission("FUNCTION_UNIT_UPDATE")
    public ResponseEntity<ApiResponse<FunctionUnitDocumentDTO>> save(
            @PathVariable Long functionUnitId, @PathVariable AiDocumentType type,
            @Valid @RequestBody SaveFunctionUnitDocumentRequest request) {
        return handleRequest(() -> {
            if (request.getContent() == null) {
                throw new DeveloperBusinessException("DOCUMENT_CONTENT_REQUIRED", "content is required");
            }
            return documentComponent.save(functionUnitId, type, request.getContent(), request.getBaseVersion());
        });
    }

    @PostMapping("/next-round")
    @Operation(summary = "Start a new design round: the next save gets the next major version")
    @RequireDeveloperPermission("FUNCTION_UNIT_UPDATE")
    public ResponseEntity<ApiResponse<Integer>> startNewRound(@PathVariable Long functionUnitId) {
        return handleRequest(() -> documentComponent.startNewRound(functionUnitId));
    }

    @PostMapping("/{type}/versions/{version}/restore")
    @Operation(summary = "Append a new version with the content of an older one")
    @RequireDeveloperPermission("FUNCTION_UNIT_UPDATE")
    public ResponseEntity<ApiResponse<FunctionUnitDocumentDTO>> restore(
            @PathVariable Long functionUnitId, @PathVariable AiDocumentType type, @PathVariable int version,
            @Valid @RequestBody SaveFunctionUnitDocumentRequest request) {
        return handleRequest(() ->
                documentComponent.restore(functionUnitId, type, version, request.getBaseVersion()));
    }
}
