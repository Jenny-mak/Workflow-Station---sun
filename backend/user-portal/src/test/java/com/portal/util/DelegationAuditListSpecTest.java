package com.portal.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DelegationAuditListSpec")
class DelegationAuditListSpecTest {

    @Test
    void parseFilters_whitelist() {
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
        raw.put("operationType", Map.of("operator", "eq", "value", "CREATE"));
        raw.put("createdAt", Map.of("operator", "isNull", "value", ""));
        raw.put("taskId", Map.of("operator", "eq", "value", "t1"));

        List<PortalColumnFilterSupport.ColumnFilter> filters = DelegationAuditListSpec.parseFilters(raw);

        assertThat(filters).extracting(PortalColumnFilterSupport.ColumnFilter::field)
                .containsExactly("operationType", "createdAt");
    }

    @Test
    void withSort_defaultsCreatedAtDesc() {
        var pageable = DelegationAuditListSpec.withSort(PageRequest.of(0, 5), null, null, null);
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void sanitizeGroupBy_whitelist() {
        assertThat(DelegationAuditListSpec.sanitizeGroupBy("operationResult")).isEqualTo("operationResult");
        assertThat(DelegationAuditListSpec.sanitizeGroupBy("ipAddress")).isNull();
    }

    @Test
    void build_returnsNonNullSpecification() {
        assertThat(DelegationAuditListSpec.build("u1", List.of(
                new PortalColumnFilterSupport.ColumnFilter("delegatorId", "eq", "u1")
        ))).isNotNull();
    }
}
