package com.portal.component;

import com.portal.dto.TaskInfo;
import com.portal.entity.DelegationRule;
import com.portal.enums.DelegationStatus;
import com.portal.enums.DelegationType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelegationRuleMatcherTest {

    private final DelegationRuleMatcher matcher = new DelegationRuleMatcher();

    @Test
    void allMatchesWithinWindow() {
        DelegationRule rule = baseRule(DelegationType.ALL);
        TaskInfo task = TaskInfo.builder()
                .processDefinitionKey("leave")
                .priority("NORMAL")
                .build();
        assertTrue(matcher.matches(task, rule));
    }

    @Test
    void partialRequiresProcessType() {
        DelegationRule rule = baseRule(DelegationType.PARTIAL);
        rule.setProcessTypes(List.of("leave_process"));
        TaskInfo ok = TaskInfo.builder().processDefinitionKey("leave_process").priority("HIGH").build();
        TaskInfo no = TaskInfo.builder().processDefinitionKey("other").priority("HIGH").build();
        assertTrue(matcher.matches(ok, rule));
        assertFalse(matcher.matches(no, rule));
        rule.setProcessTypes(List.of());
        assertFalse(matcher.matches(ok, rule));
    }

    @Test
    void urgentRequiresUrgentPriority() {
        DelegationRule rule = baseRule(DelegationType.URGENT);
        assertTrue(matcher.matches(TaskInfo.builder().priority("URGENT").build(), rule));
        assertTrue(matcher.matches(TaskInfo.builder().priority("CRITICAL").build(), rule));
        assertFalse(matcher.matches(TaskInfo.builder().priority("NORMAL").build(), rule));
    }

    @Test
    void priorityFilterAppliedWhenPresent() {
        DelegationRule rule = baseRule(DelegationType.ALL);
        rule.setPriorityFilter(List.of("HIGH"));
        assertTrue(matcher.matches(TaskInfo.builder().priority("HIGH").build(), rule));
        assertFalse(matcher.matches(TaskInfo.builder().priority("LOW").build(), rule));
    }

    @Test
    void suspendedNeverMatches() {
        DelegationRule rule = baseRule(DelegationType.ALL);
        rule.setStatus(DelegationStatus.SUSPENDED);
        assertFalse(matcher.matches(TaskInfo.builder().priority("NORMAL").build(), rule));
    }

    private static DelegationRule baseRule(DelegationType type) {
        return DelegationRule.builder()
                .delegatorId("A")
                .delegateId("B")
                .delegationType(type)
                .status(DelegationStatus.ACTIVE)
                .startTime(LocalDateTime.now().minusDays(1))
                .endTime(LocalDateTime.now().plusDays(1))
                .build();
    }
}
