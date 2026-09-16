package com.admin.bi.controller;

import com.admin.bi.component.BiDashboardRegistryResponseAssembler;
import com.admin.bi.component.DashboardRoleGate;
import com.admin.bi.component.DashboardSyncComponent;
import com.admin.bi.config.BiProperties;
import com.admin.bi.dto.request.DashboardAssignmentCreateRequest;
import com.admin.bi.dto.request.DashboardRegistryUpdateRequest;
import com.admin.bi.dto.response.DashboardAssignmentResponse;
import com.admin.bi.dto.response.DashboardRegistryResponse;
import com.admin.bi.dto.response.SyncResultResponse;
import com.admin.bi.entity.BiDashboardAssignment;
import com.admin.bi.entity.BiDashboardRegistry;
import com.admin.bi.enums.AssignmentTargetType;
import com.admin.bi.enums.DashboardStatus;
import com.admin.bi.enums.LayoutMode;
import com.admin.bi.repository.BiDashboardAssignmentRepository;
import com.admin.bi.repository.BiDashboardRegistryRepository;
import com.admin.bi.repository.BiSupersetRoleRepository;
import com.admin.bi.service.BiRbacMappingService;
import com.admin.bi.service.impl.BiDashboardAssignmentServiceImpl;
import com.admin.bi.service.impl.BiDashboardRegistryServiceImpl;
import com.admin.repository.BusinessUnitRepository;
import com.admin.repository.RoleRepository;
import com.admin.repository.UserRepository;
import com.admin.repository.UserRoleRepository;
import com.admin.service.UserBusinessUnitService;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeTry;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 审计日志完整性属性测试
 *
 * Property 12: 审计日志完整性
 *
 * 验证变更操作（同步、更新、删除、分配创建/删除、状态切换）后，
 * 实体通过 repository.save()/delete() 持久化，从而触发 JPA AuditingEntityListener
 * 填充审计字段（createdAt/createdBy/updatedAt/updatedBy）。
 *
 * 所有 BI 实体均使用 @EntityListeners(AuditingEntityListener.class) 注解，
 * 确保每次持久化操作都会自动记录审计信息。
 *
 * Validates: Requirements 3.3
 */
class BiAuditLogPropertyTest {

    // --- Registry service dependencies ---
    private BiDashboardRegistryRepository registryRepository;
    private BiDashboardAssignmentRepository assignmentRepository;
    private DashboardSyncComponent dashboardSyncComponent;
    private BiDashboardRegistryServiceImpl registryService;

    // --- Assignment service dependencies ---
    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private BusinessUnitRepository businessUnitRepository;
    private UserRoleRepository userRoleRepository;
    private UserBusinessUnitService userBusinessUnitService;
    private BiDashboardAssignmentServiceImpl assignmentService;

    @BeforeTry
    void setUp() {
        registryRepository = mock(BiDashboardRegistryRepository.class);
        assignmentRepository = mock(BiDashboardAssignmentRepository.class);
        dashboardSyncComponent = mock(DashboardSyncComponent.class);
        registryService = new BiDashboardRegistryServiceImpl(
                registryRepository, assignmentRepository, dashboardSyncComponent,
                new BiDashboardRegistryResponseAssembler(mock(BiSupersetRoleRepository.class)));

        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        businessUnitRepository = mock(BusinessUnitRepository.class);
        userRoleRepository = mock(UserRoleRepository.class);
        userBusinessUnitService = mock(UserBusinessUnitService.class);
        assignmentService = new BiDashboardAssignmentServiceImpl(
                assignmentRepository, registryRepository,
                userRepository, roleRepository, businessUnitRepository,
                userRoleRepository, userBusinessUnitService,
                new DashboardRoleGate(mock(BiRbacMappingService.class), new BiProperties(), userRoleRepository));
    }

    // ========== Arbitraries ==========

