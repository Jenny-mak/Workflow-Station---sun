package com.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.dto.RelationFieldDTO;
import com.platform.common.enums.RelationDataType;
import com.portal.component.RoleAccessComponent;
import com.portal.dto.ListColumnFilter;
import com.portal.dto.RelationTableDataPage;
import com.portal.dto.RelationTableQueryRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("PortalRelationTableServiceImpl queryTableData")
class PortalRelationTableQueryDataSearchTest {

    private PortalRelationTableService serviceWith(
            JdbcTemplate jdbcTemplate, RoleAccessComponent roleAccess) {
        return new PortalRelationTableServiceImpl(
                jdbcTemplate,
                roleAccess,
                new ObjectMapper(),
                mock(com.platform.common.fk.PrimaryKeyAllocationService.class),
                mock(com.portal.component.RelationTableComputedFieldRecalculator.class));
    }

    @Test
    void queryTableData_appliesSearchFilterOnJsonRows() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RoleAccessComponent roleAccess = mock(RoleAccessComponent.class);
        Long tableId = 100L;
        String userId = "user1";

        when(roleAccess.getUserBusinessRoles(userId)).thenReturn(List.of(Map.of("id", "role1")));
        when(jdbcTemplate.queryForList(contains("sys_user_business_unit_roles"), eq(String.class), eq(userId)))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql != null && sql.contains("rt_table_access")),
                eq(Long.class),
                any(Object[].class)))
                .thenReturn(1L);
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql != null && sql.contains("rt_table_definitions")),
                eq(Integer.class),
                any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("rt_field_definitions"), any(RowMapper.class), eq(tableId)))
                .thenReturn(List.of(
                        RelationFieldDTO.builder().fieldName("name").dataType(RelationDataType.VARCHAR)
                                .displayName("Name").build(),
                        RelationFieldDTO.builder().fieldName("code").dataType(RelationDataType.VARCHAR)
                                .displayName("Code").build()));
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql != null && sql.contains("COUNT(*)") && sql.contains("ILIKE")),
                eq(Long.class),
                any(Object[].class)))
                .thenReturn(1L);
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("SELECT data") && sql.contains("ILIKE")),
                any(RowMapper.class),
                any(Object[].class)))
                .thenReturn(Collections.emptyList());

        RelationTableDataPage result = serviceWith(jdbcTemplate, roleAccess).queryTableData(
                tableId, userId, RelationTableQueryRequest.of(0, 10, "acme", List.of(), null, null));

        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.columns()).extracting(c -> c.field()).containsExactly("name", "code");
        assertThat(result.columns()).allMatch(c -> !c.groupable());

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).queryForObject(countSql.capture(), eq(Long.class), any(Object[].class));
        String searchCountSql = countSql.getAllValues().stream()
                .filter(sql -> sql.contains("ILIKE"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No search-filtered COUNT query captured: " + countSql.getAllValues()));
        assertThat(searchCountSql)
                .contains("data::text ILIKE ?")
                .contains("data->>'name'")
                .contains("data->>'code'");
    }

    @Test
    void queryTableData_pushesColumnFilterAndSortIntoSql() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RoleAccessComponent roleAccess = mock(RoleAccessComponent.class);
        Long tableId = 100L;
        String userId = "user1";

        when(roleAccess.getUserBusinessRoles(userId)).thenReturn(List.of(Map.of("id", "role1")));
        when(jdbcTemplate.queryForList(contains("sys_user_business_unit_roles"), eq(String.class), eq(userId)))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql != null && sql.contains("rt_table_access")),
                eq(Long.class),
                any(Object[].class)))
                .thenReturn(1L);
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql != null && sql.contains("rt_table_definitions")),
                eq(Integer.class),
                any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("rt_field_definitions"), any(RowMapper.class), eq(tableId)))
                .thenReturn(List.of(
                        RelationFieldDTO.builder().fieldName("name").dataType(RelationDataType.VARCHAR)
                                .displayName("Name").build(),
                        RelationFieldDTO.builder().fieldName("amount").dataType(RelationDataType.DECIMAL)
                                .displayName("Amount").build()));
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql != null && sql.contains("COUNT(*)")
                        && sql.contains("rt_table_data_rows")
                        && !sql.contains("rt_table_access")),
                eq(Long.class),
                any(Object[].class)))
                .thenReturn(0L);
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("SELECT data")),
                any(RowMapper.class),
                any(Object[].class)))
                .thenReturn(Collections.emptyList());

        serviceWith(jdbcTemplate, roleAccess).queryTableData(
                tableId,
                userId,
                RelationTableQueryRequest.of(
                        0,
                        20,
                        null,
                        List.of(new ListColumnFilter("name", "contains", "acme", null)),
                        "amount",
                        "DESC"));

        ArgumentCaptor<String> dataSql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).query(dataSql.capture(), any(RowMapper.class), any(Object[].class));
        String pageSql = dataSql.getAllValues().stream()
                .filter(sql -> sql != null && sql.contains("SELECT data, status"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No data page query: " + dataSql.getAllValues()));
        assertThat(pageSql)
                .contains("data->>'name' ILIKE ?")
                .contains("ORDER BY")
                .contains("::numeric")
                .contains("DESC NULLS LAST")
                .contains(", id");
    }

    @Test
    void queryTableData_systemUserDeclaresStatusAndLanguageAsEnumNotGroupable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RoleAccessComponent roleAccess = mock(RoleAccessComponent.class);
        Long systemUserTableId = -1_000_000_001L;

        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql != null && sql.contains("COUNT(*)") && sql.contains("sys_users")),
                eq(Long.class),
                any(Object[].class)))
                .thenReturn(0L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(Collections.emptyList());

        RelationTableDataPage page = serviceWith(jdbcTemplate, roleAccess).queryTableData(
                systemUserTableId,
                "user1",
                RelationTableQueryRequest.of(0, 20, null, List.of(), null, null));

        assertThat(page.columns()).allMatch(c -> !c.groupable());
        var status = page.columns().stream().filter(c -> c.field().equals("status")).findFirst().orElseThrow();
        assertThat(status.kind()).isEqualTo(com.portal.dto.PortalListColumnMeta.Kind.ENUM);
        assertThat(status.operators()).containsExactly("eq", "ne", "isNull", "isNotNull");
        assertThat(status.options()).extracting(com.portal.dto.PortalListColumnMeta.Option::value)
                .contains("ACTIVE", "INACTIVE", "LOCKED");
        var language = page.columns().stream().filter(c -> c.field().equals("language")).findFirst().orElseThrow();
        assertThat(language.kind()).isEqualTo(com.portal.dto.PortalListColumnMeta.Kind.ENUM);
        assertThat(language.options()).extracting(com.portal.dto.PortalListColumnMeta.Option::value)
                .contains("en", "zh_CN", "zh-CN");
        var username = page.columns().stream().filter(c -> c.field().equals("username")).findFirst().orElseThrow();
        assertThat(username.kind()).isEqualTo(com.portal.dto.PortalListColumnMeta.Kind.TEXT);
    }

    @Test
    void queryTableData_systemUserFilterStartsWithWhereNotBareAnd() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RoleAccessComponent roleAccess = mock(RoleAccessComponent.class);
        Long systemUserTableId = -1_000_000_001L;

        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql != null && sql.contains("COUNT(*)") && sql.contains("sys_users")),
                eq(Long.class),
                any(Object[].class)))
                .thenReturn(0L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(Collections.emptyList());

        serviceWith(jdbcTemplate, roleAccess).queryTableData(
                systemUserTableId,
                "user1",
                RelationTableQueryRequest.of(
                        0,
                        20,
                        null,
                        List.of(new ListColumnFilter("full_name", "contains", "dev", null)),
                        null,
                        null));

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(countSql.capture(), eq(Long.class), any(Object[].class));
        assertThat(countSql.getValue())
                .contains("FROM sys_users WHERE 1=1")
                .contains("AND full_name ILIKE ?")
                .doesNotContain("sys_users AND");
    }

    @Test
    void queryTableData_deniesWhenNoAccess() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RoleAccessComponent roleAccess = mock(RoleAccessComponent.class);
        when(roleAccess.getUserBusinessRoles("user1")).thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql != null && sql.contains("rt_table_access")),
                eq(Long.class),
                any(Object[].class)))
                .thenReturn(0L);

        assertThatThrownBy(() -> serviceWith(jdbcTemplate, roleAccess).queryTableData(
                100L, "user1", RelationTableQueryRequest.of(0, 10, null, List.of(), null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
