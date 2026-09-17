package com.workflow.component;

import com.workflow.dto.request.TaskClaimRequest;
import com.workflow.dto.response.TaskAssignmentResult;
import com.workflow.entity.ExtendedTaskInfo;
import com.workflow.enums.AssignmentType;
import com.workflow.repository.ExtendedTaskInfoRepository;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskClaimSupport.claimTask missing wf_extended_task_info")
class TaskClaimSupportMissingExtendedTest {

    private static final String TASK_ID = "task-claim-missing-ext";
    private static final String CLAIMER = "user-e2e-lina";

    @Mock
    private TaskService taskService;
    @Mock
    private ExtendedTaskInfoRepository extendedTaskInfoRepository;
    @Mock
    private UserTaskExtendedInfoWriter userTaskExtendedInfoWriter;

    @InjectMocks
    private TaskClaimSupport taskClaimSupport;

    private Task flowableTask;

    @BeforeEach
    void setUp() {
        flowableTask = mock(Task.class);
        when(flowableTask.getId()).thenReturn(TASK_ID);
        when(flowableTask.getAssignee()).thenReturn(null);
        TaskQuery query = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.taskId(TASK_ID)).thenReturn(query);
        when(query.singleResult()).thenReturn(flowableTask);

        IdentityLink link = mock(IdentityLink.class);
        when(link.getType()).thenReturn("candidate");
        when(link.getUserId()).thenReturn(CLAIMER);
        when(taskService.getIdentityLinksForTask(TASK_ID)).thenReturn(List.of(link));
        when(extendedTaskInfoRepository.findByTaskIdAndIsDeletedFalse(TASK_ID)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("Unclaimed pool task without extended row is backfilled then claimed")
    void missingExtendedRowIsCreatedThenClaimed() {
        ExtendedTaskInfo created = ExtendedTaskInfo.builder()
                .taskId(TASK_ID)
                .processInstanceId("pi-1")
                .processDefinitionId("pd-1")
                .assignmentType(AssignmentType.CANDIDATE_USERS)
                .assignmentTarget(CLAIMER)
                .status("ASSIGNED")
                .isDeleted(false)
                .build();
        when(userTaskExtendedInfoWriter.ensureFromFlowable(flowableTask)).thenReturn(created);
        when(extendedTaskInfoRepository.save(any(ExtendedTaskInfo.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TaskAssignmentResult result = taskClaimSupport.claimTask(TASK_ID, TaskClaimRequest.builder()
                .taskId(TASK_ID)
                .claimedBy(CLAIMER)
                .build());

        assertThat(result.isSuccess()).isTrue();
        verify(taskService).claim(TASK_ID, CLAIMER);
        ArgumentCaptor<ExtendedTaskInfo> captor = ArgumentCaptor.forClass(ExtendedTaskInfo.class);
        verify(extendedTaskInfoRepository).save(captor.capture());
        assertThat(captor.getValue().getClaimedBy()).isEqualTo(CLAIMER);
        assertThat(captor.getValue().getStatus()).isEqualTo("CLAIMED");
    }
}
