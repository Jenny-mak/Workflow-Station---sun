package com.workflow.component;

import com.workflow.client.AdminCenterClient;
import com.workflow.dto.response.TaskAssignmentResult;
import com.workflow.entity.ExtendedTaskInfo;
import com.workflow.enums.AssignmentType;
import com.workflow.exception.WorkflowValidationException;
import com.workflow.repository.ExtendedTaskInfoRepository;
import com.workflow.service.UserPermissionService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskClaimSupport.reassignClaim")
class TaskClaimSupportReassignTest {

    private static final String TASK_ID = "task-reassign";
    private static final String HOLDER_ID = "holder-user";
    private static final String TARGET_ID = "target-user";
    private static final String ACTOR_ID = "leader-user";
    private static final String PROCESS_DEFINITION_ID = "proc-def-1";
    private static final String TASK_DEFINITION_KEY = "claimPoolTask";
    private static final String PROCESS_INSTANCE_ID = "pi-1";
    private static final String BUSINESS_UNIT_ID = "bu-hase";
    private static final String ROLE_ID = "role-analyst";

    @Mock
    private TaskService taskService;
    @Mock
    private ExtendedTaskInfoRepository extendedTaskInfoRepository;
    @Mock
    private AdminCenterClient adminCenterClient;
    @Mock
    private BpmnActionParser bpmnActionParser;
    @Mock
    private RuntimeService runtimeService;
    @Mock
    private TaskOrphanRepairService taskOrphanRepairService;
    @Mock
    private UserPermissionService userPermissionService;

    @InjectMocks
    private TaskClaimSupport taskClaimSupport;

    private Task flowableTask;
    private ExtendedTaskInfo extended;

    @BeforeEach
    void setUp() {
        flowableTask = mock(Task.class);
        lenient().when(flowableTask.getId()).thenReturn(TASK_ID);
        lenient().when(flowableTask.getAssignee()).thenReturn(HOLDER_ID);
        lenient().when(flowableTask.getProcessDefinitionId()).thenReturn(PROCESS_DEFINITION_ID);
        lenient().when(flowableTask.getTaskDefinitionKey()).thenReturn(TASK_DEFINITION_KEY);
        lenient().when(flowableTask.getProcessInstanceId()).thenReturn(PROCESS_INSTANCE_ID);

        TaskQuery query = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.taskId(TASK_ID)).thenReturn(query);
        when(query.singleResult()).thenReturn(flowableTask);

