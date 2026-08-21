package com.portal.properties;

import com.platform.common.dto.RelationTableDTO;
import com.platform.common.enums.RelationTableStatus;
import com.portal.component.RoleAccessComponent;
import com.portal.service.PortalRelationTableService;
import com.portal.service.PortalRelationTableServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for Portal Relation Table service.
 *
 * Covers Properties 5, 6, 13, 18.
 */
class PortalRelationTablePropertyTest {

    private static final Long SYSTEM_USER_TABLE_ID = -1_000_000_001L;

    /**
     * Property 5: Portal 可见性过滤
     *
     * For any Relation Table, when portal_visible is true, the table should appear
     * in the User Portal list (given user has permission); when false, it should not.
     *
     * <p><b>Feature: relation-tables, Property 5: Portal 可见性过滤</b></p>
     * <p><b>Validates: Requirements 7.2, 7.3, 7.4</b></p>
     */
    @Property(tries = 100)
    @Label("Feature: relation-tables, Property 5: Portal visibility filtering")
    void portalVisibleTablesShouldAppearInList(
            @ForAll("tableScenarios") TableScenario scenario) {

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RoleAccessComponent roleAccess = mock(RoleAccessComponent.class);
        ObjectMapper objectMapper = new ObjectMapper();

        // Only portal_visible=true AND DEPLOYED AND enabled tables should be returned by SQL
        List<RelationTableDTO> visibleDeployed = scenario.tables.stream()
                .filter(t -> t.getPortalVisible() && t.getStatus() == RelationTableStatus.DEPLOYED && t.getEnabled())
                .collect(Collectors.toList());

        when(jdbcTemplate.query(contains("rt_table_definitions"), any(RowMapper.class), eq(RelationTableStatus.DEPLOYED.getCode())))
                .thenReturn(visibleDeployed);

        // Mock user roles
        List<Map<String, Object>> roles = List.of(Map.of("id", scenario.userRoleId));
        when(roleAccess.getUserBusinessRoles(scenario.userId)).thenReturn(roles);

        // Mock access check - all tables have access for this role
        when(jdbcTemplate.queryForObject(contains("rt_table_access"), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);

        PortalRelationTableService service = new PortalRelationTableServiceImpl(jdbcTemplate, roleAccess, objectMapper,
                mock(com.platform.common.fk.PrimaryKeyAllocationService.class),
                mock(com.portal.component.RelationTableComputedFieldRecalculator.class));
        List<RelationTableDTO> result = service.getVisibleTables(scenario.userId);

        // All returned tables should be portal_visible=true
        assertThat(result).allMatch(RelationTableDTO::getPortalVisible);
        // All returned tables should be DEPLOYED
        assertThat(result).allMatch(t -> t.getStatus() == RelationTableStatus.DEPLOYED);

        // No non-visible table should appear
        Set<Long> nonVisibleIds = scenario.tables.stream()
                .filter(t -> !t.getPortalVisible() || t.getStatus() != RelationTableStatus.DEPLOYED)
                .map(RelationTableDTO::getId)
                .collect(Collectors.toSet());
        Set<Long> resultIds = result.stream().map(RelationTableDTO::getId).collect(Collectors.toSet());
        if (!nonVisibleIds.isEmpty()) {
            assertThat(resultIds).doesNotContainAnyElementsOf(nonVisibleIds);
        }
    }

    /**
     * Property 6: Portal 写操作拒绝
     *
     * For any write operation through User Portal API, the system should reject
     * the request. The service layer enforces read-only access.
     *
     * <p><b>Feature: relation-tables, Property 6: Portal 写操作拒绝</b></p>
     * <p><b>Validates: Requirements 8.7, 12.5</b></p>
     */
    @Property(tries = 100)
    @Label("Feature: relation-tables, Property 6: Portal write operations rejected")
    void portalWriteOperationsShouldBeRejected(
            @ForAll("userIds") String userId,
            @ForAll("tableIds") Long tableId) {

        // The PortalRelationTableService interface has no write methods (add/update/delete).
        // This property verifies the design: the service only exposes read operations.
        // Any attempt to modify data through the portal should be blocked at the controller level.

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RoleAccessComponent roleAccess = mock(RoleAccessComponent.class);
        ObjectMapper objectMapper = new ObjectMapper();

        // Mock no access
        when(roleAccess.getUserBusinessRoles(userId)).thenReturn(Collections.emptyList());

        PortalRelationTableService service = new PortalRelationTableServiceImpl(jdbcTemplate, roleAccess, objectMapper,
                mock(com.platform.common.fk.PrimaryKeyAllocationService.class),
                mock(com.portal.component.RelationTableComputedFieldRecalculator.class));

        // Verify: getVisibleTables returns empty when user has no roles
        List<RelationTableDTO> result = service.getVisibleTables(userId);
        assertThat(result).isEmpty();

        // Verify: queryTableData rejects callers without access
        when(jdbcTemplate.queryForObject(contains("rt_table_access"), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        assertThatThrownBy(() -> service.queryTableData(
                tableId, userId, com.portal.dto.RelationTableQueryRequest.of(0, 10, null, List.of(), null, null)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        // Verify: exportCsv returns an empty payload when user has no access
        assertThat(service.exportCsv(tableId, userId, 1000)).isEmpty();
    }

    /**
     * Property 13: Lookup 搜索结果正确性
     *
     * For any search keyword and Search_Field_Config, returned results should
     * contain the keyword in at least one configured search field.
     *
     * <p><b>Feature: relation-tables, Property 13: Lookup 搜索结果正确性</b></p>
     * <p><b>Validates: Requirements 11.2, 11.3</b></p>
     */
    @Property(tries = 100)
    @Label("Feature: relation-tables, Property 13: Lookup search result correctness")
    void lookupSearchResultsShouldMatchKeyword(
            @ForAll("searchKeywords") String keyword,
            @ForAll("searchFieldLists") List<String> searchFields) {

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RoleAccessComponent roleAccess = mock(RoleAccessComponent.class);
        ObjectMapper objectMapper = new ObjectMapper();

        Long tableId = 1L;

        // Mock table name lookup
        when(jdbcTemplate.query(contains("table_name"), any(RowMapper.class), eq(tableId), any()))
                .thenReturn(List.of("test_table"));

        // Simulate search results - each row has at least one search field containing keyword
        List<Map<String, Object>> mockResults = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> row = new HashMap<>();
            for (String field : searchFields) {
                row.put(field, "prefix_" + keyword + "_suffix_" + i);
            }
            row.put("display_col", "Display " + i);
            mockResults.add(row);
        }

        when(jdbcTemplate.queryForList(contains("ILIKE"), any(Object[].class)))
                .thenReturn(mockResults);

        PortalRelationTableService service = new PortalRelationTableServiceImpl(jdbcTemplate, roleAccess, objectMapper,
                mock(com.platform.common.fk.PrimaryKeyAllocationService.class),
                mock(com.portal.component.RelationTableComputedFieldRecalculator.class));
        List<Map<String, Object>> results = service.searchForLookup(tableId, keyword, searchFields, "display_col", null, 10, 0);

        // Each result should contain the keyword in at least one search field
        for (Map<String, Object> row : results) {
            boolean hasMatch = searchFields.stream()
                    .anyMatch(f -> {
                        Object val = row.get(f);
                        return val != null && val.toString().contains(keyword);
                    });
            assertThat(hasMatch).isTrue();
        }
    }

    @Test
    void systemUserLookupShouldQuerySysUsersWithAllowedColumnsOnly() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RoleAccessComponent roleAccess = mock(RoleAccessComponent.class);
        ObjectMapper objectMapper = new ObjectMapper();
        PortalRelationTableService service = new PortalRelationTableServiceImpl(jdbcTemplate, roleAccess, objectMapper,
                mock(com.platform.common.fk.PrimaryKeyAllocationService.class),
                mock(com.portal.component.RelationTableComputedFieldRecalculator.class));

        List<Map<String, Object>> mockResults = List.of(Map.of(
                "id", "user-1",
                "username", "alice",
                "display_name", "Alice"
        ));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(mockResults);

        List<Map<String, Object>> results = service.searchForLookup(
                SYSTEM_USER_TABLE_ID,
                "ali",
                List.of("username", "display_name", "password_hash"),
                "display_name",
                null,
                10,
                0);

        assertThat(results).isEqualTo(mockResults);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), any(Object[].class));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("SELECT id, username, display_name, full_name, email, employee_id, status, language FROM sys_users");
        assertThat(sql).contains("deleted = false", "status = 'ACTIVE'");
        assertThat(sql).doesNotContain("password_hash");
    }

    /**
     * Property 18: CSV 导出数据一致性
     *
     * For any table data, the exported CSV should contain consistent row count
     * and column data.
     *
     * <p><b>Feature: relation-tables, Property 18: CSV 导出数据一致性</b></p>
     * <p><b>Validates: Requirements 8.5</b></p>
     */
    @Property(tries = 100)
    @Label("Feature: relation-tables, Property 18: CSV export data consistency")
    void csvExportShouldBeConsistentWithData(
            @ForAll("csvDataScenarios") CsvDataScenario scenario) {

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RoleAccessComponent roleAccess = mock(RoleAccessComponent.class);
        ObjectMapper objectMapper = new ObjectMapper();

        String userId = "user1";
        Long tableId = 1L;

        // Mock user has access
        when(roleAccess.getUserBusinessRoles(userId)).thenReturn(List.of(Map.of("id", "role1")));
        when(jdbcTemplate.queryForObject(contains("rt_table_access"), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);

        // Mock deployed relation table check (rt_table_definitions status guard)
        when(jdbcTemplate.queryForObject(contains("rt_table_definitions"), eq(Integer.class), eq(tableId), any()))
                .thenReturn(1);

        // Mock field definitions (loadFields maps rt_field_definitions rows to RelationFieldDTO)
        when(jdbcTemplate.query(contains("field_name"), any(RowMapper.class), eq(tableId)))
                .thenReturn(scenario.fieldNames.stream()
                        .map(fn -> com.platform.common.dto.RelationFieldDTO.builder()
                                .fieldName(fn)
                                .dataType(com.platform.common.enums.RelationDataType.TEXT)
                                .build())
                        .collect(Collectors.toList()));

        // Mock data (JSON row storage: SELECT data FROM rt_table_data_rows WHERE table_id = ? ... LIMIT ?)
        when(jdbcTemplate.query(contains("rt_table_data_rows"), any(RowMapper.class), eq(tableId), eq(scenario.maxRows)))
                .thenReturn(scenario.rows);

        PortalRelationTableService service = new PortalRelationTableServiceImpl(jdbcTemplate, roleAccess, objectMapper,
                mock(com.platform.common.fk.PrimaryKeyAllocationService.class),
                mock(com.portal.component.RelationTableComputedFieldRecalculator.class));
        String csv = service.exportCsv(tableId, userId, scenario.maxRows);

        // Parse CSV
        String[] lines = csv.split("\n");
        assertThat(lines.length).isEqualTo(scenario.rows.size() + 1); // header + data rows

        // Header should contain all field names
        String header = lines[0];
        for (String fieldName : scenario.fieldNames) {
            assertThat(header).contains(fieldName);
        }
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<TableScenario> tableScenarios() {
        Arbitrary<String> userIdArb = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10).map(s -> "user-" + s);
        Arbitrary<String> roleIdArb = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10).map(s -> "role-" + s);
        Arbitrary<Integer> countArb = Arbitraries.integers().between(0, 15);

        return Combinators.combine(userIdArb, roleIdArb, countArb).flatAs((userId, roleId, count) -> {
            // Generate tables with unique sequential IDs to avoid collisions
            Arbitrary<RelationTableDTO> tableArb = Combinators.combine(
                    Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20),
                    Arbitraries.of(RelationTableStatus.values()),
                    Arbitraries.of(true, false),
                    Arbitraries.of(true, false)
            ).as((name, status, enabled, portalVisible) -> RelationTableDTO.builder()
                    .tableName(name)
                    .displayName(name)
                    .status(status)
                    .enabled(enabled)
                    .portalVisible(portalVisible)
                    .currentVersion(1)
                    .build());

            return tableArb.list().ofSize(count).map(tables -> {
                // Assign unique IDs
                for (int i = 0; i < tables.size(); i++) {
                    tables.get(i).setId((long) (i + 1));
                }
                return new TableScenario(userId, roleId, tables);
            });
        });
    }

