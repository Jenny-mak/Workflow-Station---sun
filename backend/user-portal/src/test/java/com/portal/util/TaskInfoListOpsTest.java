package com.portal.util;

import com.portal.dto.ListColumnFilter;
import com.portal.dto.PortalListGroup;
import com.portal.dto.TaskInfo;
import com.portal.dto.TaskQueryRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskInfoListOpsTest {

    @Test
    void pageOfSlicesAndGroupsByPriority() {
        TaskInfo a = TaskInfo.builder().taskId("1").priority("HIGH").taskName("A").build();
        TaskInfo b = TaskInfo.builder().taskId("2").priority("LOW").taskName("B").build();
        TaskInfo c = TaskInfo.builder().taskId("3").priority("HIGH").taskName("C").build();
        List<TaskInfo> all = List.of(a, b, c);

        assertEquals(List.of(a, b), TaskInfoListOps.pageOf(all, 0, 2));
        assertEquals(List.of(c), TaskInfoListOps.pageOf(all, 1, 2));

        List<PortalListGroup> groups = TaskInfoListOps.groupsOf(all, "priority");
        assertEquals(2, groups.size());
        assertEquals("HIGH", groups.get(0).label());
        assertEquals(2L, groups.get(0).count());
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
}
