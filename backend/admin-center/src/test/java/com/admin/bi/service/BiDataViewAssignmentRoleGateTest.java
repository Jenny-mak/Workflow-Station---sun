package com.admin.bi.service;

import com.admin.bi.component.DashboardRoleGate;
import com.admin.bi.config.BiProperties;
import com.admin.bi.dto.response.DataViewDashboardResponse;
import com.admin.bi.entity.BiDashboardRegistry;
import com.admin.bi.entity.BiDataViewAssignment;
import com.admin.bi.entity.BiSupersetRole;
import com.admin.bi.enums.DashboardStatus;
import com.admin.bi.enums.SupersetRoleStatus;
import com.admin.bi.repository.BiDashboardRegistryRepository;
import com.admin.bi.repository.BiDataViewAssignmentRepository;
import com.admin.bi.service.impl.BiDataViewAssignmentServiceImpl;
import com.admin.repository.RoleRepository;
import com.admin.repository.UserRoleRepository;
import com.admin.service.UserBusinessUnitService;
import com.platform.security.entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Data -> Views path (dashboards bound to a request/sub table) must apply the same
 * RBAC-mapping role gate as the landing page: a dashboard carrying Superset roles is listed and
 * guest-token-eligible only when the user's mapped Superset roles cover it.
 */
class BiDataViewAssignmentRoleGateTest {

    private static final String USER = "user-1";
    private static final long VIEW_ID = 42L;
    private static final long TABLE_ID = 7L;
    private static final List<String> SYS_ROLES = List.of("role-sys-admin");

    private BiDataViewAssignmentRepository assignmentRepository;
    private BiDashboardRegistryRepository dashboardRepository;
    private BiRbacMappingService rbacMappingService;
    private BiDataViewAssignmentServiceImpl service;
    private BiDashboardRegistry restricted;
    private BiDashboardRegistry open;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        assignmentRepository = mock(BiDataViewAssignmentRepository.class);
        dashboardRepository = mock(BiDashboardRegistryRepository.class);
        rbacMappingService = mock(BiRbacMappingService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UserRoleRepository userRoleRepository = mock(UserRoleRepository.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        UserBusinessUnitService userBusinessUnitService = mock(UserBusinessUnitService.class);

        // requireAccessibleView: the published view resolves to TABLE_ID ...
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getLong("id")).thenReturn(VIEW_ID);
        when(rs.getLong("main_table_id")).thenReturn(TABLE_ID);
        when(rs.getString("function_unit_code")).thenReturn("FU");
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any()))
                .thenAnswer(inv -> inv.<ResultSetExtractor<Object>>getArgument(1).extractData(rs));
        // ... and the user is SYS_ADMIN, so view visibility itself is granted without further SQL.
        when(userRoleRepository.findAllRoleIdsByUserId(USER)).thenReturn(SYS_ROLES);
        when(roleRepository.findAllById(SYS_ROLES)).thenReturn(List.of(
                Role.builder().id("role-sys-admin").code("SYS_ADMIN").type("ADMIN").status("ACTIVE").build()));

        restricted = dashboard("dash-alpha", "3");
        open = dashboard("dash-open", null);
        when(dashboardRepository.findById(restricted.getId())).thenReturn(Optional.of(restricted));
        when(dashboardRepository.findById(open.getId())).thenReturn(Optional.of(open));
        when(assignmentRepository.findByTableIdOrderByCreatedAtAsc(TABLE_ID)).thenReturn(List.of(
                binding(restricted.getId()), binding(open.getId())));
        when(assignmentRepository.existsByDashboardIdAndTableId(restricted.getId(), TABLE_ID)).thenReturn(true);
        when(assignmentRepository.existsByDashboardIdAndTableId(open.getId(), TABLE_ID)).thenReturn(true);

        service = new BiDataViewAssignmentServiceImpl(assignmentRepository, dashboardRepository, jdbcTemplate,
                userRoleRepository, roleRepository, userBusinessUnitService,
                new DashboardRoleGate(rbacMappingService, new BiProperties(), userRoleRepository));
    }

    @Test
    void withoutMatchingMappingOnlyUnrestrictedDashboardsAreListedOrGrantable() {
        when(rbacMappingService.getEffectiveSupersetRoles(SYS_ROLES)).thenReturn(List.of(supersetRole(4, "Gamma")));

        assertThat(service.getDashboardsForView(USER, VIEW_ID))
                .extracting(DataViewDashboardResponse::dashboardId)
                .containsExactly(open.getId());
        assertThat(service.canAccessDashboardForView(USER, restricted.getId(), VIEW_ID)).isFalse();
        assertThat(service.canAccessDashboardForView(USER, open.getId(), VIEW_ID)).isTrue();
    }

    @Test
    void intersectingMappingOpensTheRestrictedDashboard() {
        when(rbacMappingService.getEffectiveSupersetRoles(SYS_ROLES)).thenReturn(List.of(supersetRole(3, "Alpha")));

        assertThat(service.getDashboardsForView(USER, VIEW_ID))
                .extracting(DataViewDashboardResponse::dashboardId)
                .containsExactly(restricted.getId(), open.getId());
        assertThat(service.canAccessDashboardForView(USER, restricted.getId(), VIEW_ID)).isTrue();
    }

    @Test
    void supersetAdminMappingBypassesTheGate() {
        when(rbacMappingService.getEffectiveSupersetRoles(SYS_ROLES)).thenReturn(List.of(supersetRole(1, "Admin")));

        assertThat(service.canAccessDashboardForView(USER, restricted.getId(), VIEW_ID)).isTrue();
    }

    private static BiDashboardRegistry dashboard(String id, String supersetRoleIds) {
        return BiDashboardRegistry.builder()
                .id(id).dashboardTitle(id).embedId(UUID.randomUUID())
                .supersetDashboardUuid(UUID.randomUUID()).supersetDashboardId(id.hashCode())
                .supersetRoleIds(supersetRoleIds).status(DashboardStatus.ACTIVE)
                .lastSyncedAt(LocalDateTime.now()).build();
    }

    private static BiDataViewAssignment binding(String dashboardId) {
        return BiDataViewAssignment.builder()
                .id(UUID.randomUUID().toString()).dashboardId(dashboardId)
                .functionUnitId(1L).tableId(TABLE_ID).build();
    }

    private static BiSupersetRole supersetRole(int id, String name) {
        return BiSupersetRole.builder().id(id).supersetRoleId(id).name(name)
                .status(SupersetRoleStatus.ACTIVE).lastSyncedAt(LocalDateTime.now()).build();
    }
}
