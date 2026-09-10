package com.admin.list;

import com.platform.common.enums.RelationTableStatus;
import com.platform.common.list.ListColumnMeta;
import com.platform.common.list.ListColumnMeta.Kind;
import com.platform.common.list.ListFilterSql;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relation Table Structure columns. Left-rail Function Unit selection stays outside
 * this spec (toolbar {@code functionUnitCode}) and AND with header filters.
 * Outer alias is {@code t} on {@code rt_table_definitions}.
 */
public final class RelationTableStructureColumnSpec {

    private RelationTableStructureColumnSpec() {
    }

    public static List<ListColumnMeta> columns() {
        return List.of(
                ListColumnMeta.of("displayName", "relationTable.structure.displayName", Kind.TEXT),
                ListColumnMeta.of("currentVersion", "relationTable.structure.currentVersion", Kind.NUMBER),
                ListColumnMeta.withOptions("status", "relationTable.structure.status", Kind.ENUM, statusOptions()),
                ListColumnMeta.of("enabled", "relationTable.structure.enabled", Kind.BOOLEAN),
                ListColumnMeta.of("portalVisible", "relationTable.structure.portalVisible", Kind.BOOLEAN),
                ListColumnMeta.of("createdAt", "relationTable.structure.createdAt", Kind.DATETIME),
                ListColumnMeta.of("updatedAt", "relationTable.structure.updatedAt", Kind.DATETIME)
        );
    }

    public static ListFilterSql sql() {
        Map<String, ListColumnMeta> byField = new LinkedHashMap<>();
        for (ListColumnMeta column : columns()) {
            byField.put(column.field(), column);
        }
        return new ListFilterSql(byField, RelationTableStructureColumnSpec::sqlFor, "t.id", "t.updated_at DESC");
    }

    static String sqlFor(String field) {
        return switch (field) {
            case "displayName" -> "t.display_name";
            case "currentVersion" -> "t.current_version::text";
            case "status" -> "t.status";
            case "enabled" -> "t.enabled::text";
            case "portalVisible" -> "t.portal_visible::text";
            case "createdAt" -> "t.created_at::text";
            case "updatedAt" -> "t.updated_at::text";
            default -> throw new IllegalArgumentException("Unknown relation-table-structure column: " + field);
        };
    }

    private static List<ListColumnMeta.Option> statusOptions() {
        return Arrays.stream(RelationTableStatus.values())
                .map(status -> new ListColumnMeta.Option(
                        status.name(), "relationTable.structure.status" + title(status.name())))
                .toList();
    }

    private static String title(String name) {
        return name.charAt(0) + name.substring(1).toLowerCase();
    }
}
