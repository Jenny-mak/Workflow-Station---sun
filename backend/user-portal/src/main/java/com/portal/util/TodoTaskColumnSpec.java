package com.portal.util;

import com.platform.common.list.ListColumnMeta;
import com.platform.common.list.ListColumnMeta.Kind;
import java.util.List;

/**
 * Fixed column declaration for To Do. Kind drives shared-header operators; filtering/sorting
 * that cannot be pushed into Flowable runs as an exact portal fullScan (see {@link EngineTaskPushdown}).
 *
 * <p>{@code requestId} is computed (enriched before filter/sort). It is ordinary TEXT
 * ({@link ListColumnMeta#of}) so the header can search and A→Z sort. {@code functionUnitCode}
 * is filled from {@code up_process_instance} in the same enrich pass. {@code priority} is stored as Flowable's
 * numeric string ({@code "50"}); ENUM options map to numeric bands in
 * {@link TaskQueryColumnFilters} so chrome labels match the cell renderer.
 *
 * <p>Process Name / Initiator / Priority / Due Date stay declared (after Create Time) so they can be
 * restored without a spec rewrite; Portal currently hides them via {@code visibleFields}.
 */
public final class TodoTaskColumnSpec {

    /**
     * Columns Portal renders on To Do. Keep in sync with {@code TODO_VISIBLE_FIELDS} in
     * {@code frontend/user-portal/src/views/tasks/index.vue}. Toolbar keyword searches these
     * painted cells (Create Time as {@code yyyy-MM-dd HH:mm}, plus {@code functionUnitName}
     * for the Function Unit cell).
     */
    public static final List<String> VISIBLE_FIELDS = List.of(
            "requestId",
            "functionUnitCode",
            "taskName",
            "assignmentType",
            "assigneeName",
            "createTime");

    private TodoTaskColumnSpec() {
    }

    public static List<ListColumnMeta> columns() {
        return List.of(
                ListColumnMeta.of("requestId", "task.requestId", Kind.TEXT),
                ListColumnMeta.of("functionUnitCode", "task.functionUnit", Kind.TEXT),
                ListColumnMeta.of("taskName", "task.taskName", Kind.TEXT),
                ListColumnMeta.withOptions("assignmentType", "task.assignmentType", Kind.ENUM, assignmentOptions()),
                ListColumnMeta.of("createTime", "task.createTime", Kind.DATETIME),
                ListColumnMeta.of("processDefinitionName", "task.processName", Kind.TEXT),
                ListColumnMeta.of("initiatorName", "task.initiator", Kind.TEXT),
                ListColumnMeta.of("assigneeName", "task.claimedBy", Kind.TEXT),
                ListColumnMeta.withOptions("priority", "task.priority", Kind.ENUM, priorityOptions()),
                ListColumnMeta.of("dueDate", "task.dueDate", Kind.DATETIME)
        );
    }

    private static List<ListColumnMeta.Option> assignmentOptions() {
        return List.of(
                new ListColumnMeta.Option("USER", "task.user"),
                new ListColumnMeta.Option("BU_ROLE", "task.buRole"),
                new ListColumnMeta.Option("DEPT_ROLE", "task.deptRole"),
                new ListColumnMeta.Option("CANDIDATE_USERS", "task.candidateUsers")
        );
    }

    private static List<ListColumnMeta.Option> priorityOptions() {
        return List.of(
                new ListColumnMeta.Option("URGENT", "task.urgent"),
                new ListColumnMeta.Option("HIGH", "task.high"),
                new ListColumnMeta.Option("NORMAL", "task.normal"),
                new ListColumnMeta.Option("LOW", "task.low")
        );
    }
}
