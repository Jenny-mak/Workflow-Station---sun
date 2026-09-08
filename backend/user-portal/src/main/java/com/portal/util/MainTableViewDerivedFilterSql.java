package com.portal.util;

import com.platform.common.list.ListColumnFilter;
import com.platform.common.list.ListColumnMeta;
import com.platform.common.list.ListFilterSql;
import com.platform.common.jdbc.SqlIdentifiers;
import com.portal.util.MainTableViewColumnSpec.FieldSource;
import com.portal.util.MainTableViewColumnSpec.SqlSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles lookup / FK <em>display</em> column filters by converting the visible label to the
 * stored key, then comparing that key in SQL.
 *
 * <p>The header sends the text the user sees. The database holds a lookup PK or FK scalar. This
 * class is the correspondence: lookup labels join {@code rt_table_data_rows} / {@code sys_users}
 * on that PK; FK labels use the same CASE the Java projector uses (match MAIN PK, else raw FK).
 * {@link ListFilterSql} never sees these filters, so it cannot compare the typed label to the key.
 */
public final class MainTableViewDerivedFilterSql {

    static final long SYSTEM_USER_TABLE_ID = -1_000_000_001L;
    private static final Set<String> SYSTEM_USER_COLUMNS = Set.of(
            "id", "username", "display_name", "full_name", "email", "employee_id", "status", "language");
    /**
     * Guessed referenced-PK column names, used only when a FK field carries no
     * {@code ref_primary_key_fields}.
     *
     * <p><b>Known guess, kept deliberately.</b> Which column an FK points at is configuration; these
     * two names are the historical defaults and match no table whose PK is called anything else
     * (dev generates names like {@code id_idwxwcxmw} / {@code correspondence_id}). All 8 FK fields
     * in dev do carry {@code ref_primary_key_fields}, so this path is currently unreachable.
     *
     * <p>Left in place because the failure is cosmetic and self-limiting: {@link #fkDisplayExpr}
     * uses it only to decide whether the FK value matches the referenced row, and a miss falls
     * through to {@code COALESCE(..., fk)} — the raw key is shown instead of its label. No row is
     * dropped, merged, or mis-scoped. Replacing it means threading the referenced table's PK into
     * this SQL builder, which is worth doing together with the other derived-filter work rather
     * than as an untested edit to filter SQL.
     */
    private static final List<String> FK_FALLBACK_PKS = List.of("id", "id_idw");
    private static final ListColumnMeta TEXT_LABEL = ListColumnMeta.displayMapped("label", "label");

    private MainTableViewDerivedFilterSql() {
    }

    /** Filters {@link ListFilterSql} is allowed to compile — display-mapped columns removed. */
    public static List<ListColumnFilter> plainFilters(List<ListColumnFilter> filters,
                                                      List<FieldSource> fields) {
        Map<String, FieldSource> mapped = mappedByField(fields);
        List<ListColumnFilter> plain = new ArrayList<>();
        for (ListColumnFilter filter : filters) {
            if (!mapped.containsKey(filter.field())) {
                plain.add(filter);
            }
        }
        return List.copyOf(plain);
    }

    /** {@code AND (...)} fragments for every display-mapped filter, or empty when none apply. */
    public static SqlFragment whereClause(List<ListColumnFilter> filters, List<FieldSource> fields,
                                          SqlSource source) {
        Map<String, FieldSource> mapped = mappedByField(fields);
        if (mapped.isEmpty()) {
            return SqlFragment.EMPTY;
        }
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (ListColumnFilter filter : filters) {
            FieldSource field = mapped.get(filter.field());
            if (field == null) {
                continue;
            }
            requireAllowed(field, filter);
            sql.append(predicate(field, filter, source, params));
        }
        return sql.isEmpty() ? SqlFragment.EMPTY : new SqlFragment(sql.toString(), params);
    }

    private static Map<String, FieldSource> mappedByField(List<FieldSource> fields) {
        Map<String, FieldSource> mapped = new LinkedHashMap<>();
        for (FieldSource field : fields) {
            if (MainTableViewColumnSpec.isDisplayMapped(field)) {
                mapped.put(field.fieldName(), field);
            }
        }
        return mapped;
    }

    private static void requireAllowed(FieldSource field, ListColumnFilter filter) {
        ListColumnMeta column = ListColumnMeta.displayMapped(field.fieldName(), field.fieldName());
        if (!column.allowsOperator(filter.operator())) {
            throw new IllegalArgumentException(
                    "Operator " + filter.operator() + " is not allowed on column " + filter.field());
        }
    }

    private static String predicate(FieldSource field, ListColumnFilter filter, SqlSource source,
                                    List<Object> params) {
        if ("isNull".equals(filter.operator()) || "isNotNull".equals(filter.operator())) {
            return storedKeyPresence(field, filter, source);
        }
        if (MainTableViewColumnSpec.isLookupDisplay(field.columnType())) {
            return lookupExists(field, filter, source, params);
        }
        return textOn(fkDisplayExpr(field, source), filter, params);
    }

    private static String storedKeyPresence(FieldSource field, ListColumnFilter filter, SqlSource source) {
        String src = SqlIdentifiers.requireIdentifier(field.lookupSourceField());
        String doc = jsonDoc(source);
        String hasArray = "(jsonb_typeof(" + doc + "->'" + src + "') = 'array'"
                + " AND jsonb_array_length(" + doc + "->'" + src + "') > 0)";
        String scalar = storedScalarExpr(doc, src);
        String empty = "(" + scalar + " IS NULL OR " + scalar + " = '') AND NOT " + hasArray;
        return "isNull".equals(filter.operator())
                ? " AND (" + empty + ")"
                : " AND NOT (" + empty + ")";
    }

