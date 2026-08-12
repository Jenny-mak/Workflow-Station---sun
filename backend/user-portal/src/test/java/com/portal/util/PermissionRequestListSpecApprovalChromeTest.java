package com.portal.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PermissionRequestListSpec approval chrome")
class PermissionRequestListSpecApprovalChromeTest {

    @Test
    void requestTargetFilter_appendsOrAcrossNames() {
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
        raw.put("requestTarget", Map.of("operator", "contains", "value", "Finance"));
        List<Object> args = new ArrayList<>();
        String sql = PermissionRequestListSpec.appendRequestTargetFilterSql(raw, args);
        assertThat(sql).contains("business_unit_name").contains("virtual_group_name").contains("OR");
        assertThat(args).hasSize(3);
        assertThat(PermissionRequestListSpec.withoutRequestTarget(raw)).doesNotContainKey("requestTarget");
    }

    @Test
    void resolveOrderBy_requestTargetUsesCoalesceExpr() {
        String order = PermissionRequestListSpec.resolveOrderBy("requestTarget", "ASC", null);
        assertThat(order).contains(PermissionRequestListSpec.REQUEST_TARGET_EXPR);
        assertThat(order).contains("ASC");
    }

    @Test
    void sanitizeGroupBy_requestTarget() {
        assertThat(PermissionRequestListSpec.sanitizeGroupBy("requestTarget"))
                .isEqualTo(PermissionRequestListSpec.REQUEST_TARGET_EXPR);
    }
}
