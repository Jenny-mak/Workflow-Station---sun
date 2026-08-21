package com.portal.util;

import com.portal.dto.PortalListColumnMeta;
import com.portal.dto.PortalListColumnMeta.Kind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed column declaration for Delegation Audit. Operation type/result are free text (writers
 * emit several verbs); identity columns use USER for people-picker filters.
 */
public final class DelegationAuditColumnSpec {

    private DelegationAuditColumnSpec() {
    }

    public static List<PortalListColumnMeta> columns() {
        return List.of(
                PortalListColumnMeta.of("operationType", "delegation.operationType", Kind.TEXT),
                PortalListColumnMeta.of("delegatorId", "delegation.delegator", Kind.USER),
                PortalListColumnMeta.of("delegateId", "delegation.delegate", Kind.USER),
                PortalListColumnMeta.of("taskId", "delegation.taskId", Kind.TEXT),
                PortalListColumnMeta.of("operationResult", "delegation.result", Kind.TEXT),
                PortalListColumnMeta.of("createdAt", "delegation.time", Kind.DATETIME)
        );
    }

    public static ListFilterSql sql() {
        Map<String, PortalListColumnMeta> byField = new LinkedHashMap<>();
        for (PortalListColumnMeta column : columns()) {
            byField.put(column.field(), column);
        }
        return new ListFilterSql(byField, DelegationAuditColumnSpec::sqlFor, "a.id", "a.created_at DESC");
    }

    private static String sqlFor(String field) {
        return switch (field) {
            case "operationType" -> "a.operation_type";
            case "delegatorId" -> "a.delegator_id";
            case "delegateId" -> "a.delegate_id";
            case "taskId" -> "a.task_id";
            case "operationResult" -> "a.operation_result";
            case "createdAt" -> "a.created_at::text";
            default -> throw new IllegalArgumentException("Unknown delegation-audit column: " + field);
        };
    }
}
