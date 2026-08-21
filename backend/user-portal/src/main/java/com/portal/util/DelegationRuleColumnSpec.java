package com.portal.util;

import com.portal.dto.PortalListColumnMeta;
import com.portal.dto.PortalListColumnMeta.Kind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed column declaration for My Delegation Rules. Status and type are closed ENUMs so they
 * may group; identity columns use USER so the people-picker filter matches stored user ids.
 */
public final class DelegationRuleColumnSpec {

    private DelegationRuleColumnSpec() {
    }

    public static List<PortalListColumnMeta> columns() {
        return List.of(
                PortalListColumnMeta.of("delegateId", "delegation.delegateTo", Kind.USER),
                PortalListColumnMeta.withOptions("delegationType", "delegation.delegationType",
                        Kind.ENUM, typeOptions()),
                PortalListColumnMeta.of("startTime", "delegation.startTime", Kind.DATETIME),
                PortalListColumnMeta.of("endTime", "delegation.endTime", Kind.DATETIME),
                PortalListColumnMeta.withOptions("status", "delegation.status", Kind.ENUM, statusOptions()),
                PortalListColumnMeta.of("reason", "delegation.reason", Kind.TEXT),
                PortalListColumnMeta.of("createdAt", "task.createTime", Kind.DATETIME)
        );
    }

    public static ListFilterSql sql() {
        Map<String, PortalListColumnMeta> byField = new LinkedHashMap<>();
        for (PortalListColumnMeta column : columns()) {
            byField.put(column.field(), column);
        }
        return new ListFilterSql(byField, DelegationRuleColumnSpec::sqlFor, "r.id", "r.created_at DESC");
    }

    private static String sqlFor(String field) {
        return switch (field) {
            case "delegateId" -> "r.delegate_id";
            case "delegationType" -> "r.delegation_type";
            case "startTime" -> "r.start_time::text";
            case "endTime" -> "r.end_time::text";
            case "status" -> "r.status";
            case "reason" -> "r.reason";
            case "createdAt" -> "r.created_at::text";
            default -> throw new IllegalArgumentException("Unknown delegation-rule column: " + field);
        };
    }

    private static List<PortalListColumnMeta.Option> typeOptions() {
        return List.of(
                new PortalListColumnMeta.Option("ALL", "delegation.all"),
                new PortalListColumnMeta.Option("PARTIAL", "delegation.partial"),
                new PortalListColumnMeta.Option("TEMPORARY", "delegation.temporary"),
                new PortalListColumnMeta.Option("URGENT", "delegation.urgent")
        );
    }

    private static List<PortalListColumnMeta.Option> statusOptions() {
        return List.of(
                new PortalListColumnMeta.Option("ACTIVE", "delegation.active"),
                new PortalListColumnMeta.Option("INACTIVE", "delegation.inactive"),
                new PortalListColumnMeta.Option("EXPIRED", "delegation.expired"),
                new PortalListColumnMeta.Option("SUSPENDED", "delegation.suspended")
        );
    }
}