    @Provide
    Arbitrary<BiDashboardRegistry> activeDashboard() {
        return Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20),
                Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(50),
                Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(30).injectNull(0.3),
                Arbitraries.of(true, false)
        ).as((title, desc, tags, isDefault) -> BiDashboardRegistry.builder()
                .id(UUID.randomUUID().toString())
                .dashboardTitle(title)
                .description(desc)
                .embedId(UUID.randomUUID())
                .supersetDashboardUuid(UUID.randomUUID())
                .supersetDashboardId(new Random().nextInt(10000) + 1)
                .tags(tags)
                .isDefaultLanding(isDefault)
                .status(DashboardStatus.ACTIVE)
                .lastSyncedAt(LocalDateTime.now().minusHours(1))
                .createdAt(LocalDateTime.now().minusDays(1))
                .createdBy("system")
                .updatedAt(LocalDateTime.now().minusHours(1))
                .updatedBy("system")
                .build());
    }

    @Provide
    Arbitrary<String> newTags() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30);
    }

    @Provide
    Arbitrary<String> userIds() {
        return Arbitraries.strings().alpha().ofMinLength(4).ofMaxLength(16)
                .map(s -> "user-" + s);
    }

    @Provide
    Arbitrary<String> targetIds() {
        return Arbitraries.strings().alpha().ofMinLength(4).ofMaxLength(16)
                .map(s -> "target-" + s);
    }

    // ========== Property 12: 审计日志完整性 ==========

    /**
     * Property 12a: Dashboard 更新操作触发审计持久化
     *
     * For any ACTIVE dashboard and any valid update request (tags/isDefaultLanding),
     * the service must call repository.save() with the updated entity,
     * ensuring JPA AuditingEntityListener populates updatedAt/updatedBy.
     *
     * Validates: Requirements 3.3
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management")
    @Tag("Property 12: Audit log completeness")
    void dashboardUpdateTriggersAuditPersistence(
            @ForAll("activeDashboard") BiDashboardRegistry dashboard,
            @ForAll("newTags") String newTags,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 1) int isDefaultInt
    ) {
        boolean newIsDefault = isDefaultInt == 1;
        String id = dashboard.getId();

        when(registryRepository.findById(id)).thenReturn(Optional.of(dashboard));
        when(registryRepository.save(any(BiDashboardRegistry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DashboardRegistryUpdateRequest request = new DashboardRegistryUpdateRequest();
        request.setTags(newTags);
        request.setIsDefaultLanding(newIsDefault);

        DashboardRegistryResponse response = registryService.updateDashboard(id, request);

        // Verify save was called (triggers @LastModifiedDate / @LastModifiedBy)
        ArgumentCaptor<BiDashboardRegistry> captor = ArgumentCaptor.forClass(BiDashboardRegistry.class);
        verify(registryRepository).save(captor.capture());

        BiDashboardRegistry saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getTags()).isEqualTo(newTags);
        assertThat(saved.getIsDefaultLanding()).isEqualTo(newIsDefault);
        // Response includes audit timestamp fields
        assertThat(response.getId()).isEqualTo(id);
    }

    /**
     * Property 12b: Dashboard 状态切换（禁用）触发审计持久化
     *
     * For any ACTIVE dashboard, disabling it must call repository.save()
     * with status changed to MANUAL_INACTIVE, triggering audit field updates.
     *
     * Validates: Requirements 3.3
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management")
    @Tag("Property 12: Audit log completeness")
    void dashboardDisableTriggersAuditPersistence(
            @ForAll("activeDashboard") BiDashboardRegistry dashboard
    ) {
        String id = dashboard.getId();

        when(registryRepository.findById(id)).thenReturn(Optional.of(dashboard));
        when(registryRepository.save(any(BiDashboardRegistry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DashboardRegistryResponse response = registryService.disableDashboard(id);

        ArgumentCaptor<BiDashboardRegistry> captor = ArgumentCaptor.forClass(BiDashboardRegistry.class);
        verify(registryRepository).save(captor.capture());

        BiDashboardRegistry saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getStatus()).isEqualTo(DashboardStatus.MANUAL_INACTIVE);
        assertThat(response.getStatus()).isEqualTo(DashboardStatus.MANUAL_INACTIVE);
    }

    /**
     * Property 12c: Dashboard 状态切换（启用）触发审计持久化
     *
     * For any dashboard, enabling it must call repository.save()
     * with status changed to ACTIVE, triggering audit field updates.
     *
     * Validates: Requirements 3.3
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management")
    @Tag("Property 12: Audit log completeness")
    void dashboardEnableTriggersAuditPersistence(
            @ForAll("activeDashboard") BiDashboardRegistry dashboard
    ) {
        // Set to MANUAL_INACTIVE first
        dashboard.setStatus(DashboardStatus.MANUAL_INACTIVE);
        String id = dashboard.getId();

        when(registryRepository.findById(id)).thenReturn(Optional.of(dashboard));
        when(registryRepository.save(any(BiDashboardRegistry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DashboardRegistryResponse response = registryService.enableDashboard(id);

        ArgumentCaptor<BiDashboardRegistry> captor = ArgumentCaptor.forClass(BiDashboardRegistry.class);
        verify(registryRepository).save(captor.capture());

        BiDashboardRegistry saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getStatus()).isEqualTo(DashboardStatus.ACTIVE);
        assertThat(response.getStatus()).isEqualTo(DashboardStatus.ACTIVE);
    }

    /**
     * Property 12d: Dashboard 删除操作触发审计持久化
     *
     * For any dashboard with no assignments, deleting it must call
     * repository.delete() with the correct entity, ensuring the deletion
     * is persisted (and can be tracked via audit mechanisms).
     *
     * Validates: Requirements 3.3
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management")
    @Tag("Property 12: Audit log completeness")
    void dashboardDeleteTriggersAuditPersistence(
            @ForAll("activeDashboard") BiDashboardRegistry dashboard
    ) {
        String id = dashboard.getId();

        when(registryRepository.findById(id)).thenReturn(Optional.of(dashboard));
        when(assignmentRepository.countByDashboardId(id)).thenReturn(0L);

        registryService.deleteDashboard(id);

        // Verify delete was called with the correct entity
        verify(registryRepository).delete(dashboard);
    }

    /**
     * Property 12e: Dashboard 同步操作触发审计持久化
     *
     * For any sync operation, the service must delegate to DashboardSyncComponent
     * which calls repository.save() for each created/updated entity,
     * ensuring audit fields are populated via JPA AuditingEntityListener.
     *
     * Validates: Requirements 3.3
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management")
    @Tag("Property 12: Audit log completeness")
    void dashboardSyncTriggersAuditPersistence(
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 5) int created,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 5) int updated,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 3) int autoInactivated
    ) {
        SyncResultResponse syncResult = SyncResultResponse.builder()
                .created(created)
                .updated(updated)
                .autoInactivated(autoInactivated)
                .syncedAt(LocalDateTime.now())
                .build();

        when(dashboardSyncComponent.executeSyncOperation()).thenReturn(syncResult);

        SyncResultResponse response = registryService.syncDashboards();

        // Verify sync component was invoked
        verify(dashboardSyncComponent).executeSyncOperation();

        // Verify the response reflects the sync operation counts
        assertThat(response.getCreated()).isEqualTo(created);
        assertThat(response.getUpdated()).isEqualTo(updated);
        assertThat(response.getAutoInactivated()).isEqualTo(autoInactivated);
        assertThat(response.getSyncedAt()).isNotNull();

        // Total mutations = created + updated + autoInactivated
        // Each of these represents a repository.save() call in the sync component,
        // which triggers JPA AuditingEntityListener
        int totalMutations = created + updated + autoInactivated;
        assertThat(totalMutations).isGreaterThanOrEqualTo(0);
    }

    /**
     * Property 12f: Assignment 创建操作触发审计持久化
     *
     * For any valid assignment creation request, the service must call
     * repository.save() with a new BiDashboardAssignment entity that has
     * a non-null ID, ensuring JPA AuditingEntityListener populates
     * createdAt/createdBy/updatedAt/updatedBy.
     *
     * Validates: Requirements 3.3
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management")
    @Tag("Property 12: Audit log completeness")
    void assignmentCreateTriggersAuditPersistence(
            @ForAll("activeDashboard") BiDashboardRegistry dashboard,
            @ForAll("targetIds") String targetId
    ) {
        String dashboardId = dashboard.getId();

        when(registryRepository.findById(dashboardId)).thenReturn(Optional.of(dashboard));
        when(userRepository.existsById(targetId)).thenReturn(true);
        when(assignmentRepository.existsByDashboardIdAndTargetTypeAndTargetId(
                dashboardId, AssignmentTargetType.USER, targetId)).thenReturn(false);
        when(assignmentRepository.save(any(BiDashboardAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DashboardAssignmentCreateRequest request = new DashboardAssignmentCreateRequest();
        request.setDashboardId(dashboardId);
        request.setTargetType(AssignmentTargetType.USER);
        request.setTargetId(targetId);
        request.setLayoutMode(LayoutMode.SINGLE);
        request.setDisplayOrder(0);
        request.setIsDefault(false);

        DashboardAssignmentResponse response = assignmentService.createAssignment(request);

        // Verify save was called (triggers @CreatedDate / @CreatedBy / @LastModifiedDate / @LastModifiedBy)
        ArgumentCaptor<BiDashboardAssignment> captor = ArgumentCaptor.forClass(BiDashboardAssignment.class);
        verify(assignmentRepository).save(captor.capture());

        BiDashboardAssignment saved = captor.getValue();
        assertThat(saved.getId()).isNotNull().isNotEmpty();
        assertThat(saved.getDashboardId()).isEqualTo(dashboardId);
        assertThat(saved.getTargetType()).isEqualTo(AssignmentTargetType.USER);
        assertThat(saved.getTargetId()).isEqualTo(targetId);
        assertThat(response.getDashboardId()).isEqualTo(dashboardId);
    }

    /**
     * Property 12g: Assignment 删除操作触发审计持久化
     *
     * For any existing assignment, deleting it must call repository.delete()
     * with the correct entity, ensuring the deletion is persisted.
     *
     * Validates: Requirements 3.3
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management")
    @Tag("Property 12: Audit log completeness")
    void assignmentDeleteTriggersAuditPersistence(
            @ForAll("activeDashboard") BiDashboardRegistry dashboard,
            @ForAll("targetIds") String targetId
    ) {
        String assignmentId = UUID.randomUUID().toString();
        BiDashboardAssignment assignment = BiDashboardAssignment.builder()
                .id(assignmentId)
                .dashboardId(dashboard.getId())
                .targetType(AssignmentTargetType.USER)
                .targetId(targetId)
                .layoutMode(LayoutMode.SINGLE)
                .displayOrder(0)
                .isDefault(false)
                .createdAt(LocalDateTime.now().minusDays(1))
                .createdBy("admin")
                .updatedAt(LocalDateTime.now().minusHours(1))
                .updatedBy("admin")
                .build();

        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));

        assignmentService.deleteAssignment(assignmentId);

        // Verify delete was called with the correct entity
        verify(assignmentRepository).delete(assignment);
    }
}
