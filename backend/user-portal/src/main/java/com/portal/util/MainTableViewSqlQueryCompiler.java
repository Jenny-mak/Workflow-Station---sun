package com.portal.util;

import com.platform.common.jdbc.SqlIdentifiers;
import com.portal.dto.MainTableViewPortalDtos.MainTableViewColumnFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiles Main Table View filter / sort / search into parameterized SQL fragments.
 *
 * <p>Field references are whitelisted; JSON keys and column names are validated with
 * {@link SqlIdentifiers} at the SQL sink. Values always use {@code ?} bind parameters.
 *
 * <p>lookup_display / fk_display columns filter and sort on the <em>stored source field</em>
 * (PK / raw scalar), not hydrated display labels.
 */
public final class MainTableViewSqlQueryCompiler {

    public enum RowSource {
        /** MAIN view: one row per process instance. */
        MAIN,
        /** SUB view: one row per expanded {@code __subTables__} element ({@code sub_elem}). */
        SUB
    }

    public record FieldMeta(
            String fieldName,
            boolean systemField,
            String columnType,
            String lookupSourceField) {}

    public record SqlFragment(String sql, List<Object> params) {
        public static SqlFragment empty() {
            return new SqlFragment("", List.of());
        }

        public SqlFragment and(SqlFragment other) {
            if (other == null || other.sql().isBlank()) {
                return this;
            }
            if (sql.isBlank()) {
                return other;
            }
            List<Object> merged = new ArrayList<>(params);
            merged.addAll(other.params());
            return new SqlFragment(sql + " AND " + other.sql(), merged);
        }
    }

    private final RowSource rowSource;
    private final Map<String, FieldMeta> fieldsByName;

    public MainTableViewSqlQueryCompiler(RowSource rowSource, List<FieldMeta> fields) {
        this.rowSource = rowSource;
        this.fieldsByName = new LinkedHashMap<>();
        if (fields != null) {
            for (FieldMeta f : fields) {
                if (f != null && f.fieldName() != null) {
                    fieldsByName.put(f.fieldName(), f);
                }
            }
        }
    }

    public SqlFragment compileDesignerFilter(Map<String, Object> filterConfig) {
        if (filterConfig == null || filterConfig.isEmpty()) {
            return SqlFragment.empty();
        }
        // toolbar-only configs have no conditions/groups
        return compileFilterNode(filterConfig);
    }

    @SuppressWarnings("unchecked")
    public SqlFragment compileFilterNode(Map<String, Object> node) {
        if (node == null || node.isEmpty()) {
            return SqlFragment.empty();
        }
        String logic = stringVal(node.get("logic"));
        boolean useOr = "or".equalsIgnoreCase(logic);

        List<Map<String, Object>> conditions = new ArrayList<>();
        Object conditionsObj = node.get("conditions");
        if (conditionsObj instanceof List<?> rawConditions) {
            for (Object condObj : rawConditions) {
                if (condObj instanceof Map<?, ?> cond) {
                    conditions.add((Map<String, Object>) cond);
                }
            }
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        Object groupsObj = node.get("groups");
        if (groupsObj instanceof List<?> rawGroups) {
            for (Object groupObj : rawGroups) {
                if (groupObj instanceof Map<?, ?> group) {
                    groups.add((Map<String, Object>) group);
                }
            }
        }

        if (conditions.isEmpty() && groups.isEmpty()) {
            return SqlFragment.empty();
        }

        List<SqlFragment> parts = new ArrayList<>();
        for (Map<String, Object> cond : conditions) {
            SqlFragment f = compileCondition(
                    stringVal(cond.get("fieldName")),
                    stringVal(cond.get("operator")),
                    cond.get("value"));
            if (!f.sql().isBlank()) {
                parts.add(f);
            }
        }
        for (Map<String, Object> group : groups) {
            SqlFragment f = compileFilterNode(group);
            if (!f.sql().isBlank()) {
                parts.add(f);
            }
        }
        if (parts.isEmpty()) {
            return SqlFragment.empty();
        }
        return joinParts(parts, useOr);
    }

    public SqlFragment compileColumnFilters(List<MainTableViewColumnFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return SqlFragment.empty();
        }
        List<SqlFragment> parts = new ArrayList<>();
        for (MainTableViewColumnFilter filter : filters) {
            if (!PortalMainTableViewFilterUtils.isActiveColumnFilter(filter)) {
                continue;
            }
            SqlFragment f = compileCondition(filter.fieldName(), filter.operator(), filter.value());
            if (!f.sql().isBlank()) {
                parts.add(f);
            }
        }
        if (parts.isEmpty()) {
            return SqlFragment.empty();
        }
        return joinParts(parts, false);
    }

