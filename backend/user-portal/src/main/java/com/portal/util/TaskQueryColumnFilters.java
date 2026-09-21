package com.portal.util;

import com.platform.common.list.ListColumnFilter;
import com.platform.common.list.ListFilterSql;
import com.platform.common.list.ListRelativeDates;
import com.portal.dto.TaskInfo;
import com.portal.dto.TaskQueryRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * In-memory column filters for Portal To Do lists ({@link ListColumnFilter}).
 *
 * <p>Whitelist fields: taskName, requestId, functionUnitCode, processDefinitionName, initiatorName,
 * assigneeName, priority, assignmentType, currentStepName (alias currentNode), createTime, dueDate.
 * DATETIME operators mirror {@link ListFilterSql} / {@link ListRelativeDates}.
 * {@code assigneeName} matches display name or user id (the painted cell may say You / 我).
 */
public final class TaskQueryColumnFilters {

    public static final Set<String> FILTER_FIELDS = Set.of(
            "taskName",
            "requestId",
            "functionUnitCode",
            "processDefinitionName",
            "initiatorName",
            "assigneeName",
            "priority",
            "assignmentType",
            "currentNode",
            "currentStepName",
            "createTime",
            "dueDate");

    private static final Set<String> DATETIME_FIELDS = Set.of("createTime", "dueDate");
    /** Same paint as Portal {@code formatDate} default: {@code YYYY-MM-DD HH:mm}. */
    private static final DateTimeFormatter KEYWORD_CREATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /**
     * Portal {@code task.claimedByMe}: en You, zh-CN/zh-TW 我. Keep in sync with
     * {@code frontend/user-portal/src/i18n/locales}.
     */
    private static final List<String> PAINTED_CLAIMED_BY_ME = List.of("You", "我");

    private static Clock clock = Clock.system(ListRelativeDates.ZONE);

    private TaskQueryColumnFilters() {
    }

    /** Test-only clock override so relative date filters stay deterministic. */
    static void setClock(Clock override) {
        clock = override != null ? override : Clock.system(ListRelativeDates.ZONE);
    }

    public static boolean hasFilters(List<ListColumnFilter> filters) {
        return filters != null && !filters.isEmpty();
    }

