package com.portal.util;

import com.portal.dto.PortalListColumnMeta;
import com.portal.dto.PortalListColumnMeta.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionRequestColumnSpecTest {

    @Test
    void closedValueColumnsAreGroupable() {
        assertThat(PermissionRequestColumnSpec.columns())
                .filteredOn(PortalListColumnMeta::groupable)
                .extracting(PortalListColumnMeta::field)
                .containsExactlyInAnyOrder("requestType", "status", "applicantId", "submittedByUserId");
    }

    @Test
    void targetNameIsFilterableText() {
        assertThat(column("targetName").kind()).isEqualTo(Kind.TEXT);
        assertThat(column("targetName").filterable()).isTrue();
        assertThat(column("createdAt").kind()).isEqualTo(Kind.DATETIME);
        assertThat(column("requestType").kind()).isEqualTo(Kind.ENUM);
    }

    private static PortalListColumnMeta column(String field) {
        return PermissionRequestColumnSpec.columns().stream()
                .filter(c -> field.equals(c.field()))
                .findFirst()
                .orElseThrow();
    }
}
