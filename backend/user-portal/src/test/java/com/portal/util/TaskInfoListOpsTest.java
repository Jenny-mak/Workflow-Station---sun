package com.portal.util;

import com.platform.common.list.ListColumnFilter;
import com.portal.dto.TaskInfo;
import com.portal.dto.TaskQueryRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskInfoListOpsTest {

    @Test
    void pageOfSlicesAcrossPages() {
        TaskInfo a = TaskInfo.builder().taskId("1").priority("HIGH").taskName("A").build();
        TaskInfo b = TaskInfo.builder().taskId("2").priority("LOW").taskName("B").build();
        TaskInfo c = TaskInfo.builder().taskId("3").priority("HIGH").taskName("C").build();
        List<TaskInfo> all = List.of(a, b, c);

        assertEquals(List.of(a, b), TaskInfoListOps.pageOf(all, 0, 2));
        assertEquals(List.of(c), TaskInfoListOps.pageOf(all, 1, 2));
    }

    @Test
    void applyColumnFiltersMatchesTaskName() {
        TaskInfo a = TaskInfo.builder().taskId("1").taskName("Approve leave").build();
        TaskInfo b = TaskInfo.builder().taskId("2").taskName("Review invoice").build();
        List<TaskInfo> filtered = TaskInfoListOps.applyColumnFilters(
                List.of(a, b),
                List.of(new ListColumnFilter("taskName", "contains", "leave", null)));
        assertEquals(1, filtered.size());
        assertEquals("1", filtered.get(0).getTaskId());
    }

    @Test
    void applySortingByTaskNameAsc() {
        TaskInfo a = TaskInfo.builder().taskId("1").taskName("Zeta").build();
        TaskInfo b = TaskInfo.builder().taskId("2").taskName("Alpha").build();
        List<TaskInfo> sorted = TaskInfoListOps.applySorting(
                List.of(a, b),
                TaskQueryRequest.builder().sortBy("taskName").sortDirection("ASC").build());
        assertEquals("Alpha", sorted.get(0).getTaskName());
    }

    @Test
    void applySortingByRequestIdAscIsTextOrder() {
        TaskInfo later = TaskInfo.builder().taskId("1").requestId("ATM-DC-PW-000295").build();
        TaskInfo earlier = TaskInfo.builder().taskId("2").requestId("ATM-DC-PW-000100").build();
        List<TaskInfo> sorted = TaskInfoListOps.applySorting(
                List.of(later, earlier),
                TaskQueryRequest.builder().sortBy("requestId").sortDirection("ASC").build());
        assertEquals("ATM-DC-PW-000100", sorted.get(0).getRequestId());
        assertEquals("ATM-DC-PW-000295", sorted.get(1).getRequestId());
    }

    @Test
    void applySortingByFunctionUnitUsesNameThenCode() {
        TaskInfo later = TaskInfo.builder()
                .taskId("1")
                .functionUnitCode("z-code")
                .functionUnitName("Zeta")
                .build();
        TaskInfo earlier = TaskInfo.builder()
                .taskId("2")
                .functionUnitCode("a-code")
                .build();
        List<TaskInfo> sorted = TaskInfoListOps.applySorting(
                List.of(later, earlier),
                TaskQueryRequest.builder().sortBy("functionUnitCode").sortDirection("ASC").build());
        assertEquals("2", sorted.get(0).getTaskId());
        assertEquals("1", sorted.get(1).getTaskId());
    }

    @Test
    void applySortingByAssigneeUsesNameThenId() {
        TaskInfo later = TaskInfo.builder()
                .taskId("1")
                .assignee("z-id")
                .assigneeName("Zeta")
                .build();
        TaskInfo earlier = TaskInfo.builder()
                .taskId("2")
                .assignee("a-id")
                .build();
        List<TaskInfo> sorted = TaskInfoListOps.applySorting(
                List.of(later, earlier),
                TaskQueryRequest.builder().sortBy("assigneeName").sortDirection("ASC").build());
        assertEquals("2", sorted.get(0).getTaskId());
        assertEquals("1", sorted.get(1).getTaskId());
    }
}
