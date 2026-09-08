package com.portal.component;

import com.platform.common.list.ListColumnFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.dto.CompletedTaskQueryRequest;
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

/**
 * Completed Tasks pages in SQL: COUNT and the page share the assignee+finished predicate,
 * and undeclared filters are refused.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompletedTaskQuerySqlTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private RequestIdEnricher requestIdEnricher;

    private CompletedTaskListQueryComponent component;
    private final List<String> preparedSql = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        component = new CompletedTaskListQueryComponent(jdbcTemplate, new ObjectMapper(), requestIdEnricher);
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
    void countAndPageShareTheAssigneeFinishedPredicate() {
        component.query("user-1", request(List.of()));

        assertThat(preparedSql.get(0)).startsWith("SELECT COUNT(*) FROM ACT_HI_TASKINST ht");
        assertThat(preparedSql.get(0)).contains("ht.ASSIGNEE_ = ?").contains("ht.END_TIME_ IS NOT NULL");
        assertThat(pageSql()).contains("LIMIT ?").contains("OFFSET ?");
        assertThat(pageSql()).contains("ht.ASSIGNEE_ = ?").contains("ht.END_TIME_ IS NOT NULL");
        assertThat(pageSql()).contains("(pi.variables - '__subTables__')");
    }

    @Test
    void filtersArePushedIntoTheSharedPredicate() {
        component.query("user-1", request(
                List.of(new ListColumnFilter("durationInMillis", "gt", "10", null))));

        assertThat(pageSql()).contains("ht.DURATION_::text");
        assertThat(preparedSql.get(0)).contains("ht.DURATION_::text");
    }

    @Test
    void requestIdFilterAndSortShareTheJsonTextPredicate() {
        component.query("user-1", new CompletedTaskQueryRequest(
                0, 20, List.of(new ListColumnFilter("requestId", "contains", "ATM-DC", null)),
                "requestId", "ASC", null, null, null));

        assertThat(preparedSql.get(0)).contains("pi.variables->>'__request_id'");
        assertThat(pageSql()).contains("pi.variables->>'__request_id'");
        assertThat(pageSql()).contains("pi.variables->>'__request_id' ASC");
    }

    @Test
    void aFilterOnAColumnTheListDoesNotDeclareIsRefused() {
        assertThatThrownBy(() -> component.query("user-1", request(
                List.of(new ListColumnFilter("secret", "contains", "x", null)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CompletedTaskQueryRequest request(List<ListColumnFilter> filters) {
        return new CompletedTaskQueryRequest(0, 20, filters, null, null, null, null, null);
    }

    private String pageSql() {
        return preparedSql.get(preparedSql.size() - 1);
    }
}
