package com.portal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.exception.PortalException;
import com.platform.common.dto.ApiResponse;
import com.portal.dto.MainTableViewImportResult;
import com.portal.dto.MainTableViewPortalDtos.FunctionUnitViewMenuItem;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewColumnFilter;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewDataPage;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewSummary;
import com.portal.security.CurrentUserId;
import com.portal.service.PortalMainTableViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/main-table-views")
@RequiredArgsConstructor
@Tag(name = "Main Table Views", description = "Portal runtime for FU Main Table views")
public class PortalMainTableViewController {

    private final PortalMainTableViewService portalMainTableViewService;
    private final ObjectMapper objectMapper;

    @GetMapping("/function-units")
    @Operation(summary = "List function units with published Main Table views")
    public ResponseEntity<ApiResponse<List<FunctionUnitViewMenuItem>>> listFunctionUnits(
            @CurrentUserId String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        return ResponseEntity.ok(ApiResponse.success(portalMainTableViewService.listAccessibleFunctionUnits(userId)));
    }

    @GetMapping("/function-units/{functionUnitCode}/views")
    @Operation(summary = "List published views for a function unit")
    public ResponseEntity<ApiResponse<List<MainTableViewSummary>>> listViews(
            @CurrentUserId String userId,
            @PathVariable String functionUnitCode) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        return ResponseEntity.ok(ApiResponse.success(
                portalMainTableViewService.listPublishedViews(userId, functionUnitCode)));
    }

    @GetMapping("/{viewId}/data")
    @Operation(summary = "Query view data (process instance main table rows)")
    public ResponseEntity<ApiResponse<MainTableViewDataPage>> queryData(
            @CurrentUserId String userId,
            @PathVariable Long viewId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String filters,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) String groupBy) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.ok(ApiResponse.success(MainTableViewDataPage.builder()
                    .columns(List.of()).rows(List.of()).total(0).page(page).size(size)
                    .groupCounts(List.of()).build()));
        }
        return ResponseEntity.ok(ApiResponse.success(
                portalMainTableViewService.queryViewData(
                        userId,
                        viewId,
                        page,
                        size,
                        search,
                        parseColumnFilters(filters),
                        sortField,
                        sortDirection,
                        groupBy)));
    }

    /**
     * Accepts either a JSON array of {@code {fieldName,operator,value}} or a map
     * {@code {fieldName:{operator,value}}} (portal grid runtime shape).
     */
    private List<MainTableViewColumnFilter> parseColumnFilters(String filtersJson) {
        if (filtersJson == null || filtersJson.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = objectMapper.readValue(filtersJson, Object.class);
            List<MainTableViewColumnFilter> out = new ArrayList<>();
            if (parsed instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        out.add(toColumnFilter(String.valueOf(map.get("fieldName")), map));
                    }
                }
                return out;
            }
            if (parsed instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!(e.getValue() instanceof Map<?, ?> filterMap)) {
                        continue;
                    }
                    out.add(toColumnFilter(String.valueOf(e.getKey()), filterMap));
                }
                return out;
            }
            return List.of();
        } catch (Exception ex) {
            throw new PortalException("400", "Invalid filters JSON: " + ex.getMessage());
        }
    }

    private MainTableViewColumnFilter toColumnFilter(String fieldName, Map<?, ?> filterMap) {
        Object op = filterMap.get("operator");
        Object value = filterMap.containsKey("value") ? filterMap.get("value") : null;
        return MainTableViewColumnFilter.builder()
                .fieldName(fieldName)
                .operator(op != null ? String.valueOf(op) : null)
                .value(value != null ? String.valueOf(value) : null)
                .build();
    }

    @GetMapping("/{viewId}/export")
    @Operation(summary = "Export view data as CSV")
    public ResponseEntity<byte[]> exportCsv(
            @CurrentUserId String userId,
            @PathVariable Long viewId,
            @RequestParam(defaultValue = "10000") int maxRows) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(403).build();
        }
        byte[] csv = portalMainTableViewService.exportViewCsv(userId, viewId, maxRows);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"main-table-view.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    @PostMapping(value = "/{viewId}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import view data from CSV (updates by processInstanceId, creates new rows when blank)")
    public ResponseEntity<ApiResponse<MainTableViewImportResult>> importCsv(
            @CurrentUserId String userId,
            @PathVariable Long viewId,
            @RequestParam("file") MultipartFile file) {
        if (userId == null || userId.isBlank()) {
            throw new PortalException("403", "User context required");
        }
        try {
            MainTableViewImportResult result = portalMainTableViewService.importViewCsv(
                    userId, viewId, file.getBytes());
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (IllegalArgumentException ex) {
            throw new PortalException("400", ex.getMessage());
        } catch (java.io.IOException ex) {
            throw new PortalException("400", "Failed to read uploaded file", ex);
        }
    }
}
