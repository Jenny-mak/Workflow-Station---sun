package com.portal.util;

import com.platform.common.list.ListColumnFilter;
import com.platform.common.list.ListColumnMeta;
import com.platform.common.list.ListColumnMeta.Kind;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CompletedTaskColumnSpecTest {

    @Test
    void requestIdIsTextSearchableAndSortable() {
        assertThat(column("requestId").kind()).isEqualTo(Kind.TEXT);
        assertThat(column("requestId").filterable()).isTrue();
        assertThat(column("requestId").sortable()).isTrue();
        assertThat(CompletedTaskColumnSpec.columns()).noneMatch(c -> "currentStepName".equals(c.field()));
        assertThat(column("durationInMillis").kind()).isEqualTo(Kind.NUMBER);
        assertThat(column("completedTime").kind()).isEqualTo(Kind.DATETIME);
        assertThat(column("taskName").kind()).isEqualTo(Kind.TEXT);
        assertThat(CompletedTaskColumnSpec.columns()).noneMatch(c -> "action".equals(c.field()));
    }

    @Test
    void requestIdCompilesToPersistedJsonText() {
        List<Object> params = new ArrayList<>();
        String where = CompletedTaskColumnSpec.sql().whereClause(
                List.of(new ListColumnFilter("requestId", "contains", "ATM-DC", null)), params);
        assertThat(where).contains("pi.variables->>'__request_id'");
        assertThat(params).containsExactly("%ATM-DC%");
        assertThat(CompletedTaskColumnSpec.sql().orderBy("requestId", "ASC"))
                .contains("pi.variables->>'__request_id' ASC");
    }

    @Test
    void numericAndDateColumnsExposeKindOperators() {
        assertThat(column("durationInMillis").operators()).contains("gt", "between");
        assertThat(column("completedTime").operators()).contains("today", "between");
        assertThat(column("taskName").operators()).contains("contains", "eq");
    }

    private static ListColumnMeta column(String field) {
        return CompletedTaskColumnSpec.columns().stream()
                .filter(c -> field.equals(c.field()))
                .findFirst()
                .orElseThrow();
    }
}
