package com.portal.util;

import com.portal.dto.PortalListColumnMeta;
import com.portal.dto.PortalListColumnMeta.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MyApplicationColumnSpecTest {

    @Test
    void onlyClosedValueColumnsGroup() {
        assertThat(MyApplicationColumnSpec.columns())
                .filteredOn(PortalListColumnMeta::groupable)
                .extracting(PortalListColumnMeta::field)
                .containsExactly("currentAssignee", "status");
        assertThat(column("businessKey").groupable()).isFalse();
        assertThat(column("startTime").groupable()).isFalse();
        assertThat(column("requestId").filterable()).isFalse();
    }

    @Test
    void kindsFollowStoredTypes() {
        assertThat(column("currentAssignee").kind()).isEqualTo(Kind.USER);
        assertThat(column("status").kind()).isEqualTo(Kind.ENUM);
        assertThat(column("startTime").kind()).isEqualTo(Kind.DATETIME);
        assertThat(column("businessKey").kind()).isEqualTo(Kind.TEXT);
        assertThat(column("status").options()).extracting(PortalListColumnMeta.Option::value)
                .containsExactly("RUNNING", "COMPLETED", "WITHDRAWN", "REJECTED");
    }

    private static PortalListColumnMeta column(String field) {
        return MyApplicationColumnSpec.columns().stream()
                .filter(c -> field.equals(c.field()))
                .findFirst()
                .orElseThrow();
    }
}
