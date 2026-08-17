package com.portal.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProcessApplicationListSpec")
class ProcessApplicationListSpecTest {

    @Test
    void resolveSort_defaultsToStartTimeDesc() {
        Sort sort = ProcessApplicationListSpec.resolveSort(null, null);
        assertThat(sort.getOrderFor("startTime")).isNotNull();
        assertThat(sort.getOrderFor("startTime").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void resolveSort_whitelistAndDirection() {
        Sort sort = ProcessApplicationListSpec.resolveSort("businessKey", "ASC");
        assertThat(sort.getOrderFor("businessKey").getDirection()).isEqualTo(Sort.Direction.ASC);

        Sort ignored = ProcessApplicationListSpec.resolveSort("hack;drop", "ASC");
        assertThat(ignored.getOrderFor("startTime").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void withSort_preservesPageSize() {
        var pageable = ProcessApplicationListSpec.withSort(PageRequest.of(2, 15), "status", "ASC");
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(15);
        assertThat(pageable.getSort().getOrderFor("status").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void resolveSort_groupByThenRuntime() {
        Sort sort = ProcessApplicationListSpec.resolveSort("businessKey", "DESC", "status");
        var orders = sort.toList();
        assertThat(orders.get(0).getProperty()).isEqualTo("status");
        assertThat(orders.get(0).getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(orders.get(1).getProperty()).isEqualTo("businessKey");
        assertThat(orders.get(1).getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void sanitizeGroupBy_whitelist() {
        assertThat(ProcessApplicationListSpec.sanitizeGroupBy("status")).isEqualTo("status");
        assertThat(ProcessApplicationListSpec.sanitizeGroupBy("hack;drop")).isNull();
        assertThat(ProcessApplicationListSpec.sanitizeGroupBy("  ")).isNull();
    }

    @Test
    void parseFilters_mapsAliasesAndSkipsEmpty() {
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
        raw.put("currentStepName", Map.of("operator", "contains", "value", "Approve"));
        raw.put("requestId", Map.of("operator", "contains", "value", "BK-1"));
        raw.put("startTime", Map.of("operator", "contains", "value", "2024"));
        raw.put("title", Map.of("operator", "eq", "value", ""));
        raw.put("status", Map.of("operator", "isNull", "value", ""));
        raw.put("unknown", Map.of("operator", "eq", "value", "x"));

        List<ProcessApplicationListSpec.ColumnFilter> filters = ProcessApplicationListSpec.parseFilters(raw);

        assertThat(filters).extracting(ProcessApplicationListSpec.ColumnFilter::field)
                .containsExactly("currentNode", "requestId", "status");
        assertThat(filters).noneMatch(f -> "startTime".equals(f.field()) && "contains".equals(f.operator()));
        assertThat(filters).noneMatch(f -> "title".equals(f.field()));
        assertThat(filters).noneMatch(f -> "unknown".equals(f.field()));
    }

    @Test
    void parseFilters_keepsStartTimeNullOps() {
        Map<String, Map<String, Object>> raw = Map.of(
                "startTime", Map.of("operator", "isNotNull", "value", ""));
        List<ProcessApplicationListSpec.ColumnFilter> filters = ProcessApplicationListSpec.parseFilters(raw);
        assertThat(filters).hasSize(1);
        assertThat(filters.get(0).field()).isEqualTo("startTime");
        assertThat(filters.get(0).operator()).isEqualTo("isNotNull");
    }

    @Test
    void escapeLike_escapesWildcards() {
        assertThat(ProcessApplicationListSpec.escapeLike("a%b_c\\d")).isEqualTo("a\\%b\\_c\\\\d");
    }

    @Test
    void build_returnsNonNullSpecification() {
        assertThat(ProcessApplicationListSpec.build("u1", "RUNNING", "key", List.of(
                new ProcessApplicationListSpec.ColumnFilter("businessKey", "contains", "x")
        ))).isNotNull();
    }
}
