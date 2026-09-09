package com.portal.component;

import com.platform.common.jdbc.SubTableRowIdentity;
import com.platform.common.jdbc.SubTableRowKeySupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decides whether two sub-table snapshots are the same logical row.
 *
 * <p>Order is configuration first: the designer primary key
 * ({@code dw_field_definitions.is_primary_key}), then the platform-generated
 * {@link SubTableRowIdentity#CANONICAL_FIELD}. Business field values are never
 * consulted — two rows that both default a status to {@code OPEN} are still two
 * rows.
 *
 * <p>The resolved key is stamped as {@link #FIELD} so later stages do not
 * re-derive it. That name is row metadata, not a recorded change.
 */
final class ChangeHistoryAuditRowKey {

    static final String FIELD = "__auditRowKey";

    private ChangeHistoryAuditRowKey() {
    }

    static String derive(Map<?, ?> row, List<String> pkFields) {
        if (row == null) {
            return null;
        }
        Map<String, Object> normalized = SubTableRowKeySupport.normalizeStringKeyMap(row);
        String configured = displayPrimaryKey(normalized, pkFields);
        return configured != null ? configured : platformKey(normalized);
    }

    static String resolve(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Object stamped = row.get(FIELD);
        if (stamped != null) {
            String text = String.valueOf(stamped).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return derive(row, List.of());
    }

    static void stamp(Map<String, Object> row, List<String> pkFields) {
        if (row == null) {
            return;
        }
        String derived = derive(row, pkFields);
        if (derived == null) {
            return;
        }
        Object existing = row.get(FIELD);
        if (existing != null && (pkFields == null || pkFields.isEmpty())) {
            String text = String.valueOf(existing).trim();
            if (!text.isEmpty()) {
                return;
            }
        }
        row.put(FIELD, derived);
    }

    /**
     * Same row when configured primary keys match, or when neither side can
     * use a primary key and the platform key matches. Different primary keys
     * never collapse, even if a status or other business field is identical.
     */
    static boolean sameLogicalRow(
            Map<String, Object> left, Map<String, Object> right, List<String> pkFields) {
        if (left == null || right == null) {
            return false;
        }
        String pkLeft = matchPrimaryKey(left, pkFields);
        String pkRight = matchPrimaryKey(right, pkFields);
        if (pkLeft != null && pkRight != null) {
            return pkLeft.equals(pkRight);
        }
        String leftPlatform = platformKey(left);
        String rightPlatform = platformKey(right);
        return leftPlatform != null && leftPlatform.equals(rightPlatform);
    }

    /** User-visible identifier: the PK value, or a composite {@code field=value} key. */
    static String displayPrimaryKey(Map<String, Object> row, List<String> pkFields) {
        FilledPrimaryKey filled = filledPrimaryKey(row, pkFields);
        if (filled == null) {
            return null;
        }
        if (filled.values.size() == 1) {
            return filled.values.get(0);
        }
        return filled.matchKey;
    }

    /** Matching key always includes field names so {@code id=1} is not {@code row_id=1}. */
    static String matchPrimaryKey(Map<String, Object> row, List<String> pkFields) {
        FilledPrimaryKey filled = filledPrimaryKey(row, pkFields);
        return filled == null ? null : filled.matchKey;
    }

    private static FilledPrimaryKey filledPrimaryKey(Map<String, Object> row, List<String> pkFields) {
        if (row == null || pkFields == null || pkFields.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (String field : pkFields) {
            Object value = SubTableRowKeySupport.getRowValueIgnoreCase(row, field);
            String text = value == null ? "" : String.valueOf(value).trim();
            if (text.isEmpty()) {
                return null;
            }
            names.add(field);
            values.add(text);
        }
        if (values.isEmpty()) {
            return null;
        }
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                key.append('|');
            }
            key.append(names.get(i)).append('=').append(values.get(i));
        }
        return new FilledPrimaryKey(values, key.toString());
    }

    private record FilledPrimaryKey(List<String> values, String matchKey) {
    }

    static String platformKey(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Object value = SubTableRowKeySupport.getRowValueIgnoreCase(row, SubTableRowIdentity.CANONICAL_FIELD);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
