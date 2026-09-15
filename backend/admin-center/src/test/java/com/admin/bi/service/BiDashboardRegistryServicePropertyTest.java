package com.admin.bi.service;

import com.admin.bi.component.DashboardSyncComponent;
import com.admin.bi.dto.request.DashboardRegistryUpdateRequest;
import com.admin.bi.dto.response.DashboardRegistryResponse;
import com.admin.bi.entity.BiDashboardRegistry;
import com.admin.bi.enums.DashboardStatus;
import com.admin.bi.repository.BiDashboardAssignmentRepository;
import com.admin.bi.repository.BiDashboardRegistryRepository;
import com.admin.bi.repository.BiDataViewAssignmentRepository;
import com.admin.bi.service.impl.BiDashboardRegistryServiceImpl;
import com.admin.exception.DashboardHasAssignmentsException;
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
 * BiDashboardRegistryService 属性测试
 *
 * Feature: bi-management
 * Property 4: Dashboard 状态手动切换往返
 * Property 5: Dashboard 列表筛选正确性
 * Property 6: 本地扩展字段更新往返
 * Property 7: Dashboard 删除与分配关联守卫
 *
 * Validates: Requirements 1.11, 1.12, 1.13, 1.14, 1.15, 1.16
 */
class BiDashboardRegistryServicePropertyTest {

    private BiDashboardRegistryRepository registryRepository;
    private BiDashboardAssignmentRepository assignmentRepository;
    private BiDataViewAssignmentRepository dataViewAssignmentRepository;
    private DashboardSyncComponent dashboardSyncComponent;
    private BiDashboardRegistryServiceImpl service;

