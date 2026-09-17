package com.admin.bi.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Encodes the Superset role IDs attached to a dashboard (Superset {@code dashboard_roles})
 * as the comma-separated column {@code bi_dashboard_registry.superset_role_ids}.
 * <p>
 * Empty / null means the dashboard carries no role restriction in Superset.
 */
public final class SupersetRoleIdCsv {

    private SupersetRoleIdCsv() {
    }

    /** Parse the stored CSV into a list of role IDs; blank input yields an empty list. */
    public static List<Integer> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptyList();
        }
        List<Integer> ids = new ArrayList<>();
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            ids.add(Integer.parseInt(trimmed));
        }
        return ids;
    }

    /** Format role IDs as a sorted, de-duplicated CSV; empty input yields {@code null} (no restriction). */
    public static String format(Collection<Integer> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return null;
        }
        return new TreeSet<>(roleIds).stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }
}
