package com.developer.controller;

import com.developer.component.FileTransferComponent;
import com.developer.dto.FileTransferQueryRequest;
import com.developer.dto.FileTransferResponse;
import com.developer.dto.FileTransferUpdateRequest;
import com.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * File-transfer metadata for Upload fields. Any authenticated caller may query
 * (same gate as GET /upload/files/{name}); updates require the uploader.
 */
@RestController
@RequestMapping("/upload/file-transfers")
@RequiredArgsConstructor
@Tag(name = "File Transfer", description = "Upload file description and reserved FileNet metadata")
public class FileTransferController extends BaseController {

    private final FileTransferComponent fileTransferComponent;

    @PostMapping("/query")
    @Operation(summary = "Query file transfer rows by stored names")
    public ResponseEntity<ApiResponse<List<FileTransferResponse>>> query(
            @Valid @RequestBody FileTransferQueryRequest request) {
        return handleRequest(() -> fileTransferComponent.query(request.getStoredNames()));
    }

    @PatchMapping("/{storedName}")
    @Operation(summary = "Update file description")
    public ResponseEntity<ApiResponse<FileTransferResponse>> updateDescription(
            @PathVariable String storedName,
            @Valid @RequestBody FileTransferUpdateRequest request) {
        return handleRequest(() -> fileTransferComponent.updateDescription(storedName, request.getFileDescription()));
    }
}
