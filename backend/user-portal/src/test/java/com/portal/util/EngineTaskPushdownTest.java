package com.portal.util;

import com.platform.common.list.ListColumnFilter;
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
    void assignmentTypesForceFullScan() {
        TaskQueryRequest request = TaskQueryRequest.builder()
                .assignmentTypes(List.of("USER"))
                .build();
        assertFalse(EngineTaskPushdown.canFullyPush(request));
    }

    @Test
    void toolbarKeywordForcesFullScan() {
        TaskQueryRequest request = TaskQueryRequest.builder()
                .keyword("请假")
                .build();
        assertFalse(EngineTaskPushdown.canFullyPush(request));
    }

    @Test
    void requestIdTextFilterForcesFullScan() {
        TaskQueryRequest request = TaskQueryRequest.builder()
                .filters(List.of(new ListColumnFilter("requestId", "contains", "ATM-DC", null)))
                .build();
        assertFalse(EngineTaskPushdown.canFullyPush(request));
    }

    @Test
    void requestIdTextSortForcesFullScan() {
        TaskQueryRequest request = TaskQueryRequest.builder()
                .sortBy("requestId")
                .sortDirection("ASC")
                .build();
        assertFalse(EngineTaskPushdown.canFullyPush(request));
    }

    @Test
    void functionUnitFilterAndSortForceFullScan() {
        assertFalse(EngineTaskPushdown.canFullyPush(TaskQueryRequest.builder()
                .filters(List.of(new ListColumnFilter("functionUnitCode", "contains", "help", null)))
                .build()));
        assertFalse(EngineTaskPushdown.canFullyPush(TaskQueryRequest.builder()
                .sortBy("functionUnitCode")
                .sortDirection("ASC")
                .build()));
    }

    @Test
    void assigneeNameFilterAndSortForceFullScan() {
        assertFalse(EngineTaskPushdown.canFullyPush(TaskQueryRequest.builder()
                .filters(List.of(new ListColumnFilter("assigneeName", "contains", "Ada", null)))
                .build()));
        assertFalse(EngineTaskPushdown.canFullyPush(TaskQueryRequest.builder()
                .sortBy("assigneeName")
                .sortDirection("ASC")
                .build()));
    }

    @Test
    void toolbarPriorityInFilterForcesFullScan() {
        TaskQueryRequest request = TaskQueryRequest.builder()
                .filters(List.of(new ListColumnFilter("priority", "in", "HIGH,URGENT", null)))
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
    void processNameAndPriorityAndDateArePushableTogether() {
        TaskQueryRequest request = TaskQueryRequest.builder()
                .filters(List.of(
                        new ListColumnFilter("processDefinitionName", "contains", "Leave", null),
                        new ListColumnFilter("priority", "eq", "HIGH", null),
                        new ListColumnFilter("createTime", "today", null, null)))
                .sortBy("dueDate")
                .sortDirection("asc")
                .build();
        assertTrue(EngineTaskPushdown.canFullyPush(request));
        EngineTaskPushdown.Criteria criteria = EngineTaskPushdown.from(request);
        assertEquals("Leave", criteria.processDefinitionNameLike());
        assertEquals(50, criteria.priorityMin());
        assertEquals(74, criteria.priorityMax());
        assertTrue(criteria.createdAfter() != null);
        assertTrue(criteria.createdBefore() != null);
        assertEquals("dueDate", criteria.sortBy());
    }

    @Test
    void currentStepNamePushesAsTaskName() {
        TaskQueryRequest request = TaskQueryRequest.builder()
                .filters(List.of(new ListColumnFilter("currentStepName", "contains", "Review", null)))
                .build();
        assertTrue(EngineTaskPushdown.canFullyPush(request));
        assertEquals("Review", EngineTaskPushdown.from(request).taskNameLike());
    }

    @Test
    void taskNameAndCurrentStepTogetherForceFullScan() {
        TaskQueryRequest request = TaskQueryRequest.builder()
                .filters(List.of(
                        new ListColumnFilter("taskName", "contains", "Approve", null),
                        new ListColumnFilter("currentStepName", "contains", "Review", null)))
                .build();
        assertFalse(EngineTaskPushdown.canFullyPush(request));
    }
}
