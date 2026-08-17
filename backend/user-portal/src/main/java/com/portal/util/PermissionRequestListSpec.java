package com.portal.util;

import com.portal.enums.PermissionRequestStatus;
import com.portal.enums.PermissionRequestType;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JDBC whitelist filters / sort / groupBy for Portal permission "my requests".
 *
 * <p>Columns are physical snake_case names used in {@code up_permission_request}.
 * {@link #COLUMNS} uses the FE field names the header dialog sends.
 */
public final class PermissionRequestListSpec {

    public static final List<PortalListColumnMeta> COLUMNS = List.of(
            PortalListColumnMeta.enumOf("requestType", PermissionRequestType.class),
            PortalListColumnMeta.text("requestTarget"),
            PortalListColumnMeta.user("beneficiary"),
            PortalListColumnMeta.user("applicant"),
            PortalListColumnMeta.user("applicantId"),
            PortalListColumnMeta.user("submittedBy"),
            PortalListColumnMeta.text("reason"),
            PortalListColumnMeta.datetime("createdAt"),
            PortalListColumnMeta.datetime("updatedAt"),
            PortalListColumnMeta.enumOf("status", PermissionRequestStatus.class),
            PortalListColumnMeta.text("approverComment"),
            PortalListColumnMeta.datetime("approvedAt"));

    /** FE / API field → SQL column. {@code requestTarget} is handled specially (BU/VG/OU OR). */
    public static final Map<String, String> FIELD_TO_COLUMN = Map.ofEntries(
            Map.entry("requestType", "request_type"),
            Map.entry("request_type", "request_type"),
            Map.entry("status", "status"),
            Map.entry("reason", "reason"),
            Map.entry("businessUnitName", "business_unit_name"),
            Map.entry("business_unit_name", "business_unit_name"),
            Map.entry("roleName", "role_name"),
            Map.entry("role_name", "role_name"),
            Map.entry("createdAt", "created_at"),
            Map.entry("created_at", "created_at"),
            Map.entry("updatedAt", "updated_at"),
            Map.entry("updated_at", "updated_at"),
            Map.entry("approveTime", "approve_time"),
            Map.entry("approve_time", "approve_time"),
            Map.entry("approvedAt", "approve_time"),
            Map.entry("approverComment", "approve_comment"),
            Map.entry("approveComment", "approve_comment"),
            Map.entry("approve_comment", "approve_comment"),
            Map.entry("applicantId", "applicant_id"),
            Map.entry("applicant_id", "applicant_id"),
            Map.entry("applicant", "applicant_id"),
            Map.entry("beneficiary", "applicant_id"),
            Map.entry("submittedBy", "submitted_by_user_id"),
            Map.entry("submittedByUserId", "submitted_by_user_id"),
            Map.entry("submitted_by_user_id", "submitted_by_user_id"));

    public static final Set<String> SQL_COLUMNS = Set.copyOf(FIELD_TO_COLUMN.values());

    public static final Set<String> DATE_COLUMNS = Set.of("created_at", "updated_at", "approve_time");

    private PermissionRequestListSpec() {
    }

    /** SQL expression for the UI "request target" display name (BU → VG → OU). */
    public static final String REQUEST_TARGET_EXPR =
            "COALESCE(NULLIF(TRIM(business_unit_name), ''), NULLIF(TRIM(virtual_group_name), ''), "
                    + "NULLIF(TRIM(organization_unit_name), ''), '')";

    public static String sanitizeGroupBy(String groupBy) {
        if (groupBy == null || groupBy.isBlank()) {
            return null;
        }
        String field = groupBy.trim();
        if ("requestTarget".equals(field)) {
            return REQUEST_TARGET_EXPR;
        }
        String col = resolveColumn(field);
        return col != null ? col : null;
    }

    public static String resolveColumn(String field) {
        if (field == null || field.isBlank()) {
            return null;
        }
        return FIELD_TO_COLUMN.get(field.trim());
    }

    public static List<PortalColumnFilterSupport.ColumnFilter> parseFilters(Map<String, Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> canonical = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            String canonicalField = toCanonicalField(e.getKey().trim());
            if (canonicalField == null || "requestTarget".equals(canonicalField)) {
                continue;
            }
            canonical.put(canonicalField, e.getValue());
        }
        List<PortalColumnFilterSupport.ColumnFilter> parsed =
                PortalColumnFilterSupport.parseFilters(canonical, COLUMNS);
        List<PortalColumnFilterSupport.ColumnFilter> out = new ArrayList<>();
        for (PortalColumnFilterSupport.ColumnFilter filter : parsed) {
            String sql = resolveColumn(filter.field());
            if (sql != null) {
                out.add(new PortalColumnFilterSupport.ColumnFilter(sql, filter.operator(), filter.value()));
            }
        }
        return out;
    }

    /**
     * Map a request key (FE name, snake_case, or alias) onto the {@link #COLUMNS} field,
     * or null when the list does not declare it.
     */
    static String toCanonicalField(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (PortalListColumnMeta.find(COLUMNS, raw) != null) {
            return raw;
        }
        String sql = resolveColumn(raw);
        if (sql == null) {
            return null;
        }
        for (PortalListColumnMeta column : COLUMNS) {
            String mapped = resolveColumn(column.field());
            if (sql.equals(mapped)) {
                return column.field();
            }
        }
        return null;
    }

    /**
     * Append AND clauses; args collected into {@code args}. Returns SQL fragment starting with spaces.
     */
    public static String appendFilterSql(List<PortalColumnFilterSupport.ColumnFilter> filters, List<Object> args) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (PortalColumnFilterSupport.ColumnFilter filter : filters) {
            String col = filter.field();
            if (col == null || !SQL_COLUMNS.contains(col)) {
                continue;
            }
            String op = filter.operator().trim();
            String value = filter.value() != null ? filter.value() : "";
            if (DATE_COLUMNS.contains(col)) {
                sb.append(PortalColumnFilterSupport.appendDateFilterSql(col, op, value, args));
                continue;
            }
            switch (op) {
                case "isNull" -> sb.append(" AND (").append(col).append(" IS NULL OR TRIM(COALESCE(")
                        .append(col).append("::text, '')) = '')");
                case "isNotNull" -> sb.append(" AND ").append(col).append(" IS NOT NULL AND TRIM(COALESCE(")
                        .append(col).append("::text, '')) <> ''");
                case "eq" -> {
                    sb.append(" AND LOWER(COALESCE(").append(col).append("::text, '')) = ?");
                    args.add(value.toLowerCase(Locale.ROOT));
                }
                case "ne" -> {
                    sb.append(" AND LOWER(COALESCE(").append(col).append("::text, '')) <> ?");
                    args.add(value.toLowerCase(Locale.ROOT));
                }
                case "contains" -> {
                    sb.append(" AND LOWER(COALESCE(").append(col).append("::text, '')) LIKE ? ESCAPE '\\'");
                    args.add("%" + PortalColumnFilterSupport.escapeLike(value.toLowerCase(Locale.ROOT)) + "%");
                }
                case "notContains" -> {
                    sb.append(" AND LOWER(COALESCE(").append(col).append("::text, '')) NOT LIKE ? ESCAPE '\\'");
                    args.add("%" + PortalColumnFilterSupport.escapeLike(value.toLowerCase(Locale.ROOT)) + "%");
                }
                case "startsWith" -> {
                    sb.append(" AND LOWER(COALESCE(").append(col).append("::text, '')) LIKE ? ESCAPE '\\'");
                    args.add(PortalColumnFilterSupport.escapeLike(value.toLowerCase(Locale.ROOT)) + "%");
                }
                case "endsWith" -> {
                    sb.append(" AND LOWER(COALESCE(").append(col).append("::text, '')) LIKE ? ESCAPE '\\'");
                    args.add("%" + PortalColumnFilterSupport.escapeLike(value.toLowerCase(Locale.ROOT)));
                }
                default -> {
                    // skip unknown op
                }
            }
        }
        return sb.toString();
    }

    public static String resolveOrderBy(String sortField, String sortDirection, String groupBy) {
        String safeGroup = sanitizeGroupBy(groupBy);
        String sortKey = sortField != null ? sortField.trim() : "";
        String col;
        boolean whitelisted;
        if ("requestTarget".equals(sortKey)) {
            col = REQUEST_TARGET_EXPR;
            whitelisted = true;
        } else {
            col = resolveColumn(sortKey);
            whitelisted = col != null;
            if (!whitelisted) {
                col = "created_at";
            }
        }
        Sort.Direction dir;
        if (!whitelisted) {
            dir = Sort.Direction.DESC;
        } else if (sortDirection == null || sortDirection.isBlank()) {
            dir = "created_at".equals(col) ? Sort.Direction.DESC : Sort.Direction.ASC;
        } else {
            dir = "ASC".equalsIgnoreCase(sortDirection) ? Sort.Direction.ASC : Sort.Direction.DESC;
        }
        boolean nullsLast = "created_at".equals(col) || "approve_time".equals(col) || "updated_at".equals(col)
                || REQUEST_TARGET_EXPR.equals(col);
        String order = col + " " + dir.name() + (nullsLast ? " NULLS LAST" : "");
        if (safeGroup != null) {
            return safeGroup + " ASC, " + order;
        }
        return order;
    }

    /**
     * Filter on display target name across BU / VG / OU name columns (Portal approval "requestTarget").
     */
    public static String appendRequestTargetFilterSql(
            Map<String, Map<String, Object>> raw, List<Object> args) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        Map<String, Object> cfg = raw.get("requestTarget");
        if (cfg == null) {
            return "";
        }
        Object opObj = cfg.get("operator");
        String op = opObj != null ? String.valueOf(opObj).trim() : "";
        Object valObj = cfg.get("value");
        String value = valObj != null ? String.valueOf(valObj) : "";
        if (op.isEmpty()) {
            return "";
        }
        PortalListColumnMeta target = PortalListColumnMeta.find(COLUMNS, "requestTarget");
        if (target != null && !target.allowsOperator(op)) {
            throw new IllegalArgumentException(
                    "Operator '" + op + "' is not supported for column 'requestTarget'");
        }
        if (!"isNull".equals(op) && !"isNotNull".equals(op) && value.isBlank()) {
            return "";
        }
        String[] cols = {"business_unit_name", "virtual_group_name", "organization_unit_name"};
        return switch (op) {
            case "isNull" -> " AND (COALESCE(business_unit_name, '') = '' AND COALESCE(virtual_group_name, '') = ''"
                    + " AND COALESCE(organization_unit_name, '') = '')";
            case "isNotNull" -> " AND (COALESCE(business_unit_name, '') <> '' OR COALESCE(virtual_group_name, '') <> ''"
                    + " OR COALESCE(organization_unit_name, '') <> '')";
            case "eq", "ne" -> {
                StringBuilder sb = new StringBuilder(" AND (");
                for (int i = 0; i < cols.length; i++) {
                    if (i > 0) {
                        sb.append("eq".equals(op) ? " OR " : " AND ");
                    }
                    sb.append("LOWER(COALESCE(").append(cols[i]).append(", '')) ")
                            .append("eq".equals(op) ? "= ?" : "<> ?");
                    args.add(value.toLowerCase(Locale.ROOT));
                }
                sb.append(')');
                yield sb.toString();
            }
            case "contains", "notContains", "startsWith", "endsWith" -> {
                String like = switch (op) {
                    case "startsWith" -> PortalColumnFilterSupport.escapeLike(value.toLowerCase(Locale.ROOT)) + "%";
                    case "endsWith" -> "%" + PortalColumnFilterSupport.escapeLike(value.toLowerCase(Locale.ROOT));
                    default -> "%" + PortalColumnFilterSupport.escapeLike(value.toLowerCase(Locale.ROOT)) + "%";
                };
                boolean negate = "notContains".equals(op);
                StringBuilder sb = new StringBuilder(" AND (");
                for (int i = 0; i < cols.length; i++) {
                    if (i > 0) {
                        sb.append(negate ? " AND " : " OR ");
                    }
                    sb.append("LOWER(COALESCE(").append(cols[i]).append(", '')) ")
                            .append(negate ? "NOT LIKE ? ESCAPE '\\'" : "LIKE ? ESCAPE '\\'");
                    args.add(like);
                }
                sb.append(')');
                yield sb.toString();
            }
            default -> throw new IllegalArgumentException(
                    "Operator '" + op + "' is not supported for column 'requestTarget'");
        };
    }

    /** Strip FE-only keys that are not direct columns before {@link #parseFilters}. */
    public static Map<String, Map<String, Object>> withoutRequestTarget(Map<String, Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty() || !raw.containsKey("requestTarget")) {
            return raw;
        }
        Map<String, Map<String, Object>> copy = new java.util.LinkedHashMap<>(raw);
        copy.remove("requestTarget");
        return copy;
    }
}
