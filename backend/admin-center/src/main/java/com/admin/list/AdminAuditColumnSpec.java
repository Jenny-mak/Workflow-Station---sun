package com.admin.list;

import com.platform.common.list.ListColumnMeta;
import com.platform.common.list.ListColumnMeta.Kind;
import com.platform.common.list.ListFilterSql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin Center audit log columns. Toolbar filters (action, resource, operator, result,
 * IP, resource id, date range) stay outside this spec and AND with header filters.
 */
public final class AdminAuditColumnSpec {

    private AdminAuditColumnSpec() {
    }

    public static List<ListColumnMeta> columns() {
        return List.of(
                ListColumnMeta.withOptions("action", "audit.actionType", Kind.ENUM, actionOptions()),
                ListColumnMeta.withOptions("resourceType", "audit.resourceType", Kind.ENUM, resourceOptions()),
                ListColumnMeta.of("username", "audit.operator", Kind.TEXT),
                ListColumnMeta.of("ipAddress", "audit.ipAddress", Kind.TEXT),
                ListColumnMeta.withOptions("result", "audit.result", Kind.ENUM, resultOptions()),
                ListColumnMeta.of("duration", "audit.duration", Kind.NUMBER),
                ListColumnMeta.of("createdAt", "audit.time", Kind.DATETIME)
        );
    }

    public static ListFilterSql sql() {
        Map<String, ListColumnMeta> byField = new LinkedHashMap<>();
        for (ListColumnMeta column : columns()) {
            byField.put(column.field(), column);
        }
        return new ListFilterSql(byField, AdminAuditColumnSpec::sqlFor, "al.id", "al.timestamp DESC");
    }

    /**
     * Action SQL matches {@link com.admin.enums.AuditActionConverter}: legacy strings
     * collapse to CREATE/UPDATE/DELETE/QUERY so header filter and sort see the
     * same four values the table cells show.
     */
    static String sqlFor(String field) {
        return switch (field) {
            case "action" -> ACTION_SQL;
            case "resourceType" -> "al.resource_type";
            case "username" -> "al.user_name";
            case "ipAddress" -> "al.ip_address";
            case "result" -> "CASE WHEN al.success THEN 'SUCCESS' ELSE 'FAILED' END";
            case "duration" -> "al.duration_ms::text";
            case "createdAt" -> "al.timestamp::text";
            default -> throw new IllegalArgumentException("Unknown admin-audit column: " + field);
        };
    }

    private static final String ACTION_SQL = "CASE"
            + " WHEN al.action IN ('USER_CREATED','ROLE_CREATED','DATA_CREATED','CONFIG_CREATED',"
            + "'DATA_IMPORTED','BACKUP_CREATED') THEN 'CREATE'"
            + " WHEN al.action IN ('USER_UPDATED','USER_LOCKED','USER_UNLOCKED',"
            + "'PASSWORD_CHANGED','PASSWORD_RESET','ROLE_UPDATED','DATA_UPDATED','CONFIG_UPDATED',"
            + "'PERMISSION_GRANTED','PERMISSION_REVOKED','ROLE_ASSIGNED','ROLE_UNASSIGNED',"
            + "'USER_LOGIN','USER_LOGOUT','USER_LOGIN_FAILED','DATA_EXPORTED',"
            + "'SYSTEM_STARTUP','SYSTEM_SHUTDOWN','BACKUP_RESTORED') THEN 'UPDATE'"
            + " WHEN al.action IN ('USER_DELETED','ROLE_DELETED','DATA_DELETED','CONFIG_DELETED')"
            + " THEN 'DELETE'"
            + " WHEN al.action = 'DATA_QUERIED' THEN 'QUERY'"
            + " ELSE al.action END";

    private static List<ListColumnMeta.Option> actionOptions() {
        return List.of(
                new ListColumnMeta.Option("CREATE", "audit.actionCREATE"),
                new ListColumnMeta.Option("UPDATE", "audit.actionUPDATE"),
                new ListColumnMeta.Option("DELETE", "audit.actionDELETE"),
                new ListColumnMeta.Option("QUERY", "audit.actionQUERY")
        );
    }

    private static List<ListColumnMeta.Option> resourceOptions() {
        return List.of(
                new ListColumnMeta.Option("AUTH", "common.auth"),
                new ListColumnMeta.Option("AUTOMATION_FLOW", "menu.automationFlows"),
                new ListColumnMeta.Option("AUTOMATION_PIECE", "menu.automationPieces"),
                new ListColumnMeta.Option("BI_ASSIGNMENT", "menu.biAudienceAssignment"),
                new ListColumnMeta.Option("BI_DATA_VIEW_ASSIGNMENT", "menu.biDataViewAssignment"),
                new ListColumnMeta.Option("BI_DASHBOARD", "menu.biDashboardRegistry"),
                new ListColumnMeta.Option("BI_RBAC", "menu.biRbacMapping"),
                new ListColumnMeta.Option("BUSINESS_UNIT", "menu.organization"),
                new ListColumnMeta.Option("RELATION_TABLE", "menu.tableStructure"),
                new ListColumnMeta.Option("RELATION_TABLE_ROW", "menu.tableData"),
                new ListColumnMeta.Option("ROLE", "menu.roleManagement"),
                new ListColumnMeta.Option("USER", "menu.userList"),
                new ListColumnMeta.Option("VIRTUAL_GROUP", "menu.virtualGroup")
        );
    }

    private static List<ListColumnMeta.Option> resultOptions() {
        return List.of(
                new ListColumnMeta.Option("SUCCESS", "audit.success"),
                new ListColumnMeta.Option("FAILED", "audit.failed")
        );
    }
}
