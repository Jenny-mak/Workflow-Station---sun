package com.portal.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DelegationRuleListSpec")
class DelegationRuleListSpecTest {

    @Test
    void parseFilters_whitelist() {
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
        raw.put("delegateId", Map.of("operator", "eq", "value", "u2"));
        raw.put("status", Map.of("operator", "eq", "value", "ACTIVE"));
        raw.put("startTime", Map.of("operator", "contains", "value", "2024"));
        raw.put("hack", Map.of("operator", "eq", "value", "x"));

        List<PortalColumnFilterSupport.ColumnFilter> filters = DelegationRuleListSpec.parseFilters(raw);

        assertThat(filters).extracting(PortalColumnFilterSupport.ColumnFilter::field)
                .containsExactly("delegateId", "status");
        assertThat(filters).noneMatch(f -> "startTime".equals(f.field()));
        assertThat(filters).noneMatch(f -> "hack".equals(f.field()));
    }

    @Test
    void sanitizeGroupBy_whitelist() {
        assertThat(DelegationRuleListSpec.sanitizeGroupBy("status")).isEqualTo("status");
        assertThat(DelegationRuleListSpec.sanitizeGroupBy("createdAt")).isNull();
    }

    @Test
    void withSort_groupThenRuntime() {
        var pageable = DelegationRuleListSpec.withSort(
                PageRequest.of(0, 10), "delegateId", "DESC", "status");
        var orders = pageable.getSort().toList();
        assertThat(orders.get(0).getProperty()).isEqualTo("status");
        assertThat(orders.get(0).getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(orders.get(1).getProperty()).isEqualTo("delegateId");
    }

    @Test
    void build_returnsNonNullSpecification() {
        assertThat(DelegationRuleListSpec.build("delegator-1", List.of(
                new PortalColumnFilterSupport.ColumnFilter("status", "eq", "ACTIVE")
        ))).isNotNull();
    }
}
