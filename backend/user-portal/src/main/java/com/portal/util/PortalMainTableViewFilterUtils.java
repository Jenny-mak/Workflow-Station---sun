package com.portal.util;

import com.portal.dto.MainTableViewPortalDtos.MainTableViewColumnFilter;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewGroupCount;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Filter, search, and sort helpers for portal Main Table view row projection.
 *
 * <p>View data lives in process-instance JSON variables (not relational columns). Runtime
 * filter/sort/group therefore run on projected rows in the portal service before pagination —
 * authoritative for {@code total} / page slices, not a SQL {@code WHERE} on physical columns.
 */
public final class PortalMainTableViewFilterUtils {

    private PortalMainTableViewFilterUtils() {}

    public static boolean matchesFilter(Map<String, Object> row, Map<String, Object> filterConfig) {
        if (filterConfig == null || filterConfig.isEmpty()) {
            return true;
        }
        return matchesFilterNode(row, filterConfig);
    }

    @SuppressWarnings("unchecked")
    public static boolean matchesFilterNode(Map<String, Object> row, Map<String, Object> node) {
        if (node == null || node.isEmpty()) {
            return true;
        }
        String logic = stringVal(node.get("logic"));
        boolean useOr = "or".equalsIgnoreCase(logic);

        Object conditionsObj = node.get("conditions");
        List<Map<String, Object>> conditions = new ArrayList<>();
        if (conditionsObj instanceof List<?> rawConditions) {
            for (Object condObj : rawConditions) {
                if (condObj instanceof Map<?, ?> cond) {
                    conditions.add((Map<String, Object>) cond);
                }
            }
        }

        Object groupsObj = node.get("groups");
        List<Map<String, Object>> groups = new ArrayList<>();
        if (groupsObj instanceof List<?> rawGroups) {
            for (Object groupObj : rawGroups) {
                if (groupObj instanceof Map<?, ?> group) {
                    groups.add((Map<String, Object>) group);
                }
            }
        }

        if (conditions.isEmpty() && groups.isEmpty()) {
            return true;
        }

        if (useOr) {
            for (Map<String, Object> cond : conditions) {
                if (evaluateFilterCondition(row, cond)) {
                    return true;
                }
            }
            for (Map<String, Object> group : groups) {
                if (matchesFilterNode(row, group)) {
                    return true;
                }
            }
            return false;
        }

        for (Map<String, Object> cond : conditions) {
            if (!evaluateFilterCondition(row, cond)) {
                return false;
            }
        }
        for (Map<String, Object> group : groups) {
            if (!matchesFilterNode(row, group)) {
                return false;
            }
        }
        return true;
    }

