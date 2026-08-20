package com.portal.util;

import com.portal.dto.PortalListColumnMeta;
import com.portal.dto.PortalListColumnMeta.Kind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed column declaration for Completed Tasks. Kind follows the stored type (task name is
 * TEXT, duration is NUMBER, action is a closed ENUM derived from {@code DELETE_REASON_}).
 * Request ID is computed and therefore display-only — there is no honest SQL predicate for it.
 */
public final class CompletedTaskColumnSpec {

    /**
     * Mirrors the engine's historic-task action mapping so a filter/group on Action matches
     * the tag the cell shows. ILIKE keeps the historic case-insensitive contains behaviour.
     */
    public static final String ACTION_SQL = "CASE"
            + " WHEN ht.DELETE_REASON_ ILIKE '%approved%' THEN 'approved'"
            + " WHEN ht.DELETE_REASON_ ILIKE '%rejected%' THEN 'rejected'"
            + " WHEN ht.DELETE_REASON_ ILIKE '%transfer%' THEN 'transferred'"
            + " WHEN ht.DELETE_REASON_ ILIKE '%delegate%' THEN 'delegated'"
            + " ELSE 'completed' END";

    private CompletedTaskColumnSpec() {
    }

    public static List<PortalListColumnMeta> columns() {
        return List.of(
                PortalListColumnMeta.displayOnly("requestId", "task.requestId", Kind.TEXT),
                PortalListColumnMeta.of("taskName", "task.taskName", Kind.TEXT),
                PortalListColumnMeta.displayOnly("currentStepName", "task.currentStep", Kind.TEXT),
                PortalListColumnMeta.of("processDefinitionName", "task.processName", Kind.TEXT),
                PortalListColumnMeta.withOptions("action", "task.action", Kind.ENUM, actionOptions()),
                PortalListColumnMeta.of("createTime", "task.createTime", Kind.DATETIME),
                PortalListColumnMeta.of("completedTime", "task.completedTime", Kind.DATETIME),
                PortalListColumnMeta.of("durationInMillis", "task.duration", Kind.NUMBER)
        );
    }

    public static ListFilterSql sql() {
        Map<String, PortalListColumnMeta> byField = new LinkedHashMap<>();
        for (PortalListColumnMeta column : columns()) {
            byField.put(column.field(), column);
        }
        return new ListFilterSql(byField, CompletedTaskColumnSpec::sqlFor, "ht.ID_", "ht.END_TIME_ DESC");
    }

    private static String sqlFor(String field) {
        return switch (field) {
            case "taskName" -> "ht.NAME_";
            case "processDefinitionName" -> "pi.process_definition_name";
            case "action" -> ACTION_SQL;
            case "createTime" -> "ht.START_TIME_::text";
            case "completedTime" -> "ht.END_TIME_::text";
            case "durationInMillis" -> "ht.DURATION_::text";
            default -> throw new IllegalArgumentException("Unknown completed-task column: " + field);
        };
    }

    private static List<PortalListColumnMeta.Option> actionOptions() {
        return List.of(
                new PortalListColumnMeta.Option("approved", "action.approved"),
                new PortalListColumnMeta.Option("rejected", "action.rejected"),
                new PortalListColumnMeta.Option("transferred", "action.transferred"),
                new PortalListColumnMeta.Option("delegated", "action.delegated"),
                new PortalListColumnMeta.Option("completed", "action.completed")
        );
    }
}
