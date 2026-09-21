package com.portal.util;

import com.platform.common.list.ListColumnFilter;
import com.portal.dto.TaskInfo;
import com.portal.dto.TaskQueryRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Shared in-memory filter / sort / page helpers for Portal To Do lists.
 */
public final class TaskInfoListOps {

    private TaskInfoListOps() {
    }

    public static List<TaskInfo> applyColumnFilters(List<TaskInfo> tasks, List<ListColumnFilter> filters) {
        List<ListColumnFilter> parsed = TaskQueryColumnFilters.normalize(filters);
        if (parsed.isEmpty()) {
            return tasks;
        }
        return tasks.stream()
                .filter(t -> TaskQueryColumnFilters.matches(t, parsed))
                .collect(Collectors.toList());
    }

    /**
     * Sort whitelist for To Do. Unknown fields fall back to {@code createTime}.
     */
    public static List<TaskInfo> applySorting(List<TaskInfo> tasks, TaskQueryRequest request) {
        String sortBy = request.getSortBy() != null ? request.getSortBy().trim() : "";
        if (sortBy.isEmpty()) {
            sortBy = "createTime";
        }
        boolean ascending = "asc".equalsIgnoreCase(request.getSortDirection());

        Comparator<TaskInfo> comparator = switch (sortBy) {
            case "priority" -> Comparator.comparing(TaskInfoListOps::prioritySortKey, Comparator.nullsLast(Comparator.naturalOrder()));
            case "dueDate" -> Comparator.comparing(TaskInfo::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()));
            case "taskName", "name" -> Comparator.comparing(TaskInfo::getTaskName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "processDefinitionName" -> Comparator.comparing(
                    TaskInfo::getProcessDefinitionName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "createTime" -> Comparator.comparing(
                    TaskInfo::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder()));
            case "requestId" -> Comparator.comparing(
                    TaskInfo::getRequestId, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "functionUnitCode" -> Comparator.comparing(
                    TaskInfoListOps::functionUnitSortKey, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "initiatorName" -> Comparator.comparing(
                    TaskInfo::getInitiatorName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "assigneeName" -> Comparator.comparing(
                    TaskInfoListOps::assigneeSortKey, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "assignmentType" -> Comparator.comparing(
                    TaskInfo::getAssignmentType, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "currentStepName", "currentNode" -> Comparator.comparing(
                    t -> t.getCurrentStepName() != null ? t.getCurrentStepName() : t.getTaskName(),
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            default -> Comparator.comparing(TaskInfo::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder()));
        };

        if (!ascending) {
            comparator = comparator.reversed();
        }
        return tasks.stream().sorted(comparator).collect(Collectors.toList());
    }

    public static List<TaskInfo> pageOf(List<TaskInfo> tasks, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);
        int start = safePage * safeSize;
        if (start >= tasks.size()) {
            return List.of();
        }
        int end = Math.min(start + safeSize, tasks.size());
        return new ArrayList<>(tasks.subList(start, end));
    }


    private static String functionUnitSortKey(TaskInfo task) {
        String name = task.getFunctionUnitName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return task.getFunctionUnitCode();
    }

    private static String assigneeSortKey(TaskInfo task) {
        String name = task.getAssigneeName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return task.getAssignee();
    }

    private static Integer prioritySortKey(TaskInfo task) {
        String raw = task.getPriority();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "URGENT" -> 100;
                case "HIGH" -> 75;
                case "NORMAL" -> 50;
                case "LOW" -> 0;
                default -> null;
            };
        }
    }

}
