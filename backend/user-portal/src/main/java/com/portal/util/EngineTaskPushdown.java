package com.portal.util;

import com.platform.common.list.ListColumnFilter;
import com.platform.common.list.ListRelativeDates;
import com.portal.dto.TaskQueryRequest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides when Portal To Do chrome can be pushed into workflow-engine {@code TaskQuery}
 * (engine window page + engine filtered total) instead of a portal fullScan.
 *
 * <p>Pushable: taskName / currentStepName, processDefinitionName, priority ENUM bands,
 * createTime / dueDate (incl. relative day ops), and sorts on createTime/dueDate/priority/name.
 * Memory-only: initiatorName, assigneeName, requestId, functionUnitCode, assignmentType,
 * keyword / legacy list filters.
 */
public final class EngineTaskPushdown {

    private static final Set<String> PUSHABLE_SORTS = Set.of(
            "createtime", "create_time",
            "duedate", "due_date",
            "priority",
            "name", "taskname", "task_name");

    private static final Set<String> PUSHABLE_TEXT_OPS = Set.of(
            "contains", "eq", "startswith", "endswith");

    private static final Set<String> PUSHABLE_FIELDS = Set.of(
            "taskName",
            "currentStepName",
            "processDefinitionName",
            "priority",
            "createTime",
            "dueDate");

    private static final ZoneId ZONE = ListRelativeDates.ZONE;

    private EngineTaskPushdown() {
    }

    /**
     * Criteria forwarded as query params on {@code GET /api/v1/tasks}.
     */
    public record Criteria(
            String taskNameLike,
            String taskNameExact,
            String taskNameLikeMode,
            Integer priority,
            Integer priorityMin,
            Integer priorityMax,
            Date createdAfter,
            Date createdBefore,
            Date dueAfter,
            Date dueBefore,
            String processDefinitionNameLike,
            String processDefinitionNameExact,
            String sortBy,
            String sortDirection
    ) {
        public static Criteria empty() {
            return new Criteria(
                    null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null);
        }

        public boolean hasAny() {
            return hasFilterFragments()
                    || (sortBy != null && !sortBy.isBlank())
                    || (sortDirection != null && !sortDirection.isBlank());
        }

        public boolean hasFilterFragments() {
            return (taskNameLike != null && !taskNameLike.isBlank())
                    || (taskNameExact != null && !taskNameExact.isBlank())
                    || priority != null
                    || priorityMin != null
                    || priorityMax != null
                    || createdAfter != null
                    || createdBefore != null
                    || dueAfter != null
                    || dueBefore != null
                    || (processDefinitionNameLike != null && !processDefinitionNameLike.isBlank())
                    || (processDefinitionNameExact != null && !processDefinitionNameExact.isBlank());
        }
    }

    public static boolean canFullyPush(TaskQueryRequest request) {
        if (request == null) {
            return true;
        }
        if (request.getAssignmentTypes() != null && !request.getAssignmentTypes().isEmpty()) {
            return false;
        }
        if (hasMemoryOnlyListFilters(request)) {
            return false;
        }
        List<ListColumnFilter> filters = TaskQueryColumnFilters.normalize(request.getFilters());
        if (hasBothTaskNameAndCurrentStepName(filters)) {
            return false;
        }
        for (ListColumnFilter filter : filters) {
            if (!isPushableFilter(filter)) {
                return false;
            }
        }
        return isPushableSort(request.getSortBy());
    }

    public static Criteria from(TaskQueryRequest request) {
        if (request == null) {
            return Criteria.empty();
        }
        String taskNameLike = null;
        String taskNameExact = null;
        String taskNameLikeMode = null;
        Integer priority = null;
        Integer priorityMin = null;
        Integer priorityMax = null;
        Date createdAfter = null;
        Date createdBefore = null;
        Date dueAfter = null;
        Date dueBefore = null;
        String processDefinitionNameLike = null;
        String processDefinitionNameExact = null;

        List<ListColumnFilter> filters = TaskQueryColumnFilters.normalize(request.getFilters());
        for (ListColumnFilter f : filters) {
            if (!isPushableFilter(f)) {
                continue;
            }
            String field = f.field();
            String op = normalizeOp(f.operator());
            if ("taskName".equals(field) || "currentStepName".equals(field)) {
                String value = f.value() != null ? f.value().trim() : "";
                if (value.isEmpty()) {
                    continue;
                }
                switch (op) {
                    case "eq" -> taskNameExact = value;
                    case "startswith" -> {
                        taskNameLike = value;
                        taskNameLikeMode = "startsWith";
                    }
                    case "endswith" -> {
                        taskNameLike = value;
                        taskNameLikeMode = "endsWith";
                    }
                    case "contains" -> {
                        taskNameLike = value;
                        taskNameLikeMode = "contains";
                    }
                    default -> {
                    }
                }
            } else if ("processDefinitionName".equals(field)) {
                String value = f.value() != null ? f.value().trim() : "";
                if (value.isEmpty()) {
                    continue;
                }
                if ("eq".equals(op)) {
                    processDefinitionNameExact = value;
                } else if (PUSHABLE_TEXT_OPS.contains(op)) {
                    processDefinitionNameLike = value;
                }
            } else if ("priority".equals(field)) {
                int[] band = priorityBandBounds(f.value());
                if (band != null && "eq".equals(op)) {
                    priorityMin = band[0];
                    priorityMax = band[1];
                }
            } else if ("createTime".equals(field)) {
                DayBounds b = dateBounds(f);
                if (b != null) {
                    createdAfter = b.after();
                    createdBefore = b.before();
                }
            } else if ("dueDate".equals(field)) {
                DayBounds b = dateBounds(f);
                if (b != null) {
                    dueAfter = b.after();
                    dueBefore = b.before();
                }
            }
        }

        String sortBy = null;
        String sortDirection = null;
        if (request.getSortBy() != null && !request.getSortBy().isBlank()) {
            String raw = request.getSortBy().trim();
            if (isPushableSort(raw)) {
                sortBy = normalizeSortField(raw);
                sortDirection = request.getSortDirection() != null && !request.getSortDirection().isBlank()
                        ? request.getSortDirection().trim()
                        : "desc";
            }
        } else if (request.getSortDirection() != null
                && !request.getSortDirection().isBlank()
                && !"desc".equalsIgnoreCase(request.getSortDirection().trim())) {
            sortBy = "createTime";
            sortDirection = request.getSortDirection().trim();
        }

        return new Criteria(
                taskNameLike, taskNameExact, taskNameLikeMode,
                priority, priorityMin, priorityMax,
                createdAfter, createdBefore, dueAfter, dueBefore,
                processDefinitionNameLike, processDefinitionNameExact,
                sortBy, sortDirection);
    }