    /**
     * Toolbar keyword: OR across {@link TodoTaskColumnSpec#VISIBLE_FIELDS} painted cells
     * (requestId / function unit filled before this runs). Create Time uses {@code yyyy-MM-dd HH:mm}.
     * Hidden columns and description / process key are not searched.
     */
    public static boolean toolbarKeywordMatches(TaskInfo task, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String expected = keyword.trim();
        for (String field : TodoTaskColumnSpec.VISIBLE_FIELDS) {
            if (keywordFieldMatches(task, field, expected)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Request ID and Function Unit are filled in portal memory. Enrich before keyword,
     * those column filters, or A→Z sort on either field.
     */
    public static boolean needsPortalDerivedTaskColumns(TaskQueryRequest request) {
        List<ListColumnFilter> filters = normalize(request.getFilters());
        if (filters.stream().anyMatch(f -> "requestId".equals(f.field()) || "functionUnitCode".equals(f.field()))) {
            return true;
        }
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            return true;
        }
        String sortBy = request.getSortBy();
        if (sortBy == null || sortBy.isBlank()) {
            return false;
        }
        String field = sortBy.trim();
        return "requestId".equalsIgnoreCase(field) || "functionUnitCode".equalsIgnoreCase(field);
    }

    public static List<ListColumnFilter> normalize(List<ListColumnFilter> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ListColumnFilter> out = new ArrayList<>();
        for (ListColumnFilter filter : raw) {
            if (filter == null) {
                continue;
            }
            String field = normalizeField(filter.field());
            if (field == null) {
                throw new IllegalArgumentException("Unknown todo-task filter field: " + filter.field());
            }
            if (filter.operator() == null || filter.operator().isBlank()) {
                throw new IllegalArgumentException("filter operator is required for field " + field);
            }
            out.add(new ListColumnFilter(field, filter.operator(), filter.value(), filter.value2()));
        }
        return List.copyOf(out);
    }

    public static boolean matches(TaskInfo task, List<ListColumnFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (ListColumnFilter filter : filters) {
            if (!matchesOne(task, filter)) {
                return false;
            }
        }
        return true;
    }

    static String normalizeField(String field) {
        if (field == null || field.isBlank()) {
            return null;
        }
        if ("currentStepName".equals(field) || "currentNode".equals(field)) {
            return "currentStepName";
        }
        if (!FILTER_FIELDS.contains(field)) {
            return null;
        }
        return field;
    }

    private static boolean matchesOne(TaskInfo task, ListColumnFilter filter) {
        if (filter == null || filter.field() == null || filter.operator() == null) {
            throw new IllegalArgumentException("filter field and operator are required");
        }
        if (DATETIME_FIELDS.contains(filter.field())) {
            return dateMatches(resolveDateTime(task, filter.field()), filter);
        }
        if ("priority".equals(filter.field())) {
            return priorityMatches(task.getPriority(), filter);
        }
        if ("assignmentType".equals(filter.field())) {
            return assignmentTypeMatches(task, filter);
        }
        if ("functionUnitCode".equals(filter.field())) {
            return functionUnitMatches(task, filter);
        }
        if ("assigneeName".equals(filter.field())) {
            return assigneeNameMatches(task, filter);
        }
        String actual = resolveFieldValue(task, filter.field());
        return textMatches(actual, filter.operator(), filter.value() != null ? filter.value() : "");
    }

    static boolean assignmentTypeMatches(TaskInfo task, ListColumnFilter filter) {
        String op = filter.operator() != null ? filter.operator().trim() : "";
        String expected = filter.value() != null ? filter.value().trim() : "";
        if ("isNull".equals(op)) {
            return task.getAssignmentType() == null || task.getAssignmentType().isBlank();
        }
        if ("isNotNull".equals(op)) {
            return task.getAssignmentType() != null && !task.getAssignmentType().isBlank();
        }
        if ("eq".equals(op)) {
            return TodoAssignmentTypes.matchesOne(task, expected);
        }
        if ("ne".equals(op)) {
            return !TodoAssignmentTypes.matchesOne(task, expected);
        }
        if ("in".equals(op)) {
            for (String part : expected.split(",")) {
                if (TodoAssignmentTypes.matchesOne(task, part.trim())) {
                    return true;
                }
            }
            return false;
        }
        String actual = TodoAssignmentTypes.displayToken(task);
        return textMatches(actual, op, expected);
    }

    /**
     * Cell shows {@code functionUnitName || functionUnitCode}; filter matches either value.
     * Negative operators require neither side to match.
     */
    static boolean functionUnitMatches(TaskInfo task, ListColumnFilter filter) {
        String op = filter.operator() != null ? filter.operator().trim() : "";
        String expected = filter.value() != null ? filter.value() : "";
        String code = task.getFunctionUnitCode();
        String name = task.getFunctionUnitName();
        if ("isNull".equals(op)) {
            return isBlank(code) && isBlank(name);
        }
        if ("isNotNull".equals(op)) {
            return !isBlank(code) || !isBlank(name);
        }
        boolean codeHit = textMatches(code, positiveTextOp(op), expected);
        boolean nameHit = textMatches(name, positiveTextOp(op), expected);
        return switch (op) {
            case "eq", "contains", "startsWith", "endsWith" -> codeHit || nameHit;
            case "ne", "notContains" -> !codeHit && !nameHit;
            default -> throw new IllegalArgumentException(
                    "Operator " + op + " is not allowed on TEXT column functionUnitCode");
        };
    }

    /**
     * Cell may paint You / 我 when the current user holds a claim-pool row. Filter matches
     * that painted label, {@code assigneeName}, or {@code assignee}.
     */
    static boolean assigneeNameMatches(TaskInfo task, ListColumnFilter filter) {
        String op = filter.operator() != null ? filter.operator().trim() : "";
        String expected = filter.value() != null ? filter.value() : "";
        String name = task.getAssigneeName();
        String id = task.getAssignee();
        if ("isNull".equals(op)) {
            return isBlank(name) && isBlank(id);
        }
        if ("isNotNull".equals(op)) {
            return !isBlank(name) || !isBlank(id);
        }
        boolean nameHit = textMatches(name, positiveTextOp(op), expected);
        boolean idHit = textMatches(id, positiveTextOp(op), expected);
        boolean paintedHit = paintedClaimedByMeMatches(task, positiveTextOp(op), expected);
        return switch (op) {
            case "eq", "contains", "startsWith", "endsWith" -> nameHit || idHit || paintedHit;
            case "ne", "notContains" -> !nameHit && !idHit && !paintedHit;
            default -> throw new IllegalArgumentException(
                    "Operator " + op + " is not allowed on TEXT column assigneeName");
        };
    }

    private static boolean paintedClaimedByMeMatches(TaskInfo task, String positiveOp, String expected) {
        if (!task.isClaimedByCurrentUser()) {
            return false;
        }
        for (String label : PAINTED_CLAIMED_BY_ME) {
            if (textMatches(label, positiveOp, expected)) {
                return true;
            }
        }
        return false;
    }

    private static String positiveTextOp(String op) {
        return switch (op) {
            case "ne" -> "eq";
            case "notContains" -> "contains";
            default -> op;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Flowable stores priority as a number string ({@code "50"}); chrome ENUM options are
     * URGENT/HIGH/NORMAL/LOW. Match labels to the same bands the To Do cell renderer uses.
     */
    static boolean priorityMatches(String rawPriority, ListColumnFilter filter) {
        String op = filter.operator() != null ? filter.operator().trim() : "";
        String expected = filter.value() != null ? filter.value().trim() : "";
        if ("isNull".equals(op)) {
            return rawPriority == null || rawPriority.isBlank();
        }
        if ("isNotNull".equals(op)) {
            return rawPriority != null && !rawPriority.isBlank();
        }
        String band = priorityBand(rawPriority);
        return switch (op) {
            case "eq" -> band.equalsIgnoreCase(expected);
            case "ne" -> !band.equalsIgnoreCase(expected);
            case "in" -> {
                for (String part : expected.split(",")) {
                    if (band.equalsIgnoreCase(part.trim())) {
                        yield true;
                    }
                }
                yield false;
            }
            default -> throw new IllegalArgumentException(
                    "Operator " + op + " is not allowed on ENUM column priority");
        };
    }

    /** Same thresholds as {@code getPriorityLabel} on the To Do page. */
    static String priorityBand(String rawPriority) {
        if (rawPriority == null || rawPriority.isBlank()) {
            return "NORMAL";
        }
        String upper = rawPriority.trim().toUpperCase(Locale.ROOT);
        if (List.of("URGENT", "HIGH", "NORMAL", "LOW").contains(upper)) {
            return upper;
        }
        try {
            int n = Integer.parseInt(rawPriority.trim());
            if (n >= 75) {
                return "URGENT";
            }
            if (n >= 50) {
                return "HIGH";
            }
            if (n >= 25) {
                return "NORMAL";
            }
            return "LOW";
        } catch (NumberFormatException e) {
            return upper;
        }
    }

    private static LocalDateTime resolveDateTime(TaskInfo task, String field) {
        return switch (field) {
            case "createTime" -> task.getCreateTime();
            case "dueDate" -> task.getDueDate();
            default -> null;
        };
    }

    private static boolean dateMatches(LocalDateTime actual, ListColumnFilter filter) {
        String op = filter.operator().trim();
        if ("isNull".equals(op)) {
            return actual == null;
        }
        if ("isNotNull".equals(op)) {
            return actual != null;
        }
        if (actual == null) {
            return false;
        }
        LocalDate day = actual.atZone(ListRelativeDates.ZONE).toLocalDate();
        if (ListRelativeDates.isRelative(op)) {
            ListRelativeDates.DayRange range = ListRelativeDates.range(op, LocalDate.now(clock));
            return !day.isBefore(range.start()) && !day.isAfter(range.end());
        }
        LocalDate bound = parseDate(filter.field(), filter.value());
        return switch (op) {
            case "on" -> day.equals(bound);
            case "before" -> day.isBefore(bound);
            case "after" -> day.isAfter(bound);
            case "between" -> {
                LocalDate end = parseDate(filter.field(), filter.value2());
                yield !day.isBefore(bound) && !day.isAfter(end);
            }
            default -> throw new IllegalArgumentException(
                    "Operator " + op + " is not allowed on DATETIME column " + filter.field());
        };
    }

    private static LocalDate parseDate(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("date value is required for column " + field);
        }
        String day = raw.trim().length() >= 10 ? raw.trim().substring(0, 10) : raw.trim();
        try {
            return LocalDate.parse(day);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid date for column " + field + ": " + raw, e);
        }
    }

    private static boolean keywordFieldMatches(TaskInfo task, String field, String expected) {
        if (DATETIME_FIELDS.contains(field)) {
            return textMatches(formatDateTimeForKeyword(resolveDateTime(task, field)), "contains", expected);
        }
        if ("functionUnitCode".equals(field)) {
            return textMatches(resolveFieldValue(task, "functionUnitCode"), "contains", expected)
                    || textMatches(resolveFieldValue(task, "functionUnitName"), "contains", expected);
        }
        if ("assigneeName".equals(field)) {
            return textMatches(task.getAssigneeName(), "contains", expected)
                    || textMatches(task.getAssignee(), "contains", expected)
                    || paintedClaimedByMeMatches(task, "contains", expected);
        }
        return textMatches(resolveFieldValue(task, field), "contains", expected);
    }

    private static String formatDateTimeForKeyword(LocalDateTime value) {
        return value == null ? "" : value.format(KEYWORD_CREATE_TIME);
    }

    private static String resolveFieldValue(TaskInfo task, String field) {
        Function<TaskInfo, String> getter = switch (field) {
            case "taskName" -> TaskInfo::getTaskName;
            case "requestId" -> TaskInfo::getRequestId;
            case "functionUnitCode" -> TaskInfo::getFunctionUnitCode;
            case "functionUnitName" -> TaskInfo::getFunctionUnitName;
            case "processDefinitionName" -> TaskInfo::getProcessDefinitionName;
            case "initiatorName" -> TaskInfo::getInitiatorName;
            case "assigneeName" -> TaskInfo::getAssigneeName;
            case "priority" -> TaskInfo::getPriority;
            case "assignmentType" -> TaskInfo::getAssignmentType;
            case "currentStepName", "currentNode" ->
                    t -> t.getCurrentStepName() != null ? t.getCurrentStepName() : t.getTaskName();
            default -> t -> null;
        };
        return getter.apply(task);
    }

    static boolean textMatches(String actual, String operator, String expected) {
        String op = operator != null ? operator.trim() : "";
        String value = expected != null ? expected : "";
        String left = actual != null ? actual : "";
        return switch (op) {
            case "isNull" -> left.isBlank();
            case "isNotNull" -> !left.isBlank();
            case "eq" -> left.equalsIgnoreCase(value);
            case "ne" -> !left.equalsIgnoreCase(value);
            case "contains" -> left.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
            case "notContains" -> !left.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
            case "startsWith" -> left.toLowerCase(Locale.ROOT).startsWith(value.toLowerCase(Locale.ROOT));
            case "endsWith" -> left.toLowerCase(Locale.ROOT).endsWith(value.toLowerCase(Locale.ROOT));
            default -> throw new IllegalArgumentException("unsupported text operator: " + op);
        };
    }
}