    public SqlFragment compileSearch(String search, Set<String> visibleFieldNames) {
        if (search == null || search.isBlank()) {
            return SqlFragment.empty();
        }
        String needle = "%" + escapeLike(search.trim().toLowerCase(Locale.ROOT)) + "%";
        List<SqlFragment> parts = new ArrayList<>();
        for (String fieldName : visibleFieldNames) {
            String expr = valueExpr(fieldName);
            if (expr == null) {
                continue;
            }
            List<Object> params = new ArrayList<>();
            params.add(needle);
            parts.add(new SqlFragment("LOWER(COALESCE(" + expr + ", '')) LIKE ? ESCAPE '\\'", params));
        }
        // Always include process id for FK-style deep links that pass instance ids.
        parts.add(new SqlFragment(
                "LOWER(COALESCE(pi.id, '')) LIKE ? ESCAPE '\\'",
                List.of(needle)));
        if (parts.isEmpty()) {
            return SqlFragment.empty();
        }
        return joinParts(parts, true);
    }

    public SqlFragment compileInvolvement(String userId) {
        if (userId == null || userId.isBlank()) {
            return new SqlFragment("FALSE", List.of());
        }
        // Initiator OR historic assignee OR MI participant hint in __subTables__ JSON text.
        // MI match is substring on __subTables__ (Phase 1.5 pragmatic); not a full key walk.
        String sql = """
                (pi.start_user_id = ?
                 OR EXISTS (
                     SELECT 1 FROM ACT_HI_TASKINST ht
                     WHERE ht.PROC_INST_ID_ = pi.id AND ht.ASSIGNEE_ = ?
                 )
                 OR (pi.variables -> '__subTables__')::text ILIKE ? ESCAPE '\\')
                """;
        String miPattern = "%" + escapeLike(userId) + "%";
        return new SqlFragment(sql, List.of(userId, userId, miPattern));
    }

    /**
     * ORDER BY clause without leading {@code ORDER BY} keyword. Empty when nothing to sort.
     */
    public SqlFragment compileOrderBy(
            String groupBy,
            String sortField,
            String sortDirection,
            List<Map<String, Object>> viewSortConfig) {
        List<String> pieces = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        String groupField = blankToNull(groupBy);
        if (groupField != null && valueExpr(groupField) != null) {
            pieces.add(valueExpr(groupField) + " ASC NULLS LAST");
        }

        String runtimeField = blankToNull(sortField);
        if (runtimeField != null && valueExpr(runtimeField) != null) {
            String dir = "DESC".equalsIgnoreCase(sortDirection) ? "DESC" : "ASC";
            pieces.add(valueExpr(runtimeField) + " " + dir + " NULLS LAST");
        } else if (groupField == null && viewSortConfig != null) {
            for (Map<String, Object> spec : viewSortConfig) {
                String field = stringVal(spec.get("fieldName"));
                if (field == null || valueExpr(field) == null) {
                    continue;
                }
                String dir = "DESC".equalsIgnoreCase(stringVal(spec.get("direction"))) ? "DESC" : "ASC";
                pieces.add(valueExpr(field) + " " + dir + " NULLS LAST");
            }
        }

        if (pieces.isEmpty()) {
            pieces.add("pi.start_time DESC NULLS LAST");
        }
        pieces.add("pi.id ASC");
        return new SqlFragment(String.join(", ", pieces), params);
    }