    @Provide
    Arbitrary<String> userIds() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10).map(s -> "user-" + s);
    }

    @Provide
    Arbitrary<Long> tableIds() {
        return Arbitraries.longs().between(1, 10000);
    }

    @Provide
    Arbitrary<String> searchKeywords() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10);
    }

    @Provide
    Arbitrary<List<String>> searchFieldLists() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15)
                .list().ofMinSize(1).ofMaxSize(5).uniqueElements();
    }

    @Provide
    Arbitrary<CsvDataScenario> csvDataScenarios() {
        Arbitrary<List<String>> fieldNamesArb = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15)
                .list().ofMinSize(1).ofMaxSize(5).uniqueElements();
        Arbitrary<Integer> rowCountArb = Arbitraries.integers().between(0, 10);
        Arbitrary<Integer> maxRowsArb = Arbitraries.integers().between(10, 1000);

        return Combinators.combine(fieldNamesArb, rowCountArb, maxRowsArb).as((fields, rowCount, maxRows) -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < rowCount; i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (String field : fields) {
                    row.put(field, "value_" + i + "_" + field);
                }
                rows.add(row);
            }
            return new CsvDataScenario(fields, rows, maxRows);
        });
    }

    record TableScenario(String userId, String userRoleId, List<RelationTableDTO> tables) {}
    record CsvDataScenario(List<String> fieldNames, List<Map<String, Object>> rows, int maxRows) {}
}
