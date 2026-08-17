package com.portal.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.i18n.I18nService;
import com.portal.dto.PermissionRequestListItem;
import com.portal.enums.PermissionRequestStatus;
import com.portal.repository.PermissionRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * History pagination must exclude PENDING from both rows and Page.total when excludePending=true.
 */
class PermissionMyRequestsExcludePendingTest {

    private JdbcTemplate jdbcTemplate;
    private PermissionComponent permissionComponent;
    private PermissionRequestEnrichmentComponent enrichmentComponent;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        enrichmentComponent = mock(PermissionRequestEnrichmentComponent.class);
        when(enrichmentComponent.nonBlankString(any())).thenAnswer(inv -> {
            Object v = inv.getArgument(0);
            if (v == null) {
                return null;
            }
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : s;
        });
        when(enrichmentComponent.firstNonBlank(any(String[].class))).thenAnswer(inv -> {
            Object[] parts = inv.getArguments();
            for (Object part : parts) {
                if (part instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
            return null;
        });

        permissionComponent = new PermissionComponent(
                mock(PermissionRequestRepository.class),
                mock(RoleAccessComponent.class),
                mock(VirtualGroupAccessComponent.class),
                mock(FunctionUnitAccessComponent.class),
                new ObjectMapper(),
                jdbcTemplate,
                mock(I18nService.class));
        ReflectionTestUtils.setField(permissionComponent, "enrichmentComponent", enrichmentComponent);
    }

    @Test
    @DisplayName("excludePending=true appends status <> PENDING to count and data SQL")
    void excludePendingAddsStatusNotPendingClause() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(3L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(Collections.emptyList());

        Page<PermissionRequestListItem> page = permissionComponent.getMyRequests(
                "user-1", null, true, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(3L);

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> countArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(countSql.capture(), eq(Long.class), countArgs.capture());
        assertThat(countSql.getValue()).contains("status <> ?");
        assertThat(countArgs.getValue()).containsExactly("user-1", "user-1", PermissionRequestStatus.PENDING.name());

        ArgumentCaptor<String> dataSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> dataArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(dataSql.capture(), any(RowMapper.class), dataArgs.capture());
        assertThat(dataSql.getValue()).contains("status <> ?");
        assertThat(List.of(dataArgs.getValue())).contains(
                "user-1", "user-1", PermissionRequestStatus.PENDING.name(), 20, 0L);
    }

    @Test
    @DisplayName("explicit status wins over excludePending")
    void statusFilterTakesPrecedenceOverExcludePending() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(Collections.emptyList());

        permissionComponent.getMyRequests(
                "user-1", PermissionRequestStatus.APPROVED, true, PageRequest.of(0, 10));

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> countArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(countSql.capture(), eq(Long.class), countArgs.capture());
        assertThat(countSql.getValue()).contains("status = ?");
        assertThat(countSql.getValue()).doesNotContain("status <> ?");
        assertThat(countArgs.getValue()).containsExactly("user-1", "user-1", PermissionRequestStatus.APPROVED.name());
    }

    @Test
    @DisplayName("excludePending=false without status does not filter by status")
    void noStatusFilterWhenExcludePendingFalse() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(5L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(Collections.emptyList());

        permissionComponent.getMyRequests("user-1", null, false, PageRequest.of(0, 20));

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> countArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(countSql.capture(), eq(Long.class), countArgs.capture());
        assertThat(countSql.getValue()).doesNotContain("status");
        assertThat(countArgs.getValue()).containsExactly("user-1", "user-1");
    }
}
