package com.portal.util;

import com.portal.dto.ListColumnFilter;
import com.portal.dto.TaskQueryRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineTaskPushdownTest {

    @Test
    void emptyRequestIsFullyPushable() {
        assertTrue(EngineTaskPushdown.canFullyPush(TaskQueryRequest.builder().build()));
    }

    @Test
    void singleTaskNameContainsIsPushable() {
        TaskQueryRequest request = TaskQueryRequest.builder()
                .filters(List.of(new ListColumnFilter("taskName", "contains", "Approve", null)))
                .sortBy("createTime")
                .sortDirection("DESC")
                .build();
        assertTrue(EngineTaskPushdown.canFullyPush(request));
        EngineTaskPushdown.Criteria criteria = EngineTaskPushdown.from(request);
        assertEquals("Approve", criteria.taskNameLike());
        assertEquals("createTime", criteria.sortBy());
    }

    @Test
    void groupByForcesFullScan() {
        TaskQueryRequest request = TaskQueryRequest.builder()
                .groupBy("priority")
                .build();
        assertFalse(EngineTaskPushdown.canFullyPush(request));
    }

    @Test
    void assignmentTypesForceFullScan() {
        TaskQueryRequest request = TaskQueryRequest.builder()
                .assignmentTypes(List.of("USER"))
                .build();
        assertFalse(EngineTaskPushdown.canFullyPush(request));
    }

    @Test
    void sortOnlyCriteriaHasNoFilterFragments() {
        EngineTaskPushdown.Criteria criteria = EngineTaskPushdown.from(
                TaskQueryRequest.builder().sortBy("createTime").sortDirection("asc").build());
        assertTrue(criteria.hasAny());
        assertFalse(criteria.hasFilterFragments());
    }

    @Test
    void initiatorFilterIsNotPushable() {
        TaskQueryRequest request = TaskQueryRequest.builder()
                .filters(List.of(new ListColumnFilter("initiatorName", "contains", "Ada", null)))
                .build();
        assertFalse(EngineTaskPushdown.canFullyPush(request));
    }
}