    @BeforeTry
    void setUp() {
        registryRepository = mock(BiDashboardRegistryRepository.class);
        assignmentRepository = mock(BiDashboardAssignmentRepository.class);
        dataViewAssignmentRepository = mock(BiDataViewAssignmentRepository.class);
        dashboardSyncComponent = mock(DashboardSyncComponent.class);
        service = new BiDashboardRegistryServiceImpl(registryRepository, assignmentRepository, dashboardSyncComponent);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "dataViewAssignmentRepository", dataViewAssignmentRepository);
    }

    // ========== Arbitraries ==========

    @Provide
    Arbitrary<BiDashboardRegistry> activeDashboard() {
        return dashboardWithStatus(DashboardStatus.ACTIVE);
    }

    @Provide
    Arbitrary<BiDashboardRegistry> anyDashboard() {
        return Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20),
                Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(50),
                Arbitraries.of(DashboardStatus.ACTIVE, DashboardStatus.AUTO_INACTIVE, DashboardStatus.MANUAL_INACTIVE),
                Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(30).injectNull(0.3),
                Arbitraries.of(true, false)
        ).as((title, desc, status, tags, isDefault) -> {
            String id = UUID.randomUUID().toString();
            return BiDashboardRegistry.builder()
                    .id(id)
                    .dashboardTitle(title)
                    .description(desc)
                    .embedId(UUID.randomUUID())
                    .supersetDashboardUuid(UUID.randomUUID())
                    .supersetDashboardId(new Random().nextInt(10000) + 1)
                    .tags(tags)
                    .isDefaultLanding(isDefault)
                    .status(status)
                    .lastSyncedAt(LocalDateTime.now().minusHours(1))
                    .createdAt(LocalDateTime.now().minusDays(1))
                    .updatedAt(LocalDateTime.now().minusHours(1))
                    .build();
        });
    }

    private Arbitrary<BiDashboardRegistry> dashboardWithStatus(DashboardStatus status) {
        return Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20),
                Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(50),
                Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(30).injectNull(0.3),
                Arbitraries.of(true, false)
        ).as((title, desc, tags, isDefault) -> {
            String id = UUID.randomUUID().toString();
            return BiDashboardRegistry.builder()
                    .id(id)
                    .dashboardTitle(title)
                    .description(desc)
                    .embedId(UUID.randomUUID())
                    .supersetDashboardUuid(UUID.randomUUID())
                    .supersetDashboardId(new Random().nextInt(10000) + 1)
                    .tags(tags)
                    .isDefaultLanding(isDefault)
                    .status(status)
                    .lastSyncedAt(LocalDateTime.now().minusHours(1))
                    .createdAt(LocalDateTime.now().minusDays(1))
                    .updatedAt(LocalDateTime.now().minusHours(1))
                    .build();
        });
    }

    @Provide
    Arbitrary<List<BiDashboardRegistry>> dashboardList() {
        return anyDashboard().list().ofMinSize(1).ofMaxSize(10);
    }

    @Provide
    Arbitrary<String> optionalTags() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30).injectNull(0.2);
    }

    @Provide
    Arbitrary<Boolean> optionalBoolean() {
        return Arbitraries.of(true, false);
    }

    // ========== Property 4: Dashboard 状态手动切换往返 ==========

    /**
     * Property 4: Dashboard 状态手动切换往返
     *
     * For any ACTIVE dashboard, disable → status becomes MANUAL_INACTIVE,
     * then enable → status restores to ACTIVE.
     * i.e. enable(disable(dashboard)).status == ACTIVE
     *
     * Feature: bi-management, Property 4: Dashboard status manual toggle round-trip
     * Validates: Requirements 1.11, 1.12
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management, Property 4: Dashboard status manual toggle round-trip")
    void dashboardStatusManualToggleRoundTrip(
            @ForAll("activeDashboard") BiDashboardRegistry dashboard
    ) {
        String id = dashboard.getId();

        // Mock findById to return the entity
        when(registryRepository.findById(id)).thenReturn(Optional.of(dashboard));
        // Mock save to return the entity as-is (entity is mutated in-place by service)
        when(registryRepository.save(any(BiDashboardRegistry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Verify initial state is ACTIVE
        assertThat(dashboard.getStatus()).isEqualTo(DashboardStatus.ACTIVE);

        // Step 1: Disable → should become MANUAL_INACTIVE
        DashboardRegistryResponse disabledResponse = service.disableDashboard(id);
        assertThat(disabledResponse.getStatus()).isEqualTo(DashboardStatus.MANUAL_INACTIVE);
        assertThat(dashboard.getStatus()).isEqualTo(DashboardStatus.MANUAL_INACTIVE);

        // Step 2: Enable → should restore to ACTIVE
        DashboardRegistryResponse enabledResponse = service.enableDashboard(id);
        assertThat(enabledResponse.getStatus()).isEqualTo(DashboardStatus.ACTIVE);
        assertThat(dashboard.getStatus()).isEqualTo(DashboardStatus.ACTIVE);
    }

    // ========== Property 5: Dashboard 列表筛选正确性 ==========

    /**
     * Property 5: Dashboard 列表筛选正确性
     *
     * For any dashboard dataset and any filter criteria combination (title fuzzy,
     * tags fuzzy, status exact), every returned record satisfies all specified
     * filter criteria, and no matching record is missing from the result.
     *
     * Feature: bi-management, Property 5: Dashboard list filter correctness
     * Validates: Requirements 1.13
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management, Property 5: Dashboard list filter correctness")
    void dashboardListFilterCorrectness(
            @ForAll("dashboardList") List<BiDashboardRegistry> allDashboards
    ) {
        // Pick random filter criteria from the dataset to ensure some matches
        String filterTitle = null;
        String filterTags = null;
        DashboardStatus filterStatus = null;

        Random rng = new Random();

        // Randomly decide which filters to apply
        if (rng.nextBoolean() && !allDashboards.isEmpty()) {
            BiDashboardRegistry sample = allDashboards.get(rng.nextInt(allDashboards.size()));
            String title = sample.getDashboardTitle();
            if (title != null && title.length() > 1) {
                filterTitle = title.substring(0, Math.min(3, title.length()));
            }
        }
        if (rng.nextBoolean() && !allDashboards.isEmpty()) {
            BiDashboardRegistry sample = allDashboards.get(rng.nextInt(allDashboards.size()));
            filterTags = sample.getTags();
        }
        if (rng.nextBoolean()) {
            DashboardStatus[] statuses = DashboardStatus.values();
            filterStatus = statuses[rng.nextInt(statuses.length)];
        }

        // Compute expected results by applying filters manually
        final String fTitle = filterTitle;
        final String fTags = filterTags;
        final DashboardStatus fStatus = filterStatus;

        List<BiDashboardRegistry> expectedMatches = allDashboards.stream()
                .filter(d -> fTitle == null || d.getDashboardTitle().toLowerCase().contains(fTitle.toLowerCase()))
                .filter(d -> fTags == null || (d.getTags() != null && d.getTags().toLowerCase().contains(fTags.toLowerCase())))
                .filter(d -> fStatus == null || d.getStatus() == fStatus)
                .collect(Collectors.toList());

        // Mock repository to return the expected matches as a Page
        Pageable pageable = PageRequest.of(0, 100);
        Page<BiDashboardRegistry> mockPage = new PageImpl<>(expectedMatches, pageable, expectedMatches.size());
        when(registryRepository.findByFilters(eq(fTitle), eq(fTags), eq(fStatus), any(Pageable.class)))
                .thenReturn(mockPage);

        // Call service
        Page<DashboardRegistryResponse> result = service.listDashboards(fTitle, fTags, fStatus, pageable);

        // Verify completeness: result count matches expected
        assertThat(result.getTotalElements()).isEqualTo(expectedMatches.size());

        // Verify accuracy: every returned record satisfies all filter criteria
        for (DashboardRegistryResponse resp : result.getContent()) {
            if (fTitle != null) {
                assertThat(resp.getDashboardTitle().toLowerCase())
                        .contains(fTitle.toLowerCase());
            }
            if (fTags != null) {
                assertThat(resp.getTags()).isNotNull();
                assertThat(resp.getTags().toLowerCase())
                        .contains(fTags.toLowerCase());
            }
            if (fStatus != null) {
                assertThat(resp.getStatus()).isEqualTo(fStatus);
            }
        }

        // Verify no matching results are missing
        Set<String> returnedIds = result.getContent().stream()
                .map(DashboardRegistryResponse::getId)
                .collect(Collectors.toSet());
        for (BiDashboardRegistry expected : expectedMatches) {
            assertThat(returnedIds).contains(expected.getId());
        }
    }

    // ========== Property 6: 本地扩展字段更新往返 ==========

    /**
     * Property 6: 本地扩展字段更新往返
     *
     * For any synced dashboard and any valid tags/isDefaultLanding values,
     * after update the tags and isDefaultLanding reflect the new values,
     * while other fields (dashboardTitle, description, embedId, etc.) remain unchanged.
     *
     * Feature: bi-management, Property 6: Local extension field update round-trip
     * Validates: Requirements 1.14
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management, Property 6: Local extension field update round-trip")
    void localExtensionFieldUpdateRoundTrip(
            @ForAll("anyDashboard") BiDashboardRegistry dashboard,
            @ForAll("optionalTags") String newTags,
            @ForAll("optionalBoolean") Boolean newIsDefaultLanding
    ) {
        String id = dashboard.getId();

        // Snapshot immutable fields before update
        String originalTitle = dashboard.getDashboardTitle();
        String originalDescription = dashboard.getDescription();
        UUID originalEmbedId = dashboard.getEmbedId();
        UUID originalSupersetUuid = dashboard.getSupersetDashboardUuid();
        Integer originalSupersetId = dashboard.getSupersetDashboardId();
        DashboardStatus originalStatus = dashboard.getStatus();
        LocalDateTime originalLastSyncedAt = dashboard.getLastSyncedAt();

        // Mock repository
        when(registryRepository.findById(id)).thenReturn(Optional.of(dashboard));
        when(registryRepository.save(any(BiDashboardRegistry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Build update request
        DashboardRegistryUpdateRequest request = new DashboardRegistryUpdateRequest();
        request.setTags(newTags);
        request.setIsDefaultLanding(newIsDefaultLanding);

        // Execute update
        DashboardRegistryResponse response = service.updateDashboard(id, request);

        // Verify tags and isDefaultLanding are updated
        if (newTags != null) {
            assertThat(response.getTags()).isEqualTo(newTags);
        }
        if (newIsDefaultLanding != null) {
            assertThat(response.getIsDefaultLanding()).isEqualTo(newIsDefaultLanding);
        }

        // Verify other fields remain unchanged
        assertThat(response.getDashboardTitle()).isEqualTo(originalTitle);
        assertThat(response.getDescription()).isEqualTo(originalDescription);
        assertThat(response.getEmbedId()).isEqualTo(originalEmbedId);
        assertThat(response.getSupersetDashboardUuid()).isEqualTo(originalSupersetUuid);
        assertThat(response.getSupersetDashboardId()).isEqualTo(originalSupersetId);
        assertThat(response.getStatus()).isEqualTo(originalStatus);
        assertThat(response.getLastSyncedAt()).isEqualTo(originalLastSyncedAt);
    }

    // ========== Property 7: Dashboard 删除与分配关联守卫 ==========

    @Example
    void dataViewAssignmentAlsoPreventsDashboardDeletion() {
        BiDashboardRegistry dashboard = BiDashboardRegistry.builder()
                .id("dashboard-bound-to-table")
                .dashboardTitle("Bound dashboard")
                .embedId(UUID.randomUUID())
                .status(DashboardStatus.ACTIVE)
                .build();
        when(registryRepository.findById(dashboard.getId())).thenReturn(Optional.of(dashboard));
        when(assignmentRepository.countByDashboardId(dashboard.getId())).thenReturn(0L);
        when(dataViewAssignmentRepository.countByDashboardId(dashboard.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteDashboard(dashboard.getId()))
                .isInstanceOf(DashboardHasAssignmentsException.class);
        verify(registryRepository, never()).delete(any(BiDashboardRegistry.class));
    }

    /**
     * Property 7: Dashboard 删除与分配关联守卫
     *
     * For any dashboard:
     * - When assignmentCount > 0: deleteDashboard throws DashboardHasAssignmentsException
     *   and the dashboard record remains unchanged
     * - When assignmentCount == 0: deleteDashboard succeeds and repository.delete is called
     *
     * Feature: bi-management, Property 7: Dashboard delete with assignment guard
     * Validates: Requirements 1.15, 1.16
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management, Property 7: Dashboard delete with assignment guard")
    void dashboardDeleteWithAssignmentGuard(
            @ForAll("anyDashboard") BiDashboardRegistry dashboard,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 10) int assignmentCount
    ) {
        String id = dashboard.getId();

        // Mock repository
        when(registryRepository.findById(id)).thenReturn(Optional.of(dashboard));
        when(assignmentRepository.countByDashboardId(id)).thenReturn((long) assignmentCount);

        if (assignmentCount > 0) {
            // Should throw DashboardHasAssignmentsException
            assertThatThrownBy(() -> service.deleteDashboard(id))
                    .isInstanceOf(DashboardHasAssignmentsException.class);

            // Verify delete was NOT called
            verify(registryRepository, never()).delete(any(BiDashboardRegistry.class));
        } else {
            // Should succeed
            service.deleteDashboard(id);

            // Verify delete WAS called with the correct entity
            verify(registryRepository).delete(dashboard);
        }
    }
}
