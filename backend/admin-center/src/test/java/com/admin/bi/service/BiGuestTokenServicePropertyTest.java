package com.admin.bi.service;

import com.admin.bi.client.SupersetApiClient;
import com.admin.bi.config.BiProperties;
import com.admin.bi.dto.request.GuestTokenRequest;
import com.admin.bi.dto.response.GuestTokenResponse;
import com.admin.bi.dto.response.UserDashboardResponse;
import com.admin.bi.entity.BiDashboardRegistry;
import com.admin.bi.enums.DashboardStatus;
import com.admin.bi.repository.BiDashboardRegistryRepository;
import com.admin.bi.service.impl.BiGuestTokenServiceImpl;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BiGuestTokenService 属性测试
 *
 * Feature: bi-management
 * Property 11: Guest Token 授权守卫（含 RBAC 角色过滤后的分配结果）
 *
 * Validates: Requirements 4.2, 7.14
 */
class BiGuestTokenServicePropertyTest {

    private BiDashboardRegistryRepository dashboardRegistryRepository;
    private BiDashboardAssignmentService assignmentService;
    private BiDataViewAssignmentService dataViewAssignmentService;
    private SupersetApiClient supersetApiClient;
    private BiProperties biProperties;
    private BiGuestTokenServiceImpl service;