    /** Expression used for group-by labels (empty → em dash). */
    public String groupLabelExpr(String groupByField) {
        String expr = valueExpr(groupByField);
        if (expr == null) {
            return null;
        }
        return "COALESCE(NULLIF(TRIM(BOTH FROM COALESCE(" + expr + ", '')), ''), '—')";
    }

    public String valueExpr(String viewFieldName) {
        if (viewFieldName == null || viewFieldName.isBlank()) {
            return null;
        }
        FieldMeta meta = fieldsByName.get(viewFieldName);
        if (meta == null) {
            // Allow designer filters referencing fields not currently visible.
            if (!isSafeJsonKey(viewFieldName)) {
                return null;
            }
            return jsonTextExpr(viewFieldName);
        }
        if (meta.systemField()) {
            return systemColumnExpr(meta.fieldName());
        }
        String jsonKey = meta.fieldName();
        if ("lookup_display".equalsIgnoreCase(nullToEmpty(meta.columnType()))
                || "fk_display".equalsIgnoreCase(nullToEmpty(meta.columnType()))) {
            if (meta.lookupSourceField() != null && !meta.lookupSourceField().isBlank()) {
                jsonKey = meta.lookupSourceField();
            }
        }
        if (!isSafeJsonKey(jsonKey)) {
            return null;
        }
        return jsonTextExpr(jsonKey);
    }

    private String jsonTextExpr(String jsonKey) {
        String safe = SqlIdentifiers.requireIdentifier(jsonKey);
        if (rowSource == RowSource.SUB) {
            return "pi.sub_elem ->> '" + safe + "'";
        }
        return "pi.variables ->> '" + safe + "'";
    }

    private String systemColumnExpr(String fieldName) {
        return switch (fieldName) {
            case "process_status" -> "pi.status";
            case "start_time" -> "CAST(pi.start_time AS text)";
            case "initiator" -> "COALESCE(NULLIF(pi.start_user_name, ''), pi.start_user_id)";
            case "current_step" -> "pi.current_node";
            default -> null;
        };
    }

