package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.dto.TaskInfo;
import com.portal.repository.DelegationRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskPermissionEvaluator single-task delegatee")
class TaskPermissionEvaluatorSingleDelegateTest {

    @Mock
    private DelegationRuleRepository delegationRuleRepository;

    @Mock
    private WorkflowEngineClient workflowEngineClient;

    @Mock
    private WorkspaceTaskFilterComponent workspaceTaskFilterComponent;

    private TaskPermissionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new TaskPermissionEvaluator(
                new DelegationRuleMatcher(delegationRuleRepository, workspaceTaskFilterComponent),
                workflowEngineClient, workspaceTaskFilterComponent);
    }

    @Test
    @DisplayName("USER delegatedTo can process without matching assignee")
    void userDelegateeCanProcess() {
        TaskInfo task = TaskInfo.builder()
                .taskId("t1")
                .assignmentType("USER")
                .assignee("user-a")
                .delegatedTargetType("USER")
                .delegatedTo("user-b")
                .build();
        assertTrue(evaluator.isSingleTaskDelegatee(task, "user-b", null));
        assertTrue(evaluator.canProcessTask(task, "user-b"));
        assertFalse(evaluator.isSingleTaskDelegatee(task, "user-c", null));
    }

    @Test
    @DisplayName("BU_ROLE delegatee matches current workspace pair")
    void buRoleDelegateeMatchesWorkspace() {
        TaskInfo task = TaskInfo.builder()
                .taskId("t1")
                .assignmentType("USER")
                .assignee("user-a")
                .delegatedTargetType("BU_ROLE")
                .delegatedBuCode("HK")
                .delegatedRoleCode("APPROVER")
                .build();
        when(workspaceTaskFilterComponent.workspacePairMatches("HK", "APPROVER", "user-b"))
                .thenReturn(true);
        when(workspaceTaskFilterComponent.workspacePairMatches("HK", "APPROVER", "user-c"))
                .thenReturn(false);
        assertTrue(evaluator.isSingleTaskDelegatee(task, "user-b", null));
        assertTrue(evaluator.canProcessTask(task, "user-b"));
        assertFalse(evaluator.isSingleTaskDelegatee(task, "user-c", null));
        assertFalse(evaluator.canProcessTask(task, "user-c"));
    }
}
