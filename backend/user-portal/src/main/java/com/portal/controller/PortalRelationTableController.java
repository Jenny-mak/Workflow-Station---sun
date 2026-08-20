package com.portal.controller;

import com.platform.common.dto.RelationTableDTO;
import com.platform.common.dto.ApiResponse;
import com.portal.security.CurrentUserId;
import com.portal.dto.RelationTableDataPage;
import com.portal.dto.RelationTableQueryRequest;
import com.portal.service.PortalRelationTableService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Portal Relation Table 控制器
 * 提供只读数据查看接口
 */
@RestController
@RequestMapping("/relation-tables")
@RequiredArgsConstructor
@Tag(name = "Portal Relation Tables", description = "Portal 只读数据查看")
public class PortalRelationTableController {

    private final PortalRelationTableService service;

    @GetMapping
    @Operation(summary = "获取用户可见的表列表")
    public ResponseEntity<ApiResponse<List<RelationTableDTO>>> getVisibleTables(
            @CurrentUserId String userId) {
        try {
            List<RelationTableDTO> result = service.getVisibleTables(userId);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            // Gracefully handle any unexpected errors (e.g., table not yet created, DB connection issues)
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
    }

    @GetMapping("/{tableId}")
    @Operation(summary = "分页查询表数据（只读；兼容 GET，仅 search）")
    public ResponseEntity<ApiResponse<RelationTableDataPage>> queryTableDataGet(
            @PathVariable Long tableId,
            @CurrentUserId String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search) {
        RelationTableQueryRequest request = new RelationTableQueryRequest(
                page, size, search, List.of(), null, null);
        return ResponseEntity.ok(ApiResponse.success(service.queryTableData(tableId, userId, request)));
    }

    @PostMapping("/{tableId}/data")
    @Operation(summary = "分页查询表数据（真分页；筛选/排序推入 SQL；无分组）")
    public ResponseEntity<ApiResponse<RelationTableDataPage>> queryTableData(
            @PathVariable Long tableId,
            @CurrentUserId String userId,
            @RequestBody RelationTableQueryRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.queryTableData(tableId, userId, request)));
    }

    @GetMapping("/{tableId}/export")
    @Operation(summary = "导出 CSV")
    public ResponseEntity<byte[]> exportCsv(
            @PathVariable Long tableId,
            @CurrentUserId String userId,
            @RequestParam(defaultValue = "10000") int maxRows) {
        try {
            String csv = service.exportCsv(tableId, userId, maxRows);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=export.csv")
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body(csv.getBytes());
        } catch (Exception e) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=export.csv")
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body(new byte[0]);
        }
    }

    @GetMapping("/{tableId}/search")
    @Operation(summary = "Lookup 搜索")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> searchForLookup(
            @PathVariable Long tableId,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false) List<String> searchFields,
            @RequestParam(required = false, defaultValue = "") String displayField,
            @RequestParam(required = false, defaultValue = "") String filterConditions,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        try {
            List<Map<String, Object>> result = service.searchForLookup(tableId, keyword, searchFields, displayField, filterConditions, limit, offset);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
    }

    @GetMapping("/lookup-configs/{formId}")
    @Operation(summary = "获取表单的 Lookup 配置")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getLookupConfigs(
            @PathVariable Long formId) {
        try {
            List<Map<String, Object>> result = service.getLookupConfigs(formId);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
    }

    @GetMapping("/{tableId}/view-fields")
    @Operation(summary = "获取 Relation Table 的 View 字段配置")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getViewFields(
            @PathVariable Long tableId) {
        try {
            List<Map<String, Object>> result = service.getViewFieldsByTableId(tableId);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
    }

    @GetMapping("/{tableId}/fields")
    @Operation(summary = "获取字段定义（含类型，供编辑表单使用）")
    public ResponseEntity<ApiResponse<List<com.platform.common.dto.RelationFieldDTO>>> getFieldDefinitions(
            @PathVariable Long tableId,
            @CurrentUserId String userId) {
        return ResponseEntity.ok(ApiResponse.success(service.getFieldDefinitions(tableId, userId)));
    }

    @PostMapping("/{tableId}/primary-keys/allocate")
    @Operation(summary = "按策略分配主键值（add-row 自动生成，需要 READ_WRITE）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> allocatePrimaryKeys(
            @PathVariable Long tableId,
            @CurrentUserId String userId,
            @RequestBody Map<String, Object> request) {
        String fieldName = String.valueOf(request.get("fieldName"));
        Integer count = request.get("count") instanceof Number num ? num.intValue() : null;
        List<String> values = service.allocatePrimaryKeys(tableId, userId, fieldName, count);
        return ResponseEntity.ok(ApiResponse.success(Map.of("values", values)));
    }

    // ==================== Write operations (require READ_WRITE on the active role) ====================

    @PostMapping("/{tableId}")
    @Operation(summary = "新增数据（需要 READ_WRITE）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addData(
            @PathVariable Long tableId,
            @CurrentUserId String userId,
            @RequestBody Map<String, Object> data) {
        return ResponseEntity.ok(ApiResponse.success(service.addData(tableId, userId, data)));
    }

    @PutMapping("/{tableId}/{rowId}")
    @Operation(summary = "更新数据（需要 READ_WRITE）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateData(
            @PathVariable Long tableId,
            @PathVariable String rowId,
            @CurrentUserId String userId,
            @RequestBody Map<String, Object> data) {
        return ResponseEntity.ok(ApiResponse.success(service.updateData(tableId, userId, rowId, data)));
    }

    @PutMapping("/{tableId}/{rowId}/status")
    @Operation(summary = "切换数据状态 ACTIVE/INACTIVE（需要 READ_WRITE）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> changeStatus(
            @PathVariable Long tableId,
            @PathVariable String rowId,
            @CurrentUserId String userId,
            @RequestBody Map<String, String> request) {
        String status = request.get("status");
        return ResponseEntity.ok(ApiResponse.success(service.changeStatus(tableId, userId, rowId, status)));
    }

    @GetMapping("/{tableId}/template")
    @Operation(summary = "下载导入模板 (csv|xlsx)")
    public ResponseEntity<byte[]> downloadTemplate(
            @PathVariable Long tableId,
            @CurrentUserId String userId,
            @RequestParam(defaultValue = "csv") String format) {
        byte[] bytes = service.generateTemplate(tableId, userId, format);
        boolean xlsx = "xlsx".equalsIgnoreCase(format);
        String filename = "template." + (xlsx ? "xlsx" : "csv");
        MediaType ct = xlsx
                ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                : MediaType.parseMediaType("text/csv");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(ct)
                .body(bytes);
    }

    @PostMapping("/{tableId}/import")
    @Operation(summary = "导入数据 (csv|xlsx)（需要 READ_WRITE）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importData(
            @PathVariable Long tableId,
            @CurrentUserId String userId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(required = false) String format,
            @RequestParam(required = false, defaultValue = "false") boolean dryRun) throws java.io.IOException {
        String fmt = resolveFormat(format, file.getOriginalFilename());
        Map<String, Object> result = service.importData(tableId, userId, file.getBytes(), fmt, dryRun);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private String resolveFormat(String format, String filename) {
        if (format != null && !format.isBlank()) return format;
        if (filename != null && filename.toLowerCase().endsWith(".xlsx")) return "xlsx";
        return "csv";
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException e) {
        return ResponseEntity.status(403).body(ApiResponse.error("403", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error("400", e.getMessage()));
    }
}
