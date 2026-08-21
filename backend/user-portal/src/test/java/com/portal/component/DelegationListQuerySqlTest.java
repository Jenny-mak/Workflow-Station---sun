package com.portal.component;

import com.portal.dto.DelegationListQueryRequest;
import com.portal.dto.ListColumnFilter;
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
class DelegationListQuerySqlTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private DelegationListQueryComponent component;
    private final List<String> preparedSql = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        component = new DelegationListQueryComponent(jdbcTemplate);
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
    }

    @Test
    void rulesCountAndPageShareDelegatorPredicate() {
        component.queryRules("user-1", request(null, List.of()));

        assertThat(preparedSql.get(0)).startsWith("SELECT COUNT(*) FROM up_delegation_rule r");
        assertThat(preparedSql.get(0)).contains("r.delegator_id = ?");
        assertThat(pageSql()).contains("LIMIT ?").contains("OFFSET ?");
        assertThat(pageSql()).contains("r.delegator_id = ?");
    }

    @Test
    void auditVisibilityIsEitherParty() {
        component.queryAudit("user-1", request(null, List.of()));

        assertThat(preparedSql.get(0)).contains("a.delegator_id = ? OR a.delegate_id = ?");
        assertThat(pageSql()).contains("a.delegator_id = ? OR a.delegate_id = ?");
    }

    @Test
    void filtersArePushedIntoTheSharedPredicate() {
        component.queryRules("user-1", request(null,
                List.of(new ListColumnFilter("status", "eq", "ACTIVE", null))));

        assertThat(pageSql()).contains("r.status");
        assertThat(preparedSql.get(0)).contains("r.status");
    }

    @Test
    void groupCountsAreTakenOverTheWholeResultSet() {
        component.queryRules("user-1", request("status", List.of()));

        String groupSql = preparedSql.stream().filter(sql -> sql.contains("GROUP BY")).findFirst().orElseThrow();
        assertThat(groupSql).doesNotContain("LIMIT");
        assertThat(groupSql).contains("COUNT(*) AS group_count");
    }

    @Test
    void undeclaredFilterIsRefused() {
        assertThatThrownBy(() -> component.queryRules("user-1", request(null,
                List.of(new ListColumnFilter("secret", "contains", "x", null)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String pageSql() {
        return preparedSql.stream().filter(sql -> sql.contains("LIMIT ?")).findFirst().orElseThrow();
    }

    private static DelegationListQueryRequest request(String groupBy, List<ListColumnFilter> filters) {
        return new DelegationListQueryRequest(0, 20, filters, null, null, groupBy);
    }
}
