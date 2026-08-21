package com.portal.util;

import com.portal.dto.PortalListColumnMeta;
import com.portal.dto.PortalListColumnMeta.Kind;

import java.util.List;

/**
 * Fixed column declaration for To Do. Kind drives shared-header operators; filtering/sorting
 * that cannot be pushed into Flowable runs as an exact portal fullScan (see {@link EngineTaskPushdown}).
 *
 * <p>{@code requestId} is enriched after the page is chosen — same as Completed Tasks, display-only.
 * {@code priority} is stored as Flowable's numeric string ({@code "50"}); ENUM options map to
 * numeric bands in {@link TaskQueryColumnFilters} so chrome labels match the cell renderer.
 */
public final class TodoTaskColumnSpec {

    private TodoTaskColumnSpec() {
    }

    public static List<PortalListColumnMeta> columns() {
        return List.of(
                PortalListColumnMeta.displayOnly("requestId", "task.requestId", Kind.TEXT),
                PortalListColumnMeta.of("taskName", "task.taskName", Kind.TEXT),
                PortalListColumnMeta.of("currentStepName", "task.currentStep", Kind.TEXT),
                PortalListColumnMeta.of("processDefinitionName", "task.processName", Kind.TEXT),
                PortalListColumnMeta.withOptions("assignmentType", "task.assignmentType", Kind.ENUM, assignmentOptions()),
                PortalListColumnMeta.of("initiatorName", "task.initiator", Kind.TEXT),
                PortalListColumnMeta.withOptions("priority", "task.priority", Kind.ENUM, priorityOptions()),
                PortalListColumnMeta.of("createTime", "task.createTime", Kind.DATETIME),
                PortalListColumnMeta.of("dueDate", "task.dueDate", Kind.DATETIME)
        );
    }

    private static List<PortalListColumnMeta.Option> assignmentOptions() {
        return List.of(
                new PortalListColumnMeta.Option("USER", "task.user"),
                new PortalListColumnMeta.Option("CANDIDATE_USERS", "task.candidateUsers"),
                new PortalListColumnMeta.Option("VIRTUAL_GROUP", "task.virtualGroup"),
                new PortalListColumnMeta.Option("DEPT_ROLE", "task.deptRole")
        );
    }

    private static List<PortalListColumnMeta.Option> priorityOptions() {
        return List.of(
                new PortalListColumnMeta.Option("URGENT", "task.urgent"),
                new PortalListColumnMeta.Option("HIGH", "task.high"),
                new PortalListColumnMeta.Option("NORMAL", "task.normal"),
                new PortalListColumnMeta.Option("LOW", "task.low")
        );
    }
}
