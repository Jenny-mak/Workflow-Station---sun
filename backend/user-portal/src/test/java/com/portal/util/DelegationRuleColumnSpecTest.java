package com.portal.util;

import com.portal.dto.PortalListColumnMeta;
import com.portal.dto.PortalListColumnMeta.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DelegationRuleColumnSpecTest {

    @Test
    void statusAndTypeAreGroupableEnums() {
        assertThat(DelegationRuleColumnSpec.columns())
                .filteredOn(PortalListColumnMeta::groupable)
                .extracting(PortalListColumnMeta::field)
                .containsExactlyInAnyOrder("delegationType", "status", "delegateId");
        assertThat(column("status").kind()).isEqualTo(Kind.ENUM);
        assertThat(column("delegationType").kind()).isEqualTo(Kind.ENUM);
        assertThat(column("delegateId").kind()).isEqualTo(Kind.USER);
    }

    @Test
    void datetimeColumnsExposeRelativeOperators() {
        assertThat(column("startTime").operators()).contains("today", "between");
        assertThat(column("status").operators()).containsExactly("eq", "ne", "isNull", "isNotNull");
    }

    private static PortalListColumnMeta column(String field) {
        return DelegationRuleColumnSpec.columns().stream()
                .filter(c -> field.equals(c.field()))
                .findFirst()
                .orElseThrow();
    }
}
