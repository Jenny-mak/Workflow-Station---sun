package com.workflow.component;

import com.workflow.dto.request.TaskDelegationRequest;
import com.workflow.dto.response.TaskAssignmentResult;
import com.workflow.entity.ExtendedTaskInfo;
import com.workflow.enums.AssignmentType;
import com.workflow.exception.WorkflowValidationException;
import com.workflow.repository.ExtendedTaskInfoRepository;
import com.workflow.service.UserPermissionService;
import org.flowable.engine.TaskService;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskActionService.delegateTask missing wf_extended_task_info")
class TaskActionServiceDelegateMissingExtendedTest {

    private static final String TASK_ID = "task-owner-demo-review";
    private static final String ASSIGNEE = "user-e2e-lina";
    private static final String DELEGATEE = "user-e2e-wangfang";

    @Mock
    private TaskService taskService;
    @Mock
    private ExtendedTaskInfoRepository extendedTaskInfoRepository;
    @Mock
    private UserPermissionService userPermissionService;
    @Mock
    private UserTaskExtendedInfoWriter userTaskExtendedInfoWriter;

    @InjectMocks
    private TaskActionService taskActionService;

    private Task flowableTask;

    @BeforeEach
    void setUp() {
        flowableTask = mock(Task.class);
        lenient().when(flowableTask.getId()).thenReturn(TASK_ID);
        lenient().when(flowableTask.getAssignee()).thenReturn(ASSIGNEE);
        TaskQuery query = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.taskId(TASK_ID)).thenReturn(query);
        when(query.singleResult()).thenReturn(flowableTask);
        when(extendedTaskInfoRepository.findByTaskIdAndIsDeletedFalse(TASK_ID)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("Claimed Flowable task without extended row is backfilled then delegated")
    void missingExtendedRowIsCreatedThenDelegated() {
        ExtendedTaskInfo created = ExtendedTaskInfo.builder()
                .taskId(TASK_ID)
                .processInstanceId("pi-1")
                .processDefinitionId("pd-1")
                .assignmentType(AssignmentType.USER)
                .assignmentTarget(ASSIGNEE)
                .status("ASSIGNED")
                .isDeleted(false)
                .build();
        when(userTaskExtendedInfoWriter.ensureFromFlowable(flowableTask)).thenReturn(created);
        when(userPermissionService.hasTaskPermission(ASSIGNEE, AssignmentType.USER, ASSIGNEE)).thenReturn(true);
        when(extendedTaskInfoRepository.save(any(ExtendedTaskInfo.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TaskAssignmentResult result = taskActionService.delegateTask(TASK_ID, TaskDelegationRequest.builder()
                .taskId(TASK_ID)
                .delegatedBy(ASSIGNEE)
                .delegatedTo(DELEGATEE)
                .sendNotification(false)
                .build());

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<ExtendedTaskInfo> captor = ArgumentCaptor.forClass(ExtendedTaskInfo.class);
        verify(extendedTaskInfoRepository).save(captor.capture());
        assertThat(captor.getValue().isDelegated()).isTrue();
        assertThat(captor.getValue().getDelegatedTo()).isEqualTo(DELEGATEE);
        assertThat(captor.getValue().getAssignmentTarget()).isEqualTo(ASSIGNEE);
    }

    @Test
    @DisplayName("Missing extended row still rejects when writer cannot backfill")
    void missingExtendedRowWithoutWriterStillFails() {
        when(userTaskExtendedInfoWriter.ensureFromFlowable(flowableTask)).thenReturn(null);

        assertThatThrownBy(() -> taskActionService.delegateTask(TASK_ID, TaskDelegationRequest.builder()
                .taskId(TASK_ID)
                .delegatedBy(ASSIGNEE)
                .delegatedTo(DELEGATEE)
                .sendNotification(false)
                .build()))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Task not found");
    }
}
