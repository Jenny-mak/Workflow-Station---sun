package com.portal.component;

import com.portal.client.WorkflowEngineClient;
import com.portal.dto.PageResponse;
import com.portal.dto.TaskInfo;
import com.portal.dto.TaskQueryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Delegated-only To Do must not apply viewer workspace FIXED_BU_ROLE filter")
class TaskQueryComponentDelegatedPostFilterTest {

    @Mock
    private WorkflowEngineClient workflowEngineClient;
    @Mock
    private DelegatedTaskQueryComponent delegatedTaskQueryComponent;
    @Mock
    private TaskHistoryComponent taskHistoryComponent;
    @Mock
    private RequestIdEnricher requestIdEnricher;
    @Mock
    private CompletedTaskListQueryComponent completedTaskListQueryComponent;
    @Mock
    private MineTaskScanner mineTaskScanner;
    @Mock
    private EngineVisibleTaskFetcher engineVisibleTaskFetcher;
    @Mock
    private TaskDetailQueryComponent taskDetailQueryComponent;
    @Mock
    private TodoListQueryComponent todoListQueryComponent;
    @Mock
    private MineTaskListCache mineTaskListCache;

    private TaskQueryComponent component;

    @BeforeEach
    void setUp() {
        component = new TaskQueryComponent(
                workflowEngineClient,
                delegatedTaskQueryComponent,
                taskHistoryComponent,
                requestIdEnricher,
                completedTaskListQueryComponent,
                mineTaskScanner,
                engineVisibleTaskFetcher,
                taskDetailQueryComponent,
                todoListQueryComponent,
                mineTaskListCache);
    }

    @Test
    void delegatedOnlyPageSkipsViewerWorkspacePostFilter() {
        when(workflowEngineClient.isAvailable()).thenReturn(true);
        TaskInfo held = TaskInfo.builder()
                .taskId("review-1")
                .assignee("user-e2e-lina")
                .assignmentType("DELEGATED")
                .bpmnAssigneeType("FIXED_BU_ROLE")
                .build();
        when(delegatedTaskQueryComponent.queryDelegatedTasks("user-e2e-wangfang"))
                .thenReturn(List.of(held));
        when(mineTaskScanner.applyDelegatedOverlayPostFilters(anyList()))
                .thenAnswer(inv -> inv.getArgument(0));

        PageResponse<TaskInfo> page = component.queryTasks(TaskQueryRequest.builder()
                .userId("user-e2e-wangfang")
                .assignmentTypes(List.of("DELEGATED"))
                .page(0)
                .size(20)
                .build());

        assertThat(page.getContent()).extracting(TaskInfo::getTaskId).containsExactly("review-1");
        verify(mineTaskScanner).applyDelegatedOverlayPostFilters(anyList());
        verify(mineTaskScanner, never()).applyPortalPostEngineFilters(anyString(), any());
    }
}
