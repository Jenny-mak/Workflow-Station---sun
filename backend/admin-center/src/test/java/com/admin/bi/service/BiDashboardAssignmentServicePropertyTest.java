package com.admin.bi.service;

import com.admin.bi.component.DashboardRoleGate;
import com.admin.bi.config.BiProperties;
import com.admin.bi.dto.request.DashboardAssignmentCreateRequest;
import com.admin.bi.dto.response.DashboardAssignmentResponse;
import com.admin.bi.dto.response.UserDashboardResponse;
import com.admin.bi.entity.BiDashboardAssignment;
import com.admin.bi.entity.BiDashboardRegistry;
import com.admin.bi.entity.BiSupersetRole;
import com.admin.bi.enums.AssignmentTargetType;
import com.admin.bi.enums.DashboardStatus;
import com.admin.bi.enums.LayoutMode;
import com.admin.bi.repository.BiDashboardAssignmentRepository;
import com.admin.bi.repository.BiDashboardRegistryRepository;
import com.admin.bi.service.impl.BiDashboardAssignmentServiceImpl;
import com.admin.bi.enums.SupersetRoleStatus;
import com.admin.exception.AssignmentTargetNotFoundException;
import com.admin.exception.DashboardInactiveException;
import com.admin.exception.DashboardNotFoundException;
import com.admin.exception.DuplicateAssignmentException;
import com.admin.repository.BusinessUnitRepository;
import com.admin.repository.RoleRepository;
import com.admin.repository.UserRepository;
import com.admin.repository.UserRoleRepository;
import com.admin.service.UserBusinessUnitService;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BiDashboardAssignmentService 属性测试
 *
 * Feature: bi-management
 * Property 8: Assignment 创建验证
 * Property 9: 用户 Dashboard 合并去重与优先级
 * Property 10: Assignment 列表筛选正确性
 *
 * Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.8
 */
class BiDashboardAssignmentServicePropertyTest {

    private BiDashboardAssignmentRepository assignmentRepository;
    private BiDashboardRegistryRepository registryRepository;
    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private BusinessUnitRepository businessUnitRepository;
    private UserRoleRepository userRoleRepository;
    private UserBusinessUnitService userBusinessUnitService;
    private BiRbacMappingService rbacMappingService;
    private BiProperties biProperties;
    private BiDashboardAssignmentServiceImpl service;

    @BeforeTry
    void setUp() {
        assignmentRepository = mock(BiDashboardAssignmentRepository.class);
        registryRepository = mock(BiDashboardRegistryRepository.class);
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        businessUnitRepository = mock(BusinessUnitRepository.class);
        userRoleRepository = mock(UserRoleRepository.class);
        userBusinessUnitService = mock(UserBusinessUnitService.class);
        rbacMappingService = mock(BiRbacMappingService.class);
        biProperties = new BiProperties();
        service = new BiDashboardAssignmentServiceImpl(
                assignmentRepository, registryRepository,
                userRepository, roleRepository, businessUnitRepository,
                userRoleRepository, userBusinessUnitService,
                new DashboardRoleGate(rbacMappingService, biProperties, userRoleRepository));
    }

    // ========== Arbitraries ==========

    @Provide
    Arbitrary<BiDashboardRegistry> activeDashboard() {
        return dashboardWithStatus(DashboardStatus.ACTIVE);
    }

    @Provide
    Arbitrary<BiDashboardRegistry> inactiveDashboard() {
        return Arbitraries.of(DashboardStatus.AUTO_INACTIVE, DashboardStatus.MANUAL_INACTIVE)
                .flatMap(this::dashboardWithStatus);
    }

