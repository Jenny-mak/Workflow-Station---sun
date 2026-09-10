package com.admin.dto.list;

/**
 * Left-rail Function Unit group on the Table Structure page. {@code key} is the Function Unit
 * code, or {@code __common__} for tables with no link. Keying by code (not by the per-version
 * {@code sys_function_units.id}) keeps one rail entry per unit no matter how many versions of it
 * were published. Count is distinct tables in that group (a table linked to two units appears in
 * both, but a table linked to two versions of one unit is counted once).
 */
public record RelationTableFuGroup(String key, String label, long count) {
}
