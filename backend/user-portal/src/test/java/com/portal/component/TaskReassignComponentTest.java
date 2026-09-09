package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.dto.TaskInfo;
import com.portal.enums.ChangeType;
import com.portal.exception.PortalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskReassignComponentTest {

    @Mock
    private TaskQueryComponent taskQueryComponent;
    @Mock
    private WorkflowEngineClient workflowEngineClient;
    @Mock
    private TaskPermissionEvaluator taskPermissionEvaluator;
    @Mock
    private ClaimForceUnclaimAnnotator claimForceUnclaimAnnotator;
    @Mock
    private ProcessInstanceSyncComponent processInstanceSyncComponent;
    @Mock
    private TaskAssignmentHistoryRecorder taskAssignmentHistoryRecorder;

    private TaskReassignComponent component;

    @BeforeEach
    void setUp() {
        component = new TaskReassignComponent(
                taskQueryComponent,
                workflowEngineClient,
                taskPermissionEvaluator,
                claimForceUnclaimAnnotator,
                processInstanceSyncComponent,
                taskAssignmentHistoryRecorder);
        when(workflowEngineClient.isAvailable()).thenReturn(true);
    }

    @Test
    void reassignsPoolTaskAndRecordsHistory() {
        TaskInfo before = pool("alice");
        TaskInfo after = pool("bob");
        when(taskQueryComponent.getTaskById("t1")).thenReturn(Optional.of(before), Optional.of(after));
        when(claimForceUnclaimAnnotator.canReassign(before, "leader")).thenReturn(true);
        when(taskPermissionEvaluator.isHeldByUser(before, "bob", null)).thenReturn(false);
        when(workflowEngineClient.reassignClaim("t1", "leader", "bob"))
                .thenReturn(Optional.of(Map.of("success", true)));

        TaskInfo result = component.reassign("t1", "leader", "bob", "leader");

        assertThat(result.getAssignee()).isEqualTo("bob");
        verify(taskAssignmentHistoryRecorder).record(before, "leader", ChangeType.REASSIGN, "bob");
        verify(processInstanceSyncComponent).updateProcessInstanceAssignee(
                eq("pi-1"), eq("bob"), any(), any(), isNull());
    }

    @Test
    void reassignsMiPoolTaskForwardsCurrentItem() {
        Map<String, Object> currentItem = Map.of("row_id", "r1");
        TaskInfo before = pool("alice", currentItem);
        TaskInfo after = pool("bob", currentItem);
        when(taskQueryComponent.getTaskById("t1")).thenReturn(Optional.of(before), Optional.of(after));
        when(claimForceUnclaimAnnotator.canReassign(before, "leader")).thenReturn(true);
        when(taskPermissionEvaluator.isHeldByUser(before, "bob", null)).thenReturn(false);
        when(workflowEngineClient.reassignClaim("t1", "leader", "bob"))
                .thenReturn(Optional.of(Map.of("success", true)));

        component.reassign("t1", "leader", "bob", "leader");

        verify(processInstanceSyncComponent).updateProcessInstanceAssignee(
                eq("pi-1"), eq("bob"), any(), any(), eq(currentItem));
    }

    @Test
    void memberCannotReassign() {
        TaskInfo before = pool("alice");
        when(taskQueryComponent.getTaskById("t1")).thenReturn(Optional.of(before));
        when(claimForceUnclaimAnnotator.canReassign(before, "member")).thenReturn(false);

        assertThatThrownBy(() -> component.reassign("t1", "member", "bob", null))
                .isInstanceOf(PortalException.class)
                .hasMessageContaining("not allowed to reassign");
        verify(workflowEngineClient, never()).reassignClaim(any(), any(), any());
    }

    @Test
    void rejectsDirectAssignment() {
        TaskInfo direct = TaskInfo.builder().taskId("t1").assignmentType("USER").assignee("alice").build();
        when(taskQueryComponent.getTaskById("t1")).thenReturn(Optional.of(direct));

        assertThatThrownBy(() -> component.reassign("t1", "leader", "bob", null))
                .isInstanceOf(PortalException.class)
                .hasMessageContaining("Only business-unit role requests");
    }

    private static TaskInfo pool(String assignee) {
        return pool(assignee, null);
    }

    private static TaskInfo pool(String assignee, Map<String, Object> currentItem) {
        return TaskInfo.builder()
                .taskId("t1")
                .processInstanceId("pi-1")
                .bpmnAssigneeType("BU_ROLE")
                .assignee(assignee)
                .candidateUserIds(List.of("alice", "bob"))
                .variables(currentItem == null ? null : Map.of("_currentItem", currentItem))
                .build();
    }
}
