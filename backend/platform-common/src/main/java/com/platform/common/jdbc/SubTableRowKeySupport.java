package com.platform.common.jdbc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Helpers for multi-column (and single-column) physical sub-table primary keys in MI / sub-task flows.
 */
public final class SubTableRowKeySupport {

    private static final char UNIT_SEP = '\u001f';

    private SubTableRowKeySupport() {
    }

    /** @return parsed long or null if not a finite whole number */
    private static Long tryWholeNumber(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            return n.longValue();
        }
        try {
            String s = String.valueOf(raw).trim();
            if (s.isEmpty()) {
                return null;
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Object firstNonNull(Object... xs) {
        if (xs == null) {
            return null;
        }
        for (Object x : xs) {
            if (x != null) {
                return x;
            }
        }
        return null;
    }

    /**
     * When {@code id}/{@code id_idw} holds a designer placeholder (non-numeric) but DW surrogate / {@code rowId} carries the
     * bigint PK, substitute so MI overlay keys ({@code id=8778}) and fuzzy PK parsing succeed.
     *
     * <p><b>On the {@code id} ⇄ {@code id_idw} substitutions in this class.</b> They look like the
     * guessed column-name lists deleted elsewhere in this change, but they are not, and removing
     * them breaks real merges (the frontend twin of this logic is covered by the MI regression gate,
     * which caught exactly that). The difference: the column being resolved is ALREADY the
     * configured primary key — the caller passed {@code pkCols} from
     * {@code dw_field_definitions.is_primary_key} — and these branches only look up the SAME row's
     * value under the other spelling of that one key, because one physical row reaches us from two
     * sources that spell it differently (the MI collection expansion exposes {@code id_idw}, the
     * hydrated row exposes {@code id}; runtime shows {@code {id_idw: 1123}} and {@code {id: 1123}}
     * are one row). A deleted name list, by contrast, guessed what the key was called when nothing
     * had told us. Keep these; do not "clean them up" without a passing MI regression run.
     */
    private static void substituteNumericPkIfPlaceholder(
            Map<String, Object> rowKey, Map<String, Object> row, Map<String, Object> nestedNorm, List<String> pkCols) {
        if (rowKey == null || pkCols.size() != 1) {
            return;
        }
        String c = pkCols.get(0);
        Object cur = rowKey.get(c);
        if (tryWholeNumber(cur) != null) {
            return;
        }
        Object alt = null;
        if ("id".equalsIgnoreCase(c)) {
            alt = firstNonNull(
                    getRowValueIgnoreCase(row, "id_idw"),
                    nestedNorm != null ? getRowValueIgnoreCase(nestedNorm, "id_idw") : null,
                    nestedNorm != null ? getRowValueIgnoreCase(nestedNorm, "id") : null,
                    getRowValueIgnoreCase(row, "rowId"),
                    nestedNorm != null ? getRowValueIgnoreCase(nestedNorm, "rowId") : null);
        } else if ("id_idw".equalsIgnoreCase(c)) {
            alt = firstNonNull(
                    getRowValueIgnoreCase(row, "id"),
                    nestedNorm != null ? getRowValueIgnoreCase(nestedNorm, "id") : null,
                    nestedNorm != null ? getRowValueIgnoreCase(nestedNorm, "id_idw") : null,
                    getRowValueIgnoreCase(row, "rowId"),
                    nestedNorm != null ? getRowValueIgnoreCase(nestedNorm, "rowId") : null);
        }
        if (tryWholeNumber(alt) != null) {
            rowKey.put(c, alt);
        }
    }

    /** Resolve {@code key} on row map with exact match, then case-insensitive key match (form vs PG lowercased column). */
    public static Object getRowValueIgnoreCase(Map<String, Object> row, String key) {
        if (row == null || key == null) {
            return null;
        }
        if (row.containsKey(key)) {
            return row.get(key);
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    public static Map<String, Object> normalizeStringKeyMap(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /**
     * Build {@code col1 = ? AND col2 = ?} for validated identifiers.
     */
    public static String buildPkWhereClause(List<String> pkCols) {
        return pkCols.stream().map(c -> c + " = ?").collect(Collectors.joining(" AND "));
    }

    public static Object[] orderedPkParams(List<String> pkCols, Map<String, Object> rowKey) {
        Object[] args = new Object[pkCols.size()];
        for (int i = 0; i < pkCols.size(); i++) {
            args[i] = rowKey.get(pkCols.get(i));
        }
        return args;
    }

    public static boolean isComplete(List<String> pkCols, Map<String, Object> rowKey) {
        if (rowKey == null || pkCols == null || pkCols.isEmpty()) {
            return false;
        }
        for (String c : pkCols) {
            if (!rowKey.containsKey(c) || rowKey.get(c) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Stable string for maps keyed by row identity (e.g. MI progress overlay in portal).
     */
    public static String canonicalRowKeyString(List<String> orderedPkCols, Map<String, Object> rowKey) {
        if (orderedPkCols == null || rowKey == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String col : orderedPkCols) {
            if (!sb.isEmpty()) {
                sb.append(UNIT_SEP);
            }
            sb.append(col).append('=');
            Object v = rowKey.get(col);
            sb.append(v == null ? "" : String.valueOf(v));
        }
        return sb.toString();
    }

    /**
     * From Flowable collection element / runtime {@code currentItem}.
     */
    public static Map<String, Object> rowKeyFromCurrentItem(Map<String, Object> currentItem, List<String> pkCols) {
        if (currentItem == null || pkCols == null || pkCols.isEmpty()) {
            return null;
        }
        Object rawRk = currentItem.get("rowKey");
        if (rawRk instanceof Map<?, ?> m) {
            Map<String, Object> normalized = normalizeStringKeyMap(m);
            Map<String, Object> out = new LinkedHashMap<>();
            for (String c : pkCols) {
                Object val = getRowValueIgnoreCase(normalized, c);
                if (val == null && pkCols.size() == 1 && "id".equalsIgnoreCase(c)) {
                    val = getRowValueIgnoreCase(normalized, "id_idw");
                }
                if (val == null && pkCols.size() == 1 && "id_idw".equalsIgnoreCase(c)) {
                    val = getRowValueIgnoreCase(normalized, "id");
                }
                if (val == null) {
                    return null;
                }
                out.put(c, val);
            }
            return out;
        }
        if (pkCols.size() == 1) {
            String col = pkCols.get(0);
            Object v = getRowValueIgnoreCase(currentItem, "rowId");
            if (v == null) {
                v = getRowValueIgnoreCase(currentItem, col);
            }
            if (v == null && "id".equalsIgnoreCase(col)) {
                v = getRowValueIgnoreCase(currentItem, "id_idw");
            }
            if (v == null && "id_idw".equalsIgnoreCase(col)) {
                v = getRowValueIgnoreCase(currentItem, "id");
            }
            if (v == null) {
                return null;
            }
            return new LinkedHashMap<>(Map.of(col, v));
        }
        return null;
    }

    /**
     * From {@code wf_extended_task_info.extended_properties} JSON (already parsed).
     */
    public static Map<String, Object> rowKeyFromExtendedProps(Map<String, Object> extProps, List<String> pkCols) {
        if (extProps == null || pkCols == null || pkCols.isEmpty()) {
            return null;
        }
        Object rawRk = extProps.get("subTableRowKey");
        if (rawRk instanceof Map<?, ?> m) {
            Map<String, Object> normalized = normalizeStringKeyMap(m);
            Map<String, Object> out = new LinkedHashMap<>();
            for (String bc : pkCols) {
                Object val = getRowValueIgnoreCase(normalized, bc);
                if (val == null) {
                    return null;
                }
                out.put(bc, val);
            }
            return out;
        }
        if (pkCols.size() == 1) {
            Object id = extProps.get("subTableRowId");
            if (id == null) {
                return null;
            }
            return new LinkedHashMap<>(Map.of(pkCols.get(0), id));
        }
        return null;
    }

    /**
     * Sub-table row from initiator variables {@code __subTables__} / merged JSON row.
     */
    public static Map<String, Object> rowKeyFromVariableRow(Map<String, Object> row, List<String> pkCols) {
        if (row == null || pkCols == null || pkCols.isEmpty()) {
            return null;
        }
        Object rawRk = row.get("rowKey");
        if (rawRk instanceof Map<?, ?> m) {
            Map<String, Object> normalized = normalizeStringKeyMap(m);
            Map<String, Object> out = new LinkedHashMap<>();
            for (String c : pkCols) {
                Object nestedVal = getRowValueIgnoreCase(normalized, c);
                if (nestedVal == null && pkCols.size() == 1 && "id".equalsIgnoreCase(c)) {
                    nestedVal = getRowValueIgnoreCase(normalized, "id_idw");
                }
                if (nestedVal == null && pkCols.size() == 1 && "id_idw".equalsIgnoreCase(c)) {
                    nestedVal = getRowValueIgnoreCase(normalized, "id");
                }

                Object envelopeVal = getRowValueIgnoreCase(row, c);
                if (envelopeVal == null && pkCols.size() == 1 && "id".equalsIgnoreCase(c)) {
                    envelopeVal = getRowValueIgnoreCase(row, "id_idw");
                }
                if (envelopeVal == null && pkCols.size() == 1 && "id_idw".equalsIgnoreCase(c)) {
                    envelopeVal = getRowValueIgnoreCase(row, "id");
                }
                if (envelopeVal == null && pkCols.size() == 1) {
                    envelopeVal = getRowValueIgnoreCase(row, "rowId");
                }

                /*
                 * Prefer envelope PK when it parses as a whole number — matches frontend mergeSubTableRowsByRowId /
                 * rowValueForPkFieldSingle (top-level row before nested rowKey). Copied MI rows (subform_copy) often
                 * retain a stale numeric id inside nested rowKey while the authoritative persisted id sits on the row.
                 */
                Object val;
                if (pkCols.size() == 1
                        && ("id".equalsIgnoreCase(c) || "id_idw".equalsIgnoreCase(c))) {
                    Long nestedNum = tryWholeNumber(nestedVal);
                    Long envNum = tryWholeNumber(envelopeVal);
                    if (envNum != null) {
                        val = envelopeVal;
                    } else if (nestedNum != null) {
                        val = nestedVal;
                    } else {
                        val = nestedVal != null ? nestedVal : envelopeVal;
                    }
                } else {
                    val = nestedVal != null ? nestedVal : envelopeVal;
                }

                if (val == null) {
                    return null;
                }
                out.put(c, val);
            }
            substituteNumericPkIfPlaceholder(out, row, normalized, pkCols);
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String col : pkCols) {
            Object v = getRowValueIgnoreCase(row, col);
            if (v == null && pkCols.size() == 1) {
                v = getRowValueIgnoreCase(row, "rowId");
            }
            // Physical PG may expose PK as "id" while designer/json rows use dw_* primary key "id_idw" (e.g. kk subtable).
            if (v == null && pkCols.size() == 1 && "id".equalsIgnoreCase(col)) {
                v = getRowValueIgnoreCase(row, "id_idw");
            }
            // Form sub-table field may be "id" while dw_field_definitions PK is "id_idw" (reverse of above).
            if (v == null && pkCols.size() == 1 && "id_idw".equalsIgnoreCase(col)) {
                v = getRowValueIgnoreCase(row, "id");
            }
            if (v == null) {
                return null;
            }
            out.put(col, v);
        }
        substituteNumericPkIfPlaceholder(out, row, null, pkCols);
        return out;
    }

    /**
     * Build row key for assign API: explicit body wins; else single-column from path {@code rowId}.
     */
    public static Map<String, Object> resolveRowKeyForAssign(
            Long pathRowId,
            Map<String, Object> requestRowKey,
            List<String> pkCols) {
        if (pkCols == null || pkCols.isEmpty()) {
            return null;
        }
        if (requestRowKey != null && !requestRowKey.isEmpty()) {
            Map<String, Object> normalized = normalizeStringKeyMap(requestRowKey);
            Map<String, Object> out = new LinkedHashMap<>();
            for (String c : pkCols) {
                Object val = getRowValueIgnoreCase(normalized, c);
                if (val == null) {
                    throw new IllegalArgumentException("rowKey must include all primary key columns: " + pkCols);
                }
                out.put(c, val);
            }
            return out;
        }
        if (pkCols.size() == 1) {
            if (pathRowId == null) {
                return null;
            }
            return new LinkedHashMap<>(Map.of(pkCols.get(0), pathRowId));
        }
        throw new IllegalArgumentException(
                "Composite primary key requires rowKey in request body (path rowId is not sufficient)");
    }

    /**
     * PostgreSQL {@code information_schema} stores unquoted identifiers lowercased.
     */
    public static String informationSchemaTableName(String physicalTableName) {
        return physicalTableName.toLowerCase(Locale.ROOT);
    }
}
