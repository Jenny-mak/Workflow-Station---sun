package com.portal.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProcessDraftListSpec")
class ProcessDraftListSpecTest {

    @Test
    void parseFilters_skipsProcessDefinitionNameForSpec() {
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
        raw.put("processDefinitionName", Map.of("operator", "contains", "value", "Leave"));
        raw.put("updatedAt", Map.of("operator", "isNotNull", "value", ""));
        raw.put("unknown", Map.of("operator", "eq", "value", "x"));

        List<PortalColumnFilterSupport.ColumnFilter> filters = ProcessDraftListSpec.parseFilters(raw);

        assertThat(filters).extracting(PortalColumnFilterSupport.ColumnFilter::field)
                .containsExactly("updatedAt");
        assertThat(ProcessDraftListSpec.hasProcessDefinitionNameFilter(raw)).isTrue();
        assertThat(ProcessDraftListSpec.processDefinitionNameFilter(raw))
                .extracting(PortalColumnFilterSupport.ColumnFilter::value)
                .isEqualTo("Leave");
    }

    @Test
    void parseFilters_acceptsUpdatedAtOn() {
        Map<String, Map<String, Object>> raw = Map.of(
                "updatedAt", Map.of("operator", "on", "value", "2026-08-14"));
        List<PortalColumnFilterSupport.ColumnFilter> filters = ProcessDraftListSpec.parseFilters(raw);
        assertThat(filters).extracting(PortalColumnFilterSupport.ColumnFilter::field)
                .containsExactly("updatedAt");
        assertThat(filters.get(0).operator()).isEqualTo("on");
    }

    @Test
    void parseFilters_rejectsUnsupportedDateOperator() {
        Map<String, Map<String, Object>> raw = Map.of(
                "updatedAt", Map.of("operator", "contains", "value", "2024"));
        assertThatThrownBy(() -> ProcessDraftListSpec.parseFilters(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("updatedAt");
    }

    @Test
    void columns_declareNameAndUpdatedAt() {
        assertThat(PortalListColumnMeta.find(ProcessDraftListSpec.COLUMNS, "processDefinitionName").kind())
                .isEqualTo(PortalListColumnMeta.Kind.TEXT);
        assertThat(PortalListColumnMeta.find(ProcessDraftListSpec.COLUMNS, "updatedAt").kind())
                .isEqualTo(PortalListColumnMeta.Kind.DATETIME);
    }

    @Test
    void sanitizeGroupBy_whitelist() {
        assertThat(ProcessDraftListSpec.sanitizeGroupBy("processDefinitionKey")).isEqualTo("processDefinitionKey");
        assertThat(ProcessDraftListSpec.sanitizeGroupBy("hack")).isNull();
    }

    @Test
    void withSort_defaultsUpdatedAtDesc() {
        var pageable = ProcessDraftListSpec.withSort(PageRequest.of(0, 10), null, null, null);
        assertThat(pageable.getSort().getOrderFor("updatedAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void withSort_mapsProcessDefinitionName() {
        var pageable = ProcessDraftListSpec.withSort(PageRequest.of(0, 10), "processDefinitionName", "ASC", null);
        assertThat(pageable.getSort().getOrderFor("processDefinitionKey")).isNotNull();
    }

    @Test
    void build_returnsNonNullSpecification() {
        assertThat(ProcessDraftListSpec.build("u1", List.of(
                new PortalColumnFilterSupport.ColumnFilter("processDefinitionKey", "contains", "x")
        ))).isNotNull();
    }
}