    @BeforeTry
    void setUp() {
        dashboardRegistryRepository = mock(BiDashboardRegistryRepository.class);
        assignmentService = mock(BiDashboardAssignmentService.class);
        dataViewAssignmentService = mock(BiDataViewAssignmentService.class);
        supersetApiClient = mock(SupersetApiClient.class);
        biProperties = new BiProperties();
        service = new BiGuestTokenServiceImpl(
                dashboardRegistryRepository,
                assignmentService,
                supersetApiClient,
                biProperties
        );
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "dataViewAssignmentService", dataViewAssignmentService);
    }

    // ========== Arbitraries ==========

    @Example
    void dataViewGuestTokenUsesTheConcreteViewAssignment() {
        String dashboardId = "dashboard-data-view";
        String userId = "portal-user";
        long viewId = 42L;
        BiDashboardRegistry dashboard = BiDashboardRegistry.builder()
                .id(dashboardId)
                .dashboardTitle("Data View Dashboard")
                .embedId(UUID.randomUUID())
                .status(DashboardStatus.ACTIVE)
                .build();
        when(dashboardRegistryRepository.findById(dashboardId)).thenReturn(Optional.of(dashboard));
        when(dataViewAssignmentService.canAccessDashboardForView(userId, dashboardId, viewId))
                .thenReturn(true);
        when(supersetApiClient.getGuestToken(dashboard.getEmbedId().toString()))
                .thenReturn("data-view-token");

        GuestTokenRequest request = new GuestTokenRequest();
        request.setDashboardId(dashboardId);
        request.setDataViewId(viewId);

        GuestTokenResponse response = service.getGuestToken(userId, request);

        assertThat(response.getToken()).isEqualTo("data-view-token");
        verify(dataViewAssignmentService).canAccessDashboardForView(userId, dashboardId, viewId);
        verifyNoInteractions(assignmentService);
    }

    @Example
    void dataViewGuestTokenRejectsAnUnassignedDashboard() {
        String dashboardId = "dashboard-unassigned";
        String userId = "portal-user";
        long viewId = 43L;
        BiDashboardRegistry dashboard = BiDashboardRegistry.builder()
                .id(dashboardId)
                .dashboardTitle("Unassigned")
                .embedId(UUID.randomUUID())
                .status(DashboardStatus.ACTIVE)
                .build();
        when(dashboardRegistryRepository.findById(dashboardId)).thenReturn(Optional.of(dashboard));
        when(dataViewAssignmentService.canAccessDashboardForView(userId, dashboardId, viewId))
                .thenReturn(false);

        GuestTokenRequest request = new GuestTokenRequest();
        request.setDashboardId(dashboardId);
        request.setDataViewId(viewId);

        assertThatThrownBy(() -> service.getGuestToken(userId, request))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(assignmentService, supersetApiClient);
    }

    @Provide
    Arbitrary<String> dashboardIds() {
        return Arbitraries.strings().alpha().ofMinLength(8).ofMaxLength(32)
                .map(s -> "dash-" + s);
    }

    @Provide
    Arbitrary<String> userIds() {
        return Arbitraries.strings().alpha().ofMinLength(4).ofMaxLength(16)
                .map(s -> "user-" + s);
    }

    @Provide
    Arbitrary<BiDashboardRegistry> dashboardArbitrary() {
        return Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(8).ofMaxLength(32).map(s -> "dash-" + s),
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(50),
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.integers().between(1, 10000)
        ).as((id, title, embedId, supersetUuid, supersetId) ->
                BiDashboardRegistry.builder()
                        .id(id)
                        .dashboardTitle(title)
                        .description("Test dashboard")
                        .embedId(embedId)
                        .supersetDashboardUuid(supersetUuid)
                        .supersetDashboardId(supersetId)
                        .status(DashboardStatus.ACTIVE)
                        .lastSyncedAt(LocalDateTime.now().minusHours(1))
                        .createdAt(LocalDateTime.now().minusDays(1))
                        .updatedAt(LocalDateTime.now().minusHours(1))
                        .build()
        );
    }

    /**
     * Generate a list of UserDashboardResponse that does NOT contain the target dashboardId.
     */
    @Provide
    Arbitrary<List<UserDashboardResponse>> otherDashboards() {
        return Arbitraries.integers().between(0, 5).flatMap(size ->
                Arbitraries.strings().alpha().ofMinLength(8).ofMaxLength(32)
                        .map(s -> "other-" + s)
                        .list().ofSize(size)
                        .map(ids -> ids.stream()
                                .map(id -> UserDashboardResponse.builder()
                                        .dashboardId(id)
                                        .dashboardTitle("Other Dashboard")
                                        .embedId(UUID.randomUUID())
                                        .displayOrder(0)
                                        .isDefault(false)
                                        .build())
                                .collect(Collectors.toList()))
        );
    }

    // ========== Property 11: Guest Token 授权守卫 ==========

    /**
     * Property 11: Guest Token 授权守卫
     *
     * For any authenticated user and any Dashboard, if the user is NOT assigned
     * that Dashboard (directly or through Role/BU), then requesting a Guest Token
     * should throw AccessDeniedException (403 Forbidden).
     *
     * Conversely, if the user IS assigned the Dashboard, the call should succeed.
     *
     * Feature: bi-management, Property 11: Guest Token authorization guard
     * Validates: Requirements 4.2
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management")
    @Tag("Property 11: Guest Token authorization guard")
    void guestTokenAuthorizationGuard(
            @ForAll("dashboardArbitrary") BiDashboardRegistry dashboard,
            @ForAll("userIds") String userId,
            @ForAll("otherDashboards") List<UserDashboardResponse> otherDashboards
    ) {
        String dashboardId = dashboard.getId();

        // Mock: dashboard exists in registry
        when(dashboardRegistryRepository.findById(dashboardId))
                .thenReturn(Optional.of(dashboard));

        GuestTokenRequest request = new GuestTokenRequest();
        request.setDashboardId(dashboardId);

        // --- Case 1: User NOT assigned the dashboard ---
        // Ensure otherDashboards does not contain the target dashboardId
        List<UserDashboardResponse> unassignedList = otherDashboards.stream()
                .filter(d -> !dashboardId.equals(d.getDashboardId()))
                .collect(Collectors.toList());
        when(assignmentService.getUserDashboards(userId, null)).thenReturn(unassignedList);

        assertThatThrownBy(() -> service.getGuestToken(userId, request))
                .isInstanceOf(AccessDeniedException.class);

        // --- Case 2: User IS assigned the dashboard ---
        List<UserDashboardResponse> assignedList = new ArrayList<>(unassignedList);
        assignedList.add(UserDashboardResponse.builder()
                .dashboardId(dashboardId)
                .dashboardTitle(dashboard.getDashboardTitle())
                .embedId(dashboard.getEmbedId())
                .displayOrder(0)
                .isDefault(false)
                .build());
        when(assignmentService.getUserDashboards(userId, null)).thenReturn(assignedList);

        // Mock remaining dependencies for the success path
        when(supersetApiClient.getGuestToken(anyString())).thenReturn("mock-guest-token");

        GuestTokenResponse response = service.getGuestToken(userId, request);

        assertThat(response).isNotNull();
        assertThat(response.getToken()).isEqualTo("mock-guest-token");
        assertThat(response.getDashboardEmbedId()).isEqualTo(dashboard.getEmbedId().toString());
        // The guest token is scoped to the dashboard resource only; Superset's guest_token API
        // takes no role list, so the service must not try to pass one.
        verify(supersetApiClient).getGuestToken(dashboard.getEmbedId().toString());
    }

    // Property 17 (Guest Token role merge) was retired: Superset's guest_token API accepts no role
    // list, so the mapped roles were never sent. Role-based visibility is now enforced by
    // BiDashboardAssignmentService (Property 18, BiDashboardAssignmentServicePropertyTest) and
    // reaches this service through getUserDashboards(), which Property 11 above covers.
}
