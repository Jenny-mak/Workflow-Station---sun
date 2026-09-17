package com.admin.bi.controller;

import com.admin.bi.dto.request.DataViewAssignmentRequest;
import com.admin.bi.dto.request.DataViewAssignmentBatchRequest;
import com.admin.bi.dto.response.DataViewAssignmentResponse;
import com.admin.bi.dto.response.DataViewDashboardResponse;
import com.admin.bi.dto.response.DataViewFunctionUnitOptionResponse;
import com.admin.bi.dto.response.DataViewTableOptionResponse;
import com.admin.bi.service.BiDataViewAssignmentService;
import com.platform.security.util.SecurityContextUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/bi/data-view-assignments")
@RequiredArgsConstructor
@Tag(name = "Data View Assignment Management", description = "Bind BI dashboards to User Portal data-view tables")
public class BiDataViewAssignmentController {

    private final BiDataViewAssignmentService assignmentService;

    @PostMapping
    @Operation(summary = "Create a Data View Assignment")
    public ResponseEntity<DataViewAssignmentResponse> create(
            @RequestBody @Valid DataViewAssignmentRequest request) {
        return ResponseEntity.ok(assignmentService.create(request));
    }

    @PostMapping("/batch")
    @Operation(summary = "Assign several dashboards to one Data View table")
    public ResponseEntity<List<DataViewAssignmentResponse>> createBatch(
            @RequestBody @Valid DataViewAssignmentBatchRequest request) {
        return ResponseEntity.ok(assignmentService.createBatch(request));
    }

    @GetMapping
    @Operation(summary = "List Data View Assignments")
    public ResponseEntity<Page<DataViewAssignmentResponse>> list(
            @RequestParam(required = false) String dashboardTitle,
            @RequestParam(required = false) Long functionUnitId,
            Pageable pageable) {
        return ResponseEntity.ok(assignmentService.list(dashboardTitle, functionUnitId, pageable));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a Data View Assignment")
    public ResponseEntity<DataViewAssignmentResponse> update(
            @PathVariable String id,
            @RequestBody @Valid DataViewAssignmentRequest request) {
        return ResponseEntity.ok(assignmentService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a Data View Assignment")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        assignmentService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/function-units")
    @Operation(summary = "List function units that expose published Data Views")
    public ResponseEntity<List<DataViewFunctionUnitOptionResponse>> listFunctionUnits() {
        return ResponseEntity.ok(assignmentService.listFunctionUnits());
    }

    @GetMapping("/function-units/{functionUnitId}/tables")
    @Operation(summary = "List request/sub tables that expose published Data Views")
    public ResponseEntity<List<DataViewTableOptionResponse>> listTables(
            @PathVariable Long functionUnitId) {
        return ResponseEntity.ok(assignmentService.listTables(functionUnitId));
    }

    @GetMapping("/views/{viewId}/dashboards")
    @Operation(summary = "List the dashboards bound to an accessible User Portal Data View")
    public ResponseEntity<List<DataViewDashboardResponse>> getDashboardsForView(
            @PathVariable Long viewId) {
        String userId = SecurityContextUtils.getCurrentUserId()
                .orElseThrow(() -> new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                        "Unauthenticated"));
        return ResponseEntity.ok(assignmentService.getDashboardsForView(userId, viewId));
    }
}
