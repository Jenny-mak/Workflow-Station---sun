package com.portal.component;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChangeHistoryLookupAuditValuesTest {

    @Test
    void usesConfiguredDisplayColumn() {
        Map<String, Object> lookup = Map.of("status_name", "Open", "id", "st-open");
        assertThat(ChangeHistoryLookupAuditValues.visibleAuditValue(lookup, "status_name"))
                .isEqualTo("Open");
    }

    @Test
    void doesNotGuessIdWhenDisplayColumnIsMissing() {
        Map<String, Object> lookup = Map.of("id", "st-open", "code", "OPEN");
        assertThat(ChangeHistoryLookupAuditValues.visibleAuditValue(lookup, "status_name"))
                .isEqualTo(lookup);
    }

    @Test
    void keepsObjectWhenDisplayColumnIsNotConfigured() {
        Map<String, Object> lookup = Map.of("id", "st-open", "status_name", "Open");
        assertThat(ChangeHistoryLookupAuditValues.visibleAuditValue(lookup, null))
                .isEqualTo(lookup);
        assertThat(ChangeHistoryLookupAuditValues.visibleAuditValue(lookup, "  "))
                .isEqualTo(lookup);
    }
}

class ChangeHistorySubTableSliceMergerTest {

    @Test
    void laterShadowDoesNotReplaceAnAlreadyFilledValue() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("channel", "Letter");
        Map<String, Object> topLevelShadow = new LinkedHashMap<>();
        topLevelShadow.put("channel", "Email");
        assertThat(ChangeHistorySubTableSliceMerger.unionPreferringValues(nested, topLevelShadow))
                .containsEntry("channel", "Letter");
    }

    @Test
    void blankIncomingDoesNotClearAFilledValue() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("channel", "Email");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("channel", "  ");
        assertThat(ChangeHistorySubTableSliceMerger.unionPreferringValues(first, second))
                .containsEntry("channel", "Email");
    }

    @Test
    void unidentifiedRowsStaySeparateAndDoNotInventAnIndexIdentity() {
        Map<String, Map<String, Object>> rowsByIdentity = new LinkedHashMap<>();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("channel", "Email");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("channel", "Letter");
        ChangeHistorySubTableSliceMerger.mergeSliceRows(
                rowsByIdentity, List.of(first, second), List.of("item_id"));
        assertThat(rowsByIdentity).hasSize(2);
        assertThat(rowsByIdentity.keySet()).noneMatch(key -> key.contains("__index_"));
        assertThat(rowsByIdentity.values()).allSatisfy(row ->
                assertThat(row).doesNotContainKey(ChangeHistoryAuditRowKey.FIELD));
    }
}

class ChangeHistoryDesignerPrimaryKeyAmbiguityTest {

    @Test
    void unscopedTableNameWithTwoFunctionUnitsReturnsEmpty() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(contains("function_unit_id"), anyString()))
                .thenReturn(List.of(
                        Map.of("field_name", "alpha_id", "function_unit_id", 1L),
                        Map.of("field_name", "beta_id", "function_unit_id", 2L)));
        assertThat(ChangeHistoryDesignerPrimaryKeyLookup.resolve(jdbcTemplate, "shared_table"))
                .isEmpty();
    }

    @Test
    void functionUnitCodeScopesThePrimaryKeyQuery() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(contains("fu.code"), eq(String.class), eq("item_table"), eq("fu-a")))
                .thenReturn(List.of("item_id"));
        assertThat(ChangeHistoryDesignerPrimaryKeyLookup.resolve(jdbcTemplate, "dw:item_table", "fu-a"))
                .containsExactly("item_id");
    }
}
