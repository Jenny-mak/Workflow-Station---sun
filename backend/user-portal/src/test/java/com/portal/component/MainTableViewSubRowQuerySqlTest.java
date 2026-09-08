package com.portal.component;

import com.platform.common.list.ListColumnFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.util.MainTableViewColumnSpec;
import com.portal.util.MainTableViewColumnSpec.FieldSource;
import com.portal.util.MainTableViewColumnSpec.SqlSource;
import com.portal.util.PortalMainTableViewRowKeys;
import com.portal.util.SqlFragment;
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
 * What the SUB row query asks the database, captured from the statements it prepares. The SQL
 * itself was checked against the dev database; this pins the decisions that would otherwise be
 * easy to undo by accident.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MainTableViewSubRowQuerySqlTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private MainTableViewSubRowQueryComponent component;
    private final List<String> preparedSql = new ArrayList<>();
    private final List<Object> boundParams = new ArrayList<>();

    private static final List<FieldSource> FIELDS = List.of(
            new FieldSource("line_amount", "Amount", false, "field", "DECIMAL"),
            new FieldSource("line_status", "Line status", false, "field", "VARCHAR"),
            new FieldSource("reconciled", "Reconciled", false, "field", "BOOLEAN"));

    /** The statement that reads the page, which is the last one the component prepares. */
    private String pageSql() {
        return preparedSql.get(preparedSql.size() - 1);
    }

    @BeforeEach
    void setUp() throws Exception {
        component = new MainTableViewSubRowQueryComponent(jdbcTemplate, new ObjectMapper());

        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        org.mockito.Mockito.doAnswer(call -> {
            boundParams.add(call.getArgument(1));
            return null;
        }).when(statement).setObject(org.mockito.ArgumentMatchers.anyInt(), any());
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            preparedSql.add(call.getArgument(0));
            return statement;
        });
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), any(ResultSetExtractor.class)))
                .thenAnswer(call -> {
                    call.<PreparedStatementCreator>getArgument(0).createPreparedStatement(connection);
                    return null;
                });
    }

    private MainTableViewSubRowQueryComponent.Query query(List<ListColumnFilter> filters,
                                                          MainTableViewInvolvementScope.Predicate involvement) {
        return new MainTableViewSubRowQueryComponent.Query(
                7L,
                "fu-atm",
                "dw:atm_line",
                MainTableViewColumnSpec.sqlFor(FIELDS, List.of(), SqlSource.EXPANDED_SUB_ROW,
                        "pi.id, pi.row_identity"),
                SqlFragment.EMPTY,
                filters,
                null,
                null,
                null,
                List.of(),
                involvement,
                0,
                20);
    }

    @Test
    void theTableIsReadFromItsOneCanonicalSliceAndTheRowIsStillDeduplicated() {
        component.query(query(List.of(), null));

        String sql = preparedSql.get(0);
        assertThat(sql).contains("jsonb_array_elements(pi.variables->'__subTables__'->?::text)");
        assertThat(sql.split("jsonb_array_elements", -1))
                .as("one key per table means exactly one expansion, not one per binding")
                .hasSize(2);
        assertThat(sql)
                .as("an instance can still carry the same row identity twice after a bad merge, "
                        + "and it should be shown once")
                // The identity key list is owned by SubTableRowIdentity; naming its first entry
                // here as a literal asserts today's key rather than "the query de-duplicates by
                // row identity", and broke on a rename that kept the behaviour intact.
                .contains("DISTINCT ON (pi.id, COALESCE(expanded.elem->>'"
                        + com.platform.common.jdbc.SubTableRowIdentity.IDENTITY_FIELDS.get(0) + "'");
    }

    @Test
    void theSliceIsLookedUpByTheCanonicalTableKeyNotByBindingIds() {
        component.query(query(List.of(), null));

        assertThat(boundParams)
                .as("rows are stored under dw:<table name> (SubTableStoreKeys); looking the slice "
                        + "up by binding id finds nothing and the view silently shows No Data")
                .contains("dw:atm_line");
        assertThat(boundParams.stream().filter(p -> p instanceof String s && s.matches("\\d+")).toList())
                .as("a numeric binding id as a slice key is exactly the bug this pins")
                .isEmpty();
    }

    @Test
    void whoMaySeeARowIsSettledBeforeTheRowsAreExpanded() {
        component.query(query(List.of(),
                new MainTableViewInvolvementScope.Predicate(" AND (pi.start_user_id = ?)", List.of("u1"))));

        String sql = pageSql();
        assertThat(sql.indexOf("pi.start_user_id = ?"))
                .as("visibility is a property of the instance, so an instance the user may not "
                        + "see should never be expanded at all — the clause belongs inside the "
                        + "expansion, ahead of the outer WHERE")
                .isGreaterThan(sql.indexOf("DISTINCT ON"))
                .isLessThan(sql.indexOf("WHERE TRUE"));
    }

    @Test
    void theTotalCountsSubTableRowsRatherThanTheInstancesTheyCameFrom() {
        component.query(query(List.of(), null));

        assertThat(preparedSql.get(0))
                .as("counting instances would report a smaller total than the rows on screen")
                .startsWith("SELECT COUNT(*) FROM (SELECT DISTINCT ON");
    }

    @Test
    void aFilterOnAColumnTheViewDoesNotDeclareIsRefused() {
        assertThatThrownBy(() -> component.query(
                query(List.of(new ListColumnFilter("secret", "contains", "x", null)), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRowKeyMatchIsAppliedAfterTheRowIsExpanded() {
        SqlFragment rowKey = PortalMainTableViewRowKeys.exactMatch(
                "inst-1|row_id=ATM-DC-PW-TRANS-000030", true);
        MainTableViewSubRowQueryComponent.Query keyed = new MainTableViewSubRowQueryComponent.Query(
                7L,
                "fu-atm",
                "dw:atm_line",
                MainTableViewColumnSpec.sqlFor(FIELDS, List.of(), SqlSource.EXPANDED_SUB_ROW,
                        "pi.id, pi.row_identity"),
                rowKey,
                List.of(),
                null,
                null,
                null,
                List.of(),
                null,
                0,
                20);
        component.query(keyed);

        String sql = preparedSql.get(0);
        assertThat(sql.indexOf("pi.row_identity = ?"))
                .as("row_identity exists only after expansion; matching it inside LATERAL would "
                        + "not find the list rowKey")
                .isGreaterThan(sql.indexOf("WHERE TRUE"));
        assertThat(sql).doesNotContain("ILIKE");
    }

    @Test
    void aViewWithNoResolvableSliceKeyHasNowhereToReadItsRowsFromAndSaysSo() {
        MainTableViewSubRowQueryComponent.Query unkeyed = new MainTableViewSubRowQueryComponent.Query(
                7L, "fu-atm", null,
                MainTableViewColumnSpec.sqlFor(FIELDS, List.of(), SqlSource.EXPANDED_SUB_ROW,
                        "pi.id, pi.row_identity"),
                SqlFragment.EMPTY, List.of(), null, null, null, List.of(), null, 0, 20);

        assertThatThrownBy(() -> component.query(unkeyed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("__subTables__ key");
    }

    @Test
    void aRowWithNoIdentityIsReportedRatherThanMergedIntoItsNeighbour() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        // The first row goes to the COUNT extractor; the second is the page's one row.
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("row_identity")).thenReturn(null);
        when(rs.getString("id")).thenReturn("proc-1");
        when(rs.getString("slice_key")).thenReturn("dw:atm_line");
        when(rs.getLong("ord")).thenReturn(3L);

        when(jdbcTemplate.query(any(PreparedStatementCreator.class), any(ResultSetExtractor.class)))
                .thenAnswer(call -> call.<ResultSetExtractor<?>>getArgument(1).extractData(rs));

        assertThatThrownBy(() -> component.query(query(List.of(), null)))
                .as("DISTINCT ON treats two missing identities as the same row, so one of them "
                        + "would vanish and the total would be short by one")
                .hasMessageContaining("proc-1")
                .hasMessageContaining("dw:atm_line");
    }
}
