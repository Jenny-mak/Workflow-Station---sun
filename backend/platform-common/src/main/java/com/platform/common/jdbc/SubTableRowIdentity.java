package com.platform.common.jdbc;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Identity of a sub-table row as it is stored in JSON (the {@code __subTables__} process
 * variable), which is a different problem from {@link SubTableRowKeySupport}: that class
 * resolves the <em>physical</em> primary key columns of the few sub-tables that really have
 * a PostgreSQL table, and returns nothing for the rest. Designer sub-tables are JSON-row
 * stored, so their identity can only come from a key inside the row itself.
 *
 * <p>This is the single source for that key list and its priority. Anything that needs to
 * tell two sub-table rows apart — audit diffing, list de-duplication, the SQL that expands
 * {@code __subTables__} for Main Table Views — must read the order from here rather than
 * hard-coding a subset, and must never fall back to hashing the row's content: two rows
 * that happen to carry the same values are two rows, not one.
 */
public final class SubTableRowIdentity {

    /**
     * The key this class writes when a row arrives with no identity at all.
     *
     * <p><b>Deliberately not a plausible column name.</b> The value is a generated
     * {@link java.util.UUID} owned by the platform, not by the designer, and the name says both of
     * those things outright.
     *
     * <p>It used to be {@code row_id}, which reads like an ordinary column — and is one: three
     * tables in this database declare a designer field of exactly that name, so the same key meant
     * "generated identity" on one table and "the user's business primary key" on another. Telling
     * them apart was then only possible by inspecting the value's shape, which is what made
     * {@code prefixedSequence} keys such as {@code Corr-000004} read as "not a primary key".
     *
     * <p><b>Why not a {@code __} prefix</b>, this codebase's usual mark for runtime-only keys: a
     * leading {@code __} means "meta" to the row sanitizers, and {@code stripSubTableRowMetaFields}
     * drops every such key but the one explicitly excepted ({@code __subTables__}). An identity
     * stripped before it is read is worse than an ambiguous one, and adding a second exception to
     * every sanitizer is the special case the next one will miss.
     */
    public static final String CANONICAL_FIELD = "platformRowUuid";

    /**
     * Keys that carry a row's identity, most authoritative first.
     *
     * <p><b>Only the platform's own key belongs here.</b> This list used to continue
     * {@code "row_id", "rowId", "rowID", "id_idw", "_rowKey", "rowKey", "id"} — guesses at what a
     * designer might have named their primary key. Every one is wrong somewhere: a table keyed by
     * {@code correspondence_id} matches none of them, while {@code row_id} and {@code id} are real
     * business columns on tables in this database, so a name match proved nothing about what the
     * value meant. Which columns identify a row is configuration
     * ({@code dw_field_definitions.is_primary_key}); callers resolve it per binding and pass it to
     * the overloads that take {@code designerPrimaryKeyFields}.
     *
     * <p>It stays a list because {@link #sqlIdentityExpression} and the priority-ordered lookups
     * are shaped that way, and a future platform-owned key would be added here, not to a caller.
     */
    public static final List<String> IDENTITY_FIELDS = List.of(CANONICAL_FIELD);

    private SubTableRowIdentity() {
    }

    /**
     * The keys to look for on a row of a given table: the platform key first, then the columns the
     * DESIGNER declared as that table's primary key.
     *
     * <p>Passing the configured key is what lets a row be recognised by its real identity —
     * {@code correspondence_id}, {@code case_number}, or any other name a user chose — instead of
     * by a fixed list of names this class would otherwise have to guess.
     *
     * @param designerPrimaryKeyFields the table's configured primary key columns; may be null/empty
     *                                 when the caller genuinely has no binding in scope, in which
     *                                 case only the platform key is considered
     */
    public static List<String> identityFieldsFor(List<String> designerPrimaryKeyFields) {
        return lookupOrder(designerPrimaryKeyFields);
    }

    private static List<String> lookupOrder(List<String> designerPrimaryKeyFields) {
        if (designerPrimaryKeyFields == null || designerPrimaryKeyFields.isEmpty()) {
            return IDENTITY_FIELDS;
        }
        List<String> order = new java.util.ArrayList<>(IDENTITY_FIELDS);
        for (String field : designerPrimaryKeyFields) {
            String name = stringValue(field);
            if (name != null && !order.contains(name)) {
                order.add(name);
            }
        }
        return order;
    }

