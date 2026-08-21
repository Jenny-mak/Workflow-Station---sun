package com.portal.component;

import com.portal.dto.ListColumnFilter;
import com.portal.dto.MyApplicationQueryRequest;
import com.portal.service.UserDisplayNameResolver;
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
 * My Requests pages in SQL: COUNT and the page share start_user_id (+ optional status tab),
 * group counts are not limited to the page, and undeclared filters are refused.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MyApplicationQuerySqlTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ProcessApplicationQueryComponent processApplicationQueryComponent;
    @Mock private RequestIdEnricher requestIdEnricher;
    @Mock private UserDisplayNameResolver userDisplayNameResolver;

    private MyApplicationListQueryComponent component;
    private final List<String> preparedSql = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        component = new MyApplicationListQueryComponent(
                jdbcTemplate, new com.fasterxml.jackson.databind.ObjectMapper(),
                processApplicationQueryComponent, requestIdEnricher, userDisplayNameResolver);
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
    void countAndPageShareTheInitiatorPredicate() {
        component.query("user-1", request(null, null, List.of()));

        assertThat(preparedSql.get(0)).startsWith("SELECT COUNT(*) FROM up_process_instance pi");
        assertThat(preparedSql.get(0)).contains("pi.start_user_id = ?");
        assertThat(pageSql()).contains("LIMIT ?").contains("OFFSET ?").contains("pi.start_user_id = ?");
    }

    @Test
    void statusTabIsInsideTheSharedPredicate() {
        component.query("user-1", request("RUNNING", null, List.of()));

        assertThat(preparedSql.get(0)).contains("pi.status = ?");
        assertThat(pageSql()).contains("pi.status = ?");
    }

    @Test
    void groupCountsAreTakenOverTheWholeResultSetNotOverThePage() {
        component.query("user-1", request(null, "status", List.of()));

        String groupSql = preparedSql.stream().filter(sql -> sql.contains("GROUP BY")).findFirst().orElseThrow();
        assertThat(groupSql).doesNotContain("LIMIT");
        assertThat(groupSql).contains("COUNT(*) AS group_count");
    }

    @Test
    void aFilterOnAColumnTheListDoesNotDeclareIsRefused() {
        assertThatThrownBy(() -> component.query("user-1", request(null, null,
                List.of(new ListColumnFilter("secret", "contains", "x", null)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void groupingATextColumnIsRefused() {
        assertThatThrownBy(() -> component.query("user-1", request(null, "businessKey", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not groupable");
    }

    @Test
    void listHydrateOmitsSubTableJson() {
        assertThat(MyApplicationListQueryComponent.LIST_PROJECTION_SQL)
                .contains("(pi.variables - '__subTables__')");
    }

    private MyApplicationQueryRequest request(String status, String groupBy, List<ListColumnFilter> filters) {
        return new MyApplicationQueryRequest(0, 20, status, filters, null, null, groupBy);
    }

    private String pageSql() {
        return preparedSql.get(preparedSql.size() - 1);
    }
}
