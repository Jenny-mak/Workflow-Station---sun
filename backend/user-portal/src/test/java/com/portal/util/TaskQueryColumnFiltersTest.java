package com.portal.util;

import com.platform.common.list.ListColumnFilter;
import com.platform.common.list.ListRelativeDates;
import com.portal.dto.TaskInfo;
import com.portal.dto.TaskQueryRequest;
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

    @Test
    void toolbarKeywordMatchesVisibleListCells() {
        TaskInfo task = TaskInfo.builder()
                .taskId("1")
                .taskName("Approve")
                .currentStepName("Case Submission")
                .requestId("ATM-DC-PW-0002")
                .processDefinitionName("Leave Flow")
                .processDefinitionKey("atm-20260623-g")
                .initiatorName("Developer Test")
                .description("weekend overtime")
                .assignmentType("USER")
                .createTime(LocalDateTime.of(2026, 8, 21, 10, 0))
                .dueDate(LocalDateTime.of(2026, 9, 1, 18, 30))
                .build();
        assertTrue(TaskQueryColumnFilters.toolbarKeywordMatches(task, "ATM-DC"));
        assertTrue(TaskQueryColumnFilters.toolbarKeywordMatches(task, "Approve"));
        assertTrue(TaskQueryColumnFilters.toolbarKeywordMatches(task, "2026-08-21"));
        assertTrue(TaskQueryColumnFilters.toolbarKeywordMatches(task, "10:00"));
        assertTrue(TaskQueryColumnFilters.toolbarKeywordMatches(task, "USER"));
        assertFalse(TaskQueryColumnFilters.toolbarKeywordMatches(task, "2026-09-01"));
        assertFalse(TaskQueryColumnFilters.toolbarKeywordMatches(task, "18:30"));
        assertFalse(TaskQueryColumnFilters.toolbarKeywordMatches(task, "Case"));
        assertFalse(TaskQueryColumnFilters.toolbarKeywordMatches(task, "Leave"));
        assertFalse(TaskQueryColumnFilters.toolbarKeywordMatches(task, "Developer"));
        assertFalse(TaskQueryColumnFilters.toolbarKeywordMatches(task, "atm-2026"));
        assertFalse(TaskQueryColumnFilters.toolbarKeywordMatches(task, "overtime"));
        assertFalse(TaskQueryColumnFilters.toolbarKeywordMatches(task, "zzz"));
    }

    @Test
    void requestIdContainsFilterMatchesEnrichedValue() {
        TaskInfo hit = TaskInfo.builder().taskId("1").requestId("ATM-DC-PW-000295").build();
        TaskInfo miss = TaskInfo.builder().taskId("2").requestId("HR-2026-001").build();
        ListColumnFilter contains = new ListColumnFilter("requestId", "contains", "ATM-DC", null);
        assertTrue(TaskQueryColumnFilters.matches(hit, List.of(contains)));
        assertFalse(TaskQueryColumnFilters.matches(miss, List.of(contains)));
    }

    @Test
    void functionUnitFilterMatchesNameOrCode() {
        TaskInfo named = TaskInfo.builder()
                .taskId("1")
                .functionUnitCode("help_pr")
                .functionUnitName("Purchase Request")
                .build();
        TaskInfo codeOnly = TaskInfo.builder().taskId("2").functionUnitCode("expense").build();
        assertTrue(TaskQueryColumnFilters.matches(named, List.of(
                new ListColumnFilter("functionUnitCode", "contains", "Purchase", null))));
        assertTrue(TaskQueryColumnFilters.matches(named, List.of(
                new ListColumnFilter("functionUnitCode", "eq", "help_pr", null))));
        assertFalse(TaskQueryColumnFilters.matches(codeOnly, List.of(
                new ListColumnFilter("functionUnitCode", "contains", "Purchase", null))));
        assertTrue(TaskQueryColumnFilters.toolbarKeywordMatches(named, "help_pr"));
        assertTrue(TaskQueryColumnFilters.toolbarKeywordMatches(named, "Purchase"));
    }

    @Test
    void assigneeNameFilterMatchesDisplayNameOrUserId() {
        TaskInfo named = TaskInfo.builder()
                .taskId("1")
                .assignee("u-ada")
                .assigneeName("Ada Chen")
                .claimedByCurrentUser(true)
                .build();
        TaskInfo idOnly = TaskInfo.builder().taskId("2").assignee("u-bo").build();
        TaskInfo other = TaskInfo.builder()
                .taskId("3")
                .assignee("u-other")
                .assigneeName("Bo Li")
                .build();
        assertTrue(TaskQueryColumnFilters.matches(named, List.of(
                new ListColumnFilter("assigneeName", "contains", "Ada", null))));
        assertTrue(TaskQueryColumnFilters.matches(named, List.of(
                new ListColumnFilter("assigneeName", "eq", "u-ada", null))));
        assertTrue(TaskQueryColumnFilters.matches(idOnly, List.of(
                new ListColumnFilter("assigneeName", "contains", "u-bo", null))));
        assertFalse(TaskQueryColumnFilters.matches(other, List.of(
                new ListColumnFilter("assigneeName", "contains", "Ada", null))));
        assertTrue(TaskQueryColumnFilters.toolbarKeywordMatches(named, "Ada"));
        assertTrue(TaskQueryColumnFilters.toolbarKeywordMatches(named, "u-ada"));
        assertTrue(TaskQueryColumnFilters.matches(named, List.of(
                new ListColumnFilter("assigneeName", "contains", "You", null))));
        assertTrue(TaskQueryColumnFilters.matches(named, List.of(
                new ListColumnFilter("assigneeName", "eq", "我", null))));
        assertTrue(TaskQueryColumnFilters.toolbarKeywordMatches(named, "You"));
        assertFalse(TaskQueryColumnFilters.matches(other, List.of(
                new ListColumnFilter("assigneeName", "contains", "You", null))));
    }

    @Test
    void unknownAssigneeFilterFieldStillThrows() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> TaskQueryColumnFilters.normalize(List.of(
                        new ListColumnFilter("assignee", "contains", "Ada", null))));
        assertTrue(thrown.getMessage().contains("Unknown todo-task filter field"));
    }

    @Test
    void derivedColumnsNeededForKeywordFilterAndSort() {
        assertTrue(TaskQueryColumnFilters.needsPortalDerivedTaskColumns(
                TaskQueryRequest.builder().keyword("ATM").build()));
        assertTrue(TaskQueryColumnFilters.needsPortalDerivedTaskColumns(
                TaskQueryRequest.builder()
                        .filters(List.of(new ListColumnFilter("functionUnitCode", "contains", "help", null)))
                        .build()));
        assertTrue(TaskQueryColumnFilters.needsPortalDerivedTaskColumns(
                TaskQueryRequest.builder().sortBy("requestId").build()));
        assertFalse(TaskQueryColumnFilters.needsPortalDerivedTaskColumns(
                TaskQueryRequest.builder()
                        .filters(List.of(new ListColumnFilter("taskName", "contains", "Approve", null)))
                        .sortBy("createTime")
                        .build()));
    }
}
