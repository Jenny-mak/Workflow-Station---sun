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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DelegatedTaskQueryComponent standing overlay ignores viewer workspace BU")
class DelegatedTaskQueryComponentWorkspaceFilterTest {

    @Mock
    private WorkflowEngineClient workflowEngineClient;
    @Mock
    private DelegationRuleRepository delegationRuleRepository;
    @Mock
    private WorkspaceTaskFilterComponent workspaceTaskFilterComponent;
    @Mock
    private RequestIdEnricher requestIdEnricher;

    private DelegatedTaskQueryComponent component;

    @BeforeEach
    void setUp() {
        component = new DelegatedTaskQueryComponent(
                workflowEngineClient,
                new DelegationRuleMatcher(delegationRuleRepository, workspaceTaskFilterComponent),
                requestIdEnricher,
                org.mockito.Mockito.mock(DelegationUserDisplayEnricher.class));
    }

    @Test
    @DisplayName("Delegator assigned tasks are loaded without the viewer activeBusinessUnitId")
    void loadDelegatorTasksWithoutViewerWorkspaceBu() {
        when(workflowEngineClient.isAvailable()).thenReturn(true);
        when(workflowEngineClient.getDelegatedRuntimeTasks(any(), any())).thenReturn(Optional.empty());
        when(delegationRuleRepository.findActiveDelegationsForDelegate(eq("user-e2e-wangfang"), any()))
                .thenReturn(List.of(DelegationRule.builder()
                        .delegatorId("user-e2e-lina")
                        .delegateId("user-e2e-wangfang")
                        .delegateTargetType(DelegateTargetType.USER)
                        .delegationType(DelegationType.ALL)
                        .status(DelegationStatus.ACTIVE)
                        .build()));
        when(workspaceTaskFilterComponent.activeWorkspaceBuCode()).thenReturn(Optional.empty());
        when(workspaceTaskFilterComponent.activeWorkspaceRoleCode("user-e2e-wangfang"))
                .thenReturn(Optional.empty());

        Map<String, Object> task = Map.of(
                "taskId", "review-1",
                "taskName", "Review",
                "processInstanceId", "pi-1",
                "processDefinitionKey", "owner-demo",
                "assignmentType", "USER",
                "currentAssignee", "user-e2e-lina",
                "bpmnAssigneeType", "FIXED_BU_ROLE",
                "status", "ASSIGNED");
        when(workflowEngineClient.getDelegatorAssignedTasks(eq("user-e2e-lina"), eq(0), eq(200)))
                .thenReturn(Map.of("tasks", List.of(task)));

        List<TaskInfo> result = component.queryDelegatedTasks("user-e2e-wangfang");

        assertThat(result).extracting(TaskInfo::getTaskId).containsExactly("review-1");
        assertThat(result.get(0).getDelegatorId()).isEqualTo("user-e2e-lina");
        assertThat(result.get(0).getDelegatedTargetType()).isEqualTo("USER");
        verify(workflowEngineClient).getDelegatorAssignedTasks("user-e2e-lina", 0, 200);
        verify(workflowEngineClient, never()).getUserTasks(anyString(), anyInt(), anyInt());
        verify(workflowEngineClient, never()).getUserTasks(anyString(), anyInt(), anyInt(), eq(false));
    }

