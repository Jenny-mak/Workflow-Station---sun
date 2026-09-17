package com.workflow.component;

import com.workflow.entity.ExtendedTaskInfo;
import com.workflow.enums.AssignmentType;
import com.workflow.repository.ExtendedTaskInfoRepository;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserTaskExtendedInfoWriter")
class UserTaskExtendedInfoWriterTest {

    @Mock
    private ExtendedTaskInfoRepository repository;
    @Mock
    private TaskService taskService;

    private UserTaskExtendedInfoWriter writer;

    @BeforeEach
    void setUp() {
        writer = new UserTaskExtendedInfoWriter(repository, taskService);
    }

    @Test
    @DisplayName("Direct assignee creates USER extended row")
    void assigneeCreatesUserRow() {
        Task task = mockTask("t-user", "user-a", "pi-1", "pd-1", "review");
        when(repository.findByTaskIdAndIsDeletedFalse("t-user")).thenReturn(Optional.empty());
        when(repository.save(any(ExtendedTaskInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExtendedTaskInfo saved = writer.ensureFromFlowable(task);

        assertThat(saved.getAssignmentType()).isEqualTo(AssignmentType.USER);
        assertThat(saved.getAssignmentTarget()).isEqualTo("user-a");
        assertThat(saved.getStatus()).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("Candidate pool creates CANDIDATE_USERS extended row")
    void candidatesCreatePoolRow() {
        Task task = mockTask("t-pool", null, "pi-1", "pd-1", "review");
        IdentityLink one = mock(IdentityLink.class);
        when(one.getType()).thenReturn("candidate");
        when(one.getUserId()).thenReturn("user-a");
        IdentityLink two = mock(IdentityLink.class);
        when(two.getType()).thenReturn("candidate");
        when(two.getUserId()).thenReturn("user-b");
        when(taskService.getIdentityLinksForTask("t-pool")).thenReturn(List.of(one, two));
        when(repository.findByTaskIdAndIsDeletedFalse("t-pool")).thenReturn(Optional.empty());
        when(repository.save(any(ExtendedTaskInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExtendedTaskInfo saved = writer.ensureFromFlowable(task);

        ArgumentCaptor<ExtendedTaskInfo> captor = ArgumentCaptor.forClass(ExtendedTaskInfo.class);
        verify(repository).save(captor.capture());
        assertThat(saved.getAssignmentType()).isEqualTo(AssignmentType.CANDIDATE_USERS);
        assertThat(captor.getValue().getAssignmentTarget()).isEqualTo("user-a,user-b");
    }

    @Test
    @DisplayName("Existing row is left unchanged so MI JSON is not wiped")
    void existingRowIsNotOverwritten() {
        ExtendedTaskInfo existing = ExtendedTaskInfo.builder()
                .taskId("t-mi")
                .assignmentType(AssignmentType.USER)
                .assignmentTarget("user-a")
                .extendedProperties("{\"multiInstance\":true}")
                .build();
        Task task = mockTask("t-mi", "user-a", "pi-1", "pd-1", "review");
        when(repository.findByTaskIdAndIsDeletedFalse("t-mi")).thenReturn(Optional.of(existing));

        ExtendedTaskInfo result = writer.ensureFromFlowable(task);

        assertThat(result.getExtendedProperties()).isEqualTo("{\"multiInstance\":true}");
        verify(repository, never()).save(any());
    }

    private Task mockTask(String id, String assignee, String pi, String pd, String defKey) {
        Task task = mock(Task.class);
        lenient().when(task.getId()).thenReturn(id);
        lenient().when(task.getAssignee()).thenReturn(assignee);
        lenient().when(task.getProcessInstanceId()).thenReturn(pi);
        lenient().when(task.getProcessDefinitionId()).thenReturn(pd);
        lenient().when(task.getTaskDefinitionKey()).thenReturn(defKey);
        lenient().when(task.getName()).thenReturn("Review");
        return task;
    }
}
