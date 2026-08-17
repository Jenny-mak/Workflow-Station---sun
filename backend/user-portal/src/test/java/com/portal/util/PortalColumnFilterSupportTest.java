package com.portal.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PortalColumnFilterSupport")
class PortalColumnFilterSupportTest {

    @Test
    void parseFilters_whitelistAndSkipsEmpty() {
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
        raw.put("status", Map.of("operator", "eq", "value", "ACTIVE"));
        raw.put("reason", Map.of("operator", "contains", "value", ""));
        raw.put("unknown", Map.of("operator", "eq", "value", "x"));
        raw.put("delegateId", Map.of("operator", "isNull", "value", ""));

        List<PortalColumnFilterSupport.ColumnFilter> filters =
                PortalColumnFilterSupport.parseFilters(raw, java.util.Set.of("status", "reason", "delegateId"));

        assertThat(filters).extracting(PortalColumnFilterSupport.ColumnFilter::field)
                .containsExactly("status", "delegateId");
    }

    @Test
    void escapeLike_escapesWildcards() {
        assertThat(PortalColumnFilterSupport.escapeLike("a%b_c\\d")).isEqualTo("a\\%b\\_c\\\\d");
    }

    @Test
    void matchesText_operators() {
        assertThat(PortalColumnFilterSupport.matchesText("Hello", "contains", "ell")).isTrue();
        assertThat(PortalColumnFilterSupport.matchesText("Hello", "eq", "hello")).isTrue();
        assertThat(PortalColumnFilterSupport.matchesText("", "isNull", "")).isTrue();
        assertThat(PortalColumnFilterSupport.matchesText("x", "isNotNull", "")).isTrue();
        assertThat(PortalColumnFilterSupport.matchesText("Hello", "weirdOp", "ell")).isFalse();
    }

    @Test
    void resolveSort_groupByThenRuntime() {
        Sort sort = PortalColumnFilterSupport.resolveSort(
                "reason", "DESC", "status",
                java.util.Set.of("reason", "status", "createdAt"),
                "createdAt", Sort.Direction.DESC);
        var orders = sort.toList();
        assertThat(orders.get(0).getProperty()).isEqualTo("status");
        assertThat(orders.get(1).getProperty()).isEqualTo("reason");
    }

    @Test
    void parseFilterDates_readsCalendarDays() {
        assertThat(PortalColumnFilterSupport.parseFilterDates("on", "2026-03-05"))
                .containsExactly(LocalDate.of(2026, 3, 5));
        assertThat(PortalColumnFilterSupport.parseFilterDates("before", "2026-03-05T13:45:00"))
                .containsExactly(LocalDate.of(2026, 3, 5));
        assertThat(PortalColumnFilterSupport.parseFilterDates("between", "2026-03-01,2026-03-31"))
                .containsExactly(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
    }

    @Test
    void parseFilterDates_rejectsUnreadableValues() {
        assertThatThrownBy(() -> PortalColumnFilterSupport.parseFilterDates("on", "last week"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PortalColumnFilterSupport.parseFilterDates("between", "2026-03-01"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseFilters_byColumnKind_rejectsUnsupportedOperator() {
        List<PortalListColumnMeta> columns = List.of(
                PortalListColumnMeta.text("reason"),
                PortalListColumnMeta.datetime("startTime"));

        assertThatThrownBy(() -> PortalColumnFilterSupport.parseFilters(
                Map.of("startTime", Map.of("operator", "contains", "value", "2026")), columns))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startTime");
    }

    @Test
    void parseFilters_byColumnKind_dropsUndeclaredColumnButKeepsValidOnes() {
        List<PortalListColumnMeta> columns = List.of(
                PortalListColumnMeta.text("reason"),
                PortalListColumnMeta.datetime("startTime"));
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
        raw.put("reason", Map.of("operator", "contains", "value", "trip"));
        raw.put("startTime", Map.of("operator", "between", "value", "2026-03-01,2026-03-31"));
        raw.put("ipAddress", Map.of("operator", "eq", "value", "127.0.0.1"));

        assertThat(PortalColumnFilterSupport.parseFilters(raw, columns))
                .extracting(PortalColumnFilterSupport.ColumnFilter::field)
                .containsExactly("reason", "startTime");
    }

    @Test
    void withSort_preservesPage() {
        var pageable = PortalColumnFilterSupport.withSort(
                PageRequest.of(1, 25), "status", "ASC", null,
                java.util.Set.of("status", "createdAt"), "createdAt", Sort.Direction.DESC);
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(25);
    }

    @Test
    void appendDateFilterSql_onBindsHalfOpenDayRange() {
        java.util.List<Object> args = new java.util.ArrayList<>();
        String sql = PortalColumnFilterSupport.appendDateFilterSql("created_at", "on", "2026-08-14", args);
        assertThat(sql).isEqualTo(" AND created_at >= ? AND created_at < ?");
        assertThat(args).containsExactly(
                LocalDate.parse("2026-08-14").atStartOfDay(),
                LocalDate.parse("2026-08-15").atStartOfDay());
    }
}
