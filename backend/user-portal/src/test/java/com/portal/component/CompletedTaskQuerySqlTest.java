package com.portal.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.dto.CompletedTaskQueryRequest;
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

/**
 * Completed Tasks pages in SQL: COUNT and the page share the assignee+finished predicate,
 * group counts are not limited to the page, and undeclared filters are refused.
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
        component.query("user-1", request(null, List.of()));

        assertThat(preparedSql.get(0)).startsWith("SELECT COUNT(*) FROM ACT_HI_TASKINST ht");
        assertThat(preparedSql.get(0)).contains("ht.ASSIGNEE_ = ?").contains("ht.END_TIME_ IS NOT NULL");
        assertThat(pageSql()).contains("LIMIT ?").contains("OFFSET ?");
        assertThat(pageSql()).contains("ht.ASSIGNEE_ = ?").contains("ht.END_TIME_ IS NOT NULL");
        assertThat(pageSql()).contains("(pi.variables - '__subTables__')");
    }

    @Test
    void filtersArePushedIntoTheSharedPredicate() {
        component.query("user-1", request(null,
                List.of(new ListColumnFilter("durationInMillis", "gt", "10", null))));

        assertThat(pageSql()).contains("ht.DURATION_::text");
        assertThat(preparedSql.get(0)).contains("ht.DURATION_::text");
    }

    @Test
    void groupCountsAreTakenOverTheWholeResultSetNotOverThePage() {
        component.query("user-1", request("action", List.of()));

        String groupSql = preparedSql.stream().filter(sql -> sql.contains("GROUP BY")).findFirst().orElseThrow();
        assertThat(groupSql).doesNotContain("LIMIT");
        assertThat(groupSql).contains("COUNT(*) AS group_count");
        assertThat(pageSql()).contains("ORDER BY").contains(CompletedTaskColumnSpecAction());
    }

    @Test
    void aFilterOnAColumnTheListDoesNotDeclareIsRefused() {
        assertThatThrownBy(() -> component.query("user-1", request(null,
                List.of(new ListColumnFilter("secret", "contains", "x", null)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groupingATextColumnIsRefused() {
        assertThatThrownBy(() -> component.query("user-1", request("taskName", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not groupable");
    }

    private CompletedTaskQueryRequest request(String groupBy, List<ListColumnFilter> filters) {
        return new CompletedTaskQueryRequest(0, 20, filters, null, null, groupBy, null, null, null);
    }

    private String pageSql() {
        return preparedSql.get(preparedSql.size() - 1);
    }

    private static String CompletedTaskColumnSpecAction() {
        return com.portal.util.CompletedTaskColumnSpec.ACTION_SQL;
    }
}
