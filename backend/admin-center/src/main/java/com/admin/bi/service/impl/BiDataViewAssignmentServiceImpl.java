package com.admin.bi.service.impl;

import com.admin.bi.dto.request.DataViewAssignmentRequest;
import com.admin.bi.dto.request.DataViewAssignmentBatchRequest;
import com.admin.bi.dto.response.DataViewAssignmentResponse;
import com.admin.bi.dto.response.DataViewDashboardResponse;
import com.admin.bi.dto.response.DataViewFunctionUnitOptionResponse;
import com.admin.bi.dto.response.DataViewTableOptionResponse;
import com.admin.bi.entity.BiDashboardRegistry;
import com.admin.bi.entity.BiDataViewAssignment;
import com.admin.bi.enums.DashboardStatus;
import com.admin.bi.repository.BiDashboardRegistryRepository;
import com.admin.bi.repository.BiDataViewAssignmentRepository;
import com.admin.bi.service.BiDataViewAssignmentService;
import com.admin.exception.AdminBusinessException;
import com.admin.exception.DashboardInactiveException;
import com.admin.exception.DashboardNotFoundException;
import com.admin.repository.RoleRepository;
import com.admin.repository.UserRoleRepository;
import com.admin.service.UserBusinessUnitService;
import com.platform.security.entity.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BiDataViewAssignmentServiceImpl implements BiDataViewAssignmentService {

    private static final String SYS_ADMIN_ROLE_CODE = "SYS_ADMIN";

    private final BiDataViewAssignmentRepository assignmentRepository;
    private final BiDashboardRegistryRepository dashboardRepository;
    private final JdbcTemplate jdbcTemplate;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final UserBusinessUnitService userBusinessUnitService;

    @Override
    @Transactional
    public DataViewAssignmentResponse create(DataViewAssignmentRequest request) {
        BiDashboardRegistry dashboard = requireActiveDashboard(request.getDashboardId());
        TableContext table = requireTable(request.getFunctionUnitId(), request.getTableId());
        if (assignmentRepository.existsByDashboardIdAndTableId(request.getDashboardId(), request.getTableId())) {
            throw new AdminBusinessException(
                    "DUPLICATE_DATA_VIEW_ASSIGNMENT",
                    "This dashboard is already assigned to the selected table");
        }

        BiDataViewAssignment saved = assignmentRepository.save(BiDataViewAssignment.builder()
                .id(UUID.randomUUID().toString())
                .dashboardId(request.getDashboardId())
                .functionUnitId(request.getFunctionUnitId())
                .tableId(request.getTableId())
                .build());
        return toResponse(saved, dashboard, table);
    }

    @Override
    @Transactional
    public List<DataViewAssignmentResponse> createBatch(DataViewAssignmentBatchRequest request) {
        TableContext table = requireTable(request.getFunctionUnitId(), request.getTableId());
        List<String> dashboardIds = new LinkedHashSet<>(request.getDashboardIds()).stream().toList();

        Set<String> existingDashboardIds = assignmentRepository
                .findByTableIdOrderByCreatedAtAsc(request.getTableId()).stream()
                .map(BiDataViewAssignment::getDashboardId)
                .collect(Collectors.toSet());
        List<String> duplicates = dashboardIds.stream()
                .filter(existingDashboardIds::contains)
                .toList();
        if (!duplicates.isEmpty()) {
            throw new AdminBusinessException(
                    "DUPLICATE_DATA_VIEW_ASSIGNMENT",
                    "One or more selected dashboards are already assigned to the selected table");
        }

        Map<String, BiDashboardRegistry> dashboards = dashboardIds.stream()
                .map(this::requireActiveDashboard)
                .collect(Collectors.toMap(
                        BiDashboardRegistry::getId,
                        Function.identity(),
                        (left, right) -> left));

        List<BiDataViewAssignment> assignments = dashboardIds.stream()
                .map(dashboardId -> BiDataViewAssignment.builder()
                        .id(UUID.randomUUID().toString())
                        .dashboardId(dashboardId)
                        .functionUnitId(request.getFunctionUnitId())
                        .tableId(request.getTableId())
                        .build())
                .toList();

        return assignmentRepository.saveAll(assignments).stream()
                .map(assignment -> toResponse(
                        assignment,
                        dashboards.get(assignment.getDashboardId()),
                        table))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DataViewAssignmentResponse> list(
            String dashboardTitle, Long functionUnitId, Pageable pageable) {
        String normalizedTitle = dashboardTitle == null || dashboardTitle.isBlank()
                ? null : dashboardTitle.trim();
        return assignmentRepository.findByFilters(normalizedTitle, functionUnitId, pageable)
                .map(this::toResponse);
    }

    @Override
    @Transactional
    public DataViewAssignmentResponse update(String id, DataViewAssignmentRequest request) {
        BiDataViewAssignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> new AdminBusinessException(
                        "DATA_VIEW_ASSIGNMENT_NOT_FOUND", "Data View Assignment not found: " + id));
        BiDashboardRegistry dashboard = requireActiveDashboard(request.getDashboardId());
        TableContext table = requireTable(request.getFunctionUnitId(), request.getTableId());
        if (assignmentRepository.existsByDashboardIdAndTableIdAndIdNot(
                request.getDashboardId(), request.getTableId(), id)) {
            throw new AdminBusinessException(
                    "DUPLICATE_DATA_VIEW_ASSIGNMENT",
                    "This dashboard is already assigned to the selected table");
        }

        assignment.setDashboardId(request.getDashboardId());
        assignment.setFunctionUnitId(request.getFunctionUnitId());
        assignment.setTableId(request.getTableId());
        return toResponse(assignmentRepository.save(assignment), dashboard, table);
    }

    @Override
    @Transactional
    public void delete(String id) {
        BiDataViewAssignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> new AdminBusinessException(
                        "DATA_VIEW_ASSIGNMENT_NOT_FOUND", "Data View Assignment not found: " + id));
        assignmentRepository.delete(assignment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataViewFunctionUnitOptionResponse> listFunctionUnits() {
        return jdbcTemplate.query("""
                SELECT DISTINCT fu.id, fu.code, COALESCE(NULLIF(fu.display_name, ''), fu.name) AS display_name
                FROM dw_function_units fu
                INNER JOIN dw_main_table_view_configs v ON v.function_unit_id = fu.id
                INNER JOIN dw_table_definitions td
                        ON td.id = v.main_table_id AND td.function_unit_id = fu.id
                WHERE v.status = 'PUBLISHED' AND td.table_type IN ('MAIN', 'SUB')
                ORDER BY display_name, fu.code
                """, (rs, rowNum) -> DataViewFunctionUnitOptionResponse.builder()
                .id(rs.getLong("id"))
                .code(rs.getString("code"))
                .name(rs.getString("display_name"))
                .build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataViewTableOptionResponse> listTables(Long functionUnitId) {
        if (functionUnitId == null) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT DISTINCT td.id, td.function_unit_id, td.table_name,
                       COALESCE(NULLIF(td.table_display_name, ''), td.table_name) AS table_display_name,
                       td.table_type
                FROM dw_table_definitions td
                INNER JOIN dw_main_table_view_configs v
                        ON v.main_table_id = td.id AND v.function_unit_id = td.function_unit_id
                WHERE td.function_unit_id = ? AND td.table_type IN ('MAIN', 'SUB')
                      AND v.status = 'PUBLISHED'
                ORDER BY table_display_name, td.table_name
                """, (rs, rowNum) -> DataViewTableOptionResponse.builder()
                .id(rs.getLong("id"))
                .functionUnitId(rs.getLong("function_unit_id"))
                .tableName(rs.getString("table_name"))
                .tableDisplayName(rs.getString("table_display_name"))
                .tableType(rs.getString("table_type"))
                .build(), functionUnitId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataViewDashboardResponse> getDashboardsForView(String userId, Long viewId) {
        ViewContext view = requireAccessibleView(userId, viewId);
        return assignmentRepository.findByTableIdOrderByCreatedAtAsc(view.tableId()).stream()
                .map(a -> dashboardRepository.findById(a.getDashboardId()).orElse(null))
                .filter(Objects::nonNull)
                .filter(d -> d.getStatus() == DashboardStatus.ACTIVE)
                .map(d -> DataViewDashboardResponse.builder()
                        .dashboardId(d.getId())
                        .dashboardTitle(d.getDashboardTitle())
                        .description(d.getDescription())
                        .embedId(d.getEmbedId())
                        .build())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canAccessDashboardForView(String userId, String dashboardId, Long viewId) {
        ViewContext view = requireAccessibleView(userId, viewId);
        return assignmentRepository.existsByDashboardIdAndTableId(dashboardId, view.tableId());
    }

    private BiDashboardRegistry requireActiveDashboard(String dashboardId) {
        BiDashboardRegistry dashboard = dashboardRepository.findById(dashboardId)
                .orElseThrow(() -> new DashboardNotFoundException(dashboardId));
        if (dashboard.getStatus() != DashboardStatus.ACTIVE) {
            throw new DashboardInactiveException(dashboardId);
        }
        return dashboard;
    }

    private TableContext requireTable(Long functionUnitId, Long tableId) {
        try {
            TableContext table = jdbcTemplate.query("""
                    SELECT td.id, td.function_unit_id, td.table_name,
                           COALESCE(NULLIF(td.table_display_name, ''), td.table_name) AS table_display_name,
                           td.table_type, fu.code AS function_unit_code,
                           COALESCE(NULLIF(fu.display_name, ''), fu.name) AS function_unit_name
                    FROM dw_table_definitions td
                    INNER JOIN dw_function_units fu ON fu.id = td.function_unit_id
                    WHERE td.id = ? AND td.function_unit_id = ?
                          AND td.table_type IN ('MAIN', 'SUB')
                          AND EXISTS (
                              SELECT 1
                              FROM dw_main_table_view_configs v
                              WHERE v.main_table_id = td.id
                                AND v.function_unit_id = td.function_unit_id
                                AND v.status = 'PUBLISHED'
                          )
                    """, rs -> rs.next() ? new TableContext(
                    rs.getLong("id"),
                    rs.getLong("function_unit_id"),
                    rs.getString("table_name"),
                    rs.getString("table_display_name"),
                    rs.getString("table_type"),
                    rs.getString("function_unit_code"),
                    rs.getString("function_unit_name")) : null, tableId, functionUnitId);
            if (table == null) {
                throw new AdminBusinessException(
                        "DATA_VIEW_TABLE_NOT_FOUND",
                        "The selected request/sub table does not belong to the target function unit");
            }
            return table;
        } catch (DataAccessException e) {
            throw new AdminBusinessException(
                    "DATA_VIEW_TABLE_LOOKUP_FAILED", "Unable to resolve the target table", e);
        }
    }

    private ViewContext requireAccessibleView(String userId, Long viewId) {
        if (userId == null || userId.isBlank() || viewId == null) {
            throw new AccessDeniedException("A valid user and data view are required");
        }
        ViewContext view;
        try {
            view = jdbcTemplate.query("""
                    SELECT v.id, v.main_table_id, fu.code AS function_unit_code
                    FROM dw_main_table_view_configs v
                    INNER JOIN dw_function_units fu ON fu.id = v.function_unit_id
                    INNER JOIN dw_table_definitions td
                            ON td.id = v.main_table_id AND td.function_unit_id = v.function_unit_id
                    WHERE v.id = ? AND v.status = 'PUBLISHED'
                          AND td.table_type IN ('MAIN', 'SUB')
                    """, rs -> rs.next() ? new ViewContext(
                    rs.getLong("id"), rs.getLong("main_table_id"),
                    rs.getString("function_unit_code")) : null, viewId);
        } catch (DataAccessException e) {
            throw new AccessDeniedException("Unable to validate data view access", e);
        }
        if (view == null || !canUserSeeView(userId, view)) {
            throw new AccessDeniedException("Data view is not accessible to the current user");
        }
        return view;
    }

    /** Mirrors the User Portal's Function Unit + paired BU/Role view visibility rules. */
    private boolean canUserSeeView(String userId, ViewContext view) {
        List<String> allRoleIds = userRoleRepository.findAllRoleIdsByUserId(userId);
        if (allRoleIds == null) {
            allRoleIds = List.of();
        }
        List<Role> activeRoles = allRoleIds.isEmpty() ? List.of() : roleRepository.findAllById(allRoleIds).stream()
                .filter(r -> r != null && "ACTIVE".equals(r.getStatus()))
                .toList();
        if (activeRoles.stream().anyMatch(r -> SYS_ADMIN_ROLE_CODE.equals(r.getCode()))) {
            return true;
        }

        List<Role> businessRoles = activeRoles.stream()
                .filter(r -> "BU_BOUNDED".equals(r.getType()) || "BU_UNBOUNDED".equals(r.getType()))
                .toList();
        Set<String> businessRoleIds = new HashSet<>();
        Set<String> businessRoleKeys = new HashSet<>();
        for (Role role : businessRoles) {
            businessRoleIds.add(role.getId());
            businessRoleKeys.add(role.getCode() == null || role.getCode().isBlank()
                    ? role.getId() : role.getCode());
        }

        List<String> allowedFuRoleKeys = jdbcTemplate.query("""
                SELECT COALESCE(NULLIF(r.code, ''), a.target_id) AS role_key
                FROM sys_function_unit_access a
                INNER JOIN sys_function_units fu ON fu.id = a.function_unit_id
                LEFT JOIN sys_roles r ON r.id = a.target_id
                WHERE fu.code = ? AND fu.status = 'DEPLOYED' AND fu.enabled = TRUE
                      AND fu.is_active = TRUE AND a.target_type = 'ROLE'
                """, (rs, rowNum) -> rs.getString("role_key"), view.functionUnitCode());
        if (allowedFuRoleKeys.isEmpty()
                || allowedFuRoleKeys.stream().noneMatch(businessRoleKeys::contains)) {
            return false;
        }

        List<AccessRule> rules = jdbcTemplate.query("""
                SELECT target_type, target_id
                FROM dw_main_table_view_access
                WHERE view_config_id = ?
                """, (rs, rowNum) -> new AccessRule(
                rs.getString("target_type"), rs.getString("target_id")), view.id());
        Set<String> allowedRoleIds = new HashSet<>();
        Set<String> allowedBusinessUnitIds = new HashSet<>();
        for (AccessRule rule : rules) {
            if ("ROLE".equalsIgnoreCase(rule.targetType())) {
                allowedRoleIds.add(rule.targetId());
            } else if ("BUSINESS_UNIT".equalsIgnoreCase(rule.targetType())) {
                allowedBusinessUnitIds.add(rule.targetId());
            }
        }
        if (allowedRoleIds.isEmpty() || allowedBusinessUnitIds.isEmpty()) {
            return false;
        }
        boolean roleAllowed = allowedRoleIds.stream().anyMatch(businessRoleIds::contains);
        boolean buAllowed = userBusinessUnitService.getUserBusinessUnitIds(userId).stream()
                .anyMatch(allowedBusinessUnitIds::contains);
        return roleAllowed && buAllowed;
    }

    private DataViewAssignmentResponse toResponse(BiDataViewAssignment assignment) {
        BiDashboardRegistry dashboard = dashboardRepository.findById(assignment.getDashboardId()).orElse(null);
        TableContext table = requireTable(assignment.getFunctionUnitId(), assignment.getTableId());
        return toResponse(assignment, dashboard, table);
    }

    private DataViewAssignmentResponse toResponse(
            BiDataViewAssignment assignment, BiDashboardRegistry dashboard, TableContext table) {
        return DataViewAssignmentResponse.builder()
                .id(assignment.getId())
                .dashboardId(assignment.getDashboardId())
                .dashboardTitle(dashboard != null ? dashboard.getDashboardTitle() : null)
                .functionUnitId(assignment.getFunctionUnitId())
                .functionUnitCode(table.functionUnitCode())
                .functionUnitName(table.functionUnitName())
                .tableId(assignment.getTableId())
                .tableName(table.tableName())
                .tableDisplayName(table.tableDisplayName())
                .tableType(table.tableType())
                .createdAt(assignment.getCreatedAt())
                .updatedAt(assignment.getUpdatedAt())
                .build();
    }

    private record TableContext(
            Long id,
            Long functionUnitId,
            String tableName,
            String tableDisplayName,
            String tableType,
            String functionUnitCode,
            String functionUnitName) {
    }

    private record ViewContext(Long id, Long tableId, String functionUnitCode) {
    }

    private record AccessRule(String targetType, String targetId) {
    }
}
