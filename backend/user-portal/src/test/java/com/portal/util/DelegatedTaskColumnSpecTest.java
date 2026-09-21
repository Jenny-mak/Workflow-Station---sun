package com.portal.util;

import com.platform.common.list.ListColumnMeta;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DelegatedTaskColumnSpecTest {

    @Test
    void insertsDelegatorAndTargetKindAfterTaskName() {
        assertThat(DelegatedTaskColumnSpec.columns())
                .extracting(ListColumnMeta::field)
                .containsSubsequence("taskName", "delegatorId", "delegatedTargetType", "assignmentType");
    }
}
