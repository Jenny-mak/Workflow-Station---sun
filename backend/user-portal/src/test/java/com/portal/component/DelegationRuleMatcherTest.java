package com.portal.component;

import com.portal.dto.TaskInfo;
import com.portal.entity.DelegationRule;
import com.portal.enums.DelegateTargetType;
import com.portal.enums.DelegationStatus;
import com.portal.enums.DelegationType;
import com.portal.repository.DelegationRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DelegationRuleMatcher")
class DelegationRuleMatcherTest {

    @Mock
    private DelegationRuleRepository repository;
    @Mock
    private WorkspaceTaskFilterComponent workspace;

    private DelegationRuleMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new DelegationRuleMatcher(repository, workspace);
    }

    @Test
    void userRuleMatchesAssignedTaskInWindow() {
        DelegationRule rule = userRule("A", "B", DelegationType.ALL);
        TaskInfo task = TaskInfo.builder()
                .taskId("t1")
                .assignee("A")
                .processDefinitionKey("leave")
                .priority("NORMAL")
                .build();
        assertTrue(matcher.ruleMatches(task, rule));
        assertTrue(matcher.actorMatchesRule("B", null, rule));
        assertFalse(matcher.actorMatchesRule("C", null, rule));
    }

    @Test
    void partialRequiresProcessDefinitionKey() {
        DelegationRule rule = userRule("A", "B", DelegationType.PARTIAL);
        rule.setProcessTypes(List.of("leave", "expense"));
        TaskInfo leave = TaskInfo.builder().assignee("A").processDefinitionKey("leave").build();
        TaskInfo other = TaskInfo.builder().assignee("A").processDefinitionKey("other").build();
        assertTrue(matcher.ruleMatches(leave, rule));
        assertFalse(matcher.ruleMatches(other, rule));
    }

    @Test
    void partialMatchesFunctionUnitCodeWhenBpmnKeyDiffers() {
        DelegationRule rule = userRule("A", "B", DelegationType.PARTIAL);
        rule.setProcessTypes(List.of("leave-request-delegation-20260915-tj3oye"));
        TaskInfo leave = TaskInfo.builder()
                .assignee("A")
                .processDefinitionKey("Process_LeaveDelegation")
                .functionUnitCode("leave-request-delegation-20260915-tj3oye")
                .build();
        TaskInfo otherFu = TaskInfo.builder()
                .assignee("A")
                .processDefinitionKey("Process_LeaveDelegation")
                .functionUnitCode("owner-demo-20260907-gehibh")
                .build();
        assertTrue(matcher.ruleMatches(leave, rule));
        assertFalse(matcher.ruleMatches(otherFu, rule));
    }

    @Test
    void partialMatchesFunctionUnitCodeFromEngineVariables() {
        DelegationRule rule = userRule("A", "B", DelegationType.PARTIAL);
        rule.setProcessTypes(List.of("leave-request-delegation-20260915-tj3oye"));
        TaskInfo leave = TaskInfo.builder()
                .assignee("A")
                .processDefinitionKey("Process_LeaveDelegation")
                .variables(Map.of("functionUnitCode", "leave-request-delegation-20260915-tj3oye"))
                .build();
        TaskInfo otherFu = TaskInfo.builder()
                .assignee("A")
                .processDefinitionKey("Process_LeaveDelegation")
                .variables(Map.of("functionUnitCode", "owner-demo-20260907-gehibh"))
                .build();
        assertTrue(matcher.ruleMatches(leave, rule));
        assertFalse(matcher.ruleMatches(otherFu, rule));
    }

    @Test
    void urgentMatchesUrgentAndCriticalOnly() {
        DelegationRule rule = userRule("A", "B", DelegationType.URGENT);
        assertTrue(matcher.ruleMatches(TaskInfo.builder().assignee("A").priority("URGENT").build(), rule));
        assertTrue(matcher.ruleMatches(TaskInfo.builder().assignee("A").priority("CRITICAL").build(), rule));
        assertFalse(matcher.ruleMatches(TaskInfo.builder().assignee("A").priority("HIGH").build(), rule));
    }

    @Test
    void suspendedOrExpiredDoesNotMatch() {
        DelegationRule suspended = userRule("A", "B", DelegationType.ALL);
        suspended.setStatus(DelegationStatus.SUSPENDED);
        DelegationRule expired = userRule("A", "B", DelegationType.ALL);
        expired.setEndTime(LocalDateTime.now().minusDays(1));
        TaskInfo task = TaskInfo.builder().assignee("A").priority("NORMAL").build();
        assertFalse(matcher.ruleMatches(task, suspended));
        assertFalse(matcher.ruleMatches(task, expired));
    }

    @Test
    void buRoleActorRequiresWorkspacePair() {
        DelegationRule rule = DelegationRule.builder()
                .delegatorId("A")
                .delegateTargetType(DelegateTargetType.BU_ROLE)
                .delegateBuCode("HK")
                .delegateRoleCode("APPROVER")
                .delegationType(DelegationType.ALL)
                .status(DelegationStatus.ACTIVE)
                .build();
        when(workspace.workspacePairMatches("HK", "APPROVER", "user-b")).thenReturn(true);
        when(workspace.workspacePairMatches("HK", "APPROVER", "user-c")).thenReturn(false);
        assertTrue(matcher.actorMatchesRule("user-b", null, rule));
        assertFalse(matcher.actorMatchesRule("user-c", null, rule));
    }

    @Test
    void standingMatchLoadsUserRulesAndRequiresAssigneeDelegator() {
        DelegationRule rule = userRule("A", "B", DelegationType.ALL);
        when(repository.findActiveDelegationsForDelegate(eq("B"), any())).thenReturn(List.of(rule));
        when(workspace.activeWorkspaceBuCode()).thenReturn(Optional.empty());
        TaskInfo task = TaskInfo.builder().taskId("t1").assignee("A").priority("NORMAL").build();
        assertTrue(matcher.matchesStanding(task, "B", null));
        assertFalse(matcher.matchesStanding(task, "C", null));
    }

    @Test
    void unassignedTaskIsNotDelegatable() {
        TaskInfo task = TaskInfo.builder().taskId("t1").assignee(null).build();
        assertFalse(matcher.isAssignedDelegatableTask(task));
    }

    private static DelegationRule userRule(String delegator, String delegate, DelegationType type) {
        return DelegationRule.builder()
                .delegatorId(delegator)
                .delegateId(delegate)
                .delegateTargetType(DelegateTargetType.USER)
                .delegationType(type)
                .status(DelegationStatus.ACTIVE)
                .build();
    }
}
