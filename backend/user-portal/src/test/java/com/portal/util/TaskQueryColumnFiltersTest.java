package com.portal.util;

import com.portal.dto.ListColumnFilter;
import com.portal.dto.TaskInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskQueryColumnFiltersTest {

    @AfterEach
    void resetClock() {
        TaskQueryColumnFilters.setClock(null);
    }

    @Test
    void todayFilterMatchesCreateTimeOnSameCalendarDay() {
        TaskQueryColumnFilters.setClock(Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ListRelativeDates.ZONE));
        TaskInfo hit = TaskInfo.builder()
                .taskId("1")
                .createTime(LocalDateTime.of(2026, 8, 21, 10, 0))
                .build();
        TaskInfo miss = TaskInfo.builder()
                .taskId("2")
                .createTime(LocalDateTime.of(2026, 8, 20, 10, 0))
                .build();
        ListColumnFilter today = new ListColumnFilter("createTime", "today", null, null);
        assertTrue(TaskQueryColumnFilters.matches(hit, List.of(today)));
        assertFalse(TaskQueryColumnFilters.matches(miss, List.of(today)));
    }

    @Test
    void unknownDatetimeOperatorThrows() {
        TaskInfo task = TaskInfo.builder()
                .taskId("1")
                .createTime(LocalDateTime.of(2026, 8, 21, 10, 0))
                .build();
        ListColumnFilter bad = new ListColumnFilter("createTime", "contains", "x", null);
        assertThrows(IllegalArgumentException.class,
                () -> TaskQueryColumnFilters.matches(task, List.of(bad)));
    }

    @Test
    void priorityEnumMatchesNumericBands() {
        TaskInfo high = TaskInfo.builder().taskId("1").priority("50").build();
        TaskInfo urgent = TaskInfo.builder().taskId("2").priority("80").build();
        ListColumnFilter highEq = new ListColumnFilter("priority", "eq", "HIGH", null);
        assertTrue(TaskQueryColumnFilters.matches(high, List.of(highEq)));
        assertFalse(TaskQueryColumnFilters.matches(urgent, List.of(highEq)));
    }
}
