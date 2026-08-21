package com.portal.util;

import com.portal.dto.PortalListColumnMeta;
import com.portal.dto.PortalListColumnMeta.Kind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed column declaration for permission request / approval shared lists. Target is a
 * COALESCE of stored names (honest text predicate); identity columns are USER.
 */
public final class PermissionRequestColumnSpec {

    /** SQL expression for the composed target label shown in the grid. */
    public static final String TARGET_SQL = "COALESCE("
            + "NULLIF(TRIM(p.business_unit_name), ''),"
            + "NULLIF(TRIM(p.virtual_group_name), ''),"
            + "NULLIF(TRIM(p.organization_unit_name), ''),"
            + "'-')";

    private PermissionRequestColumnSpec() {
    }

    public static List<PortalListColumnMeta> columns() {
        return List.of(
                PortalListColumnMeta.withOptions("requestType", "permission.requestType",
                        Kind.ENUM, typeOptions()),
                PortalListColumnMeta.of("targetName", "permission.requestTarget", Kind.TEXT),
                PortalListColumnMeta.of("applicantId", "permission.beneficiaryColumn", Kind.USER),
                PortalListColumnMeta.of("submittedByUserId", "permission.submittedByColumn", Kind.USER),
                PortalListColumnMeta.of("reason", "permission.reason", Kind.TEXT),
                PortalListColumnMeta.withOptions("status", "permission.status", Kind.ENUM, statusOptions()),
                PortalListColumnMeta.of("approverComment", "permission.approverComment", Kind.TEXT),
                PortalListColumnMeta.of("createdAt", "permission.applyTime", Kind.DATETIME),
                PortalListColumnMeta.of("approvedAt", "permission.approvedAt", Kind.DATETIME)
        );
    }

    public static ListFilterSql sql() {
        Map<String, PortalListColumnMeta> byField = new LinkedHashMap<>();
        for (PortalListColumnMeta column : columns()) {
            byField.put(column.field(), column);
        }
        return new ListFilterSql(byField, PermissionRequestColumnSpec::sqlFor, "p.id", "p.created_at DESC");
    }

    private static String sqlFor(String field) {
        return switch (field) {
            case "requestType" -> "p.request_type";
            case "targetName" -> TARGET_SQL;
            case "applicantId" -> "p.applicant_id";
            case "submittedByUserId" -> "p.submitted_by_user_id";
            case "reason" -> "p.reason";
            case "status" -> "p.status";
            case "approverComment" -> "p.approve_comment";
            case "createdAt" -> "p.created_at::text";
            case "approvedAt" -> "p.approve_time::text";
            default -> throw new IllegalArgumentException("Unknown permission-request column: " + field);
        };
    }

    private static List<PortalListColumnMeta.Option> typeOptions() {
        return List.of(
                new PortalListColumnMeta.Option("ROLE_ASSIGNMENT", "permission.roleAssignment"),
                new PortalListColumnMeta.Option("VIRTUAL_GROUP_JOIN", "permission.virtualGroupJoin"),
                new PortalListColumnMeta.Option("BUSINESS_UNIT_JOIN", "permission.businessUnitJoin"),
                new PortalListColumnMeta.Option("BUSINESS_UNIT_ROLE_REMOVAL", "permission.businessUnitRoleRemoval"),
                new PortalListColumnMeta.Option("BUSINESS_UNIT_EXIT", "permission.businessUnitExit")
        );
    }

    private static List<PortalListColumnMeta.Option> statusOptions() {
        return List.of(
                new PortalListColumnMeta.Option("PENDING", "permission.pending"),
                new PortalListColumnMeta.Option("APPROVED", "permission.approved"),
                new PortalListColumnMeta.Option("REJECTED", "permission.rejected"),
                new PortalListColumnMeta.Option("CANCELLED", "permission.cancelled")
        );
    }
}