    /** @return the highest-priority identity key present on the row, or null if it has none */
    public static String identityFieldOf(Map<String, Object> row) {
        return identityFieldOf(row, null);
    }

    /** @see #lookupOrder(List) */
    public static String identityFieldOf(Map<String, Object> row, List<String> designerPrimaryKeyFields) {
        if (row == null) {
            return null;
        }
        for (String field : lookupOrder(designerPrimaryKeyFields)) {
            if (stringValue(SubTableRowKeySupport.getRowValueIgnoreCase(row, field)) != null) {
                return field;
            }
        }
        return null;
    }

    /**
     * @return {@code field=value} for the highest-priority identity key present, or null.
     *         The field name is part of the string so a row identified by {@code row_id=7}
     *         is not confused with one identified by {@code id=7}.
     */
    public static String identityOf(Map<String, Object> row) {
        String field = identityFieldOf(row);
        if (field == null) {
            return null;
        }
        return field + "=" + stringValue(SubTableRowKeySupport.getRowValueIgnoreCase(row, field));
    }

    public static boolean hasIdentity(Map<String, Object> row) {
        return identityFieldOf(row) != null;
    }

    /**
     * Every identity value the row carries, not only the highest-priority one. Two records
     * of the same row (say a user submission and its enriched counterpart) may each expose
     * a different subset of the keys, so they are the same row if any value matches.
     */
    public static Set<String> identityValuesOf(Map<String, Object> row) {
        return identityValuesOf(row, null);
    }

    /** @see #lookupOrder(List) */
    public static Set<String> identityValuesOf(Map<String, Object> row, List<String> designerPrimaryKeyFields) {
        Set<String> values = new LinkedHashSet<>();
        if (row == null) {
            return values;
        }
        for (String field : lookupOrder(designerPrimaryKeyFields)) {
            String value = stringValue(SubTableRowKeySupport.getRowValueIgnoreCase(row, field));
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    /**
     * Give the row an identity if it has none, so every row that reaches storage can be
     * addressed later. Rows that already carry one are left untouched — in particular a
     * designer-allocated primary key wins over a generated {@link #CANONICAL_FIELD}.
     *
     * <p><b>Rows written before the key was renamed are NOT recognised.</b> They carry the old
     * platform key {@code row_id}, which this class no longer reads, so {@link #hasIdentity} says
     * "no identity" and they are stamped with a fresh {@link #CANONICAL_FIELD} on their next
     * persist — a row can therefore end up carrying both keys, and its identity string changes at
     * that moment. Accepted deliberately: the product is not live, so pre-rename User Portal data
     * is disposable (measured in dev at the time of the change: 130 of 138 sub-table rows were in
     * this state). Child rows are unaffected because sub-table foreign keys reference the DESIGNER
     * primary key, never this generated one (verified: zero child FK values matched a parent's
     * {@code row_id}). If pre-rename data ever has to survive, add {@code "row_id"} as a
     * read-only trailing entry in {@link #lookupOrder} — never as something this class writes.
     *
     * @return true when a {@link #CANONICAL_FIELD} was assigned
     */
    public static boolean ensureIdentity(Map<String, Object> row) {
        if (row == null || hasIdentity(row)) {
            return false;
        }
        row.put(CANONICAL_FIELD, UUID.randomUUID().toString());
        return true;
    }

    /**
     * The same priority, expressed as SQL over a jsonb row.
     *
     * <p>Derived from {@link #IDENTITY_FIELDS} rather than written out, so SQL that
     * de-duplicates sub-table rows cannot drift from the Java that compares them. The two
     * sides need not produce byte-identical strings — only the same answer to "are these the
     * same row". Note SQL matches keys case-sensitively while Java does not; a row spelling
     * its key differently in case is therefore treated as having no identity in SQL, which
     * surfaces as an error rather than as a silent merge.
     *
     * @param rowExpression SQL expression yielding the row as jsonb, e.g. {@code expanded.elem}
     */
    public static String sqlIdentityExpression(String rowExpression) {
        StringBuilder sql = new StringBuilder("COALESCE(");
        for (int i = 0; i < IDENTITY_FIELDS.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(rowExpression).append("->>'").append(IDENTITY_FIELDS.get(i)).append('\'');
        }
        return sql.append(')').toString();
    }

    /** Blank strings do not identify anything, so they count as absent. */
    private static String stringValue(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? null : s;
    }
}
