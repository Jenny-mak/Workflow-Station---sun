package com.admin.bi.component;

import com.admin.bi.config.BiProperties;
import com.admin.bi.entity.BiDashboardRegistry;
import com.admin.bi.enums.DashboardStatus;
import com.admin.bi.repository.BiDashboardRegistryRepository;
import com.admin.bi.dto.response.SyncResultResponse;
import com.admin.exception.SupersetSyncException;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeTry;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Dashboard 同步正确性属性测试
 *
 * Feature: bi-management, Property 1: Dashboard sync correctness
 *
 * 使用 jqwik 验证各种 Superset 数据与本地 Registry 状态组合下的同步结果。
 *
 * Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 1.9, 1.10
 */
@Tag("Feature: bi-management, Property 1: Dashboard sync correctness")
class DashboardSyncComponentPropertyTest {

    private JdbcTemplate jdbcTemplate;
    private BiDashboardRegistryRepository registryRepository;
    private BiProperties biProperties;
    private DashboardSyncComponent syncComponent;

    /** Tracks all entities saved during sync */
    private List<BiDashboardRegistry> savedEntities;

    @BeforeTry
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        registryRepository = mock(BiDashboardRegistryRepository.class);
        biProperties = new BiProperties();
        syncComponent = new DashboardSyncComponent(jdbcTemplate, registryRepository, biProperties);
        savedEntities = new ArrayList<>();