    private SqlFragment compileCondition(String fieldName, String operator, Object expected) {
        String expr = valueExpr(fieldName);
        if (expr == null || operator == null || operator.isBlank()) {
            return SqlFragment.empty();
        }
        String op = operator.trim();
        List<Object> params = new ArrayList<>();
        return switch (op) {
            case "isNull" -> new SqlFragment(
                    "(" + expr + " IS NULL OR TRIM(BOTH FROM COALESCE(" + expr + ", '')) = '')",
                    List.of());
            case "isNotNull" -> new SqlFragment(
                    "(" + expr + " IS NOT NULL AND TRIM(BOTH FROM COALESCE(" + expr + ", '')) <> '')",
                    List.of());
            case "eq" -> {
                params.add(expected != null ? String.valueOf(expected) : "");
                yield new SqlFragment("LOWER(COALESCE(" + expr + ", '')) = LOWER(?)", params);
            }
            case "ne" -> {
                params.add(expected != null ? String.valueOf(expected) : "");
                yield new SqlFragment("LOWER(COALESCE(" + expr + ", '')) <> LOWER(?)", params);
            }
            case "contains" -> {
                params.add("%" + escapeLike(String.valueOf(expected != null ? expected : "")) + "%");
                yield new SqlFragment("LOWER(COALESCE(" + expr + ", '')) LIKE LOWER(?) ESCAPE '\\'", params);
            }
            case "notContains" -> {
                params.add("%" + escapeLike(String.valueOf(expected != null ? expected : "")) + "%");
                yield new SqlFragment(
                        "LOWER(COALESCE(" + expr + ", '')) NOT LIKE LOWER(?) ESCAPE '\\'", params);
            }
            case "startsWith" -> {
                params.add(escapeLike(String.valueOf(expected != null ? expected : "")) + "%");
                yield new SqlFragment("LOWER(COALESCE(" + expr + ", '')) LIKE LOWER(?) ESCAPE '\\'", params);
            }
            case "notStartsWith" -> {
                params.add(escapeLike(String.valueOf(expected != null ? expected : "")) + "%");
                yield new SqlFragment(
                        "LOWER(COALESCE(" + expr + ", '')) NOT LIKE LOWER(?) ESCAPE '\\'", params);
            }
            case "endsWith" -> {
                params.add("%" + escapeLike(String.valueOf(expected != null ? expected : "")));
                yield new SqlFragment("LOWER(COALESCE(" + expr + ", '')) LIKE LOWER(?) ESCAPE '\\'", params);
            }
            case "notEndsWith" -> {
                params.add("%" + escapeLike(String.valueOf(expected != null ? expected : "")));
                yield new SqlFragment(
                        "LOWER(COALESCE(" + expr + ", '')) NOT LIKE LOWER(?) ESCAPE '\\'", params);
            }
            case "gt" -> {
                String expectedStr = String.valueOf(expected != null ? expected : "");
                yield new SqlFragment(
                        "(CASE WHEN COALESCE(" + expr + ", '') ~ '^-?[0-9]+(\\.[0-9]+)?$' "
                                + "AND ? ~ '^-?[0-9]+(\\.[0-9]+)?$' "
                                + "THEN (NULLIF(" + expr + ", ''))::float8 > (?::float8) "
                                + "ELSE LOWER(COALESCE(" + expr + ", '')) > LOWER(?) END)",
                        List.of(expectedStr, expectedStr, expectedStr));
            }
            case "lt" -> {
                String expectedStr = String.valueOf(expected != null ? expected : "");
                yield new SqlFragment(
                        "(CASE WHEN COALESCE(" + expr + ", '') ~ '^-?[0-9]+(\\.[0-9]+)?$' "
                                + "AND ? ~ '^-?[0-9]+(\\.[0-9]+)?$' "
                                + "THEN (NULLIF(" + expr + ", ''))::float8 < (?::float8) "
                                + "ELSE LOWER(COALESCE(" + expr + ", '')) < LOWER(?) END)",
                        List.of(expectedStr, expectedStr, expectedStr));
            }
            case "in" -> {
                List<String> values = splitInValues(expected);
                if (values.isEmpty()) {
                    yield SqlFragment.empty();
                }
                String placeholders = String.join(", ", values.stream().map(v -> "LOWER(?)").toList());
                params.addAll(values);
                yield new SqlFragment("LOWER(COALESCE(" + expr + ", '')) IN (" + placeholders + ")", params);
            }
            default -> SqlFragment.empty();
        };
    }

    private static List<String> splitInValues(Object expected) {
        List<String> values = new ArrayList<>();
        if (expected instanceof Iterable<?> it) {
            for (Object v : it) {
                if (v != null && !String.valueOf(v).isBlank()) {
                    values.add(String.valueOf(v).trim());
                }
            }
            return values;
        }
        if (expected == null) {
            return values;
        }
        for (String part : String.valueOf(expected).split(",")) {
            if (!part.isBlank()) {
                values.add(part.trim());
            }
        }
        return values;
    }

    private static SqlFragment joinParts(List<SqlFragment> parts, boolean useOr) {
        String joiner = useOr ? " OR " : " AND ";
        StringBuilder sb = new StringBuilder("(");
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(joiner);
            }
            sb.append(parts.get(i).sql());
            params.addAll(parts.get(i).params());
        }
        sb.append(')');
        return new SqlFragment(sb.toString(), params);
    }

    private static boolean isSafeJsonKey(String key) {
        try {
            SqlIdentifiers.requireIdentifier(key);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String escapeLike(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String stringVal(Object o) {
        return o != null ? String.valueOf(o) : null;
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
