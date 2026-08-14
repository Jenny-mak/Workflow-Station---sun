package com.portal.util;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One Portal list column: its data kind plus what the UI may do with it.
 *
 * <p>A list declares its columns once; the sort / group / filter whitelists and the
 * column-meta endpoint payload are both derived from that single declaration, so the
 * filter dialog can never offer an operator the backend would silently drop.
 */
public record PortalListColumnMeta(
        String field,
        Kind kind,
        boolean filterable,
        boolean sortable,
        boolean groupable,
        List<String> operators,
        List<String> options) {

    public enum Kind {
        /** Free text — full string operator set. */
        TEXT,
        /** Closed code list; the UI picks a code and the filter compares that code. */
        ENUM,
        /** User id; the UI picks a person and the filter compares the id. */
        USER,
        /** Timestamp column; the filter compares by calendar day. */
        DATETIME
    }

    // Order is the order the header filter dialog lists them in.
    private static final List<String> TEXT_OPERATORS = List.of(
            "contains", "eq", "ne", "startsWith", "endsWith", "notContains", "isNotNull", "isNull");
    private static final List<String> CODE_OPERATORS = List.of("eq", "ne", "isNotNull", "isNull");
    private static final List<String> DATE_OPERATORS = List.of(
            "on", "before", "after", "between", "isNotNull", "isNull");

    public PortalListColumnMeta {
        operators = operators == null || operators.isEmpty() ? operatorsFor(kind) : List.copyOf(operators);
        options = options == null ? List.of() : List.copyOf(options);
    }

    public static List<String> operatorsFor(Kind kind) {
        return switch (kind) {
            case TEXT -> TEXT_OPERATORS;
            case ENUM, USER -> CODE_OPERATORS;
            case DATETIME -> DATE_OPERATORS;
        };
    }

    /** Filterable + sortable + groupable text column. */
    public static PortalListColumnMeta text(String field) {
        return new PortalListColumnMeta(field, Kind.TEXT, true, true, true, null, null);
    }

    /** Filterable + sortable + groupable user-id column. */
    public static PortalListColumnMeta user(String field) {
        return new PortalListColumnMeta(field, Kind.USER, true, true, true, null, null);
    }

    /** Filterable + sortable + groupable timestamp column. */
    public static PortalListColumnMeta datetime(String field) {
        return new PortalListColumnMeta(field, Kind.DATETIME, true, true, true, null, null);
    }

    /** Filterable + sortable + groupable enum column; options are the enum constant names. */
    public static PortalListColumnMeta enumOf(String field, Class<? extends Enum<?>> type) {
        List<String> codes = Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
        return new PortalListColumnMeta(field, Kind.ENUM, true, true, true, null, codes);
    }

    /** Column the UI never renders but callers may sort on (e.g. createdAt fallback sort). */
    public static PortalListColumnMeta sortOnly(String field, Kind kind) {
        return new PortalListColumnMeta(field, kind, false, true, false, null, null);
    }

    public boolean allowsOperator(String operator) {
        return operator != null && operators.contains(operator.trim());
    }

    public static PortalListColumnMeta find(List<PortalListColumnMeta> columns, String field) {
        if (columns == null || field == null) {
            return null;
        }
        for (PortalListColumnMeta column : columns) {
            if (column.field().equals(field)) {
                return column;
            }
        }
        return null;
    }

    public static Set<String> filterFields(List<PortalListColumnMeta> columns) {
        return collect(columns, PortalListColumnMeta::filterable);
    }

    public static Set<String> sortFields(List<PortalListColumnMeta> columns) {
        return collect(columns, PortalListColumnMeta::sortable);
    }

    public static Set<String> groupFields(List<PortalListColumnMeta> columns) {
        return collect(columns, PortalListColumnMeta::groupable);
    }

    private static Set<String> collect(
            List<PortalListColumnMeta> columns, java.util.function.Predicate<PortalListColumnMeta> keep) {
        Set<String> out = new LinkedHashSet<>();
        for (PortalListColumnMeta column : columns) {
            if (keep.test(column)) {
                out.add(column.field());
            }
        }
        return Set.copyOf(out);
    }
}
