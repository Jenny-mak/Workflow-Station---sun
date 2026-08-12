package com.portal.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PermissionRequestListSpec")
class PermissionRequestListSpecTest {

    @Test
    void parseFilters_mapsCamelAndSnake() {
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
        raw.put("requestType", Map.of("operator", "eq", "value", "BUSINESS_UNIT_JOIN"));
        raw.put("business_unit_name", Map.of("operator", "contains", "value", "Sales"));
        raw.put("createdAt", Map.of("operator", "isNotNull", "value", ""));
        raw.put("unknown", Map.of("operator", "eq", "value", "x"));

        List<PortalColumnFilterSupport.ColumnFilter> filters = PermissionRequestListSpec.parseFilters(raw);

        assertThat(filters).extracting(PortalColumnFilterSupport.ColumnFilter::field)
                .containsExactly("request_type", "business_unit_name", "created_at");
    }

    @Test
    void appendFilterSql_buildsAndClauses() {
        List<Object> args = new ArrayList<>();
        String sql = PermissionRequestListSpec.appendFilterSql(List.of(
                new PortalColumnFilterSupport.ColumnFilter("status", "eq", "PENDING"),
                new PortalColumnFilterSupport.ColumnFilter("reason", "contains", "need"),
                new PortalColumnFilterSupport.ColumnFilter("created_at", "isNotNull", "")
        ), args);

        assertThat(sql).contains("AND LOWER(COALESCE(status::text, '')) = ?");
        assertThat(sql).contains("LIKE ? ESCAPE");
        assertThat(sql).contains("created_at IS NOT NULL");
        assertThat(args).containsExactly("pending", "%need%");
    }

    @Test
    void resolveOrderBy_whitelistAndGroup() {
        String order = PermissionRequestListSpec.resolveOrderBy("status", "ASC", "requestType");
        assertThat(order).startsWith("request_type ASC");
        assertThat(order).contains("status ASC");

        String unsafe = PermissionRequestListSpec.resolveOrderBy("hack;drop", "ASC", null);
        assertThat(unsafe).startsWith("created_at DESC");
    }

    @Test
    void sanitizeGroupBy_whitelist() {
        assertThat(PermissionRequestListSpec.sanitizeGroupBy("roleName")).isEqualTo("role_name");
        assertThat(PermissionRequestListSpec.sanitizeGroupBy("drop table")).isNull();
    }
}
