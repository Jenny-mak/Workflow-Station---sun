package com.portal.util;

import com.platform.common.list.ListColumnMeta;
import com.platform.common.list.ListColumnMeta.Kind;

import java.util.ArrayList;
import java.util.List;

/**
 * Delegated tab: To Do columns plus who delegated and whether the overlay
 * target is a user or a BU+Role pair.
 */
public final class DelegatedTaskColumnSpec {

    private DelegatedTaskColumnSpec() {
    }

    public static List<ListColumnMeta> columns() {
        List<ListColumnMeta> columns = new ArrayList<>();
        for (ListColumnMeta column : TodoTaskColumnSpec.columns()) {
            columns.add(column);
            if ("taskName".equals(column.field())) {
                columns.add(ListColumnMeta.of("delegatorId", "delegation.delegator", Kind.USER));
                columns.add(ListColumnMeta.withOptions(
                        "delegatedTargetType",
                        "delegation.delegatedTargetKind",
                        Kind.ENUM,
                        targetOptions()));
            }
        }
        return List.copyOf(columns);
    }

    private static List<ListColumnMeta.Option> targetOptions() {
        return List.of(
                new ListColumnMeta.Option("USER", "delegation.specifyUser"),
                new ListColumnMeta.Option("BU_ROLE", "delegation.specifyBuRole"));
    }
}
