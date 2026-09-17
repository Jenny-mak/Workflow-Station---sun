package com.admin.bi.component;

import com.admin.bi.repository.BiDashboardRegistryRepository;
import com.admin.bi.repository.BiSupersetRoleRepository;
import com.admin.dto.request.BiDashboardListQueryRequest;
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
class BiDashboardListQuerySqlTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private BiDashboardRegistryRepository registryRepository;

    private BiDashboardListQueryComponent component;
    private final List<String> preparedSql = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        component = new BiDashboardListQueryComponent(jdbcTemplate, registryRepository,
                new BiDashboardRegistryResponseAssembler(mock(BiSupersetRoleRepository.class)));
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
    void countAndPageShareTheRegistryTable() {
        component.query(request(null, List.of()));

        assertThat(preparedSql.get(0)).startsWith("SELECT COUNT(*) FROM bi_dashboard_registry d");
        assertThat(pageSql()).contains("LIMIT ?").contains("OFFSET ?");
    }

    @Test
    void toolbarTitleIsInsideTheSharedPredicate() {
        component.query(request("sales", List.of()));

        assertThat(preparedSql.get(0)).contains("d.dashboard_title ILIKE ?");
        assertThat(pageSql()).contains("d.dashboard_title ILIKE ?");
    }

    @Test
    void aFilterOnAColumnTheListDoesNotDeclareIsRefused() {
        assertThatThrownBy(() -> component.query(request(null,
                List.of(new ListColumnFilter("secret", "contains", "x", null)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supersetRoleNamesFilterResolvesIdsThroughTheRoleRegistry() {
        component.query(request(null,
                List.of(new ListColumnFilter("supersetRoleNames", "contains", "Admin", null))));

        assertThat(pageSql())
                .contains("FROM bi_superset_role r")
                .contains("string_to_array(d.superset_role_ids, ',')::int[]")
                .contains("ILIKE ?");
    }

    private static BiDashboardListQueryRequest request(String title,
                                                       List<ListColumnFilter> filters) {
        return new BiDashboardListQueryRequest(0, 20, title, null, null, filters, null, null);
    }

    private String pageSql() {
        return preparedSql.get(preparedSql.size() - 1);
    }
}
