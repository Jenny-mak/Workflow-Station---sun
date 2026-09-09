package com.portal.component;

import com.portal.dto.MyTaskRef;
import com.portal.dto.MyTaskRefsRequest;
import com.portal.dto.TaskInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The Views grid's per-row task marker, resolved for a whole page at once.
 *
 * <p>Rows are matched to tasks by process instance id. Nothing here reads a designed business
 * field — "case number" is a name one function unit happened to choose, so keying off it would
 * make the marker appear in some function units and silently vanish in others.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TodoTaskRefsByProcessInstanceTest {

    @Spy
    @InjectMocks
    private TodoListQueryComponent component;

    private static TaskInfo task(String taskId, String taskName, String processInstanceId) {
        return TaskInfo.builder()
                .taskId(taskId)
                .taskName(taskName)
                .processInstanceId(processInstanceId)
                .build();
    }

    @Test
    void returnsOnlyTasksOnTheRequestedInstances() {
        doReturn(List.of(
                task("t-1", "Approve", "pi-1"),
                task("t-2", "Review", "pi-9")))
                .when(component).listMergedTodoTasks(anyString());

        Map<String, List<MyTaskRef>> refs =
                component.findMyTaskRefsByProcessInstance("u-1", List.of("pi-1", "pi-2"));

        assertThat(refs).containsOnlyKeys("pi-1");
        assertThat(refs.get("pi-1")).containsExactly(new MyTaskRef("t-1", "Approve"));
    }

    /** Parallel branches put more than one of the user's tasks on one request. */
    @Test
    void keepsEveryTaskOnAnInstanceInToDoOrder() {
        doReturn(List.of(
                task("t-1", "Legal", "pi-1"),
                task("t-2", "Finance", "pi-1")))
                .when(component).listMergedTodoTasks(anyString());

        Map<String, List<MyTaskRef>> refs =
                component.findMyTaskRefsByProcessInstance("u-1", List.of("pi-1"));

        assertThat(refs.get("pi-1")).containsExactly(
                new MyTaskRef("t-1", "Legal"),
                new MyTaskRef("t-2", "Finance"));
    }

    /**
     * An instance the user holds nothing on is absent rather than mapped to an empty list, so the
     * grid renders no marker for it.
     */
    @Test
    void omitsInstancesTheUserHoldsNoTaskOn() {
        doReturn(List.<TaskInfo>of()).when(component).listMergedTodoTasks(anyString());

        assertThat(component.findMyTaskRefsByProcessInstance("u-1", List.of("pi-1", "pi-2")))
                .isEmpty();
    }

    @Test
    void skipsTheToDoScanWhenThereIsNothingToAskAbout() {
        assertThat(component.findMyTaskRefsByProcessInstance("u-1", List.of())).isEmpty();
        assertThat(component.findMyTaskRefsByProcessInstance("u-1", null)).isEmpty();
        assertThat(component.findMyTaskRefsByProcessInstance("", List.of("pi-1"))).isEmpty();

        verify(component, never()).listMergedTodoTasks(anyString());
    }

    @Test
    void requestDropsBlankIdsAndDeduplicates() {
        MyTaskRefsRequest request =
                new MyTaskRefsRequest(java.util.Arrays.asList("pi-1", " ", null, "pi-1", "pi-2"));

        assertThat(request.processInstanceIds()).containsExactly("pi-1", "pi-2");
    }

    /** One grid page cannot exceed the shared 200-row list ceiling; a larger ask is a caller bug. */
    @Test
    void requestRefusesMoreIdsThanAPageCanHold() {
        List<String> tooMany = java.util.stream.IntStream.rangeClosed(0, MyTaskRefsRequest.MAX_IDS)
                .mapToObj(i -> "pi-" + i)
                .toList();

        assertThatThrownBy(() -> new MyTaskRefsRequest(tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200");
    }
}
