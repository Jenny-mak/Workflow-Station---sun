package com.workflow.component;

import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskCompletionService onBehalfOf authorization")
class TaskCompletionOnBehalfAuthorizationTest {

    @Mock
    private TaskActionService taskActionService;
    @Mock
    private TaskService taskService;

    @InjectMocks
    private TaskCompletionService service;

    @Test
    void trustedOnBehalfOfAssigneeAllowsCompleteWithoutExtendedDelegation() {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn("t1");
        when(task.getAssignee()).thenReturn("user-a");
        when(taskActionService.engineActorMatchesPortalUser("user-a", "user-b")).thenReturn(false);
        when(taskActionService.engineActorMatchesPortalUser("user-a", "user-a")).thenReturn(true);
        when(taskService.getIdentityLinksForTask("t1")).thenReturn(List.of());

        assertTrue(service.isCompleteAuthorized(task, null, "user-b", "user-a"));
    }

    @Test
    void onBehalfOfThatDoesNotMatchAssigneeIsRejected() {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn("t1");
        when(task.getAssignee()).thenReturn("user-a");
        when(taskActionService.engineActorMatchesPortalUser("user-a", "user-b")).thenReturn(false);
        when(taskActionService.engineActorMatchesPortalUser("user-a", "user-c")).thenReturn(false);
        when(taskService.getIdentityLinksForTask("t1")).thenReturn(List.of());

        assertFalse(service.isCompleteAuthorized(task, null, "user-b", "user-c"));
    }
}