    private Arbitrary<BiDashboardRegistry> dashboardWithStatus(DashboardStatus status) {
        return Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20),
                Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(50)
        ).as((title, desc) -> BiDashboardRegistry.builder()
                .id(UUID.randomUUID().toString())
                .dashboardTitle(title)
                .description(desc)
                .embedId(UUID.randomUUID())
                .supersetDashboardUuid(UUID.randomUUID())
                .supersetDashboardId(new Random().nextInt(10000) + 1)
                .status(status)
                .lastSyncedAt(LocalDateTime.now().minusHours(1))
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusHours(1))
                .build());
    }

    @Provide
    Arbitrary<AssignmentTargetType> targetType() {
        return Arbitraries.of(AssignmentTargetType.values());
    }

    @Provide
    Arbitrary<LayoutMode> layoutMode() {
        return Arbitraries.of(LayoutMode.values());
    }

    // ========== Property 8: Assignment 创建验证 ==========

    /**
     * Property 8: Assignment 创建验证
     *
     * When Dashboard exists and is ACTIVE, Target exists, and no duplicate:
     * creation should succeed.
     *
     * Feature: bi-management, Property 8: Assignment creation validation
     * Validates: Requirements 2.1, 2.2, 2.3, 2.4
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management, Property 8: Assignment creation validation - success")
    void assignmentCreationSucceedsWhenAllConditionsMet(
            @ForAll("activeDashboard") BiDashboardRegistry dashboard,
            @ForAll("targetType") AssignmentTargetType targetType,
            @ForAll("layoutMode") LayoutMode layoutMode,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 100) int displayOrder,
            @ForAll boolean isDefault
    ) {
        String targetId = UUID.randomUUID().toString();

        // Mock: Dashboard exists and is ACTIVE
        when(registryRepository.findById(dashboard.getId())).thenReturn(Optional.of(dashboard));

        // Mock: Target exists
        mockTargetExists(targetType, targetId, true);

        // Mock: No duplicate
        when(assignmentRepository.existsByDashboardIdAndTargetTypeAndTargetId(
                dashboard.getId(), targetType, targetId)).thenReturn(false);

        // Mock: save returns the entity
        when(assignmentRepository.save(any(BiDashboardAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DashboardAssignmentCreateRequest request = new DashboardAssignmentCreateRequest();
        request.setDashboardId(dashboard.getId());
        request.setTargetType(targetType);
        request.setTargetId(targetId);
        request.setLayoutMode(layoutMode);
        request.setDisplayOrder(displayOrder);
        request.setIsDefault(isDefault);

        DashboardAssignmentResponse response = service.createAssignment(request);

        assertThat(response.getDashboardId()).isEqualTo(dashboard.getId());
        assertThat(response.getTargetType()).isEqualTo(targetType);
        assertThat(response.getTargetId()).isEqualTo(targetId);
        assertThat(response.getLayoutMode()).isEqualTo(layoutMode);
        assertThat(response.getDisplayOrder()).isEqualTo(displayOrder);
        assertThat(response.getIsDefault()).isEqualTo(isDefault);
    }

    /**
     * Property 8: Assignment creation fails when Dashboard does not exist.
     *
     * Feature: bi-management, Property 8: Assignment creation validation - dashboard not found
     * Validates: Requirements 2.2
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management, Property 8: Assignment creation validation - dashboard not found")
    void assignmentCreationFailsWhenDashboardNotFound(
            @ForAll("targetType") AssignmentTargetType targetType
    ) {
        String dashboardId = UUID.randomUUID().toString();
        when(registryRepository.findById(dashboardId)).thenReturn(Optional.empty());

        DashboardAssignmentCreateRequest request = new DashboardAssignmentCreateRequest();
        request.setDashboardId(dashboardId);
        request.setTargetType(targetType);
        request.setTargetId(UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.createAssignment(request))
                .isInstanceOf(DashboardNotFoundException.class);

        verify(assignmentRepository, never()).save(any());
    }

    /**
     * Property 8: Assignment creation fails when Dashboard is inactive.
     *
     * Feature: bi-management, Property 8: Assignment creation validation - dashboard inactive
     * Validates: Requirements 2.2
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management, Property 8: Assignment creation validation - dashboard inactive")
    void assignmentCreationFailsWhenDashboardInactive(
            @ForAll("inactiveDashboard") BiDashboardRegistry dashboard,
            @ForAll("targetType") AssignmentTargetType targetType
    ) {
        when(registryRepository.findById(dashboard.getId())).thenReturn(Optional.of(dashboard));

        DashboardAssignmentCreateRequest request = new DashboardAssignmentCreateRequest();
        request.setDashboardId(dashboard.getId());
        request.setTargetType(targetType);
        request.setTargetId(UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.createAssignment(request))
                .isInstanceOf(DashboardInactiveException.class);

        verify(assignmentRepository, never()).save(any());
    }

    /**
     * Property 8: Assignment creation fails when Target does not exist.
     *
     * Feature: bi-management, Property 8: Assignment creation validation - target not found
     * Validates: Requirements 2.3
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management, Property 8: Assignment creation validation - target not found")
    void assignmentCreationFailsWhenTargetNotFound(
            @ForAll("activeDashboard") BiDashboardRegistry dashboard,
            @ForAll("targetType") AssignmentTargetType targetType
    ) {
        String targetId = UUID.randomUUID().toString();
        when(registryRepository.findById(dashboard.getId())).thenReturn(Optional.of(dashboard));
        mockTargetExists(targetType, targetId, false);

        DashboardAssignmentCreateRequest request = new DashboardAssignmentCreateRequest();
        request.setDashboardId(dashboard.getId());
        request.setTargetType(targetType);
        request.setTargetId(targetId);

        assertThatThrownBy(() -> service.createAssignment(request))
                .isInstanceOf(AssignmentTargetNotFoundException.class);

        verify(assignmentRepository, never()).save(any());
    }

    /**
     * Property 8: Assignment creation fails when duplicate exists.
     *
     * Feature: bi-management, Property 8: Assignment creation validation - duplicate
     * Validates: Requirements 2.4
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management, Property 8: Assignment creation validation - duplicate")
    void assignmentCreationFailsWhenDuplicate(
            @ForAll("activeDashboard") BiDashboardRegistry dashboard,
            @ForAll("targetType") AssignmentTargetType targetType
    ) {
        String targetId = UUID.randomUUID().toString();
        when(registryRepository.findById(dashboard.getId())).thenReturn(Optional.of(dashboard));
        mockTargetExists(targetType, targetId, true);
        when(assignmentRepository.existsByDashboardIdAndTargetTypeAndTargetId(
                dashboard.getId(), targetType, targetId)).thenReturn(true);

        DashboardAssignmentCreateRequest request = new DashboardAssignmentCreateRequest();
        request.setDashboardId(dashboard.getId());
        request.setTargetType(targetType);
        request.setTargetId(targetId);

        assertThatThrownBy(() -> service.createAssignment(request))
                .isInstanceOf(DuplicateAssignmentException.class);

        verify(assignmentRepository, never()).save(any());
    }

    // ========== Property 9: 用户 Dashboard 合并去重与优先级 ==========

    /**
     * Property 9: 用户 Dashboard 合并去重与优先级
     *
     * Merged results only contain ACTIVE Dashboards, sorted by displayOrder,
     * dedup priority USER > ROLE > BU.
     *
     * Feature: bi-management, Property 9: User Dashboard merge dedup and priority
     * Validates: Requirements 2.5, 2.6
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management, Property 9: User Dashboard merge dedup and priority")
    void userDashboardMergeDeduplicationAndPriority(
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 5) int numDashboards,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 3) int numRoles,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 3) int numBus
    ) {
        String userId = UUID.randomUUID().toString();

        // Create dashboards (mix of ACTIVE and INACTIVE)
        List<BiDashboardRegistry> dashboards = new ArrayList<>();
        for (int i = 0; i < numDashboards; i++) {
            DashboardStatus status = (i % 3 == 0 && i > 0) ? DashboardStatus.AUTO_INACTIVE : DashboardStatus.ACTIVE;
            BiDashboardRegistry d = BiDashboardRegistry.builder()
                    .id("dash-" + i)
                    .dashboardTitle("Dashboard " + i)
                    .description("Desc " + i)
                    .embedId(UUID.randomUUID())
                    .supersetDashboardUuid(UUID.randomUUID())
                    .supersetDashboardId(i + 1)
                    .status(status)
                    .lastSyncedAt(LocalDateTime.now())
                    .build();
            dashboards.add(d);
        }

        // Create role IDs and BU IDs
        List<String> roleIds = new ArrayList<>();
        for (int i = 0; i < numRoles; i++) roleIds.add("role-" + i);
        List<String> buIds = new ArrayList<>();
        for (int i = 0; i < numBus; i++) buIds.add("bu-" + i);

        // Create assignments across dimensions with overlapping dashboards
        Random rng = new Random(42);
        List<BiDashboardAssignment> userAssignments = new ArrayList<>();
        List<BiDashboardAssignment> roleAssignments = new ArrayList<>();
        List<BiDashboardAssignment> buAssignments = new ArrayList<>();

        for (BiDashboardRegistry d : dashboards) {
            int userOrder = rng.nextInt(100);
            int roleOrder = rng.nextInt(100);
            int buOrder = rng.nextInt(100);

            // Randomly assign to USER dimension
            if (rng.nextBoolean()) {
                userAssignments.add(buildAssignment(d.getId(), AssignmentTargetType.USER, userId, userOrder));
            }
            // Randomly assign to ROLE dimension
            if (!roleIds.isEmpty() && rng.nextBoolean()) {
                String roleId = roleIds.get(rng.nextInt(roleIds.size()));
                roleAssignments.add(buildAssignment(d.getId(), AssignmentTargetType.ROLE, roleId, roleOrder));
            }
            // Randomly assign to BU dimension
            if (!buIds.isEmpty() && rng.nextBoolean()) {
                String buId = buIds.get(rng.nextInt(buIds.size()));
                buAssignments.add(buildAssignment(d.getId(), AssignmentTargetType.BUSINESS_UNIT, buId, buOrder));
            }
        }

        // Mock repositories
        when(assignmentRepository.findByTargetTypeAndTargetId(AssignmentTargetType.USER, userId))
                .thenReturn(userAssignments);
        when(userRoleRepository.findAllRoleIdsByUserId(userId)).thenReturn(roleIds);
        when(userBusinessUnitService.getUserBusinessUnitIds(userId)).thenReturn(buIds);

        if (!roleIds.isEmpty()) {
            when(assignmentRepository.findByTargetTypeAndTargetIdIn(AssignmentTargetType.ROLE, roleIds))
                    .thenReturn(roleAssignments);
        }
        if (!buIds.isEmpty()) {
            when(assignmentRepository.findByTargetTypeAndTargetIdIn(AssignmentTargetType.BUSINESS_UNIT, buIds))
                    .thenReturn(buAssignments);
        }

        for (BiDashboardRegistry d : dashboards) {
            when(registryRepository.findById(d.getId())).thenReturn(Optional.of(d));
        }

        // Execute
        List<UserDashboardResponse> result = service.getUserDashboards(userId, null);

        // Verify: only ACTIVE dashboards
        Set<String> activeDashboardIds = dashboards.stream()
                .filter(d -> d.getStatus() == DashboardStatus.ACTIVE)
                .map(BiDashboardRegistry::getId)
                .collect(Collectors.toSet());
        for (UserDashboardResponse r : result) {
            assertThat(activeDashboardIds).contains(r.getDashboardId());
        }

        // Verify: no duplicates
        Set<String> resultDashboardIds = result.stream()
                .map(UserDashboardResponse::getDashboardId)
                .collect(Collectors.toSet());
        assertThat(resultDashboardIds).hasSize(result.size());

        // Verify: sorted by displayOrder ascending
        for (int i = 1; i < result.size(); i++) {
            assertThat(result.get(i).getDisplayOrder())
                    .isGreaterThanOrEqualTo(result.get(i - 1).getDisplayOrder());
        }

        // Verify: priority USER > ROLE > BU
        // For each dashboard in result, check that the correct priority assignment was used
        Map<String, BiDashboardAssignment> userMap = userAssignments.stream()
                .collect(Collectors.toMap(BiDashboardAssignment::getDashboardId, a -> a, (a, b) -> a));
        Map<String, BiDashboardAssignment> roleMap = roleAssignments.stream()
                .collect(Collectors.toMap(BiDashboardAssignment::getDashboardId, a -> a, (a, b) -> a));
        Map<String, BiDashboardAssignment> buMap = buAssignments.stream()
                .collect(Collectors.toMap(BiDashboardAssignment::getDashboardId, a -> a, (a, b) -> a));

        for (UserDashboardResponse r : result) {
            String dashId = r.getDashboardId();
            if (userMap.containsKey(dashId)) {
                // USER priority: displayOrder should match USER assignment
                assertThat(r.getDisplayOrder()).isEqualTo(userMap.get(dashId).getDisplayOrder());
            } else if (roleMap.containsKey(dashId)) {
                // ROLE priority: displayOrder should match ROLE assignment
                assertThat(r.getDisplayOrder()).isEqualTo(roleMap.get(dashId).getDisplayOrder());
            } else if (buMap.containsKey(dashId)) {
                // BU priority: displayOrder should match BU assignment
                assertThat(r.getDisplayOrder()).isEqualTo(buMap.get(dashId).getDisplayOrder());
            }
        }
    }

    // ========== Property 10: Assignment 列表筛选正确性 ==========

    /**
     * Property 10: Assignment 列表筛选正确性
     *
     * Filter results satisfy all specified conditions.
     *
     * Feature: bi-management, Property 10: Assignment list filter correctness
     * Validates: Requirements 2.8
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management, Property 10: Assignment list filter correctness")
    void assignmentListFilterCorrectness(
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 10) int numAssignments
    ) {
        Random rng = new Random();

        // Generate assignments with associated dashboards
        List<BiDashboardAssignment> allAssignments = new ArrayList<>();
        Map<String, String> dashboardTitles = new HashMap<>();

        for (int i = 0; i < numAssignments; i++) {
            String dashId = "dash-" + i;
            String title = "Title" + (char) ('A' + (i % 5));
            AssignmentTargetType tt = AssignmentTargetType.values()[i % 3];
            dashboardTitles.put(dashId, title);

            BiDashboardAssignment a = BiDashboardAssignment.builder()
                    .id(UUID.randomUUID().toString())
                    .dashboardId(dashId)
                    .targetType(tt)
                    .targetId("target-" + i)
                    .layoutMode(LayoutMode.SINGLE)
                    .displayOrder(i)
                    .isDefault(false)
                    .build();
            allAssignments.add(a);
        }

        // Pick random filter criteria
        AssignmentTargetType filterType = rng.nextBoolean()
                ? AssignmentTargetType.values()[rng.nextInt(3)] : null;
        String filterTitle = rng.nextBoolean()
                ? "Title" + (char) ('A' + rng.nextInt(5)) : null;

        // Compute expected matches
        final AssignmentTargetType fType = filterType;
        final String fTitle = filterTitle;
        List<BiDashboardAssignment> expectedMatches = allAssignments.stream()
                .filter(a -> fType == null || a.getTargetType() == fType)
                .filter(a -> fTitle == null || dashboardTitles.get(a.getDashboardId())
                        .toLowerCase().contains(fTitle.toLowerCase()))
                .collect(Collectors.toList());

        // Mock repository
        Pageable pageable = PageRequest.of(0, 100);
        Page<BiDashboardAssignment> mockPage = new PageImpl<>(expectedMatches, pageable, expectedMatches.size());
        when(assignmentRepository.findByFilters(eq(fType), eq(fTitle), any(Pageable.class)))
                .thenReturn(mockPage);

        for (BiDashboardAssignment a : expectedMatches) {
            BiDashboardRegistry dash = BiDashboardRegistry.builder()
                    .id(a.getDashboardId())
                    .dashboardTitle(dashboardTitles.get(a.getDashboardId()))
                    .embedId(UUID.randomUUID())
                    .supersetDashboardUuid(UUID.randomUUID())
                    .supersetDashboardId(rng.nextInt(10000) + 1)
                    .status(DashboardStatus.ACTIVE)
                    .lastSyncedAt(LocalDateTime.now())
                    .build();
            when(registryRepository.findById(a.getDashboardId())).thenReturn(Optional.of(dash));
        }

        // Execute
        Page<DashboardAssignmentResponse> result = service.listAssignments(fType, fTitle, pageable);

        // Verify: result count matches expected
        assertThat(result.getTotalElements()).isEqualTo(expectedMatches.size());

        // Verify: every returned record satisfies all filter criteria
        for (DashboardAssignmentResponse resp : result.getContent()) {
            if (fType != null) {
                assertThat(resp.getTargetType()).isEqualTo(fType);
            }
            if (fTitle != null) {
                assertThat(resp.getDashboardTitle().toLowerCase())
                        .contains(fTitle.toLowerCase());
            }
        }

        // Verify: no matching results are missing
        Set<String> returnedIds = result.getContent().stream()
                .map(DashboardAssignmentResponse::getId)
                .collect(Collectors.toSet());
        for (BiDashboardAssignment expected : expectedMatches) {
            assertThat(returnedIds).contains(expected.getId());
        }
    }

    // ========== Helper Methods ==========

    private void mockTargetExists(AssignmentTargetType targetType, String targetId, boolean exists) {
        switch (targetType) {
            case USER -> when(userRepository.existsById(targetId)).thenReturn(exists);
            case ROLE -> when(roleRepository.existsById(targetId)).thenReturn(exists);
            case BUSINESS_UNIT -> when(businessUnitRepository.existsById(targetId)).thenReturn(exists);
        }
    }

    // ========== Property 18: Dashboard 角色门禁（RBAC 映射 × Superset dashboard_roles） ==========

    /**
     * Property 18: Dashboard 角色门禁
     *
     * For any assigned, ACTIVE dashboard:
     * - no Superset roles on the dashboard  → always visible (unrestricted);
     * - Superset roles present               → visible iff the user's mapped ACTIVE Superset roles
     *                                          intersect them, or include the Superset admin role.
     * The RBAC mapping is consulted only when at least one restricted dashboard is assigned.
     *
     * Feature: bi-management, Property 18: Dashboard role gate
     * Validates: Requirements 7.14, 7.15
     */
    @Property(tries = 200)
    @Tag("Feature: bi-management, Property 18: Dashboard role gate")
    void dashboardRoleGate(
            @ForAll("roleIdSets") Set<Integer> dashboardRoleIds,
            @ForAll("roleIdSets") Set<Integer> mappedRoleIds,
            @ForAll boolean mappedIncludesAdmin
    ) {
        String userId = UUID.randomUUID().toString();
        List<String> sysRoleIds = List.of("role-a", "role-b");
        String csv = dashboardRoleIds.isEmpty() ? null : dashboardRoleIds.stream()
                .sorted().map(String::valueOf).collect(Collectors.joining(","));

        BiDashboardRegistry dashboard = BiDashboardRegistry.builder()
                .id("dash-gate")
                .dashboardTitle("Gated")
                .embedId(UUID.randomUUID())
                .supersetDashboardUuid(UUID.randomUUID())
                .supersetDashboardId(1)
                .supersetRoleIds(csv)
                .status(DashboardStatus.ACTIVE)
                .lastSyncedAt(LocalDateTime.now())
                .build();

        when(assignmentRepository.findByTargetTypeAndTargetId(AssignmentTargetType.USER, userId))
                .thenReturn(List.of(buildAssignment(dashboard.getId(), AssignmentTargetType.USER, userId, 0)));
        when(userRoleRepository.findAllRoleIdsByUserId(userId)).thenReturn(sysRoleIds);
        when(userBusinessUnitService.getUserBusinessUnitIds(userId)).thenReturn(List.of());
        when(registryRepository.findById(dashboard.getId())).thenReturn(Optional.of(dashboard));

        List<BiSupersetRole> mapped = new ArrayList<>();
        for (Integer id : mappedRoleIds) {
            mapped.add(supersetRole(id, "Role" + id));
        }
        if (mappedIncludesAdmin) {
            mapped.add(supersetRole(9999, biProperties.getSuperset().getAdminRoleName()));
        }
        when(rbacMappingService.getEffectiveSupersetRoles(sysRoleIds)).thenReturn(mapped);

        List<UserDashboardResponse> result = service.getUserDashboards(userId, null);

        boolean intersects = dashboardRoleIds.stream().anyMatch(mappedRoleIds::contains);
        boolean expectedVisible = dashboardRoleIds.isEmpty() || mappedIncludesAdmin || intersects;
        assertThat(result.stream().map(UserDashboardResponse::getDashboardId).collect(Collectors.toList()))
                .as("dashboardRoles=%s mapped=%s admin=%s", dashboardRoleIds, mappedRoleIds, mappedIncludesAdmin)
                .isEqualTo(expectedVisible ? List.of(dashboard.getId()) : List.of());

        if (dashboardRoleIds.isEmpty()) {
            verify(rbacMappingService, never()).getEffectiveSupersetRoles(anyList());
        } else {
            verify(rbacMappingService).getEffectiveSupersetRoles(sysRoleIds);
        }
    }

    @Provide
    Arbitrary<Set<Integer>> roleIdSets() {
        return Arbitraries.integers().between(1, 6).set().ofMinSize(0).ofMaxSize(3);
    }

    private static BiSupersetRole supersetRole(int supersetRoleId, String name) {
        return BiSupersetRole.builder()
                .id(supersetRoleId)
                .supersetRoleId(supersetRoleId)
                .name(name)
                .status(SupersetRoleStatus.ACTIVE)
                .lastSyncedAt(LocalDateTime.now())
                .build();
    }

    private BiDashboardAssignment buildAssignment(
            String dashboardId, AssignmentTargetType targetType, String targetId, int displayOrder) {
        return BiDashboardAssignment.builder()
                .id(UUID.randomUUID().toString())
                .dashboardId(dashboardId)
                .targetType(targetType)
                .targetId(targetId)
                .layoutMode(LayoutMode.SINGLE)
                .displayOrder(displayOrder)
                .isDefault(false)
                .build();
    }
}