        extended = ExtendedTaskInfo.builder()
                .taskId(TASK_ID)
                .processInstanceId(PROCESS_INSTANCE_ID)
                .processDefinitionId(PROCESS_DEFINITION_ID)
                .taskDefinitionKey(TASK_DEFINITION_KEY)
                .assignmentType(AssignmentType.CANDIDATE_USERS)
                .assignmentTarget(HOLDER_ID + "," + TARGET_ID)
                .claimedBy(HOLDER_ID)
                .status("CLAIMED")
                .isDeleted(false)
                .build();
        lenient().when(extendedTaskInfoRepository.findByTaskIdAndIsDeletedFalse(TASK_ID))
                .thenReturn(Optional.of(extended));
    }

    @Test
    @DisplayName("authorized actor can overwrite a held claim-pool task without changing pool type")
    void leaderReassignsHeldTaskToPoolMember() {
        stubBpmnClaimPool();
        stubCandidates(HOLDER_ID, TARGET_ID);
        when(adminCenterClient.canForceUnclaim(ACTOR_ID, TASK_ID, BUSINESS_UNIT_ID, List.of(ROLE_ID)))
                .thenReturn(true);

        TaskAssignmentResult result = taskClaimSupport.reassignClaim(TASK_ID, ACTOR_ID, TARGET_ID);

        assertThat(result.isSuccess()).isTrue();
        verify(taskService).setAssignee(TASK_ID, TARGET_ID);
        verify(taskService, never()).unclaim(any());
        assertThat(extended.getClaimedBy()).isEqualTo(TARGET_ID);
        assertThat(extended.getAssignmentType()).isEqualTo(AssignmentType.CANDIDATE_USERS);
    }

    @Test
    @DisplayName("authorized actor can assign a free claim-pool task to a pool member")
    void leaderAssignsUnclaimedPoolTask() {
        when(flowableTask.getAssignee()).thenReturn(null);
        extended.setClaimedBy(null);
        extended.setStatus("ASSIGNED");
        stubBpmnClaimPool();
        stubCandidates(HOLDER_ID, TARGET_ID);
        when(adminCenterClient.canForceUnclaim(ACTOR_ID, TASK_ID, BUSINESS_UNIT_ID, List.of(ROLE_ID)))
                .thenReturn(true);

        TaskAssignmentResult result = taskClaimSupport.reassignClaim(TASK_ID, ACTOR_ID, TARGET_ID);

        assertThat(result.isSuccess()).isTrue();
        verify(taskService).setAssignee(TASK_ID, TARGET_ID);
        assertThat(extended.getClaimedBy()).isEqualTo(TARGET_ID);
    }

    @Test
    @DisplayName("ordinary member cannot reassign")
    void memberCannotReassign() {
        stubBpmnClaimPool();
        when(adminCenterClient.canForceUnclaim(ACTOR_ID, TASK_ID, BUSINESS_UNIT_ID, List.of(ROLE_ID)))
                .thenReturn(false);

        assertThatThrownBy(() -> taskClaimSupport.reassignClaim(TASK_ID, ACTOR_ID, TARGET_ID))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Not allowed to reassign");
        verify(taskService, never()).setAssignee(any(), any());
    }

    @Test
    @DisplayName("target outside the candidate pool is rejected")
    void outsiderTargetRejected() {
        stubBpmnClaimPool();
        stubCandidates(HOLDER_ID, TARGET_ID);
        when(adminCenterClient.canForceUnclaim(ACTOR_ID, TASK_ID, BUSINESS_UNIT_ID, List.of(ROLE_ID)))
                .thenReturn(true);

        assertThatThrownBy(() -> taskClaimSupport.reassignClaim(TASK_ID, ACTOR_ID, "stranger"))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("not in the claim pool");
        verify(taskService, never()).setAssignee(any(), any());
    }

    @Test
    @DisplayName("reassigning to the current holder is rejected")
    void sameHolderRejected() {
        stubBpmnClaimPool();
        when(adminCenterClient.canForceUnclaim(ACTOR_ID, TASK_ID, BUSINESS_UNIT_ID, List.of(ROLE_ID)))
                .thenReturn(true);

        assertThatThrownBy(() -> taskClaimSupport.reassignClaim(TASK_ID, ACTOR_ID, HOLDER_ID))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("already held by target");
        verify(taskService, never()).setAssignee(any(), any());
    }

    @Test
    @DisplayName("direct USER assignment with no candidates cannot be reassigned")
    void directUserAssignmentRejected() {
        when(flowableTask.getAssignee()).thenReturn(HOLDER_ID);
        extended.setAssignmentType(AssignmentType.USER);
        extended.setAssignmentTarget(HOLDER_ID);
        stubBpmnClaimPool();
        when(taskService.getIdentityLinksForTask(TASK_ID)).thenReturn(List.of());
        when(adminCenterClient.canForceUnclaim(ACTOR_ID, TASK_ID, BUSINESS_UNIT_ID, List.of(ROLE_ID)))
                .thenReturn(true);

        assertThatThrownBy(() -> taskClaimSupport.reassignClaim(TASK_ID, ACTOR_ID, TARGET_ID))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("cannot be reassigned in the claim pool");
        verify(taskService, never()).setAssignee(any(), any());
    }

    private void stubBpmnClaimPool() {
        when(bpmnActionParser.getUserTaskExtensionPropertyValue(
                PROCESS_DEFINITION_ID, TASK_DEFINITION_KEY, "businessUnitId")).thenReturn(BUSINESS_UNIT_ID);
        when(bpmnActionParser.getUserTaskExtensionPropertyValue(
                PROCESS_DEFINITION_ID, TASK_DEFINITION_KEY, "roleIds")).thenReturn(ROLE_ID);
        when(bpmnActionParser.getUserTaskExtensionPropertyValue(
                PROCESS_DEFINITION_ID, TASK_DEFINITION_KEY, "roleId")).thenReturn(null);
    }

    private void stubCandidates(String... userIds) {
        List<IdentityLink> links = new ArrayList<>();
        for (String userId : userIds) {
            IdentityLink link = mock(IdentityLink.class);
            when(link.getType()).thenReturn("candidate");
            when(link.getUserId()).thenReturn(userId);
            links.add(link);
        }
        when(taskService.getIdentityLinksForTask(TASK_ID)).thenReturn(links);
    }
}
