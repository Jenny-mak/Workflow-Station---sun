package com.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.component.RoleAccessComponent;
import com.portal.dto.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("PortalRelationTableServiceImpl queryTableData sort/filter")
class PortalRelationTableQueryDataFilterSortTest {

    @Test
    void queryTableData_appliesJsonFilterAndSort() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RoleAccessComponent roleAccess = mock(RoleAccessComponent.class);
        ObjectMapper objectMapper = new ObjectMapper();

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
                .thenReturn(List.of("name", "code"));
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql != null && sql.contains("COUNT(*)") && sql.contains("LIKE")),
                eq(Long.class),
                any(Object[].class)))
                .thenReturn(1L);
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("SELECT data") && sql.contains("ORDER BY")),
                any(RowMapper.class),
                any(Object[].class)))
                .thenReturn(Collections.emptyList());
        doAnswer(invocation -> {
            org.springframework.jdbc.core.RowCallbackHandler handler = invocation.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("grp_label")).thenReturn("Acme");
            when(rs.getLong("cnt")).thenReturn(1L);
            handler.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(
                argThat(sql -> sql != null && sql.contains("GROUP BY") && sql.contains("grp_label")),
                any(org.springframework.jdbc.core.RowCallbackHandler.class),
                any(Object[].class));

        PortalRelationTableService service = new PortalRelationTableServiceImpl(
                jdbcTemplate, roleAccess, objectMapper,
                mock(com.platform.common.fk.PrimaryKeyAllocationService.class));

        String filters = "{\"name\":{\"operator\":\"contains\",\"value\":\"acme\"}}";
        PageResponse<Map<String, Object>> result =
                service.queryTableData(tableId, userId, 0, 10, null, "code", "DESC", filters, "name");

        assertThat(result.getTotalElements()).isEqualTo(1L);
        assertThat(result.getGroupCounts()).isNotNull();
        assertThat(result.getGroupCounts()).containsEntry("Acme", 1L);

        ArgumentCaptor<String> dataSql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).query(dataSql.capture(), any(RowMapper.class), any(Object[].class));
        String selectDataSql = dataSql.getAllValues().stream()
                .filter(sql -> sql != null && sql.contains("SELECT data") && sql.contains("ORDER BY"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No sorted SELECT captured: " + dataSql.getAllValues()));
        assertThat(selectDataSql)
                .contains("data->>'name'")
                .contains("LIKE LOWER(?) ESCAPE")
                .contains("ORDER BY data->>'name' ASC")
                .contains("data->>'code' DESC")
                .contains("LIMIT ? OFFSET ?");
    }
}
