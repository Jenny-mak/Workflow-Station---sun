package com.portal.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
    void withSort_preservesPage() {
        var pageable = PortalColumnFilterSupport.withSort(
                PageRequest.of(1, 25), "status", "ASC", null,
                java.util.Set.of("status", "createdAt"), "createdAt", Sort.Direction.DESC);
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(25);
    }
}