    public static boolean matchesSearch(Map<String, Object> row, String needle) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().startsWith("_")) {
                continue;
            }
            if (e.getValue() != null && displayText(e.getValue()).toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public static void applyViewSort(List<Map<String, Object>> rows, List<Map<String, Object>> sortConfig) {
        if (sortConfig == null || sortConfig.isEmpty()) {
            return;
        }
        rows.sort((a, b) -> {
            for (Map<String, Object> spec : sortConfig) {
                String field = stringVal(spec.get("fieldName"));
                if (field == null) {
                    continue;
                }
                Object va = a.get(field);
                Object vb = b.get(field);
                int cmp = compareSortValues(va, vb);
                if (cmp != 0) {
                    String dir = stringVal(spec.get("direction"));
                    return "DESC".equalsIgnoreCase(dir) ? -cmp : cmp;
                }
            }
            return 0;
        });
    }

    /**
     * Apply portal grid header filters (AND). Empty value is ignored except for isNull / isNotNull.
     */
    public static void applyRuntimeFilters(List<Map<String, Object>> rows, List<MainTableViewColumnFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        List<MainTableViewColumnFilter> active = filters.stream()
                .filter(PortalMainTableViewFilterUtils::isActiveColumnFilter)
                .toList();
        if (active.isEmpty()) {
            return;
        }
        rows.removeIf(row -> !matchesAllColumnFilters(row, active));
    }

    public static boolean isActiveColumnFilter(MainTableViewColumnFilter filter) {
        if (filter == null || filter.fieldName() == null || filter.fieldName().isBlank()) {
            return false;
        }
        String op = filter.operator();
        if ("isNull".equals(op) || "isNotNull".equals(op)) {
            return true;
        }
        return filter.value() != null && !filter.value().isBlank();
    }

    public static boolean matchesAllColumnFilters(Map<String, Object> row, List<MainTableViewColumnFilter> filters) {
        for (MainTableViewColumnFilter filter : filters) {
            if (!evaluateCondition(row.get(filter.fieldName()), filter.operator(), filter.value())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sort for grid query: optional group-by primary key, then runtime sort, else designer sortConfig.
     */
    public static void applyQuerySort(
            List<Map<String, Object>> rows,
            String groupBy,
            String sortField,
            String sortDirection,
            List<Map<String, Object>> viewSortConfig) {
        String groupField = blankToNull(groupBy);
        String runtimeField = blankToNull(sortField);
        String runtimeDir = sortDirection != null ? sortDirection : "ASC";

        if (groupField == null && runtimeField == null) {
            applyViewSort(rows, viewSortConfig);
            return;
        }

        rows.sort((a, b) -> {
            if (groupField != null) {
                int groupCmp = compareSortValues(a.get(groupField), b.get(groupField));
                if (groupCmp != 0) {
                    return groupCmp;
                }
            }
            if (runtimeField != null && !runtimeField.equals(groupField)) {
                int cmp = compareSortValues(a.get(runtimeField), b.get(runtimeField));
                if (cmp != 0) {
                    return "DESC".equalsIgnoreCase(runtimeDir) ? -cmp : cmp;
                }
            } else if (runtimeField != null) {
                int cmp = compareSortValues(a.get(runtimeField), b.get(runtimeField));
                if (cmp != 0) {
                    return "DESC".equalsIgnoreCase(runtimeDir) ? -cmp : cmp;
                }
            }
            return 0;
        });
    }

    public static List<MainTableViewGroupCount> buildGroupCounts(List<Map<String, Object>> rows, String groupBy) {
        String field = blankToNull(groupBy);
        if (field == null) {
            return List.of();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String label = groupLabel(row.get(field));
            counts.merge(label, 1L, Long::sum);
        }
        List<MainTableViewGroupCount> out = new ArrayList<>(counts.size());
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            out.add(MainTableViewGroupCount.builder().label(e.getKey()).count(e.getValue()).build());
        }
        return out;
    }

    /** Group header label — empty cells become em dash (parity with portal grid). */
    public static String groupLabel(Object value) {
        String text = displayText(value);
        return text.isBlank() ? "—" : text;
    }

    /**
     * Display text for search / group keys — mirrors portal {@code formatMainTableViewCell} basics
     * (lookup object name/id, joined arrays) without upload-URL filename heuristics.
     */
    public static String displayText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> col) {
            List<String> parts = new ArrayList<>();
            for (Object item : col) {
                String part = displayText(item);
                if (!part.isBlank()) {
                    parts.add(part);
                }
            }
            return String.join(", ", parts);
        }
        if (value instanceof Map<?, ?> map) {
            Object name = map.get("name");
            if (name != null && !String.valueOf(name).isBlank()) {
                return String.valueOf(name);
            }
            Object id = map.get("id");
            if (id != null && !String.valueOf(id).isBlank()) {
                return String.valueOf(id);
            }
            return "";
        }
        return String.valueOf(value);
    }

    private static boolean evaluateFilterCondition(Map<String, Object> row, Map<String, Object> cond) {
        String fieldName = stringVal(cond.get("fieldName"));
        String operator = stringVal(cond.get("operator"));
        Object expected = cond.get("value");
        Object actual = row.get(fieldName);
        return evaluateCondition(actual, operator, expected);
    }

    static boolean evaluateCondition(Object actual, String operator, Object expected) {
        if (operator == null || operator.isBlank()) {
            return true;
        }
        String op = operator.trim();
        if ("isNull".equals(op)) {
            return actual == null || displayText(actual).isBlank();
        }
        if ("isNotNull".equals(op)) {
            return actual != null && !displayText(actual).isBlank();
        }
        if (actual == null) {
            return false;
        }
        String actualStr = displayText(actual);
        String expectedStr = expected != null ? String.valueOf(expected) : "";
        return switch (op) {
            case "eq" -> actualStr.equalsIgnoreCase(expectedStr);
            case "ne" -> !actualStr.equalsIgnoreCase(expectedStr);
            case "contains" -> actualStr.toLowerCase(Locale.ROOT).contains(expectedStr.toLowerCase(Locale.ROOT));
            case "notContains" -> !actualStr.toLowerCase(Locale.ROOT).contains(expectedStr.toLowerCase(Locale.ROOT));
            case "startsWith" -> actualStr.toLowerCase(Locale.ROOT).startsWith(expectedStr.toLowerCase(Locale.ROOT));
            case "notStartsWith" -> !actualStr.toLowerCase(Locale.ROOT).startsWith(expectedStr.toLowerCase(Locale.ROOT));
            case "endsWith" -> actualStr.toLowerCase(Locale.ROOT).endsWith(expectedStr.toLowerCase(Locale.ROOT));
            case "notEndsWith" -> !actualStr.toLowerCase(Locale.ROOT).endsWith(expectedStr.toLowerCase(Locale.ROOT));
            case "gt" -> compareAsDouble(actualStr, expectedStr) > 0;
            case "lt" -> compareAsDouble(actualStr, expectedStr) < 0;
            case "in" -> {
                if (expected instanceof Collection<?> col) {
                    yield col.stream().anyMatch(v -> actualStr.equalsIgnoreCase(String.valueOf(v)));
                }
                yield Arrays.stream(expectedStr.split(","))
                        .map(String::trim)
                        .anyMatch(v -> actualStr.equalsIgnoreCase(v));
            }
            default -> true;
        };
    }

    private static int compareAsDouble(String a, String b) {
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        } catch (NumberFormatException e) {
            return a.compareToIgnoreCase(b);
        }
    }

    private static int compareSortValues(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof LocalDateTime ta && b instanceof LocalDateTime tb) {
            return ta.compareTo(tb);
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        String sa = displayText(a);
        String sb = displayText(b);
        try {
            if (!sa.isBlank() && !sb.isBlank()) {
                return Double.compare(Double.parseDouble(sa), Double.parseDouble(sb));
            }
        } catch (NumberFormatException ignored) {
            // fall through to string compare
        }
        return sa.compareToIgnoreCase(sb);
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static String stringVal(Object o) {
        return o != null ? String.valueOf(o) : null;
    }
}
