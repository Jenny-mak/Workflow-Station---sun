package com.portal.component;

import com.portal.dto.ListColumnFilter;
import com.portal.dto.PermissionListQueryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionRequestListQuerySqlTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PermissionRequestEnrichmentComponent enrichmentComponent;
    @Mock
    private VirtualGroupAccessComponent virtualGroupAccessComponent;

    private PermissionRequestListQueryComponent component;
    private final List<String> preparedSql = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        component = new PermissionRequestListQueryComponent(
                jdbcTemplate, enrichmentComponent, virtualGroupAccessComponent);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            preparedSql.add(call.getArgument(0));
            return statement;
        });
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), any(ResultSetExtractor.class)))
                .thenAnswer(call -> {
                    call.<PreparedStatementCreator>getArgument(0).createPreparedStatement(connection);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.next()).thenReturn(false);
                    return call.<ResultSetExtractor<?>>getArgument(1).extractData(rs);
                });
        when(virtualGroupAccessComponent.getApproverBusinessUnitIds(anyString())).thenReturn(List.of("bu-1"));
        when(virtualGroupAccessComponent.getApproverVirtualGroupIds(anyString())).thenReturn(List.of());
    }

    @Test
    void myPendingSharesApplicantPredicateAndStatus() {
        component.query("user-1", request("MY_PENDING", List.of()));

        assertThat(preparedSql.get(0)).contains("p.applicant_id = ? OR p.submitted_by_user_id = ?");
        assertThat(preparedSql.get(0)).contains("p.status = 'PENDING'");
        assertThat(pageSql()).contains("LIMIT ?").contains("OFFSET ?");
    }

    @Test
    void myCompletedExcludesPending() {
        component.query("user-1", request("MY_COMPLETED", List.of()));
        assertThat(preparedSql.get(0)).contains("p.status <> 'PENDING'");
    }

    @Test
    void approvalsPendingUsesBuIds() {
        component.query("user-1", request("APPROVALS_PENDING", List.of()));
        assertThat(preparedSql.get(0)).contains("p.business_unit_id IN");
        assertThat(preparedSql.get(0)).contains("p.status = 'PENDING'");
    }

    @Test
    void undeclaredFilterIsRefused() {
        assertThatThrownBy(() -> component.query("user-1", request("MY_PENDING",
                List.of(new ListColumnFilter("secret", "contains", "x", null)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String pageSql() {
        return preparedSql.stream().filter(sql -> sql.contains("LIMIT ?")).findFirst().orElseThrow();
    }

    private static PermissionListQueryRequest request(String scope, List<ListColumnFilter> filters) {
        return new PermissionListQueryRequest(0, 20, filters, null, null, null, scope);
    }
}
