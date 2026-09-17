package com.portal.component;

import com.portal.client.WorkflowEngineClient;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskPermissionEvaluator standing delegatee")
class TaskPermissionEvaluatorStandingDelegateTest {

    @Mock
    private DelegationRuleRepository repository;
    @Mock
    private WorkflowEngineClient workflowEngineClient;
    @Mock
    private WorkspaceTaskFilterComponent workspace;

    private TaskPermissionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new TaskPermissionEvaluator(
                new DelegationRuleMatcher(repository, workspace),
                workflowEngineClient, workspace);
    }

    @Test
    void standingUserDelegateeCanProcessWithoutExtendedDelegation() {
        DelegationRule rule = DelegationRule.builder()
                .delegatorId("user-a")
                .delegateId("user-b")
                .delegateTargetType(DelegateTargetType.USER)
                .delegationType(DelegationType.ALL)
                .status(DelegationStatus.ACTIVE)
                .build();
        when(repository.findActiveDelegationsForDelegate(eq("user-b"), any())).thenReturn(List.of(rule));
        when(workspace.activeWorkspaceBuCode()).thenReturn(Optional.empty());
        TaskInfo task = TaskInfo.builder()
                .taskId("t1")
                .assignmentType("USER")
                .assignee("user-a")
                .priority("NORMAL")
                .build();
        assertTrue(evaluator.isStandingDelegatee(task, "user-b", null));
        assertTrue(evaluator.canProcessTask(task, "user-b"));
        assertFalse(evaluator.isStandingDelegatee(task, "user-c", null));
    }
}