    private static String lookupExists(FieldSource field, ListColumnFilter filter, SqlSource source,
                                       List<Object> params) {
        String src = SqlIdentifiers.requireIdentifier(field.lookupSourceField());
        String doc = jsonDoc(source);
        String fromAndWhere = lookupFromWhere(field, doc, src, params);
        String inner = "SELECT 1 FROM " + fromAndWhere + textOn(lookupDisplayExpr(field), filter, params);
        String exists = "EXISTS (" + inner + ")";
        if ("notContains".equals(filter.operator()) || "ne".equals(filter.operator())) {
            return " AND NOT " + exists;
        }
        return " AND " + exists;
    }

    private static String lookupFromWhere(FieldSource field, String doc, String src, List<Object> params) {
        if (field.lookupTableId() == SYSTEM_USER_TABLE_ID) {
            String userId = "u." + SqlIdentifiers.requireIdentifier("id") + "::text";
            return SqlIdentifiers.requireQualifiedName("sys_users") + " u WHERE "
                    + storedMatches(doc, src, userId);
        }
        params.add(field.lookupTableId());
        String dataId = "rt.data->>'" + SqlIdentifiers.requireIdentifier("id") + "'";
        String rowId = "rt." + SqlIdentifiers.requireIdentifier("id") + "::text";
        return SqlIdentifiers.requireQualifiedName("rt_table_data_rows")
                + " rt WHERE rt.table_id = ? AND ("
                + storedMatches(doc, src, dataId) + " OR " + storedMatches(doc, src, rowId) + ")";
    }

    private static String lookupDisplayExpr(FieldSource field) {
        String attr = field.lookupDisplayField() == null || field.lookupDisplayField().isBlank()
                ? "id"
                : field.lookupDisplayField();
        if (field.lookupTableId() == SYSTEM_USER_TABLE_ID) {
            return "u." + SqlIdentifiers.requireIdentifier(systemUserColumn(attr)) + "::text";
        }
        return "rt.data->>'" + SqlIdentifiers.requireIdentifier(attr) + "'";
    }

    private static String systemUserColumn(String displayField) {
        String mapped = switch (displayField) {
            case "fullName" -> "full_name";
            case "displayName" -> "display_name";
            case "employeeId" -> "employee_id";
            default -> displayField;
        };
        if (!SYSTEM_USER_COLUMNS.contains(mapped)) {
            throw new IllegalArgumentException("Unknown sys_users display field: " + displayField);
        }
        return mapped;
    }

    private static String fkDisplayExpr(FieldSource field, SqlSource source) {
        String src = SqlIdentifiers.requireIdentifier(field.lookupSourceField());
        String display = SqlIdentifiers.requireIdentifier(field.lookupDisplayField());
        String rowDoc = jsonDoc(source);
        String mainDoc = "(" + jsonDoc(SqlSource.INSTANCE) + ")::jsonb";
        String fk = rowDoc + "->>'" + src + "'";
        String label = mainDoc + "->>'" + display + "'";
        StringBuilder when = new StringBuilder();
        for (String pk : pkFieldsOf(field)) {
            String col = SqlIdentifiers.requireIdentifier(pk);
            if (!when.isEmpty()) {
                when.append(" OR ");
            }
            when.append(fk).append(" = ").append(mainDoc).append("->>'").append(col).append("'");
        }
        return "COALESCE(CASE WHEN " + when + " THEN " + label + " END, " + fk + ")";
    }

    private static List<String> pkFieldsOf(FieldSource field) {
        if (!field.fkPrimaryKeyFields().isEmpty()) {
            return field.fkPrimaryKeyFields();
        }
        return FK_FALLBACK_PKS;
    }

    private static String storedScalarExpr(String doc, String src) {
        return "COALESCE("
                + "CASE WHEN jsonb_typeof(" + doc + "->'" + src + "') IN ('string','number') THEN "
                + doc + "->>'" + src + "' END, "
                + doc + "->'" + src + "'->>'id', "
                + doc + "->'" + src + "'->>'userId')";
    }

    private static String storedMatches(String doc, String src, String keyExpr) {
        String scalar = storedScalarExpr(doc, src);
        String elem = "COALESCE("
                + "CASE WHEN jsonb_typeof(elem) IN ('string','number') THEN elem #>> '{}' END, "
                + "elem->>'id', elem->>'userId')";
        return "((" + scalar + " = " + keyExpr + ")"
                + " OR (jsonb_typeof(" + doc + "->'" + src + "') = 'array' AND EXISTS ("
                + "SELECT 1 FROM jsonb_array_elements(" + doc + "->'" + src + "') AS elem"
                + " WHERE " + elem + " = " + keyExpr + ")))";
    }

    private static String textOn(String valueSql, ListColumnFilter filter, List<Object> params) {
        String operator = "notContains".equals(filter.operator()) || "ne".equals(filter.operator())
                ? positiveOf(filter.operator())
                : filter.operator();
        ListFilterSql inner = new ListFilterSql(
                Map.of("label", TEXT_LABEL), ignored -> valueSql, "pi.id", null);
        return inner.whereClause(
                List.of(new ListColumnFilter("label", operator, filter.value(), filter.value2())),
                params);
    }

    private static String positiveOf(String operator) {
        return "notContains".equals(operator) ? "contains" : "eq";
    }

    static String jsonDoc(SqlSource source) {
        String json = source.jsonSource();
        if ("pi.variables".equals(json) || "pi.sub_elem".equals(json)) {
            return json;
        }
        throw new IllegalArgumentException("Unsupported JSON source: " + json);
    }
}
