package com.admin.component;

import com.admin.dto.request.FunctionUnitDeploymentListQueryRequest;
import com.admin.repository.FunctionUnitDeploymentRepository;
import com.admin.service.UserReferenceResolver;
import com.platform.common.list.ListColumnFilter;
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
class FunctionUnitDeploymentListQuerySqlTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private FunctionUnitDeploymentRepository deploymentRepository;
    @Mock private UserReferenceResolver userReferenceResolver;

    private FunctionUnitDeploymentListQueryComponent component;
    private final List<String> preparedSql = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        component = new FunctionUnitDeploymentListQueryComponent(
                jdbcTemplate, deploymentRepository, userReferenceResolver);
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
    void countAndPageShareTheJoin() {
        component.query(request(List.of()));

        assertThat(preparedSql.get(0)).contains("sys_function_unit_deployments d");
        assertThat(preparedSql.get(0)).contains("sys_function_units fu");
        assertThat(preparedSql.get(0)).doesNotContain("LIMIT ?");
        assertThat(pageSql()).contains("LIMIT ?").contains("OFFSET ?");
    }

    @Test
    void aFilterOnAColumnTheListDoesNotDeclareIsRefused() {
        assertThatThrownBy(() -> component.query(request(
                List.of(new ListColumnFilter("secret", "contains", "x", null)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keywordSearchCoversFunctionUnitVersionAndStatus() {
        component.query(new FunctionUnitDeploymentListQueryRequest(0, 20, "approved", List.of(), null, null));

        assertThat(preparedSql.get(0))
                .contains("fu.name ILIKE ?")
                .contains("fu.code ILIKE ?")
                .contains("COALESCE(fu.version, '') ILIKE ?")
                .contains("CAST(d.status AS TEXT) ILIKE ?");
    }

    private static FunctionUnitDeploymentListQueryRequest request(List<ListColumnFilter> filters) {
        return new FunctionUnitDeploymentListQueryRequest(0, 20, null, filters, null, null);
    }

    private String pageSql() {
        return preparedSql.stream()
                .filter(sql -> sql.contains("LIMIT ?"))
                .findFirst()
                .orElse(preparedSql.get(preparedSql.size() - 1));
    }
}