        // Capture all save calls
        when(registryRepository.save(any(BiDashboardRegistry.class))).thenAnswer(invocation -> {
            BiDashboardRegistry entity = invocation.getArgument(0);
            savedEntities.add(deepCopy(entity));
            return entity;
        });
    }

    // ========== Arbitraries ==========

    @Provide
    Arbitrary<List<Map<String, Object>>> supersetDashboardData() {
        return supersetRow().list().ofMinSize(0).ofMaxSize(8)
                .filter(list -> {
                    // Ensure unique superset_dashboard_id values
                    Set<Integer> ids = list.stream()
                            .map(m -> (Integer) m.get("superset_dashboard_id"))
                            .collect(Collectors.toSet());
                    return ids.size() == list.size();
                });
    }

    private Arbitrary<Map<String, Object>> supersetRow() {
        return Combinators.combine(
                Arbitraries.integers().between(1, 500),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30),
                Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(50).injectNull(0.3),
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.create(UUID::randomUUID)
        ).as((id, title, desc, dashUuid, embedUuid) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("superset_dashboard_id", id);
            row.put("dashboard_title", title);
            row.put("description", desc);
            row.put("superset_dashboard_uuid", dashUuid);
            row.put("embed_id", embedUuid);
            return row;
        });
    }

    @Provide
    Arbitrary<List<BiDashboardRegistry>> existingRegistryRecords() {
        return registryRecord().list().ofMinSize(0).ofMaxSize(8)
                .filter(list -> {
                    Set<Integer> ids = list.stream()
                            .map(BiDashboardRegistry::getSupersetDashboardId)
                            .collect(Collectors.toSet());
                    return ids.size() == list.size();
                });
    }

    private Arbitrary<BiDashboardRegistry> registryRecord() {
        return Combinators.combine(
                Arbitraries.integers().between(1, 500),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30),
                Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(50).injectNull(0.3),
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.of(DashboardStatus.ACTIVE, DashboardStatus.AUTO_INACTIVE, DashboardStatus.MANUAL_INACTIVE),
                Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(20).injectNull(0.5),
                Arbitraries.of(true, false)
        ).as((supersetId, title, desc, dashUuid, embedId, status, tags, isDefault) ->
                BiDashboardRegistry.builder()
                        .id(UUID.randomUUID().toString())
                        .supersetDashboardId(supersetId)
                        .dashboardTitle(title)
                        .description(desc)
                        .supersetDashboardUuid(dashUuid)
                        .embedId(embedId)
                        .status(status)
                        .tags(tags)
                        .isDefaultLanding(isDefault)
                        .lastSyncedAt(LocalDateTime.now().minusDays(1))
                        .build()
        );
    }

    // ========== Property Test ==========

    /**
     * Property 1: Dashboard 同步正确性
     *
     * For any combination of Superset dashboard data and existing local registry records,
     * after executing sync:
     * 1. Only published=true dashboards with embedded_dashboards records are synced
     * 2. New dashboards (not in local registry) are created as ACTIVE with all required fields
     * 3. Existing dashboards with changed fields get updated, local extension fields preserved
     * 4. AUTO_INACTIVE dashboards restored to ACTIVE when still in Superset
     * 5. MANUAL_INACTIVE dashboards keep their status unchanged
     * 6. Dashboards no longer in Superset (and not MANUAL_INACTIVE) are set to AUTO_INACTIVE
     *
     * Feature: bi-management, Property 1: Dashboard sync correctness
     * Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 1.9, 1.10
     */
    @Property(tries = 100)
    void dashboardSyncCorrectness(
            @ForAll("supersetDashboardData") List<Map<String, Object>> supersetData,
            @ForAll("existingRegistryRecords") List<BiDashboardRegistry> existingRecords
    ) {
        // Setup mocks
        when(jdbcTemplate.queryForList(anyString())).thenReturn(supersetData);
        when(registryRepository.findAll()).thenReturn(new ArrayList<>(existingRecords));

        // Build lookup maps before sync
        Map<Integer, BiDashboardRegistry> existingBySuperId = existingRecords.stream()
                .collect(Collectors.toMap(BiDashboardRegistry::getSupersetDashboardId, r -> deepCopy(r)));
        Set<Integer> supersetIds = supersetData.stream()
                .map(m -> (Integer) m.get("superset_dashboard_id"))
                .collect(Collectors.toSet());
        Map<Integer, Map<String, Object>> supersetById = supersetData.stream()
                .collect(Collectors.toMap(m -> (Integer) m.get("superset_dashboard_id"), m -> m));

        // Execute sync
        savedEntities.clear();
        SyncResultResponse result = syncComponent.executeSyncOperation();

        // Build saved entities map by supersetDashboardId (last save wins)
        Map<Integer, BiDashboardRegistry> savedBySuperId = new LinkedHashMap<>();
        for (BiDashboardRegistry saved : savedEntities) {
            savedBySuperId.put(saved.getSupersetDashboardId(), saved);
        }

        // === Verify each Superset dashboard ===
        for (Map<String, Object> row : supersetData) {
            Integer superId = (Integer) row.get("superset_dashboard_id");
            String expectedTitle = (String) row.get("dashboard_title");
            String expectedDesc = (String) row.get("description");
            UUID expectedEmbedId = (UUID) row.get("embed_id");
            UUID expectedDashUuid = (UUID) row.get("superset_dashboard_uuid");

            BiDashboardRegistry saved = savedBySuperId.get(superId);
            assertThat(saved)
                    .as("Superset dashboard %d should have been saved", superId)
                    .isNotNull();

            BiDashboardRegistry prior = existingBySuperId.get(superId);

            if (prior == null) {
                // Requirement 1.3: New dashboard created as ACTIVE with all required fields
                assertThat(saved.getStatus()).isEqualTo(DashboardStatus.ACTIVE);
                assertThat(saved.getDashboardTitle()).isEqualTo(expectedTitle);
                assertThat(saved.getDescription()).isEqualTo(expectedDesc);
                assertThat(saved.getEmbedId()).isEqualTo(expectedEmbedId);
                assertThat(saved.getSupersetDashboardUuid()).isEqualTo(expectedDashUuid);
                assertThat(saved.getSupersetDashboardId()).isEqualTo(superId);
                assertThat(saved.getId()).isNotNull();
                assertThat(saved.getLastSyncedAt()).isNotNull();
                assertThat(saved.getIsDefaultLanding()).isFalse();
            } else {
                // Requirement 1.4: Superset source fields updated
                assertThat(saved.getDashboardTitle()).isEqualTo(expectedTitle);
                assertThat(saved.getDescription()).isEqualTo(expectedDesc);
                assertThat(saved.getEmbedId()).isEqualTo(expectedEmbedId);

                // Requirement 1.4: Local extension fields preserved
                assertThat(saved.getTags()).isEqualTo(prior.getTags());
                assertThat(saved.getIsDefaultLanding()).isEqualTo(prior.getIsDefaultLanding());

                if (prior.getStatus() == DashboardStatus.MANUAL_INACTIVE) {
                    // Requirement 1.9: MANUAL_INACTIVE stays MANUAL_INACTIVE
                    assertThat(saved.getStatus()).isEqualTo(DashboardStatus.MANUAL_INACTIVE);
                } else if (prior.getStatus() == DashboardStatus.AUTO_INACTIVE) {
                    // Requirement 1.10: AUTO_INACTIVE restored to ACTIVE
                    assertThat(saved.getStatus()).isEqualTo(DashboardStatus.ACTIVE);
                }
                // ACTIVE stays ACTIVE (implicit)
            }
        }

        // === Verify dashboards no longer in Superset ===
        for (Map.Entry<Integer, BiDashboardRegistry> entry : existingBySuperId.entrySet()) {
            Integer superId = entry.getKey();
            BiDashboardRegistry prior = entry.getValue();

            if (!supersetIds.contains(superId)) {
                BiDashboardRegistry saved = savedBySuperId.get(superId);

                if (prior.getStatus() == DashboardStatus.MANUAL_INACTIVE) {
                    // MANUAL_INACTIVE should not be changed to AUTO_INACTIVE
                    // It may or may not be saved, but if saved, status should remain
                    if (saved != null) {
                        assertThat(saved.getStatus()).isEqualTo(DashboardStatus.MANUAL_INACTIVE);
                    }
                } else if (prior.getStatus() == DashboardStatus.AUTO_INACTIVE) {
                    // Already AUTO_INACTIVE, should stay AUTO_INACTIVE (no change needed)
                    // The component skips already AUTO_INACTIVE records
                } else {
                    // Requirement 1.5: ACTIVE -> AUTO_INACTIVE
                    assertThat(saved)
                            .as("Dashboard %d (was ACTIVE) should be saved as AUTO_INACTIVE", superId)
                            .isNotNull();
                    assertThat(saved.getStatus()).isEqualTo(DashboardStatus.AUTO_INACTIVE);
                }
            }
        }
    }

    // ========== Property 2: 同步摘要准确性 ==========

    /**
     * Property 2: 同步摘要准确性
     *
     * For any Sync_Operation execution, the returned summary counts must match
     * the actual changes:
     * - created = number of Superset dashboards NOT in existing registry
     * - updated = number of existing dashboards that were in Superset AND
     *   (had field changes OR status was AUTO_INACTIVE)
     * - autoInactivated = number of existing ACTIVE dashboards NOT in Superset results
     *
     * Feature: bi-management, Property 2: Sync summary accuracy
     * Validates: Requirements 1.6, 7.7
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management, Property 2: Sync summary accuracy")
    void syncSummaryAccuracy(
            @ForAll("supersetDashboardData") List<Map<String, Object>> supersetData,
            @ForAll("existingRegistryRecords") List<BiDashboardRegistry> existingRecords
    ) {
        // Setup mocks
        when(jdbcTemplate.queryForList(anyString())).thenReturn(supersetData);
        when(registryRepository.findAll()).thenReturn(new ArrayList<>(existingRecords));

        // Build lookup maps before sync
        Map<Integer, BiDashboardRegistry> existingBySuperId = existingRecords.stream()
                .collect(Collectors.toMap(BiDashboardRegistry::getSupersetDashboardId, r -> deepCopy(r)));
        Set<Integer> supersetIds = supersetData.stream()
                .map(m -> (Integer) m.get("superset_dashboard_id"))
                .collect(Collectors.toSet());
        Map<Integer, Map<String, Object>> supersetById = supersetData.stream()
                .collect(Collectors.toMap(m -> (Integer) m.get("superset_dashboard_id"), m -> m));

        // Manually compute expected counts
        int expectedCreated = 0;
        int expectedUpdated = 0;
        int expectedAutoInactivated = 0;

        // Count created: superset dashboards not in existing registry
        for (Map<String, Object> row : supersetData) {
            Integer superId = (Integer) row.get("superset_dashboard_id");
            if (!existingBySuperId.containsKey(superId)) {
                expectedCreated++;
            }
        }

        // Count updated: existing dashboards that are in Superset AND
        // (had field changes OR status was AUTO_INACTIVE)
        for (Map<String, Object> row : supersetData) {
            Integer superId = (Integer) row.get("superset_dashboard_id");
            BiDashboardRegistry prior = existingBySuperId.get(superId);
            if (prior != null) {
                String newTitle = (String) row.get("dashboard_title");
                String newDesc = (String) row.get("description");
                UUID newEmbedId = (UUID) row.get("embed_id");

                boolean fieldsChanged = !Objects.equals(prior.getDashboardTitle(), newTitle)
                        || !Objects.equals(prior.getDescription(), newDesc)
                        || !Objects.equals(prior.getEmbedId(), newEmbedId);

                if (prior.getStatus() == DashboardStatus.AUTO_INACTIVE) {
                    // AUTO_INACTIVE always counts as updated (restored to ACTIVE)
                    expectedUpdated++;
                } else if (prior.getStatus() == DashboardStatus.MANUAL_INACTIVE) {
                    // MANUAL_INACTIVE: only counts if fields changed
                    if (fieldsChanged) {
                        expectedUpdated++;
                    }
                } else {
                    // ACTIVE: only counts if fields changed
                    if (fieldsChanged) {
                        expectedUpdated++;
                    }
                }
            }
        }

        // Count autoInactivated: existing ACTIVE dashboards not in Superset
        for (BiDashboardRegistry prior : existingRecords) {
            if (!supersetIds.contains(prior.getSupersetDashboardId())) {
                if (prior.getStatus() != DashboardStatus.MANUAL_INACTIVE
                        && prior.getStatus() != DashboardStatus.AUTO_INACTIVE) {
                    expectedAutoInactivated++;
                }
            }
        }

        // Execute sync
        savedEntities.clear();
        SyncResultResponse result = syncComponent.executeSyncOperation();

        // Verify summary counts match expected
        assertThat(result.getCreated())
                .as("created count should match number of new dashboards")
                .isEqualTo(expectedCreated);
        assertThat(result.getUpdated())
                .as("updated count should match number of updated dashboards")
                .isEqualTo(expectedUpdated);
        assertThat(result.getAutoInactivated())
                .as("autoInactivated count should match number of newly auto-inactivated dashboards")
                .isEqualTo(expectedAutoInactivated);
    }

    // ========== Property 3: Dashboard 同步错误恢复 ==========

    /**
     * Property 3: Dashboard 同步错误恢复
     *
     * For any existing Dashboard Registry state, if the Sync_Operation encounters
     * a database connection exception during JdbcTemplate query, then no save calls
     * should be made (the exception occurs before any processing).
     *
     * Feature: bi-management, Property 3: Dashboard sync error recovery
     * Validates: Requirements 1.8
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management, Property 3: Dashboard sync error recovery")
    void syncErrorRecovery(
            @ForAll("existingRegistryRecords") List<BiDashboardRegistry> existingRecords
    ) {
        // Mock JdbcTemplate to throw RuntimeException (simulating DB connection failure)
        when(jdbcTemplate.queryForList(anyString()))
                .thenThrow(new RuntimeException("Simulated Superset database connection failure"));
        when(registryRepository.findAll()).thenReturn(new ArrayList<>(existingRecords));

        // Capture state before sync
        List<BiDashboardRegistry> snapshotBefore = existingRecords.stream()
                .map(this::deepCopy)
                .collect(Collectors.toList());

        // Execute sync - expect SupersetSyncException
        savedEntities.clear();
        assertThatThrownBy(() -> syncComponent.executeSyncOperation())
                .isInstanceOf(SupersetSyncException.class);

        // Verify no save calls were made (exception happens before any processing)
        verify(registryRepository, never()).save(any(BiDashboardRegistry.class));
        assertThat(savedEntities).isEmpty();
    }

    // ========== Helpers ==========

    private BiDashboardRegistry deepCopy(BiDashboardRegistry source) {
        return BiDashboardRegistry.builder()
                .id(source.getId())
                .dashboardTitle(source.getDashboardTitle())
                .description(source.getDescription())
                .embedId(source.getEmbedId())
                .supersetDashboardUuid(source.getSupersetDashboardUuid())
                .supersetDashboardId(source.getSupersetDashboardId())
                .tags(source.getTags())
                .isDefaultLanding(source.getIsDefaultLanding())
                .supersetRoleIds(source.getSupersetRoleIds())
                .status(source.getStatus())
                .lastSyncedAt(source.getLastSyncedAt())
                .createdAt(source.getCreatedAt())
                .createdBy(source.getCreatedBy())
                .updatedAt(source.getUpdatedAt())
                .updatedBy(source.getUpdatedBy())
                .build();
    }

    // ========== Property 19: Dashboard 角色同步 ==========

    /**
     * Property 19: Dashboard 角色同步
     *
     * For any Superset dashboard_roles rows, after sync every saved registry record carries exactly
     * the sorted, de-duplicated role IDs granted on that dashboard (null when none), and a role
     * change on an otherwise unchanged ACTIVE record counts as an update.
     *
     * Feature: bi-management, Property 19: Dashboard role sync
     * Validates: Requirements 1.4, 7.14
     */
    @Property(tries = 100)
    @Tag("Feature: bi-management, Property 19: Dashboard role sync")
    @SuppressWarnings("unchecked")
    void dashboardRoleSync(
            @ForAll("supersetDashboardData") List<Map<String, Object>> supersetData,
            @ForAll("dashboardRolePairs") List<int[]> rolePairs
    ) {
        // Only pairs for dashboards that exist in the Superset result matter; scatter them over those.
        List<Integer> dashboardIds = supersetData.stream()
                .map(m -> (Integer) m.get("superset_dashboard_id")).collect(Collectors.toList());
        List<int[]> pairs = new ArrayList<>();
        Map<Integer, Set<Integer>> expectedRoles = new HashMap<>();
        if (!dashboardIds.isEmpty()) {
            for (int i = 0; i < rolePairs.size(); i++) {
                int dashboardId = dashboardIds.get(i % dashboardIds.size());
                int roleId = rolePairs.get(i)[1];
                pairs.add(new int[]{dashboardId, roleId});
                expectedRoles.computeIfAbsent(dashboardId, k -> new TreeSet<>()).add(roleId);
            }
        }

        // Existing ACTIVE copies of every Superset dashboard with identical source fields but no roles:
        // the only thing sync can change on them is superset_role_ids.
        List<BiDashboardRegistry> existing = new ArrayList<>();
        for (Map<String, Object> row : supersetData) {
            existing.add(BiDashboardRegistry.builder()
                    .id(UUID.randomUUID().toString())
                    .supersetDashboardId((Integer) row.get("superset_dashboard_id"))
                    .dashboardTitle((String) row.get("dashboard_title"))
                    .description((String) row.get("description"))
                    .supersetDashboardUuid((UUID) row.get("superset_dashboard_uuid"))
                    .embedId((UUID) row.get("embed_id"))
                    .supersetRoleIds(null)
                    .status(DashboardStatus.ACTIVE)
                    .isDefaultLanding(false)
                    .lastSyncedAt(LocalDateTime.now().minusDays(1))
                    .build());
        }

        when(jdbcTemplate.queryForList(anyString())).thenReturn(supersetData);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(pairs);
        when(registryRepository.findAll()).thenReturn(new ArrayList<>(existing));

        savedEntities.clear();
        SyncResultResponse result = syncComponent.executeSyncOperation();

        Map<Integer, BiDashboardRegistry> savedBySuperId = new LinkedHashMap<>();
        for (BiDashboardRegistry saved : savedEntities) {
            savedBySuperId.put(saved.getSupersetDashboardId(), saved);
        }
        for (Integer dashboardId : dashboardIds) {
            Set<Integer> roles = expectedRoles.getOrDefault(dashboardId, Set.of());
            String expectedCsv = roles.isEmpty() ? null
                    : roles.stream().map(String::valueOf).collect(Collectors.joining(","));
            assertThat(savedBySuperId.get(dashboardId).getSupersetRoleIds())
                    .as("roles synced for dashboard %d", dashboardId)
                    .isEqualTo(expectedCsv);
        }
        assertThat(result.getCreated()).isZero();
        assertThat(result.getUpdated()).isEqualTo(expectedRoles.size());
    }

    @Provide
    Arbitrary<List<int[]>> dashboardRolePairs() {
        return Combinators.combine(
                Arbitraries.integers().between(1, 500),
                Arbitraries.integers().between(1, 6)
        ).as((d, r) -> new int[]{d, r}).list().ofMinSize(0).ofMaxSize(10);
    }
}