    private static boolean hasBothTaskNameAndCurrentStepName(List<ListColumnFilter> filters) {
        boolean taskName = false;
        boolean step = false;
        for (ListColumnFilter filter : filters) {
            if (!isPushableFilter(filter)) {
                continue;
            }
            if ("taskName".equals(filter.field())) {
                taskName = true;
            } else if ("currentStepName".equals(filter.field())) {
                step = true;
            }
        }
        return taskName && step;
    }

    private static boolean isPushableFilter(ListColumnFilter filter) {
        if (filter == null || filter.field() == null) {
            return false;
        }
        String field = filter.field();
        if (!PUSHABLE_FIELDS.contains(field)) {
            return false;
        }
        String op = normalizeOp(filter.operator());
        return switch (field) {
            case "taskName", "currentStepName", "processDefinitionName" -> PUSHABLE_TEXT_OPS.contains(op);
            case "priority" -> "eq".equals(op) && priorityBandBounds(filter.value()) != null;
            case "createTime", "dueDate" -> dateBounds(filter) != null;
            default -> false;
        };
    }

    private static boolean hasMemoryOnlyListFilters(TaskQueryRequest request) {
        if (request.getPriorities() != null && !request.getPriorities().isEmpty()) {
            return true;
        }
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            return true;
        }
        if (request.getProcessTypes() != null && !request.getProcessTypes().isEmpty()) {
            return true;
        }
        if (request.getStatuses() != null && !request.getStatuses().isEmpty()) {
            return true;
        }
        if (request.getStartTime() != null || request.getEndTime() != null) {
            return true;
        }
        return Boolean.TRUE.equals(request.getIncludeOverdue());
    }

    private static boolean isPushableSort(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return true;
        }
        return PUSHABLE_SORTS.contains(sortBy.trim().toLowerCase(Locale.ROOT));
    }

    private static String normalizeSortField(String sortBy) {
        String s = sortBy.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "due_date", "duedate" -> "dueDate";
            case "task_name", "taskname", "name" -> "name";
            case "priority" -> "priority";
            default -> "createTime";
        };
    }

    private static String normalizeOp(String operator) {
        return operator != null ? operator.trim().toLowerCase(Locale.ROOT) : "";
    }

    /** Inclusive numeric band for To Do ENUM labels; null if unknown. */
    static int[] priorityBandBounds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "URGENT" -> new int[]{75, Integer.MAX_VALUE};
            case "HIGH" -> new int[]{50, 74};
            case "NORMAL" -> new int[]{25, 49};
            case "LOW" -> new int[]{0, 24};
            default -> null;
        };
    }

    private record DayBounds(Date after, Date before) {
    }

    private static DayBounds dateBounds(ListColumnFilter filter) {
        String op = normalizeOp(filter.operator());
        LocalDate today = LocalDate.now(ZONE);
        if (ListRelativeDates.isRelative(op)) {
            ListRelativeDates.DayRange range = ListRelativeDates.range(op, today);
            return toBounds(range.start(), range.end());
        }
        return switch (op) {
            case "on" -> {
                LocalDate day = parseDay(filter.value());
                yield day == null ? null : toBounds(day, day);
            }
            case "before" -> {
                LocalDate day = parseDay(filter.value());
                // Flowable taskCreatedBefore is exclusive of the instant; use start of day.
                yield day == null ? null : new DayBounds(null, toStartOfDay(day));
            }
            case "after" -> {
                LocalDate day = parseDay(filter.value());
                // exclusive after end of day → start of next day as after
                yield day == null ? null : new DayBounds(toStartOfDay(day.plusDays(1)), null);
            }
            case "between" -> {
                LocalDate start = parseDay(filter.value());
                LocalDate end = parseDay(filter.value2());
                yield (start == null || end == null) ? null : toBounds(start, end);
            }
            default -> null;
        };
    }

    private static DayBounds toBounds(LocalDate startInclusive, LocalDate endInclusive) {
        return new DayBounds(toStartOfDay(startInclusive), toStartOfDay(endInclusive.plusDays(1)));
    }

    private static Date toStartOfDay(LocalDate day) {
        return Date.from(day.atStartOfDay(ZONE).toInstant());
    }

    private static LocalDate parseDay(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim().substring(0, Math.min(10, raw.trim().length())));
        } catch (Exception e) {
            return null;
        }
    }
}
