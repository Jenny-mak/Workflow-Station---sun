package com.portal.component;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Change-history slices are keyed by physical table name (filter output) or by the
 * history-normalized canonical key ({@code dw:foo} → {@code dw_foo}). Both must resolve
 * the designer primary key — otherwise a new row is audited without {@code rowIdentifier}
 * and later rows that share business defaults pair incorrectly.
 */
class ChangeHistoryPrimaryKeySliceLookupTest {

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(contains("function_unit_id"), anyString()))
                .thenAnswer(invocation -> {
                    String tableName = invocation.getArgument(1);
                    return "item_table".equalsIgnoreCase(tableName)
                            ? List.of(Map.of("field_name", "item_id", "function_unit_id", 1L))
                            : List.of();
                });
    }

    @Test
    void canonicalStoreKeyResolvesConfiguredPrimaryKey() {
        assertThat(ChangeHistoryDesignerPrimaryKeyLookup.resolve(jdbcTemplate, "dw:item_table"))
                .containsExactly("item_id");
    }

    @Test
    void historyTableNameResolvesConfiguredPrimaryKey() {
        assertThat(ChangeHistoryDesignerPrimaryKeyLookup.resolve(jdbcTemplate, "item_table"))
                .containsExactly("item_id");
    }

    @Test
    void historyNormalizedCanonicalKeyResolvesConfiguredPrimaryKey() {
        assertThat(ChangeHistoryDesignerPrimaryKeyLookup.tableNameFromHistoryNormalizedCanonicalKey(
                "dw_item_table")).isEqualTo("item_table");
        assertThat(ChangeHistoryDesignerPrimaryKeyLookup.tableNameFromHistoryNormalizedCanonicalKey(
                "rt_item_table")).isEqualTo("item_table");
        assertThat(ChangeHistoryDesignerPrimaryKeyLookup.tableNameFromHistoryNormalizedCanonicalKey(
                "item_table")).isNull();
        assertThat(ChangeHistoryDesignerPrimaryKeyLookup.resolve(jdbcTemplate, "dw_item_table"))
                .containsExactly("item_id");
    }

    @Test
    void unknownSliceReturnsEmptyRatherThanGuessingAColumn() {
        assertThat(ChangeHistoryDesignerPrimaryKeyLookup.resolve(jdbcTemplate, "no_such_table")).isEmpty();
        assertThat(ChangeHistoryDesignerPrimaryKeyLookup.resolve(jdbcTemplate, "")).isEmpty();
    }
}