    @Test
    @DisplayName("Engine forbidden/empty Optional on GET /tasks is not treated as an empty overlay")
    void engineTransportFailureIsNotAnEmptyDelegatedList() {
        when(workflowEngineClient.isAvailable()).thenReturn(true);
        when(workflowEngineClient.getDelegatedRuntimeTasks(any(), any())).thenReturn(Optional.empty());
        when(delegationRuleRepository.findActiveDelegationsForDelegate(eq("user-e2e-wangfang"), any()))
                .thenReturn(List.of(DelegationRule.builder()
                        .delegatorId("user-e2e-lina")
                        .delegateId("user-e2e-wangfang")
                        .delegateTargetType(DelegateTargetType.USER)
                        .delegationType(DelegationType.ALL)
                        .status(DelegationStatus.ACTIVE)
                        .build()));
        when(workspaceTaskFilterComponent.activeWorkspaceBuCode()).thenReturn(Optional.empty());
        when(workspaceTaskFilterComponent.activeWorkspaceRoleCode("user-e2e-wangfang"))
                .thenReturn(Optional.empty());
        when(workflowEngineClient.getDelegatorAssignedTasks(eq("user-e2e-lina"), eq(0), eq(200)))
                .thenThrow(new IllegalStateException(
                        "Failed to load delegator assigned tasks from workflow engine: 403"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> component.queryDelegatedTasks("user-e2e-wangfang"));
    }

    @Test
    @DisplayName("PARTIAL standing overlay matches portal start key stored on engine variables")
    void partialOverlayMatchesFunctionUnitCodeInVariables() {
        when(workflowEngineClient.isAvailable()).thenReturn(true);
        when(workflowEngineClient.getDelegatedRuntimeTasks(any(), any())).thenReturn(Optional.empty());
        when(delegationRuleRepository.findActiveDelegationsForDelegate(eq("user-e2e-wangfang"), any()))
                .thenReturn(List.of(DelegationRule.builder()
                        .delegatorId("user-e2e-lina")
                        .delegateId("user-e2e-wangfang")
                        .delegateTargetType(DelegateTargetType.USER)
                        .delegationType(DelegationType.PARTIAL)
                        .processTypes(List.of("leave-request-delegation-20260915-tj3oye"))
                        .status(DelegationStatus.ACTIVE)
                        .build()));
        when(workspaceTaskFilterComponent.activeWorkspaceBuCode()).thenReturn(Optional.empty());
        when(workspaceTaskFilterComponent.activeWorkspaceRoleCode("user-e2e-wangfang"))
                .thenReturn(Optional.empty());

        Map<String, Object> leave = engineAssignedTask(
                "leave-1",
                "Process_LeaveDelegation",
                Map.of("functionUnitCode", "leave-request-delegation-20260915-tj3oye"));
        Map<String, Object> other = engineAssignedTask(
                "other-1",
                "Process_LeaveDelegation",
                Map.of("functionUnitCode", "owner-demo-20260907-gehibh"));
        when(workflowEngineClient.getDelegatorAssignedTasks(eq("user-e2e-lina"), eq(0), eq(200)))
                .thenReturn(Map.of("tasks", List.of(leave, other)));

        List<TaskInfo> result = component.queryDelegatedTasks("user-e2e-wangfang");
        assertThat(result).extracting(TaskInfo::getTaskId).containsExactly("leave-1");
    }

    @Test
    @DisplayName("PARTIAL standing overlay matches after RequestIdEnricher fills functionUnitCode")
    void partialOverlayMatchesEnrichedFunctionUnitCodeWhenEngineOmitsVariables() {
        when(workflowEngineClient.isAvailable()).thenReturn(true);
        when(workflowEngineClient.getDelegatedRuntimeTasks(any(), any())).thenReturn(Optional.empty());
        when(delegationRuleRepository.findActiveDelegationsForDelegate(eq("user-e2e-wangfang"), any()))
                .thenReturn(List.of(DelegationRule.builder()
                        .delegatorId("user-e2e-lina")
                        .delegateId("user-e2e-wangfang")
                        .delegateTargetType(DelegateTargetType.USER)
                        .delegationType(DelegationType.PARTIAL)
                        .processTypes(List.of("leave-request-delegation-20260915-tj3oye"))
                        .status(DelegationStatus.ACTIVE)
                        .build()));
        when(workspaceTaskFilterComponent.activeWorkspaceBuCode()).thenReturn(Optional.empty());
        when(workspaceTaskFilterComponent.activeWorkspaceRoleCode("user-e2e-wangfang"))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.doAnswer(invocation -> {
            List<TaskInfo> tasks = invocation.getArgument(0);
            for (TaskInfo task : tasks) {
                if ("leave-1".equals(task.getTaskId())) {
                    task.setFunctionUnitCode("leave-request-delegation-20260915-tj3oye");
                } else {
                    task.setFunctionUnitCode("owner-demo-20260907-gehibh");
                }
            }
            return null;
        }).when(requestIdEnricher).enrichTaskRequestIds(org.mockito.ArgumentMatchers.anyList());

        Map<String, Object> leave = engineAssignedTask("leave-1", "Process_LeaveDelegation", null);
        Map<String, Object> other = engineAssignedTask("other-1", "Process_LeaveDelegation", null);
        when(workflowEngineClient.getDelegatorAssignedTasks(eq("user-e2e-lina"), eq(0), eq(200)))
                .thenReturn(Map.of("tasks", List.of(leave, other)));

        List<TaskInfo> result = component.queryDelegatedTasks("user-e2e-wangfang");
        assertThat(result).extracting(TaskInfo::getTaskId).containsExactly("leave-1");
    }

    private static Map<String, Object> engineAssignedTask(
            String taskId, String processDefinitionKey, Map<String, Object> variables) {
        Map<String, Object> task = new HashMap<>();
        task.put("taskId", taskId);
        task.put("taskName", "经理审批");
        task.put("processInstanceId", "pi-" + taskId);
        task.put("processDefinitionKey", processDefinitionKey);
        task.put("assignmentType", "USER");
        task.put("currentAssignee", "user-e2e-lina");
        task.put("bpmnAssigneeType", "FIXED_BU_ROLE");
        task.put("status", "ASSIGNED");
        if (variables != null) {
            task.put("variables", variables);
        }
        return task;
    }
}
