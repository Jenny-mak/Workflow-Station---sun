package com.portal.util;

import com.portal.dto.PortalListColumnMeta;
import com.portal.dto.PortalListColumnMeta.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DelegationAuditColumnSpecTest {

    @Test
    void onlyUserColumnsAreGroupable() {
        assertThat(DelegationAuditColumnSpec.columns())
                .filteredOn(PortalListColumnMeta::groupable)
                .extracting(PortalListColumnMeta::field)
                .containsExactlyInAnyOrder("delegatorId", "delegateId");
        assertThat(column("operationType").kind()).isEqualTo(Kind.TEXT);
        assertThat(column("createdAt").kind()).isEqualTo(Kind.DATETIME);
    }

    private static PortalListColumnMeta column(String field) {
        return DelegationAuditColumnSpec.columns().stream()
                .filter(c -> field.equals(c.field()))
                .findFirst()
                .orElseThrow();
    }
}
